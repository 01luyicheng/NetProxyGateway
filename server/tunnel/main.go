package main

import (
	"bytes"
	"context"
	"crypto/subtle"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"net/url"
	"os"
	"os/signal"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/gorilla/websocket"
	"github.com/netproxy/shared/httpclient"
	"github.com/netproxy/shared/stringutil"
)

// Config holds the tunnel server configuration.
type Config struct {
	Addr              string
	APIEndpoint       string
	InternalAPIKey    string
	StatsToken        string
	AllowedOrigins    map[string]struct{}
	TLSCert           string
	TLSKey            string
	EnableTLS         bool
	HeartbeatInterval time.Duration
	HeartbeatTimeout  time.Duration
}

const (
	defaultIncomingMessageRatePerSecond = 200.0
	defaultIncomingMessageBurst         = 400
	defaultNotifyStatusMaxAttempts      = 3
	defaultNotifyStatusBaseBackoff      = 100 * time.Millisecond
	defaultNotifyStatusMaxBackoffShift  = 30
	maxHTTPHeaderBytes                  = 1 << 20
)

type tokenBucketLimiter struct {
	mu            sync.Mutex
	ratePerSecond float64
	capacity      float64
	tokens        float64
	lastRefill    time.Time
	now           func() time.Time
}

func newTokenBucketLimiter(ratePerSecond float64, burst int) *tokenBucketLimiter {
	return newTokenBucketLimiterWithClock(ratePerSecond, burst, time.Now)
}

func newTokenBucketLimiterWithClock(ratePerSecond float64, burst int, nowFn func() time.Time) *tokenBucketLimiter {
	if ratePerSecond < 0 {
		ratePerSecond = 0
	}
	if burst <= 0 {
		burst = 1
	}
	if nowFn == nil {
		nowFn = time.Now
	}

	now := nowFn()
	capacity := float64(burst)

	return &tokenBucketLimiter{
		ratePerSecond: ratePerSecond,
		capacity:      capacity,
		tokens:        capacity,
		lastRefill:    now,
		now:           nowFn,
	}
}

func (l *tokenBucketLimiter) Allow() bool {
	if l == nil {
		return true
	}

	l.mu.Lock()
	defer l.mu.Unlock()

	now := l.now()
	if now.Before(l.lastRefill) {
		now = l.lastRefill
	}

	if elapsed := now.Sub(l.lastRefill).Seconds(); elapsed > 0 && l.ratePerSecond > 0 {
		l.tokens += elapsed * l.ratePerSecond
		if l.tokens > l.capacity {
			l.tokens = l.capacity
		}
	}
	l.lastRefill = now

	if l.tokens < 1 {
		return false
	}

	l.tokens--
	return true
}

// TunnelConn represents a tunnel connection.
type TunnelConn struct {
	DeviceID       string
	Conn           *websocket.Conn
	LastPing       time.Time
	mu             sync.RWMutex
	connMu         sync.Mutex
	sendChan       chan []byte
	messageLimiter *tokenBucketLimiter
	closeChan      chan struct{}
	closeOnce      sync.Once
	closed         bool
}

var errTunnelClosed = errors.New("tunnel closed")

// NewTunnelConn creates a new tunnel connection.
func NewTunnelConn(deviceID string, conn *websocket.Conn) *TunnelConn {
	return &TunnelConn{
		DeviceID: deviceID,
		Conn:     conn,
		LastPing: time.Now(),
		sendChan: make(chan []byte, 100),
		messageLimiter: newTokenBucketLimiter(
			defaultIncomingMessageRatePerSecond,
			defaultIncomingMessageBurst,
		),
		closeChan: make(chan struct{}),
	}
}

func (t *TunnelConn) allowIncomingMessage() bool {
	if t == nil || t.messageLimiter == nil {
		return true
	}
	return t.messageLimiter.Allow()
}

// UpdatePing updates the last ping time.
func (t *TunnelConn) UpdatePing() {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.LastPing = time.Now()
}

// IsAlive checks if the connection is alive.
func (t *TunnelConn) IsAlive(timeout time.Duration) bool {
	t.mu.RLock()
	defer t.mu.RUnlock()
	return time.Since(t.LastPing) < timeout
}

// Send sends a message through the tunnel.
func (t *TunnelConn) Send(data []byte) error {
	select {
	case t.sendChan <- data:
		return nil
	case <-t.closeChan:
		return fmt.Errorf("tunnel closed")
	default:
		return fmt.Errorf("send buffer full")
	}
}

// Close closes the tunnel connection.
func (t *TunnelConn) Close() {
	t.closeOnce.Do(func() {
		t.connMu.Lock()
		t.closed = true
		close(t.closeChan)
		if t.Conn != nil {
			_ = t.Conn.Close()
		}
		t.connMu.Unlock()
	})
}

// WritePing sends a WebSocket ping control message with the specified deadline.
// connMu prevents racing with Close(): without the lock, Close() could set t.closed
// and close the underlying connection between WritePing's nil-check and the
// WriteControl call, causing a use-after-close panic. WriteControl is safe to
// call concurrently with WriteMessage per gorilla/websocket docs.
func (t *TunnelConn) WritePing(deadline time.Time) error {
	t.connMu.Lock()
	defer t.connMu.Unlock()

	if t.closed || t.Conn == nil {
		return errTunnelClosed
	}

	return t.Conn.WriteControl(websocket.PingMessage, []byte{}, deadline)
}

// TunnelManager manages tunnel connections.
type TunnelManager struct {
	tunnels    map[string]*TunnelConn
	mu         sync.RWMutex
	config     *Config
	httpClient *http.Client
	ctx        context.Context
	cancel     context.CancelFunc
	wg         sync.WaitGroup
	// stopMu protects stopped and coordinates with wg.Add in notifyDeviceStatus.
	stopMu sync.Mutex
	// stopped is set to true after Stop() has been called at least once.
	stopped bool
}

// NewTunnelManager creates a new tunnel manager.
func NewTunnelManager(config *Config) *TunnelManager {
	ctx, cancel := context.WithCancel(context.Background())
	return &TunnelManager{
		tunnels:    make(map[string]*TunnelConn),
		config:     config,
		httpClient: &http.Client{Timeout: 5 * time.Second},
		ctx:        ctx,
		cancel:     cancel,
	}
}

// Stop stops the tunnel manager, cancels in-flight notifications, and waits
// for background goroutines to finish (with a 30-second timeout).
// Stop is safe to call multiple times; subsequent calls return immediately.
func (m *TunnelManager) Stop() {
	m.stopMu.Lock()
	if m.stopped {
		m.stopMu.Unlock()
		return
	}
	m.stopped = true
	m.cancel()
	m.stopMu.Unlock()

	done := make(chan struct{})
	go func() {
		defer close(done)
		m.wg.Wait()
	}()

	select {
	case <-done:
	case <-time.After(30 * time.Second):
		// 超时后记录日志但不阻塞关闭流程
		// 此时等待 wg.Wait() 的 goroutine 会泄漏，但进程即将退出，无实际影响
		log.Printf("TunnelManager.Stop: timeout waiting for goroutines to finish")
	}
}

// Register registers a new tunnel for a device.
func (m *TunnelManager) Register(deviceID string, conn *websocket.Conn) *TunnelConn {
	tunnel := NewTunnelConn(deviceID, conn)

	m.mu.Lock()
	defer m.mu.Unlock()
	if old, ok := m.tunnels[deviceID]; ok {
		old.Close()
	}
	m.tunnels[deviceID] = tunnel

	log.Printf("Tunnel registered for device: %s", deviceID)

	m.wg.Add(1)
	go m.notifyDeviceStatus(deviceID, "online", "")

	return tunnel
}

// Unregister unregisters a tunnel for a device.
// The optional tunnel parameter enables an identity check: the entry is
// removed only if the map still holds the same *TunnelConn object.  This
// prevents a concurrently registered replacement tunnel from being closed
// and deleted by mistake.
func (m *TunnelManager) Unregister(deviceID string, tunnel *TunnelConn) {
	removed := false

	m.mu.Lock()
	if current, ok := m.tunnels[deviceID]; ok && (tunnel == nil || current == tunnel) {
		current.Close()
		delete(m.tunnels, deviceID)
		removed = true
	}
	m.mu.Unlock()

	if !removed {
		return
	}

	log.Printf("Tunnel unregistered for device: %s", deviceID)

	m.wg.Add(1)
	go m.notifyDeviceStatus(deviceID, "offline", "")
}

// Get retrieves a tunnel by device ID.
func (m *TunnelManager) Get(deviceID string) (*TunnelConn, bool) {
	m.mu.RLock()
	tunnel, ok := m.tunnels[deviceID]
	m.mu.RUnlock()
	return tunnel, ok
}

// notifyDeviceStatus notifies the API of a device status change.
func (m *TunnelManager) notifyDeviceStatus(deviceID, status, tunnelAddr string) {
	m.stopMu.Lock()
	if m.stopped {
		m.stopMu.Unlock()
		m.wg.Done()
		return
	}
	m.stopMu.Unlock()

	defer m.wg.Done()
	payload := map[string]string{
		"device_id":   deviceID,
		"status":      status,
		"tunnel_addr": tunnelAddr,
	}

	data, err := json.Marshal(payload)
	if err != nil {
		log.Printf("Failed to marshal device status payload: %v", err)
		return
	}

	for attempt := 1; attempt <= defaultNotifyStatusMaxAttempts; attempt++ {
		select {
		case <-m.ctx.Done():
			return
		default:
		}
		req, err := http.NewRequestWithContext(
			m.ctx,
			http.MethodPost,
			m.config.APIEndpoint+"/api/device/status",
			bytes.NewReader(data),
		)
		if err != nil {
			log.Printf("Failed to build device status request: %v", err)
			return
		}
		req.Header.Set("Content-Type", "application/json")
		if m.config.InternalAPIKey != "" {
			req.Header.Set("X-Internal-API-Key", m.config.InternalAPIKey)
		}

		resp, err := m.httpClient.Do(req)
		if err != nil {
			if attempt < defaultNotifyStatusMaxAttempts {
				select {
				case <-m.ctx.Done():
					return
				case <-time.After(notifyStatusBackoff(attempt)):
					continue
				}
			}
			log.Printf("Failed to notify device status after %d attempts: %v", attempt, err)
			return
		}

		statusCode := resp.StatusCode
		_, _ = io.Copy(io.Discard, resp.Body)
		_ = resp.Body.Close()

		if statusCode >= http.StatusOK && statusCode < http.StatusMultipleChoices {
			return
		}

		if shouldRetryNotifyStatusCode(statusCode) && attempt < defaultNotifyStatusMaxAttempts {
			select {
			case <-m.ctx.Done():
				return
			case <-time.After(notifyStatusBackoff(attempt)):
				continue
			}
		}

		if shouldRetryNotifyStatusCode(statusCode) {
			log.Printf("Failed to notify device status after %d attempts: status code %d", attempt, statusCode)
		} else {
			log.Printf("Failed to notify device status: status code %d", statusCode)
		}
		return
	}
}

func notifyStatusBackoff(attempt int) time.Duration {
	if attempt <= 0 {
		attempt = 1
	}
	if attempt > defaultNotifyStatusMaxBackoffShift {
		attempt = defaultNotifyStatusMaxBackoffShift
	}
	multiplier := int64(1) << (attempt - 1)
	return defaultNotifyStatusBaseBackoff * time.Duration(multiplier)
}

func shouldRetryNotifyStatusCode(statusCode int) bool {
	return statusCode == http.StatusTooManyRequests || statusCode >= http.StatusInternalServerError
}

// cleanupDeadTunnels periodically cleans up dead tunnel connections.
// The caller must call m.wg.Add(1) before starting the goroutine.
func (m *TunnelManager) cleanupDeadTunnels() {
	defer m.wg.Done()

	ticker := time.NewTicker(30 * time.Second)
	defer ticker.Stop()

	for {
		select {
		case <-m.ctx.Done():
			return
		case <-ticker.C:
		}

		m.stopMu.Lock()
		if m.stopped {
			m.stopMu.Unlock()
			return
		}
		m.stopMu.Unlock()

		m.cleanupDeadTunnelsOnce()
	}
}

// cleanupDeadTunnelsOnce performs a single dead tunnel cleanup (for testing).
func (m *TunnelManager) cleanupDeadTunnelsOnce() {
	m.stopMu.Lock()
	if m.stopped {
		m.stopMu.Unlock()
		return
	}
	m.stopMu.Unlock()

	var deadTunnels []*TunnelConn
	var deadIDs []string

	m.mu.Lock()
	for deviceID, tunnel := range m.tunnels {
		if !tunnel.IsAlive(m.config.HeartbeatTimeout) {
			log.Printf("Cleaning up dead tunnel for device: %s", deviceID)
			// Identity check: only remove if the tunnel in the map is still
			// the same object we observed.  Without this, a concurrently
			// registered replacement tunnel could be deleted by mistake.
			if current, ok := m.tunnels[deviceID]; ok && current == tunnel {
				deadTunnels = append(deadTunnels, tunnel)
				deadIDs = append(deadIDs, deviceID)
				delete(m.tunnels, deviceID)
			}
		}
	}
	m.mu.Unlock()

	for i, tunnel := range deadTunnels {
		tunnel.Close()
		m.wg.Add(1)
		go m.notifyDeviceStatus(deadIDs[i], "offline", "")
	}
}

// Server is the WebSocket tunnel server.
type Server struct {
	manager    *TunnelManager
	upgrader   websocket.Upgrader
	config     *Config
	httpClient *http.Client
	httpServer *http.Server
}

// NewServer creates a new tunnel server.
func NewServer(config *Config) *Server {
	server := &Server{
		manager: NewTunnelManager(config),
		config:  config,
		httpClient: &http.Client{
			Timeout: 10 * time.Second,
		},
	}

	server.upgrader = websocket.Upgrader{
		CheckOrigin:     server.checkOrigin,
		ReadBufferSize:  64 * 1024,
		WriteBufferSize: 64 * 1024,
	}

	return server
}

func (s *Server) checkOrigin(r *http.Request) bool {
	origin := strings.TrimSpace(r.Header.Get("Origin"))
	if origin == "" {
		if s == nil || s.config == nil {
			return true
		}
		return len(s.config.AllowedOrigins) == 0
	}

	normalizedOrigin, host, err := normalizeOrigin(origin)
	if err != nil {
		return false
	}

	if isLocalhostHost(host) {
		return true
	}

	if s == nil || s.config == nil || len(s.config.AllowedOrigins) == 0 {
		return false
	}

	_, allowed := s.config.AllowedOrigins[normalizedOrigin]
	return allowed
}

func isLocalhostHost(host string) bool {
	h := strings.ToLower(strings.TrimSpace(host))
	if parsedHost, _, err := net.SplitHostPort(h); err == nil {
		h = parsedHost
	}
	h = strings.Trim(h, "[]")

	if h == "localhost" || h == "127.0.0.1" || h == "::1" {
		return true
	}

	ip := net.ParseIP(h)
	return ip != nil && ip.IsLoopback()
}

func normalizeOrigin(origin string) (string, string, error) {
	parsed, err := url.Parse(strings.TrimSpace(origin))
	if err != nil {
		return "", "", err
	}
	if parsed.Scheme == "" || parsed.Host == "" {
		return "", "", fmt.Errorf("origin must include scheme and host")
	}

	scheme := strings.ToLower(parsed.Scheme)
	if scheme != "http" && scheme != "https" {
		return "", "", fmt.Errorf("origin scheme must be http or https")
	}
	hostname := strings.ToLower(parsed.Hostname())
	if hostname == "" {
		return "", "", fmt.Errorf("origin host is empty")
	}

	port := parsed.Port()
	normalizedHost := hostname
	if port != "" && !((scheme == "http" && port == "80") || (scheme == "https" && port == "443")) {
		normalizedHost = net.JoinHostPort(hostname, port)
	}

	return scheme + "://" + normalizedHost, hostname, nil
}

func parseAllowedOrigins(raw string) map[string]struct{} {
	allowedOrigins := make(map[string]struct{})
	for _, item := range strings.Split(raw, ",") {
		origin := strings.TrimSpace(item)
		if origin == "" {
			continue
		}

		normalizedOrigin, _, err := normalizeOrigin(origin)
		if err != nil {
			log.Printf("Warning: ignoring invalid allowed origin %q: %v", origin, err)
			continue
		}

		allowedOrigins[normalizedOrigin] = struct{}{}
	}
	return allowedOrigins
}

// handleTunnel handles a WebSocket tunnel connection.
func (s *Server) handleTunnel(w http.ResponseWriter, r *http.Request) {
	deviceID := r.URL.Query().Get("device_id")
	token := r.Header.Get("X-Session-Token")

	if deviceID == "" || token == "" {
		http.Error(w, "missing device_id or token", http.StatusBadRequest)
		return
	}

	valid, err := s.validateDeviceToken(deviceID, token)
	if err != nil {
		log.Printf("Token validation failed: %v", err)
		http.Error(w, "token validation error", http.StatusInternalServerError)
		return
	}
	if !valid {
		http.Error(w, "invalid credentials", http.StatusUnauthorized)
		return
	}

	conn, err := s.upgrader.Upgrade(w, r, nil)
	if err != nil {
		log.Printf("Failed to upgrade connection: %v", err)
		return
	}
	defer conn.Close()

	tunnel := s.manager.Register(deviceID, conn)
	defer s.manager.Unregister(deviceID, tunnel)

	stopHeartbeat := make(chan struct{})
	go s.heartbeat(tunnel, stopHeartbeat)

	go s.sendLoop(tunnel)

	s.readLoop(tunnel)

	close(stopHeartbeat)
}

// validateDeviceToken validates a device token by calling the API service.
func (s *Server) validateDeviceToken(deviceID, token string) (bool, error) {
	payload := map[string]string{
		"device_id": deviceID,
		"token":     token,
	}

	var result struct {
		Valid bool `json:"valid"`
	}

	if err := httpclient.PostJSON(s.httpClient, s.config.APIEndpoint+"/api/session/validate", s.config.InternalAPIKey, payload, &result); err != nil {
		return false, err
	}

	return result.Valid, nil
}

// heartbeat sends periodic ping checks to the tunnel.
func (s *Server) heartbeat(tunnel *TunnelConn, stop chan struct{}) {
	ticker := time.NewTicker(s.config.HeartbeatInterval)
	defer ticker.Stop()

	for {
		select {
		case <-ticker.C:
			if !tunnel.IsAlive(s.config.HeartbeatTimeout) {
				log.Printf("Heartbeat timeout for device: %s", tunnel.DeviceID)
				tunnel.Close()
				return
			}

			if err := tunnel.WritePing(time.Now().Add(10 * time.Second)); err != nil {
				if errors.Is(err, errTunnelClosed) {
					return
				}
				log.Printf("Failed to send ping: %v", err)
				tunnel.Close()
				return
			}

		case <-stop:
			return
		}
	}
}

// sendLoop sends messages from the send channel to the tunnel.
// connMu protects WriteMessage from racing with Close() and WritePing.
// Without the lock, Close() could set t.closed and close the underlying
// connection between the nil-check and the WriteMessage call, causing a
// use-after-close panic. gorilla/websocket requires all WriteMessage calls
// to be serialized.
func (s *Server) sendLoop(tunnel *TunnelConn) {
	for {
		select {
		case data := <-tunnel.sendChan:
			tunnel.connMu.Lock()
			if tunnel.closed || tunnel.Conn == nil {
				tunnel.connMu.Unlock()
				return
			}
			tunnel.Conn.SetWriteDeadline(time.Now().Add(10 * time.Second))
			err := tunnel.Conn.WriteMessage(websocket.BinaryMessage, data)
			tunnel.connMu.Unlock()
			if err != nil {
				log.Printf("Failed to write message: %v", err)
				tunnel.Close()
				return
			}
		case <-tunnel.closeChan:
			return
		}
	}
}

// readLoop reads messages from the tunnel.
func (s *Server) readLoop(tunnel *TunnelConn) {
	tunnel.Conn.SetPongHandler(func(string) error {
		tunnel.UpdatePing()
		return nil
	})

	for {
		messageType, data, err := tunnel.Conn.ReadMessage()
		if err != nil {
			if websocket.IsUnexpectedCloseError(err, websocket.CloseGoingAway, websocket.CloseAbnormalClosure) {
				log.Printf("WebSocket error for device %s: %v", tunnel.DeviceID, err)
			}
			return
		}

		tunnel.UpdatePing()

		if messageType == websocket.BinaryMessage || messageType == websocket.TextMessage {
			if !tunnel.allowIncomingMessage() {
				log.Printf("Incoming message rate limit exceeded for device: %s", tunnel.DeviceID)
				tunnel.Close()
				return
			}

			s.handleMessage(tunnel, data)
		}
	}
}

// handleMessage processes a message from the tunnel.
func (s *Server) handleMessage(tunnel *TunnelConn, data []byte) {
	var msg struct {
		Type string          `json:"type"`
		Data json.RawMessage `json:"data"`
	}

	if err := json.Unmarshal(data, &msg); err != nil {
		log.Printf("Failed to unmarshal message: %v", err)
		return
	}

	switch msg.Type {
	case "connect_response":
		s.handleConnectResponse(tunnel, msg.Data)
	case "data":
		s.handleData(tunnel, msg.Data)
	case "disconnect":
		s.handleDisconnect(tunnel, msg.Data)
	default:
		log.Printf("Unknown message type: %s", msg.Type)
	}
}

// handleConnectResponse processes a connect response from the device.
func (s *Server) handleConnectResponse(tunnel *TunnelConn, data json.RawMessage) {
	var resp struct {
		StreamID string `json:"stream_id"`
		Success  bool   `json:"success"`
		Error    string `json:"error,omitempty"`
	}

	if err := json.Unmarshal(data, &resp); err != nil {
		log.Printf("Failed to unmarshal connect response: %v", err)
		return
	}

	log.Printf("Connect response for stream %s: success=%v", resp.StreamID, resp.Success)
}

// handleData processes data from the device.
func (s *Server) handleData(tunnel *TunnelConn, data json.RawMessage) {
	var resp struct {
		StreamID string `json:"stream_id"`
		Data     []byte `json:"data"`
	}

	if err := json.Unmarshal(data, &resp); err != nil {
		log.Printf("Failed to unmarshal data: %v", err)
		return
	}

}

// handleDisconnect processes a disconnect message from the device.
func (s *Server) handleDisconnect(tunnel *TunnelConn, data json.RawMessage) {
	var resp struct {
		StreamID string `json:"stream_id"`
	}

	if err := json.Unmarshal(data, &resp); err != nil {
		log.Printf("Failed to unmarshal disconnect: %v", err)
		return
	}

	log.Printf("Disconnect for stream %s", resp.StreamID)
}

// handleHealth returns the health status of the server.
func (s *Server) handleHealth(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]interface{}{
		"status": "ok",
		"time":   time.Now().UTC(),
	})
}

func (s *Server) authorizeStats(r *http.Request) bool {
	expectedToken := ""
	if s != nil && s.config != nil {
		expectedToken = strings.TrimSpace(s.config.StatsToken)
	}

	if expectedToken != "" {
		receivedToken := strings.TrimSpace(r.Header.Get("X-Stats-Token"))
		if receivedToken == "" {
			return false
		}
		return subtle.ConstantTimeCompare([]byte(receivedToken), []byte(expectedToken)) == 1
	}

	return false
}

// handleStats returns tunnel statistics.
func (s *Server) handleStats(w http.ResponseWriter, r *http.Request) {
	if !s.authorizeStats(r) {
		http.Error(w, "forbidden", http.StatusForbidden)
		return
	}

	s.manager.mu.RLock()
	count := len(s.manager.tunnels)
	s.manager.mu.RUnlock()

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]interface{}{
		"online_devices": count,
		"time":           time.Now().UTC(),
	})
}

func newHTTPServer(addr string, handler http.Handler) *http.Server {
	return &http.Server{
		Addr:              addr,
		Handler:           handler,
		MaxHeaderBytes:    maxHTTPHeaderBytes,
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       30 * time.Second,
		WriteTimeout:      30 * time.Second,
		IdleTimeout:       120 * time.Second,
	}
}

// Run starts the tunnel server.
func (s *Server) Run() error {
	// wg.Add(1) must happen before the goroutine launch (not inside it);
	// otherwise Stop() could observe wg.Wait() == 0 before the goroutine
	// has called wg.Add(1), causing WaitGroup reuse panic.
	s.manager.wg.Add(1)
	go s.manager.cleanupDeadTunnels()

	mux := http.NewServeMux()
	mux.HandleFunc("/tunnel", s.handleTunnel)
	mux.HandleFunc("/health", s.handleHealth)
	mux.HandleFunc("/stats", s.handleStats)

	s.httpServer = newHTTPServer(s.config.Addr, mux)

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	go func() {
		<-quit
		log.Printf("Tunnel server shutting down gracefully...")
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		if err := s.httpServer.Shutdown(shutdownCtx); err != nil {
			log.Printf("Tunnel server graceful shutdown failed: %v", err)
		}
		s.manager.Stop()
	}()

	log.Printf("Tunnel server starting on %s", s.config.Addr)

	if s.config.EnableTLS {
		return s.httpServer.ListenAndServeTLS(s.config.TLSCert, s.config.TLSKey)
	}
	return s.httpServer.ListenAndServe()
}

func main() {
	addr := flag.String("addr", "0.0.0.0:8443", "Server address")
	apiEndpoint := flag.String("api", "http://localhost:8080", "API endpoint URL")
	statsTokenFlag := flag.String("stats-token", "", "Token required for all /stats requests when configured")
	allowedOriginsFlag := flag.String("allowed-origins", "", "Comma-separated allowed origins for WebSocket upgrades")
	tlsCert := flag.String("tls-cert", "", "TLS certificate file")
	tlsKey := flag.String("tls-key", "", "TLS key file")
	heartbeatInterval := flag.Duration("heartbeat-interval", 30*time.Second, "Heartbeat interval")
	heartbeatTimeout := flag.Duration("heartbeat-timeout", 90*time.Second, "Heartbeat timeout")
	flag.Parse()

	if envAddr := os.Getenv("TUNNEL_ADDR"); envAddr != "" {
		*addr = envAddr
	}
	if envAPI := os.Getenv("API_ENDPOINT"); envAPI != "" {
		*apiEndpoint = envAPI
	}
	internalAPIKey := os.Getenv("INTERNAL_API_KEY")
	if internalAPIKey == "" {
		log.Fatalf("FATAL: INTERNAL_API_KEY environment variable is not set. Please configure an internal API key before starting the server.")
	}
	allowedOriginsRaw := stringutil.FirstNonEmpty(os.Getenv("TUNNEL_ALLOWED_ORIGINS"), *allowedOriginsFlag)
	statsToken := stringutil.FirstNonEmpty(os.Getenv("TUNNEL_STATS_TOKEN"), *statsTokenFlag)

	envEnableTLS := os.Getenv("ENABLE_TLS")
	envTLSCert := os.Getenv("TLS_CERT")
	envTLSKey := os.Getenv("TLS_KEY")

	enableTLS := false
	switch envEnableTLS {
	case "true":
		enableTLS = true
	case "false", "":
		if envEnableTLS == "" {
			enableTLS = *tlsCert != "" && *tlsKey != ""
		}
	default:
		log.Printf("Warning: unexpected ENABLE_TLS value '%s', treating as false", envEnableTLS)
	}

	finalTLSCert := stringutil.FirstNonEmpty(envTLSCert, *tlsCert)
	finalTLSKey := stringutil.FirstNonEmpty(envTLSKey, *tlsKey)

	if enableTLS {
		if finalTLSCert == "" || finalTLSKey == "" {
			log.Fatalf("TLS enabled but certificate paths not provided")
		}
		if _, err := os.Stat(finalTLSCert); os.IsNotExist(err) {
			log.Fatalf("TLS certificate file not found: %s", finalTLSCert)
		}
		if _, err := os.Stat(finalTLSKey); os.IsNotExist(err) {
			log.Fatalf("TLS key file not found: %s", finalTLSKey)
		}
	}

	config := &Config{
		Addr:              *addr,
		APIEndpoint:       *apiEndpoint,
		InternalAPIKey:    internalAPIKey,
		StatsToken:        statsToken,
		AllowedOrigins:    parseAllowedOrigins(allowedOriginsRaw),
		EnableTLS:         enableTLS,
		TLSCert:           finalTLSCert,
		TLSKey:            finalTLSKey,
		HeartbeatInterval: *heartbeatInterval,
		HeartbeatTimeout:  *heartbeatTimeout,
	}

	server := NewServer(config)

	log.Printf("Tunnel server starting on %s (TLS enabled: %v)", config.Addr, config.EnableTLS)

	if err := server.Run(); err != nil {
		log.Fatalf("Server failed: %v", err)
	}
}

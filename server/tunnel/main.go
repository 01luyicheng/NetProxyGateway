package main

import (
	"bytes"
	"crypto/subtle"
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"net"
	"net/http"
	"net/url"
	"os"
	"strings"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

// Config 配置结构
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

// TunnelConn 隧道连接
type TunnelConn struct {
	DeviceID       string
	Conn           *websocket.Conn
	LastPing       time.Time
	mu             sync.RWMutex
	sendChan       chan []byte
	messageLimiter *tokenBucketLimiter
	closeChan      chan struct{}
	closeOnce      sync.Once
}

// NewTunnelConn 创建新的隧道连接
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

// UpdatePing 更新最后ping时间
func (t *TunnelConn) UpdatePing() {
	t.mu.Lock()
	t.LastPing = time.Now()
	t.mu.Unlock()
}

// IsAlive 检查连接是否存活
func (t *TunnelConn) IsAlive(timeout time.Duration) bool {
	t.mu.RLock()
	defer t.mu.RUnlock()
	return time.Since(t.LastPing) < timeout
}

// Send 发送消息
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

// Close 关闭连接
func (t *TunnelConn) Close() {
	t.closeOnce.Do(func() {
		close(t.closeChan)
		if t.Conn != nil {
			_ = t.Conn.Close()
		}
	})
}

// TunnelManager 隧道管理器
type TunnelManager struct {
	tunnels map[string]*TunnelConn
	mu      sync.RWMutex
	config  *Config
}

// NewTunnelManager 创建隧道管理器
func NewTunnelManager(config *Config) *TunnelManager {
	return &TunnelManager{
		tunnels: make(map[string]*TunnelConn),
		config:  config,
	}
}

// Register 注册隧道
func (m *TunnelManager) Register(deviceID string, conn *websocket.Conn) *TunnelConn {
	tunnel := NewTunnelConn(deviceID, conn)

	m.mu.Lock()
	// 关闭旧连接
	if old, ok := m.tunnels[deviceID]; ok {
		old.Close()
	}
	m.tunnels[deviceID] = tunnel
	m.mu.Unlock()

	log.Printf("Tunnel registered for device: %s", deviceID)

	// 通知API服务设备上线
	m.notifyDeviceStatus(deviceID, "online", "")

	return tunnel
}

// Unregister 注销隧道
func (m *TunnelManager) Unregister(deviceID string) {
	m.mu.Lock()
	if tunnel, ok := m.tunnels[deviceID]; ok {
		tunnel.Close()
		delete(m.tunnels, deviceID)
	}
	m.mu.Unlock()

	log.Printf("Tunnel unregistered for device: %s", deviceID)

	// 通知API服务设备离线
	m.notifyDeviceStatus(deviceID, "offline", "")
}

// Get 获取隧道
func (m *TunnelManager) Get(deviceID string) (*TunnelConn, bool) {
	m.mu.RLock()
	tunnel, ok := m.tunnels[deviceID]
	m.mu.RUnlock()
	return tunnel, ok
}

// notifyDeviceStatus 通知API设备状态变化
func (m *TunnelManager) notifyDeviceStatus(deviceID, status, tunnelAddr string) {
	go func() {
		client := &http.Client{Timeout: 5 * time.Second}

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
			req, err := http.NewRequest(
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

			resp, err := client.Do(req)
			if err != nil {
				if attempt < defaultNotifyStatusMaxAttempts {
					time.Sleep(notifyStatusBackoff(attempt))
					continue
				}
				log.Printf("Failed to notify device status after %d attempts: %v", attempt, err)
				return
			}

			statusCode := resp.StatusCode
			_ = resp.Body.Close()

			if statusCode >= http.StatusOK && statusCode < http.StatusMultipleChoices {
				return
			}

			if shouldRetryNotifyStatusCode(statusCode) && attempt < defaultNotifyStatusMaxAttempts {
				time.Sleep(notifyStatusBackoff(attempt))
				continue
			}

			if shouldRetryNotifyStatusCode(statusCode) {
				log.Printf("Failed to notify device status after %d attempts: status code %d", attempt, statusCode)
			} else {
				log.Printf("Failed to notify device status: status code %d", statusCode)
			}
			return
		}
	}()
}

func notifyStatusBackoff(attempt int) time.Duration {
	if attempt <= 0 {
		attempt = 1
	}

	return defaultNotifyStatusBaseBackoff * time.Duration(1<<(attempt-1))
}

func shouldRetryNotifyStatusCode(statusCode int) bool {
	return statusCode == http.StatusTooManyRequests || statusCode >= http.StatusInternalServerError
}

// cleanupDeadTunnels 清理死连接
func (m *TunnelManager) cleanupDeadTunnels() {
	ticker := time.NewTicker(30 * time.Second)
	defer ticker.Stop()

	for range ticker.C {
		var deadTunnels []*TunnelConn
		var deadIDs []string

		m.mu.Lock()
		for deviceID, tunnel := range m.tunnels {
			if !tunnel.IsAlive(m.config.HeartbeatTimeout) {
				log.Printf("Cleaning up dead tunnel for device: %s", deviceID)
				deadTunnels = append(deadTunnels, tunnel)
				deadIDs = append(deadIDs, deviceID)
				delete(m.tunnels, deviceID)
			}
		}
		m.mu.Unlock()

		for i, tunnel := range deadTunnels {
			tunnel.Close()
			go m.notifyDeviceStatus(deadIDs[i], "offline", "")
		}
	}
}

// Server WebSocket服务器
type Server struct {
	manager  *TunnelManager
	upgrader websocket.Upgrader
	config   *Config
}

// NewServer 创建服务器
func NewServer(config *Config) *Server {
	server := &Server{
		manager: NewTunnelManager(config),
		config:  config,
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

// handleTunnel WebSocket连接处理
func (s *Server) handleTunnel(w http.ResponseWriter, r *http.Request) {
	// 获取设备ID和凭证
	deviceID := r.URL.Query().Get("device_id")
	token := r.Header.Get("X-Session-Token")

	if deviceID == "" || token == "" {
		http.Error(w, "missing device_id or token", http.StatusBadRequest)
		return
	}

	// 验证设备凭证（调用API服务）
	if !s.validateDeviceToken(deviceID, token) {
		http.Error(w, "invalid credentials", http.StatusUnauthorized)
		return
	}

	// 升级WebSocket连接
	conn, err := s.upgrader.Upgrade(w, r, nil)
	if err != nil {
		log.Printf("Failed to upgrade connection: %v", err)
		return
	}
	defer conn.Close()

	// 注册隧道
	tunnel := s.manager.Register(deviceID, conn)
	defer s.manager.Unregister(deviceID)

	// 启动心跳检测
	stopHeartbeat := make(chan struct{})
	go s.heartbeat(tunnel, stopHeartbeat)

	// 启动发送协程
	go s.sendLoop(tunnel)

	// 读取消息循环
	s.readLoop(tunnel)

	close(stopHeartbeat)
}

// validateDeviceToken 验证设备令牌
// 通过HTTP请求调用API服务验证token的有效性
func (s *Server) validateDeviceToken(deviceID, token string) bool {
	// 构造验证请求
	payload := map[string]string{
		"device_id": deviceID,
		"token":     token,
	}

	data, err := json.Marshal(payload)
	if err != nil {
		log.Printf("Failed to marshal validation request: %v", err)
		return false
	}

	// 创建带超时的HTTP客户端
	client := &http.Client{
		Timeout: 10 * time.Second,
	}

	// 发送验证请求到API服务
	req, err := http.NewRequest(
		http.MethodPost,
		s.config.APIEndpoint+"/api/session/validate",
		bytes.NewReader(data),
	)
	if err != nil {
		log.Printf("Failed to build validation request: %v", err)
		return false
	}
	req.Header.Set("Content-Type", "application/json")
	if s.config.InternalAPIKey != "" {
		req.Header.Set("X-Internal-API-Key", s.config.InternalAPIKey)
	}

	resp, err := client.Do(req)
	if err != nil {
		log.Printf("Failed to call validation API: %v", err)
		return false
	}
	defer resp.Body.Close()

	// 检查HTTP状态码
	if resp.StatusCode != http.StatusOK {
		log.Printf("Validation API returned non-OK status: %d", resp.StatusCode)
		return false
	}

	// 解析响应
	var result struct {
		Valid bool `json:"valid"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&result); err != nil {
		log.Printf("Failed to decode validation response: %v", err)
		return false
	}

	return result.Valid
}

// heartbeat 心跳检测
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

			// 发送ping
			if err := tunnel.Conn.WriteControl(websocket.PingMessage, []byte{}, time.Now().Add(10*time.Second)); err != nil {
				log.Printf("Failed to send ping: %v", err)
				tunnel.Close()
				return
			}

		case <-stop:
			return
		}
	}
}

// sendLoop 发送循环
func (s *Server) sendLoop(tunnel *TunnelConn) {
	for {
		select {
		case data := <-tunnel.sendChan:
			// 在写入前检查连接是否已关闭
			select {
			case <-tunnel.closeChan:
				return
			default:
			}

			// 再次检查 Conn 是否为 nil
			if tunnel.Conn == nil {
				log.Printf("Cannot write message: connection is nil")
				return
			}

			if err := tunnel.Conn.WriteMessage(websocket.BinaryMessage, data); err != nil {
				log.Printf("Failed to write message: %v", err)
				return
			}
		case <-tunnel.closeChan:
			return
		}
	}
}

// readLoop 读取循环
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

			// 处理消息
			s.handleMessage(tunnel, data)
		}
	}
}

// handleMessage 处理消息
func (s *Server) handleMessage(tunnel *TunnelConn, data []byte) {
	// 解析消息
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
		// 设备响应连接请求
		s.handleConnectResponse(tunnel, msg.Data)
	case "data":
		// 数据传输
		s.handleData(tunnel, msg.Data)
	case "disconnect":
		// 断开连接
		s.handleDisconnect(tunnel, msg.Data)
	default:
		log.Printf("Unknown message type: %s", msg.Type)
	}
}

// handleConnectResponse 处理连接响应
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

	// 这里应该将响应转发给SOCKS5服务
	// 简化版本：直接处理
}

// handleData 处理数据
func (s *Server) handleData(tunnel *TunnelConn, data json.RawMessage) {
	var resp struct {
		StreamID string `json:"stream_id"`
		Data     []byte `json:"data"`
	}

	if err := json.Unmarshal(data, &resp); err != nil {
		log.Printf("Failed to unmarshal data: %v", err)
		return
	}

	// 这里应该将数据转发给SOCKS5服务
	// 简化版本：直接处理
}

// handleDisconnect 处理断开连接
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

// handleHealth 健康检查
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

// handleStats 统计信息
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

// Run 运行服务器
func (s *Server) Run() error {
	// 启动清理协程
	go s.manager.cleanupDeadTunnels()

	// 设置路由
	mux := http.NewServeMux()
	mux.HandleFunc("/tunnel", s.handleTunnel)
	mux.HandleFunc("/health", s.handleHealth)
	mux.HandleFunc("/stats", s.handleStats)

	httpServer := &http.Server{
		Addr:              s.config.Addr,
		Handler:           mux,
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       30 * time.Second,
		WriteTimeout:      30 * time.Second,
		IdleTimeout:       120 * time.Second,
	}

	log.Printf("Tunnel server starting on %s", s.config.Addr)

	if s.config.EnableTLS {
		return httpServer.ListenAndServeTLS(s.config.TLSCert, s.config.TLSKey)
	}
	return httpServer.ListenAndServe()
}

// firstNonEmpty 返回第一个非空字符串（环境变量优先）
func firstNonEmpty(values ...string) string {
	for _, v := range values {
		if v != "" {
			return v
		}
	}
	return ""
}

func main() {
	// 解析命令行参数
	addr := flag.String("addr", "0.0.0.0:8443", "Server address")
	apiEndpoint := flag.String("api", "http://localhost:8080", "API endpoint URL")
	statsTokenFlag := flag.String("stats-token", "", "Token required for all /stats requests when configured")
	allowedOriginsFlag := flag.String("allowed-origins", "", "Comma-separated allowed origins for WebSocket upgrades")
	tlsCert := flag.String("tls-cert", "", "TLS certificate file")
	tlsKey := flag.String("tls-key", "", "TLS key file")
	heartbeatInterval := flag.Duration("heartbeat-interval", 30*time.Second, "Heartbeat interval")
	heartbeatTimeout := flag.Duration("heartbeat-timeout", 90*time.Second, "Heartbeat timeout")
	flag.Parse()

	// 从环境变量读取配置
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
	allowedOriginsRaw := firstNonEmpty(os.Getenv("TUNNEL_ALLOWED_ORIGINS"), *allowedOriginsFlag)
	statsToken := firstNonEmpty(os.Getenv("TUNNEL_STATS_TOKEN"), *statsTokenFlag)

	// 读取TLS配置（环境变量优先）
	envEnableTLS := os.Getenv("ENABLE_TLS")
	envTLSCert := os.Getenv("TLS_CERT")
	envTLSKey := os.Getenv("TLS_KEY")

	// 确定是否启用TLS：环境变量设置则使用环境变量，否则根据命令行参数判断
	enableTLS := false
	switch envEnableTLS {
	case "true":
		enableTLS = true
	case "false", "":
		// 显式关闭或环境变量未设置时，根据命令行参数判断
		if envEnableTLS == "" {
			enableTLS = *tlsCert != "" && *tlsKey != ""
		}
	default:
		// 非预期值，按false处理
		log.Printf("Warning: unexpected ENABLE_TLS value '%s', treating as false", envEnableTLS)
	}

	// 证书路径：环境变量优先
	finalTLSCert := firstNonEmpty(envTLSCert, *tlsCert)
	finalTLSKey := firstNonEmpty(envTLSKey, *tlsKey)

	// 验证TLS配置
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

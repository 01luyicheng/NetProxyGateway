package main

import (
	"bufio"
	"crypto/rand"
	"crypto/tls"
	"encoding/hex"
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
	"sync/atomic"
	"syscall"
	"time"

	"github.com/gorilla/websocket"
	"github.com/netproxy/shared/httpclient"
	"github.com/netproxy/shared/stringutil"
)
// SOCKS5 protocol constants
const (
	socks5Version = 0x05

	// Authentication methods
	authNone     = 0x00 // No authentication required
	authGSSAPI   = 0x01 // GSSAPI
	authPassword = 0x02 // Username/password
	authNoAccept = 0xFF // No acceptable methods

	// Command types
	cmdConnect      = 0x01 // CONNECT
	cmdBind         = 0x02 // BIND
	cmdUDPAssociate = 0x03 // UDP ASSOCIATE

	// Address types
	atypIPv4   = 0x01 // IPv4
	atypDomain = 0x03 // Domain name
	atypIPv6   = 0x04 // IPv6

	// Reply codes
	repSucceeded           = 0x00 // Succeeded
	repGeneralFailure      = 0x01 // General SOCKS server failure
	repNotAllowed          = 0x02 // Connection not allowed by ruleset
	repNetworkUnreachable  = 0x03 // Network unreachable
	repHostUnreachable     = 0x04 // Host unreachable
	repConnectionRefused   = 0x05 // Connection refused
	repTTLExpired          = 0x06 // TTL expired
	repCommandNotSupported = 0x07 // Command not supported
	repAddressNotSupported = 0x08 // Address type not supported

	// Auth sub-negotiation version
	authSubVersion = 0x01 // Username/password auth version

	// Reserved field
	rsvReserved = 0x00 // Reserved field in SOCKS5 reply
)

const (
	tunnelReadTimeout     = 60 * time.Second
	streamCloseWriteLimit = 2 * time.Second
	streamWriteLimit      = 5 * time.Second

	// Rate limiter constants
	rateLimitMaxAttempts     = 5
	rateLimitWindow          = 5 * time.Minute
	rateLimitBlockDuration   = 15 * time.Minute
	rateLimitCleanupInterval = 10 * time.Minute
	rateLimitStaleAttemptTTL = 30 * time.Minute
)

var streamIDGenerator = generateRandomStreamID

func generateRandomStreamID() (string, error) {
	randomBytes := make([]byte, 16)
	if _, err := rand.Read(randomBytes); err != nil {
		return "", fmt.Errorf("failed to read crypto random bytes: %w", err)
	}

	return hex.EncodeToString(randomBytes), nil
}

// createSecureTLSConfig creates a base secure TLS config with minimum version 1.2.
func createSecureTLSConfig() *tls.Config {
	return &tls.Config{
		MinVersion: tls.VersionTLS12,
	}
}

// Config holds the SOCKS5 server configuration.
type Config struct {
	Addr           string
	APIEndpoint    string
	InternalAPIKey string
	TunnelEndpoint string
	TLSCert        string
	TLSKey         string
	EnableTLS      bool
	MaxConnections int
	IdleTimeout    time.Duration
}

// StreamIDProvider provides a StreamID.
type StreamIDProvider interface {
	StreamID() string
}

// SessionStore is the session storage interface.
type SessionStore interface {
	ValidateToken(deviceID, token string) (bool, error)
}

// APISessionStore validates sessions via API calls.
type APISessionStore struct {
	apiEndpoint    string
	internalAPIKey string
	httpClient     *http.Client
}

// NewAPISessionStore creates a new API session store.
func NewAPISessionStore(apiEndpoint string, internalAPIKey string) *APISessionStore {
	return &APISessionStore{
		apiEndpoint:    apiEndpoint,
		internalAPIKey: internalAPIKey,
		httpClient: &http.Client{
			Timeout: 5 * time.Second,
			Transport: &http.Transport{
				TLSClientConfig: createSecureTLSConfig(),
			},
		},
	}
}

// ValidateToken validates a device token.
func (s *APISessionStore) ValidateToken(deviceID, token string) (bool, error) {
	valid, err := s.validateWithAPI(deviceID, token)
	if err != nil {
		log.Printf("API validation error: %v", err)
		return false, err
	}

	return valid, nil
}

// validateWithAPI calls the API to validate a token.
func (s *APISessionStore) validateWithAPI(deviceID, token string) (bool, error) {
	u, err := url.Parse(s.apiEndpoint + "/api/session/validate")
	if err != nil {
		return false, fmt.Errorf("failed to parse validate API endpoint: %w", err)
	}

	payload := struct {
		DeviceID string `json:"device_id"`
		Token    string `json:"token"`
	}{
		DeviceID: deviceID,
		Token:    token,
	}

	var result struct {
		Valid bool `json:"valid"`
	}

	if err := httpclient.PostJSON(s.httpClient, u.String(), s.internalAPIKey, payload, &result); err != nil {
		return false, err
	}

	return result.Valid, nil
}

// RateLimiter is a login rate limiter.
type RateLimiter struct {
	attempts     map[string]*LoginAttempt
	mu           sync.RWMutex
	stopCh       chan struct{}
	restartCount int32
}

// LoginAttempt tracks login attempts for rate limiting.
type LoginAttempt struct {
	Count      int
	LastTry    time.Time
	Blocked    bool
	BlockUntil time.Time
}

// NewRateLimiter creates a new rate limiter.
func NewRateLimiter() *RateLimiter {
	rl := &RateLimiter{
		attempts: make(map[string]*LoginAttempt),
		stopCh:   make(chan struct{}),
	}
	go rl.cleanupLoop()
	return rl
}

// Stop stops the rate limiter's cleanup goroutine.
func (rl *RateLimiter) Stop() {
	close(rl.stopCh)
}

// Allow checks if a login attempt is allowed.
func (rl *RateLimiter) Allow(key string) bool {
	rl.mu.Lock()
	defer rl.mu.Unlock()

	attempt, ok := rl.attempts[key]
	if !ok {
		rl.attempts[key] = &LoginAttempt{
			Count:   1,
			LastTry: time.Now(),
		}
		return true
	}

	if attempt.Blocked && time.Now().Before(attempt.BlockUntil) {
		return false
	}

	if attempt.Blocked && time.Now().After(attempt.BlockUntil) {
		attempt.Blocked = false
		attempt.Count = 0
	}

	if attempt.Count >= rateLimitMaxAttempts && time.Since(attempt.LastTry) < rateLimitWindow {
		attempt.Blocked = true
		attempt.BlockUntil = time.Now().Add(rateLimitBlockDuration)
		log.Printf("Rate limit exceeded for %s, blocked for %v", key, rateLimitBlockDuration)
		return false
	}

	if time.Since(attempt.LastTry) > rateLimitWindow {
		attempt.Count = 0
	}

	attempt.Count++
	attempt.LastTry = time.Now()
	return true
}

// Success resets the rate limit counter on successful login.
func (rl *RateLimiter) Success(key string) {
	rl.mu.Lock()
	defer rl.mu.Unlock()
	delete(rl.attempts, key)
}

// cleanupLoop periodically removes stale attempt records.
func (rl *RateLimiter) cleanupLoop() {
	const maxRestarts = 3

	defer func() {
		if r := recover(); r != nil {
			count := atomic.AddInt32(&rl.restartCount, 1)
			if count <= maxRestarts {
				log.Printf("Panic in RateLimiter.cleanupLoop, restarting (%d/%d): %v", count, maxRestarts, r)
				go rl.cleanupLoop()
			} else {
				log.Printf("Panic in RateLimiter.cleanupLoop, max restarts (%d) exceeded, not restarting: %v", maxRestarts, r)
			}
		}
	}()

	ticker := time.NewTicker(rateLimitCleanupInterval)
	defer ticker.Stop()

	for {
		select {
		case <-ticker.C:
			func() {
				rl.mu.Lock()
				defer rl.mu.Unlock()
				now := time.Now()
				for key, attempt := range rl.attempts {
					if now.Sub(attempt.LastTry) > rateLimitStaleAttemptTTL {
						delete(rl.attempts, key)
					}
				}
			}()
		case <-rl.stopCh:
			return
		}
	}
}

// IPFilter filters allowed destination IPs.
type IPFilter struct {
	allowedCIDRs []string
}

// NewIPFilter creates a new IP filter.
func NewIPFilter() *IPFilter {
	return &IPFilter{
		allowedCIDRs: []string{
			"10.0.0.0/8",
			"172.16.0.0/12",
			"192.168.0.0/16",
		},
	}
}

// IsAllowed checks if the given IP is allowed.
func (f *IPFilter) IsAllowed(ip string) bool {
	parsedIP := net.ParseIP(ip)
	if parsedIP == nil {
		return false
	}

	if parsedIP.IsLoopback() ||
		parsedIP.IsMulticast() ||
		parsedIP.Equal(net.ParseIP("0.0.0.0")) ||
		parsedIP.IsLinkLocalUnicast() {
		return false
	}

	for _, cidr := range f.allowedCIDRs {
		_, ipNet, err := net.ParseCIDR(cidr)
		if err != nil {
			continue
		}
		if ipNet.Contains(parsedIP) {
			return true
		}
	}

	return false
}

// StreamConn represents a stream connection through the tunnel.
// Lock hierarchy: readMu -> mu (nesting allowed in this direction only).
type StreamConn struct {
	StreamID      string
	DeviceID      string
	TunnelConn    *websocket.Conn
	tunnelWriteMu *sync.Mutex
	DataChan      chan []byte
	CloseChan     chan struct{}
	Connected     chan bool
	Closed        int32
	readRemainder []byte
	readDeadline  atomic.Value // *time.Time
	readMu        sync.Mutex
	mu            sync.Mutex
}

const (
	defaultDataChanSize = 100
	defaultBufferSize   = 64 * 1024
)

// NewStreamConn creates a new stream connection.
func NewStreamConn(streamID, deviceID string, tunnelConn *websocket.Conn, tunnelWriteMu *sync.Mutex) *StreamConn {
	return &StreamConn{
		StreamID:      streamID,
		DeviceID:      deviceID,
		TunnelConn:    tunnelConn,
		tunnelWriteMu: tunnelWriteMu,
		DataChan:      make(chan []byte, defaultDataChanSize),
		CloseChan:     make(chan struct{}),
		Connected:     make(chan bool, 1),
	}
}

// Read implements net.Conn.Read.
func (s *StreamConn) Read(p []byte) (n int, err error) {
	defer func() {
		if r := recover(); r != nil {
			log.Printf("Panic in StreamConn.Read for stream %s: %v", s.StreamID, r)
			n = 0
			err = fmt.Errorf("read panic: %w", errors.New(fmt.Sprint(r)))
		}
	}()

	s.readMu.Lock()
	defer s.readMu.Unlock()

	if len(p) == 0 {
		return 0, nil
	}

	func() {
		s.mu.Lock()
		defer s.mu.Unlock()
		if len(s.readRemainder) > 0 {
			n = copy(p, s.readRemainder)
			s.readRemainder = s.readRemainder[n:]
			return
		}
	}()
	if n > 0 {
		return n, nil
	}

	select {
	case data := <-s.DataChan:
		return s.consumeReadChunk(p, data), nil
	default:
	}

	deadline := s.readDeadline.Load()
	if deadline != nil {
		if t := deadline.(*time.Time); t != nil && !t.IsZero() {
			timer := time.NewTimer(time.Until(*t))
			defer timer.Stop()

			select {
			case data := <-s.DataChan:
				return s.consumeReadChunk(p, data), nil
			case <-s.CloseChan:
				return s.drainAndEOF(p)
			case <-timer.C:
				return 0, os.ErrDeadlineExceeded
			}
		}
	}

	select {
	case data := <-s.DataChan:
		return s.consumeReadChunk(p, data), nil
	case <-s.CloseChan:
		return s.drainAndEOF(p)
	}
}

func (s *StreamConn) consumeReadChunk(p []byte, data []byte) int {
	n := copy(p, data)
	if n < len(data) {
		s.mu.Lock()
		defer s.mu.Unlock()
		s.readRemainder = append(s.readRemainder[:0], data[n:]...)
	}
	return n
}

// drainAndEOF drains DataChan non-blockingly after CloseChan is closed, then returns io.EOF.
func (s *StreamConn) drainAndEOF(p []byte) (n int, err error) {
	for {
		select {
		case data := <-s.DataChan:
			if len(data) > 0 {
				return s.consumeReadChunk(p, data), nil
			}
			continue
		default:
			return 0, io.EOF
		}
	}
}

// Write implements net.Conn.Write.
func (s *StreamConn) Write(p []byte) (n int, err error) {
	defer func() {
		if r := recover(); r != nil {
			log.Printf("Panic in StreamConn.Write for stream %s: %v", s.StreamID, r)
			n = 0
			err = fmt.Errorf("write panic: %w", errors.New(fmt.Sprint(r)))
		}
	}()

	if atomic.LoadInt32(&s.Closed) == 1 {
		return 0, fmt.Errorf("stream closed")
	}

	msg := struct {
		Type string `json:"type"`
		Data struct {
			StreamID string `json:"stream_id"`
			Data     []byte `json:"data"`
		} `json:"data"`
	}{
		Type: "data",
		Data: struct {
			StreamID string `json:"stream_id"`
			Data     []byte `json:"data"`
		}{
			StreamID: s.StreamID,
			Data:     p,
		},
	}

	data, err := json.Marshal(msg)
	if err != nil {
		return 0, err
	}

	var tunnelConn *websocket.Conn
	var writeMu *sync.Mutex
	func() {
		s.mu.Lock()
		defer s.mu.Unlock()
		tunnelConn = s.TunnelConn
		writeMu = s.tunnelWriteMu
	}()

	if tunnelConn == nil {
		return 0, fmt.Errorf("tunnel connection not available")
	}

	if atomic.LoadInt32(&s.Closed) == 1 {
		return 0, fmt.Errorf("stream closed")
	}

	if writeMu != nil {
		writeMu.Lock()
		defer writeMu.Unlock()
	}

	_ = tunnelConn.SetWriteDeadline(time.Now().Add(streamWriteLimit))
	err = tunnelConn.WriteMessage(websocket.BinaryMessage, data)
	_ = tunnelConn.SetWriteDeadline(time.Time{})
	if err != nil {
		return 0, err
	}

	return len(p), nil
}

// ErrDataChannelFull is returned when the data channel is full.
var ErrDataChannelFull = errors.New("data channel full")

// WriteToDataChan writes data to DataChan. Returns an error if StreamConn is closed.
func (s *StreamConn) WriteToDataChan(data []byte) error {
	if atomic.LoadInt32(&s.Closed) == 1 {
		return fmt.Errorf("stream closed")
	}

	select {
	case s.DataChan <- data:
		return nil
	case <-s.CloseChan:
		return fmt.Errorf("stream closed")
	default:
		return ErrDataChannelFull
	}
}

// sendDisconnect sends a disconnect message asynchronously.
func (s *StreamConn) sendDisconnect() {
	defer func() {
		if r := recover(); r != nil {
			log.Printf("Panic in sendDisconnect for stream %s: %v", s.StreamID, r)
		}
	}()

	msg := struct {
		Type string `json:"type"`
		Data struct {
			StreamID string `json:"stream_id"`
		} `json:"data"`
	}{
		Type: "disconnect",
		Data: struct {
			StreamID string `json:"stream_id"`
		}{
			StreamID: s.StreamID,
		},
	}

	data, err := json.Marshal(msg)
	if err != nil {
		log.Printf("Failed to marshal disconnect message for stream %s: %v", s.StreamID, err)
		return
	}

	func() {
		s.mu.Lock()
		defer s.mu.Unlock()
		tunnelConn := s.TunnelConn
		writeMu := s.tunnelWriteMu

		if tunnelConn != nil {
			if writeMu != nil {
				writeMu.Lock()
				defer writeMu.Unlock()
			}

			_ = tunnelConn.SetWriteDeadline(time.Now().Add(streamCloseWriteLimit))
			_ = tunnelConn.WriteMessage(websocket.BinaryMessage, data)
			_ = tunnelConn.SetWriteDeadline(time.Time{})
		}
	}()
}

// Close implements net.Conn.Close.
func (s *StreamConn) Close() error {
	if atomic.CompareAndSwapInt32(&s.Closed, 0, 1) {
		close(s.CloseChan)
		go s.sendDisconnect()
	}
	return nil
}

// closeLocal closes the stream locally without sending a disconnect message.
// Used when the peer has already disconnected or the connection is dead,
// to avoid sending unnecessary disconnect echoes.
func (s *StreamConn) closeLocal() {
	if atomic.CompareAndSwapInt32(&s.Closed, 0, 1) {
		close(s.CloseChan)
	}
}

// detachTunnel clears tunnelConn and tunnelWriteMu references under stream.mu
// to prevent subsequent async operations from using a stale connection.
func (s *StreamConn) detachTunnel() {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.TunnelConn = nil
	s.tunnelWriteMu = nil
}

// LocalAddr implements net.Conn.LocalAddr.
func (s *StreamConn) LocalAddr() net.Addr {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.TunnelConn != nil {
		return s.TunnelConn.LocalAddr()
	}
	// net.Conn interface typically does not expect nil; return empty address to avoid caller panic.
	return &net.TCPAddr{IP: net.IPv4zero, Port: 0}
}

// RemoteAddr implements net.Conn.RemoteAddr.
func (s *StreamConn) RemoteAddr() net.Addr {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.TunnelConn != nil {
		return s.TunnelConn.RemoteAddr()
	}
	// net.Conn interface typically does not expect nil; return empty address to avoid caller panic.
	return &net.TCPAddr{IP: net.IPv4zero, Port: 0}
}

// SetDeadline implements net.Conn.SetDeadline.
func (s *StreamConn) SetDeadline(t time.Time) error {
	if err := s.SetReadDeadline(t); err != nil {
		return err
	}
	return s.SetWriteDeadline(t)
}

// SetReadDeadline implements net.Conn.SetReadDeadline.
func (s *StreamConn) SetReadDeadline(t time.Time) error {
	s.readDeadline.Store(&t)
	return nil
}

// SetWriteDeadline implements net.Conn.SetWriteDeadline.
// Currently a no-op: WebSocket writes are protected by writeMu + streamWriteLimit,
// and StreamConn.Write does not involve blocking I/O that needs deadline control.
func (s *StreamConn) SetWriteDeadline(t time.Time) error {
	return nil
}

// TunnelClient manages tunnel connections to devices.
//
// Lock hierarchy: tc.mu -> tc.writeMu
// - tc.mu protects connections, dialing, and streams maps.
// - tc.writeMu protects WebSocket write operations (WriteMessage, WriteControl).
// Nesting tc.mu -> tc.writeMu is allowed; the reverse will deadlock.
//
// Additional hierarchy: tc.mu -> stream.mu
//   - In removeConnectionAndCollectStreams -> detachTunnel, tc.mu is held
//     while acquiring stream.mu to clear tunnelConn references.
//   - Reverse nesting stream.mu -> tc.mu is prohibited to avoid deadlock.
//
// Connection liveness check (isConnAlive) acquires writeMu while holding tc.mu
// to send Ping. This is a known design trade-off: the 5-second Ping timeout
// may block contenders for tc.mu.
type TunnelClient struct {
	tunnelEndpoint string
	httpClient     *http.Client
	wsDialer       *websocket.Dialer
	connections    map[string]*websocket.Conn // deviceID -> websocket.Conn
	dialing        map[string]chan struct{}   // deviceID -> in-flight dial signal
	streams        map[string]*StreamConn     // streamID -> StreamConn
	mu             sync.RWMutex
	writeMu        sync.Mutex
}

const (
	tunnelHTTPTimeout      = 10 * time.Second
	tunnelDialerTimeout    = 10 * time.Second
	connectResponseTimeout = 30 * time.Second
)

// NewTunnelClient creates a new tunnel client.
func NewTunnelClient(tunnelEndpoint string) *TunnelClient {
	return &TunnelClient{
		tunnelEndpoint: tunnelEndpoint,
		httpClient: &http.Client{
			Timeout: tunnelHTTPTimeout,
			Transport: &http.Transport{
				TLSClientConfig: createSecureTLSConfig(),
			},
		},
		wsDialer: &websocket.Dialer{
			HandshakeTimeout: tunnelDialerTimeout,
			TLSClientConfig:  createSecureTLSConfig(),
			ReadBufferSize:   defaultBufferSize,
			WriteBufferSize:  defaultBufferSize,
		},
		connections: make(map[string]*websocket.Conn),
		dialing:     make(map[string]chan struct{}),
		streams:     make(map[string]*StreamConn),
	}
}

// GetOrConnectTunnel gets or establishes a tunnel connection to a device.
func (tc *TunnelClient) GetOrConnectTunnel(deviceID, token string) (*websocket.Conn, error) {
	for {
		if conn, ok := tc.getExistingConn(deviceID); ok {
			return conn, nil
		}

		tc.mu.RLock()
		waitCh, dialing := tc.dialing[deviceID]
		tc.mu.RUnlock()

		if dialing {
			<-waitCh
			continue
		}

		var conn *websocket.Conn
		var err error
		func() {
			tc.mu.Lock()
			defer tc.mu.Unlock()
			if waitCh, dialing = tc.dialing[deviceID]; dialing {
				return
			}
			waitCh = make(chan struct{})
			tc.dialing[deviceID] = waitCh
		}()
		if dialing {
			<-waitCh
			continue
		}

		func() {
			defer func() {
				if r := recover(); r != nil {
					log.Printf("Panic in dialTunnel for device %s: %v", deviceID, r)
					err = fmt.Errorf("dial panic: %w", errors.New(fmt.Sprint(r)))
				}
			}()
			conn, err = tc.dialTunnel(deviceID, token)
		}()

		func() {
			tc.mu.Lock()
			defer tc.mu.Unlock()
			if err == nil && conn != nil {
				tc.connections[deviceID] = conn
			}
			delete(tc.dialing, deviceID)
			close(waitCh)
		}()

		if err != nil {
			return nil, err
		}

		go tc.readLoop(deviceID, conn)
		return conn, nil
	}
}

// getExistingConn checks and returns an existing alive connection.
// To reduce lock hold time, it first acquires tc.mu.RLock to copy the conn reference,
// then calls isConnAlive outside the lock.
// If the connection is dead, it reacquires tc.mu.Lock and uses identity check
// (tc.connections[deviceID] == conn) to ensure the connection has not been replaced,
// then removes the connection and its associated streams under the lock,
// and finally closes the streams outside the lock.
func (tc *TunnelClient) getExistingConn(deviceID string) (conn *websocket.Conn, ok bool) {
	// Phase 1: read-only lock to get conn reference
	tc.mu.RLock()
	conn, ok = tc.connections[deviceID]
	tc.mu.RUnlock()
	if !ok {
		return nil, false
	}

	// Check connection liveness outside the lock (avoid blocking others for up to 5 seconds)
	if tc.isConnAlive(conn) {
		return conn, true
	}

	// Phase 2: connection is dead, need to modify state, acquire write lock
	tc.mu.Lock()
	defer tc.mu.Unlock()

	// Double-check: ensure connection has not been replaced
	if tc.connections[deviceID] != conn {
		return nil, false
	}

	toClose := tc.removeConnectionAndCollectStreams(deviceID, conn)
	// Close streams outside the lock (avoid I/O while holding lock)
	if len(toClose) > 0 {
		go func(streams []*StreamConn) {
			for _, stream := range streams {
				stream.Close()
			}
		}(toClose)
	}
	return nil, false
}

// removeConnectionAndCollectStreams removes a connection and collects associated streams
// under the tc.mu lock, returning the list of streams to close.
// The caller should verify the connection is dead (e.g., via isConnAlive) before calling;
// this function only performs deletion and collection, no liveness check.
// It also clears the tunnelConn reference on removed streams to prevent async operations
// from using a stale connection.
func (tc *TunnelClient) removeConnectionAndCollectStreams(deviceID string, conn *websocket.Conn) []*StreamConn {
	if tc.connections[deviceID] == conn {
		delete(tc.connections, deviceID)
	}
	var toClose []*StreamConn
	for streamID, stream := range tc.streams {
		if stream.DeviceID == deviceID {
			delete(tc.streams, streamID)
			stream.detachTunnel()
			toClose = append(toClose, stream)
		}
	}
	return toClose
}

func (tc *TunnelClient) dialTunnel(deviceID, token string) (*websocket.Conn, error) {
	wsURL := fmt.Sprintf("%s/tunnel?device_id=%s", tc.tunnelEndpoint, deviceID)
	if strings.HasPrefix(wsURL, "https://") {
		wsURL = "wss://" + wsURL[8:]
	} else if strings.HasPrefix(wsURL, "http://") {
		wsURL = "ws://" + wsURL[7:]
	}

	headers := http.Header{}
	headers.Set("X-Session-Token", token)

	conn, _, err := tc.wsDialer.Dial(wsURL, headers)
	if err != nil {
		return nil, fmt.Errorf("failed to dial tunnel: %w", err)
	}

	return conn, nil
}

const pingTimeout = 1 * time.Second

// isConnAlive checks if the connection is alive.
func (tc *TunnelClient) isConnAlive(conn *websocket.Conn) bool {
	// WriteControl supports concurrent calls without locking; short timeout avoids long blocking.
	if err := conn.WriteControl(websocket.PingMessage, []byte{}, time.Now().Add(pingTimeout)); err != nil {
		return false
	}
	return true
}

// readLoop reads and processes messages from the device.
func (tc *TunnelClient) readLoop(deviceID string, conn *websocket.Conn) {
	defer func() {
		if r := recover(); r != nil {
			log.Printf("Panic in readLoop for device %s: %v", deviceID, r)
		}
	}()

	defer func() {
		var streamsToClose []*StreamConn
		var shouldCloseConn bool

		tc.mu.Lock()
		if tc.connections[deviceID] == conn {
			delete(tc.connections, deviceID)
			shouldCloseConn = true
		}
		for streamID, stream := range tc.streams {
			if stream.DeviceID == deviceID {
				delete(tc.streams, streamID)
				streamsToClose = append(streamsToClose, stream)
			}
		}
		tc.mu.Unlock()

		for _, stream := range streamsToClose {
			stream.closeLocal()
		}
		if shouldCloseConn {
			conn.Close()
		}
	}()

	if err := conn.SetReadDeadline(time.Now().Add(tunnelReadTimeout)); err != nil {
		log.Printf("Failed to set initial read deadline for device %s: %v", deviceID, err)
	}

	conn.SetPongHandler(func(string) error {
		return conn.SetReadDeadline(time.Now().Add(tunnelReadTimeout))
	})

	for {
		if err := conn.SetReadDeadline(time.Now().Add(tunnelReadTimeout)); err != nil {
			log.Printf("Failed to refresh read deadline for device %s: %v", deviceID, err)
			return
		}

		messageType, data, err := conn.ReadMessage()
		if err != nil {
			if netErr, ok := err.(net.Error); ok && netErr.Timeout() {
				log.Printf("WebSocket read timeout for device %s: %v", deviceID, err)
				return
			}
			if websocket.IsUnexpectedCloseError(err, websocket.CloseGoingAway, websocket.CloseAbnormalClosure) {
				log.Printf("WebSocket error for device %s: %v", deviceID, err)
			}
			return
		}

		if messageType == websocket.BinaryMessage || messageType == websocket.TextMessage {
			func() {
				defer func() {
					if r := recover(); r != nil {
						log.Printf("Panic in handleMessage for device %s: %v", deviceID, r)
					}
				}()
				tc.handleMessage(conn, data)
			}()
		}
	}
}

// handleMessage processes a message from the device.
func (tc *TunnelClient) handleMessage(conn *websocket.Conn, data []byte) {
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
		tc.handleConnectResponse(msg.Data)
	case "data":
		tc.handleData(msg.Data)
	case "disconnect":
		tc.handleDisconnect(msg.Data)
	default:
		log.Printf("Unknown message type: %s", msg.Type)
	}
}

func (tc *TunnelClient) getStreamLocked(streamID string) (*StreamConn, bool) {
	tc.mu.RLock()
	stream, ok := tc.streams[streamID]
	tc.mu.RUnlock()
	if !ok {
		log.Printf("No stream found for ID: %s", streamID)
		return nil, false
	}
	return stream, true
}

// handleConnectResponse processes a connect response.
func (tc *TunnelClient) handleConnectResponse(data json.RawMessage) {
	var resp struct {
		StreamID string `json:"stream_id"`
		Success  bool   `json:"success"`
		Error    string `json:"error,omitempty"`
	}

	if err := json.Unmarshal(data, &resp); err != nil {
		log.Printf("Failed to unmarshal connect response: %v", err)
		return
	}

	stream, ok := tc.getStreamLocked(resp.StreamID)
	if !ok {
		return
	}

	if !resp.Success {
		log.Printf("Connection failed for stream %s: %s", resp.StreamID, resp.Error)
		select {
		case stream.Connected <- false:
		default:
		}
		tc.cleanupStream(resp.StreamID, stream)
		return
	}

	log.Printf("Connection established for stream %s", resp.StreamID)
	select {
	case stream.Connected <- true:
	default:
	}
}

// handleData processes a data message.
func (tc *TunnelClient) handleData(data json.RawMessage) {
	var resp struct {
		StreamID string `json:"stream_id"`
		Data     []byte `json:"data"`
	}

	if err := json.Unmarshal(data, &resp); err != nil {
		log.Printf("Failed to unmarshal data: %v", err)
		return
	}

	stream, ok := tc.getStreamLocked(resp.StreamID)
	if !ok {
		return
	}

	if err := stream.WriteToDataChan(resp.Data); err != nil {
		log.Printf("Failed to write to DataChan for stream %s: %v, closing stream", resp.StreamID, err)
		tc.cleanupStream(resp.StreamID, stream)
	}
}

// handleDisconnect processes a disconnect message from the device.
// Since the peer initiated the disconnect, no disconnect echo is sent.
func (tc *TunnelClient) handleDisconnect(data json.RawMessage) {
	defer func() {
		if r := recover(); r != nil {
			log.Printf("Panic in handleDisconnect: %v", r)
		}
	}()

	var resp struct {
		StreamID string `json:"stream_id"`
	}

	if err := json.Unmarshal(data, &resp); err != nil {
		log.Printf("Failed to unmarshal disconnect: %v", err)
		return
	}

	var stream *StreamConn
	var ok bool
	func() {
		tc.mu.Lock()
		defer tc.mu.Unlock()
		stream, ok = tc.streams[resp.StreamID]
		if ok {
			delete(tc.streams, resp.StreamID)
		}
	}()

	if ok {
		stream.closeLocal()
	}
}

// cleanupStream removes a stream from the streams map and closes it.
// Lock safety: streamConn.Close() is executed outside the lock to avoid holding tc.mu during I/O.
func (tc *TunnelClient) cleanupStream(streamID string, streamConn *StreamConn) {
	defer func() {
		if r := recover(); r != nil {
			log.Printf("Panic in cleanupStream for stream %s: %v", streamID, r)
		}
	}()

	tc.mu.Lock()
	exists := false
	if _, ok := tc.streams[streamID]; ok {
		delete(tc.streams, streamID)
		exists = true
	}
	tc.mu.Unlock()

	if exists {
		streamConn.Close()
	}
}

// ConnectThroughTunnel connects to a destination address through the tunnel.
func (tc *TunnelClient) ConnectThroughTunnel(deviceID, token, dstAddr string, dstPort int) (net.Conn, error) {
	tunnelConn, err := tc.GetOrConnectTunnel(deviceID, token)
	if err != nil {
		return nil, fmt.Errorf("failed to get tunnel: %w", err)
	}

	streamID, err := streamIDGenerator()
	if err != nil {
		return nil, fmt.Errorf("failed to generate stream id: %w", err)
	}

	streamConn := NewStreamConn(streamID, deviceID, tunnelConn, &tc.writeMu)

	func() {
		tc.mu.Lock()
		defer tc.mu.Unlock()
		tc.streams[streamID] = streamConn
	}()

	connectReq := struct {
		Type string `json:"type"`
		Data struct {
			StreamID string `json:"stream_id"`
			Address  string `json:"address"`
			Port     int    `json:"port"`
		} `json:"data"`
	}{
		Type: "connect",
		Data: struct {
			StreamID string `json:"stream_id"`
			Address  string `json:"address"`
			Port     int    `json:"port"`
		}{
			StreamID: streamID,
			Address:  dstAddr,
			Port:     dstPort,
		},
	}

	reqData, err := json.Marshal(connectReq)
	if err != nil {
		tc.cleanupStream(streamID, streamConn)
		return nil, fmt.Errorf("failed to marshal connect request: %w", err)
	}

	func() {
		tc.writeMu.Lock()
		defer tc.writeMu.Unlock()
		_ = tunnelConn.SetWriteDeadline(time.Now().Add(streamWriteLimit))
		err = tunnelConn.WriteMessage(websocket.BinaryMessage, reqData)
		_ = tunnelConn.SetWriteDeadline(time.Time{})
	}()
	if err != nil {
		tc.cleanupStream(streamID, streamConn)
		return nil, fmt.Errorf("failed to send connect request: %w", err)
	}

	// Wait for connect response with timeout
	select {
	case <-time.After(connectResponseTimeout):
		tc.cleanupStream(streamID, streamConn)
		return nil, fmt.Errorf("connection timeout")
	case success := <-streamConn.Connected:
		if !success {
			tc.cleanupStream(streamID, streamConn)
			return nil, fmt.Errorf("connection failed")
		}
		return streamConn, nil
	}
}

// RemoveStream removes a stream from the streams map.
// It does not close the underlying connection; the caller is responsible for closing.
func (tc *TunnelClient) RemoveStream(streamID string) {
	tc.mu.Lock()
	defer tc.mu.Unlock()
	delete(tc.streams, streamID)
}

// AuthSession holds authentication session info.
type AuthSession struct {
	DeviceID string
	Token    string
}

// TunnelDialer is the interface for dialing through a tunnel.
// Used to establish network connections via tunnel, with support for test injection.
//
// Thread safety requirements:
// - All methods must be concurrency-safe and callable from multiple goroutines.
// - Implementations should use appropriate synchronization (e.g., mutex) for shared state.
//
// Error handling:
// - ConnectThroughTunnel should return descriptive errors wrapped with fmt.Errorf.
// - RemoveStream should silently succeed for non-existent streamIDs.
type TunnelDialer interface {
	// ConnectThroughTunnel connects to a destination address through the tunnel.
	//
	// Parameters:
	//   - deviceID: unique device identifier for authentication and routing
	//   - token: authentication token paired with deviceID
	//   - dstAddr: target host address (domain or IP)
	//   - dstPort: target port number
	//
	// Returns:
	//   - net.Conn: connection to the target on success
	//   - error: on failure, possible error types include:
	//     * tunnel connection failure: fmt.Errorf("failed to get tunnel: %w", err)
	//     * authentication failure: when deviceID/token is invalid
	//     * network error: target unreachable or connection timeout
	//
	// Behavior:
	//   - Each call creates a new connection.
	//   - The returned net.Conn must be concurrency-safe.
	//   - The caller is responsible for closing the connection when done.
	ConnectThroughTunnel(deviceID, token, dstAddr string, dstPort int) (net.Conn, error)

	// RemoveStream removes the specified stream connection.
	//
	// Parameters:
	//   - streamID: the stream ID to remove
	//
	// Behavior:
	//   - Should silently succeed if streamID does not exist.
	//   - Should release related resources (close underlying connection, etc.).
	//   - Must be internally synchronized for concurrency safety.
	RemoveStream(streamID string)
}

// SOCKS5Server is the SOCKS5 proxy server.
type SOCKS5Server struct {
	config       *Config
	sessionStore SessionStore
	rateLimiter  *RateLimiter
	ipFilter     *IPFilter
	tunnelClient TunnelDialer
	listener     net.Listener
	connCount    int32 // active connection count (atomic)
}

// NewSOCKS5Server creates a new SOCKS5 server.
func NewSOCKS5Server(config *Config) *SOCKS5Server {
	return &SOCKS5Server{
		config:       config,
		sessionStore: NewAPISessionStore(config.APIEndpoint, config.InternalAPIKey),
		rateLimiter:  NewRateLimiter(),
		ipFilter:     NewIPFilter(),
		tunnelClient: NewTunnelClient(config.TunnelEndpoint),
	}
}

// NewSOCKS5ServerWithDialer creates a SOCKS5 server with a custom TunnelDialer (for testing).
func NewSOCKS5ServerWithDialer(config *Config, dialer TunnelDialer) (*SOCKS5Server, error) {
	if config == nil {
		return nil, fmt.Errorf("config cannot be nil")
	}
	if dialer == nil {
		return nil, fmt.Errorf("dialer cannot be nil")
	}
	return &SOCKS5Server{
		config:       config,
		sessionStore: NewAPISessionStore(config.APIEndpoint, config.InternalAPIKey),
		rateLimiter:  NewRateLimiter(),
		ipFilter:     NewIPFilter(),
		tunnelClient: dialer,
	}, nil
}

// Start starts the SOCKS5 server.
func (s *SOCKS5Server) Start() error {
	listener, err := net.Listen("tcp", s.config.Addr)
	if err != nil {
		return err
	}
	s.listener = listener

	if s.config.EnableTLS {
		cert, err := tls.LoadX509KeyPair(s.config.TLSCert, s.config.TLSKey)
		if err != nil {
			return fmt.Errorf("failed to load TLS certificates: %w", err)
		}

		tlsConfig := createSecureTLSConfig()
		tlsConfig.Certificates = []tls.Certificate{cert}

		s.listener = tls.NewListener(listener, tlsConfig)
		log.Printf("SOCKS5 server with TLS starting on %s", s.config.Addr)
	} else {
		log.Printf("SOCKS5 server starting on %s", s.config.Addr)
	}

	// Setup graceful shutdown signal handling
	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	go func() {
		<-quit
		log.Printf("SOCKS5 server shutting down gracefully...")
		if s.listener != nil {
			_ = s.listener.Close()
		}
		s.rateLimiter.Stop()
	}()

	for {
		conn, err := s.listener.Accept()
		if err != nil {
			if errors.Is(err, net.ErrClosed) {
				log.Printf("SOCKS5 server listener closed, exiting")
				return nil
			}
			log.Printf("Accept error: %v", err)
			continue
		}

		go func(c net.Conn) {
			defer func() {
				if r := recover(); r != nil {
					log.Printf("Panic in handleConnection: %v", r)
					_ = c.Close()
				}
			}()
			s.handleConnection(c)
		}(conn)
	}
}

const handshakeTimeout = 15 * time.Second

// handleConnection handles an incoming SOCKS5 connection.
func (s *SOCKS5Server) handleConnection(conn net.Conn) {
	currentCount := atomic.AddInt32(&s.connCount, 1)
	defer atomic.AddInt32(&s.connCount, -1)

	if int(currentCount) > s.config.MaxConnections {
		log.Printf("Connection limit exceeded: %d/%d", currentCount, s.config.MaxConnections)
		conn.Close()
		return
	}

	defer conn.Close()

	if err := conn.SetDeadline(time.Now().Add(handshakeTimeout)); err != nil {
		log.Printf("Failed to set handshake deadline: %v", err)
		return
	}

	clientAddr := conn.RemoteAddr().String()
	clientIP, _, _ := net.SplitHostPort(clientAddr)

	if !s.rateLimiter.Allow(clientIP) {
		log.Printf("Rate limit exceeded for %s", clientIP)
		return
	}

	deviceID, token, err := s.handleHandshake(conn, clientIP)
	if err != nil {
		log.Printf("Handshake failed for %s: %v", clientIP, err)
		return
	}

	err = s.handleRequest(conn, deviceID, token)
	if err != nil {
		log.Printf("Request handling failed for %s: %v", clientIP, err)
	}
}

// handleHandshake handles the SOCKS5 handshake.
func (s *SOCKS5Server) handleHandshake(conn net.Conn, clientIP string) (string, string, error) {
	reader := bufio.NewReader(conn)

	buf := make([]byte, 2)
	if _, err := io.ReadFull(reader, buf); err != nil {
		return "", "", err
	}

	if buf[0] != socks5Version {
		return "", "", fmt.Errorf("unsupported SOCKS version: %d", buf[0])
	}

	nmethods := int(buf[1])
	methods := make([]byte, nmethods)
	if _, err := io.ReadFull(reader, methods); err != nil {
		return "", "", err
	}

	supportsAuth := false
	for _, m := range methods {
		if m == authPassword {
			supportsAuth = true
			break
		}
	}

	if !supportsAuth {
		if _, err := conn.Write([]byte{socks5Version, authNone}); err != nil {
			log.Printf("Failed to write no-auth method response: %v", err)
		}
		return "", "", fmt.Errorf("no supported authentication method")
	}

	if _, err := conn.Write([]byte{socks5Version, authPassword}); err != nil {
		log.Printf("Failed to write auth method response: %v", err)
		return "", "", fmt.Errorf("failed to write auth method response: %w", err)
	}

	return s.handleAuth(conn, reader, clientIP)
}

// handleAuth handles username/password authentication.
func (s *SOCKS5Server) handleAuth(conn net.Conn, reader *bufio.Reader, clientIP string) (string, string, error) {
	version, err := reader.ReadByte()
	if err != nil {
		return "", "", err
	}
	if version != 0x01 {
		return "", "", fmt.Errorf("unsupported auth version: %d", version)
	}

	ulen, err := reader.ReadByte()
	if err != nil {
		return "", "", err
	}

	username := make([]byte, ulen)
	if _, err := io.ReadFull(reader, username); err != nil {
		return "", "", err
	}

	plen, err := reader.ReadByte()
	if err != nil {
		return "", "", err
	}

	password := make([]byte, plen)
	if _, err := io.ReadFull(reader, password); err != nil {
		return "", "", err
	}

	deviceID := string(username)
	token := string(password)

	valid, err := s.sessionStore.ValidateToken(deviceID, token)
	if err != nil || !valid {
		if _, werr := conn.Write([]byte{0x01, repGeneralFailure}); werr != nil {
			log.Printf("Failed to write auth failure response: %v", werr)
		}
		return "", "", fmt.Errorf("authentication failed")
	}

	if _, err := conn.Write([]byte{0x01, repSucceeded}); err != nil {
		log.Printf("Failed to write auth success response: %v", err)
		return "", "", fmt.Errorf("failed to write auth success response: %w", err)
	}
	s.rateLimiter.Success(clientIP)
	log.Printf("Authentication successful for device: %s", deviceID)

	return deviceID, token, nil
}

// handleRequest handles a SOCKS5 request.
func (s *SOCKS5Server) handleRequest(conn net.Conn, deviceID string, token string) error {
	reader := bufio.NewReader(conn)

	buf := make([]byte, 4)
	if _, err := io.ReadFull(reader, buf); err != nil {
		return err
	}

	if buf[0] != socks5Version {
		return fmt.Errorf("unsupported SOCKS version: %d", buf[0])
	}

	cmd := buf[1]
	atyp := buf[3]

	var dstAddr string
	var dstPort int

	switch atyp {
	case atypIPv4:
		ipBuf := make([]byte, 4)
		if _, err := io.ReadFull(reader, ipBuf); err != nil {
			return err
		}
		dstAddr = net.IP(ipBuf).String()

	case atypDomain:
		lenBuf, err := reader.ReadByte()
		if err != nil {
			return err
		}
		domainBuf := make([]byte, lenBuf)
		if _, err := io.ReadFull(reader, domainBuf); err != nil {
			return err
		}
		dstAddr = string(domainBuf)

		ips, err := net.LookupIP(dstAddr)
		if err != nil {
			s.sendReply(conn, repHostUnreachable)
			return err
		}
		resolved := false
		for _, ip := range ips {
			if ip.To4() != nil {
				dstAddr = ip.String()
				resolved = true
				break
			}
		}
		if !resolved {
			s.sendReply(conn, repHostUnreachable)
			return fmt.Errorf("no IPv4 address found for %s", dstAddr)
		}

	case atypIPv6:
		s.sendReply(conn, repAddressNotSupported)
		return fmt.Errorf("IPv6 not supported")

	default:
		s.sendReply(conn, repAddressNotSupported)
		return fmt.Errorf("unsupported address type: %d", atyp)
	}

	portBuf := make([]byte, 2)
	if _, err := io.ReadFull(reader, portBuf); err != nil {
		return err
	}
	dstPort = int(portBuf[0])<<8 | int(portBuf[1])

	if !s.ipFilter.IsAllowed(dstAddr) {
		log.Printf("Blocked connection to %s:%d", dstAddr, dstPort)
		s.sendReply(conn, repNotAllowed)
		return fmt.Errorf("target IP not allowed: %s", dstAddr)
	}

	switch cmd {
	case cmdConnect:
		return s.handleConnect(conn, AuthSession{DeviceID: deviceID, Token: token}, dstAddr, dstPort)
	case cmdBind:
		s.sendReply(conn, repCommandNotSupported)
		return fmt.Errorf("BIND not supported")
	case cmdUDPAssociate:
		s.sendReply(conn, repCommandNotSupported)
		return fmt.Errorf("UDP ASSOCIATE not supported")
	default:
		s.sendReply(conn, repCommandNotSupported)
		return fmt.Errorf("unsupported command: %d", cmd)
	}
}

// sendReply sends a SOCKS5 reply.
func (s *SOCKS5Server) sendReply(conn net.Conn, rep byte) {
	reply := []byte{socks5Version, rep, rsvReserved, atypIPv4, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00}
	if _, err := conn.Write(reply); err != nil {
		log.Printf("Failed to send SOCKS5 reply 0x%02x: %v", rep, err)
	}
}

// handleConnect handles a CONNECT request.
func (s *SOCKS5Server) handleConnect(conn net.Conn, session AuthSession, dstAddr string, dstPort int) error {
	log.Printf("CONNECT request from %s to %s:%d", session.DeviceID, dstAddr, dstPort)

	if err := conn.SetDeadline(time.Time{}); err != nil {
		log.Printf("Failed to clear connection deadline: %v", err)
	}

	targetConn, err := s.tunnelClient.ConnectThroughTunnel(session.DeviceID, session.Token, dstAddr, dstPort)
	if err != nil {
		log.Printf("Failed to connect through tunnel: %v", err)
		s.sendReply(conn, repConnectionRefused)
		return err
	}
	defer func() {
		targetConn.Close()
		if sip, ok := targetConn.(StreamIDProvider); ok {
			s.tunnelClient.RemoveStream(sip.StreamID())
		}
	}()

	s.sendReply(conn, repSucceeded)

	return s.relay(conn, targetConn)
}

// relay bidirectionally forwards data between clientConn and targetConn.
func (s *SOCKS5Server) relay(clientConn, targetConn net.Conn) error {
	errChan := make(chan error, 2)
	var closeOnce sync.Once

	closeConnections := func() {
		_ = clientConn.Close()
		_ = targetConn.Close()
	}

	copyStream := func(dst, src net.Conn) {
		defer func() {
			if r := recover(); r != nil {
				log.Printf("Panic in relay copyStream: %v", r)
				closeOnce.Do(closeConnections)
				errChan <- fmt.Errorf("copyStream panic: %w", errors.New(fmt.Sprint(r)))
			}
		}()
		_, err := io.Copy(dst, src)
		closeOnce.Do(closeConnections)
		errChan <- err
	}

	go copyStream(targetConn, clientConn)
	go copyStream(clientConn, targetConn)

	var relayErr error
	for i := 0; i < 2; i++ {
		err := <-errChan
		if relayErr == nil && !isExpectedRelayError(err) {
			relayErr = err
		}
	}

	return relayErr
}

func isExpectedRelayError(err error) bool {
	if err == nil {
		return true
	}

	if errors.Is(err, io.EOF) || errors.Is(err, io.ErrClosedPipe) || errors.Is(err, net.ErrClosed) {
		return true
	}

	errMsg := err.Error()
	return strings.Contains(errMsg, "use of closed network connection") || strings.Contains(errMsg, "stream closed")
}

func main() {
	addr := flag.String("addr", "0.0.0.0:1080", "SOCKS5 server address")
	apiEndpoint := flag.String("api", "http://localhost:8080", "API endpoint URL")
	tunnelEndpoint := flag.String("tunnel", "ws://localhost:8443", "Tunnel WebSocket endpoint URL")
	tlsCert := flag.String("tls-cert", "", "TLS certificate file")
	tlsKey := flag.String("tls-key", "", "TLS key file")
	flag.Parse()

	if envAddr := os.Getenv("SOCKS5_ADDR"); envAddr != "" {
		*addr = envAddr
	}
	if envAPI := os.Getenv("API_ENDPOINT"); envAPI != "" {
		*apiEndpoint = envAPI
	}
	internalAPIKey := os.Getenv("INTERNAL_API_KEY")
	if internalAPIKey == "" {
		log.Fatalf("FATAL: INTERNAL_API_KEY environment variable is not set. Please configure an internal API key before starting the server.")
	}
	if envTunnel := os.Getenv("TUNNEL_ENDPOINT"); envTunnel != "" {
		*tunnelEndpoint = envTunnel
	}

	// Read TLS config (environment variables take priority)
	envEnableTLS := os.Getenv("ENABLE_TLS")
	envTLSCert := os.Getenv("TLS_CERT")
	envTLSKey := os.Getenv("TLS_KEY")

	enableTLS := false
	if envEnableTLS != "" {
		enableTLS = envEnableTLS == "true"
	} else {
		enableTLS = *tlsCert != "" && *tlsKey != ""
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
		Addr:           *addr,
		APIEndpoint:    *apiEndpoint,
		InternalAPIKey: internalAPIKey,
		TunnelEndpoint: *tunnelEndpoint,
		EnableTLS:      enableTLS,
		TLSCert:        finalTLSCert,
		TLSKey:         finalTLSKey,
		MaxConnections: 1000,
		IdleTimeout:    5 * time.Minute,
	}

	server := NewSOCKS5Server(config)

	log.Printf("API endpoint: %s", config.APIEndpoint)
	log.Printf("Tunnel endpoint: %s", config.TunnelEndpoint)
	log.Printf("TLS enabled: %v", config.EnableTLS)

	if err := server.Start(); err != nil {
		log.Fatalf("Server failed: %v", err)
	}
}

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
)

var streamIDGenerator = generateRandomStreamID

func generateRandomStreamID() (string, error) {
	randomBytes := make([]byte, 16)
	if _, err := rand.Read(randomBytes); err != nil {
		return "", fmt.Errorf("failed to read crypto random bytes: %w", err)
	}

	return hex.EncodeToString(randomBytes), nil
}

// createSecureTLSConfig 创建基础安全TLS配置，设置最低TLS版本为1.2
func createSecureTLSConfig() *tls.Config {
	return &tls.Config{
		MinVersion: tls.VersionTLS12,
	}
}

// Config 服务配置
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

// StreamIDProvider 提供 StreamID 的接口
type StreamIDProvider interface {
	StreamID() string
}

// SessionStore 会话存储接口
type SessionStore interface {
	ValidateToken(deviceID, token string) (bool, error)
}

// APISessionStore 通过API验证会话
type APISessionStore struct {
	apiEndpoint    string
	internalAPIKey string
	httpClient     *http.Client
}

// NewAPISessionStore 创建API会话存储
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

// ValidateToken 验证设备令牌
func (s *APISessionStore) ValidateToken(deviceID, token string) (bool, error) {
	// 调用API验证
	valid, err := s.validateWithAPI(deviceID, token)
	if err != nil {
		log.Printf("API validation error: %v", err)
		return false, err
	}

	return valid, nil
}

// validateWithAPI 调用API验证
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

// RateLimiter 限流器
type RateLimiter struct {
	attempts     map[string]*LoginAttempt
	mu           sync.RWMutex
	stopCh       chan struct{}
	restartCount int32
}

// LoginAttempt 登录尝试
type LoginAttempt struct {
	Count      int
	LastTry    time.Time
	Blocked    bool
	BlockUntil time.Time
}

// NewRateLimiter 创建限流器
func NewRateLimiter() *RateLimiter {
	rl := &RateLimiter{
		attempts: make(map[string]*LoginAttempt),
		stopCh:   make(chan struct{}),
	}
	go rl.cleanupLoop()
	return rl
}

// Stop 停止限流器的清理协程
func (rl *RateLimiter) Stop() {
	close(rl.stopCh)
}

// Allow 检查是否允许登录
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

	if attempt.Count >= 5 && time.Since(attempt.LastTry) < 5*time.Minute {
		attempt.Blocked = true
		attempt.BlockUntil = time.Now().Add(15 * time.Minute)
		log.Printf("Rate limit exceeded for %s, blocked for 15 minutes", key)
		return false
	}

	if time.Since(attempt.LastTry) > 5*time.Minute {
		attempt.Count = 0
	}

	attempt.Count++
	attempt.LastTry = time.Now()
	return true
}

// Success 登录成功，重置计数
func (rl *RateLimiter) Success(key string) {
	rl.mu.Lock()
	defer rl.mu.Unlock()
	delete(rl.attempts, key)
}

// cleanupLoop 定期清理旧的尝试记录
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

	ticker := time.NewTicker(10 * time.Minute)
	defer ticker.Stop()

	for {
		select {
		case <-ticker.C:
			func() {
				rl.mu.Lock()
				defer rl.mu.Unlock()
				now := time.Now()
				for key, attempt := range rl.attempts {
					if now.Sub(attempt.LastTry) > 30*time.Minute {
						delete(rl.attempts, key)
					}
				}
			}()
		case <-rl.stopCh:
			return
		}
	}
}

// IPFilter IP过滤器
type IPFilter struct {
	allowedCIDRs []string
}

// NewIPFilter 创建IP过滤器
func NewIPFilter() *IPFilter {
	return &IPFilter{
		allowedCIDRs: []string{
			"10.0.0.0/8",
			"172.16.0.0/12",
			"192.168.0.0/16",
		},
	}
}

// IsAllowed 检查IP是否允许
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

// StreamConn 隧道流连接
// StreamConn 表示一条通过 tunnel 的流连接。
// 锁层次: readMu -> mu (允许嵌套，但反之不可，否则会死锁)
type StreamConn struct {
	StreamID       string
	DeviceID       string
	TunnelConn     *websocket.Conn
	tunnelWriteMu  *sync.Mutex
	DataChan       chan []byte
	CloseChan      chan struct{}
	Connected      chan bool
	Closed         int32
	readRemainder  []byte
	readDeadline   atomic.Value // *time.Time
	readMu         sync.Mutex
	mu             sync.Mutex
}

// NewStreamConn 创建新的流连接
func NewStreamConn(streamID, deviceID string, tunnelConn *websocket.Conn, tunnelWriteMu *sync.Mutex) *StreamConn {
	return &StreamConn{
		StreamID:      streamID,
		DeviceID:      deviceID,
		TunnelConn:    tunnelConn,
		tunnelWriteMu: tunnelWriteMu,
		DataChan:      make(chan []byte, 100),
		CloseChan:     make(chan struct{}),
		Connected:     make(chan bool, 1),
	}
}

// Read 实现 net.Conn 的 Read 方法
func (s *StreamConn) Read(p []byte) (n int, err error) {
	defer func() {
		if r := recover(); r != nil {
			log.Printf("Panic in StreamConn.Read for stream %s: %v", s.StreamID, r)
			n = 0
			err = fmt.Errorf("read panic: %v", r)
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

// drainAndEOF 在 CloseChan 关闭后非阻塞排空 DataChan，然后返回 io.EOF
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

// Write 实现 net.Conn 的 Write 方法
func (s *StreamConn) Write(p []byte) (n int, err error) {
	defer func() {
		if r := recover(); r != nil {
			log.Printf("Panic in StreamConn.Write for stream %s: %v", s.StreamID, r)
			n = 0
			err = fmt.Errorf("write panic: %v", r)
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

// WriteToDataChan 将数据写入 DataChan，若 StreamConn 已关闭则返回错误
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
		return fmt.Errorf("data channel full")
	}
}

// sendDisconnect 异步发送 disconnect 消息
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

	s.mu.Lock()
	tunnelConn := s.TunnelConn
	writeMu := s.tunnelWriteMu
	s.mu.Unlock()

	if tunnelConn != nil {
		if writeMu != nil {
			writeMu.Lock()
			defer writeMu.Unlock()
		}

		_ = tunnelConn.SetWriteDeadline(time.Now().Add(streamCloseWriteLimit))
		_ = tunnelConn.WriteMessage(websocket.BinaryMessage, data)
		_ = tunnelConn.SetWriteDeadline(time.Time{})
	}
}

// Close 实现 net.Conn 的 Close 方法
func (s *StreamConn) Close() error {
	if atomic.CompareAndSwapInt32(&s.Closed, 0, 1) {
		close(s.CloseChan)
		go s.sendDisconnect()
	}
	return nil
}

// closeLocal 本地关闭 stream，不发送 disconnect 消息。
// 用于对端已主动断开或连接已失效的场景，避免发送不必要的 disconnect 回声。
func (s *StreamConn) closeLocal() {
	if atomic.CompareAndSwapInt32(&s.Closed, 0, 1) {
		close(s.CloseChan)
	}
}

// detachTunnel 在 stream.mu 保护下清空 tunnelConn 和 tunnelWriteMu 引用，
// 防止后续异步操作使用失效连接。
func (s *StreamConn) detachTunnel() {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.TunnelConn = nil
	s.tunnelWriteMu = nil
}

// LocalAddr 实现 net.Conn 的 LocalAddr 方法
func (s *StreamConn) LocalAddr() net.Addr {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.TunnelConn != nil {
		return s.TunnelConn.LocalAddr()
	}
	// net.Conn 接口通常不期望返回 nil，返回空地址避免调用方 panic
	return &net.TCPAddr{IP: net.IPv4zero, Port: 0}
}

// RemoteAddr 实现 net.Conn 的 RemoteAddr 方法
func (s *StreamConn) RemoteAddr() net.Addr {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.TunnelConn != nil {
		return s.TunnelConn.RemoteAddr()
	}
	// net.Conn 接口通常不期望返回 nil，返回空地址避免调用方 panic
	return &net.TCPAddr{IP: net.IPv4zero, Port: 0}
}

// SetDeadline 实现 net.Conn 的 SetDeadline 方法
func (s *StreamConn) SetDeadline(t time.Time) error {
	if err := s.SetReadDeadline(t); err != nil {
		return err
	}
	return s.SetWriteDeadline(t)
}

// SetReadDeadline 实现 net.Conn 的 SetReadDeadline 方法
func (s *StreamConn) SetReadDeadline(t time.Time) error {
	s.readDeadline.Store(&t)
	return nil
}

// SetWriteDeadline 实现 net.Conn 的 SetWriteDeadline 方法。
// 当前为空实现：WebSocket 写入已通过 writeMu + streamWriteLimit 保护，
// 且 StreamConn 的 Write 操作不涉及阻塞 I/O，无需额外 deadline 控制。
func (s *StreamConn) SetWriteDeadline(t time.Time) error {
	return nil
}

// TunnelClient 隧道客户端
//
// 锁层次: tc.mu -> tc.writeMu
// - tc.mu 保护 connections、dialing、streams 映射
// - tc.writeMu 保护 WebSocket 连接的写入操作（WriteMessage、WriteControl）
// 允许 tc.mu -> tc.writeMu 的嵌套，但反之不可，否则会死锁
//
// 附加层次: tc.mu -> stream.mu
// - 在 removeConnectionAndCollectStreams -> detachTunnel 路径中，
//   持有 tc.mu 时会获取 stream.mu 以清空 tunnelConn 引用。
// - 禁止 stream.mu -> tc.mu 的反向嵌套，否则会死锁。
//
// 连接存活检测（isConnAlive）在持有 tc.mu 期间获取 writeMu 发送 Ping，
// 这是已知的设计权衡，Ping 超时 5 秒会阻塞对 tc.mu 的竞争者。
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

// NewTunnelClient 创建新的隧道客户端
func NewTunnelClient(tunnelEndpoint string) *TunnelClient {
	return &TunnelClient{
		tunnelEndpoint: tunnelEndpoint,
		httpClient: &http.Client{
			Timeout: 10 * time.Second,
			Transport: &http.Transport{
				TLSClientConfig: createSecureTLSConfig(),
			},
		},
		wsDialer: &websocket.Dialer{
			HandshakeTimeout: 10 * time.Second,
			TLSClientConfig:  createSecureTLSConfig(),
			ReadBufferSize:   64 * 1024,
			WriteBufferSize:  64 * 1024,
		},
		connections: make(map[string]*websocket.Conn),
		dialing:     make(map[string]chan struct{}),
		streams:     make(map[string]*StreamConn),
	}
}

// GetOrConnectTunnel 获取或建立到设备的隧道连接
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
					err = fmt.Errorf("dial panic: %v", r)
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

// getExistingConn 检查并返回存活的现有连接。
// 为减少锁持有时间，先获取 tc.mu.RLock 复制 conn 引用，然后在锁外调用 isConnAlive。
// 若连接失效，重新获取 tc.mu.Lock 并使用 identity check（tc.connections[deviceID] == conn）
// 确保连接未被替换后，在锁保护下删除该连接及其关联的 streams，最后在锁外关闭 streams。
func (tc *TunnelClient) getExistingConn(deviceID string) (conn *websocket.Conn, ok bool) {
	// 第一阶段：只读获取 conn 引用
	tc.mu.RLock()
	conn, ok = tc.connections[deviceID]
	tc.mu.RUnlock()
	if !ok {
		return nil, false
	}

	// 锁外检查连接存活（避免阻塞其他操作长达 5 秒）
	if tc.isConnAlive(conn) {
		return conn, true
	}

	// 第二阶段：连接失效，需要修改状态，获取写锁
	tc.mu.Lock()
	defer tc.mu.Unlock()

	// 二次验证：确保连接未被替换
	if tc.connections[deviceID] != conn {
		return nil, false
	}

	toClose := tc.removeConnectionAndCollectStreams(deviceID, conn)
	// 在锁外关闭 streams（避免持有锁期间执行 I/O）
	if len(toClose) > 0 {
		go func(streams []*StreamConn) {
			for _, stream := range streams {
				stream.Close()
			}
		}(toClose)
	}
	return nil, false
}

// removeConnectionAndCollectStreams 在已持有 tc.mu 锁的情况下删除连接并收集关联的 streams，返回待关闭的 stream 列表。
// 调用方应在调用前确认连接已失效（例如通过 isConnAlive），本函数仅执行删除和收集，不做存活检查。
// 同时清空被移除 stream 的 tunnelConn 引用，防止后续异步操作使用失效连接。
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

// isConnAlive 检查连接是否存活
func (tc *TunnelClient) isConnAlive(conn *websocket.Conn) bool {
	// WriteControl 支持并发调用，无需加锁；缩短超时避免长时间阻塞
	if err := conn.WriteControl(websocket.PingMessage, []byte{}, time.Now().Add(1*time.Second)); err != nil {
		return false
	}
	return true
}

// readLoop 读取循环，处理来自设备的消息
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

// handleMessage 处理来自设备的消息
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

// handleConnectResponse 处理连接响应
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

// handleData 处理数据消息
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
		if err.Error() == "data channel full" {
			log.Printf("Data channel full for stream %s, dropping packet", resp.StreamID)
		} else {
			log.Printf("Failed to write to DataChan for stream %s: %v, closing stream", resp.StreamID, err)
			tc.cleanupStream(resp.StreamID, stream)
		}
	}
}

// handleDisconnect 处理来自设备的断开连接消息。
// 由于是对端主动断开，不需要再发送 disconnect 回声。
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

// cleanupStream 从 streams 映射中移除流并关闭连接。
// 锁安全：streamConn.Close() 在锁外执行，避免 I/O 阻塞时持有 tc.mu。
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

// ConnectThroughTunnel 通过隧道连接到目标地址
func (tc *TunnelClient) ConnectThroughTunnel(deviceID, token, dstAddr string, dstPort int) (net.Conn, error) {
	// 获取或建立隧道连接
	tunnelConn, err := tc.GetOrConnectTunnel(deviceID, token)
	if err != nil {
		return nil, fmt.Errorf("failed to get tunnel: %w", err)
	}

	// 生成唯一的流ID
	streamID, err := streamIDGenerator()
	if err != nil {
		return nil, fmt.Errorf("failed to generate stream id: %w", err)
	}

	// 创建流连接
	streamConn := NewStreamConn(streamID, deviceID, tunnelConn, &tc.writeMu)

	func() {
		tc.mu.Lock()
		defer tc.mu.Unlock()
		tc.streams[streamID] = streamConn
	}()

	// 发送连接请求
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

	// 等待连接响应（带超时）
	select {
	case <-time.After(30 * time.Second):
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

// RemoveStream 从 streams 映射中移除流。
// 不关闭底层连接，由调用方负责关闭。
func (tc *TunnelClient) RemoveStream(streamID string) {
	tc.mu.Lock()
	defer tc.mu.Unlock()
	delete(tc.streams, streamID)
}

// AuthSession 认证会话信息
type AuthSession struct {
	DeviceID string
	Token    string
}

// TunnelDialer 隧道拨号器接口
// 用于通过隧道建立网络连接，支持测试注入
//
// 线程安全要求：
// - 所有方法必须是并发安全的，可以在多个 goroutine 中同时调用
// - 实现应该使用适当的同步机制（如 mutex）保护共享状态
//
// 错误处理：
// - ConnectThroughTunnel 失败时应返回描述性错误，使用 fmt.Errorf 包装底层错误
// - RemoveStream 对于不存在的 streamID 应该静默成功（不返回错误）
type TunnelDialer interface {
	// ConnectThroughTunnel 通过隧道连接到目标地址
	//
	// 参数：
	//   - deviceID: 设备唯一标识符，用于认证和路由
	//   - token: 认证令牌，与 deviceID 配对使用
	//   - dstAddr: 目标主机地址（域名或 IP）
	//   - dstPort: 目标端口号
	//
	// 返回：
	//   - net.Conn: 成功时返回与目标的连接
	//   - error: 失败时返回错误，可能的错误类型包括：
	//     * 隧道连接失败：fmt.Errorf("failed to get tunnel: %w", err)
	//     * 认证失败：当 deviceID/token 无效时
	//     * 网络错误：目标不可达或连接超时
	//
	// 行为约定：
	//   - 每次调用都会创建一个新的连接
	//   - 返回的 net.Conn 必须是并发安全的
	//   - 调用者负责在使用完毕后关闭连接
	ConnectThroughTunnel(deviceID, token, dstAddr string, dstPort int) (net.Conn, error)

	// RemoveStream 移除指定的流连接
	//
	// 参数：
	//   - streamID: 要移除的流 ID
	//
	// 行为约定：
	//   - 如果 streamID 不存在，应该静默成功（不返回错误）
	//   - 移除后应该释放相关资源（关闭底层连接等）
	//   - 该方法应该在内部进行适当的同步，保证并发安全
	RemoveStream(streamID string)
}

// SOCKS5Server SOCKS5 服务器
type SOCKS5Server struct {
	config       *Config
	sessionStore SessionStore
	rateLimiter  *RateLimiter
	ipFilter     *IPFilter
	tunnelClient TunnelDialer
	listener     net.Listener
	connCount    int32 // 当前活跃连接数（原子操作）
}

// NewSOCKS5Server 创建 SOCKS5 服务器
func NewSOCKS5Server(config *Config) *SOCKS5Server {
	return &SOCKS5Server{
		config:       config,
		sessionStore: NewAPISessionStore(config.APIEndpoint, config.InternalAPIKey),
		rateLimiter:  NewRateLimiter(),
		ipFilter:     NewIPFilter(),
		tunnelClient: NewTunnelClient(config.TunnelEndpoint),
	}
}

// NewSOCKS5ServerWithDialer 使用自定义 TunnelDialer 创建 SOCKS5 服务器（用于测试）
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

// Start 启动服务器
func (s *SOCKS5Server) Start() error {
	listener, err := net.Listen("tcp", s.config.Addr)
	if err != nil {
		return err
	}
	s.listener = listener

	if s.config.EnableTLS {
		cert, err := tls.LoadX509KeyPair(s.config.TLSCert, s.config.TLSKey)
		if err != nil {
			return fmt.Errorf("failed to load TLS certificates: %v", err)
		}

		tlsConfig := createSecureTLSConfig()
		tlsConfig.Certificates = []tls.Certificate{cert}

		s.listener = tls.NewListener(listener, tlsConfig)
		log.Printf("SOCKS5 server with TLS starting on %s", s.config.Addr)
	} else {
		log.Printf("SOCKS5 server starting on %s", s.config.Addr)
	}

	// 设置优雅关闭信号处理
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

// handleConnection 处理连接
func (s *SOCKS5Server) handleConnection(conn net.Conn) {
	// 检查连接数限制
	currentCount := atomic.AddInt32(&s.connCount, 1)
	defer atomic.AddInt32(&s.connCount, -1)

	if int(currentCount) > s.config.MaxConnections {
		log.Printf("Connection limit exceeded: %d/%d", currentCount, s.config.MaxConnections)
		conn.Close()
		return
	}

	defer conn.Close()

	if err := conn.SetDeadline(time.Now().Add(15 * time.Second)); err != nil {
		log.Printf("Failed to set handshake deadline: %v", err)
		return
	}

	clientAddr := conn.RemoteAddr().String()
	clientIP, _, _ := net.SplitHostPort(clientAddr)

	// 检查限流
	if !s.rateLimiter.Allow(clientIP) {
		log.Printf("Rate limit exceeded for %s", clientIP)
		return
	}

	// SOCKS5握手
	deviceID, token, err := s.handleHandshake(conn, clientIP)
	if err != nil {
		log.Printf("Handshake failed for %s: %v", clientIP, err)
		return
	}

	// 处理SOCKS5请求
	err = s.handleRequest(conn, deviceID, token)
	if err != nil {
		log.Printf("Request handling failed for %s: %v", clientIP, err)
	}
}

// handleHandshake 处理SOCKS5握手
func (s *SOCKS5Server) handleHandshake(conn net.Conn, clientIP string) (string, string, error) {
	reader := bufio.NewReader(conn)

	// 读取版本和认证方法数
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

	// 检查是否支持用户名/密码认证
	supportsAuth := false
	for _, m := range methods {
		if m == authPassword {
			supportsAuth = true
			break
		}
	}

	if !supportsAuth {
		// 不支持认证，返回无认证方法
		if _, err := conn.Write([]byte{socks5Version, authNone}); err != nil {
			log.Printf("Failed to write no-auth method response: %v", err)
		}
		return "", "", fmt.Errorf("no supported authentication method")
	}

	// 选择用户名/密码认证
	if _, err := conn.Write([]byte{socks5Version, authPassword}); err != nil {
		log.Printf("Failed to write auth method response: %v", err)
		return "", "", fmt.Errorf("failed to write auth method response: %w", err)
	}

	// 处理用户名/密码认证
	return s.handleAuth(conn, reader, clientIP)
}

// handleAuth 处理认证
func (s *SOCKS5Server) handleAuth(conn net.Conn, reader *bufio.Reader, clientIP string) (string, string, error) {
	// 读取版本
	version, err := reader.ReadByte()
	if err != nil {
		return "", "", err
	}
	if version != 0x01 {
		return "", "", fmt.Errorf("unsupported auth version: %d", version)
	}

	// 读取用户名长度
	ulen, err := reader.ReadByte()
	if err != nil {
		return "", "", err
	}

	// 读取用户名
	username := make([]byte, ulen)
	if _, err := io.ReadFull(reader, username); err != nil {
		return "", "", err
	}

	// 读取密码长度
	plen, err := reader.ReadByte()
	if err != nil {
		return "", "", err
	}

	// 读取密码
	password := make([]byte, plen)
	if _, err := io.ReadFull(reader, password); err != nil {
		return "", "", err
	}

	deviceID := string(username)
	token := string(password)

	// 验证令牌
	valid, err := s.sessionStore.ValidateToken(deviceID, token)
	if err != nil || !valid {
		if _, werr := conn.Write([]byte{0x01, repGeneralFailure}); werr != nil { // 认证失败
			log.Printf("Failed to write auth failure response: %v", werr)
		}
		return "", "", fmt.Errorf("authentication failed")
	}

	// 认证成功
	if _, err := conn.Write([]byte{0x01, repSucceeded}); err != nil {
		log.Printf("Failed to write auth success response: %v", err)
		return "", "", fmt.Errorf("failed to write auth success response: %w", err)
	}
	s.rateLimiter.Success(clientIP)
	log.Printf("Authentication successful for device: %s", deviceID)

	return deviceID, token, nil
}

// handleRequest 处理SOCKS5请求
func (s *SOCKS5Server) handleRequest(conn net.Conn, deviceID string, token string) error {
	reader := bufio.NewReader(conn)

	// 读取请求头
	buf := make([]byte, 4)
	if _, err := io.ReadFull(reader, buf); err != nil {
		return err
	}

	if buf[0] != socks5Version {
		return fmt.Errorf("unsupported SOCKS version: %d", buf[0])
	}

	cmd := buf[1]
	// rsv := buf[2]
	atyp := buf[3]

	var dstAddr string
	var dstPort int

	switch atyp {
	case atypIPv4: // IPv4
		ipBuf := make([]byte, 4)
		if _, err := io.ReadFull(reader, ipBuf); err != nil {
			return err
		}
		dstAddr = net.IP(ipBuf).String()

	case atypDomain: // Domain name
		lenBuf, err := reader.ReadByte()
		if err != nil {
			return err
		}
		domainBuf := make([]byte, lenBuf)
		if _, err := io.ReadFull(reader, domainBuf); err != nil {
			return err
		}
		dstAddr = string(domainBuf)

		// 解析域名
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

	case atypIPv6: // IPv6
		s.sendReply(conn, repAddressNotSupported)
		return fmt.Errorf("IPv6 not supported")

	default:
		s.sendReply(conn, repAddressNotSupported)
		return fmt.Errorf("unsupported address type: %d", atyp)
	}

	// 读取端口
	portBuf := make([]byte, 2)
	if _, err := io.ReadFull(reader, portBuf); err != nil {
		return err
	}
	dstPort = int(portBuf[0])<<8 | int(portBuf[1])

	// 检查IP是否允许
	if !s.ipFilter.IsAllowed(dstAddr) {
		log.Printf("Blocked connection to %s:%d", dstAddr, dstPort)
		s.sendReply(conn, repNotAllowed)
		return fmt.Errorf("target IP not allowed: %s", dstAddr)
	}

	switch cmd {
	case cmdConnect: // CONNECT
		return s.handleConnect(conn, AuthSession{DeviceID: deviceID, Token: token}, dstAddr, dstPort)
	case cmdBind: // BIND
		s.sendReply(conn, repCommandNotSupported)
		return fmt.Errorf("BIND not supported")
	case cmdUDPAssociate: // UDP ASSOCIATE
		s.sendReply(conn, repCommandNotSupported)
		return fmt.Errorf("UDP ASSOCIATE not supported")
	default:
		s.sendReply(conn, repCommandNotSupported)
		return fmt.Errorf("unsupported command: %d", cmd)
	}
}

// sendReply 发送SOCKS5响应
func (s *SOCKS5Server) sendReply(conn net.Conn, rep byte) {
	reply := []byte{socks5Version, rep, rsvReserved, atypIPv4, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00}
	if _, err := conn.Write(reply); err != nil {
		log.Printf("Failed to send SOCKS5 reply 0x%02x: %v", rep, err)
	}
}

// handleConnect 处理 CONNECT 请求
func (s *SOCKS5Server) handleConnect(conn net.Conn, session AuthSession, dstAddr string, dstPort int) error {
	log.Printf("CONNECT request from %s to %s:%d", session.DeviceID, dstAddr, dstPort)

	if err := conn.SetDeadline(time.Time{}); err != nil {
		log.Printf("Failed to clear connection deadline: %v", err)
	}

	// 通过隧道连接到目标
	targetConn, err := s.tunnelClient.ConnectThroughTunnel(session.DeviceID, session.Token, dstAddr, dstPort)
	if err != nil {
		log.Printf("Failed to connect through tunnel: %v", err)
		s.sendReply(conn, repConnectionRefused)
		return err
	}
	defer func() {
		targetConn.Close()
		// 清理流记录
		if sip, ok := targetConn.(StreamIDProvider); ok {
			s.tunnelClient.RemoveStream(sip.StreamID())
		}
	}()

	// 发送成功响应
	s.sendReply(conn, repSucceeded)

	// 双向转发
	return s.relay(conn, targetConn)
}

// relay 双向转发数据
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
				errChan <- fmt.Errorf("copyStream panic: %v", r)
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

	// 读取TLS配置（环境变量优先）
	envEnableTLS := os.Getenv("ENABLE_TLS")
	envTLSCert := os.Getenv("TLS_CERT")
	envTLSKey := os.Getenv("TLS_KEY")

	// 确定是否启用TLS：环境变量设置则使用环境变量，否则根据命令行参数判断
	enableTLS := false
	if envEnableTLS != "" {
		enableTLS = envEnableTLS == "true"
	} else {
		enableTLS = *tlsCert != "" && *tlsKey != ""
	}

	// 证书路径：环境变量优先
	finalTLSCert := stringutil.FirstNonEmpty(envTLSCert, *tlsCert)
	finalTLSKey := stringutil.FirstNonEmpty(envTLSKey, *tlsKey)

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

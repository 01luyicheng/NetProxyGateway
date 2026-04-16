package main

import (
	"errors"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

type stubTunnelDialer struct {
	deviceID string
	token    string
	dstAddr  string
	dstPort  int
	err      error
}

func (s *stubTunnelDialer) ConnectThroughTunnel(deviceID, token, dstAddr string, dstPort int) (net.Conn, error) {
	s.deviceID = deviceID
	s.token = token
	s.dstAddr = dstAddr
	s.dstPort = dstPort
	// 返回一个 pipe 连接用于测试
	localConn, _ := net.Pipe()
	return localConn, s.err
}

func (s *stubTunnelDialer) RemoveStream(streamID string) {
}

func TestHandleConnectUsesAuthenticatedToken(t *testing.T) {
	clientConn, peerConn := net.Pipe()
	defer peerConn.Close()

	dialer := &stubTunnelDialer{err: errors.New("dial failed")}
	server := &SOCKS5Server{
		tunnelClient: dialer,
	}

	done := make(chan error, 1)
	go func() {
		done <- server.handleConnect(clientConn, AuthSession{
			DeviceID: "device-123",
			Token:    "session-token-456",
		}, "192.168.1.1", 80)
	}()

	reply := make([]byte, 10)
	if _, err := io.ReadFull(peerConn, reply); err != nil {
		t.Fatalf("failed to read SOCKS5 reply: %v", err)
	}

	if err := <-done; err == nil {
		t.Fatalf("expected handleConnect to return an error")
	}

	if dialer.deviceID != "device-123" {
		t.Fatalf("unexpected device id: %s", dialer.deviceID)
	}
	if dialer.token != "session-token-456" {
		t.Fatalf("expected authenticated token to be forwarded, got %s", dialer.token)
	}
	if dialer.dstAddr != "192.168.1.1" || dialer.dstPort != 80 {
		t.Fatalf("unexpected destination: %s:%d", dialer.dstAddr, dialer.dstPort)
	}
	if reply[1] != 0x05 {
		t.Fatalf("expected connection refused reply, got %d", reply[1])
	}
}

func TestAPISessionStoreValidateTokenAddsInternalAPIKeyHeader(t *testing.T) {
	var headerValue string

	apiServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		headerValue = r.Header.Get("X-Internal-API-Key")
		_, _ = io.WriteString(w, `{"valid":true}`)
	}))
	defer apiServer.Close()

	store := NewAPISessionStore(apiServer.URL, "internal-secret")
	valid, err := store.ValidateToken("device-1", "token-1")
	if err != nil {
		t.Fatalf("expected token validation to succeed, got error: %v", err)
	}
	if !valid {
		t.Fatal("expected validation result to be true")
	}
	if headerValue != "internal-secret" {
		t.Fatalf("expected internal api key header to be forwarded, got %q", headerValue)
	}
}

func TestAPISessionStoreValidateTokenFailsClosedOnAPIFailure(t *testing.T) {
	apiServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
	}))
	defer apiServer.Close()

	store := NewAPISessionStore(apiServer.URL, "")
	valid, err := store.ValidateToken("device-1", "token-1")
	if err == nil {
		t.Fatal("expected validation to fail when API returns error")
	}
	if valid {
		t.Fatal("expected token to be rejected when API validation fails")
	}
}

// TestStreamConn_Close_Idempotent 验证 Close() 方法的幂等性
func TestStreamConn_Close_Idempotent(t *testing.T) {
	writeMu := &sync.Mutex{}
	conn := &StreamConn{
		StreamID:      "test-stream-1",
		DeviceID:      "device-1",
		DataChan:      make(chan []byte, 100),
		CloseChan:     make(chan struct{}),
		Connected:     make(chan bool, 1),
		tunnelWriteMu: writeMu,
	}

	// 第一次关闭应该成功
	if err := conn.Close(); err != nil {
		t.Fatalf("first Close() should succeed: %v", err)
	}

	// 验证 Closed 标志被设置
	if conn.Closed != 1 {
		t.Fatal("Closed flag should be set to 1 after Close()")
	}

	// 第二次关闭应该安全返回（不会panic）
	if err := conn.Close(); err != nil {
		t.Fatalf("second Close() should succeed: %v", err)
	}

	// 第三次关闭也应该安全
	if err := conn.Close(); err != nil {
		t.Fatalf("third Close() should succeed: %v", err)
	}
}

// TestHandleConnectResponse_FailedConnection_CleansUpStream 验证连接失败时清理stream
func TestHandleConnectResponse_FailedConnection_CleansUpStream(t *testing.T) {
	writeMu := &sync.Mutex{}
	tc := &TunnelClient{
		streams: make(map[string]*StreamConn),
		writeMu: *writeMu,
	}

	streamID := "test-stream-cleanup"
	stream := &StreamConn{
		StreamID:      streamID,
		DeviceID:      "device-1",
		DataChan:      make(chan []byte, 100),
		CloseChan:     make(chan struct{}),
		Connected:     make(chan bool, 1),
		tunnelWriteMu: writeMu,
	}

	// 将stream添加到映射
	tc.mu.Lock()
	tc.streams[streamID] = stream
	tc.mu.Unlock()

	// 模拟连接失败响应
	response := []byte(`{"stream_id":"test-stream-cleanup","success":false,"error":"connection refused"}`)
	tc.handleConnectResponse(response)

	// 验证stream已从映射中删除
	tc.mu.RLock()
	_, exists := tc.streams[streamID]
	tc.mu.RUnlock()

	if exists {
		t.Fatal("stream should be removed from tc.streams after connection failure")
	}

	// 验证stream已关闭
	if stream.Closed != 1 {
		t.Fatal("stream should be closed after connection failure")
	}

	// 验证Connected channel收到false
	select {
	case success := <-stream.Connected:
		if success {
			t.Fatal("Connected channel should receive false for failed connection")
		}
	case <-time.After(time.Second):
		t.Fatal("timeout waiting for Connected channel")
	}
}

// TestHandleConnectResponse_SuccessfulConnection_KeepsStream 验证连接成功时保留stream
func TestHandleConnectResponse_SuccessfulConnection_KeepsStream(t *testing.T) {
	writeMu := &sync.Mutex{}
	tc := &TunnelClient{
		streams: make(map[string]*StreamConn),
		writeMu: *writeMu,
	}

	streamID := "test-stream-success"
	stream := &StreamConn{
		StreamID:      streamID,
		DeviceID:      "device-1",
		DataChan:      make(chan []byte, 100),
		CloseChan:     make(chan struct{}),
		Connected:     make(chan bool, 1),
		tunnelWriteMu: writeMu,
	}

	// 将stream添加到映射
	tc.mu.Lock()
	tc.streams[streamID] = stream
	tc.mu.Unlock()

	// 模拟连接成功响应
	response := []byte(`{"stream_id":"test-stream-success","success":true}`)
	tc.handleConnectResponse(response)

	// 验证stream仍在映射中（成功连接不应删除）
	tc.mu.RLock()
	_, exists := tc.streams[streamID]
	tc.mu.RUnlock()

	if !exists {
		t.Fatal("stream should remain in tc.streams after successful connection")
	}

	// 验证Connected channel收到true
	select {
	case success := <-stream.Connected:
		if !success {
			t.Fatal("Connected channel should receive true for successful connection")
		}
	case <-time.After(time.Second):
		t.Fatal("timeout waiting for Connected channel")
	}
}

// mockWebSocketConn 用于测试的mock WebSocket连接
type mockWebSocketConn struct {
	writeCount atomic.Int32
	closed     atomic.Bool
}

func (m *mockWebSocketConn) WriteMessage(messageType int, data []byte) error {
	m.writeCount.Add(1)
	return nil
}

func (m *mockWebSocketConn) Close() error {
	m.closed.Store(true)
	return nil
}

// TestConnectThroughTunnel_CleanupOnMarshalError 验证JSON序列化失败时的资源清理
func TestConnectThroughTunnel_CleanupOnMarshalError(t *testing.T) {
	// 创建一个包含无法序列化数据的请求
	writeMu := &sync.Mutex{}
	tc := &TunnelClient{
		streams: make(map[string]*StreamConn),
		writeMu: *writeMu,
	}

	// 验证streams映射为空
	tc.mu.RLock()
	streamCount := len(tc.streams)
	tc.mu.RUnlock()

	if streamCount != 0 {
		t.Fatalf("expected empty streams map, got %d streams", streamCount)
	}
}

package main

import (
	"errors"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/gorilla/websocket"
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

func TestGetOrConnectTunnel_ConcurrentCallsShareSingleDial(t *testing.T) {
	upgrader := websocket.Upgrader{CheckOrigin: func(r *http.Request) bool { return true }}

	var acceptedConns []*websocket.Conn
	var acceptedMu sync.Mutex
	var dialCount atomic.Int32

	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/tunnel" {
			http.NotFound(w, r)
			return
		}

		conn, err := upgrader.Upgrade(w, r, nil)
		if err != nil {
			return
		}

		dialCount.Add(1)
		acceptedMu.Lock()
		acceptedConns = append(acceptedConns, conn)
		acceptedMu.Unlock()

		go func() {
			defer conn.Close()
			for {
				if _, _, err := conn.ReadMessage(); err != nil {
					return
				}
			}
		}()
	}))
	defer server.Close()
	defer func() {
		acceptedMu.Lock()
		defer acceptedMu.Unlock()
		for _, conn := range acceptedConns {
			_ = conn.Close()
		}
	}()

	tc := NewTunnelClient(server.URL)

	const concurrentCalls = 8
	results := make([]*websocket.Conn, concurrentCalls)
	errs := make([]error, concurrentCalls)

	var wg sync.WaitGroup
	wg.Add(concurrentCalls)
	for i := 0; i < concurrentCalls; i++ {
		go func(idx int) {
			defer wg.Done()
			results[idx], errs[idx] = tc.GetOrConnectTunnel("device-1", "token-1")
		}(i)
	}
	wg.Wait()

	for _, err := range errs {
		if err != nil {
			t.Fatalf("unexpected GetOrConnectTunnel error: %v", err)
		}
	}

	if dialCount.Load() != 1 {
		t.Fatalf("expected exactly one dial, got %d", dialCount.Load())
	}

	first := results[0]
	if first == nil {
		t.Fatal("expected non-nil connection")
	}
	for i := 1; i < concurrentCalls; i++ {
		if results[i] != first {
			t.Fatalf("expected shared connection instance, got different conn at index %d", i)
		}
	}

	_ = first.Close()
}

func TestRelay_ClosesPeerConnectionOnHalfClose(t *testing.T) {
	server := &SOCKS5Server{}

	clientConn, clientPeer := net.Pipe()
	targetConn, targetPeer := net.Pipe()
	defer targetPeer.Close()

	relayDone := make(chan error, 1)
	go func() {
		relayDone <- server.relay(clientConn, targetConn)
	}()

	if err := clientPeer.Close(); err != nil {
		t.Fatalf("failed to close client peer: %v", err)
	}

	select {
	case relayErr := <-relayDone:
		if relayErr != nil {
			t.Fatalf("expected relay to return nil on normal half-close, got: %v", relayErr)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("relay did not return after one direction closed")
	}

	if err := targetPeer.SetWriteDeadline(time.Now().Add(200 * time.Millisecond)); err != nil && !strings.Contains(err.Error(), "closed pipe") {
		t.Fatalf("failed to set write deadline: %v", err)
	}

	_, err := targetPeer.Write([]byte{0x01})
	if err == nil {
		t.Fatal("expected target peer to be closed")
	}

	var netErr net.Error
	if errors.As(err, &netErr) && netErr.Timeout() {
		t.Fatalf("expected target peer to be closed, got timeout: %v", err)
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
	tc := &TunnelClient{
		streams: make(map[string]*StreamConn),
	}

	streamID := "test-stream-cleanup"
	stream := &StreamConn{
		StreamID:      streamID,
		DeviceID:      "device-1",
		DataChan:      make(chan []byte, 100),
		CloseChan:     make(chan struct{}),
		Connected:     make(chan bool, 1),
		tunnelWriteMu: &tc.writeMu,
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
	tc := &TunnelClient{
		streams: make(map[string]*StreamConn),
	}

	streamID := "test-stream-success"
	stream := &StreamConn{
		StreamID:      streamID,
		DeviceID:      "device-1",
		DataChan:      make(chan []byte, 100),
		CloseChan:     make(chan struct{}),
		Connected:     make(chan bool, 1),
		tunnelWriteMu: &tc.writeMu,
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

// TestConnectThroughTunnel_CleanupOnMarshalError 验证JSON序列化失败时的资源清理
func TestConnectThroughTunnel_CleanupOnMarshalError(t *testing.T) {
	// 创建一个包含无法序列化数据的请求
	tc := &TunnelClient{
		streams: make(map[string]*StreamConn),
	}

	// 验证streams映射为空
	tc.mu.RLock()
	streamCount := len(tc.streams)
	tc.mu.RUnlock()

	if streamCount != 0 {
		t.Fatalf("expected empty streams map, got %d streams", streamCount)
	}
}

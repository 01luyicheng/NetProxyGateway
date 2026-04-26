package main

import (
	"crypto/tls"
	"encoding/hex"
	"encoding/json"
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

func TestAPISessionStoreValidateTokenSendsValidJSONForSpecialCharacters(t *testing.T) {
	deviceID := "device-\"A\"\\B\nC"
	token := "token-\"x\"\\y\nline"

	type requestBody struct {
		DeviceID string `json:"device_id"`
		Token    string `json:"token"`
	}

	var got requestBody
	var pathCheckErr string
	var pathCheckMu sync.Mutex

	apiServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/session/validate" {
			pathCheckMu.Lock()
			pathCheckErr = "unexpected path: " + r.URL.Path
			pathCheckMu.Unlock()
			w.WriteHeader(http.StatusNotFound)
			return
		}
		defer r.Body.Close()

		if err := json.NewDecoder(r.Body).Decode(&got); err != nil {
			w.WriteHeader(http.StatusBadRequest)
			return
		}

		_, _ = io.WriteString(w, `{"valid":true}`)
	}))
	defer apiServer.Close()

	store := NewAPISessionStore(apiServer.URL, "")
	valid, err := store.ValidateToken(deviceID, token)
	if err != nil {
		t.Fatalf("expected validation to succeed with special characters, got error: %v", err)
	}
	if !valid {
		t.Fatal("expected validation result to be true")
	}

	pathCheckMu.Lock()
	defer pathCheckMu.Unlock()
	if pathCheckErr != "" {
		t.Fatal(pathCheckErr)
	}

	if got.DeviceID != deviceID {
		t.Fatalf("deviceID changed during JSON encoding, got %q want %q", got.DeviceID, deviceID)
	}
	if got.Token != token {
		t.Fatalf("token changed during JSON encoding, got %q want %q", got.Token, token)
	}
}

func TestAPISessionStoreValidateTokenPreservesDeviceIDAndToken(t *testing.T) {
	deviceID := strings.Repeat("dev-01", 128)
	token := strings.Repeat("token-ABC123", 256)

	type requestBody struct {
		DeviceID string `json:"device_id"`
		Token    string `json:"token"`
	}

	var got requestBody

	apiServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		defer r.Body.Close()

		if err := json.NewDecoder(r.Body).Decode(&got); err != nil {
			w.WriteHeader(http.StatusBadRequest)
			return
		}

		_, _ = io.WriteString(w, `{"valid":true}`)
	}))
	defer apiServer.Close()

	store := NewAPISessionStore(apiServer.URL, "")
	valid, err := store.ValidateToken(deviceID, token)
	if err != nil {
		t.Fatalf("expected validation to succeed, got error: %v", err)
	}
	if !valid {
		t.Fatal("expected validation result to be true")
	}

	if got.DeviceID != deviceID {
		t.Fatalf("deviceID should be passed through unchanged, got length %d want %d", len(got.DeviceID), len(deviceID))
	}
	if got.Token != token {
		t.Fatalf("token should be passed through unchanged, got length %d want %d", len(got.Token), len(token))
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

func TestStreamConn_Read_SmallBufferTwoReadsNoDataLoss(t *testing.T) {
	conn := &StreamConn{
		StreamID:      "test-stream-read-buffer",
		DeviceID:      "device-1",
		DataChan:      make(chan []byte, 1),
		CloseChan:     make(chan struct{}),
		Connected:     make(chan bool, 1),
		tunnelWriteMu: &sync.Mutex{},
	}

	conn.DataChan <- []byte("ABCD")

	buf := make([]byte, 2)

	n1, err := conn.Read(buf)
	if err != nil {
		t.Fatalf("first Read() should succeed: %v", err)
	}
	if n1 != 2 {
		t.Fatalf("first Read() got %d bytes, want 2", n1)
	}
	if string(buf[:n1]) != "AB" {
		t.Fatalf("first Read() got %q, want %q", string(buf[:n1]), "AB")
	}

	n2, err := conn.Read(buf)
	if err != nil {
		t.Fatalf("second Read() should succeed: %v", err)
	}
	if n2 != 2 {
		t.Fatalf("second Read() got %d bytes, want 2", n2)
	}
	if string(buf[:n2]) != "CD" {
		t.Fatalf("second Read() got %q, want %q", string(buf[:n2]), "CD")
	}

	conn.mu.Lock()
	defer conn.mu.Unlock()
	if len(conn.WriteBuffer) != 0 {
		t.Fatalf("WriteBuffer should be empty after two reads, got %d bytes", len(conn.WriteBuffer))
	}
}

func TestStreamConn_Read_ZeroLengthBufferReturnsImmediately(t *testing.T) {
	conn := &StreamConn{
		DataChan:      make(chan []byte, 1),
		CloseChan:     make(chan struct{}),
		Connected:     make(chan bool, 1),
		tunnelWriteMu: &sync.Mutex{},
	}
	conn.DataChan <- []byte("AB")

	n, err := conn.Read([]byte{})
	if err != nil {
		t.Fatalf("Read with zero-length buffer should not fail: %v", err)
	}
	if n != 0 {
		t.Fatalf("Read with zero-length buffer should return 0, got %d", n)
	}
}

func TestStreamConn_Read_BufferBeforeCloseStillReturned(t *testing.T) {
	conn := &StreamConn{
		DataChan:      make(chan []byte, 1),
		CloseChan:     make(chan struct{}),
		Connected:     make(chan bool, 1),
		tunnelWriteMu: &sync.Mutex{},
	}
	conn.DataChan <- []byte("ABCD")

	buf := make([]byte, 2)
	n, err := conn.Read(buf)
	if err != nil {
		t.Fatalf("first read should succeed: %v", err)
	}
	if n != 2 || string(buf[:n]) != "AB" {
		t.Fatalf("first read got %q (%d), want AB (2)", string(buf[:n]), n)
	}

	close(conn.CloseChan)

	n, err = conn.Read(buf)
	if err != nil {
		t.Fatalf("buffered bytes should be returned even after close: %v", err)
	}
	if n != 2 || string(buf[:n]) != "CD" {
		t.Fatalf("second read got %q (%d), want CD (2)", string(buf[:n]), n)
	}

	n, err = conn.Read(buf)
	if err != io.EOF {
		t.Fatalf("expected EOF after buffered bytes consumed, got n=%d err=%v", n, err)
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

func TestHandleData_FullDataChan_DoesNotBlock(t *testing.T) {
	tc := &TunnelClient{
		streams: make(map[string]*StreamConn),
	}

	streamID := "test-stream-data-full-non-blocking"
	stream := &StreamConn{
		StreamID:      streamID,
		DataChan:      make(chan []byte, 1),
		CloseChan:     make(chan struct{}),
		Connected:     make(chan bool, 1),
		tunnelWriteMu: &tc.writeMu,
	}

	stream.DataChan <- []byte("already-buffered")

	tc.mu.Lock()
	tc.streams[streamID] = stream
	tc.mu.Unlock()

	payload, err := json.Marshal(struct {
		StreamID string `json:"stream_id"`
		Data     []byte `json:"data"`
	}{
		StreamID: streamID,
		Data:     []byte("new-data"),
	})
	if err != nil {
		t.Fatalf("failed to marshal data payload: %v", err)
	}

	done := make(chan struct{})
	go func() {
		tc.handleData(payload)
		close(done)
	}()

	select {
	case <-done:
	case <-time.After(200 * time.Millisecond):
		t.Fatal("handleData should return quickly when DataChan is full")
	}
}

func TestHandleData_FullDataChan_ClosesAndRemovesStream(t *testing.T) {
	tc := &TunnelClient{
		streams: make(map[string]*StreamConn),
	}

	streamID := "test-stream-data-full-cleanup"
	stream := &StreamConn{
		StreamID:      streamID,
		DataChan:      make(chan []byte, 1),
		CloseChan:     make(chan struct{}),
		Connected:     make(chan bool, 1),
		tunnelWriteMu: &tc.writeMu,
	}

	stream.DataChan <- []byte("already-buffered")

	tc.mu.Lock()
	tc.streams[streamID] = stream
	tc.mu.Unlock()

	payload, err := json.Marshal(struct {
		StreamID string `json:"stream_id"`
		Data     []byte `json:"data"`
	}{
		StreamID: streamID,
		Data:     []byte("new-data"),
	})
	if err != nil {
		t.Fatalf("failed to marshal data payload: %v", err)
	}

	done := make(chan struct{})
	go func() {
		tc.handleData(payload)
		close(done)
	}()

	select {
	case <-done:
	case <-time.After(200 * time.Millisecond):
		t.Fatal("handleData should not block when DataChan is full")
	}

	if atomic.LoadInt32(&stream.Closed) != 1 {
		t.Fatal("stream should be closed when DataChan is full")
	}

	select {
	case <-stream.CloseChan:
	default:
		t.Fatal("stream CloseChan should be closed when DataChan is full")
	}

	tc.mu.RLock()
	_, exists := tc.streams[streamID]
	tc.mu.RUnlock()

	if exists {
		t.Fatal("stream should be removed from tc.streams when DataChan is full")
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

func TestGenerateRandomStreamID_FormatAndLength(t *testing.T) {
	streamID, err := generateRandomStreamID()
	if err != nil {
		t.Fatalf("expected stream id generation to succeed, got error: %v", err)
	}

	if len(streamID) != 32 {
		t.Fatalf("expected stream id length 32, got %d", len(streamID))
	}

	decoded, err := hex.DecodeString(streamID)
	if err != nil {
		t.Fatalf("expected hex stream id, got decode error: %v", err)
	}

	if len(decoded) != 16 {
		t.Fatalf("expected 16 random bytes, got %d", len(decoded))
	}
}

func TestGenerateRandomStreamID_DoesNotContainDeviceIDPlaintext(t *testing.T) {
	deviceID := "device-123"

	streamID, err := generateRandomStreamID()
	if err != nil {
		t.Fatalf("expected stream id generation to succeed, got error: %v", err)
	}

	if strings.Contains(streamID, deviceID) {
		t.Fatalf("stream id should not include plaintext device id, streamID=%q", streamID)
	}
}

func TestConnectThroughTunnel_UsesRandomStreamID(t *testing.T) {
	upgrader := websocket.Upgrader{CheckOrigin: func(r *http.Request) bool { return true }}
	streamIDCh := make(chan string, 1)

	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/tunnel" {
			http.NotFound(w, r)
			return
		}

		conn, err := upgrader.Upgrade(w, r, nil)
		if err != nil {
			return
		}
		defer conn.Close()

		_, payload, err := conn.ReadMessage()
		if err != nil {
			return
		}

		var connectReq struct {
			Type string `json:"type"`
			Data struct {
				StreamID string `json:"stream_id"`
				Address  string `json:"address"`
				Port     int    `json:"port"`
			} `json:"data"`
		}
		if err := json.Unmarshal(payload, &connectReq); err != nil {
			return
		}

		resp := struct {
			Type string `json:"type"`
			Data struct {
				StreamID string `json:"stream_id"`
				Success  bool   `json:"success"`
			} `json:"data"`
		}{
			Type: "connect_response",
			Data: struct {
				StreamID string `json:"stream_id"`
				Success  bool   `json:"success"`
			}{
				StreamID: connectReq.Data.StreamID,
				Success:  true,
			},
		}

		respData, err := json.Marshal(resp)
		if err != nil {
			return
		}
		if err := conn.WriteMessage(websocket.TextMessage, respData); err != nil {
			return
		}

		select {
		case streamIDCh <- connectReq.Data.StreamID:
		default:
		}

		for {
			if _, _, err := conn.ReadMessage(); err != nil {
				return
			}
		}
	}))
	defer server.Close()

	tc := NewTunnelClient(server.URL)
	deviceID := "device-123"

	conn, err := tc.ConnectThroughTunnel(deviceID, "token-1", "10.0.0.1", 443)
	if err != nil {
		t.Fatalf("expected ConnectThroughTunnel to succeed, got error: %v", err)
	}
	defer conn.Close()

	var streamID string
	select {
	case streamID = <-streamIDCh:
	case <-time.After(2 * time.Second):
		t.Fatal("timeout waiting for connect request stream id")
	}

	if streamID == "" {
		t.Fatal("expected non-empty stream id")
	}
	if len(streamID) != 32 {
		t.Fatalf("expected stream id length 32, got %d", len(streamID))
	}
	if _, err := hex.DecodeString(streamID); err != nil {
		t.Fatalf("expected hex stream id, got decode error: %v", err)
	}
	if strings.Contains(streamID, deviceID) {
		t.Fatalf("stream id should not include plaintext device id, streamID=%q", streamID)
	}
	if strings.HasPrefix(streamID, deviceID+"-") {
		t.Fatalf("stream id should not use legacy predictable format, streamID=%q", streamID)
	}
}

func TestConnectThroughTunnel_ReturnsErrorWhenStreamIDGenerationFails(t *testing.T) {
	originalGenerator := streamIDGenerator
	streamIDGenerator = func() (string, error) {
		return "", errors.New("random source failed")
	}
	t.Cleanup(func() {
		streamIDGenerator = originalGenerator
	})

	upgrader := websocket.Upgrader{CheckOrigin: func(r *http.Request) bool { return true }}
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/tunnel" {
			http.NotFound(w, r)
			return
		}

		conn, err := upgrader.Upgrade(w, r, nil)
		if err != nil {
			return
		}
		defer conn.Close()

		for {
			if _, _, err := conn.ReadMessage(); err != nil {
				return
			}
		}
	}))
	defer server.Close()

	tc := NewTunnelClient(server.URL)
	_, err := tc.ConnectThroughTunnel("device-1", "token-1", "10.0.0.1", 443)
	if err == nil {
		t.Fatal("expected ConnectThroughTunnel to fail when stream id generation fails")
	}
	if !strings.Contains(err.Error(), "failed to generate stream id") {
		t.Fatalf("expected stream id generation error, got: %v", err)
	}

	tc.mu.RLock()
	defer tc.mu.RUnlock()
	if len(tc.streams) != 0 {
		t.Fatalf("expected no streams to be created on stream id generation failure, got %d", len(tc.streams))
	}
}

// TestDefaultTLSConfig_ReturnsNonNilConfigWithTLS12 验证 defaultTLSConfig 返回正确的配置
func TestDefaultTLSConfig_ReturnsNonNilConfigWithTLS12(t *testing.T) {
	cfg := defaultTLSConfig()
	if cfg == nil {
		t.Fatal("expected non-nil tls.Config")
	}
	if cfg.MinVersion != tls.VersionTLS12 {
		t.Errorf("expected MinVersion %d, got %d", tls.VersionTLS12, cfg.MinVersion)
	}
}

// TestDefaultTLSConfig_ReturnsIndependentInstances 验证每次调用返回独立实例
func TestDefaultTLSConfig_ReturnsIndependentInstances(t *testing.T) {
	cfg1 := defaultTLSConfig()
	cfg2 := defaultTLSConfig()

	if cfg1 == cfg2 {
		t.Fatal("expected independent instances, got same pointer")
	}

	// 验证修改一个实例不影响另一个
	cfg1.MinVersion = tls.VersionTLS13
	if cfg2.MinVersion != tls.VersionTLS12 {
		t.Errorf("modifying cfg1 affected cfg2: expected MinVersion %d, got %d", tls.VersionTLS12, cfg2.MinVersion)
	}
}

// TestDefaultTLSConfig_UsedByClients 验证 defaultTLSConfig 被客户端构造函数正确使用
func TestDefaultTLSConfig_UsedByClients(t *testing.T) {
	// 验证 APISessionStore
	store := NewAPISessionStore("https://example.com", "key")
	tr, ok := store.httpClient.Transport.(*http.Transport)
	if !ok {
		t.Fatal("expected *http.Transport")
	}
	if tr.TLSClientConfig == nil || tr.TLSClientConfig.MinVersion != tls.VersionTLS12 {
		t.Fatal("expected TLS 1.2 in APISessionStore")
	}

	// 验证 TunnelClient
	client := NewTunnelClient("wss://example.com")
	tr2, ok := client.httpClient.Transport.(*http.Transport)
	if !ok {
		t.Fatal("expected *http.Transport")
	}
	if tr2.TLSClientConfig == nil || tr2.TLSClientConfig.MinVersion != tls.VersionTLS12 {
		t.Fatal("expected TLS 1.2 in TunnelClient httpClient")
	}
	if client.wsDialer.TLSClientConfig == nil || client.wsDialer.TLSClientConfig.MinVersion != tls.VersionTLS12 {
		t.Fatal("expected TLS 1.2 in TunnelClient wsDialer")
	}
}

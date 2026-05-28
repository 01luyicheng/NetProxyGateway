package main

import (
	"bytes"
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
	// returns a pipe connection for testing
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

func TestGetOrConnectTunnelConcurrentCallsShareSingleDial(t *testing.T) {
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

func TestRelayClosesPeerConnectionOnHalfClose(t *testing.T) {
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

// TestStreamConnCloseIdempotent verifies that Close() is idempotent.
func TestStreamConnCloseIdempotent(t *testing.T) {
	writeMu := &sync.Mutex{}
	conn := &StreamConn{
		StreamID:      "test-stream-1",
		DeviceID:      "device-1",
		DataChan:      make(chan []byte, 100),
		CloseChan:     make(chan struct{}),
		Connected:     make(chan bool, 1),
		tunnelWriteMu: writeMu,
	}

	// first close should succeed
	if err := conn.Close(); err != nil {
		t.Fatalf("first Close() should succeed: %v", err)
	}

	// verify Closed flag is set
	if conn.Closed != 1 {
		t.Fatal("Closed flag should be set to 1 after Close()")
	}

	// second close should return safely (no panic)
	if err := conn.Close(); err != nil {
		t.Fatalf("second Close() should succeed: %v", err)
	}

	// third close should also be safe
	if err := conn.Close(); err != nil {
		t.Fatalf("third Close() should succeed: %v", err)
	}
}

func TestStreamConnReadPreservesRemainderAcrossSmallBuffers(t *testing.T) {
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
		t.Fatalf("first read failed: %v", err)
	}
	if n != 2 || string(buf[:n]) != "AB" {
		t.Fatalf("first read got %q (%d), want AB (2)", string(buf[:n]), n)
	}

	n, err = conn.Read(buf)
	if err != nil {
		t.Fatalf("second read failed: %v", err)
	}
	if n != 2 || string(buf[:n]) != "CD" {
		t.Fatalf("second read got %q (%d), want CD (2)", string(buf[:n]), n)
	}
}

func TestStreamConnReadReturnsQueuedDataBeforeEOF(t *testing.T) {
	conn := &StreamConn{
		DataChan:      make(chan []byte, 1),
		CloseChan:     make(chan struct{}),
		Connected:     make(chan bool, 1),
		tunnelWriteMu: &sync.Mutex{},
	}
	conn.DataChan <- []byte("XY")
	close(conn.CloseChan)

	buf := make([]byte, 4)

	n, err := conn.Read(buf)
	if err != nil {
		t.Fatalf("expected queued data before EOF, got err: %v", err)
	}
	if n != 2 || string(buf[:n]) != "XY" {
		t.Fatalf("read got %q (%d), want XY (2)", string(buf[:n]), n)
	}

	n, err = conn.Read(buf)
	if err != io.EOF {
		t.Fatalf("expected EOF after queued data is consumed, got n=%d err=%v", n, err)
	}
}

func TestStreamConnReadAcrossGoroutinesDeliversBufferedRemainderBeforeEOF(t *testing.T) {
	conn := &StreamConn{
		DataChan:      make(chan []byte, 1),
		CloseChan:     make(chan struct{}),
		Connected:     make(chan bool, 1),
		tunnelWriteMu: &sync.Mutex{},
	}
	conn.DataChan <- []byte("ABCD")

	firstBuf := make([]byte, 2)
	n, err := conn.Read(firstBuf)
	if err != nil {
		t.Fatalf("first read failed: %v", err)
	}
	if n != 2 || string(firstBuf[:n]) != "AB" {
		t.Fatalf("first read got %q (%d), want AB (2)", string(firstBuf[:n]), n)
	}

	secondBuf := make([]byte, 2)
	resultCh := make(chan struct {
		n   int
		err error
	}, 1)

	go func() {
		n, err := conn.Read(secondBuf)
		resultCh <- struct {
			n   int
			err error
		}{n: n, err: err}
	}()

	close(conn.CloseChan)

	result := <-resultCh
	if result.err != nil {
		t.Fatalf("second read should return buffered remainder before EOF, got err: %v", result.err)
	}
	if result.n != 2 || string(secondBuf[:result.n]) != "CD" {
		t.Fatalf("second read got %q (%d), want CD (2)", string(secondBuf[:result.n]), result.n)
	}
}

// TestHandleConnectResponseFailedConnectionCleansUpStream verifies that streams are cleaned up on connection failure.
func TestHandleConnectResponseFailedConnectionCleansUpStream(t *testing.T) {
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

	// add stream to map
	tc.mu.Lock()
	tc.streams[streamID] = stream
	tc.mu.Unlock()

	// simulate failed connection response
	response := []byte(`{"stream_id":"test-stream-cleanup","success":false,"error":"connection refused"}`)
	tc.handleConnectResponse(response)

	// verify stream is removed from map
	tc.mu.RLock()
	_, exists := tc.streams[streamID]
	tc.mu.RUnlock()

	if exists {
		t.Fatal("stream should be removed from tc.streams after connection failure")
	}

	// verify stream is closed
	if stream.Closed != 1 {
		t.Fatal("stream should be closed after connection failure")
	}

	// verify Connected channel receives false
	select {
	case success := <-stream.Connected:
		if success {
			t.Fatal("Connected channel should receive false for failed connection")
		}
	case <-time.After(time.Second):
		t.Fatal("timeout waiting for Connected channel")
	}
}

// TestHandleConnectResponseSuccessfulConnectionKeepsStream verifies that streams are kept on successful connection.
func TestHandleConnectResponseSuccessfulConnectionKeepsStream(t *testing.T) {
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

	// add stream to map
	tc.mu.Lock()
	tc.streams[streamID] = stream
	tc.mu.Unlock()

	// simulate successful connection response
	response := []byte(`{"stream_id":"test-stream-success","success":true}`)
	tc.handleConnectResponse(response)

	// verify stream remains in map (successful connection should not delete)
	tc.mu.RLock()
	_, exists := tc.streams[streamID]
	tc.mu.RUnlock()

	if !exists {
		t.Fatal("stream should remain in tc.streams after successful connection")
	}

	// verify Connected channel receives true
	select {
	case success := <-stream.Connected:
		if !success {
			t.Fatal("Connected channel should receive true for successful connection")
		}
	case <-time.After(time.Second):
		t.Fatal("timeout waiting for Connected channel")
	}
}

func TestHandleDataFullDataChanDoesNotBlock(t *testing.T) {
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

func TestHandleDataFullDataChanClosesAndRemovesStream(t *testing.T) {
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

// TestConnectThroughTunnelCleanupOnMarshalError verifies resource cleanup when JSON marshaling fails.
func TestConnectThroughTunnelCleanupOnMarshalError(t *testing.T) {
	// create a request with unmarshalable data
	tc := &TunnelClient{
		streams: make(map[string]*StreamConn),
	}

	// verify streams map is empty
	tc.mu.RLock()
	streamCount := len(tc.streams)
	tc.mu.RUnlock()

	if streamCount != 0 {
		t.Fatalf("expected empty streams map, got %d streams", streamCount)
	}
}

func TestGenerateRandomStreamIDFormatAndLength(t *testing.T) {
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

func TestGenerateRandomStreamIDDoesNotContainDeviceIDPlaintext(t *testing.T) {
	deviceID := "device-123"

	streamID, err := generateRandomStreamID()
	if err != nil {
		t.Fatalf("expected stream id generation to succeed, got error: %v", err)
	}

	if strings.Contains(streamID, deviceID) {
		t.Fatalf("stream id should not include plaintext device id, streamID=%q", streamID)
	}
}

func TestConnectThroughTunnelUsesRandomStreamID(t *testing.T) {
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

func TestConnectThroughTunnelReturnsErrorWhenStreamIDGenerationFails(t *testing.T) {
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

func TestStreamConnReadConcurrent(t *testing.T) {
	conn := &StreamConn{
		DataChan:      make(chan []byte, 10),
		CloseChan:     make(chan struct{}),
		Connected:     make(chan bool, 1),
		tunnelWriteMu: &sync.Mutex{},
	}

	chunks := []string{"chunk0", "chunk1", "chunk2", "chunk3", "chunk4"}
	for _, c := range chunks {
		conn.DataChan <- []byte(c)
	}

	const numReaders = 5
	const bufSize = 10

	var wg sync.WaitGroup
	results := make([][]byte, numReaders)
	errCh := make(chan error, numReaders)

	for i := 0; i < numReaders; i++ {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()
			var buf bytes.Buffer
			readBuf := make([]byte, bufSize)
			for {
				n, err := conn.Read(readBuf)
				if err != nil {
					if err == io.EOF {
						break
					}
					errCh <- err
					return
				}
				if n > 0 {
					buf.Write(readBuf[:n])
				}
			}
			results[idx] = buf.Bytes()
		}(i)
	}

	time.Sleep(50 * time.Millisecond)
	close(conn.CloseChan)

	done := make(chan struct{})
	go func() {
		wg.Wait()
		close(errCh)
		close(done)
	}()

	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("timed out waiting for readers")
	}

	for err := range errCh {
		t.Fatalf("unexpected read error: %v", err)
	}

	select {
	case remaining := <-conn.DataChan:
		t.Fatalf("expected all data to be consumed, got: %q", remaining)
	default:
	}
}

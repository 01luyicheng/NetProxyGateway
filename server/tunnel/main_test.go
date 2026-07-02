package main

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/gorilla/websocket"
)

type roundTripFunc func(*http.Request) (*http.Response, error)

func (f roundTripFunc) RoundTrip(req *http.Request) (*http.Response, error) {
	return f(req)
}

func TestNotifyDeviceStatusAddsInternalAPIKeyHeader(t *testing.T) {
	var (
		headerValue string
		wg          sync.WaitGroup
	)
	wg.Add(1)

	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		headerValue = r.Header.Get("X-Internal-API-Key")
		w.WriteHeader(http.StatusOK)
		wg.Done()
	}))
	defer server.Close()

	manager := NewTunnelManager(&Config{
		APIEndpoint:    server.URL,
		InternalAPIKey: "internal-secret",
	})

	manager.wg.Add(1)
	manager.notifyDeviceStatus("device-123", "online", "")

	waitDone := make(chan struct{})
	go func() {
		wg.Wait()
		close(waitDone)
	}()

	select {
	case <-waitDone:
	case <-time.After(2 * time.Second):
		t.Fatal("timed out waiting for notifyDeviceStatus")
	}

	if headerValue != "internal-secret" {
		t.Fatalf("expected internal api key header to be forwarded, got %q", headerValue)
	}
}

func TestNotifyDeviceStatusIncludesLastSeenTimestamp(t *testing.T) {
	var (
		body map[string]interface{}
		wg   sync.WaitGroup
	)
	wg.Add(1)

	before := time.Now().UnixMilli()
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			t.Errorf("failed to decode payload: %v", err)
		}
		_ = r.Body.Close()
		wg.Done()
		w.WriteHeader(http.StatusOK)
	}))
	defer server.Close()

	manager := NewTunnelManager(&Config{APIEndpoint: server.URL})

	manager.wg.Add(1)
	manager.notifyDeviceStatus("device-123", "online", "")

	waitDone := make(chan struct{})
	go func() {
		wg.Wait()
		close(waitDone)
	}()

	select {
	case <-waitDone:
	case <-time.After(2 * time.Second):
		t.Fatal("timed out waiting for notifyDeviceStatus")
	}

	after := time.Now().UnixMilli()

	raw, ok := body["last_seen"]
	if !ok {
		t.Fatalf("expected payload to contain last_seen, got %v", body)
	}
	lastSeen, ok := raw.(float64)
	if !ok {
		t.Fatalf("expected last_seen to be a JSON number, got %T", raw)
	}
	if int64(lastSeen) < before || int64(lastSeen) > after {
		t.Fatalf("last_seen = %d, want between %d and %d", int64(lastSeen), before, after)
	}
}

func TestNotifyDeviceStatusRetriesAndEventuallySucceeds(t *testing.T) {
	var (
		mu       sync.Mutex
		requests int
	)
	requestSignal := make(chan struct{}, 4)

	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		requests++
		attempt := requests
		mu.Unlock()

		requestSignal <- struct{}{}

		if attempt == 1 {
			w.WriteHeader(http.StatusInternalServerError)
			return
		}

		w.WriteHeader(http.StatusOK)
	}))
	defer server.Close()

	manager := NewTunnelManager(&Config{APIEndpoint: server.URL})
	manager.wg.Add(1)
	manager.notifyDeviceStatus("device-123", "online", "")

	for i := 0; i < 2; i++ {
		select {
		case <-requestSignal:
		case <-time.After(2 * time.Second):
			t.Fatalf("timed out waiting for notifyDeviceStatus attempt %d", i+1)
		}
	}

	select {
	case <-requestSignal:
		t.Fatal("expected no extra request after success")
	case <-time.After(300 * time.Millisecond):
	}

	mu.Lock()
	got := requests
	mu.Unlock()

	if got != 2 {
		t.Fatalf("expected 2 notify attempts (retry then success), got %d", got)
	}
}

func TestNotifyDeviceStatusDoesNotRetryOnBadRequest(t *testing.T) {
	var (
		mu       sync.Mutex
		requests int
	)
	requestSignal := make(chan struct{}, 2)

	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		requests++
		mu.Unlock()

		requestSignal <- struct{}{}
		w.WriteHeader(http.StatusBadRequest)
	}))
	defer server.Close()

	manager := NewTunnelManager(&Config{APIEndpoint: server.URL})
	manager.wg.Add(1)
	manager.notifyDeviceStatus("device-123", "online", "")

	select {
	case <-requestSignal:
	case <-time.After(2 * time.Second):
		t.Fatal("timed out waiting for notifyDeviceStatus first attempt")
	}

	select {
	case <-requestSignal:
		t.Fatal("expected no retry for 4xx response")
	case <-time.After(300 * time.Millisecond):
	}

	mu.Lock()
	got := requests
	mu.Unlock()

	if got != 1 {
		t.Fatalf("expected no retry for 4xx response, got %d attempts", got)
	}
}

func TestUnregisterSkipsStaleTunnelInstance(t *testing.T) {
	manager := NewTunnelManager(&Config{})
	defer manager.Stop()

	first := manager.Register("device-123", nil)
	second := manager.Register("device-123", nil)

	manager.Unregister("device-123", first)

	current, ok := manager.Get("device-123")
	if !ok {
		t.Fatal("expected current tunnel to remain registered")
	}
	if current != second {
		t.Fatal("expected stale unregister to keep the newest tunnel instance")
	}
}

func TestUnregisterRemovesMatchingInstance(t *testing.T) {
	manager := NewTunnelManager(&Config{})
	defer manager.Stop()

	tunnel := manager.Register("device-456", nil)
	manager.Unregister("device-456", tunnel)

	_, ok := manager.Get("device-456")
	if ok {
		t.Fatal("expected tunnel to be unregistered when instance matches")
	}
}

func TestUnregisterNilTunnelRemovesCurrent(t *testing.T) {
	manager := NewTunnelManager(&Config{})
	defer manager.Stop()

	manager.Register("device-789", nil)
	manager.Unregister("device-789", nil)

	_, ok := manager.Get("device-789")
	if ok {
		t.Fatal("expected nil tunnel unregister to remove current mapping")
	}
}

func TestUnregisterNonExistentDevice(t *testing.T) {
	manager := NewTunnelManager(&Config{})
	defer manager.Stop()

	manager.Unregister("device-not-exist", nil)

	_, ok := manager.Get("device-not-exist")
	if ok {
		t.Fatal("expected unregister of non-existent device to be no-op")
	}
}

func TestCleanupDeadTunnelsSkipsReplacedInstance(t *testing.T) {
	config := &Config{HeartbeatTimeout: 50 * time.Millisecond}
	manager := NewTunnelManager(config)
	defer manager.Stop()

	first := manager.Register("device-cleanup", nil)
	second := manager.Register("device-cleanup", nil)

	// wait for old connection to timeout while keeping new connection alive
	time.Sleep(100 * time.Millisecond)
	second.UpdatePing()

	manager.cleanupDeadTunnelsOnce()

	current, ok := manager.Get("device-cleanup")
	if !ok {
		t.Fatal("expected current tunnel to remain after cleanup of stale instance")
	}
	if current != second {
		t.Fatal("expected cleanup to keep the current active tunnel")
	}
	if current == first {
		t.Fatal("expected cleanup to skip stale instance")
	}
}

func TestValidateDeviceTokenAddsInternalAPIKeyHeader(t *testing.T) {
	var headerValue string

	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		headerValue = r.Header.Get("X-Internal-API-Key")
		_, _ = io.WriteString(w, `{"valid":true}`)
	}))
	defer server.Close()

	tunnelServer := NewServer(&Config{
		APIEndpoint:       server.URL,
		InternalAPIKey:    "internal-secret",
		HeartbeatInterval: time.Second,
		HeartbeatTimeout:  2 * time.Second,
	})

	valid, err := tunnelServer.validateDeviceToken("device-123", "token-abc")
	if err != nil {
		t.Fatalf("unexpected token validation error: %v", err)
	}
	if !valid {
		t.Fatal("expected token validation to succeed")
	}

	if headerValue != "internal-secret" {
		t.Fatalf("expected internal api key header to be forwarded, got %q", headerValue)
	}
}

func TestNewServerInitializesReusableHTTPClient(t *testing.T) {
	tunnelServer := NewServer(&Config{})

	if tunnelServer.httpClient == nil {
		t.Fatal("expected NewServer to initialize reusable httpClient")
	}

	if tunnelServer.httpClient.Timeout != 10*time.Second {
		t.Fatalf("expected reusable httpClient timeout to be 10s, got %v", tunnelServer.httpClient.Timeout)
	}
}

func TestValidateDeviceTokenUsesServerHTTPClient(t *testing.T) {
	tunnelServer := NewServer(&Config{
		APIEndpoint:       "http://token-validate.test",
		HeartbeatInterval: time.Second,
		HeartbeatTimeout:  2 * time.Second,
	})

	requests := 0
	tunnelServer.httpClient = &http.Client{
		Transport: roundTripFunc(func(r *http.Request) (*http.Response, error) {
			requests++
			return &http.Response{
				StatusCode: http.StatusOK,
				Body:       io.NopCloser(strings.NewReader(`{"valid":true}`)),
				Header:     make(http.Header),
			}, nil
		}),
	}

	valid, err := tunnelServer.validateDeviceToken("device-123", "token-abc")
	if err != nil {
		t.Fatalf("unexpected token validation error: %v", err)
	}
	if !valid {
		t.Fatal("expected token validation to succeed with injected server httpClient")
	}

	if requests != 1 {
		t.Fatalf("expected injected server httpClient to handle exactly one request, got %d", requests)
	}
}

func TestCheckOriginRejectsUntrustedOriginByDefault(t *testing.T) {
	tunnelServer := NewServer(&Config{})
	req := httptest.NewRequest(http.MethodGet, "/tunnel", nil)
	req.Header.Set("Origin", "https://evil.example.com")

	if tunnelServer.upgrader.CheckOrigin(req) {
		t.Fatal("expected untrusted origin to be rejected when whitelist is not configured")
	}
}

func TestCheckOriginAllowsEmptyOrigin(t *testing.T) {
	tunnelServer := NewServer(&Config{})
	req := httptest.NewRequest(http.MethodGet, "/tunnel", nil)

	if !tunnelServer.upgrader.CheckOrigin(req) {
		t.Fatal("expected empty origin to be allowed")
	}
}

func TestCheckOriginRejectsEmptyOriginWhenWhitelistConfigured(t *testing.T) {
	tunnelServer := NewServer(&Config{AllowedOrigins: parseAllowedOrigins("https://app.example.com")})
	req := httptest.NewRequest(http.MethodGet, "/tunnel", nil)

	if tunnelServer.upgrader.CheckOrigin(req) {
		t.Fatal("expected empty origin to be rejected when whitelist is configured")
	}
}

func TestCheckOriginAllowsLocalhostOrigin(t *testing.T) {
	tunnelServer := NewServer(&Config{})
	req := httptest.NewRequest(http.MethodGet, "/tunnel", nil)
	req.Header.Set("Origin", "http://localhost:3000")

	if !tunnelServer.upgrader.CheckOrigin(req) {
		t.Fatal("expected localhost origin to be allowed")
	}
}

func TestCheckOriginAllowsConfiguredWhitelistOrigin(t *testing.T) {
	tunnelServer := NewServer(&Config{AllowedOrigins: parseAllowedOrigins("https://app.example.com")})
	req := httptest.NewRequest(http.MethodGet, "/tunnel", nil)
	req.Header.Set("Origin", "https://app.example.com")

	if !tunnelServer.upgrader.CheckOrigin(req) {
		t.Fatal("expected whitelisted origin to be allowed")
	}
}

func TestCheckOriginAllowsConfiguredWhitelistOriginWithDefaultHTTPSPort(t *testing.T) {
	tunnelServer := NewServer(&Config{AllowedOrigins: parseAllowedOrigins("https://app.example.com:443")})
	req := httptest.NewRequest(http.MethodGet, "/tunnel", nil)
	req.Header.Set("Origin", "https://app.example.com")

	if !tunnelServer.upgrader.CheckOrigin(req) {
		t.Fatal("expected https origin to match whitelist with default :443 port")
	}
}

func TestCheckOriginAllowsConfiguredWhitelistOriginWithDefaultHTTPPort(t *testing.T) {
	tunnelServer := NewServer(&Config{AllowedOrigins: parseAllowedOrigins("http://app.example.com:80")})
	req := httptest.NewRequest(http.MethodGet, "/tunnel", nil)
	req.Header.Set("Origin", "http://app.example.com")

	if !tunnelServer.upgrader.CheckOrigin(req) {
		t.Fatal("expected http origin to match whitelist with default :80 port")
	}
}

func TestCheckOriginRejectsNonHTTPScheme(t *testing.T) {
	tunnelServer := NewServer(&Config{AllowedOrigins: parseAllowedOrigins("https://app.example.com")})
	req := httptest.NewRequest(http.MethodGet, "/tunnel", nil)
	req.Header.Set("Origin", "ws://app.example.com")

	if tunnelServer.upgrader.CheckOrigin(req) {
		t.Fatal("expected non-http(s) origin to be rejected")
	}
}

func TestAuthorizeStats(t *testing.T) {
	tests := []struct {
		name         string
		addr         string
		statsToken   string
		host         string
		remoteAddr   string
		forwardedFor string
		headerToken  string
		wantAllowed  bool
	}{
		{
			name:        "non-loopback without configured token is denied",
			addr:        "0.0.0.0:8443",
			host:        "gateway.example.com",
			remoteAddr:  "203.0.113.10:12000",
			wantAllowed: false,
		},
		{
			name:        "non-loopback with wrong token is denied",
			addr:        "0.0.0.0:8443",
			statsToken:  "expected-token",
			host:        "gateway.example.com",
			remoteAddr:  "203.0.113.10:12000",
			headerToken: "wrong-token",
			wantAllowed: false,
		},
		{
			name:        "non-loopback with correct token is allowed",
			addr:        "0.0.0.0:8443",
			statsToken:  "expected-token",
			host:        "gateway.example.com",
			remoteAddr:  "203.0.113.10:12000",
			headerToken: "expected-token",
			wantAllowed: true,
		},
		{
			name:        "loopback without token is denied",
			addr:        "127.0.0.1:8443",
			host:        "127.0.0.1:8443",
			remoteAddr:  "127.0.0.1:12000",
			wantAllowed: false,
		},
		{
			name:        "loopback without token remains denied with localhost host",
			addr:        "127.0.0.1:8443",
			host:        "localhost:8443",
			remoteAddr:  "127.0.0.1:12000",
			wantAllowed: false,
		},
		{
			name:         "loopback without token is denied when forwarded headers are present",
			addr:         "127.0.0.1:8443",
			host:         "127.0.0.1:8443",
			remoteAddr:   "127.0.0.1:12000",
			forwardedFor: "203.0.113.20",
			wantAllowed:  false,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			tunnelServer := NewServer(&Config{
				Addr:       tt.addr,
				StatsToken: tt.statsToken,
			})
			req := httptest.NewRequest(http.MethodGet, "/stats", nil)
			req.RemoteAddr = tt.remoteAddr
			if tt.host != "" {
				req.Host = tt.host
			}
			if tt.forwardedFor != "" {
				req.Header.Set("X-Forwarded-For", tt.forwardedFor)
			}
			if tt.headerToken != "" {
				req.Header.Set("X-Stats-Token", tt.headerToken)
			}

			allowed := tunnelServer.authorizeStats(req)
			if allowed != tt.wantAllowed {
				t.Fatalf("authorizeStats() = %v, want %v", allowed, tt.wantAllowed)
			}
		})
	}
}

func TestHandleStats(t *testing.T) {
	tests := []struct {
		name         string
		addr         string
		statsToken   string
		host         string
		remoteAddr   string
		forwardedFor string
		headerToken  string
		wantStatus   int
	}{
		{
			name:       "non-loopback without configured token returns forbidden",
			addr:       "0.0.0.0:8443",
			host:       "gateway.example.com",
			remoteAddr: "203.0.113.10:12000",
			wantStatus: http.StatusForbidden,
		},
		{
			name:        "non-loopback with wrong token returns forbidden",
			addr:        "0.0.0.0:8443",
			statsToken:  "expected-token",
			host:        "gateway.example.com",
			remoteAddr:  "203.0.113.10:12000",
			headerToken: "wrong-token",
			wantStatus:  http.StatusForbidden,
		},
		{
			name:        "non-loopback with correct token returns ok",
			addr:        "0.0.0.0:8443",
			statsToken:  "expected-token",
			host:        "gateway.example.com",
			remoteAddr:  "203.0.113.10:12000",
			headerToken: "expected-token",
			wantStatus:  http.StatusOK,
		},
		{
			name:       "loopback without token returns forbidden",
			addr:       "127.0.0.1:8443",
			host:       "127.0.0.1:8443",
			remoteAddr: "127.0.0.1:12000",
			wantStatus: http.StatusForbidden,
		},
		{
			name:       "loopback without token remains forbidden with localhost host",
			addr:       "127.0.0.1:8443",
			host:       "localhost:8443",
			remoteAddr: "127.0.0.1:12000",
			wantStatus: http.StatusForbidden,
		},
		{
			name:         "loopback without token returns forbidden when forwarded headers are present",
			addr:         "127.0.0.1:8443",
			host:         "127.0.0.1:8443",
			remoteAddr:   "127.0.0.1:12000",
			forwardedFor: "203.0.113.20",
			wantStatus:   http.StatusForbidden,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			tunnelServer := NewServer(&Config{
				Addr:       tt.addr,
				StatsToken: tt.statsToken,
			})
			req := httptest.NewRequest(http.MethodGet, "/stats", nil)
			req.RemoteAddr = tt.remoteAddr
			if tt.host != "" {
				req.Host = tt.host
			}
			if tt.forwardedFor != "" {
				req.Header.Set("X-Forwarded-For", tt.forwardedFor)
			}
			if tt.headerToken != "" {
				req.Header.Set("X-Stats-Token", tt.headerToken)
			}

			rr := httptest.NewRecorder()
			tunnelServer.handleStats(rr, req)

			if rr.Code != tt.wantStatus {
				t.Fatalf("handleStats() status = %d, want %d", rr.Code, tt.wantStatus)
			}
		})
	}
}

func TestNotifyStatusBackoff(t *testing.T) {
	tests := []struct {
		attempt  int
		expected time.Duration
	}{
		{attempt: 0, expected: 100 * time.Millisecond},
		{attempt: 1, expected: 100 * time.Millisecond},
		{attempt: 2, expected: 200 * time.Millisecond},
		{attempt: 3, expected: 400 * time.Millisecond},
		{attempt: defaultNotifyStatusMaxBackoffShift, expected: defaultNotifyStatusBaseBackoff * time.Duration(int64(1)<<(defaultNotifyStatusMaxBackoffShift-1))},
		{attempt: defaultNotifyStatusMaxBackoffShift + 1, expected: defaultNotifyStatusBaseBackoff * time.Duration(int64(1)<<(defaultNotifyStatusMaxBackoffShift-1))},
	}

	for _, tt := range tests {
		got := notifyStatusBackoff(tt.attempt)
		if got != tt.expected {
			t.Fatalf("notifyStatusBackoff(%d) = %v, want %v", tt.attempt, got, tt.expected)
		}
	}
}

func TestNotifyDeviceStatusExhaustsRetries(t *testing.T) {
	var (
		mu       sync.Mutex
		requests int
	)
	requestSignal := make(chan struct{}, 4)

	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		requests++
		mu.Unlock()

		requestSignal <- struct{}{}
		w.WriteHeader(http.StatusInternalServerError)
	}))
	defer server.Close()

	manager := NewTunnelManager(&Config{APIEndpoint: server.URL})
	manager.wg.Add(1)
	manager.notifyDeviceStatus("device-123", "online", "")

	for i := 0; i < 3; i++ {
		select {
		case <-requestSignal:
		case <-time.After(2 * time.Second):
			t.Fatalf("timed out waiting for notifyDeviceStatus attempt %d", i+1)
		}
	}

	select {
	case <-requestSignal:
		t.Fatal("expected no extra request after retries exhausted")
	case <-time.After(300 * time.Millisecond):
	}

	mu.Lock()
	got := requests
	mu.Unlock()

	if got != 3 {
		t.Fatalf("expected 3 notify attempts, got %d", got)
	}
}

func TestNotifyDeviceStatusRetriesOnTooManyRequests(t *testing.T) {
	var (
		mu       sync.Mutex
		requests int
	)
	requestSignal := make(chan struct{}, 4)

	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		requests++
		attempt := requests
		mu.Unlock()

		requestSignal <- struct{}{}

		if attempt == 1 {
			w.WriteHeader(http.StatusTooManyRequests)
			return
		}

		w.WriteHeader(http.StatusOK)
	}))
	defer server.Close()

	manager := NewTunnelManager(&Config{APIEndpoint: server.URL})
	manager.wg.Add(1)
	manager.notifyDeviceStatus("device-123", "online", "")

	for i := 0; i < 2; i++ {
		select {
		case <-requestSignal:
		case <-time.After(2 * time.Second):
			t.Fatalf("timed out waiting for notifyDeviceStatus attempt %d", i+1)
		}
	}

	select {
	case <-requestSignal:
		t.Fatal("expected no extra request after success")
	case <-time.After(300 * time.Millisecond):
	}

	mu.Lock()
	got := requests
	mu.Unlock()

	if got != 2 {
		t.Fatalf("expected 2 notify attempts (retry then success), got %d", got)
	}
}

func TestTokenBucketLimiterRejectsBurstOverLimit(t *testing.T) {
	now := time.Unix(1700000000, 0)
	limiter := newTokenBucketLimiterWithClock(1, 2, func() time.Time {
		return now
	})

	if !limiter.Allow() {
		t.Fatal("expected first message to pass")
	}

	if !limiter.Allow() {
		t.Fatal("expected second message to pass within burst")
	}

	if limiter.Allow() {
		t.Fatal("expected burst over limit to be rejected")
	}
}

func TestTokenBucketLimiterAllowsAgainAfterRefill(t *testing.T) {
	now := time.Unix(1700000000, 0)
	limiter := newTokenBucketLimiterWithClock(2, 2, func() time.Time {
		return now
	})

	if !limiter.Allow() {
		t.Fatal("expected first message to pass")
	}

	if !limiter.Allow() {
		t.Fatal("expected second message to pass within burst")
	}

	if limiter.Allow() {
		t.Fatal("expected immediate third message to be rejected")
	}

	now = now.Add(600 * time.Millisecond)

	if !limiter.Allow() {
		t.Fatal("expected message to pass after token refill")
	}
}

func TestHeartbeatDoesNotPingAfterTunnelClosed(t *testing.T) {
	tunnelServer := NewServer(&Config{
		HeartbeatInterval: 10 * time.Millisecond,
		HeartbeatTimeout:  time.Second,
	})
	tunnel := NewTunnelConn("device-closed", nil)
	tunnel.Close()

	done := make(chan struct{})
	go func() {
		tunnelServer.heartbeat(tunnel, make(chan struct{}))
		close(done)
	}()

	select {
	case <-done:
	case <-time.After(200 * time.Millisecond):
		t.Fatal("expected heartbeat to stop quickly for closed tunnel")
	}
}

func TestHeartbeatStopsAfterRuntimeClose(t *testing.T) {
	upgrader := websocket.Upgrader{}
	wsServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		conn, err := upgrader.Upgrade(w, r, nil)
		if err != nil {
			t.Errorf("failed to upgrade websocket: %v", err)
			return
		}
		defer conn.Close()

		for {
			if _, _, err := conn.ReadMessage(); err != nil {
				return
			}
		}
	}))
	defer wsServer.Close()

	wsURL := "ws" + strings.TrimPrefix(wsServer.URL, "http")
	clientConn, _, err := websocket.DefaultDialer.Dial(wsURL, nil)
	if err != nil {
		t.Fatalf("failed to dial websocket server: %v", err)
	}
	defer clientConn.Close()

	tunnelServer := NewServer(&Config{
		HeartbeatInterval: 10 * time.Millisecond,
		HeartbeatTimeout:  time.Second,
	})
	tunnel := NewTunnelConn("device-runtime-close", clientConn)

	done := make(chan struct{})
	go func() {
		tunnelServer.heartbeat(tunnel, make(chan struct{}))
		close(done)
	}()

	time.Sleep(30 * time.Millisecond)
	tunnel.Close()

	select {
	case <-done:
	case <-time.After(200 * time.Millisecond):
		t.Fatal("expected heartbeat to stop quickly after runtime close")
	}
}

func TestNewHTTPServerSetsMaxHeaderBytes(t *testing.T) {
	server := newHTTPServer("0.0.0.0:8443", http.NewServeMux())

	if server.MaxHeaderBytes != maxHTTPHeaderBytes {
		t.Fatalf("expected MaxHeaderBytes=%d, got %d", maxHTTPHeaderBytes, server.MaxHeaderBytes)
	}
}

func TestNotifyDeviceStatusNoRaceWithStop(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		time.Sleep(100 * time.Millisecond)
		w.WriteHeader(http.StatusOK)
	}))
	defer server.Close()

	manager := NewTunnelManager(&Config{APIEndpoint: server.URL})

	// Use a channel to ensure goroutine has started before calling Stop.
	started := make(chan struct{})
	go func() {
		manager.wg.Add(1)
		close(started)
		manager.notifyDeviceStatus("device-123", "online", "")
	}()

	<-started

	done := make(chan struct{})
	go func() {
		manager.Stop()
		close(done)
	}()

	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("Stop() should not block indefinitely")
	}
}

func TestStopIsIdempotent(t *testing.T) {
	manager := NewTunnelManager(&Config{})

	manager.Stop()

	done := make(chan struct{})
	go func() {
		manager.Stop()
		close(done)
	}()

	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("second Stop() call should return immediately")
	}
}

func TestNotifyDeviceStatusAfterStopIsIgnored(t *testing.T) {
	var requests int
	var mu sync.Mutex

	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		requests++
		mu.Unlock()
		w.WriteHeader(http.StatusOK)
	}))
	defer server.Close()

	manager := NewTunnelManager(&Config{APIEndpoint: server.URL})
	manager.Stop()

	manager.wg.Add(1)
	manager.notifyDeviceStatus("device-123", "online", "")

	time.Sleep(200 * time.Millisecond)

	mu.Lock()
	got := requests
	mu.Unlock()

	if got != 0 {
		t.Fatalf("expected no requests after Stop(), got %d", got)
	}
}

// TestSendLoopNoPanicOnConcurrentClose verifies that sendLoop does not panic
// when Close() is called concurrently. This is a regression test for the
// sendLoop WriteMessage race with Close(). Close() acquires connMu and calls
// t.Conn.Close() (closing the underlying connection); it does NOT set t.Conn
// to nil. sendLoop now also acquires connMu before WriteMessage, so the race
// is eliminated. The test floods sendChan to maximise the chance that sendLoop
// is inside WriteMessage when Close() is called.
func TestSendLoopNoPanicOnConcurrentClose(t *testing.T) {
	upgrader := websocket.Upgrader{}
	wsServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
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
	defer wsServer.Close()

	wsURL := "ws" + strings.TrimPrefix(wsServer.URL, "http")
	clientConn, _, err := websocket.DefaultDialer.Dial(wsURL, nil)
	if err != nil {
		t.Fatalf("failed to dial websocket server: %v", err)
	}
	defer func() {
		if clientConn != nil {
			clientConn.Close()
		}
	}()

	tunnelServer := NewServer(&Config{
		HeartbeatInterval: time.Second,
		HeartbeatTimeout:  2 * time.Second,
	})
	tunnel := NewTunnelConn("device-sendloop-race", clientConn)

	// Start sendLoop in background
	sendLoopDone := make(chan struct{})
	go func() {
		tunnelServer.sendLoop(tunnel)
		close(sendLoopDone)
	}()

	// Flood sendChan so sendLoop is likely inside WriteMessage when Close()
	// is called. Using a WaitGroup ensures all sends complete before Close().
	var floodWg sync.WaitGroup
	var sendErrCount atomic.Int32
	floodWg.Add(1)
	go func() {
		defer floodWg.Done()
		for i := 0; i < 100; i++ {
			if err := tunnel.Send([]byte("test-message")); err != nil {
				sendErrCount.Add(1)
			}
		}
	}()
	floodWg.Wait()
	if sendErrCount.Load() > 0 {
		t.Fatalf("tunnel.Send failed %d time(s)", sendErrCount.Load())
	}

	// Wrap Close() in a goroutine with a timeout to fail fast on deadlock regressions.
	closeDone := make(chan struct{})
	go func() {
		tunnel.Close()
		clientConn = nil
		close(closeDone)
	}()
	select {
	case <-closeDone:
		// Success: Close() completed
	case <-time.After(5 * time.Second):
		t.Fatal("tunnel.Close() deadlocked")
	}

	// sendLoop should exit without panicking
	select {
	case <-sendLoopDone:
		// Success: sendLoop exited cleanly
	case <-time.After(2 * time.Second):
		t.Fatal("sendLoop did not exit after Close()")
	}
}

// TestSendReturnsErrorAfterClose verifies that Send() returns an error after
// the tunnel is closed, never returning nil (which would indicate success but
// the data would be silently dropped). This is a regression test for the bug
// where Go's select could randomly pick the sendChan branch when closeChan is
// also ready, causing silent data loss.
func TestRegisterUnregisterAfterStop(t *testing.T) {
	manager := NewTunnelManager(&Config{APIEndpoint: "http://127.0.0.1:1"})
	manager.Stop()

	// Register after Stop should not panic (no wg.Add after wg.Wait)
	tunnel := manager.Register("device-after-stop", nil)
	if tunnel == nil {
		t.Fatal("expected Register to return a non-nil tunnel even after Stop")
	}

	// Verify the tunnel is in the map
	current, ok := manager.Get("device-after-stop")
	if !ok || current != tunnel {
		t.Fatal("expected tunnel to be registered in the map after Stop")
	}

	// Unregister after Stop should not panic (no wg.Add after wg.Wait)
	manager.Unregister("device-after-stop", tunnel)

	_, ok = manager.Get("device-after-stop")
	if ok {
		t.Fatal("expected tunnel to be unregistered after Unregister")
	}
}

func okHTTPResponse() *http.Response {
	return &http.Response{
		StatusCode: http.StatusOK,
		Body:       io.NopCloser(strings.NewReader("")),
		Header:     make(http.Header),
	}
}

func waitTimeout(t *testing.T, wg *sync.WaitGroup, timeout time.Duration) {
	t.Helper()
	done := make(chan struct{})
	go func() {
		defer close(done)
		wg.Wait()
	}()
	select {
	case <-done:
	case <-time.After(timeout):
		t.Fatal("timed out waiting for WaitGroup")
	}
}

func TestSendReturnsErrorAfterClose(t *testing.T) {
	upgrader := websocket.Upgrader{}
	wsServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
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
	defer wsServer.Close()

	wsURL := "ws" + strings.TrimPrefix(wsServer.URL, "http")
	clientConn, _, err := websocket.DefaultDialer.Dial(wsURL, nil)
	if err != nil {
		t.Fatalf("failed to dial websocket server: %v", err)
	}

	tunnel := NewTunnelConn("device-send-after-close", clientConn)

	// Before close, Send should succeed
	if err := tunnel.Send([]byte("before-close")); err != nil {
		t.Fatalf("Send before close should succeed, got: %v", err)
	}

	// Close the tunnel
	tunnel.Close()

	// After close, Send must never return nil — the atomic closed check
	// ensures this regardless of sendChan buffer space or select randomness.
	for i := 0; i < 200; i++ {
		err := tunnel.Send([]byte("after-close"))
		if err == nil {
			t.Fatalf("Send after close returned nil on iteration %d — data would be silently lost", i)
		}
	}
}

// TestUnregisterSkipsOfflineWhenReplaced verifies that Unregister does not
// send an offline notification when a replacement tunnel registers in the
// window between removing the old tunnel and sending the offline notification.
// This is a regression test for REV32.
func TestUnregisterSkipsOfflineWhenReplaced(t *testing.T) {
	var mu sync.Mutex
	var statuses []string

	manager := NewTunnelManager(&Config{APIEndpoint: "http://test"})
	defer manager.Stop()

	// Pause Unregister after the old tunnel is removed from the map but
	// before the offline notification is sent, so we can deterministically
	// register a replacement in the race window.
	hook := make(chan struct{})
	manager.testHookAfterUnregisterRemoved = hook

	manager.httpClient = &http.Client{
		Transport: roundTripFunc(func(r *http.Request) (*http.Response, error) {
			var payload map[string]interface{}
			if err := json.NewDecoder(r.Body).Decode(&payload); err != nil {
				t.Errorf("failed to decode payload: %v", err)
			}
			_ = r.Body.Close()
			status, _ := payload["status"].(string)
			mu.Lock()
			statuses = append(statuses, status)
			mu.Unlock()
			return okHTTPResponse(), nil
		}),
	}

	first := manager.Register("device-123", nil)

	unregisterDone := make(chan struct{})
	go func() {
		manager.Unregister("device-123", first)
		close(unregisterDone)
	}()

	// Wait until Unregister has removed the old tunnel from the map.
	for {
		if _, ok := manager.Get("device-123"); !ok {
			break
		}
		time.Sleep(5 * time.Millisecond)
	}

	replacement := manager.Register("device-123", nil)

	// Let Unregister continue past the removal point.
	close(hook)

	select {
	case <-unregisterDone:
	case <-time.After(2 * time.Second):
		t.Fatal("timed out waiting for Unregister")
	}

	waitTimeout(t, &manager.wg, 2*time.Second)

	mu.Lock()
	defer mu.Unlock()
	for _, s := range statuses {
		if s == "offline" {
			t.Fatalf("expected no offline notification when replaced, got statuses=%v", statuses)
		}
	}

	current, ok := manager.Get("device-123")
	if !ok || current != replacement {
		t.Fatal("expected replacement tunnel to remain registered")
	}
}

// TestUnregisterSendsOfflineWhenNotReplaced verifies that Unregister still
// sends an offline notification when the unregistered tunnel was the current
// one and no replacement exists.
func TestUnregisterSendsOfflineWhenNotReplaced(t *testing.T) {
	var mu sync.Mutex
	var statuses []string

	manager := NewTunnelManager(&Config{APIEndpoint: "http://test"})
	manager.httpClient = &http.Client{
		Transport: roundTripFunc(func(r *http.Request) (*http.Response, error) {
			var payload map[string]interface{}
			_ = json.NewDecoder(r.Body).Decode(&payload)
			_ = r.Body.Close()
			status, _ := payload["status"].(string)
			mu.Lock()
			statuses = append(statuses, status)
			mu.Unlock()
			return okHTTPResponse(), nil
		}),
	}
	defer manager.Stop()

	tunnel := manager.Register("device-456", nil)
	manager.Unregister("device-456", tunnel)

	waitTimeout(t, &manager.wg, 2*time.Second)

	mu.Lock()
	defer mu.Unlock()
	hasOffline := false
	for _, s := range statuses {
		if s == "offline" {
			hasOffline = true
			break
		}
	}
	if !hasOffline {
		t.Fatalf("expected offline notification when not replaced, got statuses=%v", statuses)
	}
}

// TestCleanupDeadTunnelsSkipsOfflineWhenReplaced verifies that
// cleanupDeadTunnelsOnce does not send an offline notification when a dead
// tunnel is replaced by a new registration before the notification is sent.
// This is a regression test for REV32.
func TestCleanupDeadTunnelsSkipsOfflineWhenReplaced(t *testing.T) {
	config := &Config{HeartbeatTimeout: 50 * time.Millisecond}
	manager := NewTunnelManager(config)
	defer manager.Stop()

	// Pause cleanup after dead tunnels are removed from the map but before
	// sending offline notifications, so we can deterministically register a
	// replacement in the race window.
	hook := make(chan struct{})
	manager.testHookAfterDeadTunnelsRemoved = hook

	var mu sync.Mutex
	var statuses []string
	manager.httpClient = &http.Client{
		Transport: roundTripFunc(func(r *http.Request) (*http.Response, error) {
			var payload map[string]interface{}
			_ = json.NewDecoder(r.Body).Decode(&payload)
			_ = r.Body.Close()
			status, _ := payload["status"].(string)
			mu.Lock()
			statuses = append(statuses, status)
			mu.Unlock()
			return okHTTPResponse(), nil
		}),
	}

	first := manager.Register("device-cleanup", nil)
	first.mu.Lock()
	first.LastPing = time.Now().Add(-time.Hour)
	first.mu.Unlock()

	cleanupDone := make(chan struct{})
	go func() {
		manager.cleanupDeadTunnelsOnce()
		close(cleanupDone)
	}()

	// Wait until cleanup has removed the dead tunnel from the map.
	for {
		if _, ok := manager.Get("device-cleanup"); !ok {
			break
		}
		time.Sleep(5 * time.Millisecond)
	}

	replacement := manager.Register("device-cleanup", nil)

	// Let cleanup continue past the removal point.
	close(hook)

	select {
	case <-cleanupDone:
	case <-time.After(2 * time.Second):
		t.Fatal("timed out waiting for cleanup")
	}

	waitTimeout(t, &manager.wg, 2*time.Second)

	mu.Lock()
	defer mu.Unlock()
	for _, s := range statuses {
		if s == "offline" {
			t.Fatalf("expected no offline notification when replaced, got statuses=%v", statuses)
		}
	}

	current, ok := manager.Get("device-cleanup")
	if !ok || current != replacement {
		t.Fatal("expected replacement tunnel to remain registered")
	}
}

// TestCleanupDeadTunnelsSendsOfflineWhenNotReplaced verifies that
// cleanupDeadTunnelsOnce still sends an offline notification when a dead
// tunnel is removed and no replacement has registered.
func TestCleanupDeadTunnelsSendsOfflineWhenNotReplaced(t *testing.T) {
	config := &Config{HeartbeatTimeout: 50 * time.Millisecond}
	manager := NewTunnelManager(config)
	defer manager.Stop()

	var mu sync.Mutex
	var statuses []string
	manager.httpClient = &http.Client{
		Transport: roundTripFunc(func(r *http.Request) (*http.Response, error) {
			var payload map[string]interface{}
			_ = json.NewDecoder(r.Body).Decode(&payload)
			_ = r.Body.Close()
			status, _ := payload["status"].(string)
			mu.Lock()
			statuses = append(statuses, status)
			mu.Unlock()
			return okHTTPResponse(), nil
		}),
	}

	tunnel := manager.Register("device-cleanup-dead", nil)
	tunnel.mu.Lock()
	tunnel.LastPing = time.Now().Add(-time.Hour)
	tunnel.mu.Unlock()

	manager.cleanupDeadTunnelsOnce()

	waitTimeout(t, &manager.wg, 2*time.Second)

	mu.Lock()
	defer mu.Unlock()
	hasOffline := false
	for _, s := range statuses {
		if s == "offline" {
			hasOffline = true
			break
		}
	}
	if !hasOffline {
		t.Fatalf("expected offline notification when not replaced, got statuses=%v", statuses)
	}

	_, ok := manager.Get("device-cleanup-dead")
	if ok {
		t.Fatal("expected dead tunnel to be removed")
	}
}

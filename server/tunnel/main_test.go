package main

import (
	"io"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
	"time"
)

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

	valid := tunnelServer.validateDeviceToken("device-123", "token-abc")
	if !valid {
		t.Fatal("expected token validation to succeed")
	}

	if headerValue != "internal-secret" {
		t.Fatalf("expected internal api key header to be forwarded, got %q", headerValue)
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

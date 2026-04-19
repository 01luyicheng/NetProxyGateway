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

package main

import (
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

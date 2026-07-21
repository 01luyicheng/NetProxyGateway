package httpclient

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestPostJSONSuccess(t *testing.T) {
	expectedPayload := map[string]string{"key": "value"}
	expectedResult := map[string]bool{"valid": true}

	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			t.Errorf("expected POST, got %s", r.Method)
		}
		if ct := r.Header.Get("Content-Type"); ct != "application/json" {
			t.Errorf("expected Content-Type application/json, got %s", ct)
		}
		if key := r.Header.Get("X-Internal-API-Key"); key != "secret" {
			t.Errorf("expected X-Internal-API-Key secret, got %s", key)
		}

		body, _ := io.ReadAll(r.Body)
		var payload map[string]string
		if err := json.Unmarshal(body, &payload); err != nil {
			t.Fatalf("failed to unmarshal body: %v", err)
		}
		if payload["key"] != expectedPayload["key"] {
			t.Errorf("expected payload key %s, got %s", expectedPayload["key"], payload["key"])
		}

		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_ = json.NewEncoder(w).Encode(expectedResult)
	}))
	defer ts.Close()

	client := &http.Client{}
	var result map[string]bool
	if err := PostJSON(client, ts.URL, "secret", expectedPayload, &result); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if !result["valid"] {
		t.Errorf("expected valid=true, got %v", result["valid"])
	}
}

func TestPostJSONEmptyAPIKey(t *testing.T) {
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if key := r.Header.Get("X-Internal-API-Key"); key != "" {
			t.Errorf("expected empty X-Internal-API-Key, got %s", key)
		}
		w.WriteHeader(http.StatusOK)
		_ = json.NewEncoder(w).Encode(map[string]string{})
	}))
	defer ts.Close()

	client := &http.Client{}
	var result map[string]string
	if err := PostJSON(client, ts.URL, "", map[string]string{}, &result); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
}

func TestPostJSONNonOKStatus(t *testing.T) {
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
	}))
	defer ts.Close()

	client := &http.Client{}
	var result map[string]string
	err := PostJSON(client, ts.URL, "", map[string]string{}, &result)
	if err == nil {
		t.Fatal("expected error for non-OK status")
	}
	expected := "API returned status 500"
	if err.Error() != expected {
		t.Errorf("expected error %q, got %q", expected, err.Error())
	}
}

func TestPostJSONInvalidJSONResponse(t *testing.T) {
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("not-json"))
	}))
	defer ts.Close()

	client := &http.Client{}
	var result map[string]string
	err := PostJSON(client, ts.URL, "", map[string]string{}, &result)
	if err == nil {
		t.Fatal("expected error for invalid JSON response")
	}
	if !strings.Contains(err.Error(), "failed to decode response") {
		t.Errorf("expected error containing 'failed to decode response', got %v", err)
	}
}

func TestPostJSONInvalidPayload(t *testing.T) {
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		t.Error("server should not be called with invalid payload")
	}))
	defer ts.Close()

	client := &http.Client{}
	// channel cannot be JSON-encoded
	var result map[string]string
	err := PostJSON(client, ts.URL, "", make(chan int), &result)
	if err == nil {
		t.Fatal("expected error for invalid payload")
	}
	if !strings.Contains(err.Error(), "failed to encode request body") {
		t.Errorf("expected error containing 'failed to encode request body', got %v", err)
	}
}

func TestPostJSONRequestCreationError(t *testing.T) {
	client := &http.Client{}
	var result map[string]string

	// http.NewRequest will fail if endpoint contains invalid control character
	err := PostJSON(client, string([]byte{0x7f}), "", map[string]string{}, &result)
	if err == nil {
		t.Fatal("expected error for invalid request creation")
	}
	if !strings.Contains(err.Error(), "failed to create request") {
		t.Errorf("expected error containing 'failed to create request', got %v", err)
	}
}

func TestPostJSONRequestSendError(t *testing.T) {
	client := &http.Client{}
	var result map[string]string

	// Unreachable endpoint URL will cause client.Do to fail
	err := PostJSON(client, "http://127.0.0.1:0", "", map[string]string{}, &result)
	if err == nil {
		t.Fatal("expected error for failed request send")
	}
	if !strings.Contains(err.Error(), "failed to send request") {
		t.Errorf("expected error containing 'failed to send request', got %v", err)
	}
}

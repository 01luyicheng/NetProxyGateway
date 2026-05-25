package httpclient

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestPostJSON_Success(t *testing.T) {
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

func TestPostJSON_EmptyAPIKey(t *testing.T) {
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

func TestPostJSON_NonOKStatus(t *testing.T) {
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

func TestPostJSON_InvalidJSONResponse(t *testing.T) {
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
}

func TestPostJSON_InvalidPayload(t *testing.T) {
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
}

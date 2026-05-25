// Package httpclient provides reusable HTTP client utilities for internal services.
package httpclient

import (
	"bytes"
	"encoding/json"
	"fmt"
	"net/http"
)

// PostJSON sends a POST request with a JSON payload to the specified endpoint,
// verifies that the response status is HTTP 200 OK, and decodes the JSON response
// into the provided result value.
//
// If apiKey is non-empty, it is sent as the "X-Internal-API-Key" header.
func PostJSON(client *http.Client, endpoint string, apiKey string, payload interface{}, result interface{}) error {
	reqBody, err := json.Marshal(payload)
	if err != nil {
		return fmt.Errorf("failed to encode request body: %w", err)
	}

	req, err := http.NewRequest(http.MethodPost, endpoint, bytes.NewReader(reqBody))
	if err != nil {
		return fmt.Errorf("failed to create request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")
	if apiKey != "" {
		req.Header.Set("X-Internal-API-Key", apiKey)
	}

	resp, err := client.Do(req)
	if err != nil {
		return fmt.Errorf("failed to send request: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("API returned status %d", resp.StatusCode)
	}

	if err := json.NewDecoder(resp.Body).Decode(result); err != nil {
		return fmt.Errorf("failed to decode response: %w", err)
	}

	return nil
}

package main

import (
	"bytes"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/golang-jwt/jwt/v5"
	"github.com/netproxy/shared/ratelimit"
)

func issueAuthToken(t *testing.T, secret []byte, claims jwt.MapClaims) string {
	t.Helper()

	token := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)
	tokenString, err := token.SignedString(secret)
	if err != nil {
		t.Fatalf("failed to sign test token: %v", err)
	}

	return tokenString
}

func runAuthRequest(server *Server, token string) *httptest.ResponseRecorder {
	router := gin.New()
	router.GET("/protected", server.authMiddleware(), func(c *gin.Context) {
		c.Status(http.StatusOK)
	})

	req := httptest.NewRequest(http.MethodGet, "/protected", nil)
	req.Header.Set("Authorization", fmt.Sprintf("Bearer %s", token))
	recorder := httptest.NewRecorder()
	router.ServeHTTP(recorder, req)

	return recorder
}

func assertAuthValidationFailedResponse(t *testing.T, recorder *httptest.ResponseRecorder) {
	t.Helper()

	if recorder.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401 for invalid auth token, got %d", recorder.Code)
	}

	if !strings.Contains(recorder.Body.String(), ErrFailedToValidateToken.Error()) {
		t.Fatalf("expected error response to contain %q, got %s", ErrFailedToValidateToken.Error(), recorder.Body.String())
	}
}

func newPairingTestServer(t *testing.T) *Server {
	t.Helper()

	db, err := sql.Open("sqlite3", "file::memory:?cache=shared")
	if err != nil {
		t.Fatalf("failed to open in-memory db: %v", err)
	}
	db.SetMaxOpenConns(1)

	if err := initSchema(db); err != nil {
		db.Close()
		t.Fatalf("failed to init schema: %v", err)
	}

	t.Cleanup(func() {
		_ = db.Close()
	})

	server := &Server{
		db:          db,
		rateLimiter: ratelimit.NewRateLimiterWithDefaults(),
	}
	t.Cleanup(func() {
		server.rateLimiter.Stop()
	})

	return server
}

func insertPendingPairingSession(t *testing.T, server *Server, code string, deviceID string) {
	t.Helper()

	err := server.createPairingSessionDB(&PairingSession{
		Code:      code,
		DeviceID:  deviceID,
		Status:    "pending",
		CreatedAt: time.Now(),
		ExpiresAt: time.Now().Add(PairingCodeTTL),
		Used:      false,
	})
	if err != nil {
		t.Fatalf("failed to insert pairing session %s: %v", code, err)
	}
}

func runCreatePairingSessionRequest(server *Server, body string) *httptest.ResponseRecorder {
	router := gin.New()
	router.POST("/pairing", server.createPairingSession)

	req := httptest.NewRequest(http.MethodPost, "/pairing", strings.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	recorder := httptest.NewRecorder()
	router.ServeHTTP(recorder, req)

	return recorder
}

func TestInternalOrUserAuthMiddlewareAcceptsInternalKey(t *testing.T) {
	gin.SetMode(gin.TestMode)

	server := &Server{
		jwtSecret:      []byte("jwt-secret"),
		internalAPIKey: []byte("internal-secret"),
	}

	router := gin.New()
	router.POST("/internal", server.internalOrUserAuthMiddleware(), func(c *gin.Context) {
		c.Status(http.StatusOK)
	})

	req := httptest.NewRequest(http.MethodPost, "/internal", nil)
	req.Header.Set("X-Internal-API-Key", "internal-secret")
	recorder := httptest.NewRecorder()
	router.ServeHTTP(recorder, req)

	if recorder.Code != http.StatusOK {
		t.Fatalf("expected 200 for internal key auth, got %d", recorder.Code)
	}
}

func TestInternalOrUserAuthMiddlewareRejectsInvalidInternalKey(t *testing.T) {
	gin.SetMode(gin.TestMode)

	server := &Server{
		jwtSecret:      []byte("jwt-secret"),
		internalAPIKey: []byte("internal-secret"),
	}

	router := gin.New()
	router.POST("/internal", server.internalOrUserAuthMiddleware(), func(c *gin.Context) {
		c.Status(http.StatusOK)
	})

	req := httptest.NewRequest(http.MethodPost, "/internal", nil)
	req.Header.Set("X-Internal-API-Key", "wrong-secret")
	recorder := httptest.NewRecorder()
	router.ServeHTTP(recorder, req)

	if recorder.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401 for invalid internal key, got %d", recorder.Code)
	}
}

func TestInternalOrUserAuthMiddlewareFallsBackToBearerToken(t *testing.T) {
	gin.SetMode(gin.TestMode)

	server := &Server{
		jwtSecret:      []byte("jwt-secret"),
		internalAPIKey: []byte("internal-secret"),
	}

	token := jwt.NewWithClaims(jwt.SigningMethodHS256, jwt.MapClaims{
		"sub":  "engineer-1",
		"role": "engineer",
		"exp":  time.Now().Add(10 * time.Minute).Unix(),
	})
	tokenString, err := token.SignedString(server.jwtSecret)
	if err != nil {
		t.Fatalf("failed to sign test token: %v", err)
	}

	router := gin.New()
	router.POST("/internal", server.internalOrUserAuthMiddleware(), func(c *gin.Context) {
		c.Status(http.StatusOK)
	})

	req := httptest.NewRequest(http.MethodPost, "/internal", nil)
	req.Header.Set("Authorization", "Bearer "+tokenString)
	recorder := httptest.NewRecorder()
	router.ServeHTTP(recorder, req)

	if recorder.Code != http.StatusOK {
		t.Fatalf("expected 200 for bearer token auth, got %d", recorder.Code)
	}
}

func TestInternalOrUserAuthMiddlewareFallbackRejectsBearerTokenWithoutExp(t *testing.T) {
	gin.SetMode(gin.TestMode)

	server := &Server{
		jwtSecret:      []byte("jwt-secret"),
		internalAPIKey: []byte("internal-secret"),
	}

	tokenString := issueAuthToken(t, server.jwtSecret, jwt.MapClaims{
		"sub":  "engineer-1",
		"role": "engineer",
		"iat":  time.Now().Unix(),
	})

	router := gin.New()
	router.POST("/internal", server.internalOrUserAuthMiddleware(), func(c *gin.Context) {
		c.Status(http.StatusOK)
	})

	req := httptest.NewRequest(http.MethodPost, "/internal", nil)
	req.Header.Set("Authorization", "Bearer "+tokenString)
	recorder := httptest.NewRecorder()
	router.ServeHTTP(recorder, req)

	assertAuthValidationFailedResponse(t, recorder)
}

func TestInternalOrUserAuthMiddlewareFallbackRejectsBearerTokenWithInvalidRole(t *testing.T) {
	gin.SetMode(gin.TestMode)

	server := &Server{
		jwtSecret:      []byte("jwt-secret"),
		internalAPIKey: []byte("internal-secret"),
	}

	tokenString := issueAuthToken(t, server.jwtSecret, jwt.MapClaims{
		"sub":  "engineer-1",
		"role": "viewer",
		"iat":  time.Now().Unix(),
		"exp":  time.Now().Add(10 * time.Minute).Unix(),
	})

	router := gin.New()
	router.POST("/internal", server.internalOrUserAuthMiddleware(), func(c *gin.Context) {
		c.Status(http.StatusOK)
	})

	req := httptest.NewRequest(http.MethodPost, "/internal", nil)
	req.Header.Set("Authorization", "Bearer "+tokenString)
	recorder := httptest.NewRecorder()
	router.ServeHTTP(recorder, req)

	assertAuthValidationFailedResponse(t, recorder)
}

func TestInternalAuthMiddlewareAcceptsValidInternalKey(t *testing.T) {
	gin.SetMode(gin.TestMode)

	server := &Server{
		internalAPIKey: []byte("internal-secret"),
	}

	router := gin.New()
	router.POST("/internal-only", server.internalAuthMiddleware(), func(c *gin.Context) {
		c.Status(http.StatusOK)
	})

	req := httptest.NewRequest(http.MethodPost, "/internal-only", nil)
	req.Header.Set("X-Internal-API-Key", "internal-secret")
	recorder := httptest.NewRecorder()
	router.ServeHTTP(recorder, req)

	if recorder.Code != http.StatusOK {
		t.Fatalf("expected 200 for valid internal key, got %d", recorder.Code)
	}
}

func TestInternalAuthMiddlewareRejectsMissingInternalKey(t *testing.T) {
	gin.SetMode(gin.TestMode)

	server := &Server{
		internalAPIKey: []byte("internal-secret"),
	}

	router := gin.New()
	router.POST("/internal-only", server.internalAuthMiddleware(), func(c *gin.Context) {
		c.Status(http.StatusOK)
	})

	req := httptest.NewRequest(http.MethodPost, "/internal-only", nil)
	recorder := httptest.NewRecorder()
	router.ServeHTTP(recorder, req)

	if recorder.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401 for missing internal key, got %d", recorder.Code)
	}
}

func TestInternalAuthMiddlewareRejectsWhenInternalKeyNotConfigured(t *testing.T) {
	gin.SetMode(gin.TestMode)

	server := &Server{}

	router := gin.New()
	router.POST("/internal-only", server.internalAuthMiddleware(), func(c *gin.Context) {
		c.Status(http.StatusOK)
	})

	req := httptest.NewRequest(http.MethodPost, "/internal-only", nil)
	req.Header.Set("X-Internal-API-Key", "internal-secret")
	recorder := httptest.NewRecorder()
	router.ServeHTTP(recorder, req)

	if recorder.Code != http.StatusServiceUnavailable {
		t.Fatalf("expected 503 when internal key is not configured, got %d", recorder.Code)
	}
}

func TestAuthMiddlewareRejectsExpiredToken(t *testing.T) {
	gin.SetMode(gin.TestMode)

	server := &Server{
		jwtSecret: []byte("jwt-secret"),
	}

	now := time.Now()
	tokenString := issueAuthToken(t, server.jwtSecret, jwt.MapClaims{
		"sub":  "engineer-1",
		"role": "engineer",
		"iat":  now.Add(-5 * time.Minute).Unix(),
		"exp":  now.Add(-2 * time.Minute).Unix(),
	})

	recorder := runAuthRequest(server, tokenString)
	if recorder.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401 for expired token, got %d", recorder.Code)
	}
}

func TestAuthMiddlewareRejectsNbfInFuture(t *testing.T) {
	gin.SetMode(gin.TestMode)

	server := &Server{
		jwtSecret: []byte("jwt-secret"),
	}

	now := time.Now()
	tokenString := issueAuthToken(t, server.jwtSecret, jwt.MapClaims{
		"sub":  "engineer-1",
		"role": "engineer",
		"iat":  now.Unix(),
		"nbf":  now.Add(2 * time.Minute).Unix(),
		"exp":  now.Add(10 * time.Minute).Unix(),
	})

	recorder := runAuthRequest(server, tokenString)
	if recorder.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401 for token with future nbf, got %d", recorder.Code)
	}
}

func TestAuthMiddlewareRejectsIatInFuture(t *testing.T) {
	gin.SetMode(gin.TestMode)

	server := &Server{
		jwtSecret: []byte("jwt-secret"),
	}

	now := time.Now()
	tokenString := issueAuthToken(t, server.jwtSecret, jwt.MapClaims{
		"sub":  "engineer-1",
		"role": "engineer",
		"iat":  now.Add(2 * time.Minute).Unix(),
		"exp":  now.Add(10 * time.Minute).Unix(),
	})

	recorder := runAuthRequest(server, tokenString)
	if recorder.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401 for token with future iat, got %d", recorder.Code)
	}
}

func TestAuthMiddlewareRejectsTokenWithoutExp(t *testing.T) {
	gin.SetMode(gin.TestMode)

	server := &Server{
		jwtSecret: []byte("jwt-secret"),
	}

	tokenString := issueAuthToken(t, server.jwtSecret, jwt.MapClaims{
		"sub":  "engineer-1",
		"role": "engineer",
		"iat":  time.Now().Unix(),
	})

	recorder := runAuthRequest(server, tokenString)
	assertAuthValidationFailedResponse(t, recorder)
}

func TestAuthMiddlewareRejectsTokenWithoutSubClaim(t *testing.T) {
	gin.SetMode(gin.TestMode)

	server := &Server{
		jwtSecret: []byte("jwt-secret"),
	}

	now := time.Now()
	tokenString := issueAuthToken(t, server.jwtSecret, jwt.MapClaims{
		"role": "engineer",
		"iat":  now.Unix(),
		"exp":  now.Add(10 * time.Minute).Unix(),
	})

	recorder := runAuthRequest(server, tokenString)
	assertAuthValidationFailedResponse(t, recorder)
}

func TestAuthMiddlewareRejectsTokenWithNonStringSubClaim(t *testing.T) {
	gin.SetMode(gin.TestMode)

	server := &Server{
		jwtSecret: []byte("jwt-secret"),
	}

	now := time.Now()
	tokenString := issueAuthToken(t, server.jwtSecret, jwt.MapClaims{
		"sub":  12345,
		"role": "engineer",
		"iat":  now.Unix(),
		"exp":  now.Add(10 * time.Minute).Unix(),
	})

	recorder := runAuthRequest(server, tokenString)
	assertAuthValidationFailedResponse(t, recorder)
}

func TestAuthMiddlewareRejectsTokenWithoutRoleClaim(t *testing.T) {
	gin.SetMode(gin.TestMode)

	server := &Server{
		jwtSecret: []byte("jwt-secret"),
	}

	now := time.Now()
	tokenString := issueAuthToken(t, server.jwtSecret, jwt.MapClaims{
		"sub": "engineer-1",
		"iat": now.Unix(),
		"exp": now.Add(10 * time.Minute).Unix(),
	})

	recorder := runAuthRequest(server, tokenString)
	assertAuthValidationFailedResponse(t, recorder)
}

func TestAuthMiddlewareRejectsTokenWithNonStringRoleClaim(t *testing.T) {
	gin.SetMode(gin.TestMode)

	server := &Server{
		jwtSecret: []byte("jwt-secret"),
	}

	now := time.Now()
	tokenString := issueAuthToken(t, server.jwtSecret, jwt.MapClaims{
		"sub":  "engineer-1",
		"role": 123,
		"iat":  now.Unix(),
		"exp":  now.Add(10 * time.Minute).Unix(),
	})

	recorder := runAuthRequest(server, tokenString)
	assertAuthValidationFailedResponse(t, recorder)
}

func TestAuthMiddlewareRejectsTokenWithUnsupportedRoleClaim(t *testing.T) {
	gin.SetMode(gin.TestMode)

	server := &Server{
		jwtSecret: []byte("jwt-secret"),
	}

	now := time.Now()
	tokenString := issueAuthToken(t, server.jwtSecret, jwt.MapClaims{
		"sub":  "engineer-1",
		"role": "viewer",
		"iat":  now.Unix(),
		"exp":  now.Add(10 * time.Minute).Unix(),
	})

	recorder := runAuthRequest(server, tokenString)
	assertAuthValidationFailedResponse(t, recorder)
}

func TestAuthMiddlewareAcceptsValidEngineerClaims(t *testing.T) {
	gin.SetMode(gin.TestMode)

	server := &Server{
		jwtSecret: []byte("jwt-secret"),
	}

	now := time.Now()
	tokenString := issueAuthToken(t, server.jwtSecret, jwt.MapClaims{
		"sub":  "engineer-1",
		"role": "engineer",
		"iat":  now.Unix(),
		"exp":  now.Add(10 * time.Minute).Unix(),
	})

	router := gin.New()
	router.GET("/protected", server.authMiddleware(), func(c *gin.Context) {
		engineerIDValue, exists := c.Get("engineer_id")
		if !exists || engineerIDValue != "engineer-1" {
			c.AbortWithStatus(http.StatusInternalServerError)
			return
		}

		roleValue, exists := c.Get("role")
		if !exists || roleValue != "engineer" {
			c.AbortWithStatus(http.StatusInternalServerError)
			return
		}

		c.Status(http.StatusOK)
	})

	req := httptest.NewRequest(http.MethodGet, "/protected", nil)
	req.Header.Set("Authorization", "Bearer "+tokenString)
	recorder := httptest.NewRecorder()
	router.ServeHTTP(recorder, req)

	if recorder.Code != http.StatusOK {
		t.Fatalf("expected 200 for valid claims, got %d", recorder.Code)
	}
}

func TestAuthMiddlewareRejectsInvalidSignature(t *testing.T) {
	gin.SetMode(gin.TestMode)

	server := &Server{
		jwtSecret: []byte("jwt-secret"),
	}

	now := time.Now()
	tokenString := issueAuthToken(t, []byte("wrong-secret"), jwt.MapClaims{
		"sub":  "engineer-1",
		"role": "engineer",
		"iat":  now.Unix(),
		"exp":  now.Add(10 * time.Minute).Unix(),
	})

	recorder := runAuthRequest(server, tokenString)
	if recorder.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401 for token with invalid signature, got %d", recorder.Code)
	}
}

func TestServerCloseStopsCleanupWorkers(t *testing.T) {
	db, err := sql.Open("sqlite3", "file::memory:?cache=shared")
	if err != nil {
		t.Fatalf("failed to open in-memory db: %v", err)
	}
	if err := initSchema(db); err != nil {
		_ = db.Close()
		t.Fatalf("failed to init schema: %v", err)
	}

	server := &Server{
		db:                      db,
		rateLimiter:             ratelimit.NewRateLimiterWithDefaults(),
		cleanupSessionsInterval: 10 * time.Millisecond,
	}
	server.startCleanupWorkers()

	closeDone := make(chan error, 1)
	go func() {
		closeDone <- server.Close()
	}()

	select {
	case err := <-closeDone:
		if err != nil {
			t.Fatalf("expected close to succeed, got error: %v", err)
		}
	case <-time.After(1 * time.Second):
		t.Fatal("expected close to return after stopping cleanup workers")
	}

	if err := db.Ping(); err == nil {
		t.Fatal("expected database to be closed after server close")
	}
}

func TestValidateSessionExpiredTokenDeleteFailureDoesNotLogRawToken(t *testing.T) {
	gin.SetMode(gin.TestMode)

	server := newPairingTestServer(t)
	rawToken := "plain-sensitive-token"
	now := time.Now()

	err := server.createSessionTokenDB(&SessionToken{
		Token:      rawToken,
		DeviceID:   "device-1",
		EngineerID: "engineer-1",
		CreatedAt:  now.Add(-10 * time.Minute),
		ExpiresAt:  now.Add(-1 * time.Minute),
	})
	if err != nil {
		t.Fatalf("failed to create expired session token: %v", err)
	}

	_, err = server.db.Exec(`
		CREATE TRIGGER prevent_session_token_delete
		BEFORE DELETE ON session_tokens
		BEGIN
			SELECT RAISE(FAIL, 'delete blocked in test');
		END;
	`)
	if err != nil {
		t.Fatalf("failed to create delete-block trigger: %v", err)
	}

	var logBuf bytes.Buffer
	originalWriter := log.Writer()
	originalFlags := log.Flags()
	log.SetOutput(&logBuf)
	log.SetFlags(0)
	t.Cleanup(func() {
		log.SetOutput(originalWriter)
		log.SetFlags(originalFlags)
	})

	router := gin.New()
	router.POST("/validate", server.validateSession)

	req := httptest.NewRequest(http.MethodPost, "/validate", strings.NewReader(`{"device_id":"device-1","token":"plain-sensitive-token"}`))
	req.Header.Set("Content-Type", "application/json")
	recorder := httptest.NewRecorder()
	router.ServeHTTP(recorder, req)

	if recorder.Code != http.StatusOK {
		t.Fatalf("expected 200 for expired session token validation, got %d", recorder.Code)
	}
	if !strings.Contains(recorder.Body.String(), `"valid":false`) {
		t.Fatalf("expected response to mark token invalid, got %s", recorder.Body.String())
	}

	logOutput := logBuf.String()
	if !strings.Contains(logOutput, "Failed to delete expired session token") {
		t.Fatalf("expected delete failure log, got %q", logOutput)
	}
	if strings.Contains(logOutput, rawToken) {
		t.Fatalf("expected logs to redact raw token, got %q", logOutput)
	}
}

func TestGenerateUniquePairingCodeConflictThenSuccess(t *testing.T) {
	codes := []string{"111111", "222222"}
	codeIndex := 0
	lookupCount := 0

	code, err := generateUniquePairingCode(
		3,
		func() (string, error) {
			if codeIndex >= len(codes) {
				return "", errors.New("unexpected extra code generation")
			}
			generated := codes[codeIndex]
			codeIndex++
			return generated, nil
		},
		func(code string) (bool, error) {
			lookupCount++
			return code == "111111", nil
		},
	)

	if err != nil {
		t.Fatalf("expected success after resolving conflicts, got error: %v", err)
	}
	if code != "222222" {
		t.Fatalf("expected code 222222 after conflict resolution, got %s", code)
	}
	if lookupCount != 2 {
		t.Fatalf("expected 2 lookups, got %d", lookupCount)
	}
}

func TestGenerateUniquePairingCodeConflictLimitReached(t *testing.T) {
	maxRetries := 3
	generationCount := 0

	_, err := generateUniquePairingCode(
		maxRetries,
		func() (string, error) {
			generationCount++
			return "111111", nil
		},
		func(_ string) (bool, error) {
			return true, nil
		},
	)

	if !errors.Is(err, errPairingCodeConflictRetryLimitReached) {
		t.Fatalf("expected conflict retry limit error, got %v", err)
	}
	if generationCount != maxRetries {
		t.Fatalf("expected %d generation attempts, got %d", maxRetries, generationCount)
	}
}

func TestGenerateUniquePairingCodeReturnsLookupErrorImmediately(t *testing.T) {
	maxRetries := 5
	lookupErr := errors.New("query failed")
	lookupCount := 0

	_, err := generateUniquePairingCode(
		maxRetries,
		func() (string, error) {
			return "333333", nil
		},
		func(_ string) (bool, error) {
			lookupCount++
			return false, lookupErr
		},
	)

	if !errors.Is(err, lookupErr) {
		t.Fatalf("expected lookup error to be returned directly, got %v", err)
	}
	if lookupCount != 1 {
		t.Fatalf("expected lookup to stop after first error, got %d attempts", lookupCount)
	}
}

func TestCreatePairingSessionConflictThenSuccess(t *testing.T) {
	gin.SetMode(gin.TestMode)

	server := newPairingTestServer(t)
	insertPendingPairingSession(t, server, "111111", "existing-device")

	codes := []string{"111111", "222222"}
	codeIndex := 0
	server.pairingCodeGenerator = func() (string, error) {
		if codeIndex >= len(codes) {
			return "", errors.New("unexpected extra generation")
		}
		code := codes[codeIndex]
		codeIndex++
		return code, nil
	}

	recorder := runCreatePairingSessionRequest(server, `{"device_id":"new-device"}`)

	if recorder.Code != http.StatusCreated {
		t.Fatalf("expected 201 when second generated code succeeds, got %d", recorder.Code)
	}
	if !strings.Contains(recorder.Body.String(), `"code":"222222"`) {
		t.Fatalf("expected response to contain second generated code, got %s", recorder.Body.String())
	}
	if codeIndex != 2 {
		t.Fatalf("expected 2 code generation attempts, got %d", codeIndex)
	}

	stored, err := server.getPairingSessionDB("222222")
	if err != nil {
		t.Fatalf("failed to verify inserted session: %v", err)
	}
	if stored == nil {
		t.Fatalf("expected session with code 222222 to be inserted")
	}
	if stored.DeviceID != "new-device" {
		t.Fatalf("expected inserted device_id=new-device, got %s", stored.DeviceID)
	}
}

func TestCreatePairingSessionConflictRetryLimitReturns503(t *testing.T) {
	gin.SetMode(gin.TestMode)

	server := newPairingTestServer(t)
	insertPendingPairingSession(t, server, "111111", "existing-device")

	attempts := 0
	server.pairingCodeGenerator = func() (string, error) {
		attempts++
		return "111111", nil
	}

	recorder := runCreatePairingSessionRequest(server, `{"device_id":"new-device"}`)

	if recorder.Code != http.StatusServiceUnavailable {
		t.Fatalf("expected 503 when code conflicts exhaust retries, got %d", recorder.Code)
	}
	if !strings.Contains(recorder.Body.String(), ErrFailedToResolvePairingCodeConflict.Error()) {
		t.Fatalf("expected response to contain %q, got %s", ErrFailedToResolvePairingCodeConflict.Error(), recorder.Body.String())
	}
	if attempts != MaxPairingCodeConflictRetries {
		t.Fatalf("expected %d generation attempts, got %d", MaxPairingCodeConflictRetries, attempts)
	}
}

func TestCreatePairingSessionDatabaseErrorReturns500(t *testing.T) {
	gin.SetMode(gin.TestMode)

	server := newPairingTestServer(t)
	attempts := 0
	server.pairingCodeGenerator = func() (string, error) {
		attempts++
		return "333333", nil
	}

	if err := server.db.Close(); err != nil {
		t.Fatalf("failed to close db before request: %v", err)
	}

	recorder := runCreatePairingSessionRequest(server, `{"device_id":"new-device"}`)

	if recorder.Code != http.StatusInternalServerError {
		t.Fatalf("expected 500 when database insert fails with non-unique error, got %d", recorder.Code)
	}

	var response map[string]string
	if err := json.Unmarshal(recorder.Body.Bytes(), &response); err != nil {
		t.Fatalf("failed to decode response body: %v; body=%s", err, recorder.Body.String())
	}
	if response["error"] != ErrFailedToCreateSession.Error() {
		t.Fatalf("expected error %q, got %q", ErrFailedToCreateSession.Error(), response["error"])
	}
	if attempts != 1 {
		t.Fatalf("expected 1 generation attempt before database error, got %d", attempts)
	}
}

func TestNewHTTPServerSetsMaxHeaderBytes(t *testing.T) {
	server := newHTTPServer(":8080", gin.New())

	if server.MaxHeaderBytes != MaxHTTPHeaderBytes {
		t.Fatalf("expected MaxHeaderBytes=%d, got %d", MaxHTTPHeaderBytes, server.MaxHeaderBytes)
	}
}

func TestIsValidSessionTransition(t *testing.T) {
	tests := []struct {
		name      string
		current   string
		requested string
		want      bool
	}{
		{"pending to connected", "pending", "connected", true},
		{"pending to expired", "pending", "expired", true},
		{"pending to disconnected", "pending", "disconnected", false},
		{"connected to disconnected", "connected", "disconnected", true},
		{"connected to expired", "connected", "expired", true},
		{"connected to pending", "connected", "pending", false},
		{"disconnected has no transitions", "disconnected", "connected", false},
		{"disconnected to expired", "disconnected", "expired", false},
		{"expired has no transitions", "expired", "pending", false},
		{"expired to connected", "expired", "connected", false},
		{"unknown current status", "unknown", "connected", false},
		{"unknown requested status", "pending", "unknown", false},
		{"empty current status", "", "connected", false},
		{"same status pending", "pending", "pending", false},
		{"same status connected", "connected", "connected", false},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got := isValidSessionTransition(tt.current, tt.requested)
			if got != tt.want {
				t.Errorf("isValidSessionTransition(%q, %q) = %v, want %v", tt.current, tt.requested, got, tt.want)
			}
		})
	}
}

func TestGetEngineerID(t *testing.T) {
	gin.SetMode(gin.TestMode)

	tests := []struct {
		name          string
		contextValue  interface{}
		contextExists bool
		wantID        string
		wantErr       bool
	}{
		{"valid engineer_id", "engineer-1", true, "engineer-1", false},
		{"missing engineer_id", nil, false, "", true},
		{"empty engineer_id", "", true, "", true},
		{"non-string engineer_id (int)", 12345, true, "", true},
		{"non-string engineer_id (bool)", true, true, "", true},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			w := httptest.NewRecorder()
			c, _ := gin.CreateTestContext(w)
			if tt.contextExists {
				c.Set("engineer_id", tt.contextValue)
			}

			gotID, err := getEngineerID(c)
			if tt.wantErr {
				if err == nil {
					t.Errorf("getEngineerID() expected error, got nil")
				}
				if !errors.Is(err, ErrMissingEngineer) {
					t.Errorf("getEngineerID() error = %v, want ErrMissingEngineer", err)
				}
			} else {
				if err != nil {
					t.Errorf("getEngineerID() unexpected error: %v", err)
				}
				if gotID != tt.wantID {
					t.Errorf("getEngineerID() = %q, want %q", gotID, tt.wantID)
				}
			}
		})
	}
}

func TestCompareAndUpdatePairingSessionDB_ConcurrentModification(t *testing.T) {
	gin.SetMode(gin.TestMode)

	server := newPairingTestServer(t)
	insertPendingPairingSession(t, server, "123456", "device-1")

	// Read the session
	session, err := server.getPairingSessionDB("123456")
	if err != nil {
		t.Fatalf("failed to get session: %v", err)
	}

	// Simulate a concurrent modification: directly update the session in DB
	session.Status = "connected"
	session.EngineerID = "other-engineer"
	session.Used = true
	if err := server.updatePairingSessionDB(session); err != nil {
		t.Fatalf("failed to simulate concurrent modification: %v", err)
	}

	// Now try to update with stale expected values (pending, empty engineer)
	staleSession := &PairingSession{
		Code:       "123456",
		DeviceID:   "device-1",
		Status:     "connected",
		EngineerID: "my-engineer",
		Used:       true,
		CreatedAt:  session.CreatedAt,
		ExpiresAt:  session.ExpiresAt,
	}

	err = server.compareAndUpdatePairingSessionDB(staleSession, "pending", "")
	if !errors.Is(err, ErrConcurrentModification) {
		t.Fatalf("expected ErrConcurrentModification, got %v", err)
	}

	// Verify the session was NOT overwritten
	current, err := server.getPairingSessionDB("123456")
	if err != nil {
		t.Fatalf("failed to get session after failed update: %v", err)
	}
	if current.EngineerID != "other-engineer" {
		t.Fatalf("session was overwritten: engineer_id = %q, want %q", current.EngineerID, "other-engineer")
	}
}

func TestCompareAndUpdatePairingSessionDB_SuccessWhenNoConflict(t *testing.T) {
	gin.SetMode(gin.TestMode)

	server := newPairingTestServer(t)
	insertPendingPairingSession(t, server, "654321", "device-2")

	// Read the session
	session, err := server.getPairingSessionDB("654321")
	if err != nil {
		t.Fatalf("failed to get session: %v", err)
	}

	// Update with correct expected values
	session.Status = "connected"
	session.EngineerID = "engineer-1"
	session.Used = true

	err = server.compareAndUpdatePairingSessionDB(session, "pending", "")
	if err != nil {
		t.Fatalf("expected successful update, got error: %v", err)
	}

	// Verify the update took effect
	current, err := server.getPairingSessionDB("654321")
	if err != nil {
		t.Fatalf("failed to get session after update: %v", err)
	}
	if current.Status != "connected" {
		t.Fatalf("status = %q, want %q", current.Status, "connected")
	}
	if current.EngineerID != "engineer-1" {
		t.Fatalf("engineer_id = %q, want %q", current.EngineerID, "engineer-1")
	}
}

func TestCompareAndUpdatePairingSessionDB_StatusChangedConcurrently(t *testing.T) {
	gin.SetMode(gin.TestMode)

	server := newPairingTestServer(t)
	insertPendingPairingSession(t, server, "111222", "device-3")

	// Read the session (simulates handler reading stale data)
	session, err := server.getPairingSessionDB("111222")
	if err != nil {
		t.Fatalf("failed to get session: %v", err)
	}

	// Simulate a concurrent modification: another engineer already connected
	session.Status = "connected"
	session.EngineerID = "other-engineer"
	session.Used = true
	if err := server.updatePairingSessionDB(session); err != nil {
		t.Fatalf("failed to simulate concurrent modification: %v", err)
	}

	// Now try to update with stale expected values (pending, empty engineer)
	// This simulates the TOCTOU race where both engineers read the same stale data
	session.Status = "connected"
	session.EngineerID = "my-engineer"

	err = server.compareAndUpdatePairingSessionDB(session, "pending", "")
	if !errors.Is(err, ErrConcurrentModification) {
		t.Fatalf("expected ErrConcurrentModification, got %v", err)
	}

	// Verify the session was NOT overwritten - first engineer's claim is preserved
	current, err := server.getPairingSessionDB("111222")
	if err != nil {
		t.Fatalf("failed to get session after failed update: %v", err)
	}
	if current.EngineerID != "other-engineer" {
		t.Fatalf("session was overwritten: engineer_id = %q, want %q", current.EngineerID, "other-engineer")
	}
}

// TestCompareAndUpdatePairingSessionDB_DoesNotRejectExpiredSession verifies that
// compareAndUpdatePairingSessionDB enforces only optimistic-locking invariants
// (code + expected status + expected engineer_id). Expiry is intentionally NOT
// part of the WHERE clause: markSessionExpired must be able to write
// status="expired" for sessions whose expires_at is already in the past, and
// all callers perform an explicit time.Now().After(session.ExpiresAt) check
// before mutating state. Including expires_at > now (REV36) made
// markSessionExpired always return ErrConcurrentModification, leaving the
// expired status unwritten and causing updatePairingSession to regress from
// 410 Gone to 409 Conflict.
func TestCompareAndUpdatePairingSessionDB_DoesNotRejectExpiredSession(t *testing.T) {
	gin.SetMode(gin.TestMode)

	server := newPairingTestServer(t)

	// Insert an already-expired pending session.
	expiredSession := &PairingSession{
		Code:      "000000",
		DeviceID:  "device-expired",
		Status:    "pending",
		CreatedAt: time.Now().Add(-2 * PairingCodeTTL),
		ExpiresAt: time.Now().Add(-time.Minute),
		Used:      false,
	}
	if err := server.createPairingSessionDB(expiredSession); err != nil {
		t.Fatalf("failed to insert expired session: %v", err)
	}

	// Updating an expired session must succeed when the optimistic-locking
	// invariants (status + engineer_id) match. Expiry enforcement is the
	// caller's responsibility.
	err := server.compareAndUpdatePairingSessionDB(&PairingSession{
		Code:       "000000",
		DeviceID:   "device-expired",
		Status:     "expired",
		EngineerID: "engineer-1",
		Used:       true,
		CreatedAt:  expiredSession.CreatedAt,
		ExpiresAt:  expiredSession.ExpiresAt,
	}, "pending", "")
	if err != nil {
		t.Fatalf("expected nil error for expired session with matching invariants, got %v", err)
	}

	// Verify the session WAS overwritten with the new status.
	current, err := server.getPairingSessionDB("000000")
	if err != nil {
		t.Fatalf("failed to get session after update: %v", err)
	}
	if current.Status != "expired" {
		t.Fatalf("status = %q, want %q; expired session was not updated", current.Status, "expired")
	}
}

// TestMarkSessionExpired_SucceedsForExpiredSession verifies that
// markSessionExpired can write status="expired" for an already-expired session.
// This is the core regression from REV36: the expires_at > now WHERE clause
// made this operation always fail with ErrConcurrentModification.
func TestMarkSessionExpired_SucceedsForExpiredSession(t *testing.T) {
	gin.SetMode(gin.TestMode)

	server := newPairingTestServer(t)

	expiredSession := &PairingSession{
		Code:      "111111",
		DeviceID:  "device-mark-expired",
		Status:    "pending",
		CreatedAt: time.Now().Add(-2 * PairingCodeTTL),
		ExpiresAt: time.Now().Add(-time.Minute),
		Used:      false,
	}
	if err := server.createPairingSessionDB(expiredSession); err != nil {
		t.Fatalf("failed to insert expired session: %v", err)
	}

	if err := server.markSessionExpired(expiredSession, "pending", ""); err != nil {
		t.Fatalf("markSessionExpired failed for expired session: %v", err)
	}

	current, err := server.getPairingSessionDB("111111")
	if err != nil {
		t.Fatalf("failed to get session after markSessionExpired: %v", err)
	}
	if current.Status != "expired" {
		t.Fatalf("status = %q, want %q; markSessionExpired did not persist expired status", current.Status, "expired")
	}
}

// TestUpdatePairingSession_ConcurrentModificationReturns409 exercises the full
// HTTP handler path (not just the DB layer) to verify that when two requests
// race to claim the same pending pairing session, exactly one succeeds and
// the loser observes a 409 Conflict rather than silently overwriting the
// winner's claim (the TOCTOU race described in REV42).
func TestUpdatePairingSession_ConcurrentModificationReturns409(t *testing.T) {
	gin.SetMode(gin.TestMode)

	server := newPairingTestServer(t)
	insertPendingPairingSession(t, server, "race-001", "device-race")

	router := gin.New()
	router.PUT("/pair/:code", func(c *gin.Context) {
		c.Set("engineer_id", c.GetHeader("X-Engineer-ID"))
		server.updatePairingSession(c)
	})

	// Two-party barrier: both requests must reach compareAndUpdatePairingSessionDB
	// before either proceeds, guaranteeing both read pending state and one UPDATE
	// wins while the other gets ErrConcurrentModification.
	arrived := make(chan struct{}, 2)
	proceed := make(chan struct{})
	server.testHookCompareAndUpdatePairingSessionDB = func() {
		arrived <- struct{}{}
		<-proceed
	}

	sendConnectRequest := func(engineerID string) *httptest.ResponseRecorder {
		req := httptest.NewRequest(http.MethodPut, "/pair/race-001", strings.NewReader(`{"status":"connected"}`))
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("X-Engineer-ID", engineerID)
		recorder := httptest.NewRecorder()
		router.ServeHTTP(recorder, req)
		return recorder
	}

	var wg sync.WaitGroup
	recorders := make([]*httptest.ResponseRecorder, 2)
	wg.Add(2)
	go func() { defer wg.Done(); recorders[0] = sendConnectRequest("engineer-A") }()
	go func() { defer wg.Done(); recorders[1] = sendConnectRequest("engineer-B") }()

	for i := 0; i < 2; i++ {
		select {
		case <-arrived:
		case <-time.After(2 * time.Second):
			t.Fatal("timeout waiting for goroutines to reach barrier")
		}
	}
	close(proceed)
	wg.Wait()

	var codes [2]int
	for i, rec := range recorders {
		codes[i] = rec.Code
	}

	if codes[0] == http.StatusOK && codes[1] == http.StatusConflict {
		return
	}
	if codes[1] == http.StatusOK && codes[0] == http.StatusConflict {
		return
	}
	t.Fatalf("expected one 200 and one 409, got %v", codes)
}

// TestUpdatePairingSession_ExpiredReturns410Gone verifies that updating an
// expired pairing session returns 410 Gone, not 409 Conflict. This is the
// user-facing regression from REV36: the expires_at > now WHERE clause made
// markSessionExpired always return ErrConcurrentModification, which the
// updatePairingSession handler mapped to 409 Conflict instead of 410 Gone.
func TestUpdatePairingSession_ExpiredReturns410Gone(t *testing.T) {
	gin.SetMode(gin.TestMode)

	server := newPairingTestServer(t)

	// Insert an already-expired pending session.
	expiredSession := &PairingSession{
		Code:      "222222",
		DeviceID:  "device-expired-update",
		Status:    "pending",
		CreatedAt: time.Now().Add(-2 * PairingCodeTTL),
		ExpiresAt: time.Now().Add(-time.Minute),
		Used:      false,
	}
	if err := server.createPairingSessionDB(expiredSession); err != nil {
		t.Fatalf("failed to insert expired session: %v", err)
	}

	router := gin.New()
	router.PUT("/pair/:code", func(c *gin.Context) {
		c.Set("engineer_id", c.GetHeader("X-Engineer-ID"))
		server.updatePairingSession(c)
	})

	req := httptest.NewRequest(http.MethodPut, "/pair/222222", strings.NewReader(`{"status":"connected"}`))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Engineer-ID", "engineer-1")
	recorder := httptest.NewRecorder()
	router.ServeHTTP(recorder, req)

	if recorder.Code != http.StatusGone {
		t.Fatalf("expected 410 Gone for expired session, got %d (body=%s)", recorder.Code, recorder.Body.String())
	}

	// Verify the session status was persisted as "expired".
	current, err := server.getPairingSessionDB("222222")
	if err != nil {
		t.Fatalf("failed to get session after update: %v", err)
	}
	if current.Status != "expired" {
		t.Fatalf("status = %q, want %q; markSessionExpired did not persist expired status", current.Status, "expired")
	}
}

func TestUpsertDeviceStatusDB_WritesMillisecondTimestamp(t *testing.T) {
	server := newPairingTestServer(t)

	// Use a time with sub-second nanoseconds so Unix() and UnixMilli() differ.
	lastSeen := time.Unix(1700000000, 123456789)
	status := &DeviceStatus{
		DeviceID:   "device-ms",
		Status:     "online",
		LastSeen:   lastSeen,
		TunnelAddr: "192.168.1.1:8080",
	}
	if err := server.upsertDeviceStatusDB(status); err != nil {
		t.Fatalf("upsertDeviceStatusDB failed: %v", err)
	}

	var storedMs int64
	err := server.db.QueryRow("SELECT last_seen FROM device_status WHERE device_id = ?", status.DeviceID).Scan(&storedMs)
	if err != nil {
		t.Fatalf("failed to read stored last_seen: %v", err)
	}

	wantMs := lastSeen.UnixMilli()
	if storedMs != wantMs {
		t.Fatalf("stored last_seen = %d, want %d (millisecond precision)", storedMs, wantMs)
	}

	stored, err := server.getDeviceStatusDB(status.DeviceID)
	if err != nil {
		t.Fatalf("getDeviceStatusDB failed: %v", err)
	}
	if stored.LastSeen.UnixMilli() != wantMs {
		t.Fatalf("parsed LastSeen = %d, want %d", stored.LastSeen.UnixMilli(), wantMs)
	}
}

func TestGetDeviceStatusDB_BackwardCompatibleWithSecondPrecision(t *testing.T) {
	server := newPairingTestServer(t)

	// Simulate a legacy row where last_seen was stored in seconds (REV33).
	deviceID := "device-legacy"
	legacySeconds := int64(1700000000)
	_, err := server.db.Exec(
		"INSERT INTO device_status (device_id, status, last_seen, tunnel_addr) VALUES (?, ?, ?, ?)",
		deviceID, "online", legacySeconds, "192.168.1.1:8080",
	)
	if err != nil {
		t.Fatalf("failed to insert legacy row: %v", err)
	}

	stored, err := server.getDeviceStatusDB(deviceID)
	if err != nil {
		t.Fatalf("getDeviceStatusDB failed: %v", err)
	}

	wantMs := legacySeconds * 1000
	if stored.LastSeen.UnixMilli() != wantMs {
		t.Fatalf("legacy second-precision last_seen parsed as %d ms, want %d ms", stored.LastSeen.UnixMilli(), wantMs)
	}
}

func TestUpsertDeviceStatusDB_StaleOfflineDoesNotOverwriteNewerOnline(t *testing.T) {
	server := newPairingTestServer(t)
	base := time.Unix(1700000000, 0)

	// A newer online event is stored first.
	if err := server.upsertDeviceStatusDB(&DeviceStatus{
		DeviceID: "device-stale",
		Status:   "online",
		LastSeen: base.Add(time.Millisecond),
	}); err != nil {
		t.Fatalf("upsert online failed: %v", err)
	}

	// A delayed offline notification with an older timestamp must be ignored.
	if err := server.upsertDeviceStatusDB(&DeviceStatus{
		DeviceID: "device-stale",
		Status:   "offline",
		LastSeen: base,
	}); err != nil {
		t.Fatalf("upsert stale offline failed: %v", err)
	}

	stored, err := server.getDeviceStatusDB("device-stale")
	if err != nil {
		t.Fatalf("getDeviceStatusDB failed: %v", err)
	}
	if stored.Status != "online" {
		t.Fatalf("status = %q, want %q; stale offline overwrote newer online", stored.Status, "online")
	}
}

func TestUpsertDeviceStatusDB_SameMillisecondEventIsIgnored(t *testing.T) {
	server := newPairingTestServer(t)
	ts := time.Unix(1700000000, 0)

	if err := server.upsertDeviceStatusDB(&DeviceStatus{
		DeviceID: "device-same-ms",
		Status:   "online",
		LastSeen: ts,
	}); err != nil {
		t.Fatalf("upsert online failed: %v", err)
	}

	// An event with the exact same timestamp must not overwrite the existing row
	// because the guard uses a strict greater-than comparison.
	if err := server.upsertDeviceStatusDB(&DeviceStatus{
		DeviceID: "device-same-ms",
		Status:   "offline",
		LastSeen: ts,
	}); err != nil {
		t.Fatalf("upsert same-ms offline failed: %v", err)
	}

	stored, err := server.getDeviceStatusDB("device-same-ms")
	if err != nil {
		t.Fatalf("getDeviceStatusDB failed: %v", err)
	}
	if stored.Status != "online" {
		t.Fatalf("status = %q, want %q; same-millisecond event overwrote existing row", stored.Status, "online")
	}
}

func TestUpdateDeviceStatus_AcceptsLastSeenFromRequest(t *testing.T) {
	gin.SetMode(gin.TestMode)

	server := newPairingTestServer(t)
	server.internalAPIKey = []byte("internal-secret")

	router := gin.New()
	router.POST("/api/device/status", server.internalAuthMiddleware(), server.updateDeviceStatus)

	base := time.Now()
	onlineTs := base.Add(time.Millisecond).UnixMilli()
	offlineTs := base.UnixMilli()

	// Online event with explicit last_seen.
	reqBody := fmt.Sprintf(`{"device_id":"device-req","status":"online","last_seen":%d}`, onlineTs)
	req := httptest.NewRequest(http.MethodPost, "/api/device/status", strings.NewReader(reqBody))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Internal-API-Key", "internal-secret")
	recorder := httptest.NewRecorder()
	router.ServeHTTP(recorder, req)
	if recorder.Code != http.StatusOK {
		t.Fatalf("online update expected 200, got %d: %s", recorder.Code, recorder.Body.String())
	}

	// Delayed offline event with an older last_seen.
	reqBody = fmt.Sprintf(`{"device_id":"device-req","status":"offline","last_seen":%d}`, offlineTs)
	req = httptest.NewRequest(http.MethodPost, "/api/device/status", strings.NewReader(reqBody))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Internal-API-Key", "internal-secret")
	recorder = httptest.NewRecorder()
	router.ServeHTTP(recorder, req)
	if recorder.Code != http.StatusOK {
		t.Fatalf("offline update expected 200, got %d: %s", recorder.Code, recorder.Body.String())
	}

	stored, err := server.getDeviceStatusDB("device-req")
	if err != nil {
		t.Fatalf("getDeviceStatusDB failed: %v", err)
	}
	if stored.Status != "online" {
		t.Fatalf("status = %q, want %q; stale offline from request overwrote newer online", stored.Status, "online")
	}
	if stored.LastSeen.UnixMilli() != onlineTs {
		t.Fatalf("LastSeen = %d, want %d", stored.LastSeen.UnixMilli(), onlineTs)
	}
}

func TestRateLimitKeyPrefersAuthenticatedIdentity(t *testing.T) {
	gin.SetMode(gin.TestMode)

	tests := []struct {
		name       string
		setup      func(*gin.Context)
		remoteAddr string
		wantPrefix string
	}{
		{
			name: "internal role",
			setup: func(c *gin.Context) {
				c.Set("role", "internal")
			},
			wantPrefix: "internal",
		},
		{
			name: "jwt engineer_id",
			setup: func(c *gin.Context) {
				c.Set("engineer_id", "engineer-1")
			},
			wantPrefix: "jwt:engineer-1",
		},
		{
			name:       "no identity falls back to client ip",
			remoteAddr: "192.0.2.1:1234",
			wantPrefix: "192.0.2.1",
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			w := httptest.NewRecorder()
			c, _ := gin.CreateTestContext(w)
			c.Request = httptest.NewRequest(http.MethodGet, "/", nil)
			c.Request.RemoteAddr = tt.remoteAddr
			if tt.setup != nil {
				tt.setup(c)
			}

			got := rateLimitKey(c)
			if got != tt.wantPrefix {
				t.Errorf("rateLimitKey() = %q, want %q", got, tt.wantPrefix)
			}
		})
	}
}

func TestRateLimitMiddlewareAllowsRequestsUnderLimit(t *testing.T) {
	gin.SetMode(gin.TestMode)

	server := &Server{
		rateLimiter: ratelimit.NewRateLimiterWithDefaults(),
	}
	defer server.rateLimiter.Stop()

	router := gin.New()
	router.GET("/limited", func(c *gin.Context) {
		c.Set("engineer_id", "engineer-1")
		c.Next()
	}, server.rateLimitMiddleware(), func(c *gin.Context) {
		c.Status(http.StatusOK)
	})

	for i := 0; i < 5; i++ {
		req := httptest.NewRequest(http.MethodGet, "/limited", nil)
		recorder := httptest.NewRecorder()
		router.ServeHTTP(recorder, req)
		if recorder.Code != http.StatusOK {
			t.Fatalf("request %d: expected 200, got %d", i+1, recorder.Code)
		}
	}
}

func TestRateLimitMiddlewareBlocksRequestsOverLimit(t *testing.T) {
	gin.SetMode(gin.TestMode)

	server := &Server{
		rateLimiter: ratelimit.NewRateLimiterWithDefaults(),
	}
	defer server.rateLimiter.Stop()

	router := gin.New()
	router.GET("/limited", func(c *gin.Context) {
		c.Set("engineer_id", "engineer-1")
		c.Next()
	}, server.rateLimitMiddleware(), func(c *gin.Context) {
		c.Status(http.StatusOK)
	})

	for i := 0; i < 5; i++ {
		req := httptest.NewRequest(http.MethodGet, "/limited", nil)
		recorder := httptest.NewRecorder()
		router.ServeHTTP(recorder, req)
		if recorder.Code != http.StatusOK {
			t.Fatalf("setup request %d: expected 200, got %d", i+1, recorder.Code)
		}
	}

	req := httptest.NewRequest(http.MethodGet, "/limited", nil)
	recorder := httptest.NewRecorder()
	router.ServeHTTP(recorder, req)
	if recorder.Code != http.StatusTooManyRequests {
		t.Fatalf("expected 429 after exceeding rate limit, got %d", recorder.Code)
	}
	if !strings.Contains(recorder.Body.String(), ErrRateLimitExceeded.Error()) {
		t.Fatalf("expected error %q in response, got %s", ErrRateLimitExceeded.Error(), recorder.Body.String())
	}
}

func TestRateLimitMiddlewareUsesSeparateBucketsPerIdentity(t *testing.T) {
	gin.SetMode(gin.TestMode)

	server := &Server{
		rateLimiter: ratelimit.NewRateLimiterWithDefaults(),
	}
	defer server.rateLimiter.Stop()

	router := gin.New()
	router.GET("/limited/:engineer", func(c *gin.Context) {
		c.Set("engineer_id", c.Param("engineer"))
		c.Next()
	}, server.rateLimitMiddleware(), func(c *gin.Context) {
		c.Status(http.StatusOK)
	})

	// Exhaust the limit for engineer-A.
	for i := 0; i < 5; i++ {
		req := httptest.NewRequest(http.MethodGet, "/limited/engineer-A", nil)
		recorder := httptest.NewRecorder()
		router.ServeHTTP(recorder, req)
		if recorder.Code != http.StatusOK {
			t.Fatalf("setup request %d for engineer-A: expected 200, got %d", i+1, recorder.Code)
		}
	}

	blockedRecorder := httptest.NewRecorder()
	router.ServeHTTP(blockedRecorder, httptest.NewRequest(http.MethodGet, "/limited/engineer-A", nil))
	if blockedRecorder.Code != http.StatusTooManyRequests {
		t.Fatalf("expected engineer-A to be rate limited, got %d", blockedRecorder.Code)
	}

	// engineer-B should still be allowed because it uses a separate bucket.
	allowedRecorder := httptest.NewRecorder()
	router.ServeHTTP(allowedRecorder, httptest.NewRequest(http.MethodGet, "/limited/engineer-B", nil))
	if allowedRecorder.Code != http.StatusOK {
		t.Fatalf("expected engineer-B to be allowed, got %d", allowedRecorder.Code)
	}
}

// TestGetPairingSessionRateLimit_AllowsLegitimatePolling verifies that
// successful session lookups (200) reset the rate-limiter failure counter, so
// engineers polling a valid pairing code are never blocked. This is the REV34
// fix: previously getPairingSession never called rateLimiter.Success, so every
// poll incremented the failure counter and after 5 polls within 5 minutes the
// engineer was blocked for 15 minutes.
func TestGetPairingSessionRateLimit_AllowsLegitimatePolling(t *testing.T) {
	gin.SetMode(gin.TestMode)

	server := newPairingTestServer(t)
	server.jwtSecret = []byte("jwt-secret")
	server.internalAPIKey = []byte("internal-secret")
	insertPendingPairingSession(t, server, "123456", "device-1")

	tokenString := issueAuthToken(t, server.jwtSecret, jwt.MapClaims{
		"sub":  "engineer-1",
		"role": "engineer",
		"iat":  time.Now().Unix(),
		"exp":  time.Now().Add(10 * time.Minute).Unix(),
	})

	router := gin.New()
	router.GET("/api/pair/:code", server.internalOrUserAuthMiddleware(), server.rateLimitMiddleware(), server.getPairingSession)

	makeRequest := func(code string) *httptest.ResponseRecorder {
		req := httptest.NewRequest(http.MethodGet, "/api/pair/"+code, nil)
		req.Header.Set("Authorization", "Bearer "+tokenString)
		recorder := httptest.NewRecorder()
		router.ServeHTTP(recorder, req)
		return recorder
	}

	// Legitimate polling of a valid code must never be rate-limited, even well
	// past the brute-force threshold (MaxAttempts=5).
	for i := 0; i < 20; i++ {
		recorder := makeRequest("123456")
		if recorder.Code != http.StatusOK {
			t.Fatalf("legitimate poll %d: expected 200, got %d (body=%s)", i+1, recorder.Code, recorder.Body.String())
		}
	}
}

// TestGetPairingSessionRateLimit_BlocksBruteForce verifies that repeated
// lookups of a non-existent code (404) ARE rate-limited, preserving the
// brute-force protection that REV34 intended.
func TestGetPairingSessionRateLimit_BlocksBruteForce(t *testing.T) {
	gin.SetMode(gin.TestMode)

	server := newPairingTestServer(t)
	server.jwtSecret = []byte("jwt-secret")
	server.internalAPIKey = []byte("internal-secret")

	tokenString := issueAuthToken(t, server.jwtSecret, jwt.MapClaims{
		"sub":  "engineer-2",
		"role": "engineer",
		"iat":  time.Now().Unix(),
		"exp":  time.Now().Add(10 * time.Minute).Unix(),
	})

	router := gin.New()
	router.GET("/api/pair/:code", server.internalOrUserAuthMiddleware(), server.rateLimitMiddleware(), server.getPairingSession)

	makeRequest := func(code string) *httptest.ResponseRecorder {
		req := httptest.NewRequest(http.MethodGet, "/api/pair/"+code, nil)
		req.Header.Set("Authorization", "Bearer "+tokenString)
		recorder := httptest.NewRecorder()
		router.ServeHTTP(recorder, req)
		return recorder
	}

	// First MaxAttempts (5) failed lookups return 404 and count toward the limit.
	for i := 0; i < 5; i++ {
		recorder := makeRequest("999999")
		if recorder.Code != http.StatusNotFound {
			t.Fatalf("brute-force probe %d: expected 404, got %d", i+1, recorder.Code)
		}
	}

	// The 6th attempt must be blocked.
	recorder := makeRequest("999999")
	if recorder.Code != http.StatusTooManyRequests {
		t.Fatalf("expected 429 after exceeding rate limit on brute-force probes, got %d", recorder.Code)
	}
	if !strings.Contains(recorder.Body.String(), ErrRateLimitExceeded.Error()) {
		t.Fatalf("expected error %q in response, got %s", ErrRateLimitExceeded.Error(), recorder.Body.String())
	}
}

// TestGetPairingSession_ConcurrentModification_RefreshDBError verifies that when
// markSessionExpired hits ErrConcurrentModification and the refresh query fails,
// the handler returns 500 Internal Server Error instead of masking the failure
// as 410 Gone (REV31).
func TestGetPairingSession_ConcurrentModification_RefreshDBError(t *testing.T) {
	gin.SetMode(gin.TestMode)

	server := newPairingTestServer(t)
	callCount := 0
	server.testHookGetPairingSessionDB = func(code string) (*PairingSession, error) {
		defer func() { callCount++ }()
		if callCount == 0 {
			// Initial read returns an expired session.
			return &PairingSession{
				Code:      code,
				DeviceID:  "device-1",
				Status:    "pending",
				CreatedAt: time.Now().Add(-PairingCodeTTL),
				ExpiresAt: time.Now().Add(-time.Minute),
				Used:      false,
			}, nil
		}
		// Refresh query fails.
		return nil, errors.New("simulated database failure")
	}

	router := gin.New()
	router.GET("/api/pair/:code", server.getPairingSession)

	req := httptest.NewRequest(http.MethodGet, "/api/pair/123456", nil)
	recorder := httptest.NewRecorder()
	router.ServeHTTP(recorder, req)

	if recorder.Code != http.StatusInternalServerError {
		t.Fatalf("expected 500 on refresh DB error, got %d", recorder.Code)
	}
	if !strings.Contains(recorder.Body.String(), ErrFailedToQueryDatabase.Error()) {
		t.Fatalf("expected error %q in response, got %s", ErrFailedToQueryDatabase.Error(), recorder.Body.String())
	}
}

// TestGetPairingSession_ConcurrentModification_StillExpired verifies that when
// markSessionExpired hits ErrConcurrentModification but the refreshed session
// is still expired, the handler returns 410 Gone consistent with the normal
// expiration path (REV31).
func TestGetPairingSession_ConcurrentModification_StillExpired(t *testing.T) {
	gin.SetMode(gin.TestMode)

	server := newPairingTestServer(t)
	server.testHookGetPairingSessionDB = func(code string) (*PairingSession, error) {
		return &PairingSession{
			Code:      code,
			DeviceID:  "device-1",
			Status:    "pending",
			CreatedAt: time.Now().Add(-PairingCodeTTL),
			ExpiresAt: time.Now().Add(-time.Minute),
			Used:      false,
		}, nil
	}

	router := gin.New()
	router.GET("/api/pair/:code", server.getPairingSession)

	req := httptest.NewRequest(http.MethodGet, "/api/pair/123456", nil)
	recorder := httptest.NewRecorder()
	router.ServeHTTP(recorder, req)

	if recorder.Code != http.StatusGone {
		t.Fatalf("expected 410 when session still expired after refresh, got %d", recorder.Code)
	}
	if !strings.Contains(recorder.Body.String(), ErrSessionExpired.Error()) {
		t.Fatalf("expected error %q in response, got %s", ErrSessionExpired.Error(), recorder.Body.String())
	}
}

// TestGetPairingSession_ConcurrentModification_RefreshedToUnexpired verifies
// that when markSessionExpired hits ErrConcurrentModification and the refreshed
// session is no longer expired, the handler returns 200 OK with the current
// session state (REV31).
func TestGetPairingSession_ConcurrentModification_RefreshedToUnexpired(t *testing.T) {
	gin.SetMode(gin.TestMode)

	server := newPairingTestServer(t)
	refreshedSession := &PairingSession{
		Code:       "123456",
		DeviceID:   "device-1",
		Status:     "connected",
		EngineerID: "engineer-1",
		CreatedAt:  time.Now(),
		ExpiresAt:  time.Now().Add(PairingCodeTTL),
		Used:       true,
	}
	callCount := 0
	server.testHookGetPairingSessionDB = func(code string) (*PairingSession, error) {
		defer func() { callCount++ }()
		if callCount == 0 {
			// Initial read sees an expired session.
			return &PairingSession{
				Code:      code,
				DeviceID:  "device-1",
				Status:    "pending",
				CreatedAt: time.Now().Add(-PairingCodeTTL),
				ExpiresAt: time.Now().Add(-time.Minute),
				Used:      false,
			}, nil
		}
		// Refresh sees the session that was concurrently updated to unexpired.
		return refreshedSession, nil
	}

	router := gin.New()
	router.GET("/api/pair/:code", server.getPairingSession)

	req := httptest.NewRequest(http.MethodGet, "/api/pair/123456", nil)
	recorder := httptest.NewRecorder()
	router.ServeHTTP(recorder, req)

	if recorder.Code != http.StatusOK {
		t.Fatalf("expected 200 when refreshed to unexpired, got %d", recorder.Code)
	}

	var got PairingSession
	if err := json.Unmarshal(recorder.Body.Bytes(), &got); err != nil {
		t.Fatalf("failed to decode response body: %v", err)
	}
	if got.Status != refreshedSession.Status || got.EngineerID != refreshedSession.EngineerID {
		t.Fatalf("response mismatch: got %+v, want %+v", got, refreshedSession)
	}
}

// TestCreateSessionToken_RejectsExpiredSession verifies that createSessionToken
// rejects pairing sessions whose ExpiresAt has passed, even if the session
// status is still "connected". (REV43)
func TestCreateSessionToken_RejectsExpiredSession(t *testing.T) {
	gin.SetMode(gin.TestMode)

	server := newPairingTestServer(t)
	// Insert a session that is already expired but still "connected"
	if err := server.createPairingSessionDB(&PairingSession{
		Code:       "exp-1",
		DeviceID:   "device-exp",
		Status:     "connected",
		EngineerID: "engineer-1",
		Used:       true,
		CreatedAt:  time.Now().Add(-2 * PairingCodeTTL),
		ExpiresAt:  time.Now().Add(-1 * time.Second), // already expired
	}); err != nil {
		t.Fatalf("failed to create expired session: %v", err)
	}

	router := gin.New()
	router.POST("/session/token", func(c *gin.Context) {
		c.Set("engineer_id", "engineer-1")
		server.createSessionToken(c)
	})

	req := httptest.NewRequest(http.MethodPost, "/session/token", strings.NewReader(`{"code":"exp-1"}`))
	req.Header.Set("Content-Type", "application/json")
	recorder := httptest.NewRecorder()
	router.ServeHTTP(recorder, req)

	if recorder.Code != http.StatusBadRequest {
		t.Fatalf("expected 400 for expired session, got %d: %s", recorder.Code, recorder.Body.String())
	}
}

// TestGetPairingSession_ConcurrentModificationReturns410 verifies that
// getPairingSession returns 410 Gone (not 200) when a session is expired
// but marking it as expired fails due to concurrent modification. (REV44)
func TestGetPairingSession_ConcurrentModificationReturns410(t *testing.T) {
	gin.SetMode(gin.TestMode)

	server := newPairingTestServer(t)

	// Create a session that is expired but still "connected" (simulate race where
	// another request set status=connected after the session expired)
	if err := server.createPairingSessionDB(&PairingSession{
		Code:       "concurrent-exp",
		DeviceID:   "device-ce",
		Status:     "connected",
		EngineerID: "other-engineer",
		Used:       false,
		CreatedAt:  time.Now().Add(-2 * PairingCodeTTL),
		ExpiresAt:  time.Now().Add(-1 * time.Second), // already expired
	}); err != nil {
		t.Fatalf("failed to create session: %v", err)
	}

	router := gin.New()
	router.GET("/pair/:code", func(c *gin.Context) {
		server.getPairingSession(c)
	})

	req := httptest.NewRequest(http.MethodGet, "/pair/concurrent-exp", nil)
	recorder := httptest.NewRecorder()
	router.ServeHTTP(recorder, req)

	// Should return 410 Gone, not 200 OK, even though the session status
	// is "connected" — the session is expired by ExpiresAt.
	// The optimistic lock in markSessionExpired will fail (status != pending),
	// but we should still return 410, not 200.
	if recorder.Code != http.StatusGone {
		t.Fatalf("expected 410 Gone for expired session with concurrent modification, got %d: %s", recorder.Code, recorder.Body.String())
	}
}

func TestGenerateCode(t *testing.T) {
	for i := 0; i < 1000; i++ {
		code, err := generateCode()
		if err != nil {
			t.Fatalf("generateCode() returned error: %v", err)
		}
		if len(code) != 6 {
			t.Errorf("generateCode() returned code of length %d, expected 6: %s", len(code), code)
		}
		for _, ch := range code {
			if ch < '0' || ch > '9' {
				t.Errorf("generateCode() returned non-numeric character in code: %s", code)
				break
			}
		}
	}
}

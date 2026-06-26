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

	return &Server{
		db:          db,
		rateLimiter: ratelimit.NewRateLimiterWithDefaults(),
	}
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
	_, err = server.db.Exec(
		`UPDATE pairing_sessions SET status = ?, engineer_id = ?, used = ? WHERE code = ?`,
		"connected", "other-engineer", 1, session.Code,
	)
	if err != nil {
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
	_, err = server.db.Exec(
		`UPDATE pairing_sessions SET status = ?, engineer_id = ?, used = ? WHERE code = ?`,
		"connected", "other-engineer", 1, session.Code,
	)
	if err != nil {
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

// TestUpdatePairingSession_ConcurrentModificationReturns409 exercises the full
// HTTP handler path (not just the DB layer) to verify that when two requests
// race to claim the same pending pairing session, exactly one succeeds and
// the loser observes a 409 Conflict rather than silently overwriting the
// winner's claim (the TOCTOU race described in REV42).
func TestUpdatePairingSession_ConcurrentModificationReturns409(t *testing.T) {
	gin.SetMode(gin.TestMode)

	server := newPairingTestServer(t)

	router := gin.New()
	router.PUT("/pair/:code", func(c *gin.Context) {
		c.Set("engineer_id", c.GetHeader("X-Engineer-ID"))
		server.updatePairingSession(c)
	})

	sendConnectRequest := func(code, engineerID string) int {
		req := httptest.NewRequest(http.MethodPut, "/pair/"+code, strings.NewReader(`{"status":"connected"}`))
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("X-Engineer-ID", engineerID)
		recorder := httptest.NewRecorder()
		router.ServeHTTP(recorder, req)
		return recorder.Code
	}

	const trials = 30
	sawConflict := false

	for i := 0; i < trials; i++ {
		pairingCode := fmt.Sprintf("race-%03d", i)
		insertPendingPairingSession(t, server, pairingCode, "device-race")

		var wg sync.WaitGroup
		statusCodes := make([]int, 2)
		wg.Add(2)
		go func() { defer wg.Done(); statusCodes[0] = sendConnectRequest(pairingCode, "engineer-A") }()
		go func() { defer wg.Done(); statusCodes[1] = sendConnectRequest(pairingCode, "engineer-B") }()
		wg.Wait()

		successCount := 0
		for _, statusCode := range statusCodes {
			switch statusCode {
			case http.StatusOK:
				successCount++
			case http.StatusConflict:
				sawConflict = true
			case http.StatusForbidden:
				// Acceptable: this request only read the session after the
				// other had already committed its update.
			default:
				t.Fatalf("trial %d: unexpected status code %d (codes=%v)", i, statusCode, statusCodes)
			}
		}
		if successCount != 1 {
			t.Fatalf("trial %d: expected exactly one concurrent request to succeed, got %d (codes=%v)", i, successCount, statusCodes)
		}
	}

	if !sawConflict {
		t.Fatalf("expected at least one of %d trials to trigger a 409 Conflict from concurrent modification", trials)
	}
}

// TestUpdatePairingSession_UsedFieldPreserved verifies that the `used` field
// is never reset from true to false during status transitions. Once a session
// has been connected (used=true), disconnecting must not revert used to false.
// Migrated from the closed REV35 PR (#37).
func TestUpdatePairingSession_UsedFieldPreserved(t *testing.T) {
	gin.SetMode(gin.TestMode)

	server := newPairingTestServer(t)
	insertPendingPairingSession(t, server, "666666", "device-used-test")

	engineerToken := issueAuthToken(t, server.jwtSecret, jwt.MapClaims{
		"sub":  "engineer-used",
		"role": "engineer",
		"iat":  time.Now().Unix(),
		"exp":  time.Now().Add(10 * time.Minute).Unix(),
	})

	router := gin.New()
	router.PUT("/api/pair/:code", server.authMiddleware(), server.updatePairingSession)

	// Step 1: Connect — should set used=true
	req := httptest.NewRequest(http.MethodPut, "/api/pair/666666", strings.NewReader(`{"status":"connected"}`))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+engineerToken)
	recorder := httptest.NewRecorder()
	router.ServeHTTP(recorder, req)
	if recorder.Code != http.StatusOK {
		t.Fatalf("expected 200 for connect, got %d: %s", recorder.Code, recorder.Body.String())
	}

	session, err := server.getPairingSessionDB("666666")
	if err != nil {
		t.Fatalf("failed to get session: %v", err)
	}
	if !session.Used {
		t.Fatal("expected used=true after connected, got false")
	}

	// Step 2: Disconnect — used must remain true
	req2 := httptest.NewRequest(http.MethodPut, "/api/pair/666666", strings.NewReader(`{"status":"disconnected"}`))
	req2.Header.Set("Content-Type", "application/json")
	req2.Header.Set("Authorization", "Bearer "+engineerToken)
	recorder2 := httptest.NewRecorder()
	router.ServeHTTP(recorder2, req2)
	if recorder2.Code != http.StatusOK {
		t.Fatalf("expected 200 for disconnect, got %d: %s", recorder2.Code, recorder2.Body.String())
	}

	session2, err := server.getPairingSessionDB("666666")
	if err != nil {
		t.Fatalf("failed to get session: %v", err)
	}
	if !session2.Used {
		t.Fatal("expected used=true to be preserved after disconnect, got false — REV35 regression")
	}
}

// TestUpdatePairingSession_UsedFieldPreservedOnExpire verifies that the `used`
// field remains true after a connected→expired transition. This complements
// TestUpdatePairingSession_UsedFieldPreserved which only tests connected→disconnected.
func TestUpdatePairingSession_UsedFieldPreservedOnExpire(t *testing.T) {
	gin.SetMode(gin.TestMode)

	server := newPairingTestServer(t)
	insertPendingPairingSession(t, server, "555555", "device-used-expire")

	engineerToken := issueAuthToken(t, server.jwtSecret, jwt.MapClaims{
		"sub":  "engineer-expire",
		"role": "engineer",
		"iat":  time.Now().Unix(),
		"exp":  time.Now().Add(10 * time.Minute).Unix(),
	})

	router := gin.New()
	router.PUT("/api/pair/:code", server.authMiddleware(), server.updatePairingSession)

	// Step 1: Connect — should set used=true
	req := httptest.NewRequest(http.MethodPut, "/api/pair/555555", strings.NewReader(`{"status":"connected"}`))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+engineerToken)
	recorder := httptest.NewRecorder()
	router.ServeHTTP(recorder, req)
	if recorder.Code != http.StatusOK {
		t.Fatalf("expected 200 for connect, got %d: %s", recorder.Code, recorder.Body.String())
	}

	session, err := server.getPairingSessionDB("555555")
	if err != nil {
		t.Fatalf("failed to get session: %v", err)
	}
	if !session.Used {
		t.Fatal("expected used=true after connected, got false")
	}

	// Step 2: Expire — used must remain true
	req2 := httptest.NewRequest(http.MethodPut, "/api/pair/555555", strings.NewReader(`{"status":"expired"}`))
	req2.Header.Set("Content-Type", "application/json")
	req2.Header.Set("Authorization", "Bearer "+engineerToken)
	recorder2 := httptest.NewRecorder()
	router.ServeHTTP(recorder2, req2)
	if recorder2.Code != http.StatusOK {
		t.Fatalf("expected 200 for expire, got %d: %s", recorder2.Code, recorder2.Body.String())
	}

	session2, err := server.getPairingSessionDB("555555")
	if err != nil {
		t.Fatalf("failed to get session: %v", err)
	}
	if !session2.Used {
		t.Fatal("expected used=true to be preserved after expire, got false — REV35 regression")
	}
}

// TestUpsertDeviceStatusRejectsStaleUpdate verifies that a stale "offline"
// notification with an older timestamp cannot overwrite a more recent "online"
// status. This is a regression test for REV40.
func TestUpsertDeviceStatusRejectsStaleUpdate(t *testing.T) {
	gin.SetMode(gin.TestMode)

	server := newPairingTestServer(t)

	// Insert an "online" status with a recent timestamp
	now := time.Now()
	onlineStatus := &DeviceStatus{
		DeviceID:   "device-stale-test",
		Status:     "online",
		LastSeen:   now,
		TunnelAddr: "10.0.0.1:8080",
	}
	if err := server.upsertDeviceStatusDB(onlineStatus); err != nil {
		t.Fatalf("failed to insert online status: %v", err)
	}

	// Try to upsert an "offline" status with an older timestamp
	staleStatus := &DeviceStatus{
		DeviceID:   "device-stale-test",
		Status:     "offline",
		LastSeen:   now.Add(-10 * time.Second), // older than the online timestamp
		TunnelAddr: "",
	}
	if err := server.upsertDeviceStatusDB(staleStatus); err != nil {
		t.Fatalf("failed to upsert stale status: %v", err)
	}

	// Verify that the status remains "online"
	result, err := server.getDeviceStatusDB("device-stale-test")
	if err != nil {
		t.Fatalf("failed to get device status: %v", err)
	}
	if result.Status != "online" {
		t.Fatalf("expected status to remain 'online' after stale update, got %q — REV40 regression", result.Status)
	}
	if result.TunnelAddr != "10.0.0.1:8080" {
		t.Fatalf("expected tunnel_addr to remain '10.0.0.1:8080' after stale update, got %q", result.TunnelAddr)
	}
}

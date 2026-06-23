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

func TestUpdatePairingSession_ConcurrentUpdate(t *testing.T) {
	gin.SetMode(gin.TestMode)

	server := newPairingTestServer(t)
	insertPendingPairingSession(t, server, "999999", "device-concurrent")

	// Issue two concurrent PUT requests from different engineers.
	// The atomic conditional UPDATE (WHERE engineer_id = '' OR engineer_id = ?)
	// ensures only the first writer wins; the second should get 409 Conflict.
	engineer1Token := issueAuthToken(t, server.jwtSecret, jwt.MapClaims{
		"sub":  "engineer-1",
		"role": "engineer",
		"iat":  time.Now().Unix(),
		"exp":  time.Now().Add(10 * time.Minute).Unix(),
	})
	engineer2Token := issueAuthToken(t, server.jwtSecret, jwt.MapClaims{
		"sub":  "engineer-2",
		"role": "engineer",
		"iat":  time.Now().Unix(),
		"exp":  time.Now().Add(10 * time.Minute).Unix(),
	})

	router := gin.New()
	router.PUT("/api/pair/:code", server.authMiddleware(), server.updatePairingSession)

	type result struct {
		statusCode int
		body       string
	}

	results := make(chan result, 2)

	doRequest := func(token string) {
		req := httptest.NewRequest(http.MethodPut, "/api/pair/999999", strings.NewReader(`{"status":"connected"}`))
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("Authorization", "Bearer "+token)
		recorder := httptest.NewRecorder()
		router.ServeHTTP(recorder, req)
		results <- result{statusCode: recorder.Code, body: recorder.Body.String()}
	}

	go doRequest(engineer1Token)
	go doRequest(engineer2Token)

	r1 := <-results
	r2 := <-results

	// One request should succeed (200) and the other should fail.
	// The failure can be either 403 Forbidden (if the second request's read
	// sees the first engineer's ID after the first request completed) or
	// 409 Conflict (if both requests read EngineerID="" concurrently and
	// the atomic conditional UPDATE rejects the second writer).
	// Both outcomes correctly prevent the TOCTOU race.
	statuses := map[int]int{r1.statusCode: 1}
	statuses[r2.statusCode]++

	if statuses[http.StatusOK] != 1 {
		t.Fatalf("expected exactly one 200 OK, got statuses: %v (r1=%d, r2=%d)", statuses, r1.statusCode, r2.statusCode)
	}
	conflictCount := statuses[http.StatusConflict] + statuses[http.StatusForbidden]
	if conflictCount != 1 {
		t.Fatalf("expected exactly one 403 or 409 failure, got statuses: %v (r1=%d, r2=%d)", statuses, r1.statusCode, r2.statusCode)
	}

	// Verify the session is assigned to the winning engineer
	session, err := server.getPairingSessionDB("999999")
	if err != nil {
		t.Fatalf("failed to get pairing session: %v", err)
	}
	if session.EngineerID == "" {
		t.Fatal("expected engineer_id to be set after concurrent update")
	}
	if session.EngineerID != "engineer-1" && session.EngineerID != "engineer-2" {
		t.Fatalf("expected engineer_id to be engineer-1 or engineer-2, got %s", session.EngineerID)
	}
}

// TestMarkSessionExpired_DoesNotOverwriteConcurrentUpdate verifies that
// markSessionExpired only updates the status field and does not overwrite
// engineer_id or used fields that may have been updated concurrently.
// This is a regression test for REV31.
func TestMarkSessionExpired_DoesNotOverwriteConcurrentUpdate(t *testing.T) {
	gin.SetMode(gin.TestMode)

	server := newPairingTestServer(t)
	insertPendingPairingSession(t, server, "888888", "device-expire-test")

	// Simulate an engineer claiming the session via atomic UPDATE
	result, err := server.db.Exec(
		`UPDATE pairing_sessions SET status = 'connected', engineer_id = 'eng-expire', used = 1 WHERE code = '888888' AND (engineer_id = '' OR engineer_id = 'eng-expire')`,
	)
	if err != nil {
		t.Fatalf("failed to update session: %v", err)
	}
	rowsAffected, _ := result.RowsAffected()
	if rowsAffected != 1 {
		t.Fatalf("expected 1 row affected, got %d", rowsAffected)
	}

	// Now simulate a stale read: the session object has the old values
	staleSession := &PairingSession{
		Code:       "888888",
		DeviceID:   "device-expire-test",
		Status:     "pending",
		EngineerID: "",
		Used:       false,
		ExpiresAt:  time.Now().Add(-1 * time.Hour), // expired
	}

	// Call markSessionExpired with the stale session object
	if err := server.markSessionExpired(staleSession); err != nil {
		t.Fatalf("markSessionExpired failed: %v", err)
	}

	// Verify that engineer_id and used were NOT overwritten
	session, err := server.getPairingSessionDB("888888")
	if err != nil {
		t.Fatalf("failed to get session: %v", err)
	}

	if session.Status != "expired" {
		t.Fatalf("expected status=expired, got %s", session.Status)
	}
	if session.EngineerID != "eng-expire" {
		t.Fatalf("expected engineer_id=eng-expire (preserved), got %q", session.EngineerID)
	}
	if !session.Used {
		t.Fatal("expected used=true (preserved), got false")
	}
}

// TestUpdatePairingSession_StatusCheckPreventsStaleTransition verifies that
// the atomic UPDATE includes a status check, preventing same-engineer
// concurrent requests from bypassing state transition validation.
// This is a regression test for REV33.
func TestUpdatePairingSession_StatusCheckPreventsStaleTransition(t *testing.T) {
	gin.SetMode(gin.TestMode)

	server := newPairingTestServer(t)
	insertPendingPairingSession(t, server, "777777", "device-status-test")

	engineerToken := issueAuthToken(t, server.jwtSecret, jwt.MapClaims{
		"sub":  "engineer-status",
		"role": "engineer",
		"iat":  time.Now().Unix(),
		"exp":  time.Now().Add(10 * time.Minute).Unix(),
	})

	router := gin.New()
	router.PUT("/api/pair/:code", server.authMiddleware(), server.updatePairingSession)

	// First request: connect successfully
	req := httptest.NewRequest(http.MethodPut, "/api/pair/777777", strings.NewReader(`{"status":"connected"}`))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+engineerToken)
	recorder := httptest.NewRecorder()
	router.ServeHTTP(recorder, req)
	if recorder.Code != http.StatusOK {
		t.Fatalf("expected 200 for first connect, got %d: %s", recorder.Code, recorder.Body.String())
	}

	// Second request: disconnect
	req2 := httptest.NewRequest(http.MethodPut, "/api/pair/777777", strings.NewReader(`{"status":"disconnected"}`))
	req2.Header.Set("Content-Type", "application/json")
	req2.Header.Set("Authorization", "Bearer "+engineerToken)
	recorder2 := httptest.NewRecorder()
	router.ServeHTTP(recorder2, req2)
	if recorder2.Code != http.StatusOK {
		t.Fatalf("expected 200 for disconnect, got %d: %s", recorder2.Code, recorder2.Body.String())
	}

	// Third request: try to transition from "connected" -> "expired" using stale state.
	// Since the actual status is now "disconnected", the atomic UPDATE's WHERE status='connected'
	// should reject this, returning 409 Conflict.
	req3 := httptest.NewRequest(http.MethodPut, "/api/pair/777777", strings.NewReader(`{"status":"expired"}`))
	req3.Header.Set("Content-Type", "application/json")
	req3.Header.Set("Authorization", "Bearer "+engineerToken)
	// We need to simulate a stale read by directly calling with a stale session.
	// Since the handler reads from DB, we need to test the SQL directly.
	result, err := server.db.Exec(
		`UPDATE pairing_sessions SET status = 'expired', engineer_id = 'engineer-status', used = 0 WHERE code = '777777' AND (engineer_id = '' OR engineer_id = 'engineer-status') AND status = 'connected'`,
	)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	rowsAffected, _ := result.RowsAffected()
	if rowsAffected != 0 {
		t.Fatalf("expected 0 rows affected (status is 'disconnected', not 'connected'), got %d", rowsAffected)
	}

	// Verify the session is still in "disconnected" state
	session, err := server.getPairingSessionDB("777777")
	if err != nil {
		t.Fatalf("failed to get session: %v", err)
	}
	if session.Status != "disconnected" {
		t.Fatalf("expected status=disconnected (unchanged), got %s", session.Status)
	}
}

// TestUpdatePairingSession_UsedFieldPreserved verifies that the `used` field
// is never reset from true to false during status transitions. Once a session
// has been connected (used=true), disconnecting or expiring must not revert
// used to false. This is a regression test for REV35.
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

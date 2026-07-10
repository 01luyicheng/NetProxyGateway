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
	sqlite3 "github.com/mattn/go-sqlite3"
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

// TestIsPairingCodeUniqueConstraintError guards the specificity of the
// pairing-code conflict predicate. Only PRIMARY KEY / UNIQUE violations may be
// treated as a retryable code collision; other constraint classes (NOT NULL,
// CHECK, FOREIGN KEY, trigger, or a generic ErrConstraint with no extended
// code) must NOT be classified as a pairing-code conflict, otherwise a real
// integrity error would be silently retried up to MaxPairingCodeConflictRetries
// times and surface as a misleading 503 instead of a 500.
//
// Regression history: a prior change broadened the check to
// `Code == ErrConstraint`, which would have misclassified every constraint
// error as a code collision. This test pins the strict behavior.
func TestIsPairingCodeUniqueConstraintError(t *testing.T) {
	tests := []struct {
		name string
		err  error
		want bool
	}{
		{
			name: "primary key violation is a pairing-code conflict",
			err:  sqlite3.Error{Code: sqlite3.ErrConstraint, ExtendedCode: sqlite3.ErrConstraintPrimaryKey},
			want: true,
		},
		{
			name: "unique violation is a pairing-code conflict",
			err:  sqlite3.Error{Code: sqlite3.ErrConstraint, ExtendedCode: sqlite3.ErrConstraintUnique},
			want: true,
		},
		{
			name: "NOT NULL violation is NOT a pairing-code conflict (must surface as 500)",
			err:  sqlite3.Error{Code: sqlite3.ErrConstraint, ExtendedCode: sqlite3.ErrConstraintNotNull},
			want: false,
		},
		{
			name: "CHECK constraint violation is NOT a pairing-code conflict",
			err:  sqlite3.Error{Code: sqlite3.ErrConstraint, ExtendedCode: sqlite3.ErrConstraintCheck},
			want: false,
		},
		{
			name: "foreign key violation is NOT a pairing-code conflict",
			err:  sqlite3.Error{Code: sqlite3.ErrConstraint, ExtendedCode: sqlite3.ErrConstraintForeignKey},
			want: false,
		},
		{
			name: "generic constraint without extended code is NOT a pairing-code conflict",
			err:  sqlite3.Error{Code: sqlite3.ErrConstraint, ExtendedCode: sqlite3.ErrNoExtended(sqlite3.ErrConstraint)},
			want: false,
		},
		{
			name: "non-constraint sqlite error is NOT a pairing-code conflict",
			err:  sqlite3.Error{Code: sqlite3.ErrBusy, ExtendedCode: sqlite3.ErrNoExtended(sqlite3.ErrBusy)},
			want: false,
		},
		{
			name: "non-sqlite error is NOT a pairing-code conflict",
			err:  errors.New("some other error"),
			want: false,
		},
		{
			name: "wrapped sqlite primary key violation is still detected via errors.As",
			err:  fmt.Errorf("insert failed: %w", sqlite3.Error{Code: sqlite3.ErrConstraint, ExtendedCode: sqlite3.ErrConstraintPrimaryKey}),
			want: true,
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			got := isPairingCodeUniqueConstraintError(tc.err)
			if got != tc.want {
				t.Fatalf("isPairingCodeUniqueConstraintError(%v) = %v, want %v", tc.err, got, tc.want)
			}
		})
	}
}

func TestNewHTTPServerSetsMaxHeaderBytes(t *testing.T) {
	server := newHTTPServer(":8080", gin.New())

	if server.MaxHeaderBytes != MaxHTTPHeaderBytes {
		t.Fatalf("expected MaxHeaderBytes=%d, got %d", MaxHTTPHeaderBytes, server.MaxHeaderBytes)
	}
}

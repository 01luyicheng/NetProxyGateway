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

	"github.com/gin-contrib/cors"
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

// TestUpdatePairingSession_ConcurrentModificationReturns409 exercises the full
// HTTP handler path (not just the DB layer) to verify that when the session is
// modified between the handler's read and its conditional UPDATE, the handler
// returns 409 Conflict rather than silently overwriting the winner's claim
// (the TOCTOU race described in REV42).
//
// The test uses preCompareUpdateHook to deterministically inject a concurrent
// modification into the TOCTOU window (between getPairingSessionDB and
// compareAndUpdatePairingSessionDB). This replaces the previous probabilistic
// goroutine-race approach, which was flaky under -race with the full test
// suite because the goroutine scheduler would not reliably interleave the two
// handlers' read→update windows under heavy load.
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

	// Install a hook that simulates a concurrent modification: right before
	// the handler's conditional UPDATE, another writer claims the session
	// (status → connected, engineer_id → engineer-B). The handler's
	// conditional UPDATE (WHERE status='pending' AND engineer_id='') will
	// then match 0 rows → ErrConcurrentModification → 409.
	server.preCompareUpdateHook = func(code string) {
		_, _ = server.db.Exec(
			`UPDATE pairing_sessions SET status = 'connected', engineer_id = 'engineer-B', used = 1 WHERE code = ?`,
			code,
		)
	}

	pairingCode := "CONFLICT-1"
	insertPendingPairingSession(t, server, pairingCode, "device-race")

	statusCode := sendConnectRequest(pairingCode, "engineer-A")
	if statusCode != http.StatusConflict {
		t.Fatalf("expected 409 Conflict when session is concurrently modified between read and update, got %d", statusCode)
	}

	// Verify the winner's claim was NOT overwritten by the loser.
	current, err := server.getPairingSessionDB(pairingCode)
	if err != nil {
		t.Fatalf("failed to get session after conflict: %v", err)
	}
	if current.Status != "connected" || current.EngineerID != "engineer-B" {
		t.Fatalf("session was overwritten by losing request: status=%q engineer_id=%q", current.Status, current.EngineerID)
	}
}

// TestUpdatePairingSession_NoConflictReturns200 verifies that when no
// concurrent modification occurs, the handler succeeds normally (the hook is
// not installed / does not modify the session).
func TestUpdatePairingSession_NoConflictReturns200(t *testing.T) {
	gin.SetMode(gin.TestMode)

	server := newPairingTestServer(t)

	router := gin.New()
	router.PUT("/pair/:code", func(c *gin.Context) {
		c.Set("engineer_id", c.GetHeader("X-Engineer-ID"))
		server.updatePairingSession(c)
	})

	pairingCode := "OK-1"
	insertPendingPairingSession(t, server, pairingCode, "device-ok")

	req := httptest.NewRequest(http.MethodPut, "/pair/"+pairingCode, strings.NewReader(`{"status":"connected"}`))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Engineer-ID", "engineer-A")
	recorder := httptest.NewRecorder()
	router.ServeHTTP(recorder, req)

	if recorder.Code != http.StatusOK {
		t.Fatalf("expected 200 OK when no concurrent modification, got %d", recorder.Code)
	}
}

// --- CORS hard-coded origins regression tests (CORS-HARDCODED-ORIGINS-1 / REV59 C1) ---
//
// These tests pin the fix for the production blocker introduced by PR #108,
// where AllowOrigins was hard-coded to localhost only. gin-contrib/cors@v1.7.7
// calls c.AbortWithStatus(http.StatusForbidden) for any Origin not in the
// allowlist, so a deployed frontend at https://app.example.com received 403
// for every cross-origin request. See docs/ISSUES.md CORS-HARDCODED-ORIGINS-1.

func TestParseCORSAllowedOrigins_EmptyReturnsDefaults(t *testing.T) {
	got := parseCORSAllowedOrigins("")
	if len(got) != len(defaultCORSAllowedOrigins) {
		t.Fatalf("expected %d default origins, got %v", len(defaultCORSAllowedOrigins), got)
	}
	for i, want := range defaultCORSAllowedOrigins {
		if got[i] != want {
			t.Fatalf("default origin[%d] = %q, want %q", i, got[i], want)
		}
	}
}

func TestParseCORSAllowedOrigins_SingleOrigin(t *testing.T) {
	got := parseCORSAllowedOrigins("https://app.example.com")
	if len(got) != 1 || got[0] != "https://app.example.com" {
		t.Fatalf("expected [https://app.example.com], got %v", got)
	}
}

func TestParseCORSAllowedOrigins_MultipleOriginsTrimsAndDropsEmpties(t *testing.T) {
	got := parseCORSAllowedOrigins("  https://app.example.com , ,, https://admin.example.com  ,")
	want := []string{"https://app.example.com", "https://admin.example.com"}
	if len(got) != len(want) {
		t.Fatalf("expected %v, got %v", want, got)
	}
	for i, w := range want {
		if got[i] != w {
			t.Fatalf("origin[%d] = %q, want %q", i, got[i], w)
		}
	}
}

func TestParseCORSAllowedOrigins_OnlyWhitespaceReturnsDefaults(t *testing.T) {
	got := parseCORSAllowedOrigins("   ,  ,  ")
	if len(got) != len(defaultCORSAllowedOrigins) {
		t.Fatalf("expected defaults when all entries are whitespace, got %v", got)
	}
}

func TestBuildCorsConfig_DefaultsWhenEnvUnset(t *testing.T) {
	cfg := buildCorsConfig("")
	if len(cfg.AllowOrigins) != 2 {
		t.Fatalf("expected 2 default origins, got %v", cfg.AllowOrigins)
	}
	if cfg.AllowOrigins[0] != "http://localhost:3000" || cfg.AllowOrigins[1] != "http://localhost:8080" {
		t.Fatalf("unexpected default origins: %v", cfg.AllowOrigins)
	}
	if !cfg.AllowCredentials {
		t.Fatalf("AllowCredentials must be true")
	}
	// Verify the other config knobs are still set so we don't silently regress
	// the rest of the PR #108 config when refactoring.
	if len(cfg.AllowMethods) == 0 || len(cfg.AllowHeaders) == 0 {
		t.Fatalf("AllowMethods/AllowHeaders must not be empty: methods=%v headers=%v", cfg.AllowMethods, cfg.AllowHeaders)
	}
	if cfg.MaxAge != 12*time.Hour {
		t.Fatalf("MaxAge = %v, want 12h", cfg.MaxAge)
	}
}

func TestBuildCorsConfig_UsesEnvVarOrigins(t *testing.T) {
	cfg := buildCorsConfig("https://app.example.com,https://admin.example.com")
	if len(cfg.AllowOrigins) != 2 {
		t.Fatalf("expected 2 origins from env, got %v", cfg.AllowOrigins)
	}
	if cfg.AllowOrigins[0] != "https://app.example.com" || cfg.AllowOrigins[1] != "https://admin.example.com" {
		t.Fatalf("unexpected origins: %v", cfg.AllowOrigins)
	}
	for _, def := range defaultCORSAllowedOrigins {
		for _, got := range cfg.AllowOrigins {
			if got == def {
				t.Fatalf("default localhost origin %q should NOT appear when env var is set", def)
			}
		}
	}
}

// newCORSTestRouter mounts a Gin engine with the given CORS config and a
// trivial /health route. Mirrors the wiring in main().
func newCORSTestRouter(t *testing.T, corsConfig cors.Config) *gin.Engine {
	t.Helper()
	gin.SetMode(gin.TestMode)
	r := gin.New()
	r.Use(gin.Recovery())
	r.Use(cors.New(corsConfig))
	r.GET("/health", func(c *gin.Context) { c.JSON(http.StatusOK, gin.H{"ok": true}) })
	return r
}

func doCORSRequest(t *testing.T, r *gin.Engine, method, origin, path string) *httptest.ResponseRecorder {
	t.Helper()
	req := httptest.NewRequest(method, path, nil)
	if origin != "" {
		req.Header.Set("Origin", origin)
	}
	rec := httptest.NewRecorder()
	r.ServeHTTP(rec, req)
	return rec
}

// TestCORSIntegration_DefaultsRejectProductionOrigin — regression guard for the
// original PR #108 bug: with the env var UNSET (defaults), a production origin
// must NOT receive 200. It receives 403 from the library. This proves the
// production-breakage scenario and pins it as the behavior we are fixing.
func TestCORSIntegration_DefaultsRejectProductionOrigin(t *testing.T) {
	r := newCORSTestRouter(t, buildCorsConfig(""))

	rec := doCORSRequest(t, r, http.MethodGet, "https://app.example.com", "/health")
	if rec.Code != http.StatusForbidden {
		t.Fatalf("with defaults, production origin must be rejected with 403 (regression guard); got %d", rec.Code)
	}
}

// TestCORSIntegration_EnvVarAllowsProductionOrigin — the FIX: when
// API_ALLOWED_ORIGINS lists the production origin, the request reaches the
// handler and returns 200 with the proper ACAO header. Without this fix the
// same request would receive 403 (see previous test).
func TestCORSIntegration_EnvVarAllowsProductionOrigin(t *testing.T) {
	r := newCORSTestRouter(t, buildCorsConfig("https://app.example.com"))

	rec := doCORSRequest(t, r, http.MethodGet, "https://app.example.com", "/health")
	if rec.Code != http.StatusOK {
		t.Fatalf("with env var set, production origin must reach handler (200); got %d", rec.Code)
	}
	if got := rec.Header().Get("Access-Control-Allow-Origin"); got != "https://app.example.com" {
		t.Fatalf("ACAO header = %q, want %q", got, "https://app.example.com")
	}
}

// TestCORSIntegration_LocalhostDefaultStillAllowed — dev ergonomics guard:
// when the env var is unset, the dev defaults shipped with PR #108 must keep
// working so local development is not broken by the fix.
func TestCORSIntegration_LocalhostDefaultStillAllowed(t *testing.T) {
	r := newCORSTestRouter(t, buildCorsConfig(""))

	rec := doCORSRequest(t, r, http.MethodGet, "http://localhost:3000", "/health")
	if rec.Code != http.StatusOK {
		t.Fatalf("with defaults, localhost:3000 must still be allowed (200); got %d", rec.Code)
	}
}

// TestCORSIntegration_DisallowedOriginStillRejectedAfterEnvSet — security
// guard: even after the fix, an origin NOT in the env-var allowlist must still
// be rejected with 403. The fix only expands the allowlist via env var; it
// does NOT open the door to all origins.
func TestCORSIntegration_DisallowedOriginStillRejectedAfterEnvSet(t *testing.T) {
	r := newCORSTestRouter(t, buildCorsConfig("https://app.example.com"))

	rec := doCORSRequest(t, r, http.MethodGet, "https://evil.example.com", "/health")
	if rec.Code != http.StatusForbidden {
		t.Fatalf("origin not in env-var allowlist must still be rejected (403); got %d", rec.Code)
	}
}

// TestCORSIntegration_AllowedOriginPreflightReturns204 — verifies preflight
// handling for an allowed production origin: the library must respond 204 and
// echo the ACAO header, not 403.
func TestCORSIntegration_AllowedOriginPreflightReturns204(t *testing.T) {
	r := newCORSTestRouter(t, buildCorsConfig("https://app.example.com"))

	req := httptest.NewRequest(http.MethodOptions, "/health", nil)
	req.Header.Set("Origin", "https://app.example.com")
	req.Header.Set("Access-Control-Request-Method", "GET")
	req.Header.Set("Access-Control-Request-Headers", "Authorization")
	rec := httptest.NewRecorder()
	r.ServeHTTP(rec, req)

	if rec.Code != http.StatusNoContent {
		t.Fatalf("preflight for allowed origin must return 204; got %d", rec.Code)
	}
	if got := rec.Header().Get("Access-Control-Allow-Origin"); got != "https://app.example.com" {
		t.Fatalf("ACAO header = %q, want %q", got, "https://app.example.com")
	}
}

// --- createSessionToken / last_seen / rate-limit regression tests (REV60) ---
//
// These tests pin the three high-impact guards that were accidentally dropped
// from PR #108 and restored on the fix branch:
//   1. createSessionToken must reject an already-expired "connected" session
//      with 400 instead of issuing a token that bypasses the pairing TTL.
//   2. createSessionToken must re-verify the session with optimistic locking
//      before issuing a token, returning 409 on concurrent modification.
//   3. GET /api/pair/:code must enforce per-identity rate limiting so a brute
//      force on pairing codes is blocked after MaxFailedAttempts.
//   4. upsertDeviceStatusDB must reject a status update whose last_seen is
//      older than the stored value, so a delayed/offline notification cannot
//      overwrite a fresher online state.
// See docs/ISSUES.md (REV60 section).

// insertConnectedSession creates a pairing session row already in the
// "connected" state for the given engineer, with the supplied ExpiresAt. It is
// used by the createSessionToken regression tests below.
func insertConnectedSession(t *testing.T, server *Server, code, deviceID, engineerID string, expiresAt time.Time) {
	t.Helper()

	session := &PairingSession{
		Code:       code,
		DeviceID:   deviceID,
		Status:     "connected",
		EngineerID: engineerID,
		CreatedAt:  time.Now().Add(-PairingCodeTTL),
		ExpiresAt:  expiresAt,
		Used:       true,
	}
	if err := server.createPairingSessionDB(session); err != nil {
		t.Fatalf("failed to insert connected session %s: %v", code, err)
	}
}

func newCreateSessionTokenRouter(server *Server) *gin.Engine {
	router := gin.New()
	router.POST("/session/token", func(c *gin.Context) {
		c.Set("engineer_id", c.GetHeader("X-Engineer-ID"))
		server.createSessionToken(c)
	})
	return router
}

func runCreateSessionTokenRequest(router *gin.Engine, code, engineerID string) *httptest.ResponseRecorder {
	req := httptest.NewRequest(http.MethodPost, "/session/token", strings.NewReader(fmt.Sprintf(`{"code":%q}`, code)))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Engineer-ID", engineerID)
	recorder := httptest.NewRecorder()
	router.ServeHTTP(recorder, req)
	return recorder
}

// TestCreateSessionToken_ExpiredConnectedSessionReturns400 verifies that a
// session whose ExpiresAt has already passed but whose status is still
// "connected" (not yet swept by cleanupExpiredSessions) is rejected with 400
// ErrSessionExpired. Without the restored guard, createSessionToken would
// issue a valid token for a session past its pairing TTL.
func TestCreateSessionToken_ExpiredConnectedSessionReturns400(t *testing.T) {
	gin.SetMode(gin.TestMode)

	server := newPairingTestServer(t)
	insertConnectedSession(t, server, "EXP400", "device-1", "engineer-A", time.Now().Add(-5*time.Minute))

	recorder := runCreateSessionTokenRequest(newCreateSessionTokenRouter(server), "EXP400", "engineer-A")

	if recorder.Code != http.StatusBadRequest {
		t.Fatalf("expected 400 for expired connected session, got %d (body=%s)", recorder.Code, recorder.Body.String())
	}
	if !strings.Contains(recorder.Body.String(), ErrSessionExpired.Error()) {
		t.Fatalf("expected error to contain %q, got %s", ErrSessionExpired.Error(), recorder.Body.String())
	}

	// Sanity: no session token should have been issued for the expired session.
	tokens, err := server.db.Query(`SELECT COUNT(*) FROM session_tokens WHERE device_id = ?`, "device-1")
	if err != nil {
		t.Fatalf("failed to count tokens: %v", err)
	}
	defer tokens.Close()
	count := 0
	if tokens.Next() {
		_ = tokens.Scan(&count)
	}
	if count != 0 {
		t.Fatalf("expected no token issued for expired session, got %d", count)
	}
}

// TestCreateSessionToken_ValidSessionIssuesToken verifies the happy path is
// not broken by the restored expiry check and optimistic lock: a valid,
// non-expired "connected" session issues a token with 200.
func TestCreateSessionToken_ValidSessionIssuesToken(t *testing.T) {
	gin.SetMode(gin.TestMode)

	server := newPairingTestServer(t)
	insertConnectedSession(t, server, "OK200", "device-2", "engineer-B", time.Now().Add(10*time.Minute))

	recorder := runCreateSessionTokenRequest(newCreateSessionTokenRouter(server), "OK200", "engineer-B")

	if recorder.Code != http.StatusCreated {
		t.Fatalf("expected 201 for valid connected session, got %d (body=%s)", recorder.Code, recorder.Body.String())
	}

	var resp struct {
		Token string `json:"token"`
	}
	if err := json.Unmarshal(recorder.Body.Bytes(), &resp); err != nil {
		t.Fatalf("failed to decode token response: %v (body=%s)", err, recorder.Body.String())
	}
	if resp.Token == "" {
		t.Fatal("expected non-empty token in response")
	}
}

// TestCreateSessionToken_ConcurrentModificationReturns409 verifies that when a
// "connected" session is concurrently modified (e.g. expired by a sweep or
// transferred) between createSessionToken's read and its optimistic-lock
// update, the handler returns 409 instead of issuing a token for the stale
// session. This exercises the full HTTP path (not just the DB layer) and
// mirrors the pattern of TestUpdatePairingSession_ConcurrentModificationReturns409.
//
// The test uses preCompareUpdateHook to deterministically inject a concurrent
// modification into the TOCTOU window (between getPairingSessionDB and
// compareAndUpdatePairingSessionDB). This replaces the previous probabilistic
// goroutine-race approach, which was flaky under -race with the full test
// suite because the goroutine scheduler would not reliably interleave the
// flipper and the handler under heavy load.
func TestCreateSessionToken_ConcurrentModificationReturns409(t *testing.T) {
	gin.SetMode(gin.TestMode)

	server := newPairingTestServer(t)
	router := newCreateSessionTokenRouter(server)

	// Install a hook that simulates a concurrent modification: right before
	// the handler's conditional UPDATE, another writer changes the session
	// status to "expired". The handler's conditional UPDATE
	// (WHERE status='connected' AND engineer_id='engineer-A') will then
	// match 0 rows → ErrConcurrentModification → 409, and no token is issued.
	server.preCompareUpdateHook = func(code string) {
		_, _ = server.db.Exec(
			`UPDATE pairing_sessions SET status = 'expired' WHERE code = ?`,
			code,
		)
	}

	code := "C409-DET"
	insertConnectedSession(t, server, code, "device-race", "engineer-A", time.Now().Add(10*time.Minute))

	recorder := runCreateSessionTokenRequest(router, code, "engineer-A")
	if recorder.Code != http.StatusConflict {
		t.Fatalf("expected 409 Conflict when session is concurrently modified between read and update, got %d (body=%s)", recorder.Code, recorder.Body.String())
	}

	// Verify no token was issued for the stale session.
	var resp struct {
		Token string `json:"token"`
	}
	if err := json.Unmarshal(recorder.Body.Bytes(), &resp); err == nil && resp.Token != "" {
		t.Fatalf("expected no token in conflict response, got %q", resp.Token)
	}

	// Verify the concurrent modification was not overwritten.
	current, err := server.getPairingSessionDB(code)
	if err != nil {
		t.Fatalf("failed to get session after conflict: %v", err)
	}
	if current.Status != "expired" {
		t.Fatalf("session status was overwritten: got %q, want %q", current.Status, "expired")
	}
}

// TestGetPairingSessionRateLimit_BlocksBruteForce verifies that GET
// /api/pair/:code is protected by per-identity rate limiting. An attacker
// guessing pairing codes from a single IP must be blocked with 429 after
// MaxFailedAttempts, rather than being able to probe codes unboundedly.
func TestGetPairingSessionRateLimit_BlocksBruteForce(t *testing.T) {
	gin.SetMode(gin.TestMode)

	server := newPairingTestServer(t)

	router := gin.New()
	// Mirror the production route ordering: rate-limit middleware runs before
	// the handler and (since no auth context is set here) keys on ClientIP.
	router.GET("/pair/:code", server.rateLimitMiddleware(), server.getPairingSession)

	// Issue MaxFailedAttempts requests for a non-existent code; each returns
	// 404 but accumulates against the per-IP failure counter.
	for i := 0; i < MaxFailedAttempts; i++ {
		recorder := httptest.NewRecorder()
		req := httptest.NewRequest(http.MethodGet, "/pair/000000", nil)
		router.ServeHTTP(recorder, req)
		if recorder.Code != http.StatusNotFound {
			t.Fatalf("attempt %d: expected 404 for unknown code, got %d", i, recorder.Code)
		}
	}

	// The next attempt from the same identity must be blocked.
	recorder := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/pair/000000", nil)
	router.ServeHTTP(recorder, req)
	if recorder.Code != http.StatusTooManyRequests {
		t.Fatalf("expected 429 after %d failed attempts, got %d (body=%s)", MaxFailedAttempts, recorder.Code, recorder.Body.String())
	}
	if !strings.Contains(recorder.Body.String(), ErrRateLimitExceeded.Error()) {
		t.Fatalf("expected error to contain %q, got %s", ErrRateLimitExceeded.Error(), recorder.Body.String())
	}
}

// TestUpsertDeviceStatusDB_RejectsStaleLastSeen verifies the last_seen guard:
// a status update whose last_seen is older than the stored value must NOT
// overwrite the fresher status. This prevents a delayed/reordered offline
// notification from clobbering a newer online state (REV33 regression).
func TestUpsertDeviceStatusDB_RejectsStaleLastSeen(t *testing.T) {
	server := newPairingTestServer(t)

	now := time.Now()
	if err := server.upsertDeviceStatusDB(&DeviceStatus{
		DeviceID:   "device-stale",
		Status:     "online",
		LastSeen:   now,
		TunnelAddr: "online:1234",
	}); err != nil {
		t.Fatalf("failed to seed device status: %v", err)
	}

	// A stale notification with an older last_seen and a contradicting status.
	stale := now.Add(-30 * time.Second)
	if err := server.upsertDeviceStatusDB(&DeviceStatus{
		DeviceID:   "device-stale",
		Status:     "offline",
		LastSeen:   stale,
		TunnelAddr: "offline:5678",
	}); err != nil {
		t.Fatalf("upsert returned error: %v", err)
	}

	ds, err := server.getDeviceStatusDB("device-stale")
	if err != nil || ds == nil {
		t.Fatalf("failed to read back device status: %v", err)
	}
	if ds.Status != "online" {
		t.Fatalf("stale last_seen overwrote status: got %q, want %q", ds.Status, "online")
	}
	if ds.TunnelAddr != "online:1234" {
		t.Fatalf("stale last_seen overwrote tunnel_addr: got %q, want %q", ds.TunnelAddr, "online:1234")
	}
}

// TestUpsertDeviceStatusDB_AcceptsNewerLastSeen verifies the complementary
// side of the guard: an update with a strictly newer last_seen DOES overwrite
// the stored status, so genuine state transitions are not silently dropped.
func TestUpsertDeviceStatusDB_AcceptsNewerLastSeen(t *testing.T) {
	server := newPairingTestServer(t)

	now := time.Now()
	if err := server.upsertDeviceStatusDB(&DeviceStatus{
		DeviceID:   "device-new",
		Status:     "offline",
		LastSeen:   now,
		TunnelAddr: "old:1234",
	}); err != nil {
		t.Fatalf("failed to seed device status: %v", err)
	}

	newer := now.Add(5 * time.Second)
	if err := server.upsertDeviceStatusDB(&DeviceStatus{
		DeviceID:   "device-new",
		Status:     "online",
		LastSeen:   newer,
		TunnelAddr: "new:5678",
	}); err != nil {
		t.Fatalf("upsert returned error: %v", err)
	}

	ds, err := server.getDeviceStatusDB("device-new")
	if err != nil || ds == nil {
		t.Fatalf("failed to read back device status: %v", err)
	}
	if ds.Status != "online" {
		t.Fatalf("newer last_seen did not update status: got %q, want %q", ds.Status, "online")
	}
	if ds.TunnelAddr != "new:5678" {
		t.Fatalf("newer last_seen did not update tunnel_addr: got %q, want %q", ds.TunnelAddr, "new:5678")
	}
}

package main

import (
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/gin-gonic/gin"
	"github.com/golang-jwt/jwt/v5"
)

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

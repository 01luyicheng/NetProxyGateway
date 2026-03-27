package main

import (
	"crypto/rand"
	"crypto/subtle"
	"encoding/binary"
	"fmt"
	"log"
	"net/http"
	"os"
	"strings"
	"sync"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/golang-jwt/jwt/v5"
)

// 常量定义
const (
	PairingCodeTTL    = 15 * time.Minute
	SessionTokenTTL   = 15 * time.Minute
	MaxFailedAttempts = 5
	BlockDuration     = 15 * time.Minute
)

// PairingSession 配对会话
type PairingSession struct {
	Code       string    `json:"code"`
	DeviceID   string    `json:"device_id"`
	Status     string    `json:"status"`
	EngineerID string    `json:"engineer_id,omitempty"`
	CreatedAt  time.Time `json:"created_at"`
	ExpiresAt  time.Time `json:"expires_at"`
	Used       bool      `json:"used"`
}

// SessionToken 会话令牌
type SessionToken struct {
	Token     string    `json:"token"`
	DeviceID  string    `json:"device_id"`
	EngineerID string   `json:"engineer_id"`
	CreatedAt time.Time `json:"created_at"`
	ExpiresAt time.Time `json:"expires_at"`
}

// DeviceStatus 设备状态
type DeviceStatus struct {
	DeviceID   string    `json:"device_id"`
	Status     string    `json:"status"`
	LastSeen   time.Time `json:"last_seen"`
	TunnelAddr string    `json:"tunnel_addr,omitempty"`
}

// LoginAttempt 登录尝试记录
type LoginAttempt struct {
	Count      int
	LastTry    time.Time
	Blocked    bool
	BlockUntil time.Time
}

// Server 服务器结构
type Server struct {
	sessions      map[string]*PairingSession  // code -> session
	sessionTokens map[string]*SessionToken    // token -> session
	deviceStatus  map[string]*DeviceStatus    // device_id -> status
	loginAttempts map[string]*LoginAttempt    // ip -> attempts
	
	sessionsMu      sync.RWMutex
	sessionTokensMu sync.RWMutex
	deviceStatusMu  sync.RWMutex
	loginAttemptsMu sync.RWMutex
	
	jwtSecret []byte
}

// NewServer 创建新服务器
func NewServer() *Server {
	jwtSecret := os.Getenv("JWT_SECRET")
	if jwtSecret == "" {
		// 生成随机密钥（仅用于开发）
		jwtSecret = generateRandomString(32)
	}
	
	return &Server{
		sessions:      make(map[string]*PairingSession),
		sessionTokens: make(map[string]*SessionToken),
		deviceStatus:  make(map[string]*DeviceStatus),
		loginAttempts: make(map[string]*LoginAttempt),
		jwtSecret:     []byte(jwtSecret),
	}
}

// generateCode 生成6位配对码 (使用拒绝采样避免模运算偏斜)
func generateCode() (string, error) {
	const maxCode = 1000000
	// uint32 最大值为 4294967295
	// 计算 4294967296 % 1000000 = 7296
	// 因此需要拒绝 0-7295 范围内的值以确保均匀分布
	const maxUint32 = 1 << 32
	const threshold = maxUint32 - (maxUint32 % maxCode)

	for {
		b := make([]byte, 4)
		if _, err := rand.Read(b); err != nil {
			return "", err
		}
		n := binary.BigEndian.Uint32(b)
		// 拒绝采样：如果 n >= threshold，则重新采样
		if n < threshold {
			code := int(n % maxCode)
			return fmt.Sprintf("%06d", code), nil
		}
	}
}

// generateRandomString 生成随机字符串
func generateRandomString(length int) string {
	const charset = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
	b := make([]byte, length)
	rand.Read(b)
	for i := range b {
		b[i] = charset[int(b[i])%len(charset)]
	}
	return string(b)
}

// generateSessionToken 生成会话令牌
func generateSessionToken() string {
	return generateRandomString(32)
}

// checkRateLimit 检查限流
func (s *Server) checkRateLimit(clientIP string) bool {
	s.loginAttemptsMu.Lock()
	defer s.loginAttemptsMu.Unlock()
	
	attempt, ok := s.loginAttempts[clientIP]
	if !ok {
		s.loginAttempts[clientIP] = &LoginAttempt{
			Count:   1,
			LastTry: time.Now(),
		}
		return true
	}
	
	// 检查是否被封禁
	if attempt.Blocked && time.Now().Before(attempt.BlockUntil) {
		return false
	}
	
	// 重置封禁状态
	if attempt.Blocked && time.Now().After(attempt.BlockUntil) {
		attempt.Blocked = false
		attempt.Count = 0
	}
	
	// 检查是否需要封禁
	if attempt.Count >= MaxFailedAttempts && time.Since(attempt.LastTry) < 5*time.Minute {
		attempt.Blocked = true
		attempt.BlockUntil = time.Now().Add(BlockDuration)
		return false
	}
	
	// 重置计数（如果超过5分钟）
	if time.Since(attempt.LastTry) > 5*time.Minute {
		attempt.Count = 0
	}
	
	attempt.Count++
	attempt.LastTry = time.Now()
	return true
}

// recordSuccess 记录成功
func (s *Server) recordSuccess(clientIP string) {
	s.loginAttemptsMu.Lock()
	delete(s.loginAttempts, clientIP)
	s.loginAttemptsMu.Unlock()
}

// cleanupExpiredSessions 清理过期会话
func (s *Server) cleanupExpiredSessions() {
	ticker := time.NewTicker(5 * time.Minute)
	defer ticker.Stop()
	
	for range ticker.C {
		now := time.Now()
		
		// 清理配对会话
		s.sessionsMu.Lock()
		for code, session := range s.sessions {
			if now.After(session.ExpiresAt) || (session.Used && session.Status == "connected") {
				delete(s.sessions, code)
			}
		}
		s.sessionsMu.Unlock()
		
		// 清理会话令牌
		s.sessionTokensMu.Lock()
		for token, st := range s.sessionTokens {
			if now.After(st.ExpiresAt) {
				delete(s.sessionTokens, token)
			}
		}
		s.sessionTokensMu.Unlock()
	}
}

// authMiddleware JWT认证中间件
func (s *Server) authMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		auth := c.GetHeader("Authorization")
		if len(auth) < 8 || !strings.HasPrefix(auth, "Bearer ") {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "missing or invalid bearer token"})
			return
		}
		
		tokenString := auth[7:]
		token, err := jwt.Parse(tokenString, func(token *jwt.Token) (interface{}, error) {
			if _, ok := token.Method.(*jwt.SigningMethodHMAC); !ok {
				return nil, fmt.Errorf("unexpected signing method: %v", token.Header["alg"])
			}
			return s.jwtSecret, nil
		})
		
		if err != nil || !token.Valid {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "invalid token"})
			return
		}
		
		if claims, ok := token.Claims.(jwt.MapClaims); ok {
			c.Set("engineer_id", claims["sub"])
			c.Set("role", claims["role"])
		}
		
		c.Next()
	}
}

// createPairingSession 创建配对会话
func (s *Server) createPairingSession(c *gin.Context) {
	clientIP := c.ClientIP()
	
	// 检查限流
	if !s.checkRateLimit(clientIP) {
		c.JSON(http.StatusTooManyRequests, gin.H{"error": "rate limit exceeded, please try again later"})
		return
	}
	
	var req struct {
		DeviceID string `json:"device_id" binding:"required"`
	}
	
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	
	// 生成配对码
	code, err := generateCode()
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to generate pairing code"})
		return
	}
	
	// 检查配对码是否已存在
	s.sessionsMu.Lock()
	for {
		if _, exists := s.sessions[code]; !exists {
			break
		}
		code, _ = generateCode()
	}
	
	session := &PairingSession{
		Code:      code,
		DeviceID:  req.DeviceID,
		Status:    "pending",
		CreatedAt: time.Now(),
		ExpiresAt: time.Now().Add(PairingCodeTTL),
		Used:      false,
	}
	
	s.sessions[code] = session
	s.sessionsMu.Unlock()
	
	// 记录成功
	s.recordSuccess(clientIP)
	
	c.JSON(http.StatusCreated, session)
}

// getPairingSession 获取配对会话
func (s *Server) getPairingSession(c *gin.Context) {
	code := c.Param("code")
	
	s.sessionsMu.RLock()
	session, ok := s.sessions[code]
	s.sessionsMu.RUnlock()
	
	if !ok {
		c.JSON(http.StatusNotFound, gin.H{"error": "session not found"})
		return
	}
	
	// 检查是否过期
	if time.Now().After(session.ExpiresAt) {
		s.sessionsMu.Lock()
		session.Status = "expired"
		s.sessionsMu.Unlock()
		c.JSON(http.StatusGone, gin.H{"error": "session expired"})
		return
	}
	
	c.JSON(http.StatusOK, session)
}

// updatePairingSession 更新配对会话
func (s *Server) updatePairingSession(c *gin.Context) {
	code := c.Param("code")
	
	var req struct {
		Status     string `json:"status" binding:"required"`
		EngineerID string `json:"engineer_id"`
	}
	
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	
	s.sessionsMu.Lock()
	defer s.sessionsMu.Unlock()
	
	session, ok := s.sessions[code]
	if !ok {
		c.JSON(http.StatusNotFound, gin.H{"error": "session not found"})
		return
	}
	
	// 检查是否过期
	if time.Now().After(session.ExpiresAt) {
		session.Status = "expired"
		c.JSON(http.StatusGone, gin.H{"error": "session expired"})
		return
	}
	
	// 检查状态转换
	validTransitions := map[string][]string{
		"pending":     {"connected", "expired"},
		"connected":   {"disconnected", "expired"},
		"disconnected": {},
		"expired":     {},
	}
	
	valid := false
	for _, t := range validTransitions[session.Status] {
		if t == req.Status {
			valid = true
			break
		}
	}
	
	if !valid {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid status transition"})
		return
	}
	
	session.Status = req.Status
	if req.EngineerID != "" {
		session.EngineerID = req.EngineerID
	}
	
	// 如果连接成功，标记为已使用
	if req.Status == "connected" {
		session.Used = true
	}
	
	c.JSON(http.StatusOK, session)
}

// validateSession 验证会话令牌（内部API，供SOCKS5服务调用）
func (s *Server) validateSession(c *gin.Context) {
	var req struct {
		DeviceID string `json:"device_id" binding:"required"`
		Token    string `json:"token" binding:"required"`
	}
	
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	
	s.sessionTokensMu.RLock()
	sessionToken, ok := s.sessionTokens[req.Token]
	s.sessionTokensMu.RUnlock()
	
	if !ok {
		c.JSON(http.StatusOK, gin.H{"valid": false})
		return
	}
	
	// 检查是否过期
	if time.Now().After(sessionToken.ExpiresAt) {
		s.sessionTokensMu.Lock()
		delete(s.sessionTokens, req.Token)
		s.sessionTokensMu.Unlock()
		c.JSON(http.StatusOK, gin.H{"valid": false})
		return
	}
	
	// 验证deviceID匹配
	valid := subtle.ConstantTimeCompare([]byte(sessionToken.DeviceID), []byte(req.DeviceID)) == 1
	
	c.JSON(http.StatusOK, gin.H{"valid": valid})
}

// createSessionToken 创建会话令牌
func (s *Server) createSessionToken(c *gin.Context) {
	var req struct {
		Code       string `json:"code" binding:"required"`
		EngineerID string `json:"engineer_id" binding:"required"`
	}
	
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	
	s.sessionsMu.RLock()
	session, ok := s.sessions[req.Code]
	s.sessionsMu.RUnlock()
	
	if !ok {
		c.JSON(http.StatusNotFound, gin.H{"error": "pairing session not found"})
		return
	}
	
	if session.Status != "connected" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "pairing not completed"})
		return
	}
	
	if session.EngineerID != req.EngineerID {
		c.JSON(http.StatusForbidden, gin.H{"error": "unauthorized"})
		return
	}
	
	// 创建会话令牌
	token := generateSessionToken()
	sessionToken := &SessionToken{
		Token:      token,
		DeviceID:   session.DeviceID,
		EngineerID: req.EngineerID,
		CreatedAt:  time.Now(),
		ExpiresAt:  time.Now().Add(SessionTokenTTL),
	}
	
	s.sessionTokensMu.Lock()
	s.sessionTokens[token] = sessionToken
	s.sessionTokensMu.Unlock()
	
	c.JSON(http.StatusCreated, sessionToken)
}

// getDeviceStatus 获取设备状态
func (s *Server) getDeviceStatus(c *gin.Context) {
	deviceID := c.Param("id")
	
	s.deviceStatusMu.RLock()
	status, ok := s.deviceStatus[deviceID]
	s.deviceStatusMu.RUnlock()
	
	if !ok {
		// 返回离线状态
		c.JSON(http.StatusOK, DeviceStatus{
			DeviceID: deviceID,
			Status:   "offline",
			LastSeen: time.Time{},
		})
		return
	}
	
	c.JSON(http.StatusOK, status)
}

// updateDeviceStatus 更新设备状态（内部API）
func (s *Server) updateDeviceStatus(c *gin.Context) {
	var req struct {
		DeviceID   string `json:"device_id" binding:"required"`
		Status     string `json:"status" binding:"required"`
		TunnelAddr string `json:"tunnel_addr"`
	}
	
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	
	s.deviceStatusMu.Lock()
	s.deviceStatus[req.DeviceID] = &DeviceStatus{
		DeviceID:   req.DeviceID,
		Status:     req.Status,
		LastSeen:   time.Now(),
		TunnelAddr: req.TunnelAddr,
	}
	s.deviceStatusMu.Unlock()
	
	c.JSON(http.StatusOK, gin.H{"status": "updated"})
}

// login 工程师登录
func (s *Server) login(c *gin.Context) {
	clientIP := c.ClientIP()
	
	// 检查限流
	if !s.checkRateLimit(clientIP) {
		c.JSON(http.StatusTooManyRequests, gin.H{"error": "rate limit exceeded"})
		return
	}
	
	var req struct {
		Username string `json:"username" binding:"required"`
		Password string `json:"password" binding:"required"`
	}
	
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	
	// 简单的凭证验证（生产环境应该使用数据库）
	adminUser := os.Getenv("ADMIN_USER")
	adminPass := os.Getenv("ADMIN_PASS")
	if adminUser == "" {
		adminUser = "admin"
	}
	if adminPass == "" {
		adminPass = "admin" // 仅用于开发
	}
	
	if req.Username != adminUser || req.Password != adminPass {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "invalid credentials"})
		return
	}
	
	// 生成JWT
	token := jwt.NewWithClaims(jwt.SigningMethodHS256, jwt.MapClaims{
		"sub":  req.Username,
		"role": "engineer",
		"iat":  time.Now().Unix(),
		"exp":  time.Now().Add(24 * time.Hour).Unix(),
	})
	
	tokenString, err := token.SignedString(s.jwtSecret)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to generate token"})
		return
	}
	
	// 记录成功
	s.recordSuccess(clientIP)
	
	c.JSON(http.StatusOK, gin.H{
		"token": tokenString,
		"type":  "Bearer",
	})
}

// healthCheck 健康检查
func (s *Server) healthCheck(c *gin.Context) {
	c.JSON(http.StatusOK, gin.H{
		"status": "ok",
		"time":   time.Now().UTC(),
	})
}

func main() {
	// 设置Gin模式
	gin.SetMode(gin.ReleaseMode)
	
	server := NewServer()
	
	// 启动清理协程
	go server.cleanupExpiredSessions()
	
	r := gin.New()
	r.Use(gin.Recovery())
	r.Use(gin.Logger())
	
	// 健康检查（公开）
	r.GET("/health", server.healthCheck)
	
	// 登录（公开）
	r.POST("/api/login", server.login)
	
	// API路由组
	api := r.Group("/api")
	{
		// 配对会话管理
		api.POST("/pair", server.authMiddleware(), server.createPairingSession)
		api.GET("/pair/:code", server.getPairingSession)
		api.PUT("/pair/:code", server.updatePairingSession)
		
		// 会话令牌
		api.POST("/session/token", server.authMiddleware(), server.createSessionToken)
		api.POST("/session/validate", server.validateSession)
		
		// 设备状态
		api.GET("/device/:id/status", server.authMiddleware(), server.getDeviceStatus)
		api.POST("/device/status", server.authMiddleware(), server.updateDeviceStatus)
	}
	
	port := os.Getenv("PORT")
	if port == "" {
		port = "8080"
	}

	// 读取TLS配置（环境变量优先）
	enableTLS := os.Getenv("ENABLE_TLS") == "true"
	tlsCert := os.Getenv("TLS_CERT")
	tlsKey := os.Getenv("TLS_KEY")

	// 验证TLS配置
	if enableTLS {
		if tlsCert == "" || tlsKey == "" {
			log.Fatalf("TLS enabled but certificate paths not provided")
		}
		if _, err := os.Stat(tlsCert); os.IsNotExist(err) {
			log.Fatalf("TLS certificate file not found: %s", tlsCert)
		}
		if _, err := os.Stat(tlsKey); os.IsNotExist(err) {
			log.Fatalf("TLS key file not found: %s", tlsKey)
		}
		log.Printf("API server starting on :%s (TLS enabled)", port)
	} else {
		log.Printf("API server starting on :%s (TLS disabled)", port)
	}

	if enableTLS {
		if err := r.RunTLS(":"+port, tlsCert, tlsKey); err != nil {
			log.Fatalf("Server failed: %v", err)
		}
	} else {
		if err := r.Run(":" + port); err != nil {
			log.Fatalf("Server failed: %v", err)
		}
	}
}

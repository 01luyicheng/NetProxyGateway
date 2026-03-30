package main

import (
	"crypto/rand"
	"crypto/subtle"
	"database/sql"
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
	_ "github.com/mattn/go-sqlite3"
)

// 常量定义
const (
	PairingCodeTTL    = 15 * time.Minute
	SessionTokenTTL   = 15 * time.Minute
	MaxFailedAttempts = 5
	BlockDuration     = 15 * time.Minute
	DefaultDBPath     = "./api.db"
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
	Token      string    `json:"token"`
	DeviceID   string    `json:"device_id"`
	EngineerID string    `json:"engineer_id"`
	CreatedAt  time.Time `json:"created_at"`
	ExpiresAt  time.Time `json:"expires_at"`
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
	db *sql.DB

	// 内存缓存（用于登录限流，不持久化）
	loginAttempts   map[string]*LoginAttempt // ip -> attempts
	loginAttemptsMu sync.RWMutex

	jwtSecret      []byte
	internalAPIKey []byte
}

// NewServer 创建新服务器
func NewServer() (*Server, error) {
	jwtSecret := os.Getenv("JWT_SECRET")
	if jwtSecret == "" {
		log.Fatalf("FATAL: JWT_SECRET environment variable is not set. Please set a secure JWT secret before starting the server.")
	}

	// 检查管理员凭据是否配置
	adminUser := os.Getenv("ADMIN_USER")
	adminPass := os.Getenv("ADMIN_PASS")
	if adminUser == "" || adminPass == "" {
		log.Fatalf("FATAL: ADMIN_USER and ADMIN_PASS environment variables must be set. Please configure admin credentials before starting the server.")
	}

	// 获取数据库路径
	dbPath := os.Getenv("DB_PATH")
	if dbPath == "" {
		dbPath = DefaultDBPath
	}

	// 打开数据库连接
	db, err := sql.Open("sqlite3", dbPath+"?_journal_mode=WAL&_busy_timeout=5000")
	if err != nil {
		return nil, fmt.Errorf("failed to open database: %w", err)
	}

	// 验证数据库连接
	if err := db.Ping(); err != nil {
		return nil, fmt.Errorf("failed to ping database: %w", err)
	}

	// 创建表结构
	if err := initSchema(db); err != nil {
		return nil, fmt.Errorf("failed to init schema: %w", err)
	}

	return &Server{
		db:              db,
		loginAttempts:   make(map[string]*LoginAttempt),
		jwtSecret:       []byte(jwtSecret),
		internalAPIKey:  []byte(os.Getenv("INTERNAL_API_KEY")),
	}, nil
}

// initSchema 初始化数据库表结构
func initSchema(db *sql.DB) error {
	schema := `
	CREATE TABLE IF NOT EXISTS pairing_sessions (
		code TEXT PRIMARY KEY,
		device_id TEXT NOT NULL,
		status TEXT NOT NULL DEFAULT 'pending',
		engineer_id TEXT,
		created_at INTEGER NOT NULL,
		expires_at INTEGER NOT NULL,
		used INTEGER NOT NULL DEFAULT 0
	);

	CREATE INDEX IF NOT EXISTS idx_pairing_device_id ON pairing_sessions(device_id);
	CREATE INDEX IF NOT EXISTS idx_pairing_expires_at ON pairing_sessions(expires_at);

	CREATE TABLE IF NOT EXISTS session_tokens (
		token TEXT PRIMARY KEY,
		device_id TEXT NOT NULL,
		engineer_id TEXT NOT NULL,
		created_at INTEGER NOT NULL,
		expires_at INTEGER NOT NULL
	);

	CREATE INDEX IF NOT EXISTS idx_token_device_id ON session_tokens(device_id);
	CREATE INDEX IF NOT EXISTS idx_token_expires_at ON session_tokens(expires_at);

	CREATE TABLE IF NOT EXISTS device_status (
		device_id TEXT PRIMARY KEY,
		status TEXT NOT NULL,
		last_seen INTEGER NOT NULL,
		tunnel_addr TEXT
	);

	CREATE INDEX IF NOT EXISTS idx_device_status ON device_status(status);
	`

	_, err := db.Exec(schema)
	return err
}

// Close 关闭服务器资源
func (s *Server) Close() error {
	if s.db != nil {
		return s.db.Close()
	}
	return nil
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
		now := time.Now().Unix()

		// 清理过期配对会话
		_, err := s.db.Exec("DELETE FROM pairing_sessions WHERE expires_at < ?", now)
		if err != nil {
			log.Printf("Failed to cleanup expired pairing sessions: %v", err)
		}

		// 清理过期会话令牌
		_, err = s.db.Exec("DELETE FROM session_tokens WHERE expires_at < ?", now)
		if err != nil {
			log.Printf("Failed to cleanup expired session tokens: %v", err)
		}
	}
}

// ==================== 数据库操作方法 ====================

// createPairingSessionDB 创建配对会话到数据库
func (s *Server) createPairingSessionDB(session *PairingSession) error {
	_, err := s.db.Exec(
		`INSERT INTO pairing_sessions (code, device_id, status, engineer_id, created_at, expires_at, used)
		 VALUES (?, ?, ?, ?, ?, ?, ?)`,
		session.Code,
		session.DeviceID,
		session.Status,
		session.EngineerID,
		session.CreatedAt.Unix(),
		session.ExpiresAt.Unix(),
		boolToInt(session.Used),
	)
	return err
}

// getPairingSessionDB 从数据库获取配对会话
func (s *Server) getPairingSessionDB(code string) (*PairingSession, error) {
	var session PairingSession
	var createdAt, expiresAt int64
	var used int

	err := s.db.QueryRow(
		`SELECT code, device_id, status, engineer_id, created_at, expires_at, used
		 FROM pairing_sessions WHERE code = ?`,
		code,
	).Scan(
		&session.Code,
		&session.DeviceID,
		&session.Status,
		&session.EngineerID,
		&createdAt,
		&expiresAt,
		&used,
	)

	if err == sql.ErrNoRows {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}

	session.CreatedAt = time.Unix(createdAt, 0)
	session.ExpiresAt = time.Unix(expiresAt, 0)
	session.Used = used == 1

	return &session, nil
}

// updatePairingSessionDB 更新配对会话到数据库
func (s *Server) updatePairingSessionDB(session *PairingSession) error {
	_, err := s.db.Exec(
		`UPDATE pairing_sessions SET status = ?, engineer_id = ?, used = ? WHERE code = ?`,
		session.Status,
		session.EngineerID,
		boolToInt(session.Used),
		session.Code,
	)
	return err
}

// createSessionTokenDB 创建会话令牌到数据库
func (s *Server) createSessionTokenDB(token *SessionToken) error {
	_, err := s.db.Exec(
		`INSERT INTO session_tokens (token, device_id, engineer_id, created_at, expires_at)
		 VALUES (?, ?, ?, ?, ?)`,
		token.Token,
		token.DeviceID,
		token.EngineerID,
		token.CreatedAt.Unix(),
		token.ExpiresAt.Unix(),
	)
	return err
}

// getSessionTokenDB 从数据库获取会话令牌
func (s *Server) getSessionTokenDB(token string) (*SessionToken, error) {
	var st SessionToken
	var createdAt, expiresAt int64

	err := s.db.QueryRow(
		`SELECT token, device_id, engineer_id, created_at, expires_at
		 FROM session_tokens WHERE token = ?`,
		token,
	).Scan(
		&st.Token,
		&st.DeviceID,
		&st.EngineerID,
		&createdAt,
		&expiresAt,
	)

	if err == sql.ErrNoRows {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}

	st.CreatedAt = time.Unix(createdAt, 0)
	st.ExpiresAt = time.Unix(expiresAt, 0)

	return &st, nil
}

// deleteSessionTokenDB 从数据库删除会话令牌
func (s *Server) deleteSessionTokenDB(token string) error {
	_, err := s.db.Exec("DELETE FROM session_tokens WHERE token = ?", token)
	return err
}

// getDeviceStatusDB 从数据库获取设备状态
func (s *Server) getDeviceStatusDB(deviceID string) (*DeviceStatus, error) {
	var ds DeviceStatus
	var lastSeen int64

	err := s.db.QueryRow(
		`SELECT device_id, status, last_seen, tunnel_addr
		 FROM device_status WHERE device_id = ?`,
		deviceID,
	).Scan(
		&ds.DeviceID,
		&ds.Status,
		&lastSeen,
		&ds.TunnelAddr,
	)

	if err == sql.ErrNoRows {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}

	ds.LastSeen = time.Unix(lastSeen, 0)

	return &ds, nil
}

// upsertDeviceStatusDB 插入或更新设备状态到数据库
func (s *Server) upsertDeviceStatusDB(status *DeviceStatus) error {
	_, err := s.db.Exec(
		`INSERT INTO device_status (device_id, status, last_seen, tunnel_addr)
		 VALUES (?, ?, ?, ?)
		 ON CONFLICT(device_id) DO UPDATE SET
		 status = excluded.status,
		 last_seen = excluded.last_seen,
		 tunnel_addr = excluded.tunnel_addr`,
		status.DeviceID,
		status.Status,
		status.LastSeen.Unix(),
		status.TunnelAddr,
	)
	return err
}

// boolToInt 将 bool 转换为 int (0/1)
func boolToInt(b bool) int {
	if b {
		return 1
	}
	return 0
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

func (s *Server) internalOrUserAuthMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		internalKey := c.GetHeader("X-Internal-API-Key")
		if len(s.internalAPIKey) > 0 && internalKey != "" {
			if subtle.ConstantTimeCompare([]byte(internalKey), s.internalAPIKey) == 1 {
				c.Set("role", "internal")
				c.Next()
				return
			}

			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "invalid internal api key"})
			return
		}

		s.authMiddleware()(c)
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
	for {
		existing, _ := s.getPairingSessionDB(code)
		if existing == nil {
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

	if err := s.createPairingSessionDB(session); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to create pairing session"})
		return
	}

	// 记录成功
	s.recordSuccess(clientIP)

	c.JSON(http.StatusCreated, session)
}

// getPairingSession 获取配对会话
func (s *Server) getPairingSession(c *gin.Context) {
	code := c.Param("code")

	session, err := s.getPairingSessionDB(code)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "database error"})
		return
	}

	if session == nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "session not found"})
		return
	}

	// 检查是否过期
	if time.Now().After(session.ExpiresAt) {
		session.Status = "expired"
		s.updatePairingSessionDB(session)
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

	session, err := s.getPairingSessionDB(code)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "database error"})
		return
	}

	if session == nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "session not found"})
		return
	}

	// 检查是否过期
	if time.Now().After(session.ExpiresAt) {
		session.Status = "expired"
		s.updatePairingSessionDB(session)
		c.JSON(http.StatusGone, gin.H{"error": "session expired"})
		return
	}

	// 检查状态转换
	validTransitions := map[string][]string{
		"pending":      {"connected", "expired"},
		"connected":    {"disconnected", "expired"},
		"disconnected": {},
		"expired":      {},
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

	if err := s.updatePairingSessionDB(session); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to update session"})
		return
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

	sessionToken, err := s.getSessionTokenDB(req.Token)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"valid": false})
		return
	}

	if sessionToken == nil {
		c.JSON(http.StatusOK, gin.H{"valid": false})
		return
	}

	// 检查是否过期
	if time.Now().After(sessionToken.ExpiresAt) {
		s.deleteSessionTokenDB(req.Token)
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

	session, err := s.getPairingSessionDB(req.Code)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "database error"})
		return
	}

	if session == nil {
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

	if err := s.createSessionTokenDB(sessionToken); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to create session token"})
		return
	}

	c.JSON(http.StatusCreated, sessionToken)
}

// getDeviceStatus 获取设备状态
func (s *Server) getDeviceStatus(c *gin.Context) {
	deviceID := c.Param("id")

	status, err := s.getDeviceStatusDB(deviceID)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "database error"})
		return
	}

	if status == nil {
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

	status := &DeviceStatus{
		DeviceID:   req.DeviceID,
		Status:     req.Status,
		LastSeen:   time.Now(),
		TunnelAddr: req.TunnelAddr,
	}

	if err := s.upsertDeviceStatusDB(status); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to update device status"})
		return
	}

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

	// 从环境变量获取管理员凭据（已在启动时验证存在）
	adminUser := os.Getenv("ADMIN_USER")
	adminPass := os.Getenv("ADMIN_PASS")

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
	// 检查数据库连接
	dbStatus := "ok"
	if err := s.db.Ping(); err != nil {
		dbStatus = "error"
	}

	c.JSON(http.StatusOK, gin.H{
		"status":    "ok",
		"db_status": dbStatus,
		"time":      time.Now().UTC(),
	})
}

func main() {
	// 设置Gin模式
	gin.SetMode(gin.ReleaseMode)

	server, err := NewServer()
	if err != nil {
		log.Fatalf("Failed to create server: %v", err)
	}
	defer server.Close()

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
		api.POST("/device/status", server.internalOrUserAuthMiddleware(), server.updateDeviceStatus)
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

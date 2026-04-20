package main

import (
	"crypto/rand"
	"crypto/subtle"
	"database/sql"
	"encoding/binary"
	"errors"
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

// 错误消息常量 - 统一使用 ErrFailedToXxx 命名风格
const (
	ErrFailedToParseRequest        = "invalid request format"
	ErrFailedToQueryDatabase       = "database error"
	ErrFailedToFindSession         = "session not found"
	ErrFailedToFindPairingSession  = "pairing session not found"
	ErrFailedToGenerateCode        = "failed to generate pairing code"
	ErrFailedToCreateSession       = "failed to create pairing session"
	ErrFailedToUpdateSession       = "failed to update session"
	ErrFailedToUpdateStatus        = "failed to update device status"
	ErrFailedToGenerateToken       = "failed to generate session token"
	ErrFailedToCreateToken         = "failed to create session token"
	ErrFailedToValidateToken       = "missing or invalid bearer token"
	ErrFailedToAuthenticate        = "invalid credentials"
	ErrUnauthorized                = "unauthorized"
	ErrForbidden                   = "forbidden"
	ErrSessionExpired              = "session expired"
	ErrPairingNotCompleted         = "pairing not completed"
	ErrInvalidStatusTransition     = "invalid status transition"
	ErrRateLimitExceeded           = "rate limit exceeded, please try again later"
	ErrRateLimit                   = "rate limit exceeded"
	ErrMissingEngineer             = "missing authenticated engineer"
	ErrInternalAPIKeyNotConfigured = "internal api key not configured"
	ErrMissingInternalAPIKey       = "missing internal api key"
	ErrInvalidInternalAPIKey       = "invalid internal api key"
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

// handleBindError 统一处理请求绑定错误
func handleBindError(c *gin.Context, component string, err error) {
	log.Printf("[%s] Invalid request format: %v", component, err)
	c.JSON(http.StatusBadRequest, gin.H{"error": ErrFailedToParseRequest})
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

	internalAPIKey := os.Getenv("INTERNAL_API_KEY")
	if internalAPIKey == "" {
		// 严格检查：仅在明确设置 APP_ENV=development 且不是生产环境时允许自动生成密钥
		appEnv := os.Getenv("APP_ENV")
		if appEnv == "development" {
			// 额外的安全检查：确保关键生产环境变量未设置，防止误用开发模式
			if os.Getenv("ENABLE_TLS") == "true" {
				log.Fatalf("FATAL: Cannot use APP_ENV=development when ENABLE_TLS is true. Development mode is not allowed in production configurations.")
			}

			log.Println("============================================================")
			log.Println("  WARNING: DEVELOPMENT MODE - AUTO-GENERATED INTERNAL API KEY")
			log.Println("  This key is ephemeral and will change on every restart.")
			log.Println("  Internal service calls (e.g. SOCKS5 proxy) will NOT work")
			log.Println("  unless you set INTERNAL_API_KEY explicitly.")
			log.Println("  NEVER use APP_ENV=development in production!")
			log.Println("============================================================")
			key, err := generateSecureRandomString(32)
			if err != nil {
				log.Fatalf("FATAL: failed to generate random INTERNAL_API_KEY: %v", err)
			}
			internalAPIKey = key
			log.Printf("  INTERNAL_API_KEY generated (first 8 chars: %s...)", internalAPIKey[:8])
		} else {
			if appEnv != "" && appEnv != "production" {
				log.Printf("WARNING: APP_ENV is set to %q but only \"development\" enables dev mode. Treating as production.", appEnv)
			}
			log.Fatalf("FATAL: INTERNAL_API_KEY environment variable is not set. Set APP_ENV=development for local development only, or configure INTERNAL_API_KEY for production.")
		}
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
		db:             db,
		loginAttempts:  make(map[string]*LoginAttempt),
		jwtSecret:      []byte(jwtSecret),
		internalAPIKey: []byte(internalAPIKey),
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

// generateSecureRandomString 生成加密安全的随机字符串（用于API密钥等安全敏感场景）
// 使用拒绝采样避免模运算偏斜，确保均匀分布
func generateSecureRandomString(length int) (string, error) {
	const charset = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
	const charsetLen = 62
	const threshold = 256 - (256 % charsetLen)

	result := make([]byte, length)
	for i := 0; i < length; i++ {
		for {
			b := make([]byte, 1)
			if _, err := rand.Read(b); err != nil {
				return "", fmt.Errorf("crypto/rand.Read failed: %w", err)
			}
			if int(b[0]) < threshold {
				result[i] = charset[int(b[0])%charsetLen]
				break
			}
		}
	}
	return string(result), nil
}

// generateSessionToken 生成会话令牌
func generateSessionToken() (string, error) {
	return generateSecureRandomString(32)
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

// markSessionExpired 将会话标记为过期状态并更新数据库
// 返回错误表示数据库更新失败
func (s *Server) markSessionExpired(session *PairingSession) error {
	// 先更新内存状态，再更新数据库，确保状态一致性
	session.Status = "expired"
	if err := s.updatePairingSessionDB(session); err != nil {
		return err
	}
	return nil
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

func classifyJWTValidationError(err error) string {
	switch {
	case errors.Is(err, jwt.ErrTokenExpired):
		return "token_expired"
	case errors.Is(err, jwt.ErrTokenNotValidYet):
		return "token_not_valid_yet"
	case errors.Is(err, jwt.ErrTokenUsedBeforeIssued):
		return "token_used_before_issued"
	case errors.Is(err, jwt.ErrTokenSignatureInvalid):
		return "token_signature_invalid"
	case errors.Is(err, jwt.ErrTokenMalformed):
		return "token_malformed"
	case errors.Is(err, jwt.ErrTokenUnverifiable):
		return "token_unverifiable"
	case errors.Is(err, jwt.ErrTokenInvalidClaims):
		return "token_invalid_claims"
	case strings.Contains(err.Error(), "signing method"):
		return "token_invalid_algorithm"
	default:
		return "token_invalid"
	}
}

// authMiddleware JWT认证中间件
func (s *Server) authMiddleware() gin.HandlerFunc {
	parser := jwt.NewParser(
		jwt.WithValidMethods([]string{jwt.SigningMethodHS256.Alg()}),
		jwt.WithIssuedAt(),
		jwt.WithLeeway(30*time.Second),
	)

	return func(c *gin.Context) {
		auth := c.GetHeader("Authorization")
		if len(auth) < 8 || !strings.HasPrefix(auth, "Bearer ") {
			log.Printf("[Auth] JWT validation failed: token_missing_or_invalid_bearer")
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": ErrFailedToValidateToken})
			return
		}

		tokenString := auth[7:]
		claims := jwt.MapClaims{}
		token, err := parser.ParseWithClaims(tokenString, claims, func(_ *jwt.Token) (interface{}, error) {
			return s.jwtSecret, nil
		})

		if err != nil {
			log.Printf("[Auth] JWT validation failed: %s: %v", classifyJWTValidationError(err), err)
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": ErrFailedToValidateToken})
			return
		}

		if !token.Valid {
			log.Printf("[Auth] JWT validation failed: token_invalid")
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": ErrFailedToValidateToken})
			return
		}

		c.Set("engineer_id", claims["sub"])
		c.Set("role", claims["role"])

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

			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": ErrInvalidInternalAPIKey})
			return
		}

		s.authMiddleware()(c)
	}
}

func (s *Server) internalAuthMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		if len(s.internalAPIKey) == 0 {
			c.AbortWithStatusJSON(http.StatusServiceUnavailable, gin.H{"error": ErrInternalAPIKeyNotConfigured})
			return
		}

		internalKey := c.GetHeader("X-Internal-API-Key")
		if internalKey == "" {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": ErrMissingInternalAPIKey})
			return
		}

		if subtle.ConstantTimeCompare([]byte(internalKey), s.internalAPIKey) != 1 {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": ErrInvalidInternalAPIKey})
			return
		}

		c.Set("role", "internal")
		c.Next()
	}
}

// createPairingSession 创建配对会话
func (s *Server) createPairingSession(c *gin.Context) {
	clientIP := c.ClientIP()

	// 检查限流
	if !s.checkRateLimit(clientIP) {
		c.JSON(http.StatusTooManyRequests, gin.H{"error": ErrRateLimitExceeded})
		return
	}

	var req struct {
		DeviceID string `json:"device_id" binding:"required"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		handleBindError(c, "Pairing", err)
		return
	}

	// 生成配对码
	code, err := generateCode()
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": ErrFailedToGenerateCode})
		return
	}

	// 检查配对码是否已存在
	for {
		existing, _ := s.getPairingSessionDB(code)
		if existing == nil {
			break
		}
		var err error
		code, err = generateCode()
		if err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": ErrFailedToGenerateCode})
			return
		}
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
		c.JSON(http.StatusInternalServerError, gin.H{"error": ErrFailedToCreateSession})
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
		c.JSON(http.StatusInternalServerError, gin.H{"error": ErrFailedToQueryDatabase})
		return
	}

	if session == nil {
		c.JSON(http.StatusNotFound, gin.H{"error": ErrFailedToFindSession})
		return
	}

	// 检查是否过期
	if time.Now().After(session.ExpiresAt) {
		if err := s.markSessionExpired(session); err != nil {
			log.Printf("Failed to mark session %s as expired: %v", session.Code, err)
			c.JSON(http.StatusInternalServerError, gin.H{"error": ErrFailedToUpdateSession})
			return
		}
		c.JSON(http.StatusGone, gin.H{"error": ErrSessionExpired})
		return
	}

	c.JSON(http.StatusOK, session)
}

// updatePairingSession 更新配对会话
func (s *Server) updatePairingSession(c *gin.Context) {
	code := c.Param("code")

	engineerIDValue, exists := c.Get("engineer_id")
	engineerID, ok := engineerIDValue.(string)
	if !exists || !ok || engineerID == "" {
		c.JSON(http.StatusUnauthorized, gin.H{"error": ErrMissingEngineer})
		return
	}

	var req struct {
		Status string `json:"status" binding:"required"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		handleBindError(c, "Pairing", err)
		return
	}

	session, err := s.getPairingSessionDB(code)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": ErrFailedToQueryDatabase})
		return
	}

	if session == nil {
		c.JSON(http.StatusNotFound, gin.H{"error": ErrFailedToFindSession})
		return
	}

	if session.EngineerID != "" && session.EngineerID != engineerID {
		c.JSON(http.StatusForbidden, gin.H{"error": ErrForbidden})
		return
	}

	// 检查是否过期
	if time.Now().After(session.ExpiresAt) {
		if err := s.markSessionExpired(session); err != nil {
			log.Printf("Failed to mark session %s as expired: %v", session.Code, err)
			c.JSON(http.StatusInternalServerError, gin.H{"error": ErrFailedToUpdateSession})
			return
		}
		c.JSON(http.StatusGone, gin.H{"error": ErrSessionExpired})
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
		c.JSON(http.StatusBadRequest, gin.H{"error": ErrInvalidStatusTransition})
		return
	}

	session.Status = req.Status
	session.EngineerID = engineerID

	// 如果连接成功，标记为已使用
	if req.Status == "connected" {
		session.Used = true
	}

	if err := s.updatePairingSessionDB(session); err != nil {
		log.Printf("Failed to update pairing session %s: %v", session.Code, err)
		c.JSON(http.StatusInternalServerError, gin.H{"error": ErrFailedToUpdateSession})
		return
	}

	log.Printf("Pairing session %s updated to status: %s, engineer: %s", session.Code, session.Status, session.EngineerID)
	c.JSON(http.StatusOK, session)
}

// validateSession 验证会话令牌（内部API，供SOCKS5服务调用）
func (s *Server) validateSession(c *gin.Context) {
	var req struct {
		DeviceID string `json:"device_id" binding:"required"`
		Token    string `json:"token" binding:"required"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		handleBindError(c, "Session", err)
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
		if err := s.deleteSessionTokenDB(req.Token); err != nil {
			log.Printf("Failed to delete expired session token %s: %v", req.Token, err)
		}
		c.JSON(http.StatusOK, gin.H{"valid": false})
		return
	}

	// 验证deviceID匹配
	valid := subtle.ConstantTimeCompare([]byte(sessionToken.DeviceID), []byte(req.DeviceID)) == 1

	c.JSON(http.StatusOK, gin.H{"valid": valid})
}

// createSessionToken 创建会话令牌
func (s *Server) createSessionToken(c *gin.Context) {
	engineerIDValue, exists := c.Get("engineer_id")
	engineerID, ok := engineerIDValue.(string)
	if !exists || !ok || engineerID == "" {
		c.JSON(http.StatusUnauthorized, gin.H{"error": ErrMissingEngineer})
		return
	}

	var req struct {
		Code string `json:"code" binding:"required"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		handleBindError(c, "Session", err)
		return
	}

	session, err := s.getPairingSessionDB(req.Code)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": ErrFailedToQueryDatabase})
		return
	}

	if session == nil {
		c.JSON(http.StatusNotFound, gin.H{"error": ErrFailedToFindPairingSession})
		return
	}

	if session.Status != "connected" {
		c.JSON(http.StatusBadRequest, gin.H{"error": ErrPairingNotCompleted})
		return
	}

	if session.EngineerID != engineerID {
		c.JSON(http.StatusForbidden, gin.H{"error": ErrForbidden})
		return
	}

	// 创建会话令牌
	token, err := generateSessionToken()
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": ErrFailedToGenerateToken})
		return
	}
	sessionToken := &SessionToken{
		Token:      token,
		DeviceID:   session.DeviceID,
		EngineerID: engineerID,
		CreatedAt:  time.Now(),
		ExpiresAt:  time.Now().Add(SessionTokenTTL),
	}

	if err := s.createSessionTokenDB(sessionToken); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": ErrFailedToCreateToken})
		return
	}

	c.JSON(http.StatusCreated, sessionToken)
}

// getDeviceStatus 获取设备状态
func (s *Server) getDeviceStatus(c *gin.Context) {
	deviceID := c.Param("id")

	status, err := s.getDeviceStatusDB(deviceID)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": ErrFailedToQueryDatabase})
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
		handleBindError(c, "Device", err)
		return
	}

	status := &DeviceStatus{
		DeviceID:   req.DeviceID,
		Status:     req.Status,
		LastSeen:   time.Now(),
		TunnelAddr: req.TunnelAddr,
	}

	if err := s.upsertDeviceStatusDB(status); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": ErrFailedToUpdateStatus})
		return
	}

	c.JSON(http.StatusOK, gin.H{"status": "updated"})
}

// login 工程师登录
func (s *Server) login(c *gin.Context) {
	clientIP := c.ClientIP()

	// 检查限流
	if !s.checkRateLimit(clientIP) {
		c.JSON(http.StatusTooManyRequests, gin.H{"error": ErrRateLimit})
		return
	}

	var req struct {
		Username string `json:"username" binding:"required"`
		Password string `json:"password" binding:"required"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		handleBindError(c, "Admin", err)
		return
	}

	// 从环境变量获取管理员凭据（已在启动时验证存在）
	adminUser := os.Getenv("ADMIN_USER")
	adminPass := os.Getenv("ADMIN_PASS")

	if req.Username != adminUser || req.Password != adminPass {
		c.JSON(http.StatusUnauthorized, gin.H{"error": ErrFailedToAuthenticate})
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
		c.JSON(http.StatusInternalServerError, gin.H{"error": ErrFailedToGenerateToken})
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
		api.PUT("/pair/:code", server.authMiddleware(), server.updatePairingSession)

		// 会话令牌
		api.POST("/session/token", server.authMiddleware(), server.createSessionToken)
		api.POST("/session/validate", server.internalAuthMiddleware(), server.validateSession)

		// 设备状态
		api.GET("/device/:id/status", server.authMiddleware(), server.getDeviceStatus)
		api.POST("/device/status", server.internalAuthMiddleware(), server.updateDeviceStatus)
	}

	port := os.Getenv("PORT")
	if port == "" {
		port = "8080"
	}

	// 读取TLS配置（环境变量优先）
	enableTLS := os.Getenv("ENABLE_TLS") == "true"
	tlsCert := os.Getenv("TLS_CERT")
	tlsKey := os.Getenv("TLS_KEY")

	httpServer := &http.Server{
		Addr:              ":" + port,
		Handler:           r,
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       15 * time.Second,
		WriteTimeout:      30 * time.Second,
		IdleTimeout:       60 * time.Second,
	}

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
		if err := httpServer.ListenAndServeTLS(tlsCert, tlsKey); err != nil {
			log.Fatalf("Server failed: %v", err)
		}
	} else {
		if err := httpServer.ListenAndServe(); err != nil {
			log.Fatalf("Server failed: %v", err)
		}
	}
}

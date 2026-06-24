package main

import (
	"crypto/rand"
	"crypto/sha256"
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
	sqlite3 "github.com/mattn/go-sqlite3"
	"github.com/netproxy/shared/ratelimit"
)

// Constants
const (
	PairingCodeTTL                = 15 * time.Minute
	SessionTokenTTL               = 15 * time.Minute
	MaxFailedAttempts             = 5
	MaxPairingCodeConflictRetries = 10
	BlockDuration                 = 15 * time.Minute
	DefaultDBPath                 = "./api.db"
	MinJWTSecretLength            = 32
	MaxHTTPHeaderBytes            = 1 << 20
)

// Sentinel errors - unified ErrFailedToXxx naming convention
var (
	ErrFailedToParseRequest               = errors.New("invalid request format")
	ErrFailedToQueryDatabase              = errors.New("database error")
	ErrFailedToFindSession                = errors.New("session not found")
	ErrFailedToFindPairingSession         = errors.New("pairing session not found")
	ErrFailedToGenerateCode               = errors.New("failed to generate pairing code")
	ErrFailedToResolvePairingCodeConflict = errors.New("pairing code temporarily unavailable")
	ErrFailedToCreateSession              = errors.New("failed to create pairing session")
	ErrFailedToUpdateSession              = errors.New("failed to update session")
	ErrFailedToUpdateStatus               = errors.New("failed to update device status")
	ErrFailedToGenerateToken              = errors.New("failed to generate session token")
	ErrFailedToCreateToken                = errors.New("failed to create session token")
	ErrFailedToValidateToken              = errors.New("missing or invalid bearer token")
	ErrFailedToAuthenticate               = errors.New("invalid credentials")
	ErrUnauthorized                       = errors.New("unauthorized")
	ErrForbidden                          = errors.New("forbidden")
	ErrSessionExpired                     = errors.New("session expired")
	ErrPairingNotCompleted                = errors.New("pairing not completed")
	ErrInvalidStatusTransition            = errors.New("invalid status transition")
	ErrRateLimitExceeded                  = errors.New("rate limit exceeded, please try again later")
	ErrRateLimit                          = errors.New("rate limit exceeded")
	ErrMissingEngineer                    = errors.New("missing authenticated engineer")
	ErrInternalAPIKeyNotConfigured        = errors.New("internal api key not configured")
	ErrMissingInternalAPIKey              = errors.New("missing internal api key")
	ErrInvalidInternalAPIKey              = errors.New("invalid internal api key")
)

var (
	errPairingCodeConflictRetryLimitReached = errors.New("pairing code conflict retry limit reached")
)

// PairingSession represents a pairing session.
type PairingSession struct {
	Code       string    `json:"code"`
	DeviceID   string    `json:"device_id"`
	Status     string    `json:"status"`
	EngineerID string    `json:"engineer_id,omitempty"`
	CreatedAt  time.Time `json:"created_at"`
	ExpiresAt  time.Time `json:"expires_at"`
	Used       bool      `json:"used"`
}

// SessionToken represents a session token.
type SessionToken struct {
	Token      string    `json:"token"`
	DeviceID   string    `json:"device_id"`
	EngineerID string    `json:"engineer_id"`
	CreatedAt  time.Time `json:"created_at"`
	ExpiresAt  time.Time `json:"expires_at"`
}

// DeviceStatus represents the status of a device.
type DeviceStatus struct {
	DeviceID   string    `json:"device_id"`
	Status     string    `json:"status"`
	LastSeen   time.Time `json:"last_seen"`
	TunnelAddr string    `json:"tunnel_addr,omitempty"`
}

// Server is the API server.
type Server struct {
	db                   *sql.DB
	pairingCodeGenerator func() (string, error)

	rateLimiter *ratelimit.RateLimiter

	jwtSecret        []byte
	internalAPIKey   []byte
	expectedUserHash [sha256.Size]byte
	expectedPassHash [sha256.Size]byte

	cleanupStop             chan struct{}
	cleanupWorkers          sync.WaitGroup
	cleanupSessionsInterval time.Duration
}

// handleBindError handles request binding errors uniformly.
func handleBindError(c *gin.Context, component string, err error) {
	log.Printf("[%s] Invalid request format: %v", component, err)
	c.JSON(http.StatusBadRequest, gin.H{"error": ErrFailedToParseRequest.Error()})
}

func validateJWTSecret(secret string) error {
	if secret == "" {
		return errors.New("JWT_SECRET environment variable is not set. Please set a secure JWT secret before starting the server.")
	}
	if len(secret) < MinJWTSecretLength {
		return fmt.Errorf("JWT_SECRET must be at least %d characters long", MinJWTSecretLength)
	}
	return nil
}

// NewServer creates a new server.
func NewServer() (*Server, error) {
	jwtSecret := os.Getenv("JWT_SECRET")
	if err := validateJWTSecret(jwtSecret); err != nil {
		log.Fatalf("FATAL: %v", err)
	}

	// Check admin credentials are configured
	adminUser := os.Getenv("ADMIN_USER")
	adminPass := os.Getenv("ADMIN_PASS")
	if adminUser == "" || adminPass == "" {
		log.Fatalf("FATAL: ADMIN_USER and ADMIN_PASS environment variables must be set. Please configure admin credentials before starting the server.")
	}

	expectedUserHash := sha256.Sum256([]byte(adminUser))
	expectedPassHash := sha256.Sum256([]byte(adminPass))

	internalAPIKey := os.Getenv("INTERNAL_API_KEY")
	if internalAPIKey == "" {
		// Strict check: only allow auto-generated key in APP_ENV=development
		appEnv := os.Getenv("APP_ENV")
		if appEnv == "development" {
			// Extra safety check: ensure production env vars are not set
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
			prefix := internalAPIKey
			if len(prefix) > 4 {
				prefix = prefix[:4]
			}
			log.Printf("  INTERNAL_API_KEY generated (first 4 chars: %s...)", prefix)
		} else {
			if appEnv != "" && appEnv != "production" {
				log.Printf("WARNING: APP_ENV is set to %q but only \"development\" enables dev mode. Treating as production.", appEnv)
			}
			log.Fatalf("FATAL: INTERNAL_API_KEY environment variable is not set. Set APP_ENV=development for local development only, or configure INTERNAL_API_KEY for production.")
		}
	}

	// Get database path
	dbPath := os.Getenv("DB_PATH")
	if dbPath == "" {
		dbPath = DefaultDBPath
	}

	// Open database connection
	db, err := sql.Open("sqlite3", dbPath+"?_journal_mode=WAL&_busy_timeout=5000")
	if err != nil {
		return nil, fmt.Errorf("failed to open database: %w", err)
	}

	// Verify database connection
	if err := db.Ping(); err != nil {
		return nil, fmt.Errorf("failed to ping database: %w", err)
	}

	// Create schema
	if err := initSchema(db); err != nil {
		return nil, fmt.Errorf("failed to init schema: %w", err)
	}

	return &Server{
		db:                   db,
		pairingCodeGenerator: generateCode,
		rateLimiter:          ratelimit.NewRateLimiterWithDefaults(),
		jwtSecret:            []byte(jwtSecret),
		internalAPIKey:       []byte(internalAPIKey),
		expectedUserHash:     expectedUserHash,
		expectedPassHash:     expectedPassHash,
	}, nil
}

func isPairingCodeUniqueConstraintError(err error) bool {
	var sqliteErr sqlite3.Error
	if !errors.As(err, &sqliteErr) {
		return false
	}

	if sqliteErr.Code != sqlite3.ErrConstraint {
		return false
	}

	return sqliteErr.ExtendedCode == sqlite3.ErrConstraintPrimaryKey || sqliteErr.ExtendedCode == sqlite3.ErrConstraintUnique
}

// initSchema initializes the database schema.
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

// Close closes server resources.
func (s *Server) Close() error {
	if s.rateLimiter != nil {
		s.rateLimiter.Stop()
	}

	if s.cleanupStop != nil {
		close(s.cleanupStop)
		s.cleanupWorkers.Wait()
	}

	if s.db != nil {
		return s.db.Close()
	}
	return nil
}

// generateCode generates a 6-digit pairing code using rejection sampling to avoid modulo bias.
func generateCode() (string, error) {
	const maxCode = 1000000
	// uint32 max value is 4294967295
	// 4294967296 % 1000000 = 7296
	// Reject values 0-7295 to ensure uniform distribution
	const maxUint32 = 1 << 32
	const threshold = maxUint32 - (maxUint32 % maxCode)

	for {
		b := make([]byte, 4)
		if _, err := rand.Read(b); err != nil {
			return "", err
		}
		n := binary.BigEndian.Uint32(b)
		// Rejection sampling: resample if n >= threshold
		if n < threshold {
			code := int(n % maxCode)
			return fmt.Sprintf("%06d", code), nil
		}
	}
}

func generateUniquePairingCode(
	maxRetries int,
	codeGenerator func() (string, error),
	codeExists func(code string) (bool, error),
) (string, error) {
	if maxRetries <= 0 {
		return "", errPairingCodeConflictRetryLimitReached
	}

	for attempt := 0; attempt < maxRetries; attempt++ {
		code, err := codeGenerator()
		if err != nil {
			return "", err
		}

		exists, err := codeExists(code)
		if err != nil {
			return "", err
		}

		if !exists {
			return code, nil
		}
	}

	return "", errPairingCodeConflictRetryLimitReached
}

// generateSecureRandomString generates a cryptographically secure random string for sensitive use cases like API keys.
// Uses rejection sampling to avoid modulo bias and ensure uniform distribution.
// Optimized: pre-allocates buffers and batch reads random bytes to reduce system calls.
func generateSecureRandomString(length int) (string, error) {
	const charset = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
	const charsetLen = 62
	const threshold = 256 - (256 % charsetLen)
	// Batch size: read multiple bytes at once to amortize crypto/rand.Read syscall overhead.
	// Each byte has ~75% acceptance probability (threshold/256 ≈ 0.987 for charsetLen=62),
	// so a batch of 4x length provides enough valid bytes with high probability.
	const batchMultiplier = 4

	result := make([]byte, length)
	batch := make([]byte, length*batchMultiplier)
	pos := 0

	for pos < length {
		if _, err := rand.Read(batch); err != nil {
			return "", fmt.Errorf("crypto/rand.Read failed: %w", err)
		}
		for _, b := range batch {
			if int(b) < threshold {
				result[pos] = charset[int(b)%charsetLen]
				pos++
				if pos == length {
					break
				}
			}
		}
	}
	return string(result), nil
}

// generateSessionToken generates a session token.
func generateSessionToken() (string, error) {
	return generateSecureRandomString(32)
}

// startCleanupWorkers starts the background cleanup goroutines.
func (s *Server) startCleanupWorkers() {
	if s.cleanupStop != nil {
		return
	}

	s.cleanupStop = make(chan struct{})
	s.cleanupWorkers.Add(1)

	go s.cleanupExpiredSessions(s.cleanupStop)
}

// cleanupExpiredSessions periodically cleans up expired pairing sessions and session tokens.
func (s *Server) cleanupExpiredSessions(stop <-chan struct{}) {
	interval := s.cleanupSessionsInterval
	if interval <= 0 {
		interval = 5 * time.Minute
	}

	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	defer s.cleanupWorkers.Done()

	for {
		select {
		case <-stop:
			return
		case <-ticker.C:
			now := time.Now().Unix()

			// Clean up expired pairing sessions
			_, err := s.db.Exec("DELETE FROM pairing_sessions WHERE expires_at < ?", now)
			if err != nil {
				log.Printf("Failed to cleanup expired pairing sessions: %v", err)
			}

			// Clean up expired session tokens
			_, err = s.db.Exec("DELETE FROM session_tokens WHERE expires_at < ?", now)
			if err != nil {
				log.Printf("Failed to cleanup expired session tokens: %v", err)
			}
		}
	}
}

// ==================== Database Operations ====================

// createPairingSessionDB inserts a pairing session into the database.
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

// getPairingSessionDB retrieves a pairing session from the database by code.
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

// markSessionExpired marks a session as expired and updates the database.
// Uses a conditional UPDATE on status only to avoid overwriting engineer_id
// or used fields that may have been updated concurrently by updatePairingSession.
// Returns an error if the database update fails.
func (s *Server) markSessionExpired(session *PairingSession) error {
	result, err := s.db.Exec(
		`UPDATE pairing_sessions SET status = 'expired' WHERE code = ? AND status != 'expired'`,
		session.Code,
	)
	if err != nil {
		return err
	}
	rowsAffected, _ := result.RowsAffected()
	if rowsAffected > 0 {
		session.Status = "expired"
	}
	return nil
}

// createSessionTokenDB inserts a session token into the database.
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

// getSessionTokenDB retrieves a session token from the database.
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

// deleteSessionTokenDB deletes a session token from the database.
func (s *Server) deleteSessionTokenDB(token string) error {
	_, err := s.db.Exec("DELETE FROM session_tokens WHERE token = ?", token)
	return err
}

// getDeviceStatusDB retrieves device status from the database.
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

// upsertDeviceStatusDB inserts or updates device status in the database.
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

// boolToInt converts a bool to int (0 or 1).
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

var allowedJWTClaimRoles = map[string]struct{}{
	"engineer": {},
	"admin":    {},
}

func validateAuthClaims(claims jwt.MapClaims) (string, string, bool) {
	subValue, ok := claims["sub"]
	if !ok {
		return "", "", false
	}

	sub, ok := subValue.(string)
	if !ok || sub == "" {
		return "", "", false
	}

	roleValue, ok := claims["role"]
	if !ok {
		return "", "", false
	}

	role, ok := roleValue.(string)
	if !ok {
		return "", "", false
	}

	if _, ok := allowedJWTClaimRoles[role]; !ok {
		return "", "", false
	}

	return sub, role, true
}

// authMiddleware is the JWT authentication middleware.
func (s *Server) authMiddleware() gin.HandlerFunc {
	parser := jwt.NewParser(
		jwt.WithValidMethods([]string{jwt.SigningMethodHS256.Alg()}),
		jwt.WithExpirationRequired(),
		jwt.WithIssuedAt(),
		jwt.WithLeeway(30*time.Second),
	)

	return func(c *gin.Context) {
		auth := c.GetHeader("Authorization")
		if len(auth) < 8 || !strings.HasPrefix(auth, "Bearer ") {
			log.Printf("[Auth] JWT validation failed: token_missing_or_invalid_bearer")
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": ErrFailedToValidateToken.Error()})
			return
		}

		tokenString := auth[7:]
		claims := jwt.MapClaims{}
		token, err := parser.ParseWithClaims(tokenString, claims, func(_ *jwt.Token) (interface{}, error) {
			return s.jwtSecret, nil
		})

		if err != nil {
			log.Printf("[Auth] JWT validation failed: %s: %v", classifyJWTValidationError(err), err)
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": ErrFailedToValidateToken.Error()})
			return
		}

		if !token.Valid {
			log.Printf("[Auth] JWT validation failed: token_invalid")
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": ErrFailedToValidateToken.Error()})
			return
		}

		engineerID, role, valid := validateAuthClaims(claims)
		if !valid {
			log.Printf("[Auth] JWT validation failed: token_invalid_claims")
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": ErrFailedToValidateToken.Error()})
			return
		}

		c.Set("engineer_id", engineerID)
		c.Set("role", role)

		c.Next()
	}
}

// internalOrUserAuthMiddleware allows either internal API key or JWT bearer token authentication.
func (s *Server) internalOrUserAuthMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		internalKey := c.GetHeader("X-Internal-API-Key")
		if len(s.internalAPIKey) > 0 && internalKey != "" {
			if subtle.ConstantTimeCompare([]byte(internalKey), s.internalAPIKey) == 1 {
				c.Set("role", "internal")
				c.Next()
				return
			}

			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": ErrInvalidInternalAPIKey.Error()})
			return
		}

		s.authMiddleware()(c)
	}
}

// internalAuthMiddleware enforces internal API key authentication.
func (s *Server) internalAuthMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		if len(s.internalAPIKey) == 0 {
			c.AbortWithStatusJSON(http.StatusServiceUnavailable, gin.H{"error": ErrInternalAPIKeyNotConfigured.Error()})
			return
		}

		internalKey := c.GetHeader("X-Internal-API-Key")
		if internalKey == "" {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": ErrMissingInternalAPIKey.Error()})
			return
		}

		if subtle.ConstantTimeCompare([]byte(internalKey), s.internalAPIKey) != 1 {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": ErrInvalidInternalAPIKey.Error()})
			return
		}

		c.Set("role", "internal")
		c.Next()
	}
}

// createPairingSession creates a new pairing session.
func (s *Server) createPairingSession(c *gin.Context) {
	clientIP := c.ClientIP()

	// Check rate limiting
	if !s.rateLimiter.Allow(clientIP) {
		c.JSON(http.StatusTooManyRequests, gin.H{"error": ErrRateLimitExceeded.Error()})
		return
	}

	var req struct {
		DeviceID string `json:"device_id" binding:"required"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		handleBindError(c, "Pairing", err)
		return
	}

	codeGenerator := s.pairingCodeGenerator
	if codeGenerator == nil {
		codeGenerator = generateCode
	}

	for attempt := 0; attempt < MaxPairingCodeConflictRetries; attempt++ {
		code, err := codeGenerator()
		if err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": ErrFailedToGenerateCode.Error()})
			return
		}

		now := time.Now()
		session := &PairingSession{
			Code:      code,
			DeviceID:  req.DeviceID,
			Status:    "pending",
			CreatedAt: now,
			ExpiresAt: now.Add(PairingCodeTTL),
			Used:      false,
		}

		if err := s.createPairingSessionDB(session); err != nil {
			if isPairingCodeUniqueConstraintError(err) {
				continue
			}

			c.JSON(http.StatusInternalServerError, gin.H{"error": ErrFailedToCreateSession.Error()})
			return
		}

		// Record successful attempt
		s.rateLimiter.Success(clientIP)

		c.JSON(http.StatusCreated, session)
		return
	}

	c.JSON(http.StatusServiceUnavailable, gin.H{"error": ErrFailedToResolvePairingCodeConflict.Error()})
}

// getPairingSession retrieves a pairing session by code.
func (s *Server) getPairingSession(c *gin.Context) {
	code := c.Param("code")

	session, err := s.getPairingSessionDB(code)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": ErrFailedToQueryDatabase.Error()})
		return
	}

	if session == nil {
		c.JSON(http.StatusNotFound, gin.H{"error": ErrFailedToFindSession.Error()})
		return
	}

	// Check if expired
	if time.Now().After(session.ExpiresAt) {
		if err := s.markSessionExpired(session); err != nil {
			log.Printf("Failed to mark session %s as expired: %v", session.Code, err)
			c.JSON(http.StatusInternalServerError, gin.H{"error": ErrFailedToUpdateSession.Error()})
			return
		}
		c.JSON(http.StatusGone, gin.H{"error": ErrSessionExpired.Error()})
		return
	}

	c.JSON(http.StatusOK, session)
}

// updatePairingSession updates a pairing session status.
func (s *Server) updatePairingSession(c *gin.Context) {
	code := c.Param("code")

	engineerIDValue, exists := c.Get("engineer_id")
	engineerID, ok := engineerIDValue.(string)
	if !exists || !ok || engineerID == "" {
		c.JSON(http.StatusUnauthorized, gin.H{"error": ErrMissingEngineer.Error()})
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
		c.JSON(http.StatusInternalServerError, gin.H{"error": ErrFailedToQueryDatabase.Error()})
		return
	}

	if session == nil {
		c.JSON(http.StatusNotFound, gin.H{"error": ErrFailedToFindSession.Error()})
		return
	}

	if session.EngineerID != "" && session.EngineerID != engineerID {
		c.JSON(http.StatusForbidden, gin.H{"error": ErrForbidden.Error()})
		return
	}

	// Check if expired
	if time.Now().After(session.ExpiresAt) {
		if err := s.markSessionExpired(session); err != nil {
			log.Printf("Failed to mark session %s as expired: %v", session.Code, err)
			c.JSON(http.StatusInternalServerError, gin.H{"error": ErrFailedToUpdateSession.Error()})
			return
		}
		c.JSON(http.StatusGone, gin.H{"error": ErrSessionExpired.Error()})
		return
	}

	// Validate status transition
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
		c.JSON(http.StatusBadRequest, gin.H{"error": ErrInvalidStatusTransition.Error()})
		return
	}

	// Use atomic conditional UPDATE to prevent TOCTOU race: two engineers
	// could both read EngineerID="" and both pass the check above, then
	// overwrite each other's update. The WHERE clause ensures only the
	// first writer wins; the second gets rowsAffected=0.
	// Include status check to prevent same-engineer concurrent requests
	// from bypassing state transition validation with stale reads.
	// used field uses CASE to preserve monotonic semantics: once a session
	// is marked as used (connected), it must never revert to unused.
	result, err := s.db.Exec(
		`UPDATE pairing_sessions SET status = ?, engineer_id = ?, used = CASE WHEN ? = 'connected' THEN 1 ELSE used END WHERE code = ? AND (engineer_id = '' OR engineer_id = ?) AND status = ?`,
		req.Status, engineerID, req.Status, session.Code, engineerID, session.Status,
	)
	if err != nil {
		log.Printf("Failed to update pairing session %s: %v", session.Code, err)
		c.JSON(http.StatusInternalServerError, gin.H{"error": ErrFailedToUpdateSession.Error()})
		return
	}

	rowsAffected, _ := result.RowsAffected()
	if rowsAffected == 0 {
		// Another engineer claimed this session, or the session status
		// changed between our read and write (concurrent update conflict).
		log.Printf("Pairing session %s: concurrent update conflict", session.Code)
		c.JSON(http.StatusConflict, gin.H{"error": "concurrent update conflict"})
		return
	}

	log.Printf("Pairing session %s updated to status: %s, engineer: %s", session.Code, req.Status, engineerID)

	// Return the updated session from DB to reflect the actual persisted state
	updated, err := s.getPairingSessionDB(code)
	if err != nil || updated == nil {
		c.JSON(http.StatusOK, gin.H{
			"code": session.Code, "status": req.Status,
			"engineer_id": engineerID, "used": session.Used || req.Status == "connected",
		})
		return
	}
	c.JSON(http.StatusOK, updated)
}

// validateSession validates a session token (internal API for SOCKS5 service).
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

	// Check if expired
	if time.Now().After(sessionToken.ExpiresAt) {
		if err := s.deleteSessionTokenDB(req.Token); err != nil {
			log.Printf("Failed to delete expired session token: %v", err)
		}
		c.JSON(http.StatusOK, gin.H{"valid": false})
		return
	}

	// Verify device ID matches
	valid := subtle.ConstantTimeCompare([]byte(sessionToken.DeviceID), []byte(req.DeviceID)) == 1

	c.JSON(http.StatusOK, gin.H{"valid": valid})
}

// createSessionToken creates a new session token.
func (s *Server) createSessionToken(c *gin.Context) {
	engineerIDValue, exists := c.Get("engineer_id")
	engineerID, ok := engineerIDValue.(string)
	if !exists || !ok || engineerID == "" {
		c.JSON(http.StatusUnauthorized, gin.H{"error": ErrMissingEngineer.Error()})
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
		c.JSON(http.StatusInternalServerError, gin.H{"error": ErrFailedToQueryDatabase.Error()})
		return
	}

	if session == nil {
		c.JSON(http.StatusNotFound, gin.H{"error": ErrFailedToFindPairingSession.Error()})
		return
	}

	if session.Status != "connected" {
		c.JSON(http.StatusBadRequest, gin.H{"error": ErrPairingNotCompleted.Error()})
		return
	}

	if session.EngineerID != engineerID {
		c.JSON(http.StatusForbidden, gin.H{"error": ErrForbidden.Error()})
		return
	}

	// Create session token
	token, err := generateSessionToken()
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": ErrFailedToGenerateToken.Error()})
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
		c.JSON(http.StatusInternalServerError, gin.H{"error": ErrFailedToCreateToken.Error()})
		return
	}

	c.JSON(http.StatusCreated, sessionToken)
}

// getDeviceStatus retrieves the status of a device.
func (s *Server) getDeviceStatus(c *gin.Context) {
	deviceID := c.Param("id")

	status, err := s.getDeviceStatusDB(deviceID)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": ErrFailedToQueryDatabase.Error()})
		return
	}

	if status == nil {
		// Return offline status
		c.JSON(http.StatusOK, DeviceStatus{
			DeviceID: deviceID,
			Status:   "offline",
			LastSeen: time.Time{},
		})
		return
	}

	c.JSON(http.StatusOK, status)
}

// updateDeviceStatus updates device status (internal API).
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
		c.JSON(http.StatusInternalServerError, gin.H{"error": ErrFailedToUpdateStatus.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{"status": "updated"})
}

// login handles engineer login.
func (s *Server) login(c *gin.Context) {
	clientIP := c.ClientIP()

	// Check rate limiting
	if !s.rateLimiter.Allow(clientIP) {
		c.JSON(http.StatusTooManyRequests, gin.H{"error": ErrRateLimit.Error()})
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

	// Use SHA256-hashed constant-time comparison to prevent timing side-channel
	// attacks. Hashing ensures subtle.ConstantTimeCompare does not leak length
	// information (it returns immediately for slices of different lengths).
	// Expected hashes are cached during server initialization.
	userHash := sha256.Sum256([]byte(req.Username))
	passHash := sha256.Sum256([]byte(req.Password))

	userOK := subtle.ConstantTimeCompare(userHash[:], s.expectedUserHash[:])
	passOK := subtle.ConstantTimeCompare(passHash[:], s.expectedPassHash[:])
	if userOK&passOK != 1 {
		c.JSON(http.StatusUnauthorized, gin.H{"error": ErrFailedToAuthenticate.Error()})
		return
	}

	// Generate JWT
	token := jwt.NewWithClaims(jwt.SigningMethodHS256, jwt.MapClaims{
		"sub":  req.Username,
		"role": "engineer",
		"iat":  time.Now().Unix(),
		"exp":  time.Now().Add(24 * time.Hour).Unix(),
	})

	tokenString, err := token.SignedString(s.jwtSecret)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": ErrFailedToGenerateToken.Error()})
		return
	}

	// Record successful attempt
	s.rateLimiter.Success(clientIP)

	c.JSON(http.StatusOK, gin.H{
		"token": tokenString,
		"type":  "Bearer",
	})
}

// healthCheck returns the health status of the server.
func (s *Server) healthCheck(c *gin.Context) {
	// Check database connection
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

func newHTTPServer(addr string, handler http.Handler) *http.Server {
	return &http.Server{
		Addr:              addr,
		Handler:           handler,
		MaxHeaderBytes:    MaxHTTPHeaderBytes,
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       15 * time.Second,
		WriteTimeout:      30 * time.Second,
		IdleTimeout:       60 * time.Second,
	}
}

func main() {
	// Set Gin mode
	gin.SetMode(gin.ReleaseMode)

	server, err := NewServer()
	if err != nil {
		log.Fatalf("Failed to create server: %v", err)
	}
	defer server.Close()

	// Start cleanup workers
	server.startCleanupWorkers()

	r := gin.New()
	r.Use(gin.Recovery())
	r.Use(gin.Logger())

	// Health check (public)
	r.GET("/health", server.healthCheck)

	// Login (public)
	r.POST("/api/login", server.login)

	// API routes
	api := r.Group("/api")
	{
		// Pairing session management
		api.POST("/pair", server.authMiddleware(), server.createPairingSession)
		api.GET("/pair/:code", server.internalOrUserAuthMiddleware(), server.getPairingSession)
		api.PUT("/pair/:code", server.authMiddleware(), server.updatePairingSession)

		// Session tokens
		api.POST("/session/token", server.authMiddleware(), server.createSessionToken)
		api.POST("/session/validate", server.internalAuthMiddleware(), server.validateSession)

		// Device status
		api.GET("/device/:id/status", server.authMiddleware(), server.getDeviceStatus)
		api.POST("/device/status", server.internalAuthMiddleware(), server.updateDeviceStatus)
	}

	port := os.Getenv("PORT")
	if port == "" {
		port = "8080"
	}

	// Read TLS config (environment variables take priority)
	enableTLS := os.Getenv("ENABLE_TLS") == "true"
	tlsCert := os.Getenv("TLS_CERT")
	tlsKey := os.Getenv("TLS_KEY")

	httpServer := newHTTPServer(":"+port, r)

	// Validate TLS configuration
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

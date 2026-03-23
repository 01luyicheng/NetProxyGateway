# Task 4: Server-side Implementation - 云服务端实现

> **任务级别**: 核心任务  
> **前置依赖**: 已完成 `docs/modules/server-socks5-relay.md` 评审并冻结 v1 接口  
> **预计工作量**: 8 小时 (可拆分多个子任务)

## 任务目标

实现云服务端，包括 MQTT Broker 配置、SOCKS5 中转服务和 REST API。

## 交付物

1. MQTT Broker 配置 (Docker)
2. SOCKS5 中转服务 (Go)
3. REST API 服务 (Go)
4. 服务端部署配置

## 技术栈

| 组件 | 技术 |
|------|------|
| MQTT Broker | EMQX (Docker) |
| SOCKS5 代理 | Go + things-go/go-socks5（或同类 Go SOCKS5 库） |
| REST API | Go + Gin |
| 数据库 | PostgreSQL（v1 默认），SQLite（仅本地 POC） |

## 详细步骤

### 子任务 4A: MQTT Broker 配置

**目标**: 部署 EMQX MQTT Broker

**步骤**:

1. 创建 `docker-compose.yml`:

```yaml
version: '3.8'

services:
  emqx:
    image: emqx/emqx:5.3
    container_name: netproxy-mqtt
    ports:
      - "1883:1883"    # MQTT TCP
      - "8883:8883"    # MQTT TLS
      - "8083:8083"    # MQTT WebSocket
      - "8084:8084"    # MQTT WebSocket TLS
      - "18083:18083"  # Dashboard
    environment:
      EMQX_NAME: emqx
      EMQX_HOST: 127.0.0.1
      EMQX_DASHBOARD__DEFAULT_USERNAME: ${EMQX_DASHBOARD_USERNAME}
      EMQX_DASHBOARD__DEFAULT_PASSWORD: ${EMQX_DASHBOARD_PASSWORD}
      EMQX_MQTT__MAX_PACKET_SIZE: 1MB
      EMQX_MQTT__KEEPALIVE: 30s
    volumes:
      - ./emqx-data:/opt/emqx/data
      - ./emqx-log:/opt/emqx/log
    restart: unless-stopped
```

2. 启动服务:

```bash
docker-compose up -d
```

3. 验证:

访问 http://localhost:18083 （使用环境变量中配置的凭据）

---

### 子任务 4B: SOCKS5 中转服务

**目标**: 实现 SOCKS5 代理服务

**创建目录**: `server/socks5-proxy/`

**Go 模块初始化**:

```bash
mkdir -p server/socks5-proxy
cd server/socks5-proxy
go mod init github.com/netproxy/socks5-proxy
```

**实现代码** (`server/socks5-proxy/main.go`):

```go
package main

import (
	"flag"
	"log"

	"github.com/things-go/go-socks5"
)

var (
	addr     = flag.String("addr", ":1080", "SOCKS5 proxy address")
	username = flag.String("user", "", "Username for authentication")
	password = flag.String("pass", "", "Password for authentication")
)

func main() {
	flag.Parse()
	if *username == "" || *password == "" {
		log.Fatal("SOCKS5 credentials are required in v1; refuse to start without auth")
	}

	// Create SOCKS5 server
	conf := &socks5.Config{
		Addr: *addr,
	}

	// Authentication is mandatory in v1
	conf.Credentials = socks5.StaticCredentials{
		*username: *password,
	}

	server, err := socks5.New(conf)
	if err != nil {
		log.Fatalf("Failed to create SOCKS5 server: %v", err)
	}

	log.Printf("SOCKS5 proxy server listening on %s", *addr)
	if err := server.ListenAndServe(); err != nil {
		log.Fatalf("Failed to start SOCKS5 server: %v", err)
	}
}
```

**依赖**:

```bash
go get github.com/things-go/go-socks5
```

**运行**:

```bash
go run main.go
```

---

### 子任务 4C: REST API 服务

**目标**: 实现配对和管理 API

**创建目录**: `server/api/`

**Go 模块初始化**:

```bash
mkdir -p server/api
cd server/api
go mod init github.com/netproxy/api
```

**实现代码** (`server/api/main.go`):

```go
package main

import (
	"crypto/rand"
	"encoding/binary"
	"fmt"
	"net/http"
	"sync"
	"time"

	"github.com/gin-gonic/gin"
)

// PairingSession represents a device pairing session
type PairingSession struct {
	Code       string    `json:"code"`
	DeviceID   string    `json:"device_id"`
	EngineerID string    `json:"engineer_id"`
	Status     string    `json:"status"` // pending, paired, expired
	CreatedAt  time.Time `json:"created_at"`
	ExpiresAt  time.Time `json:"expires_at"`
	Used       bool      `json:"used"`
}

const pairingTTL = 10 * time.Minute

var allowedTransitions = map[string]map[string]bool{
	"pending": {"paired": true, "expired": true},
	"paired":  {},
	"expired": {},
}

// In-memory storage (use database in production)
var sessions = make(map[string]*PairingSession)
var sessionsMu sync.RWMutex

func main() {
	r := gin.Default()

	// CORS middleware
	r.Use(func(c *gin.Context) {
		c.Writer.Header().Set("Access-Control-Allow-Origin", "https://console.netproxy.example")
		c.Writer.Header().Set("Access-Control-Allow-Methods", "GET,POST,PUT,OPTIONS")
		c.Writer.Header().Set("Access-Control-Allow-Headers", "Authorization,Content-Type")
		if c.Request.Method == http.MethodOptions {
			c.AbortWithStatus(http.StatusNoContent)
			return
		}
		c.Next()
	})

	// Routes
	api := r.Group("/api")
	api.Use(requireBearerAuth())
	api.POST("/pair", createPairingSession)
	api.GET("/pair/:code", getPairingSession)
	api.PUT("/pair/:code", updatePairingSession)
	api.GET("/device/:id/status", getDeviceStatus)

	// Health check
	r.GET("/health", func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{"status": "ok"})
	})

	r.Run(":8080")
}

func requireBearerAuth() gin.HandlerFunc {
	return func(c *gin.Context) {
		auth := c.GetHeader("Authorization")
		if len(auth) < 8 || auth[:7] != "Bearer " {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "missing bearer token"})
			return
		}

		// TODO: validate JWT/session token and check engineer permissions.
		c.Next()
	}
}

func createPairingSession(c *gin.Context) {
	var req struct {
		DeviceID string `json:"device_id"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	// Generate 6-digit code
	code, err := generateCode()
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to generate pairing code"})
		return
	}

	session := &PairingSession{
		Code:      code,
		DeviceID:  req.DeviceID,
		Status:    "pending",
		CreatedAt: time.Now(),
		ExpiresAt: time.Now().Add(pairingTTL),
	}

	sessionsMu.Lock()
	sessions[code] = session
	sessionsMu.Unlock()

	c.JSON(http.StatusCreated, session)
}

func getPairingSession(c *gin.Context) {
	code := c.Param("code")

	sessionsMu.RLock()
	session, ok := sessions[code]
	sessionsMu.RUnlock()
	if !ok {
		c.JSON(http.StatusNotFound, gin.H{"error": "session not found"})
		return
	}
	if session.Used || time.Now().After(session.ExpiresAt) {
		c.JSON(http.StatusGone, gin.H{"error": "session expired"})
		return
	}

	c.JSON(http.StatusOK, session)
}

func updatePairingSession(c *gin.Context) {
	code := c.Param("code")

	sessionsMu.Lock()
	session, ok := sessions[code]
	if !ok {
		sessionsMu.Unlock()
		c.JSON(http.StatusNotFound, gin.H{"error": "session not found"})
		return
	}
	defer sessionsMu.Unlock()

	var req struct {
		Status     string `json:"status"`
		EngineerID string `json:"engineer_id"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	if session.Used || time.Now().After(session.ExpiresAt) {
		session.Status = "expired"
		c.JSON(http.StatusGone, gin.H{"error": "session expired"})
		return
	}
	if !allowedTransitions[session.Status][req.Status] {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid status transition"})
		return
	}

	session.Status = req.Status
	if req.EngineerID != "" {
		session.EngineerID = req.EngineerID
	}
	if req.Status == "paired" {
		session.Used = true // one-time use pairing code
	}

	c.JSON(http.StatusOK, session)
}

func getDeviceStatus(c *gin.Context) {
	deviceID := c.Param("id")

	// TODO: Query from MQTT broker or database
	c.JSON(http.StatusOK, gin.H{
		"device_id":   deviceID,
		"status":      "online",
		"last_seen":   time.Now(),
	})
}

func generateCode() (string, error) {
	// Generate 6-digit code using crypto-secure random
	b := make([]byte, 4)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	code := int(binary.BigEndian.Uint32(b) % 1000000)
	return fmt.Sprintf("%06d", code), nil
}
```

安全补充（必须）：
- 对 `/api/pair`、`/api/pair/:code` 启用限流与失败惩罚（如 IP + account 维度）。
- 配对码必须一次性消费并设置 TTL；超时或失败次数超限后立即失效。
- 上述示例仅展示最小实现结构，生产环境必须接入完整认证、授权与审计链路。

**依赖**:

```bash
go get github.com/gin-gonic/gin
```

**运行**:

```bash
go run main.go
```

---

### 子任务 4D: Docker Compose 编排

**目标**: 一键启动所有服务

**创建** `server/docker-compose.yml`:

说明：以下 YAML 示例仅使用空格缩进，不可使用 Tab。

```yaml
version: '3.8'

services:
  # MQTT Broker
  emqx:
    image: emqx/emqx:5.3
    container_name: netproxy-mqtt
    ports:
      - "1883:1883"
      - "8883:8883"
      - "18083:18083"
    environment:
      EMQX_DASHBOARD__DEFAULT_USERNAME: ${EMQX_DASHBOARD_USERNAME}
      EMQX_DASHBOARD__DEFAULT_PASSWORD: ${EMQX_DASHBOARD_PASSWORD}
    volumes:
      - ./emqx-data:/opt/emqx/data
      - ./emqx-log:/opt/emqx/log
    restart: unless-stopped
    networks:
      - netproxy

  # SOCKS5 Proxy
  socks5-proxy:
    build:
      context: ./socks5-proxy
      dockerfile: Dockerfile
    container_name: netproxy-socks5
    ports:
      - "1080:1080"
    environment:
      - SOCKS5_USER=${SOCKS5_USER}
      - SOCKS5_PASS=${SOCKS5_PASS}
    restart: unless-stopped
    networks:
      - netproxy

  # PostgreSQL
  postgres:
    image: postgres:16
    container_name: netproxy-postgres
    environment:
      - POSTGRES_DB=netproxy
      - POSTGRES_USER=netproxy
      - POSTGRES_PASSWORD=${DB_PASSWORD}
    volumes:
      - ./pg-data:/var/lib/postgresql/data
    restart: unless-stopped
    networks:
      - netproxy

  # REST API
  api:
    build:
      context: ./api
      dockerfile: Dockerfile
    container_name: netproxy-api
    depends_on:
      - postgres
    ports:
      - "8080:8080"
    environment:
			# local dev only; production must use sslmode=require (or stronger)
			- DB_DSN=postgres://netproxy:${DB_PASSWORD}@postgres:5432/netproxy?sslmode=disable
    volumes:
      - ./data:/data
    restart: unless-stopped
    networks:
      - netproxy

networks:
  netproxy:
    driver: bridge
```

---

## 服务端口映射

| 服务 | 端口 | 协议 |
|------|------|------|
| MQTT Broker | 1883 | TCP |
| MQTT (TLS) | 8883 | TCP |
| MQTT WebSocket | 8083 | HTTP |
| Dashboard | 18083 | HTTP |
| SOCKS5 Proxy | 1080 | TCP |
| REST API | 8080 | HTTP |
| PostgreSQL | 5432 | TCP |

## API 端点

| 端点 | 方法 | 描述 |
|------|------|------|
| /api/pair | POST | 创建配对会话 |
| /api/pair/:code | GET | 获取配对状态 |
| /api/pair/:code | PUT | 更新配对状态 |
| /api/device/:id/status | GET | 获取设备状态 |
| /health | GET | 健康检查 |

## 验证标准

- [ ] 所有 Docker 容器正常启动
- [ ] MQTT 可以建立连接
- [ ] SOCKS5 代理正常工作
- [ ] REST API 响应正常
- [ ] 端口映射正确

## 下一步

1. 运行 `docker-compose up -d` 验证所有服务
2. 测试 MQTT 连接
3. 测试 SOCKS5 代理
4. 测试 REST API

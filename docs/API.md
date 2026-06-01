# NetProxyGateway API 文档

> 本文档描述 NetProxyGateway 项目的对外接口和内部模块接口。

---

## 目录

1. [MQTT 通信协议](#1-mqtt-通信协议)
2. [Android 客户端内部接口](#2-android-客户端内部接口)
3. [SOCKS5 协议实现](#3-socks5-协议实现)
4. [服务端 HTTP API](#4-服务端-http-api)
5. [WebSocket 隧道协议](#5-websocket-隧道协议)
6. [错误码和响应格式](#6-错误码和响应格式)

---

## 1. MQTT 通信协议

### 1.1 连接配置

**Broker URL 配置**：
- **TLS 模式** (生产环境推荐): `ssl://your-broker-address:8883`
- **非 TLS 模式** (开发测试): `tcp://your-broker-address:1883`

**客户端 ID 格式**：
```text
NetProxyGateway_{deviceId}
```

**认证方式**：
- **用户名**: `deviceId` (设备唯一标识符)
- **密码**: `authToken` (会话令牌)

**连接参数**：
```kotlin
// 来自 MqttConnectionManager.kt
private const val CLIENT_ID = "NetProxyGateway"
private const val HEARTBEAT_INTERVAL = 30000L  // 30 秒
private const val CONNECTION_TIMEOUT_SECONDS = 10
private const val MAX_RECONNECT_DELAY = 60000L // 60 秒
```

### 1.2 Topic 定义和命名规范

| Topic 模式 | 方向 | QoS | 说明 |
|-----------|------|-----|------|
| `device/{deviceId}/control` | 订阅 | 0 | 设备控制指令 |
| `device/{deviceId}/heartbeat` | 发布 | 0 | 设备心跳 |
| `device/{deviceId}/status` | 发布 | 0 | 设备状态上报 |

**Topic 命名规范**：
- 使用前缀 `device/` 标识设备相关主题
- 使用 `{deviceId}` 作为设备唯一标识符占位符
- 使用 `/` 分隔层级：`{资源类型}/{资源标识}/{消息类型}`

### 1.3 消息格式（JSON 结构）

#### 心跳消息
```json
{
  "status": "alive"
}
```

**发布频率**: 每 30 秒一次

**发布 Topic**: `device/{deviceId}/heartbeat`

#### 控制指令消息（待扩展）
```json
{
  "command": "string",
  "params": {},
  "timestamp": "long"
}
```

**订阅 Topic**: `device/{deviceId}/control`

### 1.4 认证流程

```text
1. Android 客户端 → API 服务：请求配对码
   POST /api/pair
   Body: { "device_id": "device-uuid" }
   Header: Authorization: Bearer {engineer_jwt_token}

2. API 服务 → Android 客户端：返回配对码
   Response: { 
     "code": "123456",
     "device_id": "device-uuid",
     "status": "pending",
     "expires_at": "timestamp"
   }

3. 工程师 → API 服务：确认配对
   PUT /api/pair/123456
   Body: { 
     "status": "connected",
     "engineer_id": "engineer-id"
   }

4. Android 客户端 → API 服务：获取会话令牌
   POST /api/session/token
   Body: { 
     "code": "123456",
     "engineer_id": "engineer-id"
   }

5. API 服务 → Android 客户端：返回会话令牌
   Response: {
     "token": "random-32-char-string",
     "device_id": "device-uuid",
     "engineer_id": "engineer-id",
     "expires_at": "timestamp"
   }

6. Android 客户端 → MQTT Broker：使用令牌连接
   ClientId: NetProxyGateway_{deviceId}
   UserName: {deviceId}
   Password: {authToken}
```

### 1.5 QoS 配置

| 消息类型 | QoS | 说明 |
|---------|-----|------|
| 心跳 | 0 | 最多一次，允许丢失 |
| 控制指令 | 0 | 最多一次，实时性要求高 |
| 状态上报 | 0 | 最多一次，定期更新 |

**代码参考**：
```kotlin
// MqttConnectionManager.kt
fun publish(topic: String, payload: String, qos: Int = 0)
fun subscribe(topic: String, qos: Int = 0, callback: ((String) -> Unit)? = null)
```

### 1.6 TLS 安全配置

**证书验证策略**：
- **Release 构建**: 使用系统默认 CA 证书 + 可选的公钥固定（通过 `MQTT_TLS_PUBLIC_KEY_PINS` 配置）
- **Debug 构建**: 支持信任所有证书（仅用于开发测试自签名证书，`MQTT_TRUST_ALL_CERTS=true`）

**安全限制**：
```kotlin
// 禁止在 release 构建中使用信任所有证书
if (!BuildConfig.DEBUG && BuildConfig.MQTT_TRUST_ALL_CERTS) {
    throw IllegalStateException(
        "TRUST_ALL_CERTS is not allowed in release builds."
    )
}
```

---

## 2. Android 客户端内部接口

### 2.1 模块架构

采用 **CQRS（Command Query Responsibility Segregation）** 模式，每个模块拆分为：
- **CommandHandler**: 负责写操作（修改状态）
- **QueryHandler**: 负责读操作（查询状态）

### 2.2 Core 模块

**接口定义**: [`ModuleInterfaces.kt`](android/app/src/main/java/com/netproxy/gateway/di/ModuleInterfaces.kt#L22-L32)

```kotlin
interface CoreQueryHandler {
    fun getNetworkState(): StateFlow<NetworkState>
    fun getCoroutineScope(): CoroutineScope
}
```

**数据类**:
```kotlin
sealed class NetworkState {
    // 网络状态信息
}
```

### 2.3 Communication 模块

**接口定义**: [`ModuleInterfaces.kt`](android/app/src/main/java/com/netproxy/gateway/di/ModuleInterfaces.kt#L39-L64)

```kotlin
// 命令处理器
interface CommunicationCommandHandler {
    fun connect(deviceId: String, authToken: String): Boolean
    fun publish(topic: String, payload: String, qos: Int)
    fun subscribe(topic: String, qos: Int, callback: ((String) -> Unit)?)
    fun disconnect()
}

// 查询处理器
interface CommunicationQueryHandler {
    fun getConnectionState(): StateFlow<MqttConnectionState>
}
```

**连接状态类型**:
```kotlin
sealed class MqttConnectionState {
    object Disconnected : MqttConnectionState()
    object Connecting : MqttConnectionState()
    object Connected : MqttConnectionState()
    data class Error(val message: String) : MqttConnectionState()
}
```

**实现类**: [`MqttConnectionManager.kt`](android/app/src/main/java/com/netproxy/gateway/connection/MqttConnectionManager.kt#L45-L316)

**关键方法**:
- `connect(deviceId: String, authToken: String)`: 建立 MQTT 连接
- `publish(topic: String, payload: String, qos: Int = 0)`: 发布消息
- `subscribe(topic: String, qos: Int = 0, callback: ((String) -> Unit)?)`: 订阅主题
- `disconnect()`: 断开连接

### 2.4 Network 模块

**接口定义**: [`ModuleInterfaces.kt`](android/app/src/main/java/com/netproxy/gateway/di/ModuleInterfaces.kt#L71-L98)

```kotlin
// 命令处理器
interface NetworkCommandHandler {
    fun startVpn(): Boolean
    fun stopVpn(): Boolean
    fun startProxy(): Boolean
    fun stopProxy(): Boolean
}

// 查询处理器
interface NetworkQueryHandler {
    fun getVpnStatus(): StateFlow<VpnStatus>
    fun getProxyStatus(): StateFlow<Boolean>
}
```

**VPN 状态类型**:
```kotlin
data class VpnStatus(
    val state: VpnState = VpnState.STOPPED,
    // 其他状态字段
)

enum class VpnState {
    STOPPED,
    STARTING,
    RUNNING,
    STOPPING,
    ERROR
}
```

### 2.5 WiFi 模块

**接口定义**: [`ModuleInterfaces.kt`](android/app/src/main/java/com/netproxy/gateway/di/ModuleInterfaces.kt#L105-L130)

```kotlin
// 命令处理器
interface WiFiCommandHandler {
    fun scanWiFi(): List<WifiNetwork>
    fun connectToWiFi(ssid: String, password: String?, securityType: String): Boolean
    fun disconnectWiFi(): Boolean
}

// 查询处理器
interface WiFiQueryHandler {
    fun getCurrentConnection(): WifiConnectionInfo?
    fun getWifiNetworks(): StateFlow<List<WifiNetwork>>
}
```

**数据类**:
```kotlin
data class WifiNetwork(
    val ssid: String,
    val bssid: String,
    val securityType: String,
    val signalStrength: Int,
    val frequency: Int
)

data class WifiConnectionInfo(
    val ssid: String,
    val bssid: String,
    val ipAddress: String,
    val macAddress: String,
    val connectionSpeed: Int
)
```

### 2.6 Config 模块

**接口定义**: [`ModuleInterfaces.kt`](android/app/src/main/java/com/netproxy/gateway/di/ModuleInterfaces.kt#L163-L190)

```kotlin
// 命令处理器
interface ConfigCommandHandler {
    fun setDeviceId(deviceId: String)
    fun setAuthToken(token: String)
    fun requestPermissions(permissions: List<String>): Boolean
}

// 查询处理器
interface ConfigQueryHandler {
    fun getDeviceId(): String
    fun getAuthToken(): String
    fun checkPermissions(): List<String>
}
```

### 2.7 认证会话管理

**实现类**: [`AuthSessionStore.kt`](android/app/src/main/java/com/netproxy/gateway/connection/AuthSessionStore.kt#L17-L201)

```kotlin
@Singleton
class AuthSessionStore @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val appScope: CoroutineScope
) {
    // 更新会话
    fun update(deviceId: String, authToken: String)
    fun updateWithResult(deviceId: String, authToken: String): AppResult<Unit>
    
    // 清除会话
    fun clear()
    fun clearWithResult(): AppResult<Unit>
    
    // 获取设备 ID
    fun getOrCreateDeviceId(): String
    
    // 验证会话（用于 SOCKS5 代理认证）
    fun isValid(username: String, password: String): Boolean
    fun validateWithResult(username: String, password: String): AppResult<Boolean>
    
    // 获取当前会话
    fun getCurrentSession(): ProxyAuthSession?
    fun getCurrentSessionWithResult(): AppResult<ProxyAuthSession>
}

// 会话数据类
data class ProxyAuthSession(
    val deviceId: String,
    val authToken: String
)
```

**存储方式**: 使用 Android EncryptedSharedPreferences 加密存储

### 2.8 统一返回类型

**AppResult 类型**: [`AppResult.kt`](android/app/src/main/java/com/netproxy/gateway/result/AppResult.kt)

```kotlin
sealed class AppResult<out T> {
    data class Success<out T>(val data: T) : AppResult<T>()
    data class Error(val exception: Throwable) : AppResult<T>()
    
    companion object {
        fun <T> success(data: T): AppResult<T>
        fun <T> error(exception: Throwable): AppResult<T>
    }
}
```

---

## 3. SOCKS5 协议实现

### 3.1 服务端配置

**监听地址**: `0.0.0.0:1080` (可通过 `SOCKS5_ADDR` 环境变量修改)

**TLS 支持**: 可选，通过配置启用
```bash
SOCKS5_ENABLE_TLS=true
SOCKS5_TLS_CERT=/path/to/cert.crt
SOCKS5_TLS_KEY=/path/to/key.key
```

### 3.2 支持的认证方式

| 方法编号 | 认证方法 | 说明 |
|---------|---------|------|
| 0x00 | NO AUTHENTICATION REQUIRED | 无认证（不支持） |
| 0x02 | USERNAME/PASSWORD | 用户名/密码认证（**已支持**） |

**认证流程**：
```text
客户端 → 服务端：方法协商
  [0x05, 0x01, 0x02]
  版本=5, 方法数=1, 方法=0x02(用户名/密码)

服务端 → 客户端：选择认证方法
  [0x05, 0x02]
  版本=5, 方法=0x02

客户端 → 服务端：认证凭证
  [0x01, ULEN, UNAME..., PLEN, PASSWD...]
  版本=1, 用户名长度，用户名，密码长度，密码

服务端 → 客户端：认证结果
  [0x01, 0x00]  // 成功
  [0x01, 0x01]  // 失败
```

**凭证验证**：
- **用户名**: `deviceId` (设备唯一标识符)
- **密码**: `authToken` (会话令牌)
- **验证方式**: 通过 API 服务调用 `/api/session/validate` 验证

### 3.3 地址类型支持

| ATYP 编号 | 地址类型 | 支持状态 |
|----------|---------|---------|
| 0x01 | IPv4 | ✅ 已支持 |
| 0x03 | Domain Name (域名) | ✅ 已支持 |
| 0x04 | IPv6 | ❌ 不支持 |

**域名解析**：
- 服务端自动解析域名为 IPv4 地址
- 使用 `net.LookupIP()` 进行 DNS 查询
- 仅使用第一个 IPv4 地址

### 3.4 命令支持

| CMD 编号 | 命令 | 支持状态 |
|---------|------|---------|
| 0x01 | CONNECT | ✅ 已支持 |
| 0x02 | BIND | ❌ 不支持 |
| 0x03 | UDP ASSOCIATE | ❌ 不支持 |

### 3.5 CONNECT 命令流程

**请求格式**：
```text
+----+-----+-------+------+----------+----------+
|VER | CMD |  RSV  | ATYP | DST.ADDR | DST.PORT |
+----+-----+-------+------+----------+----------+
| 5  |  1  | X'00' |  1   | Variable |    2     |
+----+-----+-------+------+----------+----------+
```

**响应格式**：
```text
+----+-----+-------+------+----------+----------+
|VER | REP |  RSV  | ATYP | BND.ADDR | BND.PORT |
+----+-----+-------+------+----------+----------+
| 5  |  X  | X'00' |  1   | Variable |    2     |
+----+-----+-------+------+----------+----------+
```

**REP 响应码**：
| 值 | 说明 |
|---|------|
| 0x00 | 成功 |
| 0x02 | 连接不允许（目标 IP 被过滤） |
| 0x04 | 主机不可达 |
| 0x05 | 连接被拒绝 |
| 0x07 | 命令不支持 |
| 0x08 | 地址类型不支持 |

### 3.6 IP 过滤规则

**允许的地址范围**（RFC1918 私有地址）：
- `10.0.0.0/8`
- `172.16.0.0/12`
- `192.168.0.0/16`

**拒绝的地址**：
- 回环地址：`127.0.0.0/8`
- 链路本地：`169.254.0.0/16`
- 广播地址：`0.0.0.0`, `255.255.255.255`
- 组播地址：`224.0.0.0/4`
- IPv6 地址（暂不支持）

### 3.7 连接池实现

**实现类**: [`Socks5ConnectionPool.kt`](android/app/src/main/java/com/netproxy/gateway/proxy/Socks5ConnectionPool.kt#L69-L432)

**配置参数**：
```kotlin
data class Socks5ConnectionPoolConfig(
    val maxConnections: Int = 50,              // 最大连接数
    val idleTimeoutMs: Long = 60_000L,         // 空闲超时 (60 秒)
    val connectionTimeoutMs: Int = 5_000,      // 连接超时 (5 秒)
    val socketSoTimeoutMs: Int = 30_000,       // Socket 读写超时 (30 秒)
    val minIdleConnections: Int = 5,           // 最小空闲连接数
    val maxConnectionsPerDestination: Int = 10, // 每目标地址最大连接数
    val cleanupIntervalMs: Long = 30_000L      // 清理间隔 (30 秒)
)
```

**核心方法**：
```kotlin
// 获取或创建连接
fun borrowConnection(
    destinationIp: String,
    destinationPort: Int,
    protectSocket: ((Socket) -> Unit)? = null
): PooledSocks5Connection?

// 归还连接
fun returnConnection(connection: PooledSocks5Connection)

// 获取统计信息
fun getStats(): ConnectionPoolStats
```

### 3.8 Android SOCKS5 代理实现

**实现类**: [`Socks5ProxyHandler.kt`](android/app/src/main/java/com/netproxy/gateway/proxy/Socks5ProxyHandler.kt#L19-L234)

**基于 Netty 框架**：
- 使用 `io.netty.handler.codec.socksx.v5` 包
- 支持 SOCKS5 初始化和密码认证
- 仅支持 CONNECT 命令
- 自动拒绝非私有 IP 地址

**认证验证器**：
```kotlin
class Socks5ProxyHandler(
    private val connector: OutboundConnector = NettyOutboundConnector(),
    private val credentialValidator: (String, String) -> Boolean = { _, _ -> false }
)
```

---

## 4. 服务端 HTTP API

### 4.1 基础信息

**服务地址**: `http://localhost:8080` (默认)

**环境变量**：
```bash
PORT=8080                    # 服务端口
JWT_SECRET=your-secret       # JWT 签名密钥
ADMIN_USER=admin             # 管理员用户名
ADMIN_PASS=changeme          # 管理员密码
INTERNAL_API_KEY=key         # 内部 API 密钥
DB_PATH=./api.db            # SQLite 数据库路径
```

### 4.2 公开接口

#### 4.2.1 健康检查

**请求**：
```http
GET /health
```

**响应**：
```json
{
  "status": "ok",
  "db_status": "ok",
  "time": "2026-04-03T12:00:00Z"
}
```

#### 4.2.2 工程师登录

**请求**：
```http
POST /api/login
Content-Type: application/json

{
  "username": "admin",
  "password": "password"
}
```

**响应**：
```json
{
  "token": "jwt-token-string",
  "type": "Bearer"
}
```

**限流策略**：
- 最大失败尝试次数：5 次
- 封禁时长：15 分钟
- 计数重置时间：5 分钟

### 4.3 认证接口

**认证方式**：
```http
Authorization: Bearer {jwt_token}
```

**JWT Token 内容**：
```json
{
  "sub": "engineer_id",
  "role": "engineer",
  "iat": 1712131200,
  "exp": 1712217600
}
```

**有效期**: 24 小时

### 4.4 配对会话管理

#### 4.4.1 创建配对会话

**请求**：
```http
POST /api/pair
Authorization: Bearer {jwt_token}
Content-Type: application/json

{
  "device_id": "device-uuid"
}
```

**响应**：
```json
{
  "code": "123456",
  "device_id": "device-uuid",
  "status": "pending",
  "engineer_id": "",
  "created_at": 1712131200,
  "expires_at": 1712132100,
  "used": false
}
```

**参数说明**：
- `code`: 6 位数字配对码
- `status`: 状态（`pending`/`connected`/`disconnected`/`expired`）
- `expires_at`: 过期时间（15 分钟后）

#### 4.4.2 获取配对会话

**请求**：
```http
GET /api/pair/:code
```

**响应**：
```json
{
  "code": "123456",
  "device_id": "device-uuid",
  "status": "pending",
  "engineer_id": "",
  "created_at": 1712131200,
  "expires_at": 1712132100,
  "used": false
}
```

#### 4.4.3 更新配对会话

**请求**：
```http
PUT /api/pair/:code
Authorization: Bearer {jwt_token}
Content-Type: application/json

{
  "status": "connected",
  "engineer_id": "engineer-uuid"
}
```

**有效状态转换**：
```text
pending → connected | expired
connected → disconnected | expired
disconnected → (无)
expired → (无)
```

### 4.5 会话令牌管理

#### 4.5.1 创建会话令牌

**请求**：
```http
POST /api/session/token
Authorization: Bearer {jwt_token}
Content-Type: application/json

{
  "code": "123456",
  "engineer_id": "engineer-uuid"
}
```

**响应**：
```json
{
  "token": "random-32-char-string",
  "device_id": "device-uuid",
  "engineer_id": "engineer-uuid",
  "created_at": 1712131200,
  "expires_at": 1712132100
}
```

**有效期**: 15 分钟

#### 4.5.2 验证会话令牌（内部 API）

**请求**：
```http
POST /api/session/validate
Content-Type: application/json

{
  "device_id": "device-uuid",
  "token": "session-token"
}
```

**响应**：
```json
{
  "valid": true
}
```

**用途**: SOCKS5 服务和隧道服务验证设备凭证

### 4.6 设备状态管理

#### 4.6.1 获取设备状态

**请求**：
```http
GET /api/device/:id/status
Authorization: Bearer {jwt_token}
```

**响应**：
```json
{
  "device_id": "device-uuid",
  "status": "online",
  "last_seen": "2026-04-03T12:00:00Z",
  "tunnel_addr": "ws://device-ip:8443"
}
```

**状态值**：
- `online`: 在线
- `offline`: 离线

#### 4.6.2 更新设备状态（内部 API）

**请求**：
```http
POST /api/device/status
Content-Type: application/json
X-Internal-API-Key: {internal_api_key}  // 可选，可用 JWT Token 替代

{
  "device_id": "device-uuid",
  "status": "online",
  "tunnel_addr": "ws://device-ip:8443"
}
```

**响应**：
```json
{
  "status": "updated"
}
```

---

## 5. WebSocket 隧道协议

### 5.1 基础信息

**服务地址**: `ws://localhost:8443/tunnel` (默认)

**环境变量**：
```bash
TUNNEL_ADDR=0.0.0.0:8443           # 隧道服务端口
API_ENDPOINT=http://localhost:8080 # API 服务地址
INTERNAL_API_KEY=key               # 内部 API 密钥
```

**TLS 支持**: 可选
```bash
TUNNEL_ENABLE_TLS=true
TUNNEL_TLS_CERT=/path/to/cert.crt
TUNNEL_TLS_KEY=/path/to/key.key
```

### 5.2 连接建立

**WebSocket URL 格式**：
```text
ws://{tunnel_endpoint}/tunnel?device_id={device_id}&token={session_token}
```

**查询参数**：
- `device_id`: 设备唯一标识符
- `token`: 会话令牌（通过 `/api/session/token` 获取）

**认证流程**：
1. 客户端发起 WebSocket 连接请求
2. 服务端调用 API 服务 `/api/session/validate` 验证凭证
3. 验证失败：返回 HTTP 401
4. 验证成功：升级为 WebSocket 连接

### 5.3 消息格式

所有消息使用 JSON 格式，通过 WebSocket Binary Message 传输。

**通用消息结构**：
```json
{
  "type": "message_type",
  "data": {}
}
```

### 5.4 客户端 → 服务端消息

#### 5.4.1 连接请求

**消息类型**: `connect`

**消息内容**：
```json
{
  "type": "connect",
  "data": {
    "stream_id": "unique-stream-id",
    "address": "192.168.1.100",
    "port": 8080
  }
}
```

**字段说明**：
- `stream_id`: 唯一流标识符（格式：`{device_id}-{timestamp_nano}`）
- `address`: 目标地址（IPv4 或域名）
- `port`: 目标端口

#### 5.4.2 数据传输

**消息类型**: `data`

**消息内容**：
```json
{
  "type": "data",
  "data": {
    "stream_id": "unique-stream-id",
    "data": "base64_encoded_binary_data"
  }
}
```

#### 5.4.3 断开连接

**消息类型**: `disconnect`

**消息内容**：
```json
{
  "type": "disconnect",
  "data": {
    "stream_id": "unique-stream-id"
  }
}
```

### 5.5 服务端 → 客户端消息

#### 5.5.1 连接响应

**消息类型**: `connect_response`

**消息内容**：
```json
{
  "type": "connect_response",
  "data": {
    "stream_id": "unique-stream-id",
    "success": true,
    "error": ""  // 仅在 success=false 时存在
  }
}
```

#### 5.5.2 数据传输

**消息类型**: `data`

**消息内容**：
```json
{
  "type": "data",
  "data": {
    "stream_id": "unique-stream-id",
    "data": "base64_encoded_binary_data"
  }
}
```

#### 5.5.3 断开连接

**消息类型**: `disconnect`

**消息内容**：
```json
{
  "type": "disconnect",
  "data": {
    "stream_id": "unique-stream-id"
  }
}
```

### 5.6 心跳机制

**心跳间隔**: 30 秒

**心跳超时**: 90 秒

**实现方式**：
- 服务端定期发送 WebSocket Ping 控制帧
- 客户端自动响应 Pong
- 服务端更新 `LastPing` 时间戳
- 超过 90 秒未收到 Pong 则关闭连接

**清理机制**：
- 定期清理死连接（每 30 秒）
- 通知 API 服务设备离线

### 5.7 流连接管理

**流 ID 格式**：
```text
{device_id}-{timestamp_nano}
```
示例：`device-uuid-1712131200000000000`

**连接复用**：
- 同一设备的多个流共享一个 WebSocket 连接
- 服务端维护 `device_id → WebSocket.Conn` 映射
- 服务端维护 `stream_id → StreamConn` 映射

**StreamConn 接口**：
```go
// 实现 net.Conn 接口
type StreamConn struct {
    StreamID    string
    DeviceID    string
    TunnelConn  *websocket.Conn
    DataChan    chan []byte
    CloseChan   chan struct{}
    Connected   chan bool
}

func (s *StreamConn) Read(p []byte) (n int, err error)
func (s *StreamConn) Write(p []byte) (n int, err error)
func (s *StreamConn) Close() error
```

---

## 6. 错误码和响应格式

### 6.1 HTTP 错误码

| 状态码 | 说明 | 常见场景 |
|-------|------|---------|
| 200 OK | 请求成功 | 正常响应 |
| 201 Created | 资源创建成功 | 创建配对会话、会话令牌 |
| 400 Bad Request | 请求参数错误 | 缺少必填字段、JSON 格式错误 |
| 401 Unauthorized | 认证失败 | 缺少 Token、Token 无效、凭证错误 |
| 403 Forbidden | 授权失败 | 权限不足、工程师 ID 不匹配 |
| 404 Not Found | 资源不存在 | 配对会话不存在 |
| 410 Gone | 资源已过期 | 配对会话过期 |
| 429 Too Many Requests | 请求频率超限 | 登录限流、配对码申请限流 |
| 500 Internal Server Error | 服务器内部错误 | 数据库错误、未知异常 |

### 6.2 错误响应格式

**通用错误响应**：
```json
{
  "error": "错误描述信息"
}
```

**示例**：
```json
// 400 Bad Request
{
  "error": "device_id is required"
}

// 401 Unauthorized
{
  "error": "missing or invalid bearer token"
}

// 403 Forbidden
{
  "error": "unauthorized"
}

// 404 Not Found
{
  "error": "session not found"
}

// 410 Gone
{
  "error": "session expired"
}

// 429 Too Many Requests
{
  "error": "rate limit exceeded, please try again later"
}

// 500 Internal Server Error
{
  "error": "database error"
}
```

### 6.3 MQTT 错误处理

**连接错误**：
```kotlin
sealed class MqttConnectionState {
    object Disconnected : MqttConnectionState()
    object Connecting : MqttConnectionState()
    object Connected : MqttConnectionState()
    data class Error(val message: String) : MqttConnectionState()
}
```

**错误场景**：
- 网络不可达
- Broker 地址配置错误
- 认证失败（用户名/密码错误）
- TLS 证书验证失败
- 连接超时

**重连机制**：
- 初始重连延迟：5 秒
- 最大重连延迟：60 秒
- 退避策略：指数退避（每次翻倍）

### 6.4 SOCKS5 错误码

**REP 响应码**：
| 值 | 说明 | 触发场景 |
|---|------|---------|
| 0x00 | 成功 | 连接建立成功 |
| 0x02 | 连接不允许 | 目标 IP 不在白名单 |
| 0x04 | 主机不可达 | DNS 解析失败、目标不可达 |
| 0x05 | 连接被拒绝 | 隧道连接失败 |
| 0x07 | 命令不支持 | BIND/UDP ASSOCIATE 请求 |
| 0x08 | 地址类型不支持 | IPv6 地址 |

### 6.5 Android AppResult 错误处理

**统一返回类型**：
```kotlin
sealed class AppResult<out T> {
    data class Success<out T>(val data: T) : AppResult<T>()
    data class Error(val exception: Throwable) : AppResult<T>()
}
```

**常见错误类型**：
- `IllegalStateException`: 状态不正确（如 MQTT 未连接时发布消息）
- `SecurityException`: 权限不足
- `IOException`: 网络错误
- `JSONException`: JSON 解析错误

**使用示例**：
```kotlin
fun publishWithResult(topic: String, payload: String, qos: Int = 0): AppResult<Unit> {
    return try {
        val client = mqttClient 
            ?: return AppResult.error(IllegalStateException("MQTT client is not connected"))
        val message = MqttMessage(payload.toByteArray()).apply {
            this.qos = qos
        }
        client.publish(topic, message)
        AppResult.success(Unit)
    } catch (e: MqttException) {
        logger.error("Publish error: ${e.message}")
        AppResult.error(e)
    }
}
```

---

## 附录

### A. 配置文件示例

**服务端环境变量** (`.env`):
```bash
# API 服务配置
JWT_SECRET=your-secret-key-here
ADMIN_USER=admin
ADMIN_PASS=changeme-in-production
INTERNAL_API_KEY=replace-with-shared-internal-key
PORT=8080
DB_PATH=./api.db

# TLS 配置（可选）
ENABLE_TLS=true
TLS_CERT=/app/certs/server.crt
TLS_KEY=/app/certs/server.key

# SOCKS5 代理配置
SOCKS5_ADDR=0.0.0.0:1080
SOCKS5_ENABLE_TLS=true
SOCKS5_TLS_CERT=/app/certs/server.crt
SOCKS5_TLS_KEY=/app/certs/server.key

# 隧道服务配置
TUNNEL_ADDR=0.0.0.0:8443
TUNNEL_ENABLE_TLS=true
TUNNEL_TLS_CERT=/app/certs/server.crt
TUNNEL_TLS_KEY=/app/certs/server.key

# MQTT Broker 配置
EMQX_DASHBOARD_USERNAME=admin
EMQX_DASHBOARD_PASSWORD=public
```

**Android BuildConfig 配置**:
```kotlin
// 在 build.gradle.kts 中配置
buildConfigField("String", "MQTT_BROKER_URL_TLS", "\"ssl://broker.example.com:8883\"")
buildConfigField("String", "MQTT_BROKER_URL_PLAIN", "\"tcp://broker.example.com:1883\"")
buildConfigField("boolean", "MQTT_USE_TLS", "true")
buildConfigField("boolean", "MQTT_TRUST_ALL_CERTS", "false") // release 必须为 false
buildConfigField("String", "MQTT_TLS_PUBLIC_KEY_PINS", "\"\"") // 可选的公钥固定
```

### B. 依赖服务

| 服务 | 说明 | 端口 |
|------|------|------|
| EMQX MQTT Broker | MQTT 消息代理 | 1883 (TCP), 8883 (TLS) |
| API 服务 | RESTful HTTP API | 8080 |
| SOCKS5 代理 | SOCKS5 代理服务 | 1080 |
| 隧道服务 | WebSocket 隧道 | 8443 |

### C. 安全建议

1. **生产环境必须启用 TLS**:
   - API 服务启用 HTTPS
   - SOCKS5 代理启用 TLS
   - 隧道服务启用 WSS
   - MQTT 使用 SSL/TLS

2. **密钥管理**:
   - 使用强随机 JWT_SECRET（至少 32 字节）
   - 定期轮换密钥
   - 不要将密钥提交到版本控制

3. **证书管理**:
   - 使用 Let's Encrypt 或商业 CA 证书
   - 自签名证书仅用于开发测试
   - 实施证书固定（公钥固定）

4. **访问控制**:
   - 限制管理员账户访问
   - 使用强密码策略
   - 实施登录限流

---

*最后更新：2026-04-03*

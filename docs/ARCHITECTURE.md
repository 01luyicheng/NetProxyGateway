# NetProxyGateway 系统架构文档

> 本文档描述 NetProxyGateway 项目的整体架构设计、组件职责和数据流。

---

## 目录

1. [系统概述](#1-系统概述)
2. [系统架构图](#2-系统架构图)
3. [Android 客户端架构](#3-android-客户端架构)
4. [Go 服务端架构](#4-go-服务端架构)
5. [数据流图](#5-数据流图)
6. [关键设计模式](#6-关键设计模式)
7. [模块依赖关系](#7-模块依赖关系)
8. [技术栈评估](#8-技术栈评估)

---

## 1. 系统概述

### 1.1 项目定位

NetProxyGateway 是面向**远程网络协助场景**的代理网关系统，允许工程师通过云服务器安全地访问用户设备所在的内网资源。

### 1.2 核心场景

```text
工程师设备 → 云服务器 → 用户手机 (VPN) → 内网设备
```

### 1.3 设计目标

- **安全性**: 所有通信通道加密（TLS 1.2+），双向认证
- **透明性**: 内网设备无需感知外部访问
- **可控性**: 细粒度的流量路由和访问控制
- **可靠性**: 断线重连、心跳检测、连接池管理

---

## 2. 系统架构图

### 2.1 整体架构（逻辑视图）

```text
┌─────────────────────────────────────────────────────────────────────────┐
│                            云服务器 (Cloud)                              │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐                  │
│  │  REST API    │  │  SOCKS5      │  │   Tunnel     │                  │
│  │  Service     │  │  Proxy       │  │   Gateway    │                  │
│  │  (8080)      │  │  (1080)      │  │   (8443)     │                  │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘                  │
│         │                 │                 │                           │
│         └─────────────────┴─────────────────┘                           │
│                           │                                             │
│                  ┌────────▼────────┐                                    │
│                  │   EMQX MQTT     │                                    │
│                  │   Broker        │                                    │
│                  │   (1883/8883)   │                                    │
│                  └────────┬────────┘                                    │
└───────────────────────────┼─────────────────────────────────────────────┘
                            │ 互联网
                            │
         ┌──────────────────┼──────────────────┐
         │                  │                  │
         │         ┌────────▼────────┐         │
         │         │  工程师客户端   │         │
         │         │  (SOCKS5)      │         │
         │         └─────────────────┘         │
         │                                     │
         │         ┌─────────────────┐         │
         │         │   用户手机      │         │
         │         │  (Android)      │         │
         │         │  ┌───────────┐  │         │
         │         │  │   VPN     │  │         │
         │         │  │  Service  │  │         │
         │         │  └─────┬─────┘  │         │
         │         │        │        │         │
         │         │  ┌─────▼─────┐  │         │
         │         │  │  SOCKS5   │  │         │
         │         │  │  Proxy    │  │         │
         │         │  │ (127.0.0.1│  │         │
         │         │  │  :1080)   │  │         │
         │         │  └─────┬─────┘  │         │
         │         │        │        │         │
         │         │  ┌─────▼─────┐  │         │
         │         │  │    WiFi   │  │         │
         │         │  │  Manager  │  │         │
         │         │  └─────┬─────┘  │         │
         │         └────────┼────────┘         │
         │                  │                  │
         │         ┌────────▼────────┐         │
         │         │   内网设备      │         │
         │         │  (192.168.x.x)  │         │
         │         │  (10.0.x.x)     │         │
         │         └─────────────────┘         │
         │                                     │
└─────────────────────────────────────────────┘
```

### 2.2 网络拓扑（物理视图）

```text
┌────────────────────────────────────────────────────────────────────┐
│  互联网                                                             │
│                                                                    │
│  ┌──────────────┐                      ┌──────────────┐            │
│  │  工程师设备  │                      │   云服务器   │            │
│  │  - PC/Mac   │◄──── SOCKS5 over ───►│   - API      │            │
│  │  - 浏览器   │      TLS (1080)      │   - Tunnel   │            │
│  └──────────────┘                      └──────┬───────┘            │
│                                               │                    │
│                                        WebSocket│ (wss://8443)     │
│                                               │                    │
│  ┌──────────────────────────────────────────────▼───────┐          │
│  │              用户侧 (移动网络)                        │          │
│  │                                                       │          │
│  │  ┌─────────────────────────────────────────────────┐ │          │
│  │  │             用户手机 (Android)                   │ │          │
│  │  │  ┌───────────────────────────────────────────┐  │ │          │
│  │  │  │          蜂窝数据网络                      │  │ │          │
│  │  │  │  ┌────────────┐  ┌────────────────────┐   │  │ │          │
│  │  │  │  │    MQTT    │  │   VPN Service      │   │  │ │          │
│  │  │  │  │ Connection │  │   - TUN Device     │   │  │ │          │
│  │  │  │  │ (4G/5G)    │  │   - Traffic Router │   │  │ │          │
│  │  │  │  └────────────┘  └─────────┬──────────┘   │  │ │          │
│  │  │  │                            │              │  │ │          │
│  │  │  │                    ┌───────▼────────┐    │  │ │          │
│  │  │  │                    │ SOCKS5 Proxy   │    │  │ │          │
│  │  │  │                    │ (Local:1080)   │    │  │ │          │
│  │  │  │                    └────────────────┘    │  │ │          │
│  │  │  └───────────────────────────────────────────┘  │          │
│  │  │                          │                       │          │
│  │  │                    ┌─────▼──────┐               │          │
│  │  │                    │ WiFi NIC   │               │          │
│  │  │                    └─────┬──────┘               │          │
│  │  └────────────────────────────┼────────────────────┘          │
│  │                               │                                │
│  │                    ┌──────────▼──────────┐                    │
│  │                    │   内网设备           │                    │
│  │                    │   - 路由器          │                    │
│  │                    │   - IoT 设备        │                    │
│  │                    │   - 打印机          │                    │
│  │                    └─────────────────────┘                    │
│  └────────────────────────────────────────────────────────────────┘
└────────────────────────────────────────────────────────────────────┘
```

### 2.3 流量路由决策

```text
                        VPN TUN 设备
                             │
                    ┌────────▼────────┐
                    │   解析 IP 包     │
                    │  获取 dst:port  │
                    └────────┬────────┘
                             │
              ┌──────────────┼──────────────┐
              │              │              │
              │              │              │
    ┌─────────▼────────┐    │    ┌────────▼────────┐
    │  目标端口=53     │    │    │  目标 IP 是      │
    │  DNS 查询        │    │    │  内网 IP?       │
    │                  │    │    │                 │
    │  ┌────────────┐  │    │    │   ┌──────────┐  │
    │  │ WiFi DNS   │  │    │    │   │ YES      │  │
    │  │ protect()  │  │    │    │   │ ┌──────┐ │  │
    │  │ Datagram   │  │    │    │   │ │WiFi  │ │  │
    │  │ Socket     │  │    │    │   │ │直连  │ │  │
    │  └────────────┘  │    │    │   │ │protect│ │  │
    │                  │    │    │   │ └──────┘ │  │
    └──────────────────┘    │    │   └──────────┘  │
                            │    │                 │
                            │    │   NO            │
                            │    │   ┌──────────┐  │
                            │    │   │ 外网流量 │  │
                            │    │   │          │  │
                            │    │   │ ┌──────┐ │  │
                            │    │   │ │本地  │ │  │
                            │    │   │ │SOCKS5│ │  │
                            │    │   │ │代理  │ │  │
                            │    │   │ └──────┘ │  │
                            │    │   └──────────┘  │
                            │    └─────────────────┘
                            │
                  ┌─────────▼─────────┐
                  │   云服务器 IP?    │
                  │   (配置排除路由)  │
                  │                   │
                  │   ┌──────────┐    │
                  │   │ YES      │    │
                  │   │ 忽略包   │    │
                  │   │ (系统路由│    │
                  │   │  到蜂窝) │    │
                  │   └──────────┘    │
                  └───────────────────┘
```

---

## 3. Android 客户端架构

### 3.1 组件分层

```text
┌─────────────────────────────────────────────────────────────┐
│                        UI 层 (Jetpack Compose)               │
│  ┌──────────────────────────────────────────────────────┐  │
│  │  MainActivity                                        │  │
│  │  └─ MainViewModel (状态管理)                         │  │
│  └──────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                     依赖注入层 (Hilt)                        │
│  ┌──────────────────────────────────────────────────────┐  │
│  │  AppModule.kt                                        │  │
│  │  ModuleInterfaces.kt (CQRS 接口定义)                  │  │
│  │  ModuleImplementations.kt (实现绑定)                  │  │
│  └──────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                      业务模块层                              │
│  ┌────────────┐ ┌────────────┐ ┌────────────┐ ┌──────────┐ │
│  │ Connection │ │   Network  │ │    WiFi    │ │  Config  │ │
│  │  Module    │ │   Module   │ │   Module   │ │  Module  │ │
│  │            │ │            │ │            │ │          │ │
│  │ - MQTT     │ │ - VPN      │ │ - 扫描     │ │ - 设备 ID│ │
│  │ - 认证     │ │ - 代理     │ │ - 连接     │ │ - Token  │ │
│  │ - 状态     │ │ - 路由     │ │ - 断开     │ │ - 权限   │ │
│  └────────────┘ └────────────┘ └────────────┘ └──────────┘ │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                      基础设施层                              │
│  ┌────────────┐ ┌────────────┐ ┌────────────┐ ┌──────────┐ │
│  │  Result    │ │  Security  │ │   Utils    │ │  日志    │ │
│  │  封装      │ │  检测      │ │   工具类   │ │  Slf4j   │ │
│  └────────────┘ └────────────┘ └────────────┘ └──────────┘ │
└─────────────────────────────────────────────────────────────┘
```

### 3.2 核心组件职责

#### 3.2.1 VPN 服务 (`GatewayVpnService.kt`)

**职责**:
- 创建和管理 TUN 设备
- 拦截所有 IPv4 流量
- 根据目标地址/端口进行流量路由决策
- 管理 SOCKS5 连接池
- 构造回包并注入 TUN

**关键实现**:
```kotlin
// 文件路径：android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt

@AndroidEntryPoint
class GatewayVpnService : AndroidVpnService() {
    // TUN 设备文件描述符
    private var vpnInterface: ParcelFileDescriptor? = null
    
    // SOCKS5 连接池
    private var socks5ConnectionPool: Socks5ConnectionPool? = null
    
    // 活跃连接映射 (四元组 -> 连接会话)
    private val activeConnections = ConcurrentHashMap<String, ConnectionSession>()
    
    // 虚拟 IP 分配 (用于回包构造)
    private val virtualIpPool = ConcurrentHashMap<String, String>()
    
    // 流量处理循环
    private suspend fun processVpnTraffic() {
        // 从 TUN 读取 IP 包
        // 解析目标地址和端口
        // 根据路由决策转发流量
    }
    
    // 路由决策
    private enum class RouteType {
        LOCAL_NETWORK,  // 内网 - WiFi 直连
        DNS,            // DNS - WiFi
        CLOUD_SERVER,   // 云服务器 - 蜂窝 (排除)
        PROXY           // 外网 - SOCKS5 代理
    }
}
```

**技术要点**:
- 使用 `protect(socket)` 绕过 VPN
- 虚拟 IP 池管理 (`10.0.0.x` 网段)
- 连接池复用 SOCKS5 连接
- 平滑指数退避算法减少空闲轮询

#### 3.2.2 MQTT 连接管理器 (`MqttConnectionManager.kt`)

**职责**:
- 建立和维护 MQTT 长连接
- 处理断线重连
- 心跳保活
- 消息发布/订阅

**安全策略**:
```kotlin
// 文件路径：android/app/src/main/java/com/netproxy/gateway/connection/MqttConnectionManager.kt

private fun createSecureSocketFactory(): SSLSocketFactory {
    // 安全检查：release 构建不允许信任所有证书
    if (!BuildConfig.DEBUG && BuildConfig.MQTT_TRUST_ALL_CERTS) {
        throw IllegalStateException("TRUST_ALL_CERTS is not allowed in release builds")
    }

    return if (BuildConfig.MQTT_TRUST_ALL_CERTS) {
        // debug 构建：信任所有证书（自签名）
        createDevSocketFactory()
    } else {
        // release 构建：系统默认 CA + 证书固定
        createProductionSocketFactory()
    }
}
```

**TLS 策略**:
- **Debug 构建**: 允许信任自签名证书
- **Release 构建**: 系统 CA + 证书固定 (Certificate Pinning)
- TLS 版本：1.2+

#### 3.2.3 SOCKS5 代理服务 (`Socks5ProxyService.kt`)

**职责**:
- 监听本地 1080 端口
- 接受 VPN 转发来的外网连接请求
- 通过隧道连接到云服务器
- 双向转发数据

**实现框架**: Netty

```kotlin
// 文件路径：android/app/src/main/java/com/netproxy/gateway/proxy/Socks5ProxyService.kt

@AndroidEntryPoint
class Socks5ProxyService : Service() {
    private var bossGroup: NioEventLoopGroup? = null
    private var workerGroup: NioEventLoopGroup? = null
    private var serverChannel: Channel? = null

    private fun startProxyServer() {
        bossGroup = NioEventLoopGroup(1)
        workerGroup = NioEventLoopGroup(workerThreads)

        val bootstrap = ServerBootstrap()
            .group(bossGroup, workerGroup)
            .channel(NioServerSocketChannel::class.java)
            .childHandler(object : ChannelInitializer<SocketChannel>() {
                override fun initChannel(ch: SocketChannel) {
                    ch.pipeline().addLast(
                        LoggingHandler(LogLevel.WARN),
                        SocksPortUnificationServerHandler(),
                        Socks5ProxyHandler(credentialValidator = { ... })
                    )
                }
            })

        serverChannel = bootstrap.bind("127.0.0.1", 1080).sync().channel()
    }
}
```

#### 3.2.4 WiFi 管理器 (`GatewayWifiManager.kt`)

**职责**:
- WiFi 网络扫描
- 连接到指定 WiFi 网络
- 管理 WiFi 连接状态

**双路径实现**:
```kotlin
// 文件路径：android/app/src/main/java/com/netproxy/gateway/wifi/WifiManager.kt

fun connectToNetwork(ssid: String, password: String?, securityType: String): Boolean {
    val parsedSecurityType = parseSecurityType(securityType)
    
    return if (shouldUseNetworkSuggestion()) {
        // Android 10+: WifiNetworkSuggestion API
        connectUsingNetworkSuggestion(ssid, password, parsedSecurityType)
    } else {
        // Android 9 及以下：WifiConfiguration API
        connectUsingLegacyConfig(ssid, password, parsedSecurityType)
    }
}
```

**技术限制**:
- Android 10+ 对后台 WiFi 连接有限制
- 需要用户确认网络建议
- 随机 MAC 地址功能未处理

#### 3.2.5 认证会话存储 (`AuthSessionStore.kt`)

**职责**:
- 安全存储设备 ID 和认证 Token
- 提供 SOCKS5 代理认证验证
- 使用 EncryptedSharedPreferences 加密存储

```kotlin
// 文件路径：android/app/src/main/java/com/netproxy/gateway/connection/AuthSessionStore.kt

@Singleton
class AuthSessionStore @Inject constructor(...) {
    private val encryptedPrefs by lazy {
        EncryptedSharedPreferences.create(...)
    }

    // 常量时间比较，防止时序攻击
    private fun constantTimeEquals(left: CharArray, right: CharArray): Boolean {
        if (left.size != right.size) return false
        var diff = 0
        for (i in left.indices) {
            diff = diff or (left[i].code xor right[i].code)
        }
        return diff == 0
    }
}
```

### 3.3 模块化设计

采用 **CQRS（命令查询职责分离）** 模式：

```kotlin
// 文件路径：android/app/src/main/java/com/netproxy/gateway/di/ModuleInterfaces.kt

// 命令处理器（写操作）
interface NetworkCommandHandler {
    fun startVpn(): Boolean
    fun stopVpn(): Boolean
    fun startProxy(): Boolean
    fun stopProxy(): Boolean
}

// 查询处理器（读操作）
interface NetworkQueryHandler {
    fun getVpnStatus(): StateFlow<VpnStatus>
    fun getProxyStatus(): StateFlow<Boolean>
}
```

**模块划分**:
- `CoreModule`: 核心状态和协程作用域
- `CommunicationModule`: MQTT 连接管理
- `NetworkModule`: VPN 和代理服务
- `WiFiModule`: WiFi 连接管理
- `ConfigModule`: 配置和权限管理
- `UIModule`: UI 状态管理

---

## 4. Go 服务端架构

### 4.1 服务组件

```text
┌────────────────────────────────────────────────────────────────┐
│                     云服务器组件                                │
│                                                                │
│  ┌──────────────────────────────────────────────────────────┐ │
│  │  1. REST API Service (api/main.go)                       │ │
│  │     - 端口：8080                                         │ │
│  │     - 框架：Gin                                          │ │
│  │     - 数据库：SQLite3                                    │ │
│  │                                                          │ │
│  │     核心功能：                                             │ │
│  │     - 工程师登录 (JWT 认证)                                │ │
│  │     - 配对会话管理 (6 位配对码)                            │ │
│  │     - 会话令牌验证                                         │ │
│  │     - 设备状态管理                                         │ │
│  │     - 登录限流 (5 次失败封禁 15 分钟)                       │ │
│  └──────────────────────────────────────────────────────────┘ │
│                                                                │
│  ┌──────────────────────────────────────────────────────────┐ │
│  │  2. SOCKS5 Proxy Service (socks5-proxy/main.go)          │ │
│  │     - 端口：1080                                         │ │
│  │     - 协议：SOCKS5 (RFC 1928)                            │ │
│  │     - 认证：用户名/密码 (0x02)                           │ │
│  │                                                          │ │
│  │     核心功能：                                             │ │
│  │     - SOCKS5 握手处理                                     │ │
│  │     - 设备令牌验证 (调用 API)                             │ │
│  │     - 通过 WebSocket 隧道连接到设备                       │ │
│  │     - IP 过滤 (仅允许 RFC1918 私有地址)                    │ │
│  │     - 连接限流                                            │ │
│  │     - TLS 加密 (可选)                                     │ │
│  └──────────────────────────────────────────────────────────┘ │
│                                                                │
│  ┌──────────────────────────────────────────────────────────┐ │
│  │  3. Tunnel Gateway (tunnel/main.go)                      │ │
│  │     - 端口：8443                                         │ │
│  │     - 协议：WebSocket (wss://)                           │ │
│  │     - 消息类型：JSON                                     │ │
│  │                                                          │ │
│  │     核心功能：                                             │ │
│  │     - 设备 WebSocket 连接管理                             │ │
│  │     - 心跳检测 (30s ping, 90s timeout)                    │ │
│  │     - 连接请求转发 (CONNECT → 设备)                       │ │
│  │     - 数据双向转发                                         │ │
│  │     - 死连接清理                                          │ │
│  └──────────────────────────────────────────────────────────┘ │
│                                                                │
│  ┌──────────────────────────────────────────────────────────┐ │
│  │  4. EMQX MQTT Broker                                      │ │
│  │     - 端口：1883 (TCP), 8883 (TLS)                       │ │
│  │     - 管理端口：18083 (Dashboard)                        │ │
│  │     - 主题：device/{deviceId}/control                    │ │
│  │                                                          │ │
│  │     核心功能：                                             │ │
│  │     - 设备状态推送                                         │ │
│  │     - 远程控制命令                                         │ │
│  │     - 心跳保活                                            │ │
│  └──────────────────────────────────────────────────────────┘ │
└────────────────────────────────────────────────────────────────┘
```

### 4.2 REST API 服务详解

**文件**: [`server/api/main.go`](server/api/main.go)

**核心数据结构**:

```go
// 配对会话
type PairingSession struct {
    Code       string    // 6 位配对码
    DeviceID   string    // 设备 ID
    Status     string    // pending | connected | disconnected | expired
    EngineerID string    // 工程师 ID
    CreatedAt  time.Time
    ExpiresAt  time.Time
    Used       bool
}

// 会话令牌
type SessionToken struct {
    Token      string    // 随机生成的令牌
    DeviceID   string    // 设备 ID
    EngineerID string    // 工程师 ID
    CreatedAt  time.Time
    ExpiresAt  time.Time
}
```

**API 端点**:

| 端点 | 方法 | 认证 | 描述 |
|------|------|------|------|
| `/health` | GET | 无 | 健康检查 |
| `/api/login` | POST | 无 | 工程师登录（获取 JWT） |
| `/api/pair` | POST | JWT | 创建配对会话 |
| `/api/pair/:code` | GET | 无 | 获取配对会话状态 |
| `/api/pair/:code` | PUT | 无 | 更新配对会话状态 |
| `/api/session/token` | POST | JWT | 创建会话令牌 |
| `/api/session/validate` | POST | 内部 API Key | 验证会话令牌 |
| `/api/device/:id/status` | GET | JWT | 获取设备状态 |
| `/api/device/status` | POST | 内部 API Key | 更新设备状态 |

**安全机制**:

1. **JWT 认证**: HS256 签名，24 小时有效期
2. **登录限流**: 5 次失败封禁 15 分钟
3. **内部 API Key**: 服务间通信验证
4. **配对码**: 6 位数字，15 分钟有效期
5. **会话令牌**: 32 位随机字符串，15 分钟有效期

### 4.3 SOCKS5 代理服务详解

**文件**: `server/socks5-proxy/main.go`

**SOCKS5 握手流程**:

```text
工程师客户端                    云服务器 SOCKS5
     │                              │
     │──── VER=5, NMETHODS ────────►│
     │                              │
     │◄──── METHOD=0x02 (用户名/密码) ──│
     │                              │
     │──── 用户名 + 密码 ──────────►│
     │                              │
     │◄──── 认证成功/失败 ──────────│
     │                              │
     │──── CONNECT 请求 ──────────►│
     │     (dst_addr:dst_port)      │
     │                              │
     │◄──── CONNECT 响应 ───────────│
     │                              │
     │◄──── 双向数据转发 ──────────►│
```

**隧道连接建立**:

```go
// 通过 WebSocket 隧道连接到设备
func (s *SOCKS5Server) handleConnect(conn net.Conn, session AuthSession, dstAddr string, dstPort int) error {
    // 1. 通过隧道客户端建立到设备的连接
    targetConn, err := s.tunnelClient.ConnectThroughTunnel(
        session.DeviceID, 
        session.Token, 
        dstAddr, 
        dstPort,
    )
    if err != nil {
        s.sendReply(conn, 0x05) // Connection refused
        return err
    }
    
    // 2. 发送成功响应
    s.sendReply(conn, 0x00)
    
    // 3. 双向转发数据
    return s.relay(conn, targetConn)
}
```

**IP 过滤策略**:

```go
type IPFilter struct {
    allowedCIDRs: []string{
        "10.0.0.0/8",      // 私有 A 类
        "172.16.0.0/12",   // 私有 B 类
        "192.168.0.0/16",  // 私有 C 类
    }
}
```

### 4.4 隧道网关详解

**文件**: [`server/tunnel/main.go`](server/tunnel/main.go)

**WebSocket 消息格式**:

```json
// 连接请求
{
    "type": "connect",
    "data": {
        "stream_id": "device-1234567890",
        "address": "192.168.1.100",
        "port": 80
    }
}

// 连接响应
{
    "type": "connect_response",
    "data": {
        "stream_id": "device-1234567890",
        "success": true,
        "error": ""
    }
}

// 数据传输
{
    "type": "data",
    "data": {
        "stream_id": "device-1234567890",
        "data": "<binary>"
    }
}

// 断开连接
{
    "type": "disconnect",
    "data": {
        "stream_id": "device-1234567890"
    }
}
```

**心跳机制**:

```go
// 服务器端心跳检测
func (s *Server) heartbeat(tunnel *TunnelConn, stop chan struct{}) {
    ticker := time.NewTicker(30 * time.Second)
    defer ticker.Stop()
    
    for {
        select {
        case <-ticker.C:
            if !tunnel.IsAlive(90 * time.Second) {
                log.Printf("Heartbeat timeout for device: %s", tunnel.DeviceID)
                tunnel.Close()
                return
            }
            
            // 发送 ping
            tunnel.Conn.WriteControl(websocket.PingMessage, []byte{}, time.Now().Add(10*time.Second))
        case <-stop:
            return
        }
    }
}
```

---

## 5. 数据流图

### 5.1 配对流程

```text
工程师设备                      云服务器                      用户手机
    │                             │                             │
    │  1. 登录 (admin/pass)       │                             │
    │────────────────────────────►│                             │
    │                             │                             │
    │  2. 返回 JWT Token          │                             │
    │◄────────────────────────────│                             │
    │                             │                             │
    │  3. 创建配对会话            │                             │
    │     (POST /api/pair)        │                             │
    │────────────────────────────►│                             │
    │                             │                             │
    │  4. 返回 6 位配对码           │                             │
    │◄────────────────────────────│                             │
    │                             │                             │
    │  5. 显示配对码              │      6. 输入配对码          │
    │     (如：123456)            │         (用户输入)          │
    │                             │─────────────┐               │
    │                             │             │               │
    │                             │   7. 查询配对状态           │
    │                             │◄────────────┘               │
    │                             │                             │
    │                             │   8. 返回配对码信息         │
    │                             │─────────────►               │
    │                             │                             │
    │                             │   9. 确认配对 (PUT)         │
    │                             │◄────────────┘               │
    │                             │                             │
    │                             │   10. 创建会话令牌          │
    │                             │     (POST /api/session/token)
    │                             │◄────────────┐               │
    │                             │              │               │
    │  11. 轮询配对状态           │              │               │
    │     (GET /api/pair/:code)   │              │               │
    │────────────────────────────►│              │               │
    │                             │              │               │
    │  12. 返回 connected + Token │              │               │
    │◄────────────────────────────│              │               │
    │                             │              │               │
    │                             │   13. 建立 MQTT 连接          │
    │                             │◄─────────────────────────────│
    │                             │     (deviceId + Token)       │
    │                             │              │               │
    │                             │   14. 返回连接成功           │
    │                             │─────────────►               │
    │                             │                             │
    │  15. 建立 WebSocket 隧道     │                             │
    │◄────────────────────────────│                             │
    │     (wss://tunnel)          │                             │
    │                             │                             │
```

### 5.2 数据访问流程

```text
工程师浏览器                 云端 SOCKS5                手机 VPN                 内网设备
    │                           │                          │                       │
    │  1. HTTP 请求              │                          │                       │
    │     http://192.168.1.100  │                          │                       │
    │──────────────────────────►│                          │                       │
    │                           │                          │                       │
    │  2. SOCKS5 CONNECT        │                          │                       │
    │     192.168.1.100:80      │                          │                       │
    │──────────────────────────►│                          │                       │
    │                           │                          │                       │
    │  3. 验证 Token             │                          │                       │
    │     (调用 API)            │                          │                       │
    │◄──────────────────────────│                          │                       │
    │                           │                          │                       │
    │  4. WebSocket 隧道连接     │                          │                       │
    │     CONNECT 请求           │                          │                       │
    │─────────────────────────────────────────────────────►│                       │
    │                           │                          │                       │
    │  5. 建立到目标的 TCP 连接    │                          │                       │
    │                           │──────────────────────────►│                       │
    │                           │                          │                       │
    │  6. 返回连接成功          │                          │                       │
    │◄──────────────────────────│                          │                       │
    │                           │                          │                       │
    │  7. HTTP 请求数据          │                          │                       │
    │──────────────────────────►│                          │                       │
    │                           │                          │                       │
    │  8. 通过隧道转发          │                          │                       │
    │─────────────────────────────────────────────────────►│                       │
    │                           │                          │                       │
    │                           │  9. TCP 转发              │                       │
    │                           │──────────────────────────►│                       │
    │                           │                          │                       │
    │                           │                          │  10. HTTP 请求          │
    │                           │                          │──────────────────────►│
    │                           │                          │                       │
    │                           │                          │  11. HTTP 响应         │
    │                           │                          │◄──────────────────────│
    │                           │                          │                       │
    │                           │  12. TCP 响应数据         │                       │
    │                           │◄─────────────────────────│                       │
    │                           │                          │                       │
    │  13. 通过隧道返回         │                          │                       │
    │◄──────────────────────────│                          │                       │
    │                           │                          │                       │
    │  14. HTTP 响应            │                          │                       │
    │◄──────────────────────────│                          │                       │
    │                           │                          │                       │
```

### 5.3 VPN 流量路由流程

```text
应用层
  │
  │ 发送数据包
  ▼
┌─────────────────────────────────────────┐
│  TUN 设备 (10.0.0.2/32)                  │
│  拦截所有 0.0.0.0/0 流量                 │
└─────────────────┬───────────────────────┘
                  │
                  ▼
          ┌───────────────┐
          │ 解析 IP 包     │
          │ - 目标 IP      │
          │ - 目标端口     │
          │ - 协议类型     │
          └───────┬───────┘
                  │
      ┌───────────┼───────────┐
      │           │           │
      ▼           ▼           ▼
┌──────────┐ ┌──────────┐ ┌──────────┐
│ DNS:53   │ │ 内网 IP  │ │ 外网 IP  │
│          │ │          │ │          │
│ protect()│ │ protect()│ │ SOCKS5   │
│ Datagram │ │ TCP/UDP  │ │ 代理     │
│ Socket   │ │ Socket   │ │ 127.0.0.1│
│          │ │          │ │ :1080    │
└────┬─────┘ └────┬─────┘ └────┬─────┘
     │            │            │
     │ WiFi DNS   │ WiFi 直连  │ 本地代理
     │ 服务器     │ 内网设备   │ 转发
     │            │            │
     ▼            ▼            ▼
  物理 WiFi 网卡 ───────────────►
```

---

## 6. 关键设计模式

### 6.1 CQRS（命令查询职责分离）

**应用场景**: Android 模块化设计

```kotlin
// 命令处理器（写操作）
interface CommunicationCommandHandler {
    fun connect(deviceId: String, authToken: String): Boolean
    fun publish(topic: String, payload: String, qos: Int)
    fun disconnect()
}

// 查询处理器（读操作）
interface CommunicationQueryHandler {
    fun getConnectionState(): StateFlow<MqttConnectionState>
}
```

**优点**:
- 明确区分读写操作
- 便于测试和 mock
- 支持不同的优化策略

**缺点**:
- 增加接口数量
- 当前规模可能过度设计

### 6.2 连接池模式

**应用场景**: SOCKS5 连接管理

```kotlin
data class Socks5ConnectionPoolConfig(
    maxConnections: Int = 100,
    idleTimeoutMs: Long = 30_000,
    connectionTimeoutMs: Long = 5_000,
    maxConnectionsPerDestination: Int = 8
)

class Socks5ConnectionPool(
    private val proxyHost: String,
    private val proxyPort: Int,
    private val config: Socks5ConnectionPoolConfig,
    private val credentialProvider: () -> Pair<String, String>?
) {
    // 借用连接
    fun borrowConnection(
        destinationIp: String,
        destinationPort: Int,
        protectSocket: (Socket) -> Unit
    ): PooledSocks5Connection?
    
    // 归还连接
    fun returnConnection(connection: PooledSocks5Connection)
}
```

**优点**:
- 减少连接建立开销
- 控制并发连接数
- 提高资源利用率

### 6.3 策略模式

**应用场景**: WiFi 连接 API 选择

```kotlin
fun connectToNetwork(ssid: String, password: String?, securityType: String): Boolean {
    return if (shouldUseNetworkSuggestion()) {
        // Android 10+: WifiNetworkSuggestion
        connectUsingNetworkSuggestion(ssid, password, securityType)
    } else {
        // Android 9 及以下：WifiConfiguration
        connectUsingLegacyConfig(ssid, password, securityType)
    }
}
```

**优点**:
- 封装算法族
- 支持运行时切换
- 符合开闭原则

### 6.4 单例模式

**应用场景**: 全局管理器

```kotlin
@Singleton
class MqttConnectionManager @Inject constructor(...)
@Singleton
class GatewayWifiManager @Inject constructor(...)
@Singleton
class AuthSessionStore @Inject constructor(...)
```

**实现方式**: Hilt 依赖注入

### 6.5 工厂模式

**应用场景**: TLS SocketFactory 创建

```kotlin
private fun createSecureSocketFactory(): SSLSocketFactory {
    return if (BuildConfig.MQTT_TRUST_ALL_CERTS) {
        createDevSocketFactory()  // 开发环境
    } else {
        createProductionSocketFactory()  // 生产环境
    }
}
```

### 6.6 观察者模式

**应用场景**: 状态流（StateFlow）

```kotlin
private val _connectionState = MutableStateFlow<MqttConnectionState>(MqttConnectionState.Disconnected)
val connectionState: StateFlow<MqttConnectionState> = _connectionState.asStateFlow()

// 观察者
viewModelScope.launch {
    mqttConnectionManager.connectionState.collect { state ->
        when (state) {
            is MqttConnectionState.Connected -> { ... }
            is MqttConnectionState.Disconnected -> { ... }
            is MqttConnectionState.Error -> { ... }
        }
    }
}
```

---

## 7. 模块依赖关系

### 7.1 Android 客户端依赖图

```text
┌──────────────────────────────────────────────────────────────┐
│  MainActivity (UI 层)                                         │
│  └─ MainViewModel                                            │
└─────────────────────┬────────────────────────────────────────┘
                      │ 依赖
                      ▼
┌──────────────────────────────────────────────────────────────┐
│  业务模块层 (通过 Hilt 注入)                                   │
│                                                              │
│  ┌────────────────┐  ┌────────────────┐  ┌────────────────┐ │
│  │ NetworkModule  │  │ CommModule     │  │ WiFiModule     │ │
│  │                │  │                │  │                │ │
│  │ - VpnService   │  │ - MQTT         │  │ - 扫描         │ │
│  │ - Proxy        │  │ - 认证         │  │ - 连接         │ │
│  └───────┬────────┘  └───────┬────────┘  └───────┬────────┘ │
│          │                   │                   │          │
└──────────┼───────────────────┼───────────────────┼──────────┘
           │                   │                   │
           ▼                   ▼                   ▼
┌──────────────────────────────────────────────────────────────┐
│  基础设施层                                                   │
│                                                              │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐       │
│  │ AuthSession  │  │ Result       │  │ Security     │       │
│  │ Store        │  │ 封装         │  │ 检测         │       │
│  └──────────────┘  └──────────────┘  └──────────────┘       │
└──────────────────────────────────────────────────────────────┘
```

### 7.2 服务端依赖关系

```text
┌─────────────────────────────────────────────────────────────┐
│                     外部依赖                                 │
│                                                             │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │  SQLite3     │  │  EMQX MQTT   │  │  TLS 证书     │      │
│  │  (数据持久化) │  │  (消息推送)  │  │  (加密通信)  │      │
│  └──────────────┘  └──────────────┘  └──────────────┘      │
└─────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│                     服务层                                   │
│                                                             │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │  REST API    │◄─┤  SOCKS5      │◄─┤   Tunnel     │      │
│  │  Service     │  │  Proxy       │  │   Gateway    │      │
│  │              │  │              │  │              │      │
│  │ - Gin 框架   │  │ - Net/TCP    │  │ - WebSocket  │      │
│  │ - JWT 认证   │  │ - SOCKS5     │  │ - 心跳检测   │      │
│  │ - SQLite     │  │ - WebSocket  │  │ - 消息转发   │      │
│  └──────────────┘  └──────────────┘  └──────────────┘      │
└─────────────────────────────────────────────────────────────┘
```

### 7.3 服务间通信

```text
┌──────────────┐         ┌──────────────┐         ┌──────────────┐
│  SOCKS5      │         │   Tunnel     │         │  REST API    │
│  Proxy       │         │   Gateway    │         │  Service     │
│              │         │              │         │              │
│  HTTP POST   │────────►│              │         │              │
│  /validate   │         │              │         │              │
│              │         │              │         │              │
│              │         │  HTTP POST   │────────►│              │
│              │         │  /device/status        │              │
│              │         │              │         │              │
└──────────────┘         └──────────────┘         └──────────────┘
```

---

## 8. 技术栈评估

### 8.1 Android 客户端

| 组件 | 技术选型 | 版本 | 评估 |
|------|---------|------|------|
| **语言** | Kotlin | 1.9+ | ✅ 现代化，空安全，协程支持 |
| **UI 框架** | Jetpack Compose | 最新 | ✅ 声明式 UI，简洁 |
| **依赖注入** | Hilt | 2.48+ | ✅ Android 官方推荐 |
| **网络** | Paho MQTT | 1.2.5 | ⚠️ 成熟但维护频率低 |
| **代理** | Netty | 4.1+ | ✅ 高性能 NIO 框架 |
| **加密存储** | EncryptedSharedPreferences | 1.1+ | ✅ AndroidX 官方 |
| **日志** | Slf4j | - | ✅ 标准日志接口 |

**技术风险**:
- Paho MQTT 库维护频率较低，考虑替代方案
- WifiConfiguration API 在 Android 10+ 已废弃
- 未使用 Android 原生多网络 API（`Network.bindSocket()`）

### 8.2 Go 服务端

| 组件 | 技术选型 | 版本 | 评估 |
|------|---------|------|------|
| **语言** | Go | 1.21+ | ✅ 高性能，并发友好 |
| **Web 框架** | Gin | v1.9+ | ✅ 高性能，生态成熟 |
| **WebSocket** | gorilla/websocket | v1.5+ | ✅ 事实标准 |
| **数据库** | SQLite3 | - | ⚠️ 轻量但并发受限 |
| **JWT** | golang-jwt/jwt/v5 | v5.0+ | ✅ 官方维护 |
| **MQTT Broker** | EMQX | 5.3 | ✅ 开源领先 |

**技术风险**:
- SQLite3 在高并发场景可能成为瓶颈
- gorilla/websocket 已归档，考虑替代方案

### 8.3 架构模式

| 模式 | 应用 | 评估 |
|------|------|------|
| **单体 vs 微服务** | 微服务（3 个独立服务） | ✅ 职责清晰，独立部署 |
| **CQRS** | Android 模块接口 | ⚠️ 可能过度设计 |
| **连接池** | SOCKS5 连接管理 | ✅ 必要优化 |
| **WebSocket 长连接** | 隧道网关 | ✅ 实时双向通信 |

### 8.4 性能评估

**Android 客户端**:
- ✅ Netty NIO 处理并发连接
- ✅ 连接池减少建立开销
- ✅ 平滑指数退避减少 CPU 轮询
- ⚠️ `protect()` 无法强制指定网卡出口

**服务端**:
- ✅ Go 协程处理高并发
- ✅ WebSocket 全双工通信
- ✅ EMQX 支持百万级并发
- ⚠️ SQLite3 并发写入受限

---

## 附录

### A. 关键配置项

**Android BuildConfig**:
```kotlin
DEBUG = true/false
MQTT_BROKER_URL_TLS = "ssl://mqtt.example.com:8883"
MQTT_BROKER_URL_PLAIN = "tcp://mqtt.example.com:1883"
MQTT_USE_TLS = true
MQTT_TRUST_ALL_CERTS = false  // release 必须为 false
MQTT_TLS_PUBLIC_KEY_PINS = "sha256/xxx..."
```

**服务端环境变量**:
```bash
# API 服务
JWT_SECRET=your-secret
ADMIN_USER=admin
ADMIN_PASS=changeme
INTERNAL_API_KEY=netproxy-internal-dev-key

# TLS 配置
ENABLE_TLS=true
TLS_CERT=/app/certs/server.crt
TLS_KEY=/app/certs/server.key
```

### B. 端口分配

| 服务 | 端口 | 协议 | 描述 |
|------|------|------|------|
| **Android** | | | |
| Local Proxy | 1080 | TCP | SOCKS5 代理监听 |
| **云服务器** | | | |
| REST API | 8080 | HTTP/HTTPS | RESTful API |
| SOCKS5 Proxy | 1080 | TCP | SOCKS5 代理 |
| Tunnel Gateway | 8443 | WebSocket/WSS | 设备隧道连接 |
| EMQX MQTT | 1883 | TCP | MQTT 非加密 |
| EMQX MQTT | 8883 | TCP | MQTT over TLS |
| EMQX Dashboard | 18083 | HTTP | 管理界面 |

### C. 相关文档

- [DECISIONS.md](DECISIONS.md) - 架构决策记录
- [TECH_DEBT.md](TECH_DEBT.md) - 技术债务清单
- [KNOWN_LIMITATIONS.md](KNOWN_LIMITATIONS.md) - 已知限制
- [NETWORK_ROUTING.md](NETWORK_ROUTING.md) - 网络路由分析
- [BLOCKERS.md](BLOCKERS.md) - 阻塞问题
- [CLAUDE.md](../CLAUDE.md) - AI 代理执行契约（AGENTS.md 是其符号链接）

---

*文档最后更新：2026-04-03*

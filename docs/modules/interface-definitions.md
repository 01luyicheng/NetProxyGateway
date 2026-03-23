# 接口定义文档

> 本文档定义 NetProxyGateway 项目中各模块之间的接口，用于协调多个 Claude 实例开发时的模块通信。

## 概述

项目采用模块化架构，各模块通过以下接口进行通信。所有模块独立开发，通过接口定义确保兼容性。

---

## 1. NetworkStateManager (连接模块)

**位置**: `android/app/src/main/java/com/netproxy/gateway/connection/NetworkStateManager.kt`

**职责**: 网络状态检测和管理

### 接口定义

```kotlin
@Singleton
class NetworkStateManager @Inject constructor(
    @ApplicationContext private val context: Context
)
```

### 公共方法

| 方法 | 返回类型 | 说明 |
|------|---------|------|
| `networkState` | `Flow<NetworkState>` | 网络状态流 |
| `getCurrentNetworkType()` | `NetworkType` | 获取当前网络类型 |
| `isWifiConnected()` | `Boolean` | 检查 WiFi 是否连接 |
| `isCellularConnected()` | `Boolean` | 检查蜂窝网络是否连接 |

### 数据类

```kotlin
sealed class NetworkType {
    object Wifi : NetworkType()
    object Cellular : NetworkType()
    object Ethernet : NetworkType()
    object None : NetworkType()
}

data class NetworkState(
    val isConnected: Boolean = false,
    val networkType: NetworkType = NetworkType.None,
    val isValidated: Boolean = false,
    val network: Network? = null
)
```

### 依赖

- 由 Task 3A 实现
- 被以下模块依赖:
  - MainViewModel (UI 模块)
  - MqttConnectionManager (通信模块)

---

## 2. MqttConnectionManager (连接模块)

**位置**: `android/app/src/main/java/com/netproxy/gateway/connection/MqttConnectionManager.kt`

**职责**: MQTT 连接管理和消息通信

### 接口定义

```kotlin
@Singleton
class MqttConnectionManager @Inject constructor(
    @ApplicationContext private val context: Context
)
```

### 公共方法

| 方法 | 返回类型 | 说明 |
|------|---------|------|
| `connect(deviceId: String, authToken: String)` | `Unit` | 连接到 MQTT broker |
| `publish(topic: String, payload: String, qos: Int = 0)` | `Unit` | 发布消息 |
| `subscribe(topic: String, qos: Int = 0)` | `Unit` | 订阅主题 |
| `disconnect()` | `Unit` | 断开连接 |
| `connectionState` | `StateFlow<MqttConnectionState>` | 连接状态流 |
| `messages` | `StateFlow<String?>` | 消息流 |

### 状态类

```kotlin
sealed class MqttConnectionState {
    object Disconnected : MqttConnectionState()
    object Connecting : MqttConnectionState()
    object Connected : MqttConnectionState()
    data class Error(val message: String) : MqttConnectionState()
}
```

### MQTT 主题结构

| 主题 | 方向 | 说明 |
|------|------|------|
| `device/{deviceId}/status` | 设备 → 云 | 设备状态上报 |
| `device/{deviceId}/control` | 云 → 设备 | 工程师控制指令 |
| `device/{deviceId}/heartbeat` | 设备 → 云 | 心跳 |
| `device/{deviceId}/response` | 设备 → 云 | 操作响应 |

### 依赖

- 由 Task 3A 实现
- 依赖 NetworkStateManager

---

## 3. WifiManager (WiFi 模块)

**位置**: `android/app/src/main/java/com/netproxy/gateway/wifi/WifiManager.kt`

**职责**: WiFi 扫描和连接管理

### 接口定义

```kotlin
@Singleton
class WifiManager @Inject constructor(
    @ApplicationContext private val context: Context
)
```

### 公共方法

| 方法 | 返回类型 | 说明 |
|------|---------|------|
| `wifiScanResults` | `Flow<List<WifiNetwork>>` | WiFi 扫描结果流 |
| `startScan()` | `Boolean` | 开始扫描 |
| `getScanResults()` | `List<WifiNetwork>` | 获取扫描结果 |
| `getCurrentConnection()` | `WifiConnectionInfo?` | 获取当前连接 |
| `connectToNetwork(ssid: String, password: String?, securityType: String)` | `Boolean` | 连接网络 |
| `disconnect()` | `Boolean` | 断开连接 |

### 数据类

```kotlin
data class WifiNetwork(
    val ssid: String,
    val bssid: String,
    val signalStrength: Int,
    val frequency: Int,
    val capabilities: String,
    val isSecure: Boolean
)

data class WifiConnectionInfo(
    val ssid: String?,
    val bssid: String?,
    val ipAddress: Int,
    val linkSpeed: Int,
    val frequency: Int,
    val signalStrength: Int
)
```

### 依赖

- 由 Task 3D 实现
- 被 MainViewModel (UI 模块) 依赖

---

## 4. VpnService (VPN 模块)

**位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt`

**职责**: 本地 VPN 服务和流量隧道

### 接口定义

```kotlin
class VpnService : VpnService()
```

### 核心常量

| 常量 | 值 | 说明 |
|------|-----|------|
| `VPN_ADDRESS` | "10.0.0.2" | VPN 虚拟 IP |
| `VPN_ROUTE` | "0.0.0.0" | 路由所有流量 |
| `VPN_DNS` | "8.8.8.8" | DNS 服务器 |
| `VPN_MTU` | 1500 | MTU |

### 公共方法

| 方法 | 返回类型 | 说明 |
|------|---------|------|
| `onStartCommand(intent: Intent?, flags: Int, startId: Int)` | `Int` | 服务入口 |
| `status` | `StateFlow<VpnStatus>` | VPN 状态流 |

### 状态类

```kotlin
enum class VpnState {
    STOPPED,
    STARTING,
    RUNNING,
    ERROR
}

data class VpnStatus(
    val state: VpnState = VpnState.STOPPED,
    val errorMessage: String? = null,
    val connectedClients: Int = 0
)
```

### 依赖

- 由 Task 3B 实现
- 启动 Socks5ProxyService 作为子服务

---

## 5. Socks5ProxyService (代理模块)

**位置**: `android/app/src/main/java/com/netproxy/gateway/proxy/Socks5ProxyService.kt`

**职责**: SOCKS5 代理服务，供工程师远程访问内网

### 接口定义

```kotlin
@AndroidEntryPoint
class Socks5ProxyService : Service()
```

### 核心常量

| 常量 | 值 | 说明 |
|------|-----|------|
| `PROXY_PORT` | 1080 | SOCKS5 代理端口 |

### 公共方法

| 方法 | 返回类型 | 说明 |
|------|---------|------|
| `onStartCommand(intent: Intent?, flags: Int, startId: Int)` | `Int` | 服务入口 |
| `onBind(intent: Intent?)` | `IBinder?` | 绑定服务 |

### 依赖

- 由 Task 3C 实现
- 被 VpnService 启动

---

## 6. MainViewModel (UI 模块)

**位置**: `android/app/src/main/java/com/netproxy/gateway/ui/viewmodel/MainViewModel.kt`

**职责**: UI 状态管理和业务逻辑协调

### 接口定义

```kotlin
@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val networkStateManager: NetworkStateManager,
    private val mqttConnectionManager: MqttConnectionManager,
    private val wifiManager: WifiManager
) : ViewModel()
```

### UI 状态

```kotlin
data class UiState(
    val isConnected: Boolean = false,
    val isPaired: Boolean = false,
    val peerId: String = "",
    val deviceId: String = "",
    val authToken: String = "",
    val isVpnEnabled: Boolean = false,
    val wifiConnected: Boolean = false,
    val cellularConnected: Boolean = false,
    val currentWifiSsid: String = "",
    val wifiNetworks: List<WifiNetwork> = emptyList(),
    val errorMessage: String? = null
)
```

### 公共方法

| 方法 | 说明 |
|------|------|
| `generatePairingCode()` | 生成配对码 |
| `pairWithCode(code: String)` | 配对设备 |
| `toggleVpn(enable: Boolean)` | 切换 VPN |
| `scanWifi()` | 扫描 WiFi |
| `disconnect()` | 断开连接 |

### 依赖

- 由 Task 3E 实现
- 依赖 NetworkStateManager, MqttConnectionManager, WifiManager

---

## 模块依赖关系图

```
┌─────────────────────────────────────────────────────────────┐
│                        UI Layer                              │
│  ┌─────────────────┐                                        │
│  │  MainViewModel  │◄──────────────────────────────────┐   │
│  └─────────────────┘                                  │   │
│         │                  │                  │          │   │
│         ▼                  ▼                  ▼          ▼   │
│  ┌──────────────┐  ┌─────────────┐  ┌──────────────┐        │
│  │NetworkState   │  │MqttConnection│  │  WifiManager │        │
│  │  Manager     │  │   Manager    │  │             │        │
│  └──────────────┘  └─────────────┘  └──────────────┘        │
│         │                  │                  │          │
└─────────┼──────────────────┼──────────────────┼──────────┘
          │                  │                  │
          ▼                  ▼                  ▼
┌─────────────────────────────────────────────────────────────┐
│                     Service Layer                            │
│  ┌──────────────┐  ┌─────────────┐  ┌──────────────┐      │
│  │VpnService    │  │Socks5Proxy  │  │   System     │      │
│  │              │──►│  Service    │  │   Services   │      │
│  └──────────────┘  └─────────────┘  └──────────────┘      │
└─────────────────────────────────────────────────────────────┘
```

---

## 版本历史

| 版本 | 日期 | 说明 |
|------|------|------|
| 1.0.0 | 2026-03-10 | 初始版本 |

---

## 相关文档

- [系统架构](./02-architecture.md) - 了解整体架构
- [连接管理模块](./03-connection-module.md) - 了解连接管理细节
- [VPN模块](./04-vpn-module.md) - 了解 VPN 细节
- [代理模块](./05-proxy-module.md) - 了解 SOCKS5 代理细节
- [WiFi模块](./06-wifi-module.md) - 了解 WiFi 控制细节

# WiFi控制模块规格

## 6.1 模块概述

WiFi 控制模块允许工程师通过控制面向设备发起“扫描/查询/发起连接请求”等操作，以支持远程排障。

约束与安全要求：
- Android 10+ 对后台/静默连接 WiFi 有平台限制；“远程连接”通常需要用户确认或使用系统提供的建议/选择器能力。
- **不得**通过 MQTT/日志传输或记录明文 WiFi 密码；远程指令不应携带 `password` 字段。

## 6.2 WiFi扫描

### 6.2.1 功能描述

- 获取周围 WiFi 列表
- 获取每个 WiFi 的信息：
  - SSID（网络名称）
  - BSSID（接入点 MAC 地址）
  - 信号强度（RSSI）
  - 加密类型（WPA2/WPA3/Open）
  - 频段（2.4GHz/5GHz）

### 6.2.2 API

```kotlin
// 获取 WiFi 列表
fun scanWifiNetworks(): Flow<List<WifiNetwork>>
```

### 6.2.3 权限要求

- Android 8.0+: `ACCESS_FINE_LOCATION`
- Android 13+: `NEARBY_WIFI_DEVICES`

## 6.3 WiFi连接

### 6.3.1 功能描述

- 配置并连接指定 WiFi
- 保存 WiFi 配置到 Android 系统
- 支持忘记已保存的 WiFi

### 6.3.2 连接流程

```
1. 检查 WiFi 状态
2. 如果未开启，引导用户开启
3. 添加/更新 WiFi 配置
4. 发起连接请求
5. 等待连接结果
6. 返回连接状态
```

### 6.3.3 API

```kotlin
// 连接 WiFi
fun connectWifi(ssid: String): Flow<WifiConnectionState>

// 断开 WiFi
fun disconnectWifi(): Result<Unit>

// 获取当前连接信息
fun getCurrentConnection(): WifiInfo?
```

## 6.4 错误处理

| 错误场景 | 处理策略 |
|---------|---------|
| 位置权限未授予 | 提示用户授予权限 |
| WiFi 扫描失败 | 返回错误，提示重试 |
| WiFi 连接失败 | 返回具体错误原因（密码错误等） |
| 权限不足（Android 13+） | 引导用户手动连接 |

---

## 相关文档

- [系统架构](./02-architecture.md) - 了解整体架构
- [Android权限](./10-android-permissions.md) - 了解 WiFi 相关权限
- [通信协议](./07-communication-protocol.md) - 了解远程控制指令

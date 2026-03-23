# Module Isolation Guidelines - 模块隔离指南

> 本文档定义了多实例并行开发时的协作规则和隔离原则。

## 核心原则

1. **每个模块独立开发** - 不依赖其他模块的具体实现
2. **通过接口通信** - 模块间通过定义好的接口交互
3. **不修改其他模块** - 只负责自己目录下的代码
4. **提交前验证** - 确保本地编译通过后再提交

## 模块目录结构

```
android/app/src/main/java/com/netproxy/gateway/
├── connection/        # Task 3A - NetworkStateManager, MqttConnectionManager
├── vpn/              # Task 3B - VpnService, VpnManager
├── proxy/            # Task 3C - Socks5ProxyService, Socks5ProxyHandler
├── wifi/             # Task 3D - WifiManager
├── ui/               # Task 3E - MainActivity, MainScreen, ViewModel
├── di/               # Task 2 - Hilt Modules
└── NetProxyApp.kt    # Task 2 - Application Class
```

## 接口定义

### NetworkStateManager 接口

```kotlin
// 定义在 connection/NetworkStateManager.kt
interface INetworkStateManager {
    fun isWifiConnected(): Boolean
    fun isCellularConnected(): Boolean
    val networkState: StateFlow<NetworkState>
}
```

### MqttConnectionManager 接口

```kotlin
// 定义在 connection/MqttConnectionManager.kt
interface IMqttConnectionManager {
    fun connect(deviceId: String, authToken: String)
    fun disconnect()
    fun publish(topic: String, payload: String)
    val connectionState: StateFlow<MqttConnectionState>
}
```

### VpnManager 接口

```kotlin
// 定义在 vpn/VpnManager.kt
interface IVpnManager {
    fun startVpn()
    fun stopVpn()
    fun prepareVpn(): Intent?
}
```

### WifiManager 接口

```kotlin
// 定义在 wifi/WifiManager.kt
interface IWifiManager {
    fun startScan(): Boolean
    fun getScanResults(): List<WifiNetwork>
    fun getCurrentConnection(): WifiConnectionInfo?
    fun connectToNetwork(ssid: String, password: String, securityType: WifiSecurityType): Boolean
    val wifiScanResults: Flow<List<WifiNetwork>>
}
```

## 依赖规则

### Task 1 (项目初始化)
- 无依赖

### Task 2 (核心应用)
- 依赖: Task 1
- 提供: Application, MainActivity, Theme, 基础 DI

### Task 3A (连接管理)
- 依赖: Task 2
- 被依赖: Task 3E

### Task 3B (VPN)
- 依赖: Task 2
- 被依赖: Task 3E

### Task 3C (代理)
- 依赖: Task 2
- 被依赖: Task 3E

### Task 3D (WiFi)
- 依赖: Task 2
- 被依赖: Task 3E

### Task 3E (UI)
- 依赖: Task 2, 3A, 3B, 3C, 3D
- 集成所有模块

### Task 4 (服务端)
- 无 Android 模块依赖
- 可与任何 Android 模块并行开发

## 协作流程

### 1. 开始新任务

```bash
# 拉取最新代码
git pull origin main

# 创建任务分支
git checkout -b task-3a-connection
```

### 2. 开发过程中

- 只修改自己任务目录下的文件
- 如需调用其他模块功能，使用已有的接口
- 如需新接口，定义接口文件并通知其他模块

### 3. 提交前

```bash
# 本地验证
cd android
./gradlew clean assembleDebug

# 提交
git add .
git commit -m "Task 3A: Implement connection module"
```

### 4. 合并流程

任务完成后:
1. 创建 Pull Request
2. 等待代码审查
3. 合并到 main 分支
4. 通知相关任务负责人

## 冲突解决

### 场景 1: 同一文件被多人修改

**原则**: 谁最后提交谁负责合并

**解决**:
1. 最后提交者拉取最新代码
2. 手动解决冲突
3. 提交合并结果

### 场景 2: 接口变更

**原则**: 变更接口需要通知所有使用者

**解决**:
1. 在 docs/changelog.md 中记录接口变更
2. 通知相关任务负责人
3. 在合并前完成接口适配

### 场景 3: 依赖版本冲突

**解决**:
1. 在 root build.gradle.kts 中统一版本
2. 使用 `resolutionStrategy` 强制版本
3. 在 PR 中说明变更

## 通信渠道

| 场景 | 渠道 |
|------|------|
| 接口变更 | GitHub Issue + Changelog |
| 阻塞问题 | 标记在 GitHub Issue |
| 任务完成 | PR Review |
| 紧急问题 | (待定) |

## Git 使用规范

### 分支命名

```
task-01-project-setup
task-02-core-application
task-03a-connection-module
task-03b-vpn-module
task-03c-proxy-module
task-03d-wifi-module
task-03e-ui-module
task-04-server
```

### Commit 消息格式

```
<Task-号>: <简短描述>

<详细描述> (可选)
```

示例:
```
Task 3A: Implement NetworkStateManager

- Add network type detection (WiFi/Cellular)
- Implement Flow-based state observation
- Add reconnection logic
```

## Changelog 维护

在 `docs/CHANGELOG.md` 中记录每次变更:

```markdown
## [2026-03-10]

### Task 3A - Connection Module
- 新增: NetworkStateManager.isWifiConnected()
- 新增: MqttConnectionManager.reconnect()
- 变更: MqttConnectionState 增加 Error 状态
```

## 检查清单

每次提交前确认:

- [ ] 只修改了自己任务目录下的文件
- [ ] `./gradlew assembleDebug` 通过
- [ ] `./gradlew lint` 无 error
- [ ] 更新了 CHANGELOG.md (如有变更)
- [ ] 提交消息符合格式

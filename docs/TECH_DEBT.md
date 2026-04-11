# 技术债务清单

## 当前活跃问题

### C2: 循环依赖风险（ModuleCoordinator 作为 Event Bus）
- **位置**: `android/app/src/main/java/com/netproxy/gateway/di/ModuleCoordinator.kt`
- **问题描述**: ModuleCoordinator 通过 modules Map 持有各模块引用，缺乏生命周期管理和清理机制，配置变更时可能导致内存泄漏
- **风险**: 中。可能导致内存泄漏，尤其在配置变更时
- **建议**: 引入生命周期感知机制，或使用弱引用

### C3: 测试工具类与生产代码逻辑重复
- **位置**: `android/app/src/test/java/com/netproxy/gateway/vpn/VpnTestUtils.kt`
- **问题描述**: 测试工具方法复制了生产代码逻辑（如 parseDestinationIp, parseProtocol, calculateChecksum 等），存在同步风险
- **风险**: 低。测试和生产代码可能不一致
- **建议**: 提取公共逻辑到可复用的工具类，或直接使用生产代码工具方法
- **验证状态**: 已确认重复 - VpnTestUtils 中的方法与 VpnService.kt 中的私有方法实现逻辑相同

### C4: 内存中敏感数据处理不当
- **位置**: `android/app/src/main/java/com/netproxy/gateway/connection/AuthSessionStore.kt`
- **问题描述**: 内存中的 token 在 `loadSession()` 中转换为 String，失去 CharArray 清零能力
- **风险**: 中。敏感数据在内存中停留时间更长
- **建议**: 避免将 CharArray 转换为 String，保持 CharArray 形式传递

### C5: 服务端缺少共享库
- **位置**: `server/api/`, `server/socks5-proxy/`, `server/tunnel/`
- **问题描述**: 三个 Go 服务各自独立，存在代码重复（如限流器、配置解析等）
- **风险**: 低。维护成本增加，修改需要多处同步
- **建议**: 提取公共代码到 `server/pkg/` 或 `server/internal/` 共享库

### C6: 缺少 API 契约定义
- **位置**: 服务端与客户端通信接口（MQTT消息格式、REST API端点）
- **问题描述**: 服务间通信缺乏统一的接口定义（如 protobuf 或 OpenAPI）
- **风险**: 低。接口变更时容易遗漏
- **建议**: 定义 OpenAPI 规范或 protobuf 契约

### C7: Go 项目结构不规范
- **位置**: `server/`
- **问题描述**: 未按标准 Go 项目结构分层（如 `internal/`、`pkg/` 等）
- **风险**: 低。代码组织不够清晰
- **建议**: 重构为标准 Go 项目结构

### C8: VpnService 清理逻辑不一致
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt` (L927-973, L1023-1048)
- **问题描述**: 
  - `stopVpn()` 和 `onDestroy()` 中的资源清理逻辑不一致
  - `stopVpn()` 顺序: `serviceScope.cancel()` → `cleanupVpnResources()` → `stopProxyService()` → `stopForeground()`
  - `onDestroy()` 顺序: `cleanupVpnResources()` → `stopProxyService()` → `serviceScope.cancel()`
  - `onDestroy()` 缺少 `stopForeground()` 调用
  - 两者都操作 `isStopping` 标志，但 `onDestroy()` 不重置该标志
- **风险**: 中。不一致的清理顺序可能导致竞态条件或资源泄漏
- **建议**: 提取统一的清理方法，确保两处使用相同的清理顺序和逻辑

### C9: Tunnel Gateway 单点部署限制
- **位置**: `server/tunnel/main.go`
- **问题描述**: 隧道连接状态存储在单机内存中（`map[string]*TunnelConn`），没有使用 Redis 等共享存储，无法实现多实例负载均衡
- **风险**: 中。影响高可用部署，单点故障时服务完全不可用
- **建议**: 引入 Redis 存储连接状态，支持多实例部署和负载均衡

### C10: 缺少 Makefile 统一构建流程
- **位置**: `server/`
- **问题描述**: 项目没有 Makefile，Go 服务构建需要通过手动执行 `go build` 或使用 Docker，没有统一的测试、构建、发布流程
- **风险**: 低。开发效率受影响，新成员上手困难
- **建议**: 添加 Makefile，提供统一的 build、test、lint、docker-build 等命令

### C11: 服务间硬编码地址
- **位置**: `server/socks5-proxy/main.go`, `server/tunnel/main.go`
- **问题描述**: 服务间通信使用硬编码的 localhost 地址（如 `--api=http://localhost:8080`），没有使用服务发现机制
- **风险**: 低-中。容器化/Kubernetes 环境下需要手动配置环境变量
- **建议**: 支持通过环境变量或配置中心动态配置服务地址，或引入 Consul/etcd 服务发现

### C12: VpnServiceTest 未验证关键生命周期场景 [已验证确认]
- **位置**: `android/app/src/test/java/com/netproxy/gateway/vpn/VpnServiceTest.kt`
- **问题描述**: 
  - 测试类未真正验证 `GatewayVpnService.stopVpn()` 和 `startVpn()` 的交互
  - 没有测试 `serviceScope` 取消后重新启动的行为（H9问题的回归测试）
  - 没有针对并发安全问题的专项测试（H11-H13）
  - 测试主要基于状态值的模拟验证，而非实际方法调用
- **风险**: 中。关键缺陷缺乏回归测试，修复后可能再次引入
- **建议**: 
  1. 添加 `stopVpn()` 后 `startVpn()` 重新启动的集成测试
  2. 添加并发安全问题的压力测试
  3. 使用真实（但隔离的）依赖替代纯模拟测试

### C1: 双重连接风险（理论风险，实际影响低）[LOW]

**问题描述**:
`connect()` 方法中，两个同步块之间存在时间窗口：
1. 第一个同步块：清空 `mqttClient`
2. 同步块外：关闭旧客户端、创建新客户端
3. 第二个同步块：设置新客户端

在此期间，如果另一个线程也调用 `connect()`，可能导致两个连接同时创建。

**代码位置**:
- `MqttConnectionManager.kt:181-196`

**当前代码**:
```kotlin
// 1. 在同步块内只获取旧客户端引用并清空 mqttClient
val oldClient = synchronized(this@MqttConnectionManager) {
    mqttClient.also { mqttClient = null }
}

// 2. 在同步块外执行 close() IO 操作
oldClient?.close()

// 3. 在同步块外创建新客户端
val newClient = MqttClient(brokerUrl, clientId, MemoryPersistence())

// 4. 在新同步块内设置新客户端
val localClient = synchronized(this@MqttConnectionManager) {
    mqttClient = newClient
    newClient
}
```

**验证结果** (2026-04-10):
- **风险存在性**: 理论上有双重连接风险
- **现有保护机制**:
  1. `connectionGeneration` 机制在 `connect()` 开始时就递增，后完成的线程会因为 generation 不匹配而放弃
  2. L255-274 的同步块在连接成功后检查 `mqttClient === localClient`，确保只有当前有效连接才能设置 `Connected` 状态
- **实际风险**: **已有效缓解**。虽然理论上可能创建两个 `MqttClient` 实例，但 `connectionGeneration` 和引用检查机制能防止状态混乱

**状态**: 已验证，风险可控，作为可选优化项保留

---

## 已修复问题（历史记录）

### FIXED: 心跳失败后未触发重连
- **修复提交**: aa1afd6
- **修复时间**: 2026-04-10
- **修复内容**: 修改 `startHeartbeat()` 签名，添加 `authToken` 和 `generation` 参数，在心跳失败时调用 `scheduleReconnect()`
- **验证结果** (2026-04-10): **未完全修复**。虽然添加了 `authToken` 和 `generation` 参数，但心跳失败检测机制本身存在问题：`publish()` 内部捕获所有异常，外部 `try-catch` 块永远不会执行。需要进一步修复，参见 `ISSUES.md` H1。

### FIXED: 同步块内 IO 操作阻塞 disconnect()
- **修复提交**: 5d0778b
- **修复时间**: 2026-04-10
- **修复内容**: 将 `close()` IO 操作移出同步块，避免阻塞其他线程

### FIXED: 连接成功后竞态条件
- **修复提交**: 702511e
- **修复时间**: 2026-04-10
- **修复内容**: 使用同步块保护所有状态检查和更新，确保只有当前有效连接才能设置 Connected 状态

---

## 技术债务 vs 软件缺陷

本文档仅记录**技术债务**类问题：
- 代码可以工作，但有改进空间
- 理论风险，实际影响较低
- 设计或实现可以优化

**软件缺陷**（功能错误、崩溃、安全漏洞等）应记录在 `ISSUES.md` 中。

# 技术债务清单

## 当前活跃问题

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

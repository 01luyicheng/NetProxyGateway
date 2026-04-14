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
- **注意**: H9/H14/H15修复后，serviceScope和连接池的清理问题已解决，但清理顺序仍可统一优化。

### C9: Tunnel Gateway 单点故障与状态丢失风险
- **位置**: `server/tunnel/`
- **问题描述**: Tunnel Gateway使用内存存储连接状态，属于有状态服务，无法实现无状态化水平扩展，存在单点故障风险
- **附加风险**:
  - **数据丢失风险**: 故障时内存状态丢失导致用户连接中断
  - **恢复成本**: 用户需要重新建立连接，数据传输丢失
- **风险**: 中。当前小规模部署可接受，未来需要高可用时需重新设计架构（引入分布式状态存储）
- **建议**: 引入分布式状态存储（如Redis），实现无状态化以支持水平扩展

### C10: 服务发现硬编码与迁移成本
- **位置**: `server/docker-compose.yml`, 各服务配置
- **问题描述**: 服务间通过docker-compose网络通信，使用静态环境变量配置地址。缺乏动态服务发现机制
- **附加风险**:
  - **运维复杂度**: 服务地址变更需修改环境变量并重启容器，无法实现零停机更新
  - **扩展限制**: 无法动态发现多个服务实例，难以实现负载均衡和故障转移
- **风险**: 低。当前部署方式简单直接，但扩展时需考虑服务注册与发现方案
- **建议**: 引入服务注册与发现方案（如Consul、etcd或Kubernetes DNS）

### C11: 缺少 Makefile 统一构建流程
- **位置**: `server/`
- **问题描述**: 项目没有 Makefile，Go 服务构建需要通过手动执行 `go build` 或使用 Docker，没有统一的测试、构建、发布流程
- **风险**: 低。开发效率受影响，新成员上手困难
- **建议**: 添加 Makefile，提供统一的 build、test、lint、docker-build 等命令

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

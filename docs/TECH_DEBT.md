# 技术债务清单

> **编号说明**: C1 为预留编号，当前未使用。编号从 C2 开始。

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
- **注意**: H15修复后，serviceScope和连接池的部分清理问题已解决，但清理顺序仍可统一优化；H14 仍未完全修复。

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

### C11: Makefile 统一构建流程 [已解决]
- **状态**: 已解决（基础目标已具备）
- **修复提交**: a8ab465
- **位置**: 仓库根目录 `Makefile`
- **现状**: 已提供 `android-build`/`android-test`/`go-build`/`go-test`/`docker-*`/`all`/`clean` 等目标；`make android-test` 为项目验证门禁
- **可选后续**: 补充统一 `lint`、`release` 打包目标（非阻塞）

### C12: VpnServiceTest 未验证关键生命周期场景 [已验证确认]
- **位置**: `android/app/src/test/java/com/netproxy/gateway/vpn/VpnServiceTest.kt`
- **问题描述**: 
  - 测试类未真正验证 `GatewayVpnService.stopVpn()` 和 `startVpn()` 的交互
  - 没有测试 `serviceScope` 取消后重新启动的行为（回归测试缺失）
  - 没有针对并发安全问题的专项测试（H11-H12）
  - 测试主要基于状态值的模拟验证，而非实际方法调用
- **风险**: 中。关键缺陷缺乏回归测试，修复后可能再次引入
- **建议**: 
  1. 添加 `stopVpn()` 后 `startVpn()` 重新启动的集成测试
  2. 添加并发安全问题的压力测试
  3. 使用真实（但隔离的）依赖替代纯模拟测试

### C14: 函数过长且职责不单一 [待修复]
- **状态**: 待修复（2026-04-15 Subagents代码审查发现）
- **位置**: 
  - `android/app/src/main/java/com/netproxy/gateway/connection/MqttConnectionManager.kt` (L172-412, `connect()`约240行)
  - `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt` (L628-694, `constructReturnPacket()`约66行)
- **问题描述**: 多个函数超过50行，包含过多职责。`connect()`方法包含同步块、任务取消、连接建立、回调设置、状态管理等；`constructReturnPacket()`包含大量逐字节操作
- **风险**: 中。代码难以理解和测试
- **建议**: 
  - 将`connect()`拆分为`prepareConnection()`、`establishMqttConnection()`、`setupCallbacks()`、`startHeartbeat()`
  - 将`constructReturnPacket`拆分为`buildIpHeader()`、`buildTcpHeader()`、`calculateChecksums()`

### C15: 嵌套层级过深 [待修复]
- **状态**: 待修复（2026-04-15 Subagents代码审查发现）
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt` (L412-477, `forwardViaSocks5()`)
- **问题描述**: 函数嵌套层级过深，包含多个if-else嵌套和try-catch块，难以跟踪逻辑流程
- **风险**: 低。代码可读性差，容易引入bug
- **建议**: 使用早期返回模式，将连接借用逻辑提取为独立方法

### C16: 代码缺少适当分组和空行 [待修复]
- **状态**: 待修复（2026-04-15 Subagents代码审查发现）
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt` (L289-302), `Socks5ConnectionPool.kt`
- **问题描述**: 逻辑块之间缺少空行，属性定义没有分组，难以快速理解类结构
- **风险**: 低。影响代码可读性
- **建议**: 在逻辑步骤之间添加空行，使用代码分组注释（如`// ==================== Connection Pool State ====================`）

### C17: Go服务端代码风格不一致 [已解决]
- **状态**: 已解决（2026-06-06）
- **修复提交**: (当前工作区)
- **位置**: `server/socks5-proxy/main.go`, `server/api/main.go`
- **修复内容**:
  - SOCKS5 代理：将 auth version `0x01` 替换为 `authSubVersion` 常量
  - SOCKS5 代理：将 reply 中的 `0x00` 占位符替换为 `ipv4ReplyPlaceholder` 常量
  - SOCKS5 代理：统一错误消息风格，`authentication failed` → `authentication failed: invalid credentials`
  - API 服务：`generateSecureRandomString` 已优化（见 C18）
- **风险**: 低。维护困难
- **建议**: 
  - 统一错误处理风格
  - 定义常量：`const (SocksVersion5 = 0x05; AuthMethodPassword = 0x02)`
  - 将参数封装为结构体

### C18: generateSecureRandomString性能可优化 [已解决]
- **状态**: 已解决（2026-06-06）
- **修复提交**: (当前工作区)
- **位置**: `server/api/main.go`
- **问题描述**: 每次循环分配新内存，频繁进行系统调用。拒绝采样阈值计算正确但存在性能优化空间
- **修复内容**:
  - 预分配 `result` 和 `batch` 缓冲区，避免每次迭代分配
  - 批量读取随机字节（`length * 4`），将 `crypto/rand.Read` 系统调用次数减少约 75%
  - 使用 `pos` 索引遍历，拒绝采样循环内直接填充结果
- **风险**: 低。性能开销可接受，但已优化

### C19: 开发模式安全检查可进一步增强 [待修复]
- **状态**: 待修复
- **位置**: `server/api/main.go` (L108-137)
- **问题描述**: 当前只检查了`ENABLE_TLS`，但还有其他生产环境指标应该检查，如端口号、域名/IP限制、日志级别等
- **风险**: 低。当前检查已足够，但可进一步增强
- **建议**: 添加更多生产环境检查：
  - 检查端口号：生产环境通常使用443端口，开发环境使用8080
  - 检查域名/IP限制：生产环境可能有特定的域名配置
  - 检查日志级别：生产环境通常使用结构化日志

### C20: VpnService日志模板格式不一致 [已解决]
- **状态**: 已解决（2026-06-06）
- **修复提交**: (当前工作区)
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt`
- **问题描述**: 日志消息模板中分隔符`-`的使用格式不一致，有的带前后空格，有的不带空格。不影响功能但降低日志可读性和一致性
- **修复内容**:
  - 统一所有日志为 `"key: value"` 风格
  - `Cannot start VPN while stopping` → `action: startVpn, status: rejected, reason: VPN is currently stopping`
  - `Skip unsupported protocol=$protocol for WiFi route` → `action: forwardViaWifi, status: skipped, reason: unsupported protocol, protocol: $protocol`
  - `Forward via WiFi failed for ${redactIp(destinationIp)}` → `action: forwardViaWifi, status: failed, destination: ${redactIp(destinationIp)}`
  - `N80: Registered disconnect listener...` → `action: registerDisconnectListener, status: success, device: $deviceId`
  - `N80: Failed to register disconnect listener` → `action: registerDisconnectListener, status: failed`
  - `onDestroy() called without stopVpn()` → `action: onDestroy, status: cleanup, reason: stopVpn was not called`
- **风险**: 低。日志格式已统一

### C21: VpnLogRedaction缺少KDoc文档 [已解决]
- **状态**: 已解决（2026-06-06）
- **修复提交**: (当前工作区)
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnLogRedaction.kt`
- **问题描述**: 公共函数`redactIp()`和`redactConnectionKey()`缺少KDoc文档注释，未说明函数用途、参数格式和返回值格式
- **修复内容**:
  - 为 `redactIp()` 添加完整 KDoc，说明参数、返回值和三种输出格式
  - 为 `redactConnectionKey()` 添加完整 KDoc，说明输入格式、处理逻辑和返回值
- **风险**: 低。代码意图已明确

### C22: VpnLogRedaction魔法值未命名 [已解决]
- **状态**: 已解决（2026-06-06）
- **修复提交**: (当前工作区)
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnLogRedaction.kt`
- **问题描述**: 代码中使用字面量`4`（IPv4段数）、`6`（最小脱敏长度）、`2`（连接键分段数）等魔法值，未提取为命名常量，降低可读性
- **修复内容**:
  - `4` → `IPV4_PART_COUNT`
  - `3` → `IPV4_MAX_PART_LENGTH`
  - `0` / `255` → `IPV4_MIN_VALUE` / `IPV4_MAX_VALUE`
  - `2` → `CONNECTION_KEY_SEGMENTS`
  - `"***"` → `REDACT_MASK`
- **风险**: 低。代码可读性已提升

### C23: notifyStatusBackoff位移溢出风险 [已修复]
- **状态**: 已修复
- **提交哈希**: d06f584（修复提交）
- **位置**: `server/tunnel/main.go` (L327-337)
- **问题描述**: `notifyStatusBackoff`函数使用`1<<(attempt-1)`计算退避乘数，`1`是无类型整数常量。当前`maxAttempts=3`是安全的，但如果未来调大最大尝试次数，在32位系统上`attempt>=63`时会发生位移溢出。函数缺乏上限保护
- **修复方式**: 添加上限保护`maxAttempt=30`，使用显式`int64(1)`进行位移

### C24: http.Client未复用连接池 [已修复]
- **状态**: 已修复
- **提交哈希**: d06f584（修复提交）
- **位置**: `server/tunnel/main.go` (L179-194)
- **问题描述**: `notifyDeviceStatus`每次调用都创建新的`http.Client`，无法复用TCP连接池。在高频设备上下线场景（如网络抖动导致频繁重连），会造成大量短连接，增加延迟和系统负载
- **修复方式**: 将`httpClient`作为`TunnelManager`字段，在`NewTunnelManager`中初始化

### C25: notifyDeviceStatus goroutine泄漏风险 [已修复]
- **状态**: 已修复
- **提交哈希**: d06f584（修复提交）
- **位置**: `server/tunnel/main.go` (L179-194, L250-325)
- **问题描述**: `notifyDeviceStatus`立即启动goroutine执行HTTP请求，重试过程中使用`time.Sleep`阻塞。如果`TunnelManager`被销毁或服务器关闭，这些goroutine会持续阻塞在sleep中直到重试完成，无法被提前取消
- **修复方式**: 为`TunnelManager`添加`ctx context.Context`和`cancel context.CancelFunc`字段，重试循环中使用`select`监听`ctx.Done()`，添加`Stop()`方法用于取消

### C26: notifyDeviceStatus测试存在flaky风险 [部分缓解]
- **状态**: 部分缓解（见 C60）
- **提交哈希**: d06f584（首次修复）
- **位置**: `server/tunnel/main_test.go`
- **问题描述**: 重试成功路径曾用固定 `time.Sleep` 等待，慢 CI 下易 flaky
- **缓解**: 重试用例改为 `select` + `requestSignal` 同步等待各次请求
- **残余**: “成功后无额外请求”断言仍用 `time.After(300ms)`（`main_test.go` L94–97），慢 CI 下仍可能误报；详见 **C60**

### C27: notifyDeviceStatus测试覆盖不足 [已修复]
- **状态**: 已修复
- **提交哈希**: d06f584（修复提交）
- **位置**: `server/tunnel/main_test.go`
- **问题描述**: 新增测试缺少以下关键场景：1) 3次重试全部失败的边界；2) 429(TooManyRequests)触发重试；3) `notifyStatusBackoff`退避时间计算的正确性
- **修复方式**: 补充`TestNotifyStatusBackoff`、`TestNotifyDeviceStatusExhaustsRetries`、`TestNotifyDeviceStatusRetriesOnTooManyRequests`三个测试

---

## 技术债务 vs 软件缺陷

本文档仅记录**技术债务**类问题：
- 代码可以工作，但有改进空间
- 理论风险，实际影响较低
- 设计或实现可以优化

**软件缺陷**（功能错误、崩溃、安全漏洞等）应记录在 `ISSUES.md` 中。

---

## 新增问题（待分类）

### C28: VpnService processVpnTraffic FileInputStream未关闭 [已修复]
- **状态**: 已修复
- **修复提交**: 8398c13 (Agent: SOLO, 2026-05-15)
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt`
- **修复方式**: 使用 `FileInputStream.use` 块确保流在退出时被正确关闭

### C29: VpnService forwardViaWifi TCP无响应读取
- **提交哈希**: 1f9acee
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt` (L403-L411)
- **问题描述**: TCP `forwardViaWifi` 只发送 payload 不读取响应，`Socket().use` 块结束后立即关闭。若目标服务器需握手会丢失响应数据
- **风险**: 中。TCP 转发不完整，可能导致协议交互失败
- **修复难度**: 中。需要实现简单的响应读取或改为单向 UDP 转发模式

### C30: VpnService cleanupStaleConnections removeIf遍历风险
- **提交哈希**: 1f9acee
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt` (L513-L531)
- **问题描述**: `removeIf` 遍历 `ConcurrentHashMap` 时 lambda 中调用 `pool?.returnConnection()` 是阻塞操作，可能长时间持有内部锁
- **风险**: 中。影响 `activeConnections` 的并发访问性能
- **修复难度**: 中。将过期连接收集到列表后移出锁范围再清理

### C31: VpnService startVpn状态与资源初始化顺序不一致 [已修复]
- **状态**: 已修复
- **修复提交**: bd36914 (Agent: SOLO, 2026-05-15)
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt`
- **修复方式**: `startVpn()` 中先初始化所有资源（`initializeConnectionPool()`、`vpnOutputStream`）再更新状态为 `RUNNING`

### C32: VpnService injectPacket静默丢弃注入失败 [已修复]
- **状态**: 已修复
- **修复提交**: bd36914 (Agent: SOLO, 2026-05-15)
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt`
- **修复方式**: `injectPacket()` 返回 `Boolean`，调用方在注入失败时清理无效会话

### C33: VpnService processReturnTraffic遍历视图不一致
- **提交哈希**: 1f9acee
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt` (L543)
- **问题描述**: `activeConnections.forEach` 遍历中若 `processTcpReturn` 内部修改 map，可能看到不一致视图，某些新加入条目被跳过
- **风险**: 中。高并发下可能遗漏新连接的返回流量处理
- **修复难度**: 中。使用快照复制或更安全的遍历策略

### C34: VirtualIpAllocator锁内require异常导致死锁
- **提交哈希**: 1f9acee
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VirtualIpAllocator.kt` (L92-L94)
- **问题描述**: `synchronized(ipAllocationLock)` 块内 `require()` 失败抛出异常，若 `nextVirtualIp` 被恶意修改，分配器在持锁状态下崩溃，后续所有分配线程阻塞
- **风险**: 中。极端情况下 IP 分配完全停止
- **修复难度**: 低。将 `require` 移出锁范围或改为安全断言

### C35: VirtualIpAllocator破坏外部AtomicInteger封装
- **提交哈希**: 1f9acee
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VirtualIpAllocator.kt` (L30-L37, L107-L108)
- **问题描述**: `getOrAllocateVirtualIp` 接收外部 `AtomicInteger` 参数并直接 `nextVirtualIp.set()` 修改，破坏调用方封装
- **风险**: 中。调用方可能持有引用并在分配器外部修改，导致不可预期行为
- **修复难度**: 低。将 `nextVirtualIp` 作为分配器内部状态管理

### C36: Socks5ConnectionPool borrowConnection连接追踪泄漏
- **提交哈希**: 1f9acee
- **位置**: `android/app/src/main/java/com/netproxy/gateway/proxy/Socks5ConnectionPool.kt` (L137-L148)
- **问题描述**: `queue.poll()` 取出无效连接后，在 `write` 锁清理前若发生异常或线程中断，连接可能既不在队列也不在 `allConnections` 中，造成临时追踪泄漏
- **风险**: 中。socket 可能未关闭且未被追踪
- **修复难度**: 中。在 `read` 锁内立即关闭无效连接或确保清理不可中断

### C37: Socks5ConnectionPool returnConnection O(n)性能瓶颈
- **提交哈希**: 1f9acee
- **位置**: `android/app/src/main/java/com/netproxy/gateway/proxy/Socks5ConnectionPool.kt` (L205-L210)
- **问题描述**: `returnConnection` 在 `write` 锁内遍历 `queue` 和 `allConnections` 计算连接数，O(n) 复杂度，高并发下阻塞读写操作
- **风险**: 中。高并发场景下连接归还性能下降
- **修复难度**: 中。维护每个目标地址的连接计数器，避免遍历

### C38: Socks5ConnectionPool readFully无限循环风险
- **提交哈希**: 1f9acee
- **位置**: `android/app/src/main/java/com/netproxy/gateway/proxy/Socks5ConnectionPool.kt` (L395-L420)
- **问题描述**: `readFully` 中若 `input.read()` 持续返回 0，while 循环无限执行，CPU 空转
- **风险**: 中。某些 InputStream 实现可能导致线程空转
- **修复难度**: 低。添加最大重试次数或超时检查

### C39: Socks5ProxyHandler NettyOutboundConnector未设置连接超时
- **提交哈希**: 1f9acee
- **位置**: `android/app/src/main/java/com/netproxy/gateway/proxy/Socks5ProxyHandler.kt` (L245-L270)
- **问题描述**: `Bootstrap` 未设置 `ChannelOption.CONNECT_TIMEOUT_MILLIS` 或 `SO_TIMEOUT`，连接可能长时间挂起占用 event loop 线程
- **风险**: 中。上游服务器不可达时连接挂起
- **修复难度**: 低。添加连接超时配置

### C40: Socks5ProxyService closeFuture.sync阻塞协程
- **提交哈希**: 1f9acee
- **位置**: `android/app/src/main/java/com/netproxy/gateway/proxy/Socks5ProxyService.kt` (L114-L115)
- **问题描述**: `serverChannel?.closeFuture()?.sync()` 阻塞协程线程，若 `closeFuture` 永远不触发，协程永远挂起
- **风险**: 中。异常路径下协程泄漏
- **修复难度**: 低。使用 `await()` 替代 `sync()` 或添加超时

### C41: Socks5ProxyService shutdownGracefully无超时
- **提交哈希**: 1f9acee
- **位置**: `android/app/src/main/java/com/netproxy/gateway/proxy/Socks5ProxyService.kt` (L206-L207)
- **问题描述**: `shutdownGracefully()` 默认无超时，若存在挂起连接可能长时间阻塞 `onDestroy()`
- **风险**: 低。Service 销毁延迟
- **修复难度**: 低。添加超时参数

### C42: MqttConnectionManager subscribe回调可重复注册
- **提交哈希**: 1f9acee
- **位置**: `android/app/src/main/java/com/netproxy/gateway/connection/MqttConnectionManager.kt` (L570-L588)
- **问题描述**: `subscribeWithResult` 将 callback 添加到 `CopyOnWriteArrayList` 前不做去重，同一回调可被重复注册导致重复触发
- **风险**: 中。消息重复处理或 UI 状态异常抖动
- **修复难度**: 低。添加去重检查或 Set 结构

### C43: MqttConnectionManager startHeartbeat并发启动风险
- **提交哈希**: 1f9acee
- **位置**: `android/app/src/main/java/com/netproxy/gateway/connection/MqttConnectionManager.kt` (L493-L542)
- **问题描述**: `startHeartbeat` 先 `cancel()` 再赋值新 Job，非原子操作，旧 Job 尚未完成取消时新 Job 已启动，短暂双心跳并行
- **风险**: 中。心跳频率翻倍，增加网络负载
- **修复难度**: 低。使用原子操作或 Mutex 保护 Job 赋值

### C44: NetworkStateManager onAvailable瞬态未验证状态
- **提交哈希**: 1f9acee
- **位置**: `android/app/src/main/java/com/netproxy/gateway/connection/NetworkStateManager.kt` (L38-L40)
- **问题描述**: `onAvailable` 可能在 `onCapabilitiesChanged`（携带 VALIDATED）之前触发，下游可能收到 `isValidated=false` 瞬态并做出错误决策
- **风险**: 中。MQTT 连接决策可能基于未验证的网络状态
- **修复难度**: 中。延迟发射或过滤瞬态状态

### C45: AuthSessionStore锁内执行加密磁盘IO可能ANR
- **提交哈希**: 1f9acee
- **位置**: `android/app/src/main/java/com/netproxy/gateway/connection/AuthSessionStore.kt` (L57-L68)
- **问题描述**: `update()` 在 `@Synchronized` 锁内执行 `EncryptedSharedPreferences` 读写，涉及 MasterKey 解密和 AES-GCM，主线程调用可能导致 ANR
- **风险**: 中。UI 线程调用时可能触发 ANR
- **修复难度**: 中。将加密 IO 移到后台线程

### C46: GatewayWifiManager activeSuggestions非线程安全
- **提交哈希**: 1f9acee
- **位置**: `android/app/src/main/java/com/netproxy/gateway/wifi/WifiManager.kt` (L67, L417, L471)
- **问题描述**: `activeSuggestions` 是普通 `List` 变量，无同步机制，`clearNetworkSuggestions` 的读取-修改-写入序列非原子，并发时可能丢失更新
- **风险**: 中。WiFi 建议列表状态不一致
- **修复难度**: 低。使用 `AtomicReference` 或 `volatile` 修饰

### C47: GatewayWifiManager disconnect未移除遗留网络配置
- **提交哈希**: 1f9acee
- **位置**: `android/app/src/main/java/com/netproxy/gateway/wifi/WifiManager.kt` (L441-L447)
- **问题描述**: Android 10 以下 `disconnect()` 仅调用 `wifiManager.disconnect()`，不移除之前 `addNetwork()` 的配置，造成配置污染和安全风险
- **风险**: 中。开放网络配置残留可能导致设备自动重连
- **修复难度**: 低。添加 `removeNetwork()` 调用

### C48: GatewayWifiManager hashCode去重不可靠
- **提交哈希**: 1f9acee
- **位置**: `android/app/src/main/java/com/netproxy/gateway/wifi/WifiManager.kt` (L458)
- **问题描述**: `distinctBy { it.hashCode() }` 去重不可靠，hashCode 碰撞可能导致不同 suggestion 被错误去重
- **风险**: 低。WiFi 建议去重不准确
- **修复难度**: 低。使用 SSID 等稳定标识去重

### C49: AppModule新分离式接口未提供Hilt绑定
- **提交哈希**: 1f9acee
- **位置**: `android/app/src/main/java/com/netproxy/gateway/di/AppModule.kt` (L15-L41)
- **问题描述**: `ModuleInterfaces.kt` 中新的分离式接口未提供 `@Binds` 绑定，新接口完全不可注入，技术债务无法消除
- **风险**: 中。新接口设计无法使用，迫使继续使用已弃用接口
- **修复难度**: 低。为新接口添加 `@Binds` 绑定

### C50: ModuleCoordinator modules Map非线程安全
- **提交哈希**: 1f9acee
- **位置**: `android/app/src/main/java/com/netproxy/gateway/di/ModuleCoordinator.kt` (L54, L56-L63)
- **问题描述**: `modules` 使用 `mutableMapOf` 非线程安全，`registerModule`/`getModule` 可能在不同线程调用，存在并发修改风险
- **风险**: 中。并发修改导致数据竞争或崩溃
- **修复难度**: 低。使用 `ConcurrentHashMap` 替代

### C51: ModuleCoordinator SharedFlow事件静默丢失
- **提交哈希**: 1f9acee
- **位置**: `android/app/src/main/java/com/netproxy/gateway/di/ModuleCoordinator.kt` (L31-L38)
- **问题描述**: `MutableSharedFlow` 默认 buffer=0/replay=0，无 collector 时事件静默丢弃，重要事件（如 ConnectionStateChange）可能丢失
- **风险**: 中。配置变更期间事件丢失导致 UI 状态不一致
- **修复难度**: 低。配置 `replay=1` 或 `extraBufferCapacity`

### C52: ModuleCoordinator强引用导致生命周期对象泄漏
- **提交哈希**: 1f9acee
- **位置**: `android/app/src/main/java/com/netproxy/gateway/di/ModuleCoordinator.kt` (L57)
- **问题描述**: `registerModule` 以强引用存入 `modules`，若注册生命周期对象，Activity 销毁时无法 GC，内存泄漏
- **风险**: 中。Activity/Fragment 泄漏
- **修复难度**: 中。使用弱引用或生命周期感知注册

### C53: SecurityManager安全检测报告包含敏感信息
- **提交哈希**: 1f9acee
- **位置**: `android/app/src/main/java/com/netproxy/gateway/security/SecurityManager.kt` (L157-L187)
- **问题描述**: `getSecurityReport()` 返回包含详细 root 路径、调试器信息、模拟器特征的字符串，任何调用方（包括日志上报）都可能泄露设备指纹
- **风险**: 中。设备指纹信息泄露增加被针对性攻击风险
- **修复难度**: 低。限制报告访问权限或脱敏处理

### C54: AppAuditLogStore日志内容未脱敏
- **提交哈希**: 1f9acee
- **位置**: `android/app/src/main/java/com/netproxy/gateway/debug/AppAuditLogStore.kt` (L48-L73)
- **问题描述**: `sanitizeMessage()` 仅移除换行符和截断长度，未对 IP、配对码、Token、SSID 等脱敏。审计日志在 UI 完全可见，截图即可泄露敏感信息
- **风险**: 中。敏感信息通过审计日志界面泄露
- **修复难度**: 中。集成 `VpnLogRedaction` 脱敏逻辑

### C55: MainScreen配对码输入无验证
- **提交哈希**: 1f9acee
- **位置**: `android/app/src/main/java/com/netproxy/gateway/ui/screens/MainScreen.kt` (L417-L422)
- **问题描述**: `OutlinedTextField` 未限制数字键盘、未过滤非数字字符、未限制最大长度，用户可输入任意字符
- **风险**: 中。输入验证缺失，超长输入可能导致显示异常
- **修复难度**: 低。添加 `keyboardOptions` 和 `onValueChange` 过滤

### C56: MainScreen审计日志滚动位置丢失
- **提交哈希**: 1f9acee
- **位置**: `android/app/src/main/java/com/netproxy/gateway/ui/screens/MainScreen.kt` (L286-L333)
- **问题描述**: `AuditLogsScreen` 的 `LazyColumn` 未使用 `rememberLazyListState()`，配置变更后滚动位置丢失
- **风险**: 低。用户体验问题
- **修复难度**: 低。添加 `rememberLazyListState()`

### C57: server/socks5-proxy relay错误处理不完整
- **提交哈希**: 1f9acee
- **位置**: `server/socks5-proxy/main.go` (L1282-L1310)
- **问题描述**: `io.Copy` 错误只保留第一个非预期错误，第二个被丢弃。错误过滤依赖字符串匹配，不够健壮
- **风险**: 低。故障排查信息不完整
- **修复难度**: 低。收集所有错误或使用错误包装

### C58: server/socks5-proxy relay测试flaky
- **提交哈希**: 1f9acee
- **位置**: `server/socks5-proxy/main_test.go` (L294-L332)
- **问题描述**: `TestRelay_ClosesPeerConnectionOnHalfClose` 依赖 `time.After(2s)` 超时，慢速 CI 下可能 flaky
- **风险**: 低。测试可靠性
- **修复难度**: 低。使用同步原语替代固定超时

### C59: server/tunnel sendLoop双重select效率低
- **提交哈希**: 1f9acee
- **位置**: `server/tunnel/main.go` (L653-L677)
- **问题描述**: `sendLoop` 外层 `select` 读取数据后内层又检查 `closeChan`，增加复杂度且可能不必要地尝试写入
- **风险**: 低。代码复杂度
- **修复难度**: 低。合并为单层 select

### C60: server/tunnel notify测试仍有flaky风险
- **提交哈希**: 1f9acee
- **关联**: C26（重试路径已同步化；“无额外请求”分支未完全消除固定超时）
- **位置**: `server/tunnel/main_test.go`（如 `TestNotifyDeviceStatusRetriesAndEventuallySucceeds` 中 L94–97）
- **问题描述**: 成功重试后仍用 `time.After(300ms)` 断言无第三次请求，慢 CI 下可能 flaky
- **风险**: 低。测试可靠性
- **修复难度**: 低。用 channel/计数器或 `httptest` 钩子替代固定 300ms 窗口

### C61: server/socks5-proxy IPFilter IPv6处理不完整
- **提交哈希**: 1f9acee
- **位置**: `server/socks5-proxy/main.go` (L248-L273)
- **问题描述**: `IPFilter.IsAllowed` 对 IPv6 支持不完整，域名解析只选第一个 IPv4，若域名只有 IPv6 会返回 false
- **风险**: 低。IPv6 场景下过滤失效
- **修复难度**: 中。添加 IPv6 CIDR 支持

### C62: VpnService connectionKey格式未来IPv6冲突
- **提交哈希**: 1f9acee
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt` (L435)
- **问题描述**: `connectionKey` 使用 `"$srcIp:$srcPort-$destinationIp:$destinationPort"` 简单拼接，IPv6 地址含 `:` 和 `-` 会产生解析歧义
- **风险**: 低。未来 IPv6 支持时 key 冲突
- **修复难度**: 低。使用结构化 key 或编码处理

### C63: VpnService onDestroy重复调用stopProxyService
- **提交哈希**: 1f9acee
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt` (L1102-L1136)
- **问题描述**: `onDestroy()` 中若 `stopVpn()` 已被调用过，`stopProxyService()` 会被调用两次，虽幂等但冗余
- **风险**: 低。代码冗余
- **修复难度**: 低。添加状态检查避免重复调用

### C64: VpnDnsConfig isValidIpv4接受前导零
- **提交哈希**: 1f9acee
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnDnsConfig.kt` (L67-L82)
- **问题描述**: `octet.toIntOrNull() in 0..255` 接受前导零（如 `01`），严格模式下可能被解析为八进制导致语义不一致
- **风险**: 低。IP 验证宽松
- **修复难度**: 低。拒绝含前导零的 octet

### C65: VpnLogRedaction IPv6验证缺陷
- **提交哈希**: 1f9acee
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnLogRedaction.kt` (L35-L55)
- **问题描述**: `isValidIpv6` 对 IPv4-mapped IPv6 和空字符串处理有缺陷，且依赖 `InetAddress.getByName` 有性能开销
- **风险**: 低。日志脱敏不准确
- **修复难度**: 低。改进验证逻辑

### C66: AuthSessionStore constantTimeEquals空指针风险
- **提交哈希**: 1f9acee
- **位置**: `android/app/src/main/java/com/netproxy/gateway/connection/AuthSessionStore.kt` (L193-L200)
- **问题描述**: `constantTimeEquals` 未对参数做 null 检查，未来调用方传入 null 会 NPE
- **风险**: 低。防御性编程缺失
- **修复难度**: 低。添加 null 检查

### C67: GatewayWifiManager WiFiScan Flow receiver注销竞态
- **提交哈希**: 1f9acee
- **位置**: `android/app/src/main/java/com/netproxy/gateway/wifi/WifiManager.kt` (L74-L95)
- **问题描述**: `callbackFlow` 的 `awaitClose` 注销 receiver，若 collector 在 `registerReceiver` 后快速取消，存在短暂 receiver 残留
- **风险**: 低。极端场景下 receiver 泄漏
- **修复难度**: 低。使用 `try-finally` 确保注销

### C68: ModuleInterfaces新接口返回类型设计缺陷
- **提交哈希**: 1f9acee
- **位置**: `android/app/src/main/java/com/netproxy/gateway/di/ModuleInterfaces.kt` (L39-L44)
- **问题描述**: `CommunicationCommandHandler.publish()` 返回 `Unit` 无法传递失败信息；`WiFiCommandHandler.scanWiFi()` 同步返回与异步扫描语义不符
- **风险**: 低。接口设计与实现不一致
- **修复难度**: 中。修改接口返回类型

### C69: MqttTlsPinning每次创建新MessageDigest
- **提交哈希**: 1f9acee
- **位置**: `android/app/src/main/java/com/netproxy/gateway/connection/MqttTlsPinning.kt` (L68-L71)
- **问题描述**: 每次 TLS 握手都创建新 `MessageDigest.getInstance("SHA-256")`，高频率重连时造成 GC 压力
- **风险**: 低。性能优化空间
- **修复难度**: 低。缓存 MessageDigest 实例或使用线程本地存储

### C70: server/api 管理员密码明文存储
- **提交哈希**: f8497b8
- **位置**: `server/api/main.go` (L138-L142)
- **问题描述**: `ADMIN_USER` 和 `ADMIN_PASS` 从环境变量读取后直接字符串比较，无 bcrypt 等慢哈希存储。进程环境变量可被同一机器其他用户读取（`/proc/<pid>/environ`），密码在内存中以明文 String 存在且无复杂度要求
- **风险**: 中。密码泄露风险，不符合安全存储最佳实践
- **修复难度**: 中。使用 bcrypt 存储密码哈希，启动时验证复杂度，读取后立即覆盖内存

### C71: server/tunnel Stats接口单一Token长期有效
- **提交哈希**: f8497b8
- **位置**: `server/tunnel/main.go` (L757-L772)
- **问题描述**: `authorizeStats` 使用单一 `StatsToken` 进行鉴权，Token 长期有效无过期机制。如果 Token 泄露，攻击者可长期访问统计信息。没有限流保护和访问日志
- **风险**: 中。统计信息泄露，无法追踪异常访问
- **修复难度**: 低。添加 Token 轮换机制、IP 白名单、访问日志和限流

### C72: 内部API调用缺少重试和熔断机制
- **提交哈希**: f8497b8
- **位置**: `server/socks5-proxy/main.go` (L480-L547), `server/tunnel/main.go` (L527-L583)
- **问题描述**: `validateWithAPI` 和 `validateDeviceToken` 在 API 服务暂时不可用时直接失败，没有重试机制。`notifyDeviceStatus` 虽有重试但其他内部调用没有。缺乏熔断保护，API 服务故障时可能级联影响
- **风险**: 中。服务间调用不可靠，单点故障级联扩散
- **修复难度**: 中。统一内部 HTTP 客户端配置，添加重试、超时和熔断机制

### C73: Go服务端缺少结构化日志
- **提交哈希**: f8497b8
- **位置**: `server/api/main.go`, `server/socks5-proxy/main.go`, `server/tunnel/main.go`
- **问题描述**: 三个服务均使用标准库 `log` 包打印日志，缺少日志级别、结构化字段（如 request_id、device_id）、日志轮转等能力。不利于生产环境故障排查和监控集成
- **风险**: 低。运维和故障排查效率受影响
- **修复难度**: 低。引入 `slog` 或 `zap` 等结构化日志库，统一日志格式

### C74: SOCKS5连接池缺少并发回归测试
- **提交哈希**: f8497b8
- **位置**: `android/app/src/test/java/com/netproxy/gateway/proxy/Socks5ConnectionPoolTest.kt`
- **问题描述**: 测试仅覆盖单线程场景。ISSUES.md H5 记录的"连接池清理竞争条件"是关键缺陷，但测试中没有并发借用/归还/清理的竞态测试，修复后缺乏回归保护
- **风险**: 中。关键缺陷缺乏回归测试，修复后可能再次引入
- **修复难度**: 中。添加多线程并发测试，模拟 borrow/return/cleanup 竞态条件

### C76: VpnService 回包缓冲区缺少分配行为回归测试
- **提交哈希**: d01ddd1
- **位置**: `android/app/src/test/java/com/netproxy/gateway/vpn/VpnServiceTest.kt`
- **问题描述**: N52/N54 已将 ThreadLocal 方案替换为局部变量方案（`val buffer = ByteArray(PACKET_BUFFER_SIZE)`），现有测试未验证该分配行为在高并发场景下的内存表现，也未覆盖 `processTcpReturn` 的 `available() > 0` 边界条件。
- **风险**: 低。缺少回归保护，后续重构可能重新引入 ThreadLocal 或不当的缓冲策略
- **修复难度**: 低。补充 `processTcpReturn` 在 `available()` 返回不同值时的行为测试

### C77: Socks5ProxyHandler double-free 修复缺少 write-failure 回归测试
- **提交哈希**: 9f4b1b9
- **位置**: `android/app/src/test/java/com/netproxy/gateway/proxy/Socks5ProxyHandlerTest.kt` (L22 起)
- **问题描述**: N45 的修复修改了 `RelayHandler` 的 write-failure 分支，但当前测试只覆盖认证和 CONNECT 流程，没有构造 `relayChannel.writeAndFlush(msg)` 失败的路径来验证不会再次 release `msg`。
- **风险**: 低。该修复点缺乏测试保护，未来容易被误改回双重释放
- **修复难度**: 低。增加一个模拟 write 失败的 Netty 回归测试

### C78: VpnService 回包路径缺少TCP状态机
- **提交哈希**: 1f9acee
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt` (L582-L624, L649-L726)
- **问题描述**: `processTcpReturn()` 仅在有数据可读时构造回包（`available > 0 && read > 0`），无法发送纯TCP控制包（ACK/FIN/RST）。`constructReturnPacket()` 固定设置 `PSH+ACK` flags，序列号和确认号固定为0。这导致TCP连接建立/终止流程不完整，依赖对端容忍非标准行为。
- **风险**: 中。与严格TCP实现不兼容，可能导致连接建立失败或异常断开
- **修复难度**: 高。需要实现完整的TCP状态机，正确管理序列号、确认号和标志位
- **关联问题**: ISSUES.md H14, N36

### C79: StreamConn deadline 方法空实现导致 goroutine 泄漏
- **提交哈希**: 9f4b1b9
- **位置**: `server/socks5-proxy/main.go` (L464-L476)
- **问题描述**: 详见 ISSUES.md N56。`SetReadDeadline`、`SetDeadline`、`SetWriteDeadline` 三个方法均为空实现，`Read()` 阻塞 select 无超时保护，远端静默时永久阻塞导致 goroutine 泄漏。
- **风险**: 高
- **修复难度**: 中
- **关联问题**: ISSUES.md N56

### C80: processReturnTraffic 单协程串行处理模型 [已修复]
- **状态**: 已修复
- **提交哈希**: d01ddd1
- **修复提交**: (当前工作区)
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt` (processReturnTraffic)
- **问题描述**: ~~详见 ISSUES.md N57。`processReturnTraffic` 使用单协程串行遍历所有活跃连接，单个连接 I/O 阻塞会导致所有后续连接回包处理停滞。~~ 已改为 `coroutineScope { async(Dispatchers.IO) }` 并行模型，每个连接独立协程处理回包。
- **修复方式**: 串行 `snapshot.forEach` 改为并行 `coroutineScope { snapshot.map { async(Dispatchers.IO) { ... } }.awaitAll() }`
- **关联问题**: ISSUES.md N57, TECH_DEBT.md C78

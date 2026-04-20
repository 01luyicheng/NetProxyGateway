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

### C13: 魔法数字和硬编码协议常量 [待修复]
- **状态**: 待修复（2026-04-15 Subagents代码审查发现）
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt` (L383, L391, L628-694)
- **问题描述**: 代码中大量使用魔法数字（如协议号6/17、IP包头字段0x45、TTL值64等），没有提取为命名常量，降低可读性和可维护性
- **风险**: 低。代码可读性差，容易出错
- **代码**:
  ```kotlin
  when (protocol) {
      17 -> {  // UDP - 魔法数字
      6 -> {   // TCP - 魔法数字
  }
  buffer[0] = 0x45  // IPv4, IHL=5 - 魔法数字
  buffer[8] = 64    // TTL - 魔法数字
  ```
- **建议**: 提取为命名常量：
  ```kotlin
  companion object {
      private const val PROTOCOL_TCP = 6
      private const val PROTOCOL_UDP = 17
      private const val IP_VERSION_IPV4 = 0x45
      private const val IP_DEFAULT_TTL = 64
  }
  ```

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

### C17: Go服务端代码风格不一致 [待修复]
- **状态**: 待修复（2026-04-15 Subagents代码审查发现）
- **位置**: `server/socks5-proxy/main.go`, `server/api/main.go`
- **问题描述**: 
  - 错误处理风格不一致，有些地方使用`fmt.Errorf`，有些使用`log.Printf`
  - 魔法数字未命名（如SOCKS5版本0x05、认证方法0x02等）
  - 函数参数过多（如`ConnectThroughTunnel`有4个参数）
- **风险**: 低。维护困难
- **建议**: 
  - 统一错误处理风格
  - 定义常量：`const (SocksVersion5 = 0x05; AuthMethodPassword = 0x02)`
  - 将参数封装为结构体

### C18: generateSecureRandomString性能可优化 [待修复]
- **状态**: 待修复（2026-04-15 Subagents代码审查发现）
- **位置**: `server/api/main.go` (L254-273)
- **问题描述**: 每次循环分配新内存，频繁进行系统调用。拒绝采样阈值计算正确但存在性能优化空间
- **风险**: 低。性能开销可接受，但可优化
- **建议**: 预分配足够大的缓冲区，批量读取随机字节，减少系统调用和内存分配
- **代码示例**:
  ```go
  func generateSecureRandomString(length int) (string, error) {
      const charset = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
      const charsetLen = 62
      const threshold = 256 - (256 % charsetLen)
      
      result := make([]byte, length)
      buf := make([]byte, length*2) // 预分配缓冲区
      bufIdx := 0
      
      for i := 0; i < length; {
          if bufIdx >= len(buf) {
              if _, err := rand.Read(buf); err != nil {
                  return "", fmt.Errorf("crypto/rand.Read failed: %w", err)
              }
              bufIdx = 0
          }
          if int(buf[bufIdx]) < threshold {
              result[i] = charset[int(buf[bufIdx])%charsetLen]
              i++
          }
          bufIdx++
      }
      return string(result), nil
  }
  ```

### C19: 开发模式安全检查可进一步增强 [待修复]
- **状态**: 待修复
- **位置**: `server/api/main.go` (L108-137)
- **问题描述**: 当前只检查了`ENABLE_TLS`，但还有其他生产环境指标应该检查，如端口号、域名/IP限制、日志级别等
- **风险**: 低。当前检查已足够，但可进一步增强
- **建议**: 添加更多生产环境检查：
  - 检查端口号：生产环境通常使用443端口，开发环境使用8080
  - 检查域名/IP限制：生产环境可能有特定的域名配置
  - 检查日志级别：生产环境通常使用结构化日志

### C20: VpnService日志模板格式不一致 [新发现-待修复]
- **状态**: 待修复
- **提交哈希**: fc9552b53dde93aea4ddb7afda4a4240e906a6ee
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt` (L438, L468, L524, L617)
- **问题描述**: 日志消息模板中分隔符`-`的使用格式不一致，有的带前后空格（如`"... -> virtualIP: ..."`），有的不带空格（如`"...-$destinationIp:..."`）。不影响功能但降低日志可读性和一致性
- **风险**: 低。日志格式不统一，但不影响功能
- **建议**: 统一日志模板格式，建议采用`"key: value"`风格，分隔符前后保持一致的空格策略
- **代码示例**:
  ```kotlin
  // L438: 无空格格式
  "$srcIp:$srcPort-$destinationIp:$destinationPort"
  // L468: 有前后空格
  "${redactConnectionKey(connectionKey)} -> virtualIP: ${redactIp(virtualSrcIp)}"
  ```

### C21: VpnLogRedaction缺少KDoc文档 [新发现-待修复]
- **状态**: 待修复
- **提交哈希**: fc9552b53dde93aea4ddb7afda4a4240e906a6ee
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnLogRedaction.kt`
- **问题描述**: 公共函数`redactIp()`和`redactConnectionKey()`缺少KDoc文档注释，未说明函数用途、参数格式和返回值格式
- **风险**: 低。代码意图不明确，增加维护成本
- **建议**: 添加KDoc文档：
  ```kotlin
  /**
   * 对IP地址进行脱敏处理。
   * IPv4地址返回"*.*.*.*"，其他格式返回部分隐藏形式。
   * @param ip 原始IP地址字符串
   * @return 脱敏后的IP地址字符串
   */
  internal fun redactIp(ip: String): String
  ```

### C22: VpnLogRedaction魔法值未命名 [新发现-待修复]
- **状态**: 待修复
- **提交哈希**: fc9552b53dde93aea4ddb7afda4a4240e906a6ee
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnLogRedaction.kt` (L4, L5, L8, L12, L13)
- **问题描述**: 代码中使用字面量`4`（IPv4段数）、`6`（最小脱敏长度）、`2`（连接键分段数）等魔法值，未提取为命名常量，降低可读性
- **风险**: 低。代码可读性差，维护困难
- **建议**: 提取为命名常量：
  ```kotlin
  private const val IPV4_PART_COUNT = 4
  private const val MIN_REDACT_LENGTH = 6
  private const val CONNECTION_KEY_SEGMENTS = 2
  private const val REDACT_MASK = "***"
  ```

### C23: notifyStatusBackoff位移溢出风险 [代码审查发现-待修复]
- **状态**: 待修复
- **提交哈希**: 当前工作区未提交变更
- **位置**: `server/tunnel/main.go` (L299-305)
- **问题描述**: `notifyStatusBackoff`函数使用`1<<(attempt-1)`计算退避乘数，`1`是无类型整数常量。当前`maxAttempts=3`是安全的，但如果未来调大最大尝试次数，在32位系统上`attempt>=63`时会发生位移溢出。函数缺乏上限保护
- **风险**: 中。当前安全，但对常量变更不敏感，存在未来溢出风险
- **建议**: 添加上限保护或使用显式`int64`位移：
  ```go
  func notifyStatusBackoff(attempt int) time.Duration {
      if attempt <= 0 {
          attempt = 1
      }
      const maxAttempt = 30
      if attempt > maxAttempt {
          attempt = maxAttempt
      }
      multiplier := int64(1) << (attempt - 1)
      return defaultNotifyStatusBaseBackoff * time.Duration(multiplier)
  }
  ```

### C24: http.Client未复用连接池 [代码审查发现-待修复]
- **状态**: 待修复
- **提交哈希**: 当前工作区未提交变更
- **位置**: `server/tunnel/main.go` (L238)
- **问题描述**: `notifyDeviceStatus`每次调用都创建新的`http.Client`，无法复用TCP连接池。在高频设备上下线场景（如网络抖动导致频繁重连），会造成大量短连接，增加延迟和系统负载
- **风险**: 中。高频通知场景下性能受影响
- **建议**: 将`http.Client`作为`TunnelManager`的字段，在构造时初始化，或使用全局带连接池的client

### C25: notifyDeviceStatus goroutine泄漏风险 [代码审查发现-待修复]
- **状态**: 待修复
- **提交哈希**: 当前工作区未提交变更
- **位置**: `server/tunnel/main.go` (L237)
- **问题描述**: `notifyDeviceStatus`立即启动goroutine执行HTTP请求，重试过程中使用`time.Sleep`阻塞。如果`TunnelManager`被销毁或服务器关闭，这些goroutine会持续阻塞在sleep中直到重试完成，无法被提前取消
- **风险**: 中。服务关闭时goroutine无法优雅退出
- **建议**: 为`TunnelManager`添加`context.Context`支持，允许取消进行中的通知；或将`notifyDeviceStatus`改为同步调用，由调用方决定是否启动goroutine

### C26: notifyDeviceStatus测试存在flaky风险 [代码审查发现-待修复]
- **状态**: 待修复
- **提交哈希**: 当前工作区未提交变更
- **位置**: `server/tunnel/main_test.go` (L85, L122)
- **问题描述**: `TestNotifyDeviceStatusRetriesAndEventuallySucceeds`和`TestNotifyDeviceStatusDoesNotRetryOnBadRequest`使用固定`time.Sleep(300ms)`验证无额外请求。在慢速CI环境或高负载下可能失败，是flaky test的典型来源
- **风险**: 中。测试不稳定，可能导致CI随机失败
- **建议**: 移除`time.Sleep`，改用channel同步或`sync.WaitGroup`精确等待。例如，在收到预期请求后，使用`select`+`time.After`验证没有额外请求到达

### C27: notifyDeviceStatus测试覆盖不足 [代码审查发现-待修复]
- **状态**: 待修复
- **提交哈希**: 当前工作区未提交变更
- **位置**: `server/tunnel/main_test.go`
- **问题描述**: 新增测试缺少以下关键场景：1) 3次重试全部失败的边界；2) 429(TooManyRequests)触发重试；3) `notifyStatusBackoff`退避时间计算的正确性；4) 网络错误（非HTTP错误）触发重试；5) 2xx/3xx直接成功不重试
- **风险**: 低。核心重试逻辑的关键边界未验证
- **建议**: 补充上述缺失测试，确保重试逻辑的所有分支都被覆盖

---

## 技术债务 vs 软件缺陷

本文档仅记录**技术债务**类问题：
- 代码可以工作，但有改进空间
- 理论风险，实际影响较低
- 设计或实现可以优化

**软件缺陷**（功能错误、崩溃、安全漏洞等）应记录在 `ISSUES.md` 中。

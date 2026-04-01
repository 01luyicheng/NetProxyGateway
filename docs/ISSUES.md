# NetProxyGateway 缺陷清单
## Critical

### C1: SSL信任所有证书配置风险 [已降级为Medium]
- **状态**: 已降级至Medium优先级
- **位置**: `android/app/src/main/java/com/netproxy/gateway/connection/MqttConnectionManager.kt` (L95-L111)
- **问题**: 生产环境已有强制检查机制，当`DEBUG=false`且`MQTT_TRUST_ALL_CERTS=true`时会抛出`IllegalStateException`阻止应用启动。建议增加构建时静态检查作为额外防护
- **风险**: 配置错误导致应用无法启动（已实现运行时防护），建议增强构建时检查
- **代码**:
  ```kotlin
  private fun createSecureSocketFactory(): SSLSocketFactory {
      // 安全检查：生产环境 (DEBUG=false) 不允许启用信任所有证书
      if (!BuildConfig.DEBUG && BuildConfig.MQTT_TRUST_ALL_CERTS) {
          throw IllegalStateException(
              "TRUST_ALL_CERTS is not allowed in production builds. " +
              "Please set MQTT_TRUST_ALL_CERTS to false in build configuration."
          )
      }
      return if (BuildConfig.MQTT_TRUST_ALL_CERTS) {
          createDevSocketFactory()
      } else {
          createProductionSocketFactory()
      }
  }
  ```
- **建议修复**: 添加构建时Lint静态检查或Gradle插件验证，确保release构建配置中`MQTT_TRUST_ALL_CERTS=false`

### C3: 虚拟IP分配线程不安全 [已修复]
- **状态**: 已修复（2026-04-01）
- **位置**:
  - `android/app/src/main/java/com/netproxy/gateway/vpn/VirtualIpAllocator.kt`
  - `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt`
- **问题描述**: 原实现在 `ipNum=255` 时可能分配广播地址 `10.0.0.255`，且并发语义不清晰。
- **修复说明**: 已提取 `VirtualIpAllocator` 统一分配逻辑，溢出时先重置再回退到 `10.0.0.1`，并通过同步临界区保证分配与映射更新一致性。
- **验证**: 新增 `VirtualIpAllocatorTest`（边界与并发回归），并通过 `VpnServiceTest`、全量 Android 单测与 `assembleDebug`。
- **代码（修复后）**:
  ```kotlin
  // VpnService.kt
  private fun getOrAllocateVirtualIp(realDstIp: String): String {
      return VirtualIpAllocator.getOrAllocateVirtualIp(
          realDstIp = realDstIp,
          virtualIpPool = virtualIpPool,
          reverseIpMap = reverseIpMap,
          nextVirtualIp = nextVirtualIp,
          onPoolReset = { logger.error("Virtual IP pool exhausted! Resetting pool.") }
      )
  }
  ```

---

## High

### H1: SOCKS5代理DNS重绑定攻击风险
- **位置**: `android/app/src/main/java/com/netproxy/gateway/proxy/Socks5ProxyHandler.kt` (L107-131)
- **问题**: 代码已有IP范围验证（拒绝回环、链路本地、广播、保留地址，仅允许RFC1918私有地址），但DNS重绑定风险仍然存在。攻击者可能通过快速切换DNS记录绕过IP验证窗口
- **风险**: 攻击者可能通过DNS重绑定绕过IP验证，访问内网资源
- **建议修复**:
  1. 使用DNS缓存并验证解析结果
  2. 检查解析后的IP是否与目标域名匹配
  3. 考虑使用DNS-over-HTTPS (DoH)
- **代码**:
  ```kotlin
  private fun validateTargetAddress(host: String, port: Int): Boolean {
      // ... 端口验证 ...
      val inetAddr = java.net.InetAddress.getByName(host)
      val ip = inetAddr.hostAddress ?: return false
      // IP范围验证：拒绝127.x, 169.254.x, 0.0.0.0, 255.255.255.255, 224.x
      // 仅允许RFC1918私有地址
      IpAddressUtils.isPrivateIpv4Rfc1918(ip)
  }
  ```

### H2: MQTT异常处理不完善 [已解决]
- **状态**: 已解决
- **说明**: 协程结构（SupervisorJob）已提供异常隔离，不会导致应用崩溃；重连机制已实现指数退避，最大延迟60秒
- **代码位置**: `android/app/src/main/java/com/netproxy/gateway/di/CoroutineScopes.kt` (L24-L26)
- **验证**: `ApplicationScope`使用`CoroutineScope(SupervisorJob() + Dispatchers.IO)`，子协程异常不会影响其他协程

### H4: 连接池清理竞争条件
- **位置**: `android/app/src/main/java/com/netproxy/gateway/proxy/Socks5ConnectionPool.kt` (L343-L366)
- **问题**: read锁和write锁之间连接状态可能变化
- **风险**: 清理过期连接时可能误删有效连接，或漏删无效连接
- **建议修复**:
  1. 在write锁内重新验证连接状态
  2. 或使用CopyOnWriteArrayList简化并发控制
  3. 添加单元测试验证竞争条件处理

### H6: SOCKS5连接池读取未设置超时
- **位置**: `android/app/src/main/java/com/netproxy/gateway/proxy/Socks5ConnectionPool.kt` (L315-L329)
- **问题**: `readFully`方法没有设置超时，可能永久阻塞
- **风险**: 线程被永久阻塞，连接池资源耗尽
- **建议修复**:
  1. 为socket读取操作设置超时
  2. 使用带超时的读取方法
  3. 添加心跳检测机制

### H7: MQTT TLS证书固定配置可能为空
- **位置**: `android/app/src/main/java/com/netproxy/gateway/connection/MqttConnectionManager.kt` (L117-L124)
- **问题**: 当`MQTT_TLS_PUBLIC_KEY_PINS`为空时，仅记录警告，仍使用默认CA验证
- **风险**: 生产环境可能意外使用不安全的证书验证方式
- **建议修复**:
  1. 生产环境强制要求配置证书固定
  2. 空配置时抛出异常而非仅警告
  3. 添加构建时检查确保配置正确

---

## Medium

### M1: 边界条件：IP地址解析验证
- **位置**: `android/app/src/main/java/com/netproxy/gateway/utils/IpAddressUtils.kt` (L6-L18)
- **问题**: `isPrivateIpv4Rfc1918`方法本身没有验证每个octet是否在0-255范围内
- **实际情况**: `validateIpv4WithResult`方法已实现完整的octet范围验证（0-255），可供调用方使用
- **建议修复**: 确保调用方在使用`isPrivateIpv4Rfc1918`前先调用`validateIpv4WithResult`进行验证，或统一使用带验证的方法

### M2: WiFi管理器权限检查不一致
- **位置**: `android/app/src/main/java/com/netproxy/gateway/wifi/WifiManager.kt` (L210-L223)
- **问题**: 同一功能有两个版本，一个静默失败，一个返回错误
- **风险**: 调用方无法统一处理错误，可能导致未预期的行为
- **建议修复**: 统一错误处理方式，移除静默失败版本

### M3: Root检测执行命令未超时
- **位置**: `android/app/src/main/java/com/netproxy/gateway/security/RootDetector.kt` (L203-L214, L242-L252)
- **问题**: `process.waitFor()`没有设置超时，如果命令挂起会阻塞线程
- **风险**: 线程被永久阻塞，影响应用响应
- **建议修复**: 使用`waitFor(timeout, TimeUnit)`替代

### M4: VPN服务忙等待 [已移除]
- **状态**: 已移除，问题不存在
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/GatewayVpnService.kt` (L540-L546)
- **说明**: 当前代码已实现指数退避算法，空闲延迟从1ms增长到最大100ms，已优化忙等待问题
- **备注**: 原问题描述已过时，优化已实现

### M9: TCP回包状态管理不完整
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt` (L647-L666)
- **问题**: 序列号和确认号固定为0，不符合TCP协议
- **风险**: 与某些TCP实现不兼容，可能导致连接异常
- **建议修复**: 正确管理TCP序列号和确认号

### M10: MQTT重连延迟计算可能溢出 [已移除]
- **状态**: 已移除，问题不存在
- **位置**: `android/app/src/main/java/com/netproxy/gateway/connection/MqttConnectionManager.kt` (L245-L255)
- **说明**: 代码使用`minOf(reconnectDelay * 2, MAX_RECONNECT_DELAY)`，初始值5000L，最大60000L，不可能发生溢出
- **备注**: 原问题描述错误，不存在溢出风险

### M11: 连接池状态检查与清理的竞态条件
- **位置**: `android/app/src/main/java/com/netproxy/gateway/proxy/Socks5ConnectionPool.kt` (L119-L148)
- **问题**: 代码在 read 锁内收集无效连接列表，然后在 write 锁外执行清理操作。在 read 锁释放后到 write 锁获取前的时间窗口内，连接状态可能已发生变化，导致清理操作基于过期的状态信息
- **风险**: 可能清理有效连接或保留无效连接
- **建议修复**: 在write锁内重新验证连接状态

### L2: TODO注释未处理
- **位置**: `android/app/src/main/java/com/netproxy/gateway/connection/MqttConnectionManager.kt` (L93)
- **问题**: 存在未处理的TODO注释，涉及安全配置
- **风险**: 已知问题被遗漏
- **建议修复**: 处理TODO或创建正式issue跟踪

### L3: EmulatorDetector权限检查重复
- **位置**: `android/app/src/main/java/com/netproxy/gateway/security/EmulatorDetector.kt`
- **问题**: 多个方法重复检查`READ_PHONE_STATE`权限
- **建议修复**: 提取权限检查为统一方法

### L5: 缺少集成测试
- **问题**: 测试主要集中在单元测试，缺少组件间集成测试
- **风险**: 组件间交互问题难以发现
- **建议修复**: 添加集成测试套件

### L13: 硬编码延迟
- **位置**: `android/app/src/main/java/com/netproxy/gateway/ui/viewmodel/MainViewModel.kt` (L165-L174)
- **问题**: 使用`delay(2000)`等待扫描完成是脆弱的设计
- **风险**: 在不同设备上表现不一致
- **建议修复**: 使用回调或状态监听替代固定延迟

---

## 文档变更记录

| 日期 | 变更内容 | 变更人 |
|------|----------|--------|
| 2026-03-31 | 重构文档结构，将技术债务、阻塞问题、已知限制、架构决策迁移到独立文档 | AI Agent |
| 2026-03-31 | 修正过时问题描述：H2标记为已解决（协程已提供异常隔离）、M4移除（已实现指数退避）、M10移除（不存在溢出风险）、M1更新描述（已有验证方法） | Bug修复专家 |
| 2026-03-31 | 确认文档完整性：ISSUES.md仅保留需要修复的软件缺陷，其他类型问题已正确迁移到专门文档 | AI Agent |
| 2026-03-31 | 修正H2引用错误：原"见M2"引用不正确（M2是WiFi权限问题），H2实际已解决（SupervisorJob已实现异常隔离），更新为"已解决"状态 | Bug修复专家 |
| 2026-03-31 | 更新交叉引用：添加与其他文档的关联链接（TECH_DEBT.md、BLOCKERS.md、KNOWN_LIMITATIONS.md、DECISIONS.md） | AI Agent |
| 2026-04-01 | 修复 C3 虚拟IP边界与并发分配问题：新增 `VirtualIpAllocator` 并补充边界/并发回归测试，避免分配 `10.0.0.255` | AI Agent |

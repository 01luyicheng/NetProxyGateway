# NetProxyGateway 缺陷清单（待修复）
## Critical

### C1: SSL信任所有证书配置风险 [已降级为Medium]
- **状态**: 已降级至Medium优先级
- **位置**: `android/app/src/main/java/com/netproxy/gateway/connection/MqttConnectionManager.kt` (L98-L114)
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

### H1: 心跳失败检测失效 [已验证确认]
- **状态**: 待修复（已验证确认存在）
- **验证时间**: 2026-04-10
- **位置**: `android/app/src/main/java/com/netproxy/gateway/connection/MqttConnectionManager.kt` (L329-343)
- **问题验证**: 
  - `publish()` 方法调用 `publishWithResult()` 并忽略返回值
  - `publishWithResult()` 内部捕获所有异常并返回 `AppResult`，不抛出异常
  - 外部 `try-catch` 块永远不会捕获到异常
  - **心跳连续失败检测机制完全失效**
- **风险**: **Critical**。当网络异常时，无法自动触发重连，连接可能处于"假死"状态而不被感知。
- **代码分析**:
  ```kotlin
  // L329-343: 问题代码
  try {
      publish("device/$deviceId/heartbeat", "{\"status\":\"alive\"}")  // 内部捕获所有异常
      consecutiveFailures = 0
  } catch (e: Exception) {  // 永远不会执行到这里
      // ...
  }
  
  // L348-364: publish() 和 publishWithResult() 实现
  fun publish(topic: String, payload: String, qos: Int = 0) {
      publishWithResult(topic, payload, qos)  // 返回 AppResult，不抛出异常
  }
  
  fun publishWithResult(...): AppResult<Unit> {
      return try {
          // ...
      } catch (e: Exception) {  // 所有异常被捕获
          AppResult.error(e)  // 返回错误结果，不抛出
      }
  }
  ```
- **建议修复**:
  ```kotlin
  val result = publishWithResult("device/$deviceId/heartbeat", "{\"status\":\"alive\"}")
  if (result.isSuccess) {
      consecutiveFailures = 0
  } else {
      logger.error("Heartbeat publish error", result.exceptionOrNull())
      consecutiveFailures++
      if (consecutiveFailures >= MAX_HEARTBEAT_FAILURES) {
          logger.warn("Max heartbeat failures reached, triggering reconnect")
          _connectionState.value = MqttConnectionState.Error("Max heartbeat failures reached")
          if (shouldStayConnected && generation == connectionGeneration.get()) {
              scheduleReconnect(deviceId, authToken, generation)
          }
          break
      }
  }
  ```

### H6: connectionLost 回调状态竞态 [已验证确认]
- **状态**: 待修复（已验证确认存在，风险较低）
- **验证时间**: 2026-04-10
- **位置**: `android/app/src/main/java/com/netproxy/gateway/connection/MqttConnectionManager.kt` (L214-224)
- **问题验证**:
  - L216 已检查 `generation`，但 L220 设置状态前没有再次验证
  - 在检查 generation 和设置状态之间存在时间窗口
  - 由于 MQTT 回调在单线程队列中执行，实际并发风险较低
- **风险**: 中。UI 可能显示错误状态，即使新连接已成功建立。
- **代码分析**:
  ```kotlin
  override fun connectionLost(cause: Throwable?) {
      if (generation != connectionGeneration.get()) {  // 第1次检查
          return
      }
      logger.warn("Connection lost: ${cause?.message}")
      // 此处可能 generation 已变化，但状态仍被设置
      _connectionState.value = MqttConnectionState.Error(cause?.message ?: "Connection lost")
      // ...
  }
  ```
- **建议修复**:
  ```kotlin
  override fun connectionLost(cause: Throwable?) {
      if (generation != connectionGeneration.get()) {
          return
      }
      logger.warn("Connection lost: ${cause?.message}")
      // 使用同步块保护状态设置，或再次检查 generation
      synchronized(this@MqttConnectionManager) {
          if (generation == connectionGeneration.get()) {
              _connectionState.value = MqttConnectionState.Error(cause?.message ?: "Connection lost")
          }
      }
      if (shouldStayConnected && generation == connectionGeneration.get()) {
          scheduleReconnect(deviceId, authToken, generation)
      }
  }
  ```

### H16: MQTT连接客户端创建竞态条件 [已验证确认]
- **状态**: 待修复（2026-04-11 Subagents深度验证确认）
- **验证时间**: 2026-04-11
- **验证方式**: Logic Analyzer Agent 代码审查
- **位置**: `android/app/src/main/java/com/netproxy/gateway/connection/MqttConnectionManager.kt` (L181-196)
- **问题验证**:
  - 创建新客户端（L190）和设置`mqttClient`（L193-196）之间有时间窗口
  - 两个操作不在同一个同步块内，可能被其他线程打断
  - 当并发执行`connect()`时，后创建的客户端可能覆盖先创建的客户端
- **竞态场景**:
  ```
  T1: 线程A创建newClientA，在获取锁之前被挂起
  T2: 线程B创建newClientB，获取锁并设置mqttClient = newClientB
  T3: 线程A恢复，获取锁并设置mqttClient = newClientA（覆盖了B的客户端）
  ```
- **风险**: 高。可能导致：
  - 客户端引用丢失，`newClientB`被覆盖后无法访问，造成资源泄露
  - 状态不一致，`_connectionState`被设置为`Connected`，但实际使用的是旧客户端
  - 心跳异常，两个线程可能同时运行心跳，或一个线程的心跳覆盖了另一个
  - 回调错乱，`connectionLost`回调中的generation检查可能无法正确处理
- **代码分析**:
  ```kotlin
  // L181-196: 问题代码
  val oldClient = synchronized(this@MqttConnectionManager) {
      mqttClient.also { mqttClient = null }
  }
  oldClient?.close()
  
  // 在同步块外创建新客户端
  val newClient = MqttClient(brokerUrl, clientId, MemoryPersistence())
  
  // 在新同步块内设置新客户端（可能被其他线程覆盖）
  val localClient = synchronized(this@MqttConnectionManager) {
      mqttClient = newClient
      newClient
  }
  ```
- **建议修复**:
  ```kotlin
  // 方案1: 在同步块内完成客户端创建和赋值（推荐）
  val newClient = synchronized(this@MqttConnectionManager) {
      // 关闭旧客户端
      mqttClient?.close()
      
      // 创建并设置新客户端（原子操作）
      val client = MqttClient(brokerUrl, clientId, MemoryPersistence())
      mqttClient = client
      client
  }
  ```

### H17: MQTT连接失败资源泄漏 [已验证确认]
- **状态**: 待修复（2026-04-11 Subagents深度验证确认）
- **验证时间**: 2026-04-11
- **验证方式**: Logic Analyzer Agent 代码审查
- **位置**: `android/app/src/main/java/com/netproxy/gateway/connection/MqttConnectionManager.kt` (L298-307)
- **问题验证**:
  - 在`connect()`方法的`catch`块中，如果`generation != connectionGeneration.get()`，会直接`return@launch`
  - 此时`localClient`（已经创建的MqttClient）可能没有被关闭
  - `MqttClient`内部持有网络连接资源（Socket、线程等），如果不调用`close()`，这些资源将一直占用
- **风险**: 高。在频繁重连场景下可能导致资源耗尽，影响系统稳定性。
- **代码分析**:
  ```kotlin
  // L298-307: 问题代码
  } catch (e: Exception) {
      if (generation != connectionGeneration.get()) {
          return@launch  // 直接返回，localClient可能未被关闭！
      }
      logger.error("MQTT connection error", e)
      _connectionState.value = MqttConnectionState.Error(e.message ?: "Connection failed")
      if (shouldStayConnected) {
          scheduleReconnect(deviceId, authToken, generation)
      }
  }
  ```
- **建议修复**:
  ```kotlin
  } catch (e: Exception) {
      // 无论generation是否匹配，都应该尝试关闭localClient
      try {
          localClient.disconnect()
      } catch (ex: MqttException) {
          logger.error("Disconnect error during exception handling", ex)
      } finally {
          try {
              localClient.close()
          } catch (ex: Exception) {
              logger.error("Close error during exception handling", ex)
          }
      }
      
      if (generation != connectionGeneration.get()) {
          return@launch
      }
      logger.error("MQTT connection error", e)
      _connectionState.value = MqttConnectionState.Error(e.message ?: "Connection failed")
      if (shouldStayConnected) {
          scheduleReconnect(deviceId, authToken, generation)
      }
  }
  ```

### H4: SOCKS5代理DNS重绑定攻击风险
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

### H3: 同步块内更新 StateFlow 可能导致死锁 [已验证确认]
- **状态**: 待修复（已验证确认存在）
- **验证时间**: 2026-04-10
- **位置**: `android/app/src/main/java/com/netproxy/gateway/connection/MqttConnectionManager.kt` (L254-274)
- **问题验证**:
  - `synchronized` 块内直接更新 `_connectionState.value`
  - StateFlow 的 `value` 设置会触发所有观察者（collect/collectLatest）的回调
  - 如果观察者在回调中也尝试获取同一把锁（`synchronized(this@MqttConnectionManager)`），会导致死锁
  - 即使不发生死锁，长时间持有锁也会影响其他线程调用 `connect()`/`disconnect()`
- **风险**: 中。可能导致 UI 线程阻塞或死锁，影响用户体验。
- **代码分析**:
  ```kotlin
  // L255-274: 问题代码
  val shouldProceed = synchronized(this@MqttConnectionManager) {
      // ...
      if (mqttClient === localClient) {
          _connectionState.value = MqttConnectionState.Connected  // 在同步块内更新 StateFlow！
          reconnectDelay = 5000L
          true
      } else {
          false
      }
  }
  ```
- **建议修复**:
  ```kotlin
  val (shouldProceed, currentClient) = synchronized(this@MqttConnectionManager) { 
      if (!shouldStayConnected || generation != connectionGeneration.get()) {
          if (mqttClient === localClient) {
              mqttClient = null
          }
          Pair(false, localClient)
      } else {
          if (mqttClient === localClient) {
              // 只在同步块内做引用检查，不更新 StateFlow
              Pair(true, localClient)
          } else {
              Pair(false, localClient)
          }
      }
  }
  
  if (shouldProceed) {
      _connectionState.value = MqttConnectionState.Connected  // 在同步块外更新
      reconnectDelay = 5000L
      subscribe("device/$deviceId/control")
      startHeartbeat(deviceId, authToken, generation)
  } else {
      // 清理操作...
  }
  ```

### H9: VPN服务 serviceScope 生命周期管理缺陷 [已修复]
- **状态**: ✅ **已修复**（2026-04-11 提交 4f58619）
- **修复验证**: Subagents代码审查确认修复正确
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt` (L103, L146-148, L207-214, L953-954, L1070-1071)
- **原问题**:
  - `serviceScope` 使用 `val` 在类实例化时创建，一旦取消无法再次使用
  - `stopVpn()` 调用 `serviceScope.cancel()` 后，协程作用域处于取消状态
  - 如果服务停止后再次启动，`startVpn()` 中 `serviceScope.launch { ... }` 会立即失败
  - 这导致 VPN 服务无法停止后再启动，必须重新创建服务实例
- **风险**: 高。用户停止 VPN 后无法重新启动
- **修复内容**:
  ```kotlin
  // L103: 将 val 改为 var nullable
  private var serviceScope: CoroutineScope? = null
  
  // L146-148: 在 onCreate 中创建
  override fun onCreate() {
      super.onCreate()
      createNotificationChannel()
      serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  }
  
  // L207-214: 使用安全调用
  serviceScope?.launch { processVpnTraffic() }
  serviceScope?.launch { processReturnTraffic() }
  
  // L953-954: stopVpn中取消并置null
  serviceScope?.cancel()
  serviceScope = null
  
  // L1070-1071: onDestroy中安全调用
  serviceScope?.cancel()
  serviceScope = null
  ```
- **修复验证**:
  - ✅ serviceScope从val改为var，支持重新创建
  - ✅ 在onCreate()中延迟创建，确保每次服务创建都有新作用域
  - ✅ 使用安全调用?.launch避免NPE
  - ✅ stopVpn()和onDestroy()中正确清理
  - ✅ 单元测试全部通过

### H5: 连接池清理竞争条件
- **位置**: `android/app/src/main/java/com/netproxy/gateway/proxy/Socks5ConnectionPool.kt` (L355-378)
- **问题**: read锁和write锁之间连接状态可能变化
- **风险**: 清理过期连接时可能误删有效连接，或漏删无效连接
- **建议修复**:
  1. 在write锁内重新验证连接状态
  2. 或使用CopyOnWriteArrayList简化并发控制
  3. 添加单元测试验证竞争条件处理

### H7: SOCKS5连接池读取未设置超时
- **位置**: `android/app/src/main/java/com/netproxy/gateway/proxy/Socks5ConnectionPool.kt` (L327-341)
- **问题**: `readFully`方法没有设置超时，可能永久阻塞
- **风险**: 线程被永久阻塞，连接池资源耗尽
- **建议修复**:
  1. 为socket读取操作设置超时
  2. 使用带超时的读取方法
  3. 添加心跳检测机制

### H8: MQTT TLS证书固定配置可能为空
- **位置**: `android/app/src/main/java/com/netproxy/gateway/connection/MqttConnectionManager.kt` (L131-138)
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
- **位置**: `android/app/src/main/java/com/netproxy/gateway/wifi/GatewayWifiManager.kt` (L211-L226)
- **问题**: 同一功能有两个版本，一个静默失败，一个返回错误
- **风险**: 调用方无法统一处理错误，可能导致未预期的行为
- **建议修复**: 统一错误处理方式，移除静默失败版本

### M3: Root检测执行命令未超时
- **位置**: `android/app/src/main/java/com/netproxy/gateway/security/RootDetector.kt` (L204-214, L240-L253)
- **问题**: `process.waitFor()`没有设置超时，如果命令挂起会阻塞线程
- **风险**: 线程被永久阻塞，影响应用响应
- **建议修复**: 使用`waitFor(timeout, TimeUnit)`替代

### M9: TCP回包状态管理不完整
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt` (L668-L687)
- **问题**: 序列号和确认号固定为0，不符合TCP协议
- **风险**: 与某些TCP实现不兼容，可能导致连接异常
- **建议修复**: 正确管理TCP序列号和确认号

### M11: 连接池状态检查与清理的竞态条件
- **位置**: `android/app/src/main/java/com/netproxy/gateway/proxy/Socks5ConnectionPool.kt` (L119-L148)
- **问题**: 代码在 read 锁内收集无效连接列表，然后在 write 锁外执行清理操作。在 read 锁释放后到 write 锁获取前的时间窗口内，连接状态可能已发生变化，导致清理操作基于过期的状态信息
- **风险**: 可能清理有效连接或保留无效连接
- **建议修复**: 在write锁内重新验证连接状态

### L2: TODO注释未处理
- **位置**: `android/app/src/main/java/com/netproxy/gateway/connection/MqttConnectionManager.kt` (L458)
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
- **位置**: `android/app/src/main/java/com/netproxy/gateway/ui/viewmodel/MainViewModel.kt` (L165-L173)
- **问题**: 使用 `delay(2000)` 等待扫描完成是脆弱的设计
- **风险**: 在不同设备上表现不一致
- **建议修复**: 使用回调或状态监听替代固定延迟

---

## 新增问题（待分类）

### N1: 双版本API增加维护负担
- **位置**: `android/app/src/main/java/com/netproxy/gateway/wifi/GatewayWifiManager.kt`, `AuthSessionStore.kt`
- **问题**: 每个主要操作都有两个版本（如 `startScan()` 和 `startScanWithResult()`），维护成本翻倍，容易出现版本间行为不一致
- **风险**: 中。代码冗余，维护困难
- **建议修复**: 统一使用 `AppResult` 模式，移除静默失败版本

### N2: VpnService过于庞大
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt` (1054行)
- **问题**: 包含 VPN 服务、数据包解析、连接管理、状态机等多个职责；`processPacket()`、`forwardViaSocks5()` 等函数超过 50 行
- **风险**: 中。代码难以理解和维护
- **建议修复**: 提取数据包解析为 `PacketParser`，提取连接管理为 `ConnectionManager`

### N3: 过度使用 @Synchronized
- **位置**: `android/app/src/main/java/com/netproxy/gateway/connection/AuthSessionStore.kt`
- **问题**: 所有方法都标记 `@Synchronized`，即使只是读取操作；使用类实例作为锁，粒度太粗
- **风险**: 低。可能影响并发性能
- **建议修复**: 使用 `ReentrantReadWriteLock` 区分读写锁，或使用 `ConcurrentHashMap` 等并发集合

### N4: DI模块接口设计混乱
- **位置**: `android/app/src/main/java/com/netproxy/gateway/di/ModuleInterfaces.kt`, `AppModule.kt`
- **问题**: 大量接口被标记 `@Deprecated`，但 `AppModule.kt` 中绑定的仍是已弃用接口
- **风险**: 中。编译器警告噪音，技术债务累积
- **建议修复**: 清理已弃用接口，更新 DI 绑定使用新接口

### N5: 状态管理分散
- **位置**: 多处
- **问题**: VPN 状态多处定义 - `VpnState` 在 `VpnService.kt`，`MqttConnectionState` 在 `MqttConnectionManager.kt`，`WiFiState` 在 `ModuleCoordinator.kt`
- **风险**: 低。状态定义分散，不利于统一管理
- **建议修复**: 统一状态定义到 `result` 包或专门的状态管理模块

### N6: 测试命名不一致
- **位置**: `android/app/src/test/java/`
- **问题**: 测试命名风格不一致，有的使用下划线命名（`vpnState_values()`），有的使用驼峰命名（`socks5Integration_connectionPoolConfig_defaults()`）
- **风险**: 低。影响代码可读性
- **建议修复**: 统一使用一种命名规范（推荐下划线命名法）

### N7: 测试质量不高
- **位置**: `android/app/src/test/java/com/netproxy/gateway/vpn/VpnServiceTest.kt`
- **问题**: 存在大量测试数据类自动生成方法（`equals()`、`hashCode()`、`toString()`）的测试，对业务价值贡献极低
- **风险**: 低。增加维护成本
- **建议修复**: 移除对自动生成方法的测试，专注于业务逻辑测试

### N8: 核心业务逻辑测试缺失
- **位置**: 测试目录
- **问题**: `processVpnTraffic()`、`forwardViaSocks5()`、`startHeartbeat()` 等核心业务逻辑缺乏测试
- **风险**: 高。回归风险大
- **建议修复**: 添加核心业务逻辑的单元测试和集成测试

### N9: 日志级别使用不当
- **位置**: 多处
- **问题**: 权限检查失败使用 `warn`，连接池正常清理也使用 `warn`；缺乏结构化日志
- **风险**: 低。日志噪音，不利于问题排查
- **建议修复**: 调整日志级别，使用结构化日志或 MDC

### N10: 监控指标缺失
- **位置**: 全局
- **问题**: 没有性能指标收集（连接建立时间、流量统计等），没有健康检查端点，没有错误上报机制
- **风险**: 中。难以发现和诊断线上问题
- **建议修复**: 添加关键指标收集和上报机制

### N11: 硬编码默认值不安全
- **位置**: `android/app/build.gradle.kts`
- **问题**: `mqttBrokerUrlTlsDebug` 等配置使用 `localhost` 作为默认值，可能意外连接到错误服务器
- **风险**: 低。仅影响 debug 构建
- **建议修复**: 移除默认值，强制在构建时配置

### N12: 运行时配置缺失
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt` (L96-99)
- **问题**: DNS 服务器列表硬编码，连接池参数硬编码，无法动态调整
- **风险**: 低。灵活性不足
- **建议修复**: 将配置提取到配置文件或远程配置中心

### N13: 已弃用API使用
- **位置**: 多处
- **问题**: 编译警告显示大量使用已弃用 API（`EncryptedSharedPreferences`、`WifiConfiguration`、`NioEventLoopGroup`、`hiltViewModel()` 等）
- **风险**: 中。未来 Android 版本可能移除这些 API
- **建议修复**: 逐步迁移到新 API

### N14: gorilla/websocket 已归档
- **位置**: `server/socks5-proxy/go.mod`, `server/tunnel/go.mod`
- **问题**: `gorilla/websocket` 库已被归档不再维护，存在技术债务
- **风险**: 中。安全漏洞无法及时修复
- **建议修复**: 迁移到 `nhooyr/websocket` 或 `gobwas/ws`

### N15: Paho MQTT 维护不活跃
- **位置**: `android/app/build.gradle.kts`
- **问题**: Eclipse Paho MQTT 项目维护不活跃
- **风险**: 中。新功能和 bug 修复可能延迟
- **建议修复**: 评估迁移到 HiveMQ MQTT Client 或 KMQTT

### L14: SSL 证书安全检查注释不准确 [已修复]
- **状态**: 已修复（2026-04-03）
- **位置**: `android/app/src/main/java/com/netproxy/gateway/connection/MqttConnectionManager.kt` (L91-L104)
- **问题**: 注释使用"生产环境/开发环境"术语，但代码检查的是 `BuildConfig.DEBUG`。虽然基本正确，但不够精确，可能引起误解
- **风险**: 低，术语不准确可能导致开发者对构建配置的理解混淆
- **修复说明**: 将"生产环境/开发环境"改为"release 构建/debug 构建"，将"生产环境 (DEBUG=false)"改为"非调试版本 (DEBUG=false)"，更准确地反映代码逻辑
- **代码（修复后）**:
  ```kotlin
  /**
   * 根据 BuildConfig 配置决定使用哪种证书验证方式：
   * - release 构建：使用系统默认 CA 证书（验证服务器证书）
   * - debug 构建：信任所有证书（仅用于开发测试自签名证书）
   * TODO: 上线前将 BuildConfig.MQTT_TRUST_ALL_CERTS 改为 false
   */
  private fun createSecureSocketFactory(): SSLSocketFactory {
      // 安全检查：非调试版本 (DEBUG=false) 不允许启用信任所有证书
      if (!BuildConfig.DEBUG && BuildConfig.MQTT_TRUST_ALL_CERTS) {
          throw IllegalStateException(
              "TRUST_ALL_CERTS is not allowed in production builds. " +
              "Please set MQTT_TRUST_ALL_CERTS to false in build configuration."
          )
      }
  }
  ```

---

## Medium Severity

### M14: Android 10+ WiFi连接限制（API废弃）
- **位置**: `android/app/src/main/java/com/netproxy/gateway/wifi/GatewayWifiManager.kt` (L322-361)
- **问题描述**: 使用`WifiConfiguration` API在Android 10+上已被废弃，且Android 10+对后台应用启动WiFi连接有限制，可能导致连接失败或需要用户手动确认
- **风险**: 中。代码已实现适配，影响有限，但需要引导用户手动操作
- **建议修复**:
  - 引导用户手动连接WiFi
  - 使用Suggestion API（需要用户批准）
  - 使用NetworkSpecifier进行请求（Android 10+）

### M15: 5G网络切换问题（系统行为）
- **位置**: `android/app/src/main/java/com/netproxy/gateway/connection/NetworkStateManager.kt`
- **问题描述**: 5G NSA/SA模式切换、5G与4G切换时，网络接口可能发生变化，当前代码仅检测基础网络类型（WiFi/Cellular/Ethernet），未针对5G网络变化做特殊处理，可能导致VPN隧道中断
- **风险**: 中。网络切换时可能导致连接中断
- **建议修复**:
  - 监听网络变化并自动重建VPN连接
  - 实现连接保活和快速恢复机制
  - 提示用户在远程协助期间保持网络稳定

---

## High Severity

### H19: 电池优化和后台执行限制
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt`
- **问题描述**: 
  - Android Doze模式和App Standby可能限制后台网络活动
  - 在某些厂商ROM（如小米、华为）上VPN服务可能被强制停止或限制网络访问
  - 未检测是否已被用户加入电池优化白名单
- **风险**: 高。影响VPN服务持续运行，在省电模式下可能导致连接中断
- **建议修复**:
  - 检测电池优化白名单状态并提示用户
  - 引导用户将应用加入白名单
  - 实现连接状态监控和自动重连
  - 检测被杀死后由系统广播唤醒

---

## Low Severity

### L6: 系统私有DNS设置未检测
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnDnsConfig.kt`
- **问题描述**: VpnDnsConfig仅提供基础DNS服务器解析和路由判断功能，未检测Android系统的"私有DNS"(DNS over HTTPS/TLS)设置。当用户启用此功能时，系统的DNS查询可能被强制重定向到加密DNS服务器，影响VPN的DNS分流逻辑
- **风险**: 低。可引导用户解决
- **建议修复**:
  - 检测私有DNS设置状态并提示用户
  - 引导用户关闭私有DNS或设置为自动
  - 在VPN中强制指定DNS服务器

### L7: 随机MAC地址功能未处理
- **位置**: `android/app/src/main/java/com/netproxy/gateway/wifi/GatewayWifiManager.kt`
- **问题描述**: WifiManager未检测或处理Android的随机MAC地址功能。当系统使用随机MAC连接WiFi时，某些企业级AP可能基于MAC地址实施访问控制，导致内网访问受限
- **风险**: 低。特定企业场景下可能出现问题
- **建议修复**:
  - 检测随机MAC设置并提示用户
  - 引导用户为特定WiFi网络关闭随机MAC
  - 在企业场景下提供相关说明文档

---

## Medium Severity

### M12: MQTT 发布和订阅未检查连接状态
- **状态**: 待修复
- **位置**: `android/app/src/main/java/com/netproxy/gateway/connection/MqttConnectionManager.kt` (L352-364, L370-388)
- **问题**: `publishWithResult` 和 `subscribeWithResult` 方法只检查 `mqttClient != null`，但不检查连接状态（`_connectionState.value == MqttConnectionState.Connected`）。这可能导致在客户端正在连接或断开时尝试发布/订阅消息，操作会失败但错误信息不明确
- **风险**: 中。在连接不稳定或重连过程中，可能导致消息发布/订阅失败，增加调试难度
- **建议修复**:
  1. 在 `publishWithResult` 和 `subscribeWithResult` 中添加连接状态检查
  2. 如果未连接，返回明确的错误信息或等待连接完成
  3. 考虑添加超时机制，避免无限等待
- **代码示例**:
  ```kotlin
  fun publishWithResult(topic: String, payload: String, qos: Int = 0): AppResult<Unit> {
      val client = mqttClient ?: return AppResult.error(IllegalStateException("MQTT client is not connected"))
      
      // 添加连接状态检查
      if (_connectionState.value != MqttConnectionState.Connected) {
          return AppResult.error(IllegalStateException("MQTT client is not connected, current state: ${_connectionState.value}"))
      }
      
      // ... 其余代码
  }
  ```

### M13: MQTT 重连延迟递增逻辑问题
- **状态**: 待修复
- **位置**: `android/app/src/main/java/com/netproxy/gateway/connection/MqttConnectionManager.kt` (L311-L321)
- **问题**: `scheduleReconnect` 方法在**重连前**就增加延迟（`reconnectDelay = minOf(reconnectDelay * 2, MAX_RECONNECT_DELAY)`），导致第一次重连的延迟是 10 秒而不是 5 秒。正确的逻辑应该是在重连**失败后**再增加延迟
- **风险**: 低。会导致重连时间比预期更长，影响用户体验
- **建议修复**:
  1. 将延迟递增逻辑移到重连尝试之后
  2. 或者在重连成功后重置延迟为初始值
  3. 考虑使用指数退避算法的标准实现
- **代码示例**:
  ```kotlin
  private fun scheduleReconnect(deviceId: String, authToken: String, generation: Long) {
      reconnectJob?.cancel()
      reconnectJob = scope.launch {
          delay(reconnectDelay)
          if (!shouldStayConnected || generation != connectionGeneration.get()) {
              return@launch
          }
          
          // 先重连
          connect(deviceId, authToken)
          
          // 如果重连失败，再增加延迟（在 connect 方法中处理）
          // 或者在重连成功后重置延迟
          if (_connectionState.value == MqttConnectionState.Connected) {
              reconnectDelay = INITIAL_RECONNECT_DELAY
          }
      }
  }
  ```

### H10: VpnService stopVpn() 竞态条件 [已验证确认]
- **状态**: 待修复（2026-04-11 Subagents深度验证确认）
- **验证方式**: Logic Analyzer Agent 代码审查
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt` (L930-972)
- **问题验证**:
  - `isStopping` 原子标志与 `_status` StateFlow 是两个独立的状态源
  - 线程A通过CAS设置`isStopping=true`后，线程B可能修改`_status`状态
  - 在L933读取isStopping和L939读取_status之间存在时间窗口
  - `finally`块中重置`isStopping`，但状态可能已被其他线程改变
- **竞态场景**:
  ```
  T1: 线程A CAS成功 isStopping=true, _status=RUNNING
  T2: 线程B CAS失败返回
  T3: 线程A在L939前被挂起
  T4: 其他代码修改 _status=STOPPING
  T5: 线程A读取 currentState=STOPPING，重置isStopping=false并返回
  T6: 线程B现在可以CAS成功，重复执行停止逻辑
  ```
- **风险**: 中。可能导致重复执行停止逻辑，状态不一致
- **触发条件**: 快速连续调用stopVpn()、onRevoke()和手动停止并发、系统回收与手动停止并发
- **代码分析**:
  ```kotlin
  // L930-972: 问题代码
  private fun stopVpn() {
      if (!isStopping.compareAndSet(false, true)) return  // L933: 获取标志
      
      val currentState = _status.value.state  // L939: 读取状态 - 可能已被其他线程修改
      if (currentState == VpnState.STOPPED || currentState == VpnState.STOPPING) {
          isStopping.set(false)
          return
      }
      // ... 清理操作
  }
  ```
- **建议修复**:
  ```kotlin
  private fun stopVpn() {
      // 先读取当前状态
      val currentState = _status.value.state
      if (currentState == VpnState.STOPPED || currentState == VpnState.STOPPING) {
          return
      }
      
      // 再尝试设置停止标志
      if (!isStopping.compareAndSet(false, true)) {
          return
      }
      
      // 双重检查
      if (_status.value.state == VpnState.STOPPED) {
          isStopping.set(false)
          return
      }
      // ...
  }
  ```

### H11: writeBufferPool 线程安全问题 [已验证确认]
- **状态**: 待修复（2026-04-11 Subagents深度验证确认）
- **验证方式**: Logic Analyzer Agent 代码审查
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt` (L120-122, L619-622)
- **问题验证**:
  - `getAndIncrement() % writeBufferPool.size` 不是原子操作
  - 虽然`getAndIncrement()`是原子的，但取模和数组访问是分开的操作
  - 当并发线程数超过缓冲区池大小(4)时，多个线程可能获取到同一个缓冲区
- **竞态场景**:
  ```
  线程1-4: 分别获取 buffer[0], buffer[1], buffer[2], buffer[3]
  线程5: writeBufferIndex=4, 4%4=0, 获取buffer[0]（正在被线程1使用！）
  ```
- **风险**: 高。高并发时可能导致数据竞争，回包数据损坏或崩溃
- **触发条件**: 超过4个线程同时处理回包（高流量场景）
- **代码分析**:
  ```kotlin
  // L120-122, L619-622: 问题代码
  private val writeBufferPool = Array(4) { ByteArray(PACKET_BUFFER_SIZE) }
  private val writeBufferIndex = AtomicInteger(0)
  
  private fun getWriteBuffer(): ByteArray {
      val index = writeBufferIndex.getAndIncrement() % writeBufferPool.size
      return writeBufferPool[index]
  }
  ```
- **建议修复**:
  ```kotlin
  // 方案1: 使用ThreadLocal（推荐）
  private val writeBuffer = ThreadLocal<ByteArray>()
  
  private fun getWriteBuffer(): ByteArray {
      return writeBuffer.get() ?: ByteArray(PACKET_BUFFER_SIZE).also {
          writeBuffer.set(it)
      }
  }
  
  // 方案2: 使用同步块
  @Synchronized
  private fun getWriteBuffer(): ByteArray {
      val index = writeBufferIndex.getAndIncrement() % writeBufferPool.size
      return writeBufferPool[index]
  }
  ```

### H12: activeConnections 复合操作非原子 [已验证确认]
- **状态**: 待修复（2026-04-11 Subagents深度验证确认）
- **验证方式**: Logic Analyzer Agent 代码审查
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt` (L426-427, L467, L429-432, L470)
- **问题验证**:
  - 虽然使用 `ConcurrentHashMap`，但"检查-获取-更新"模式不是原子的
  - L429: `activeConnections[connectionKey]` 获取
  - L430: `existingSession?.pooledConnection?.isValid()` 检查
  - L470: `activeConnections[connectionKey]?.updateActivity()` 再次获取可能不同对象
  - 在检查和使用之间，连接可能被其他线程清理
- **竞态场景**:
  ```
  线程A (forwardViaSocks5)          线程B (cleanupStaleConnections)
  -------------------------------   --------------------------------
  val existing = activeConnections[key]
                                    activeConnections.remove(key)
                                    pool.returnConnection(conn)
  existing.pooledConnection.isValid()  // 访问已关闭的连接！
  ```
- **风险**: 高。可能导致使用无效连接、空指针异常、IO异常或重复归还
- **触发条件**: 连接刚好在30秒超时过期时、清理任务与转发并发执行、高流量场景
- **代码分析**:
  ```kotlin
  // L429-432, L470: 问题代码
  val existingSession = activeConnections[connectionKey]  // 获取
  val pooledConn = if (existingSession?.pooledConnection?.isValid() == true) {  // 检查
      existingSession.pooledConnection
  } else { ... }
  // ...
  activeConnections[connectionKey]?.updateActivity()  // 再次获取，可能不同对象
  ```
- **建议修复**:
  ```kotlin
  // 使用compute保证原子性
  activeConnections.compute(connectionKey) { key, existingSession ->
      if (existingSession?.pooledConnection?.isValid() == true) {
          existingSession.updateActivity()
          existingSession
      } else {
          // 创建新连接
          existingSession?.pooledConnection?.let { pool.returnConnection(it) }
          val conn = pool.borrowConnection(...)
          ConnectionSession(...)
      }
  }?.let { session ->
      // 使用session发送数据
  }
  ```

### H13: constructReturnPacket 潜在数组越界 [已验证确认]
- **状态**: 待修复（2026-04-11 Subagents深度验证确认）
- **验证方式**: Logic Analyzer Agent 代码审查
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt` (L628-687)
- **问题验证**:
  - 未验证 `buffer` 的大小是否足够容纳 `totalLen`
  - `payloadLen` 可能很大（SOCKS5返回大数据块），导致数组越界
  - `session.virtualSrcIp.split(".")` 假设IP格式正确，可能抛出异常
- **问题1 - 数组越界**:
  - writeBufferPool大小为32KB (PACKET_BUFFER_SIZE)
  - 如果payloadLen > 32728字节，会发生ArrayIndexOutOfBoundsException
  - 触发条件: SOCKS5代理返回大文件数据、视频流、合并的数据包
- **问题2 - IP解析异常**:
  - `virtualSrcIp.split(".")`可能抛出NumberFormatException
  - `srcIpParts[n]`可能抛出IndexOutOfBoundsException
  - 虽然virtualSrcIp由系统生成，但缺乏防御性编程
- **风险**: 高。可能导致ArrayIndexOutOfBoundsException或NumberFormatException崩溃
- **代码分析**:
  ```kotlin
  // L628-649: 问题代码
  private fun constructReturnPacket(buffer: ByteArray, session: ConnectionSession, payloadLen: Int): Int {
      val totalLen = 20 + 20 + payloadLen  // 40 + payloadLen
      // 直接写入buffer[0..totalLen-1]，没有边界检查！
      buffer[0] = 0x45
      // ...
      // L648: 假设IP格式正确
      val srcIpParts = session.virtualSrcIp.split(".").map { it.toInt() }
  }
  ```
- **建议修复**:
  ```kotlin
  private fun constructReturnPacket(buffer: ByteArray, session: ConnectionSession, payloadLen: Int): Int {
      val ipHeaderLen = 20
      val tcpHeaderLen = 20
      val totalLen = ipHeaderLen + tcpHeaderLen + payloadLen
      
      // 添加边界检查
      if (totalLen > buffer.size) {
          logger.warn("Payload too large: $payloadLen, buffer size: ${buffer.size}")
          return -1 // 或截断处理
      }
      
      // 安全的IP解析
      val srcIpParts = session.virtualSrcIp.split(".").mapNotNull { it.toIntOrNull() }
      if (srcIpParts.size != 4 || srcIpParts.any { it !in 0..255 }) {
          logger.error("Invalid virtual IP format: ${session.virtualSrcIp}")
          return -1
      }
      // ...
  }
  ```

### H14: cleanupVpnResources() 未归还连接池连接 [已修复]
- **状态**: ✅ **已修复**（2026-04-11 提交 4f58619）
- **修复验证**: Subagents代码审查确认修复正确
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt` (L1007-1029)
- **原问题**:
  - `cleanupVpnResources()` 中直接调用 `activeConnections.clear()` 清空映射表
  - 但连接池中的连接（`PooledSocks5Connection`）没有被归还到连接池
  - 这导致连接池不知道这些连接已经被"丢弃"，可能造成连接池泄漏
- **修复内容**:
  ```kotlin
  // L1007-1029: 修复后的代码
  // 归还所有活跃会话中的连接池连接
  val pool = socks5ConnectionPool
  if (pool != null) {
      activeConnections.values.forEach { session ->
          try {
              session.pooledConnection?.let { connection ->
                  pool.returnConnection(connection)
              }
          } catch (e: Exception) {
              logger.warn("Failed to return connection for session ${session.srcIp}:${session.srcPort}", e)
          }
      }
  } else {
      // 连接池已不存在，直接关闭所有连接
      activeConnections.values.forEach { session ->
          try {
              session.pooledConnection?.close()
          } catch (e: Exception) {
              logger.warn("Failed to close connection for session ${session.srcIp}:${session.srcPort}", e)
          }
      }
  }
  activeConnections.clear()
  ```
- **修复验证**:
  - ✅ 先归还所有连接到连接池，再清空映射表
  - ✅ 处理连接池已关闭的优雅降级场景
  - ✅ 每个连接归还操作独立try-catch，防止错误传播
  - ✅ 单元测试全部通过

### H15: onDestroy() 重复取消 serviceScope [已修复]
- **状态**: ✅ **已修复**（2026-04-11 提交 4f58619）
- **修复验证**: Subagents代码审查确认修复正确
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt` (L1069-1071)
- **原问题**:
  - `serviceScope` 使用 `val` 定义，一旦创建无法重新赋值
  - `stopVpn()` 和 `onDestroy()` 都可能尝试取消同一作用域
  - 如果 `stopVpn()` 被调用，`onDestroy()` 会再次执行清理逻辑
- **修复内容**:
  - 将 `serviceScope` 从 `val` 改为 `var`，支持重新创建
  - `stopVpn()` 中取消后置 `null`：`serviceScope?.cancel(); serviceScope = null`
  - `onDestroy()` 中安全调用：`serviceScope?.cancel(); serviceScope = null`
  - 通过将 `serviceScope` 置为 `null`，`onDestroy()` 中的安全调用不会执行实际操作，确保清理逻辑清晰
- **修复验证**:
  - ✅ serviceScope在onCreate()中创建，支持服务重启
  - ✅ stopVpn()中取消并置null
  - ✅ onDestroy()中安全调用，不会重复取消
  - ✅ 使用 `?.` 安全调用避免NPE
  - ✅ 单元测试全部通过
- **建议修复**:
  统一资源清理逻辑，提取 `performCleanup()` 方法，确保 `stopVpn()` 和 `onDestroy()` 使用相同的清理顺序

# NetProxyGateway 缺陷清单（待修复）
不要在此文档记录问题的验证状态（如“已验证真实存在”）、建议修复方式，不要记录日期，使用提交哈希识别问题存在的版本。需要记录问题存在的提交哈希、问题文件路径、问题行号、问题描述、风险、修复难度、修复状态。
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

---

## High

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

### H30: API服务日志输出明文Session Token [新发现-待修复]
- **状态**: 待修复
- **提交哈希**: b8f10a5
- **位置**: `server/api/main.go` (L969)
- **问题描述**: validateSession 处理过期token删除失败时将 `req.Token` 明文写入日志，可能导致凭证在日志系统中泄露
- **风险**: 高。会话凭证可被日志读取者复用，扩大横向移动风险
- **修复难度**: 低

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

### H10: VpnService stopVpn() 竞态条件
- **状态**: 待修复
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

### H11: writeBufferPool 线程安全问题
- **状态**: 待修复
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt` (L120-122, L619-622)
- **问题验证**:
  - `getAndIncrement() % writeBufferPool.size` 不是原子操作
  - 虽然`getAndIncrement()`是原子的，但取模和数组访问是分开的操作
  - 当并发线程数超过缓冲区池大小(4)时，多个线程可能获取到同一个缓冲区
  - **整数溢出风险**: `writeBufferIndex.getAndIncrement()`在达到`Int.MAX_VALUE`时溢出变为负数，取模后产生负数索引，导致`ArrayIndexOutOfBoundsException`
- **竞态场景**:
  ```
  线程1-4: 分别获取 buffer[0], buffer[1], buffer[2], buffer[3]
  线程5: writeBufferIndex=4, 4%4=0, 获取buffer[0]（正在被线程1使用！）
  ```
- **风险**: 高。高并发时可能导致数据竞争、回包数据损坏、崩溃；整数溢出时直接导致`ArrayIndexOutOfBoundsException`
- **触发条件**: 超过4个线程同时处理回包（高流量场景）；或长时间运行后索引溢出
- **代码分析**:
  ```kotlin
  // L120-122, L619-622: 问题代码
  private val writeBufferPool = Array(4) { ByteArray(PACKET_BUFFER_SIZE) }
  private val writeBufferIndex = AtomicInteger(0)
  
  private fun getWriteBuffer(): ByteArray {
      val index = writeBufferIndex.getAndIncrement() % writeBufferPool.size
      return writeBufferPool[index]  // 可能抛出负数索引异常
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
  
  // 方案3: 修复整数溢出（如果保留原方案）
  private fun getWriteBuffer(): ByteArray {
      val index = (writeBufferIndex.getAndIncrement().toLong() and 0xFFFFFFFFL % writeBufferPool.size).toInt()
      return writeBufferPool[index]
  }
  ```

### H12: activeConnections 复合操作非原子
- **状态**: 待修复
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

### H14: constructReturnPacket 拒绝0长度payload过于严格 [新发现-已验证]
- **状态**: 待修复
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt` (L650)
- **问题描述**: H13修复中 `if (payloadLen <= 0) return 0` 检查过于严格。0长度payload是合法的TCP场景（如ACK包、FIN包）。当前实现会丢弃这些正常包。
- **风险**: 中。可能导致TCP连接异常，ACK包丢失，连接超时。
- **代码**:
  ```kotlin
  // L650: 问题代码
  if (payloadLen <= 0) {
      return 0
  }
  ```
- **验证结果** (2026-04-19, Kimi-K2.5):
  - ✅ 问题真实存在：L649-650 确实包含 `if (payloadLen <= 0) return 0`
  - ⚠️ 当前调用上下文（processTcpReturn L601 的 `read > 0` 检查）掩盖了此问题，该检查在实际运行中不会触发
  - 🔴 根本问题是架构缺陷：当前实现完全无法处理TCP控制包（ACK、FIN、RST），固定设置PSH标志，不适合发送纯控制包
  - 建议修复分两层：短期将 `<=` 改为 `<`；长期实现完整的TCP状态机支持控制包
- **建议修复**:
  ```kotlin
  // 短期修复：仅拒绝负数payload，允许0长度
  if (payloadLen < 0) {
      return 0
  }
  ```

### H15: VpnService测试直接实例化Android Service [新发现-已验证]
- **状态**: 待修复
- **位置**: `android/app/src/test/java/com/netproxy/gateway/vpn/VpnServiceTest.kt` (L1652, L1673, L1707)
- **问题描述**: 测试代码直接实例化 `GatewayVpnService()`，违反Android组件生命周期规范。`VpnService`必须通过系统创建并调用`onCreate()`后才能使用。直接实例化可能导致依赖未初始化、Hilt注入失败。
- **风险**: 高。测试不可靠，与实际运行时不一致，可能产生假阳性/假阴性结果。
- **代码**:
  ```kotlin
  // 问题代码示例（L1652, L1673, L1707）
  val service = GatewayVpnService()
  ```
- **验证结果** (2026-04-19, Kimi-K2.5):
  - ✅ 问题真实存在：3处直接实例化 GatewayVpnService()
  - ✅ GatewayVpnService 是 @AndroidEntryPoint 类，有 @Inject lateinit 字段
  - ⚠️ 当前测试能通过是因为只测试不依赖注入的私有方法，并通过反射手动设置所需字段
  - 🔴 隐患：如果未来测试访问注入字段（如 authSessionStore），会抛出 UninitializedPropertyAccessException
  - 对比：项目中 Socks5ProxyServiceTest 正确使用 Robolectric 的 ServiceController
- **建议修复**: 使用Robolectric的`ServiceController`正确创建和启动Service：
  ```kotlin
  @RunWith(RobolectricTestRunner::class)
  @Config(application = HiltTestApplication::class, sdk = [33])
  @HiltAndroidTest
  class VpnServiceTest {
      @get:Rule
      val hiltRule = HiltAndroidRule(this)
      
      @Before
      fun setUp() {
          hiltRule.inject()
      }
      
      @Test
      fun testExample() {
          val controller = Robolectric.buildService(GatewayVpnService::class.java)
          val service = controller.create().get()
          // 测试代码
      }
  }
  ```

### H16: VpnService测试过度使用反射 [新发现-已验证]
- **状态**: 待修复
- **位置**: `android/app/src/test/java/com/netproxy/gateway/vpn/VpnServiceTest.kt` (L1748-1816)
- **问题描述**: 测试大量使用反射访问私有方法和内部类（`createSessionForReflection`、`invokeConstructReturnPacket`、`invokeProcessTcpReturn`）。代码结构变化会导致测试崩溃，重构时需要同步更新大量反射代码。
- **风险**: 高。维护困难，重构风险大，可读性差，IDE重构工具无法识别反射引用。
- **验证结果** (2026-04-19, Kimi-K2.5):
  - ✅ 问题真实存在：6个反射辅助方法，11次直接反射调用
  - 反射访问的成员：
    - 内部类：`ConnectionSession` (private data class)
    - 私有方法：`constructReturnPacket`, `processTcpReturn`
    - 私有字段：`socks5ConnectionPool`, `activeConnections`, `vpnOutputStream`
  - 具体反射方法：
    - `createSessionForReflection` - 通过反射创建内部类（4次调用）
    - `invokeConstructReturnPacket` - 反射调用私有方法（3次调用）
    - `invokeProcessTcpReturn` - 反射调用私有方法（1次调用）
    - `setPrivateField/getPrivateField` - 反射访问字段（3次调用）
  - 脆弱性：字符串名称耦合（如 `it.simpleName == "ConnectionSession"`），IDE重构无法识别
- **建议修复**:
  1. 将需要测试的逻辑提取为package-private或internal方法
  2. 使用@VisibleForTesting注解标记
  3. 或重构代码使其更易测试（依赖注入替代内部状态访问）
  4. 示例：
     ```kotlin
     @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
     internal fun constructReturnPacket(...): Int { ... }
     ```

### H17: writeBufferPool整数溢出 [新发现-已验证]
- **状态**: 待修复 (与H13独立)
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt` (L637-639)
- **问题描述**: `writeBufferIndex.getAndIncrement()`在应用运行约21亿次调用后必然溢出。高流量场景下可能数天至数周内触发。与H13修复的`constructReturnPacket`边界检查是独立问题。
- **风险**: 高。长时间运行后必崩溃。
- **代码**:
  ```kotlin
  private val writeBufferPool = Array(4) { ByteArray(PACKET_BUFFER_SIZE) }
  private val writeBufferIndex = AtomicInteger(0)
  ...
  private fun getWriteBuffer(): ByteArray {
      val index = writeBufferIndex.getAndIncrement() % writeBufferPool.size
      return writeBufferPool[index]
  }
  ```
- **验证结果** (2026-04-19, Kimi-K2.5):
  - ✅ 问题真实存在：L638 使用 `AtomicInteger.getAndIncrement()` 循环递增
  - ✅ 数学验证：Int.MAX_VALUE = 2,147,483,647，溢出后变为负数
  - ✅ 溢出机制：`-1 % 4 = -1`（Kotlin/Java 负数取模），导致 `writeBufferPool[-1]` 越界崩溃
  - 溢出时间估算：
    - 轻度使用（10包/秒）：约6.8年
    - 中度使用（100包/秒）：约248天
    - 高流量（1,000包/秒）：约24.8天
    - 极高流量（10,000包/秒）：约2.5天
  - ⚠️ 与H13完全独立：H13是数组访问边界检查，H17是索引计算溢出
- **建议修复** (方案对比)：
  - 方案1（推荐）：使用 `Math.floorMod` 正确处理负数
    ```kotlin
    private fun getWriteBuffer(): ByteArray {
        val index = Math.floorMod(writeBufferIndex.getAndIncrement(), writeBufferPool.size)
        return writeBufferPool[index]
    }
    ```
  - 方案2：使用 ThreadLocal 彻底避免竞争和溢出问题
    ```kotlin
    private val writeBuffer = ThreadLocal<ByteArray>()
    private fun getWriteBuffer(): ByteArray {
        return writeBuffer.get() ?: ByteArray(PACKET_BUFFER_SIZE).also { writeBuffer.set(it) }
    }
    ```

---

## Medium Severity

### M19: Tunnel服务消息处理无速率限制 [待修复]
- **状态**: 待修复
- **位置**: `server/tunnel/main.go` (L390-413)
- **问题描述**: 没有限制单个连接的消息速率，恶意客户端可能发送大量消息导致DoS
- **风险**: 中。可能导致服务资源耗尽
- **建议修复**: 添加基于令牌桶或滑动窗口的速率限制

---

## Low Severity

### L8: SOCKS5代理流ID生成可预测性 [待修复]
- **状态**: 待修复
- **位置**: `server/socks5-proxy/main.go` (L689)
- **问题描述**: 流ID使用 `fmt.Sprintf("%s-%d", deviceID, time.Now().UnixNano())` 生成，依赖时间戳纳秒。虽然不存在模运算分布问题，但时间戳可预测，流ID生成逻辑可被推测
- **风险**: 低。流ID可预测性增加，可能被用于会话固定攻击
- **代码**:
  ```go
  // L689
  streamID := fmt.Sprintf("%s-%d", deviceID, time.Now().UnixNano())
  ```
- **建议修复**: 使用 `crypto/rand` 生成随机字符串替代时间戳

### L9: SOCKS5代理StreamConn DataChan可能阻塞 [待修复]
- **状态**: 待修复
- **位置**: `server/socks5-proxy/main.go` (L320, L654-657)
- **问题描述**: 如果 `DataChan` 已满且 `CloseChan` 未关闭，数据发送会阻塞或丢弃
- **风险**: 低。可能导致数据丢失或延迟
- **建议修复**: 添加默认分支处理丢弃情况，或增加缓冲区大小并监控

### L10: Tunnel服务设备状态通知无重试 [待修复]
- **状态**: 待修复
- **位置**: `server/tunnel/main.go` (L146-181)
- **问题描述**: `notifyDeviceStatus` 通知失败只是记录日志，没有重试机制。如果API服务暂时不可用，设备状态可能不一致
- **风险**: 低。状态不一致，但可接受
- **建议修复**: 添加指数退避重试机制

### L11: 日志框架混用导致输出不一致 [待修复]
- **状态**: 待修复
- **位置**: 
  - `android/app/src/main/java/com/netproxy/gateway/security/SecurityManager.kt` (L88, L93, L100, L107, L118)
  - `android/app/src/main/java/com/netproxy/gateway/NetProxyApp.kt` (L33, L38, L43, L49, L51, L66)
- **问题描述**: SecurityManager和NetProxyApp使用Android原生`Log`类，而项目其他部分使用SLF4J。导致日志格式、输出目标和级别控制不一致
- **风险**: 低。日志管理混乱，不利于统一监控和排查问题
- **代码**:
  ```kotlin
  // SecurityManager.kt - 使用Android Log
  Log.d(TAG, "Starting security check...")
  Log.w(TAG, "Root detected: ${rootResult.detectedBy}")
  
  // MqttConnectionManager.kt - 使用SLF4J
  logger.error("Heartbeat publish error")
  ```
- **建议修复**: 统一使用SLF4J日志框架，移除所有Android原生Log的使用

---

## 新增问题（待分类）

---

## 新增问题

### N20: writeBufferPool整数溢出导致数组越界
- **状态**: 待修复
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt` L629-631
- **问题**: `AtomicInteger.getAndIncrement()`在Int.MAX_VALUE次调用后溢出为负数，取模后产生负数索引
- **风险**: Critical。应用长时间运行后必然崩溃（约2^31次调用后）
- **代码示例**:
  ```kotlin
  private fun getWriteBuffer(): ByteArray {
      val index = writeBufferIndex.getAndIncrement() % writeBufferPool.size  // 溢出后index为负数！
      return writeBufferPool[index]  // ArrayIndexOutOfBoundsException
  }
  ```
- **建议修复**: 使用ThreadLocal替代轮询，或添加溢出处理
- **关联问题**: H11的子问题

### N21: 配对码输入状态配置变更丢失
- **状态**: 已修复
- **位置**: `android/app/src/main/java/com/netproxy/gateway/ui/screens/MainScreen.kt` L107
- **问题**: 使用`remember`而非`rememberSaveable`保存配对码输入状态
- **风险**: High。屏幕旋转时丢失用户输入
- **引入来源**: 本次Compose UI变更引入
- **修复**: 将`remember`改为`rememberSaveable`

### N22: MainViewModel状态更新竞争条件
- **状态**: 已修复（2026-04-18 验证并修复）
- **位置**: `android/app/src/main/java/com/netproxy/gateway/ui/viewmodel/MainViewModel.kt` L58,66-75
- **问题**: 两次独立的`_uiState.value`更新之间存在竞态窗口
- **风险**: High。UI状态可能不一致
- **引入来源**: 本次ViewModel变更引入
- **修复**: 使用`_uiState.update{}`原子操作合并为一次更新；init块中的初始化也改为update形式
- **验证**: `./gradlew.bat :app:testDebugUnitTest` 全量测试通过

### N23: 测试直接实例化Android Service
- **状态**: 已修复
- **位置**: `android/app/src/test/java/com/netproxy/gateway/proxy/Socks5ProxyServiceTest.kt` L11,18,28
- **问题**: 直接实例化`Socks5ProxyService()`违反Android组件生命周期
- **风险**: Medium。测试不可靠
- **引入来源**: 本次测试代码变更引入
- **修复**: 使用Robolectric的`ServiceController`正确创建Service

### N24: Socks5ProxyService通知ID使用魔法数字
- **状态**: 已修复
- **位置**: `android/app/src/main/java/com/netproxy/gateway/proxy/Socks5ProxyService.kt` L81,189
- **问题**: 通知ID使用硬编码魔法数字`1`，可读性和可维护性差
- **风险**: Low。代码风格问题
- **修复**: 提取为命名常量`NOTIFICATION_ID`

### N25: MainViewModel状态更新方式不一致
- **状态**: 已修复（2026-04-18 验证并修复）
- **位置**: `android/app/src/main/java/com/netproxy/gateway/ui/viewmodel/MainViewModel.kt` L58,84,90,96,111,117,121,134,145,154,165,173
- **问题**: 混合使用`_uiState.value = `和`_uiState.update{}`，风格不一致
- **风险**: Low。单协程作用域内无实际竞态，但防范未来隐患
- **修复**: 统一使用`_uiState.update{}`，共修复7处直接赋值，全部改为原子更新操作
- **验证**: `./gradlew.bat :app:testDebugUnitTest` 全量测试通过

### N26: Service语言监听器残留风险
- **状态**: 已修复
- **位置**: `VpnService.kt`/`Socks5ProxyService.kt` 的`onCreate()`
- **问题**: 系统强制杀Service后监听器可能残留在单例map中
- **风险**: Low。影响小，最多2个监听器残留
- **修复**: 在`onCreate()`中先执行防御性`unregister`再`register`

---

## High Severity

> 当前无 High Severity 问题

---

## Medium Severity

> 当前无 Medium Severity 问题

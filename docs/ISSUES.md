# NetProxyGateway 缺陷清单（待修复）
不要在此文档记录问题的验证状态（如“已验证真实存在”）、建议修复方式、“新发现”，不要记录日期，使用7位提交哈希识别问题存在的版本。需要记录问题存在的提交哈希、问题文件路径、问题行号、问题描述、风险、修复难度、修复状态。
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

### H17: VirtualIpAllocator AtomicInteger溢出 [已修复]
- **提交哈希**: e89e00d
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VirtualIpAllocator.kt` (L83, L104)
- **问题**: `nextVirtualIp.getAndIncrement()`在达到`Int.MAX_VALUE`后溢出为负数。L72的`currentIp > MAX_IP`检查无法防止溢出（负数不满足条件），导致`require(ipNum in START_IP..MAX_IP)`抛出`IllegalArgumentException`
- **风险**: 高。VPN服务长时间运行后必然崩溃
- **修复**: 使用`Math.floorMod(nextVirtualIp.getAndIncrement(), MAX_IP - START_IP + 1) + START_IP`替代直接递增，与VpnService.kt中H11修复方式一致
- **修复状态**: 已修复

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

### M3: 安全检测命令执行未超时 [已修复]
- **提交哈希**: e89e00d
- **位置**: `android/app/src/main/java/com/netproxy/gateway/security/RootDetector.kt` (L204-214, L240-L253, L312-L324, L367-378), `DebugDetector.kt` (L195, L201, L262, L292, L297, L337, L342), `EmulatorDetector.kt` (L297)
- **问题**: `process.waitFor()`没有设置超时，如果命令被恶意hook或系统异常挂起会阻塞线程
- **风险**: 线程被永久阻塞，影响应用响应，攻击者可利用此绕过安全检测
- **修复**: 统一替换为`process.waitFor(3, TimeUnit.SECONDS)`，超时后调用`process.destroy()`清理资源
- **修复状态**: 已修复

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
- **状态**: 已修复
- **修复提交**: d01ddd1
- **修复方式**: 使用ThreadLocal替代共享缓冲区池，每个线程拥有独立缓冲区
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
- **状态**: 已修复
- **修复提交**: 6829cf3
- **修复方式**: 将payloadLen检查从`<= 0`改为`< 0`，允许0长度TCP控制包
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



## Medium Severity

---

## Low Severity

### L10: Tunnel服务设备状态通知无重试 [待修复]
- **状态**: 待修复
- **位置**: `server/tunnel/main.go` (L146-181)
- **问题描述**: `notifyDeviceStatus` 通知失败只是记录日志，没有重试机制。如果API服务暂时不可用，设备状态可能不一致
- **风险**: 低。状态不一致，但可接受
- **建议修复**: 添加指数退避重试机制

---

## 新增问题（待分类）

### N47: MqttConnectionManagerConnectCleanupTest flaky test
- **提交哈希**: f8497b8
- **位置**: `android/app/src/test/java/com/netproxy/gateway/connection/MqttConnectionManagerConnectCleanupTest.kt`
- **问题描述**: `connect_whenConnectThrowsAndGenerationChanges_shouldClearClientReferenceAndCloseClient` 在完整测试套件中失败，但单独运行通过。典型的测试间状态污染导致的 flaky test。`mockkConstructor(MqttClient::class)` 在测试间未完全清理，`Dispatchers.setMain`/`resetMain` 执行顺序可能导致协程泄漏
- **风险**: 高。测试不可靠，可能掩盖真实问题或产生假阴性
- **修复难度**: 低。调整 `tearDown` 中 `unmockkAll()` 和 `Dispatchers.resetMain()` 的顺序，确保测试隔离

### N49: EmulatorDetector 电话权限在 Android 10+ 上可能误判
- **提交哈希**: f8497b8
- **位置**: `android/app/src/main/java/com/netproxy/gateway/security/EmulatorDetector.kt` (L372-L441)
- **问题描述**: `checkPhoneNumber`、`checkDeviceId`、`checkImei` 需要 `READ_PHONE_STATE` 权限。在 Android 10+ 上 `getDeviceId()` 已废弃，非系统应用可能返回空或异常。权限被拒绝时静默返回 false，可能将真实设备误判为模拟器
- **风险**: 中。误判真实设备为模拟器可能影响功能可用性
- **修复难度**: 低。降低电话相关检测的权重，或完全移除（现代模拟器可模拟真实电话信息）

---

## 新增问题

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
- **验证**: `make android-test` 全量测试通过

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
- **验证**: `make android-test` 全量测试通过

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

---

## 新增问题（待分类）

### N27: Socks5ConnectionPool cleanupIdleConnections在write锁内执行阻塞IO
- **提交哈希**: 1f9acee
- **位置**: `android/app/src/main/java/com/netproxy/gateway/proxy/Socks5ConnectionPool.kt` (L435-L457)
- **问题描述**: `removeConnection(conn)` 在 `write` 锁内被调用，内部执行 `connection.close()` 阻塞IO操作。在高并发或网络异常时，长时间持有 `write` 锁会阻塞所有 `borrowConnection` 和 `returnConnection` 操作
- **风险**: 高。严重影响连接池并发性能，可能导致连接获取超时
- **修复难度**: 中。需要将 `socket.close()` 移出锁范围，改为异步关闭或在锁外执行

### N28: Socks5ProxyHandler RelayHandler释放语义优化 [引入新问题]
- **提交哈希**: 4de9b42
- **位置**: `android/app/src/main/java/com/netproxy/gateway/proxy/Socks5ProxyHandler.kt` (L283-L285)
- **问题描述**: 将`ReferenceCountUtil.release(msg)`改为`ReferenceCountUtil.safeRelease(msg)`，意图避免双重释放。但Netty的`ChannelOutboundBuffer.remove()`在write失败时已自动释放msg，`safeRelease`只是吞掉`IllegalReferenceCountException`异常，不能阻止对已经释放的池化ByteBuf进行操作，可能导致内存损坏或未定义行为。原注释"Netty releases msg automatically on write failure; do NOT call release here"是正确的
- **风险**: 高。池化ByteBuf被重复释放后可能归还到对象池，再次分配时获取到脏数据，导致数据损坏或崩溃
- **修复难度**: 低。回滚该修改，恢复原始不释放逻辑；或改为先检查`refCnt() > 0`再释放
- **修复状态**: 待修复
- **关联问题**: N45

### N30: Socks5ProxyHandler DNS解析阻塞EventLoop
- **提交哈希**: 1f9acee
- **位置**: `android/app/src/main/java/com/netproxy/gateway/proxy/Socks5ProxyHandler.kt` (L111-L128)
- **问题描述**: `InetAddress.getByName(host)` 是同步阻塞调用，在 Netty EventLoop 线程上执行。DNS 查询可能耗时数百毫秒甚至超时（数秒），期间阻塞该 EventLoop 上的所有 I/O 事件
- **风险**: 高。单连接慢DNS查询导致整个 SOCKS5 服务所有连接停滞
- **修复难度**: 中。需要引入异步 DNS 解析或使用线程池执行 DNS 查询

### N31: NetworkStateManager onLost多网络状态误判
- **提交哈希**: 1f9acee
- **位置**: `android/app/src/main/java/com/netproxy/gateway/connection/NetworkStateManager.kt` (L42-L44)
- **问题描述**: `onLost(network)` 只接收丢失的特定网络，不判断是否还有其他可用网络。`getStateAfterNetworkLost()` 直接返回 `isConnected=false`。多网络环境（WiFi+移动数据）下断开一个网络会错误报告为完全断网
- **风险**: 高。导致 VPN/MQTT 模块误判网络状态，触发不必要的重连或停止
- **修复难度**: 中。需要维护多网络状态，检查 `activeNetworks` 判断是否真的无网络

### N34: MainViewModel VPN状态与真实服务状态可能不一致
- **提交哈希**: 1f9acee
- **位置**: `android/app/src/main/java/com/netproxy/gateway/ui/viewmodel/MainViewModel.kt` (L129-L161)
- **问题描述**: `startForegroundService()` 后立即设 `isVpnEnabled=true`，但服务启动可能失败（权限被拒、系统限制、OOM）。UI 显示 VPN 已开启但实际服务未运行，缺少通过 ServiceConnection 同步真实状态的机制
- **风险**: 高。用户看到的状态与实际不符，可能导致安全/功能问题
- **修复难度**: 中。通过 ServiceConnection 或广播监听真实服务状态，UI 状态与真实状态解耦

### N35: MqttConnectionManager connect阻塞Default调度器 [误报]
- **提交哈希**: 1f9acee
- **位置**: `android/app/src/main/java/com/netproxy/gateway/connection/MqttConnectionManager.kt` (L367)
- **问题描述**: `createdClient.connect(options)` 是 Paho MQTT 同步阻塞调用，在 `viewModelScope.launch` 中执行（默认 Main 调度器）。连接超时可达数十秒，长时间阻塞 UI 线程
- **风险**: 高。主线程执行网络阻塞操作可能导致 ANR
- **修复难度**: 低。使用 `withContext(Dispatchers.IO)` 将 connect 操作移到 IO 调度器
- **误报原因**: 实际代码使用 `@ApplicationScope` 注入的 `CoroutineScope`，配置为 `Dispatchers.IO`（见 `CoroutineScopes.kt`），并非在 Main 调度器执行。代码审查时已验证不会阻塞主线程。

### N36: VpnService TCP固定标志位不符合协议状态机
- **提交哈希**: 1f9acee
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt` (L708-L709)
- **问题描述**: `constructReturnPacket` 固定设置 `PSH+ACK (0x18)`，从未根据 TCP 连接状态设置 `SYN`/`FIN`/`RST` 标志。连接建立应发送 `SYN+ACK`，终止应发送 `FIN+ACK`
- **风险**: 高。与严格遵循 TCP 协议栈的应用不兼容，可能导致连接建立失败或异常断开
- **修复难度**: 高。需要实现完整的 TCP 状态机，正确管理序列号和标志位

### N37: AuthSessionStore CharArray安全设计被String抵消
- **提交哈希**: 1f9acee
- **位置**: `android/app/src/main/java/com/netproxy/gateway/connection/AuthSessionStore.kt` (L183, L190)
- **问题描述**: `loadSession()` 将 `CharArray` 转为 `String` 返回，且 `ProxyAuthSession.authToken` 类型也是 `String`。`CharArray` 可清零的安全设计被完全绕过，敏感 token 以不可变 String 形式存在于内存
- **风险**: 高。安全设计意图失效，token 无法被主动擦除
- **修复难度**: 中。将 `ProxyAuthSession.authToken` 类型改为 `CharArray`，在业务层传递时保持 `CharArray` 形式

### N38: server/api cleanup协程泄漏
- **提交哈希**: 1f9acee
- **位置**: `server/api/main.go` (L1171-L1172, L413-L442)
- **问题描述**: `cleanupExpiredSessions` 和 `cleanupExpiredLoginAttempts` 后台协程使用 `for range ticker.C` 无限循环，无退出条件。`Server.Close()` 只关闭数据库，不通知清理协程退出
- **风险**: 高。影响优雅关闭和资源管理，热重启场景下是实质性泄漏
- **修复难度**: 低。为 Server 添加 `context.Context` 和关闭通道，协程监听 context.Done() 或通道退出

### N39: server/api JWT Secret长度未验证
- **提交哈希**: 1f9acee
- **位置**: `server/api/main.go` (L121-L124, L190)
- **问题描述**: JWT Secret 仅检查非空，未验证长度。HS256 密钥应至少 256 位（32 字节），弱密钥（1-2 字符）可被暴力破解
- **风险**: 高。使用弱 JWT 密钥可能导致令牌被伪造，造成未授权访问
- **修复难度**: 低。添加最小长度检查（如 32 字符），不足时拒绝启动

### N40: server/socks5-proxy GetOrConnectTunnel连接存活检查竞态 [已修复]
- **提交哈希**: 1f9acee
- **位置**: `server/socks5-proxy/main.go` (L479-L577)
- **问题描述**: RLock 释放后调用 `isConnAlive`，期间其他 goroutine 可能删除连接并创建新连接。返回的连接可能已被替换或即将关闭，典型的 Check-Then-Act 竞态
- **风险**: 高。返回失效连接导致客户端操作失败，影响连接稳定性
- **修复**: 将 `GetOrConnectTunnel` 中的 `RLock` 改为 `Lock`，在锁保护内完成连接存活检查和删除操作，消除 Check-Then-Act 竞态
- **修复状态**: 已修复

### N41: server/tunnel validateDeviceToken每次创建新HTTP客户端
- **提交哈希**: 1f9acee
- **位置**: `server/tunnel/main.go` (L527-L583)
- **问题描述**: 每次 WebSocket 连接建立时调用 `validateDeviceToken`，每次都创建新的 `http.Client`。无法复用 TCP 连接池，高频场景下造成连接开销和资源浪费
- **风险**: 中。性能问题，与已修复的 `notifyDeviceStatus` 问题（C24）相同
- **修复难度**: 低。将 `http.Client` 作为 `Server` 字段，初始化时创建一次并复用

### N42: server/tunnel heartbeat在Close后尝试发送Ping
- **提交哈希**: 1f9acee
- **位置**: `server/tunnel/main.go` (L586-L610)
- **问题描述**: `IsAlive()` 返回 true 后、`WriteControl` 发送 ping 前，`Close()` 可能被其他 goroutine 调用。向已关闭连接写入产生错误日志和冗余的 `Close()` 调用
- **风险**: 中。可观察性问题，产生不必要的错误日志，影响监控
- **修复难度**: 低。在 `WriteControl` 前检查 `closeChan`，或使用更一致的状态管理

### N43: MqttConnectionManager MQTT回调无法注销导致内存泄漏
- **提交哈希**: 1f9acee
- **位置**: `android/app/src/main/java/com/netproxy/gateway/connection/MqttConnectionManager.kt` (L81, L566-L617)
- **问题描述**: `subscribeWithResult` 注册回调到 `topicCallbacks`，但没有提供 `unsubscribe(topic, callback)` API。MQTT 长连接期间，持有 UI 组件闭包的回调永久留存无法清理
- **风险**: 中。MQTT 长连接场景下可能导致 Activity/ViewModel 内存泄漏
- **修复难度**: 中。添加 `unsubscribe(topic, callback)` 方法，支持精细化回调生命周期管理

### N44: VirtualIpAllocator floorMod边界偏移
- **提交哈希**: 4de9b42
- **修复提交**: 2ca17ee
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VirtualIpAllocator.kt` (L95)
- **问题描述**: 溢出IP映射使用`Math.floorMod(ipNum, MAX_IP - START_IP + 1) + START_IP`，未先将ipNum归一化到以0为起点的范围。导致边界偏移：ipNum=255时映射到2而非预期的1，ipNum=254意外走else分支时映射到1而非254
- **风险**: 高。IP分配错误可能导致虚拟IP冲突或合法IP被跳过，影响VPN流量转发
- **修复难度**: 低。修正为`Math.floorMod(ipNum - START_IP, MAX_IP - START_IP + 1) + START_IP`
- **修复状态**: 已修复

### N45: Socks5ProxyHandler double-free风险
- **提交哈希**: 4de9b42
- **修复提交**: 9f4b1b9
- **位置**: `android/app/src/main/java/com/netproxy/gateway/proxy/Socks5ProxyHandler.kt` (L283-L285)
- **问题描述**: `writeAndFlush(msg)`失败时调用`ReferenceCountUtil.safeRelease(msg)`，但Netty的`ChannelOutboundBuffer.remove()`已在失败时自动释放msg。`safeRelease`仅捕获异常，不能阻止对已经释放的池化ByteBuf进行操作。原注释明确说明"do NOT call release here"
- **风险**: 高。池化ByteBuf重复释放后归还对象池，再次分配时可能获取脏数据，导致数据损坏或应用崩溃
- **修复难度**: 低。回滚修改恢复原始不释放逻辑；如需处理race condition应先检查`refCnt() > 0`
- **修复状态**: 已修复
- **关联问题**: N28

### N46: DebugDetector语义隐晦代码
- **提交哈希**: 4de9b42
- **修复提交**: 165c272
- **位置**: `android/app/src/main/java/com/netproxy/gateway/security/DebugDetector.kt` (L204, L301, L347)
- **问题描述**: 多处使用`finished && false`表达式，结果永远为`false`，但写法隐晦浪费认知负担。代码风格也不一致：有的用`.let{}`有的用直接赋值
- **风险**: 低。无运行时风险，但可读性差，维护时易误解
- **修复难度**: 低。统一改为显式`false`并加注释说明意图；统一代码风格
- **修复状态**: 已修复

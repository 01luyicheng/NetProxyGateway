# NetProxyGateway 缺陷清单（待修复）
不要在此文档记录日期；用 7 位提交哈希作为“时间锚点”识别问题存在的版本。

**编号约定**：本文档 **C\*** 编号仅用于 ISSUES 内 **C1**（如 C1: SSL 信任所有证书）；与 `docs/TECH_DEBT.md` 的 C2+ 编号无关。

字段约定：
- **提交哈希**：该问题在此提交存在（若仅存在于未提交工作区变更，记录当前 HEAD 的 7 位哈希并标注 `(worktree)`）
- **修复提交**：修复该问题的提交（若已修复）

需要记录：问题存在的提交哈希、问题文件路径、问题行号、问题描述、风险、修复难度、修复状态。
## Critical

### C1: SSL信任所有证书配置风险
- **状态**: 待修复
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
- **位置**: `android/app/src/main/java/com/netproxy/gateway/proxy/Socks5ProxyHandler.kt` (L107-131, L264)
- **问题**: `validateTargetAddress` 中进行了一次 DNS 解析（`InetAddress.getByName(host)`，L112）验证 IP 范围，但后续 `bootstrap.connect(host, port)`（L264）会**再次独立解析 DNS**。攻击者可控制 DNS 服务器，在验证时返回合法 IP（如 10.x.x.x），在实际连接时解析到内网地址（如 127.0.0.1），绕过 IP 验证。此外 `InetAddress.getByName()` 是同步阻塞调用（见 N30）。
- **风险**: 攻击者可能通过 DNS 重绑定绕过 IP 验证，访问内网资源
- **建议修复**:
  1. 验证通过后缓存解析结果，后续连接使用已验证的 IP（`connect(InetSocketAddress(ip, port))`）
  2. 检查解析后的 IP 是否与目标域名匹配
  3. 考虑使用 DNS-over-HTTPS (DoH)
- **代码**:
  ```kotlin
  private fun validateTargetAddress(host: String, port: Int): Boolean {
      // ... 端口验证 ...
      val inetAddr = java.net.InetAddress.getByName(host)  // 第一次解析
      val ip = inetAddr.hostAddress ?: return false
      // IP范围验证：拒绝127.x, 169.254.x, 0.0.0.0, 255.255.255.255, 224.x
      // 仅允许RFC1918私有地址
      IpAddressUtils.isPrivateIpv4Rfc1918(ip)
  }
  // ...
  bootstrap.connect(host, port)  // 第二次独立解析！
  ```

### H5: 连接池清理竞争条件 [已修复]
- **状态**: 已修复
- **修复提交**: `b1e18bd`
- **位置**: `android/app/src/main/java/com/netproxy/gateway/proxy/Socks5ConnectionPool.kt` (`cleanupIdleConnections`, `borrowConnection` 无效连接清理)
- **问题**: ~~read 锁收集、write 锁清理之间连接状态可能变化~~ 原始竞态已修复
- **修复说明**: `cleanupIdleConnections` 完全在单次 `write` 锁内完成筛选与移除（L451-463）；`borrowConnection` 在读锁外收集无效连接后，于 `write` 锁内二次校验 `inUse`/`isValid` 再关闭
- **残余风险**: 写锁内 `removeConnection`/`close()` 仍可能阻塞（见 N27）

### H17: VirtualIpAllocator AtomicInteger溢出 [已修复]
- **状态**: 已修复
- **提交哈希**: `e89e00d`
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VirtualIpAllocator.kt` (L83, L104)
- **问题**: `nextVirtualIp.getAndIncrement()`在达到`Int.MAX_VALUE`后溢出为负数。L72的`currentIp > MAX_IP`检查无法防止溢出（负数不满足条件），导致`require(ipNum in START_IP..MAX_IP)`抛出`IllegalArgumentException`
- **风险**: 高。VPN服务长时间运行后必然崩溃
- **修复**: 使用`Math.floorMod(nextVirtualIp.getAndIncrement(), MAX_IP - START_IP + 1) + START_IP`替代直接递增，与VpnService.kt中H11修复方式一致
- **修复状态**: 已修复

### H8: MQTT TLS证书固定配置可能为空 [已修复]
- **状态**: 已修复
- **修复提交**: M20（运行时修复）
- **位置**: `android/app/src/main/java/com/netproxy/gateway/connection/MqttConnectionManager.kt` (L232-247)
- **问题**: ~~当`MQTT_TLS_PUBLIC_KEY_PINS`为空时，仅记录警告，仍使用默认CA验证~~ 已修复
- **当前行为**:
  - **Release 构建**: 空配置时抛出 `IllegalStateException` 阻止启动，强制要求证书固定（L235-239）
  - **Debug 构建**: 允许空配置，记录警告并回退到系统 CA 验证（L241-246）
- **风险**: Release 构建已无风险；Debug 构建为预期行为
- **残余建议**: 可添加构建时 Lint/Gradle 静态检查作为额外防护层

---

## Medium

### M1: 边界条件：IP地址解析验证 [已修复]
- **状态**: 已修复
- **修复提交**: `21ffa4c`
- **位置**: `android/app/src/main/java/com/netproxy/gateway/utils/IpAddressUtils.kt` (L6-L18)
- **问题**: `isPrivateIpv4Rfc1918`方法本身没有验证每个octet是否在0-255范围内，导致`10.256.0.1`等无效IP被误判为私有地址
- **修复方式**: `isPrivateIpv4Rfc1918WithResult`先调用`validateIpv4WithResult`做前置验证；`isPrivateIpv4Rfc1918`直接调用`isPrivateIpv4Rfc1918WithResult(ip).getOrDefault(false)`，消除重复解析

### M2: WiFi管理器权限检查不一致
- **位置**: `android/app/src/main/java/com/netproxy/gateway/wifi/WifiManager.kt` (L211-L226)
- **问题**: 同一功能有两个版本，一个静默失败，一个返回错误
- **风险**: 调用方无法统一处理错误，可能导致未预期的行为
- **建议修复**: 统一错误处理方式，移除静默失败版本

### M3: 安全检测命令执行未超时 [已修复]
- **状态**: 已修复
- **提交哈希**: `e89e00d`
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

### M11: 连接池状态检查与清理的竞态条件 [已缓解]
- **状态**: 已缓解（与 H5 同一修复，见 H5 详情）
- **修复提交**: `b1e18bd`
- **位置**: `android/app/src/main/java/com/netproxy/gateway/proxy/Socks5ConnectionPool.kt` (`borrowConnection` 无效连接清理路径)
- **问题**: read 锁内收集无效连接、write 锁外清理时状态可能已变
- **缓解**: 于 `write` 锁内对 `!conn.inUse.get() && !conn.isValid()` 二次校验后再 `remove`/`close`

### L2: TODO注释未处理
- **位置**: `android/app/src/main/java/com/netproxy/gateway/connection/MqttConnectionManager.kt` (L458)
- **问题**: 存在未处理的TODO注释，涉及安全配置
- **风险**: 已知问题被遗漏
- **建议修复**: 处理TODO或创建正式issue跟踪

### L3: EmulatorDetector权限检查重复
- **位置**: `android/app/src/main/java/com/netproxy/gateway/security/EmulatorDetector.kt`
- **问题**: 多个方法重复检查`READ_PHONE_STATE`权限
- **部分修复**: 已提取为私有方法 `hasReadPhoneStatePermission(context)`，但三个方法仍各自调用，未在更高层统一
- **建议修复**: 在调用方统一检查权限，或确认当前模式可接受

### L5: 缺少集成测试
- **问题**: 测试主要集中在单元测试，缺少组件间集成测试
- **风险**: 组件间交互问题难以发现
- **建议修复**: 添加集成测试套件

## 新增问题

### N1: 双版本API增加维护负担
- **位置**: `android/app/src/main/java/com/netproxy/gateway/wifi/GatewayWifiManager.kt`, `AuthSessionStore.kt`
- **问题**: 每个主要操作都有两个版本（如 `startScan()` 和 `startScanWithResult()`），维护成本翻倍，容易出现版本间行为不一致
- **风险**: 中。代码冗余，维护困难
- **建议修复**: 统一使用 `AppResult` 模式，移除静默失败版本

### N2: VpnService过于庞大
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt` (1186行)
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

### N11: 硬编码默认值不安全 [已修复]
- **状态**: 已修复（release 构建改为 fail-fast，debug 保留 localhost 默认值用于本地开发）
- **修复策略**: 在 `buildTypes.release` 块内强制要求 `MQTT_BROKER_URL_TLS_RELEASE` 配置，未配置时在 Gradle 配置阶段抛出 `GradleException`
- **修复提交**: 21ffa4c
- **位置**: `android/app/build.gradle.kts`
- **问题**: 过去 release 也可能因默认值回退而误用本地地址；现已改为必须显式配置 release MQTT 地址
- **风险**: 低（修复前为配置失误风险）

### N12: 运行时配置缺失
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt` (L96-99)
- **问题**: DNS 服务器列表硬编码，连接池参数硬编码，无法动态调整
- **风险**: 低。灵活性不足
- **建议修复**: 将配置提取到配置文件或远程配置中心

### N67: RootDetector.checkMagiskProps() 严重误报导致100%正常设备被判定为root
- **状态**: 已修复
- **提交哈希**: `cd7de93`
- **修复提交**: `cd7de93`
- **位置**: `android/app/src/main/java/com/netproxy/gateway/security/RootDetector.kt` (L249-L254)
- **问题描述**: `checkMagiskProps()` 的属性列表包含 `init.svc.zygote`（所有Android设备都有，值为"running"）和 `persist.sys.isUsbOtgEnabled`（与Magisk无关）。由于判断逻辑为 `value != "0" && value != ""`，`init.svc.zygote` 的值 "running" 会导致所有正常设备被误判为已root。
- **风险**: 高。100%正常设备会被误判为root，严重影响用户体验和功能可用性。
- **修复方式**: 移除 `init.svc.zygote` 和 `persist.sys.isUsbOtgEnabled`，只保留真正的Magisk属性 `ro.magisk.version`。
- **验证**: `:app:compileDebugKotlin` 和 `:app:testDebugUnitTest` 通过。

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

## High Severity

### H10: VpnService stopVpn() 竞态条件 [已修复]
- **状态**: 已修复
- **修复提交**: `d01ddd1`
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt`
- **问题**: `isStopping` 原子标志与 `_status` StateFlow 是两个独立的状态源，存在竞态窗口
- **修复方式**:
  1. `stopVpn()` 中采用"先读状态再CAS再双重检查"模式：先检查 `_status` 是否为 STOPPED/STOPPING，再通过 `isStopping.compareAndSet` 确保互斥，CAS 成功后再次确认状态
  2. `onDestroy()` 中不再使用 `isStopping.compareAndSet` 判断 stopVpn 是否被调用，改为直接读取 `_status.value.state`
  3. `startVpn()` 中增加 `isStopping.get()` 检查，防止停止过程中启动
- **验证**: `make android-test` 通过

### H11: writeBufferPool 线程安全问题 [已修复]
- **状态**: 已修复
- **修复提交**: `d01ddd1`
- **修复方式**: 使用局部变量替代共享缓冲区池，彻底消除线程安全问题
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
- **最终方案**: 见 [N54](#n54-vpnservice-threadlocal-writebufferremove-抵消缓冲区复用价值)。`processReturnTraffic` 是单协程顺序执行，同一时刻只有一个 `processTcpReturn` 在执行，直接使用局部变量 `val buffer = ByteArray(PACKET_BUFFER_SIZE)` 更简单安全，无需缓冲区复用或 ThreadLocal。

### H12: activeConnections 复合操作非原子 [已修复]
- **状态**: 已修复
- **提交哈希**: 1f9acee
- **修复提交**: b2256ff
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
- **修复**: 使用 `computeIfPresent()` 原子检查并更新现有会话，使用 `putIfAbsent()` 避免覆盖其他线程刚创建的会话
- **交叉审查结果**: 修复正确，消除了竞态条件。残留的 cleanupStaleConnections 与 forwardViaSocks5 之间的竞态是独立问题，建议后续处理

### H14: processTcpReturn 调用路径仍阻止0长度 TCP 控制包注入 [待修复]
- **状态**: 待修复
- **提交哈希**: d01ddd1
- **相关提交**: 6829cf3（仅放宽 `constructReturnPacket()` 的 payloadLen 检查，未解决 `processTcpReturn()` 的调用门槛）
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt` (L582-L607, L647-L651)
- **问题描述**: `constructReturnPacket()` 内部已允许 `payloadLen == 0`，但 `processTcpReturn()` 仍要求 `available > 0` 且 `read > 0` 才会调用构包路径。结果是0长度 TCP 控制包（如ACK/FIN/RST）在运行时依旧不会被注入；同时构造出的TCP头仍固定为 `PSH+ACK`，并不适合纯控制包。
- **风险**: 高。当前返回路径对纯TCP控制包支持不完整，可能导致连接状态推进异常或超时。
- **修复难度**: 高。需要实现完整的TCP状态机，或至少补齐控制包注入与正确 flags/seq/ack 维护

### H15: VpnService测试直接实例化Android Service [待修复]
- **状态**: 待修复
- **提交哈希**: d01ddd1
- **位置**: `android/app/src/test/java/com/netproxy/gateway/vpn/VpnServiceTest.kt` (L1697, L1718, L1752, L1796, L1846, L1880)
- **问题描述**: 测试代码直接实例化 `GatewayVpnService()`，违反Android组件生命周期规范。`VpnService`必须通过系统创建并调用`onCreate()`后才能使用。直接实例化可能导致依赖未初始化、Hilt注入失败。N86 新增测试（L1796, L1846, L1880）延续了此反模式。
- **风险**: 高。测试不可靠，与实际运行时不一致，可能产生假阳性/假阴性结果。
- **修复难度**: 中。需要引入/调整 Robolectric + Hilt 测试基座，或将纯逻辑下沉为可直接单测的无 Android 组件类

### H16: VpnService测试过度使用反射 [待修复]
- **状态**: 待修复
- **提交哈希**: d01ddd1
- **位置**: `android/app/src/test/java/com/netproxy/gateway/vpn/VpnServiceTest.kt` (L1748-1816)
- **问题描述**: 测试大量使用反射访问私有方法和内部类（`createSessionForReflection`、`invokeConstructReturnPacket`、`invokeProcessTcpReturn`）。代码结构变化会导致测试崩溃，重构时需要同步更新大量反射代码。
- **风险**: 高。维护困难，重构风险大，可读性差，IDE重构工具无法识别反射引用。
- **修复难度**: 中。需要调整可测试性边界（减少私有成员反射耦合），或将关键逻辑下沉为可直接单测的纯 Kotlin 组件



## Low Severity

### L10: Tunnel服务设备状态通知无重试 [已修复]
- **状态**: 已修复
- **修复提交**: d06f584（退避重试）；5eae7f4（C23–C27 加固）
- **位置**: `server/tunnel/main.go` (`notifyDeviceStatus`, `notifyStatusBackoff`)
- **问题描述**: `notifyDeviceStatus` 通知失败仅记日志、无重试
- **修复**: 指数退避重试、复用 `http.Client`、`TunnelManager` 上下文可取消；见 `server/tunnel/main_test.go` 重试用例

---

### N21: 配对码输入状态配置变更丢失
- **状态**: 已修复
- **位置**: `android/app/src/main/java/com/netproxy/gateway/ui/screens/MainScreen.kt` L107
- **问题**: 使用`remember`而非`rememberSaveable`保存配对码输入状态
- **风险**: High。屏幕旋转时丢失用户输入
- **引入来源**: 本次Compose UI变更引入
- **修复**: 将`remember`改为`rememberSaveable`

### N22: MainViewModel状态更新竞争条件
- **状态**: 已修复
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
- **状态**: 已修复
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

### N27: Socks5ConnectionPool cleanupIdleConnections在write锁内执行阻塞IO [已修复]
- **状态**: 已修复
- **提交哈希**: 1f9acee
- **修复提交**: 1554cf0
- **位置**: `android/app/src/main/java/com/netproxy/gateway/proxy/Socks5ConnectionPool.kt` (L435-L457)
- **问题描述**: `removeConnection(conn)` 在 `write` 锁内被调用，内部执行 `connection.close()` 阻塞IO操作。在高并发或网络异常时，长时间持有 `write` 锁会阻塞所有 `borrowConnection` 和 `returnConnection` 操作
- **风险**: 高。严重影响连接池并发性能，可能导致连接获取超时
- **修复**: 将 `cleanupIdleConnections()` 中的 `removeConnection(conn)` 拆分为锁内集合移除 + 锁外 `conn.close()`，消除write锁内的阻塞IO
- **残余修复**: `returnConnection()` 和 `borrowConnection()` 中仍存在的锁内 close 已修复：`removeConnection()` 改为仅做跟踪移除，调用者在锁外关闭；`returnConnection()` 使用 `toClose` 收集需关闭的连接；`borrowConnection()` 无效连接清理使用 `toClose` 列表锁外关闭



### N30: Socks5ProxyHandler DNS解析阻塞EventLoop [部分修复，引入回归]
- **状态**: 部分修复
- **提交哈希**: 1f9acee
- **修复提交**: b18d832（移除了阻塞DNS解析，但引入功能回归）
- **位置**: `android/app/src/main/java/com/netproxy/gateway/proxy/Socks5ProxyHandler.kt` (L111-L128)
- **问题描述**: `InetAddress.getByName(host)` 是同步阻塞调用，在 Netty EventLoop 线程上执行。DNS 查询可能耗时数百毫秒甚至超时（数秒），期间阻塞该 EventLoop 上的所有 I/O 事件
- **修复**: 改为纯IP格式校验，消除了阻塞DNS调用
- **交叉审查发现问题**: 修复后 `validateTargetAddress()` 直接拒绝所有域名格式的目标地址（返回false），导致SOCKS5域名连接功能失效。如果项目需要支持域名访问，需要恢复域名支持并将DNS解析异步化
- **风险**: 高。单连接慢DNS查询导致整个 SOCKS5 服务所有连接停滞；修复后域名连接被拒绝
- **修复难度**: 中。需要引入异步 DNS 解析或使用线程池执行 DNS 查询

### N31: NetworkStateManager onLost多网络状态误判 [已修复]
- **状态**: 已修复
- **提交哈希**: 1f9acee
- **位置**: `android/app/src/main/java/com/netproxy/gateway/connection/NetworkStateManager.kt`
- **问题描述**: ~~旧实现中 `onLost(network)` 只接收丢失的特定网络，不判断是否还有其他可用网络。`getStateAfterNetworkLost()` 直接返回 `isConnected=false`。多网络环境（WiFi+移动数据）下断开一个网络会错误报告为完全断网~~
- **当前实现**: 代码已使用 `activeNetworks` ConcurrentHashMap 维护多网络状态，`getBestNetworkState()` 遍历所有活跃网络并按优先级（Ethernet > WiFi > Cellular）返回最佳网络状态。`onLost` 仅移除对应网络，不会错误报告完全断网。
- **风险**: 已消除。多网络场景下断开单一网络不会触发误判。
- **修复状态**: 当前实现已正确处理多网络共存和切换场景。

### N34: MainViewModel VPN状态与真实服务状态可能不一致 [已修复]
- **状态**: 已修复
- **提交哈希**: 1f9acee
- **修复提交**: 2f89166
- **位置**: `android/app/src/main/java/com/netproxy/gateway/ui/viewmodel/MainViewModel.kt` (L129-L161)
- **问题描述**: `startForegroundService()` 后立即设 `isVpnEnabled=true`，但服务启动可能失败（权限被拒、系统限制、OOM）。UI 显示 VPN 已开启但实际服务未运行，缺少通过 ServiceConnection 同步真实状态的机制
- **风险**: 高。用户看到的状态与实际不符，可能导致安全/功能问题
- **修复**: `isVpnEnabled` 不再由 `toggleVpn()` 直接设置，而是由 `GatewayVpnService.status.state` 驱动。`RUNNING`->true，`STOPPED`/`ERROR`->false，`STARTING`/`STOPPING`保持当前值避免闪烁
- **交叉审查结果**: 修复正确，UI状态与真实服务状态一致



### N36: VpnService TCP固定标志位不符合协议状态机
- **提交哈希**: 1f9acee
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt` (L708-L709)
- **问题描述**: `constructReturnPacket` 固定设置 `PSH+ACK (0x18)`，从未根据 TCP 连接状态设置 `SYN`/`FIN`/`RST` 标志。连接建立应发送 `SYN+ACK`，终止应发送 `FIN+ACK`
- **风险**: 高。与严格遵循 TCP 协议栈的应用不兼容，可能导致连接建立失败或异常断开
- **修复难度**: 高。需要实现完整的 TCP 状态机，正确管理序列号和标志位

### N37: AuthSessionStore CharArray安全设计被String抵消 [修复被回退]
- **状态**: 修复被回退
- **提交哈希**: 1f9acee
- **修复提交**: 5f31a7d（已将 ProxyAuthSession.authToken 改为 CharArray）
- **位置**: `android/app/src/main/java/com/netproxy/gateway/connection/AuthSessionStore.kt` (L183, L190, L203-L206)
- **问题描述**: `loadSession()` 将 `CharArray` 转为 `String` 返回，且 `ProxyAuthSession.authToken` 类型也是 `String`。`CharArray` 可清零的安全设计被完全绕过，敏感 token 以不可变 String 形式存在于内存
- **交叉审查发现问题**: 提交 5f31a7d 确实将 `ProxyAuthSession.authToken` 改为 `CharArray`，但当前 HEAD 代码中 `authToken` 已被回退为 `String` 类型（L205）。需调查回退原因
- **风险**: 高。安全设计意图失效，token 无法被主动擦除
- **修复难度**: 中。将 `ProxyAuthSession.authToken` 类型改为 `CharArray`，在业务层传递时保持 `CharArray` 形式

### N39: server/api JWT Secret长度未验证 [已修复]
- **状态**: 已修复
- **修复提交**: 202bb95（同提交含其他修复；JWT 校验见 `validateJWTSecret`）
- **位置**: `server/api/main.go` (`MinJWTSecretLength`, `validateJWTSecret`, 启动校验)
- **问题描述**: JWT Secret 仅检查非空，未验证长度
- **修复**: `MinJWTSecretLength = 32`，`validateJWTSecret` 不足 32 字符时拒绝启动

### N40: server/socks5-proxy GetOrConnectTunnel连接存活检查竞态 [已修复]
- **提交哈希**: 1f9acee
- **位置**: `server/socks5-proxy/main.go` (L479-L577)
- **问题描述**: RLock 释放后调用 `isConnAlive`，期间其他 goroutine 可能删除连接并创建新连接。返回的连接可能已被替换或即将关闭，典型的 Check-Then-Act 竞态
- **风险**: 高。返回失效连接导致客户端操作失败，影响连接稳定性
- **修复**: 将 `GetOrConnectTunnel` 中的 `RLock` 改为 `Lock`，在锁保护内完成连接存活检查和删除操作，消除 Check-Then-Act 竞态
- **修复状态**: 已修复

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

### N51: server/socks5-proxy StreamConn.Read 在关闭边界可能丢失已排队数据
- **状态**: 已修复
- **位置**: `server/socks5-proxy/main.go` (L301-L308, L370-L372, L711-L715)
- **问题描述**: 旧实现直接 `select` 等待 `DataChan` 和 `CloseChan`。当 `CloseChan` 已关闭且 `DataChan` 中仍有已排队未读数据时，两个分支会同时就绪，`Read()` 可能直接返回 `io.EOF`，导致尾部数据未被消费。另一角度：即使 `CloseChan` 关闭后，其他 goroutine 仍可能向 `DataChan` 写入数据，若 `Read()` 优先选择 `CloseChan` 分支返回 `io.EOF`，这些已写入数据将丢失。
- **风险**: 中。连接关闭边界下可能截断隧道中的尾部数据，造成协议交互不完整
- **修复**: `Read()` 现在使用双 `select` 模式优先消费已排队数据；`CloseChan` 分支使用 `for` 循环非阻塞排空 `DataChan` 后再返回 `io.EOF`
- **修复状态**: 已修复



### N54: VpnService ThreadLocal writeBuffer.remove() 抵消缓冲区复用价值
- **状态**: 已修复
- **提交哈希**: d01ddd1
- **位置**: `VpnService.kt processTcpReturn() 方法内`
- **问题描述**: N52的修复在 `processTcpReturn()` 的 `finally` 块中调用 `writeBuffer.remove()`，导致每次调用结束后ThreadLocal被清空，下次调用 `getWriteBuffer()` 时重新创建 `ByteArray`。ThreadLocal完全退化为每次重新分配，无任何复用价值。`processReturnTraffic` 是单协程顺序执行，同一时刻只有一个 `processTcpReturn` 在执行，直接用局部变量更简单安全。
- **风险**: 中。每次调用分配 `PACKET_BUFFER_SIZE` 大小的数组增加GC压力；ThreadLocal使用不当增加代码复杂度
- **修复**: 将 ThreadLocal 替换为局部变量，移除 `getWriteBuffer()` 和 `finally` 中的 `remove()`
- **修复状态**: 已修复

### N53: server/socks5-proxy StreamConn.Read 在小缓冲区下会直接截断数据
- **状态**: 已修复
- **位置**: `server/socks5-proxy/main.go` (L285, L301-L305)
- **问题描述**: 旧实现从 `DataChan` 取出一整块数据后仅执行 `n := copy(p, data)` 并直接返回，没有保存 `data[n:]` 的剩余部分；结构体中的 `WriteBuffer` 字段也没有参与读取路径。只要调用方提供的 `p` 小于单次消息长度，尾部数据就会被无条件丢弃。
- **风险**: 高。违反 `net.Conn` 流语义，导致数据截断、协议交互失败或上层解析错误
- **修复**: `Read()` 现在会缓存未消费 remainder，并在后续读取时优先返回缓存数据；同时增加了小缓冲区回归测试
- **修复状态**: 已修复
- **关联问题**: 原N48（接口契约角度）已合并至本条目

### N55: DebugDetector 4个方法正常完成路径未调用 process.destroy() [已修复]
- **状态**: 已修复
- **修复提交**: 1ba8a50
- **位置**: `android/app/src/main/java/com/netproxy/gateway/security/DebugDetector.kt` (`checkDebuggerProcess`, `checkJDWP`, `checkFrida`, `checkDebugProperties`)
- **问题描述**: 正常完成路径未 `process.destroy()`；部分路径未 drain stderr
- **修复**: `try-finally` 保证 `process.destroy()`；相关路径 drain stderr
- **关联问题**: M3

### N56: StreamConn SetWriteDeadline 空实现 [部分已修复]
- **状态**: 部分已修复
- **提交哈希**: 9f4b1b9
- **位置**: `server/socks5-proxy/main.go` (L629-L648)
- **问题描述**:
  - `SetReadDeadline`：**已实现**（L638-L641）。使用 `atomic.Value` 存储 deadline，`Read()` 中通过 `time.NewTimer` 实现超时，超时返回 `os.ErrDeadlineExceeded`。
  - `SetDeadline`：**已实现**（L630-L635）。内部调用 `SetReadDeadline` 和 `SetWriteDeadline`。
  - `SetWriteDeadline`：**仍为空实现**（L646-L648），仅返回 `nil`。WebSocket 写入已通过 `writeMu + streamWriteLimit` 保护，但 `net.Conn` 接口语义上写 deadline 未生效。
  - `relay()` 阻塞风险：`relay()` 中通过 `io.Copy` 间接调用 `Read()` 时若未设置 read deadline 可能阻塞，但 `readLoop` 的 60 秒 WebSocket 超时会关闭连接，不会**永久**泄漏 goroutine（最多泄漏 60 秒）。
- **风险**: 中。`SetWriteDeadline` 空实现导致 `SetDeadline` 写超时语义不完整；`relay()` 阻塞被 60 秒超时限制，不会永久泄漏
- **修复难度**: 低。实现 `SetWriteDeadline` 完整语义；评估是否为 `relay()` 默认设置 read deadline
- **关联问题**: TECH_DEBT.md C79

### N57: VpnService processReturnTraffic 单协程串行处理模型导致回包处理停滞
- **状态**: 待修复
- **提交哈希**: d01ddd1
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt` (L536-L562, L593-L598)
- **问题描述**: `processReturnTraffic` 使用单协程串行遍历所有活跃连接（`activeConnections.forEach`），对每个连接同步调用 `processTcpReturn`。虽然 `input.available()` 检查可降低阻塞概率，但 `available()` 返回的是估计值，在 `available()` 和 `read()` 之间数据量可能变化。当 `available() > 0` 时 `read()` 通常不阻塞，但极端情况下（如连接被对端 RST）可能阻塞最多 `soTimeout`（30秒）。任何一个连接的 I/O 阻塞都会导致所有后续连接的回包处理停滞。
- **风险**: 中。高延迟或慢速上游连接场景下，单个连接的阻塞可导致其他连接回包延迟
- **修复难度**: 高。需要将串行处理改为并行处理（如每个连接独立协程），或使用 NIO 非阻塞 I/O
- **关联问题**: ISSUES.md H14, TECH_DEBT.md C78

### N58: VpnService processTcpReturn 依赖 InputStream.available() 不可靠
- **状态**: 待修复
- **提交哈希**: d01ddd1
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt` (processTcpReturn 中 available() 检查处)
- **问题描述**: `processTcpReturn` 使用 `input.available() > 0` 判断是否有回包数据可读。`available()` 返回的是估计值而非保证值，可能返回 0 但实际有数据已到达（尤其在网络延迟或 TCP 窗口滑动场景）。若 `available()` 返回 0，当前实现会跳过该连接的读取，导致回包被延迟到下一轮循环，增加延迟。
- **风险**: 中。极端网络条件下回包延迟增加，影响实时性要求高的应用
- **修复难度**: 中。需要引入非阻塞 I/O 或 select/poll 机制替代 available() 估计值判断
- **关联问题**: N57

---

## 交叉审查发现（5 轮修复批次，提交 8129c4c..0708644）

> 以下问题由批次结束后的交叉审查记录；**本轮不修复**，留待后续处理。

### N59: M13 修复后首次连接失败的首轮重连延迟变为 10 秒
- **提交哈希**: 4e965e2
- **位置**: `android/app/src/main/java/com/netproxy/gateway/connection/MqttConnectionManager.kt` (`connect()` 的 `catch` 路径、`onReconnectAttemptFailed`)
- **问题描述**: `onReconnectAttemptFailed()` 在**用户首次** `connect()` 失败时也会执行，将 `reconnectDelay` 从 5s 倍增为 10s 后再 `scheduleReconnect`。旧逻辑在 `scheduleReconnect` 内先 `delay(5s)` 再倍增，首次重连仍为 5s。行为回归：首次连接失败后的第一次自动重连由 5s 变为 10s。
- **风险**: 低。仅影响首次连接失败场景的重连等待时间
- **建议修复**: 仅在「已由 `scheduleReconnect` 触发过的重连尝试失败」时递增；或把递增移到 `scheduleReconnect` 内 `connect()` 返回失败之后

### N60: N47 测试修复中 `runTest` 未共享 `testScope` 的调度器
- **提交哈希**: 8129c4c
- **位置**: `android/app/src/test/java/com/netproxy/gateway/connection/MqttConnectionManagerConnectCleanupTest.kt`
- **问题描述**: `MqttConnectionManager` 注入的是 `@Before` 中的 `TestScope(testDispatcher)`，但测试体使用无参 `runTest { advanceUntilIdle() }`，默认可能使用与 `testDispatcher` 不同的 `StandardTestDispatcher`。在部分 kotlinx-coroutines-test 版本/负载下，`advanceUntilIdle()` 可能无法排空 manager 协程，flaky 风险未完全消除。
- **风险**: 中。测试套件仍可能偶发失败
- **建议修复**: 改为 `runTest(testDispatcher) { testScope.advanceUntilIdle() }` 或让 manager 使用 `runTest` 提供的 scope
- **修复状态**: 已修复。提交 `299d6da` 将测试改为 `testScope.runTest` + `testScope.advanceUntilIdle()`。

---

## 交叉审查发现（工作区未提交批次，2026-05-20）

### N61: 工作区 CLAUDE.md 含无关空白符改动且误写 MQTT 环境变量名
- **位置**: `CLAUDE.md`（未提交，已 `git restore`）
- **问题描述**: diff 主要为列表前空行等格式噪音；并将文档中的 `MQTT_TLS_PUBLIC_KEY_PINS` 误改为 `MQTTTLSPUBLICKEYPINS`，与 `BuildConfig`/gradle 属性名不一致。
- **风险**: 低（误导后续 Agent/开发者）
- **处置**: 不提交；保持仓库内正确名称 `MQTT_TLS_PUBLIC_KEY_PINS`

### N62: [误报] Gradle `-Xmx6g` 与 CI 失败无关
- **位置**: `android/gradle.properties`；提交 `b9a9f97` 曾临时改回 `-Xmx2048m`
- **原误判**: 以为 `ubuntu-latest` 仅 7GB RAM，6GB Gradle 堆会导致 CI OOM。
- **事实**: 当前标准 `ubuntu-latest` 为 **16GB RAM**（4 vCPU）；`-Xmx6g` 在 CI 与本地 16GB 环境均可接受。
- **处置**: 已恢复 `-Xmx6g` 并保留 `MaxMetaspaceSize`/HeapDump 配置；内存不足的开发机可在 `~/.gradle/gradle.properties` 本地下调 `-Xmx`

---

## SubAgent 交叉审查发现（2026-05-20，审查提交 690d572..2e2e297）



### N66: `VirtualIpAllocator` 分配失败时 `nextVirtualIp` 泄漏
- **提交哈希**: 690d572
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VirtualIpAllocator.kt`
- **问题描述**: `nextVirtualIp.getAndIncrement()` 在 `require(attempts < maxAttempts)` 之前被多次调用。若 `require` 抛出（IP 池耗尽），`nextVirtualIp` 已递增但无 IP 被分配。
- **验证结果**: **潜在风险（建议修复）**。IP 池大小为 254（MAX_IP - START_IP + 1）。AtomicInteger 溢出后会自然回绕，且 `Math.floorMod` 能将任何整数映射回有效范围，功能上不会出问题。但 `nextVirtualIp` 值会无意义漂移，若后续代码依赖其原始值做判断可能导致意外行为。
- **建议修复**: 将 `getAndIncrement()` 移到确认分配成功后再调用，避免漂移。优先级：**低**。



### N70: `StreamConn.SetReadDeadline` 更新无法被阻塞中的 `Read` 感知
- **提交哈希**: 15414b05
- **位置**: `server/socks5-proxy/main.go`
- **问题描述**: `SetReadDeadline` 使用 `atomic.Value` 存储 deadline，但已进入 `select` 阻塞的 `Read` 不会响应新的 deadline。
- **验证结果**: **潜在风险（建议修复）**。从纯技术角度，动态更新确实无法被已阻塞的 `Read` 感知。但当前 SOCKS5 代理场景下没有动态更新 deadline 的需求（`io.Copy` 不调用 `SetReadDeadline`）。这是接口契约层面的潜在风险，而非当前运行时的 bug。
- **建议**: 在 `StreamConn` 文档中明确说明此限制，或考虑使用 `context.Context` 方案。优先级：**低**。



### N72: `readLoop` defer 不调用 `detachTunnel()`
- **提交哈希**: 15414b05
- **位置**: `server/socks5-proxy/main.go` (`readLoop` defer)
- **问题描述**: `readLoop` defer 调用 `closeLocal()` 关闭 streams，但不调用 `detachTunnel()`。`stream.TunnelConn` 仍指向已关闭的 WebSocket 连接。
- **验证结果**: **代码风格建议**。`closeLocal()` 设置 `Closed=1` 后，`StreamConn.Write` 在入口原子检查（`atomic.LoadInt32(&s.Closed) == 1`）会立即返回错误，不会执行到 `tunnelConn.WriteMessage`。因此当前代码在功能上是安全的。添加 `detachTunnel()` 仅有防御性价值（彻底切断引用关系），无实际 bug 风险。
- **建议修复**: 在 `readLoop` defer 中补充 `stream.detachTunnel()` 调用，消除 `TunnelConn` 悬空引用。优先级：**极低**。



### N76: `doValidatedHTTPPost` 在 socks5-proxy 与 tunnel 中重复定义
- **提交哈希**: 83c547be
- **位置**: `server/socks5-proxy/main.go`、`server/tunnel/main.go`
- **问题描述**: 两个文件中 `doValidatedHTTPPost` 几乎完全相同，违反 DRY 原则。
- **验证结果**: **潜在风险（建议修复）**。两个实现确实一致，但当前 server/ 下三个服务是独立的 Go 模块（各自有 go.mod），没有共享包。提取到共享包需要创建新模块并修改所有服务的依赖，涉及构建流程和部署流程变更。函数仅30行，逻辑简单，当前工作正常。
- **建议修复**: 在统一 server/ 目录的 Go 模块结构时一并处理（与 TECH_DEBT.md C7 相关）。优先级：**低**。

### N77: `tunnel/main.go` `sendLoop`/`readLoop` 存在数据竞争风险
- **提交哈希**: 既有问题（非本次引入）
- **位置**: `server/tunnel/main.go` (`sendLoop`, `readLoop`)
- **问题描述**: `sendLoop` 和 `readLoop` 直接访问 `tunnel.Conn` 而不持有 `connMu`，与 `TunnelConn.Close()` 的写操作存在竞态。
- **验证结果**: **真实问题**。`sendLoop`/`readLoop` 确实不持有 `connMu` 就访问 `tunnel.Conn`。`Close()` 在 `connMu` 保护下将 `Conn` 置为 nil 并关闭连接。竞态后果包括：(1) `sendLoop` 在 `Conn` 被关闭后调用 `WriteMessage` 返回错误；(2) `sendLoop` 检查 `tunnel.Conn == nil` 后到调用 `WriteMessage` 之间，`Close()` 可能将 `Conn` 置为 nil，导致 panic。`heartbeat` 通过 `WritePing` 调用的是 `WriteControl`，根据 `gorilla/websocket` 文档，`WriteControl` 可与其他方法并发安全调用，因此 `sendLoop` 与 `heartbeat` 之间不存在 `WriteMessage` 的并发调用问题。可用 `go test -race` 检测。
- **建议修复**: 在 `sendLoop` 和 `readLoop` 中对 `tunnel.Conn` 的访问加上 `connMu` 保护，与 `WritePing` 保持一致。优先级：**中**。

---

## 交叉审查发现（2026-05-21，审查提交 4c84e4e..4c62b32）

> 以下问题由5轮修复批次结束后的交叉审查记录；**本轮不修复**，留待后续处理。

### N63: `cleanupStream` 中 `streamConn.Close()` 在 `tc.mu` 锁内执行 [已修复]
- **提交哈希**: `821518f`
- **修复提交**: `9fb8b32`
- **位置**: `server/socks5-proxy/main.go` (`cleanupStream`，L1043-L1057)
- **问题描述**: 提交 821518f 声称将 `streamConn.Close()` 移出 `tc.mu` 锁外，但实际代码中 `Close()` 仍在 `defer tc.mu.Unlock()` 保护下执行。`Close()` 是 I/O 操作，持锁期间阻塞会卡住整个 `TunnelClient` 的流管理。
- **修复方式**: 改为显式 `tc.mu.Lock()` / `tc.mu.Unlock()`，在锁内仅做 `delete(tc.streams, streamID)` 并标记 `exists`，解锁后再调用 `streamConn.Close()`。

### N78: `readLoop` defer 中 `conn.Close()` 仍在 `tc.mu` 锁内执行 [已修复]
- **提交哈希**: `f93054a`
- **修复提交**: `821518f`
- **位置**: `server/socks5-proxy/main.go` (`readLoop` defer，约 L860-873)
- **问题描述**: `readLoop` 的 `defer` 块中获取 `tc.mu.Lock()`，然后在锁内调用 `stream.closeLocal()` 和 `conn.Close()`。`conn.Close()` 是 WebSocket I/O 操作，持锁期间阻塞会卡住整个 `TunnelClient` 的流管理。N63 同期仅修复了 `cleanupStream` 的同类问题，但 `readLoop` 的 defer 路径存在相同的持锁 I/O 模式。
- **修复方式**: 在锁内收集需要关闭的 stream 列表和 conn 关闭标记，解锁后再逐个调用 `stream.closeLocal()` 和 `conn.Close()`。

---

## 交叉审查发现（工作区未提交批次，2026-05-28）

> 以下问题由代码风格修改后的审查记录；**本轮不修复**，留待后续处理。

### N79: SOCKS5-Proxy DataChan 满时关闭 stream 导致连接抖动风险
- **状态**: 待修复
- **提交哈希**: 当前工作区 (bd47aa4 引入，本次代码审查确认)
- **位置**: `server/socks5-proxy/main.go` (`handleData`，L1030-L1035)
- **问题描述**: `bd47aa4` 将 DataChan 满时的行为从"丢弃数据包"改为"关闭 stream"。虽然符合 TCP 语义，但在高带宽场景（文件传输、视频流）下，如果下游消费慢（工程师侧网络延迟、CPU 波动），DataChan 可能频繁填满，导致 stream 被反复关闭和重建。
- **连锁反应**:
  1. `cleanupStream` 发送 `disconnect` 消息到设备端
  2. Android 端**未找到处理 `disconnect` 消息的逻辑**，导致半开连接
  3. 设备端下一次写操作失败后才清理，经历一次失败的 I/O
  4. 每次重建需要完整 SOCKS5 握手（1-3 个 RTT）
- **风险**: **高**。远程协助中的文件传输、视频查看等高带宽场景下，频繁断连严重影响用户体验。
- **缓解措施**:
  1. 短期：增大 `defaultDataChanSize`（100 → 256/500），减少关闭频率
  2. 中期：在 Android 端实现 `disconnect` 消息处理，及时清理连接
  3. 长期：引入背压机制（流控消息）或重构为每个 stream 独立 goroutine
- **关联问题**: ISSUES.md N57（VpnService 单协程串行处理）、N58（available() 不可靠）

### N80: Android 端不处理 SOCKS5-Proxy 的 disconnect 消息
- **状态**: 待修复
- **提交哈希**: 既有问题
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt`, `Socks5ProxyHandler.kt`, `Socks5ConnectionPool.kt`
- **问题描述**: SOCKS5-Proxy 在 stream 关闭时会通过 WebSocket 发送 `type: "disconnect"` 消息，但搜索 Android 端代码未发现任何处理该消息的逻辑。`VpnService`、`Socks5ProxyHandler`、`Socks5ConnectionPool` 均不消费此消息。
- **后果**: 服务端已关闭 stream，但 Android 端仍认为连接有效，形成半开连接。下次写操作失败后才被动清理。
- **风险**: **中**。导致不必要的 I/O 失败和连接重建延迟。
- **建议修复**: 在 Tunnel 消息处理层添加 `disconnect` 类型消息的处理，收到后主动归还 SOCKS5 连接并清理会话。
- **关联问题**: N79

---

## 提交审查发现（2026-05-28，审查提交 715dd88..c0b3f87）

> 以下问题由对近10次提交的代码审查记录；**本轮不修复**，留待后续处理。

### N81: Kotlin文件CRLF换行符未实际转换为LF [已修复]
- **状态**: 已修复
- **提交哈希**: `41890f9`（声称修复但未生效）
- **位置**: `android/app/src/main/java/com/netproxy/gateway/**/*.kt`
- **问题描述**: 提交 `41890f9` 声称将CRLF转为LF，但diff中完全没有换行符变更。根本原因是仓库缺少 `.gitattributes` 配置，Windows环境下`core.autocrlf=true`持续将工作区文件转回CRLF。当前全部32+个Kotlin源文件仍使用CRLF换行符。
- **风险**: **中**。跨平台协作时换行符不一致导致diff噪音、review困难、潜在脚本执行问题。
- **修复**: 添加 `.gitattributes` 文件，指定 `*.kt text eol=lf`、`*.kts text eol=lf` 等源文件强制使用LF换行符
- **关联问题**: STYLE_GUIDE.md 换行规范

### N82: server/tunnel Register/Unregister异步通知引入竞态条件
- **状态**: 已修复
- **提交哈希**: `37e470a`（引入问题）；`f9a628d`（主要修复：添加 stopMu + stopped 标志）；`8aa69d0`（审查改进：添加注释、移除冗余检查）
- **位置**: `server/tunnel/main.go` (`Register`, `Unregister`, `cleanupDeadTunnelsOnce`)
- **问题描述**: 提交 `37e470a` 将 `m.notifyDeviceStatus(...)` 从同步调用改为 `go m.notifyDeviceStatus(...)` 异步调用。`notifyDeviceStatus` 内部调用 `m.wg.Add(1)`，存在 `wg.Add` 在 `Stop()` 的 `wg.Wait()` 之后执行的时序。**会导致 `panic: sync: WaitGroup is reused before previous Wait has returned`**。`go test -race -count=200` 可稳定复现 panic 和 data race。
- **风险**: **高**。服务关闭时可能直接崩溃，测试高并发下几乎必现 panic。
- **修复**: 已在 `notifyDeviceStatus` 入口处添加 `m.ctx.Done()` 检查，`Stop()` 后不再执行 `wg.Add(1)`，彻底消除竞态。
- **关联问题**: L10（Tunnel服务设备状态通知无重试，已修复）

### N83: server/tunnel关键并发安全注释被移除 [已修复]
- **状态**: 已修复
- **提交哈希**: `37e470a`
- **位置**: `server/tunnel/main.go`
- **问题描述**: 提交 `37e470a` 在"注释国际化"过程中移除了约27处中文注释，其中包括3处关键的并发安全设计注释：
  1. `heartbeat` 中关于 `WritePing` 与 `Close` 共享 `connMu` 锁的竞态防护说明
  2. `Run` 中关于 `wg.Add(1)` 必须在goroutine外的原因说明（避免与`Stop`竞态）
  3. `cleanupDeadTunnelsOnce` 中关于 `current == tunnel` 实例匹配检查的说明
- **风险**: **中**。代码当前功能正常，但未来重构时极易误删关键并发防护逻辑，引入竞态bug。
- **修复**: 恢复了3处关键并发安全注释（翻译为英文），同时补充了 `Unregister` 方法中同类身份检查的注释

### N84: NetworkStateManager防御性测试未标注"未来场景"
- **状态**: 待修复
- **提交哈希**: `26d221f`
- **位置**: `android/app/src/test/java/com/netproxy/gateway/connection/NetworkStateManagerTest.kt` (L216-281)
- **问题描述**: `validatedWifiPreferredOverUnvalidatedWifi` 和 `validatedCellularPreferredOverUnvalidatedCellular` 测试通过反射将未验证网络注入 `activeNetworks`，绕过 `isValidNetwork` 的过滤。当前生产代码中这些场景不可能发生。测试验证的是"未来放宽isValidNetwork条件"的防御性设计行为，但测试代码未明确标注此意图。
- **风险**: **低**。维护者可能困惑为什么测试要绕过正常入口检查，误以为当前代码有bug。
- **建议修复**: 在测试方法上方添加注释，明确说明这些测试验证的是"防御性设计场景：未来如果放宽isValidNetwork条件时的排序行为"。

### N85: ISSUES.md文档格式不一致
- **状态**: 待修复
- **位置**: `docs/ISSUES.md`, `docs/issues/INDEX.md`, `docs/issues/modules/vpn.md`
- **问题描述**: 文档中存在多处格式和行号不一致：
  1. N2行数三处不一致：ISSUES.md写1178行、INDEX.md写1186行、vpn.md写1054行（实际1186行）
  2. 部分问题标题使用`### Nxx:`带冒号，部分不带冒号
  3. 部分旧问题缺少"提交哈希"字段，新增问题均有此字段
  4. 风险等级描述格式不统一（有的用"风险: 高"，有的用"**风险**: **高**"）
- **风险**: **低**。不影响功能，但增加维护成本和阅读困难。
- **建议修复**:
  1. 统一所有文档中的代码行号为实际值
  2. 统一标题格式（建议全部使用`### Nxx: 描述`）
  3. 统一风险等级格式为`**风险**: **等级**`
  4. 为旧问题补全提交哈希（如已知）

### N86: Stale SOCKS5连接不应归还到连接池
- **状态**: 已修复
- **提交哈希**: `4ab337d`（WIP：初始修复）；`b0a86f5`（新增 discardConnection API）；`93fccd9`（P2 域名修复）
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt`
  - `cleanupStaleConnections()` L569
  - `forwardViaSocks5` L485（正常路径）
  - `forwardViaSocks5` L515（新会话创建冲突时）
  - `forwardViaSocks5` catch 块 L532（转发异常后）
  - `processTcpReturn` L641（通过 `removeSessionAndCloseConnection`）
  - `stopVpn()` L1138-L1141（VPN停止时）
- **关联修改**: `PooledSocks5Connection.close()` 新增 `inUse.set(false)` 防止连接池计数泄漏；`cleanupIdleConnections` 增加 `!conn.isValid()` 检查确保已关闭连接及时清理
- **问题描述**: `ConnectionSession` 与 `PooledSocks5Connection` 的生命周期绑定存在设计缺陷。当 VPN 会话因超时或异常被清理时，`PooledSocks5Connection` 上可能残留未消费的数据或处于不确定的 TCP 状态。将其 `returnConnection()` 回池会导致后续借用者读取到脏数据，造成流量混淆。连接池的复用语义（同一 dstIp:dstPort 可复用）与 VPN 会话语义（每个五元组独立字节流）不匹配。
- **风险**: **高**。可能导致跨会话的流量混淆和数据泄漏。
- **修复方案**: 所有从 `activeConnections` 移除的过期/无效会话，其 `pooledConnection` 直接关闭（`close()`）而非归还到连接池（`returnConnection()`）。`PooledSocks5Connection.close()` 中设置 `inUse=false`，`cleanupIdleConnections` 增加 `!isValid()` 检查，确保连接池的 `allConnections` 和 `totalConnections` 状态及时同步。



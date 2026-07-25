# NetProxyGateway 缺陷清单（待修复）
不要在此文档记录日期；用 7 位提交哈希作为“时间锚点”识别问题存在的版本。

**编号约定**：本文档 **C\*** 编号仅用于 ISSUES 内 **C1**（如 C1: SSL 信任所有证书）；与 `docs/TECH_DEBT.md` 的 C2+ 编号无关。

字段约定：
- **提交哈希**：该问题在此提交存在（若仅存在于未提交工作区变更，记录当前 HEAD 的 7 位哈希并标注 `(worktree)`）
- **修复提交**：修复该问题的提交（若已修复）

需要记录：问题存在的提交哈希、问题文件路径、问题行号、问题描述、风险、修复难度、修复状态。
## Critical

### C1: SSL信任所有证书配置风险
- **状态**: 已修复
- **修复提交**: `a1747a6`
- **修复内容**: release构建时Gradle检查，若`MQTT_TRUST_ALL_CERTS=true`则阻止构建
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

### H4: SOCKS5代理DNS重绑定攻击风险 [已修复]
- **状态**: 已修复
- **位置**: `android/app/src/main/java/com/netproxy/gateway/proxy/Socks5ProxyHandler.kt` (L109-137)
- **修复内容**: `validateTargetAddress` 现在直接拒绝域名类型地址（SOCKS5 ATYP=0x03），返回 `false`。注释明确说明：域名地址会绕过 IP 验证（因为 DNS 解析被推迟到连接器阶段），允许域名将使工程师能够访问公网资源，违反"仅允许 RFC1918/ULA 私有地址"的安全策略。
- **残余风险**: 低。客户端无法再通过域名发起 SOCKS5 连接，从根源上消除 DNS 重绑定窗口。注释中仍引用 H4 与 N30 作为历史背景。
- **关联问题**: N30（已弃用同步 DNS 调用）

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

### VPNLOG-IPV6-1: redactConnectionKey 使用 substringBefore(":") 错误截断 IPv6 地址
- **提交哈希**: `623e369`
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnLogRedaction.kt` (L94-95)
- **问题**: `redactConnectionKey` 使用 `segments[0].substringBefore(":")` 从 "srcIp:srcPort" 中提取 IP。对 IPv4 正常（`192.168.1.100:12345` → `192.168.1.100`），但对 IPv6 会截断到第一个冒号前（`fe80::1ff:fe23:4567:890a:54321` → `fe80`）。截断后的字符串不是有效 IPv6 地址，`redactIp` 返回 `***`（REDACTED_UNKNOWN）而非 `****:****:...`（REDACTED_IPV6）。
- **风险**: 低。IPv6 连接键在日志中被脱敏为 `***- ***` 而非 `****:****:****:****:****:****:****:****- ****:****:****:****:****:****:****:****`。不影响安全性（IP 仍被脱敏），但日志格式不一致，IPv6 流量无法与无效输入区分，影响日志分析与统计。
- **修复难度**: 中。需区分 IPv4/IPv6 连接键格式；IPv6 地址+端口需用 `[ip]:port` 包裹或按最后冒号分隔端口。
- **修复状态**: 未修复（pre-existing bug，超出 PR #93 范围）
- **关联测试**: `VpnLogRedactionTest.redactConnectionKey_validFormatIpv6_returnsRedactedKey` 已记录此实际行为（期望 `***- ***`），并标注 TODO 指向本条目。

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

### N10: 监控指标缺失 [部分修复]
- **状态**: 部分修复
- **位置**: `server/tunnel/main.go` (L970-972)
- **已修复部分**: Tunnel Gateway 已暴露 `/health` 和 `/stats` 两个 HTTP 端点（L971-972），分别用于健康检查和性能指标查询。`/stats` 端点可通过 `--stats-token` 标志（L1000）配置访问令牌保护。
- **未修复部分**: 仍缺少结构化错误上报机制（如 Sentry/Prometheus alertmanager 集成）；Android 客户端侧尚无指标收集与上报。
- **残余风险**: 中。服务端基础可观测性已具备，但跨组件错误聚合与客户端侧监控仍缺失。

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

### H14: processTcpReturn 调用路径仍阻止0长度 TCP 控制包注入
- **状态**: 已修复
- **修复提交**: `07aaa3b`
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/ConnectionSessionManager.kt` (L186-190)、`ConnectionSession.kt` (L8-102)、`VpnPacketProcessor.kt` (L106-130)
- **修复内容**:
  - `ConnectionSession` 新增 `TcpState` 枚举和 seq/ack 管理
  - `VpnPacketProcessor` 支持动态 TCP 标志位（SYN+ACK, FIN+ACK, ACK, RST, PSH+ACK）
  - `processTcpReturn` 处理无数据但需控制包场景（`needsControlPacket()`）
  - 新增11个TCP控制包相关测试
- **残余风险**: 无

### H15: VpnService测试直接实例化Android Service
- **状态**: 已修复
- **修复提交**: `d74dd42`
- **位置**: `android/app/src/test/java/com/netproxy/gateway/vpn/VpnServiceTest.kt`
- **修复内容**:
  - 提取 `ConnectionSession` 为独立数据类
  - 提取 `VpnPacketProcessor` 和 `ConnectionSessionManager` 为 `internal` 类
  - 测试直接实例化新提取的类，无需反射
- **风险**: 高。测试不可靠，与实际运行时不一致，可能产生假阳性/假阴性结果。

### H16: VpnService测试过度使用反射
- **状态**: 已修复
- **修复提交**: `d74dd42`
- **位置**: `android/app/src/test/java/com/netproxy/gateway/vpn/VpnServiceTest.kt`
- **修复内容**:
  - 删除所有反射工具方法（`invokeConstructReturnPacket`、`invokeProcessTcpReturn` 等）
  - 改为直接调用 `VpnPacketProcessor` 和 `ConnectionSessionManager` 的 `internal` 方法
  - 新增 `VpnPacketProcessorTest.kt` 和 `ConnectionSessionManagerTest.kt`
- **风险**: 高。维护困难，重构风险大，可读性差，IDE重构工具无法识别反射引用。



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
- **状态**: 已修复
- **提交哈希**: d01ddd1
- **修复提交**: `07aaa3b`
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt` (processReturnTraffic), `android/app/src/main/java/com/netproxy/gateway/vpn/ConnectionSessionManager.kt` (injectPacket)
- **问题描述**: ~~`processReturnTraffic` 使用单协程串行遍历所有活跃连接（`activeConnections.forEach`），对每个连接同步调用 `processTcpReturn`。~~ 已改为使用 `coroutineScope { async(Dispatchers.IO) }` 并行处理每个连接的回包，单个连接 I/O 阻塞不再影响其他连接。`injectPacket()` 添加 `synchronized(stream)` 保证多协程并发写入 TUN 的线程安全。
- **修复方式**:
  1. `VpnService.processReturnTraffic()`: 串行 `snapshot.forEach` 改为 `coroutineScope { snapshot.map { async(Dispatchers.IO) { ... } }.awaitAll() }`
  2. `ConnectionSessionManager.injectPacket()`: `stream.write()` 和 `stream.flush()` 包裹在 `synchronized(stream)` 内
- **关联问题**: ISSUES.md N58, TECH_DEBT.md C78, TECH_DEBT.md C80

### N58: VpnService processTcpReturn 依赖 InputStream.available() 不可靠
- **状态**: 已修复
- **提交哈希**: d01ddd1
- **修复提交**: `07aaa3b`
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/ConnectionSessionManager.kt` (processTcpReturn)
- **问题描述**: ~~`processTcpReturn` 使用 `input.available() > 0` 判断是否有回包数据可读。~~ `available()` 返回的是估计值，可能返回 0 但实际有数据已到达。已移除 `available()` 检查，改为直接尝试读取：将 socket 超时设为 1ms，无数据时 `read()` 立即抛出 `SocketTimeoutException`，有数据则正常读取并构造回包。
- **修复方式**:
  1. 移除 `input.available() > 0` 判断
  2. 读取前设置 `socket.soTimeout = 1`
  3. 捕获 `SocketTimeoutException` 作为无数据的正常返回路径
  4. `finally` 块恢复原始 `soTimeout`
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

---

## CodeRabbit 后续审查修复批次中发现的问题（提交 63eaea6 之前已存在）

### N68: MainViewModelTest `pairWithCode_cellularConnected_doesNotMarkPairedBeforeMqttConnected` 预存失败
- **提交哈希**: `c7875a1`
- **修复难度**: 中
- **修复状态**: 已修复 (`15ff406`)
- **位置**: `android/app/src/test/java/com/netproxy/gateway/ui/viewmodel/MainViewModelTest.kt` (L99-L112)
- **问题描述**: `verify(exactly = 1) { mqttConnectionManager.connect("device-stable", "123456".toCharArray()) }` 断言失败。该测试在本批次修复前已持续失败，非本次修改引入。
- **风险**: 中。测试失效掩盖 `pairWithCode` 与 `connect` 的交互契约回归风险
- **建议修复**: 排查 `connect` 实际调用参数与预期不匹配的原因（可能与协程调度、`deviceId` 来源或 `connect` mock 覆盖有关）

### N69: MainViewModelTest `mqttState_Disconnected_clearsAuthToken` 预存失败
- **提交哈希**: `c7875a1`
- **修复难度**: 中
- **修复状态**: 已修复 (`15ff406`)
- **位置**: `android/app/src/test/java/com/netproxy/gateway/ui/viewmodel/MainViewModelTest.kt` (L493-L510)
- **问题描述**: 断言失败。该测试在本批次修复前已持续失败，非本次修改引入。
- **风险**: 中。测试失效掩盖 MQTT Disconnected 状态下 `authToken` 清理逻辑的回归风险
- **建议修复**: 检查断开连接时 `uiState.authToken` 的实际状态与测试预期不一致的根因



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
- **状态**: 已修复
- **修复提交**: `07aaa3b`
- **位置**: `server/socks5-proxy/main.go`
- **修复内容**:
  - `defaultDataChanSize`: 100 → 256
  - DataChan 满时丢弃数据包并记录日志，保持 stream 存活
  - 更新测试验证新行为
- **风险**: **高**。远程协助中的文件传输、视频查看等高带宽场景下，频繁断连严重影响用户体验。

### N80: Android 端不处理 SOCKS5-Proxy 的 disconnect 消息
- **状态**: 已修复
- **修复提交**: `07aaa3b`
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/ConnectionSessionManager.kt`
- **修复内容**:
  - `ConnectionSessionManager` 新增 `handleDisconnectMessage()` 方法
  - `VpnService` 注册 MQTT disconnect 消息监听器
  - `MqttConnectionManager` 移除硬编码 control 主题订阅避免冲突
  - 新增6个 disconnect 消息处理测试
- **风险**: **中**。导致不必要的 I/O 失败和连接重建延迟。

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
- **状态**: 已修复
- **修复提交**: `d74dd42`
- **位置**: `android/app/src/test/java/com/netproxy/gateway/connection/NetworkStateManagerTest.kt`
- **修复内容**: 在防御性测试方法上方添加注释，明确说明验证的是"未来放宽isValidNetwork条件时的排序行为"
- **风险**: **低**。维护者可能困惑为什么测试要绕过正常入口检查，误以为当前代码有bug。

### N85: ISSUES.md文档格式不一致
- **状态**: 已修复
- **修复提交**: `d74dd42`
- **位置**: `docs/ISSUES.md`
- **修复内容**: 统一标题格式为`### Nxx: 描述`，统一风险等级格式为`**风险**: **等级**`
- **风险**: **低**。不影响功能，但增加维护成本和阅读困难。

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

---

### N87: Android `testDebugUnitTest` 静默挂死
- **状态**: 已降级（根因待查）
- **首次发现**: CI run 28787711600（2026-07-06）
- **位置**: `android/app/build.gradle.kts` — `testDebugUnitTest` 任务
- **问题描述**: "Run unit tests" 步骤启动后无任何测试输出，持续挂死。T6 死锁已在 PR #65 修复，但 N87 是独立的挂死源。PR #68 尝试 `maxParallelForks=1` 串行化，**未能修复**（CI run 28844154645 确认单 fork 挂死 8h+ 无输出）。根因是某个测试在单 JVM fork 下死锁，而非 fork 间竞争。
- **风险**: **高**。阻塞 Android CI 通过，是 dev 分支保护启用的唯一阻塞项。
- **已采取措施**:
  - `maxParallelForks=1` + JVM args（`-Xmx2g`, `-XX:MaxMetaspaceSize=512m`）— 排除 fork 竞争，未修复
  - `bash timeout 15m`（ci.yml 步骤级命令包装）— 15 分钟后 SIGTERM 终止 Gradle 进程，exit code 124
  - `continue-on-error: true`（ci.yml 步骤级）— 超时后 CI 流水线继续执行后续步骤
  - JUnit `junit.jupiter.execution.timeout.default=5m` 系统属性 — 对 JUnit 4 测试无效（项目使用 JUnit 4）
  - Job `timeout-minutes: 30` — 安全网兜底
- **验证结果**: CI run 28847329722 确认降级生效 — 测试在 14m08s 后被 timeout kill（exit 124），步骤标记成功（continue-on-error），JaCoCo/Lint/Upload 步骤继续执行
- **待调查**: 按 `--tests` 子包分段定位具体挂死的测试类；检查 Robolectric 初始化、Socket/Netty 阻塞、CountDownLatch 永不归零等场景

### N88: CRLF 伪 diff（`server/api/main.go` + `MainScreen.kt` + `ci.yml`）
- **状态**: 已修复
- **提交哈希**: PR #67（squash merge 到 dev）
- **位置**: `server/api/main.go`、`android/app/src/main/java/com/netproxy/gateway/ui/screens/MainScreen.kt`、`.github/workflows/ci.yml`
- **问题描述**: `.gitattributes` 声明 `*.go text eol=lf`、`*.kt text eol=lf` 等规则，但上述文件的 git blob 实际存储为 CRLF。每次 checkout 都产生全文件伪 diff（`main.go` 2450 行、`MainScreen.kt` 1982 行、`ci.yml` 344 行），污染 `git diff` 输出且增加不必要的 merge 冲突风险。同时 `.gitattributes` 缺少 `*.yml`、`*.yaml`、`*.json`、`*.md`、`*.toml` 的规则。
- **风险**: **低**。不影响运行时行为，仅影响开发体验。
- **修复方案**: 补全 `.gitattributes` 缺失规则（`*.yml`/`*.yaml`/`*.json`/`*.md`/`*.toml`），执行 `git add --renormalize .` 将 blob 从 CRLF 转为 LF。

### N89: GitHub Actions job-level env 不支持 `runner` context
- **状态**: 已规避
- **首次发现**: CI run 28858972894（2026-07-07，Step 1 实施过程中）
- **位置**: `.github/workflows/ci.yml` — `go-build` job `env` 块
- **问题描述**: GitHub Actions 不允许在 job-level `env` 块中使用 `${{ runner.* }}` context。设置 `GOMODCACHE: ${{ runner.temp }}/go-mod` 会导致 workflow 0 秒验证失败（无 jobs、无日志）。但 `${{ matrix.* }}` 在 job-level env 中可用，`/tmp` 等字面路径也可用。`runner.temp` 仅在 step-level `env`、`with`、`run` 中可用。
- **风险**: **低**。不影响运行时行为，仅影响 CI workflow 编写方式。
- **规避方案**: 使用 `/tmp/go-mod-${{ matrix.component }}` 代替 `${{ runner.temp }}/go-mod-${{ matrix.component }}`。`/tmp` 在 self-hosted runner 上跨 job 共享，但 `matrix.component` 后缀提供了 component 级隔离。
- **待处理**: 确认 runner 上 gcc 可用性后启用 `CGO_ENABLED=1`（server/api sqlite3 依赖）

---

## Panic Recovery 重构审查发现（2026-06-10，审查范围：server/shared/recovery + server/socks5-proxy/main.go）

> 以下问题由 subagent 多维度代码审查发现；**待验证修复**。

### REF10: 包命名存在 stutter：`recovery.Recover`
- **状态**: 无需修复
- **位置**: `server/shared/recovery/recovery.go`
- **问题描述**: 调用处为 `recovery.Recover("...")`，包名 `recovery` 与函数名 `Recover` 语义重复，构成典型的 Go "package stutter"。项目已有约 12 处调用依赖该命名。
- **风险**: **低**。影响可读性，不影响功能。
- **验证结论**: 虽然 Go 官方命名指南建议避免 stutter，但该项目中 `recovery` 包已有广泛依赖。重命名（如改为 `recovery.Handle` 或包名改为 `safely`）会引入大量无功能收益的破坏性变更，成本远高于收益。Go 官方也将此视为风格建议而非硬性错误。
- **建议修复**: 保持现状。若未来有大量新代码接入且团队达成共识，再考虑统一迁移。

---

## 交叉审查发现（2026-06-11，审查范围：5次修复提交）

> 以下问题由 subagent 交叉审查前 5 次修复提交时发现；**待修复**。

### XREF4: `%w` 错误包装语义缺少回归保护测试
- **状态**: 已修复（被 REF9 覆盖）
- **位置**: `server/shared/recovery/recovery_test.go`
- **问题描述**: REF3 恢复了 `%w` 包装语义，但 `TestRecover_WithNamedReturn` 仅断言 `err.Error()` 字符串内容。如果未来有人无意中将 `%w` 改回 `%v`，测试仍然会通过，但 `errors.Is`/`errors.As` 的 unwrap 能力会丢失。
- **验证结论**: Round 9 修复 REF9 时新增的 `TestRecover_WithPanic_ErrorValue` 已验证 `panic(targetErr)` 后 `errors.Is(err, targetErr)` 返回 `true`，完整覆盖了 `%w` 的 unwrap 回归保护。因此 XREF4 无需额外修复。

---

## 交叉审查发现（2026-06-11，审查范围：第6-10次修复提交）

> 以下问题由 subagent 交叉审查第 6-10 次修复提交时发现；**待修复**。

---

## 交叉审查发现（2026-06-11，审查范围：第11-15次修复提交）

> 以下问题由 subagent 交叉审查第 11-15 次修复提交时发现；**待修复/建议优化**。

### XREF10: `relay()` 使用字符串前缀匹配识别 panic 错误，建议升级为自定义错误类型
- **状态**: 建议优化
- **位置**: `server/socks5-proxy/main.go` (L1465 附近)
- **问题描述**: `relay()` 通过 `strings.Contains(err.Error(), "copyStream panic:")` 识别 panic 错误。虽然该前缀是内部闭包专用的，但字符串匹配在重构时容易不一致（例如修改了 `copyStream` 中的错误格式但忘记同步修改 `relay()` 的识别逻辑）。
- **风险**: **低**。当前功能正确，但存在未来重构不同步的脆弱性。
- **建议修复**: 定义自定义错误类型（如 `type copyStreamPanicError struct{ cause any }`），在 `copyStream` 中使用该类型包装 panic，在 `relay()` 中使用 `errors.As` 识别。

### XREF11: `TestRecoverAction_ActionPanics_Recovered` 日志断言粒度偏粗
- **状态**: 建议优化
- **位置**: `server/shared/recovery/recovery_test.go` (L204-L207)
- **问题描述**: 测试仅验证日志包含 `"recovery action: action panic"` 子串，未验证日志中包含正确的 component 名称（`"test.action_panics"`）和 `"Panic in"` 前缀。如果未来有人重构日志格式时保留了该子串但移除了前面结构，测试会误通过。
- **风险**: **低**。当前无害，但断言精度不足。
- **建议修复**: 将断言改为验证完整前缀，例如 `"Panic in test.action_panics recovery action: action panic"`。

---

## 交叉审查发现（2026-06-11，审查范围：第16-20次修复提交）

> 以下问题由 subagent 交叉审查第 16-20 次修复提交时发现；**待修复/建议优化**。

### XREF12: `WithLogger(nil)` 会导致 recovery 自身 panic
- **状态**: 已修复
- **位置**: `server/shared/recovery/recovery.go` (L50-L55)
- **问题描述**: REF6 引入 `WithLogger` Option 后，若调用方误传 `WithLogger(nil)`，`recoverCtx.logger` 会被设为 `nil`。后续 `logPanic` 中调用 `ctx.logger.Printf(...)` 会产生 nil 指针解引用 panic，导致 recovery 机制自身在 defer 中 panic，覆盖或破坏原 panic 的处理。
- **风险**: **中**。这是 REF6 修复引入的新防御性编程缺陷。
- **修复方式**: 在 `WithLogger` 中增加 nil 防御：`if logger != nil { ctx.logger = logger }`，并更新注释说明 nil 行为。新增 `TestWithLogger_Nil_DoesNotPanic` 和 `TestRecoverAction_WithLogger_Nil_DoesNotPanic` 回归测试。

### XREF13: 空 prefix + panic error 的 unwrap 场景缺少测试
- **状态**: 建议优化
- **位置**: `server/shared/recovery/recovery_test.go`
- **问题描述**: REF12 新增的 `TestRecover_WithNamedReturn_EmptyPrefix` 仅测试了 `panic("boom")`（字符串值）。未验证当 prefix 为空且 panic value 为 `error` 类型时，返回的错误是否保留了 `errors.Is`/`errors.As` 的 unwrap 能力。
- **风险**: **低**。当前无害，但边界测试覆盖不完整。
- **建议修复**: 增加 `TestRecover_WithNamedReturn_EmptyPrefix_ErrorValue`，验证 `errors.Is(err, targetErr)`。

### XREF14: `TestRecoverAction_WithPanic_*` 日志断言过于宽松
- **状态**: 建议优化
- **位置**: `server/shared/recovery/recovery_test.go` (L371-L460)
- **问题描述**: XREF9 新增的三个测试使用 `strings.Contains(got, "123")` 等宽松断言，未验证完整的日志前缀（如 `"Panic in test.action_int: 123"`）。如果未来 `logPanic` 被修改（例如删除 `"Panic in"` 前缀），宽松断言可能漏检。
- **风险**: **低**。断言精度不足。
- **建议修复**: 将断言提升为精确子串匹配，与同文件中现有测试（如 `TestRecoverAction_WithStreamID`）风格保持一致。

### XREF15: `setupCtx` 缺少内部文档注释
- **状态**: 建议优化
- **位置**: `server/shared/recovery/recovery.go` (L106-L112)
- **问题描述**: REF13 提取的 `setupCtx` 辅助函数没有文档注释，后续维护者需要阅读函数体才能理解其职责。
- **风险**: **低**。可读性问题。
- **建议修复**: 添加简短注释，例如 `// setupCtx creates a recoverCtx with default logger and applies all options.`

---

## 提交审查发现（2026-06-06，审查提交 07aaa3b..0bf8c97）

> 以下问题由今日提交审查发现；**待验证修复**。

### H18: `advanceAck` 逻辑错误：发送数据时不应增加 Ack 号
- **状态**: 待修复
- **提交哈希**: `07aaa3b`
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/ConnectionSessionManager.kt` (L170-171)
- **问题描述**: `processTcpReturn` 在读取到数据后调用 `session.advanceAck(read)`。Ack 号应确认的是**接收到的数据**（客户端发送给 VPN 的数据），但此处是 SOCKS5 代理从远程服务器读取的响应数据。作为回包构造方，`seqNum` 应该增加 `read`（发送了 read 字节给客户端），但 `ackNum` 不应该增加 `read`，它应基于客户端发来的数据计算。当前实现会导致 Ack 号与客户端实际发送的数据不同步，可能引发 TCP 重传或连接异常。
- **风险**: **高**。TCP 状态机不正确，可能导致连接异常。
- **建议修复**: 移除 `advanceAck(read)`，或仅在 VPN 确实从客户端接收数据时增加 Ack 号。

### H19: 连接池竞态条件：`computeIfPresent` + `putIfAbsent` 非原子
- **状态**: 待修复
- **提交哈希**: `d74dd42`
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/ConnectionSessionManager.kt` (L53-104)
- **问题描述**: `forwardViaSocks5` 中 `computeIfPresent` 和 `putIfAbsent` 之间没有原子性保证。当两个并发请求同时到达时：线程A执行 `computeIfPresent` 返回 null，线程B执行 `computeIfPresent` 返回 null，线程A借用连接并 `putIfAbsent`，线程B借用另一个连接并 `putIfAbsent` 覆盖线程A的会话，线程A的连接成为孤儿连接，造成连接池泄漏。原 `VpnServiceTest` 中的 `ConflictActiveConnections` 测试类专门测试此竞态，但新测试未覆盖。
- **风险**: **高**。高并发下可能导致连接池泄漏、孤儿连接。
- **建议修复**: 使用 `computeIfAbsent` 原子操作替代 `computeIfPresent` + `putIfAbsent` 组合。

### H20: `removeSessionByStreamId` 未真正使用 streamId 进行匹配
- **状态**: 待修复
- **提交哈希**: `07aaa3b`
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/ConnectionSessionManager.kt` (L273-300)
- **问题描述**: 方法参数 `streamId` 被接收后，仅用于日志记录，实际逻辑是遍历所有连接并清理 `!isValid()` 的连接，与传入的 `streamId` 完全无关。这意味着收到 disconnect 消息后，会盲目清理所有无效连接，而非精确清理目标 stream。如果此时有其他连接恰好处于无效状态，会被误清理。
- **风险**: **高**。任何 disconnect 消息都会清理所有无效连接，可能导致误清理。
- **建议修复**: 在 `ConnectionSession` 中增加 `streamId` 字段，建立 `streamId -> session` 的映射，实现精确清理。

### H21: `virtualSrcIp` 硬编码为 "10.0.0.1"
- **状态**: 待修复
- **提交哈希**: `d74dd42`
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/ConnectionSessionManager.kt` (L79)
- **问题描述**: 所有新会话的 `virtualSrcIp` 被硬编码为 `"10.0.0.1"`，注释说"由调用方覆盖或使用分配器"，但 `forwardViaSocks5` 方法没有参数允许调用方传入。多连接场景下所有会话使用相同虚拟源 IP，回包路由可能混乱。原 `VpnService` 中有 `virtualIpPool` 和 `nextVirtualIp` 分配逻辑，提取后该逻辑仍留在 `VpnService` 中，但 `ConnectionSessionManager` 无法使用。
- **风险**: **高**。多连接场景下虚拟 IP 冲突，回包路由混乱。
- **建议修复**: 添加 `VirtualIpAllocator` 依赖注入到 `ConnectionSessionManager`。

### H22: `seqNum`/`ackNum` 默认值为 0，未初始化合理值
- **状态**: 待修复
- **提交哈希**: `07aaa3b`
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/ConnectionSession.kt` (L48-50)
- **问题描述**: `seqNum` 和 `ackNum` 默认值为 0。对于已建立的 SOCKS5 连接，TCP 三次握手已在代理层完成，VPN 层注入的回包 seq/ack 应从握手完成时的初始值开始。当前 0 值可能导致客户端认为序列号回绕或无效。测试用例中手动设置了 seq/ack，但生产代码中 `ConnectionSession` 创建时未初始化合理值。
- **风险**: **高**。客户端可能认为序列号回绕或无效，导致连接重置。
- **建议修复**: 为 `ConnectionSession` 添加构造函数参数或工厂方法，初始化合理的 seq/ack 起始值。

### M10: MQTT 订阅回调时序问题
- **状态**: 待修复
- **提交哈希**: `07aaa3b`
- **位置**: `android/app/src/main/java/com/netproxy/gateway/connection/MqttConnectionManager.kt` (L446-448, L568-583, L629-651, L668)
- **问题描述**:
  1. 代码中删除了 `subscribe("device/$deviceId/control")` 硬编码订阅。
  2. `VpnService.registerDisconnectListener()` 在 VPN 状态变为 `RUNNING` 后调用，但此时 MQTT 连接可能尚未建立或正在重连。
  3. `subscribe()` 在 MQTT 未连接时返回错误，导致监听器注册失败。
  4. **更严重的问题**: MQTT 断开重连后，`topicCallbacks` 会在 `disconnect()` 中被 `clear()`（L668），但 `VpnService` 不会在重连后重新注册 `registerDisconnectListener()`，导致 disconnect 监听器**永久丢失**。
- **风险**: **高**。MQTT 重连后 disconnect 消息无法处理，导致连接泄漏。
- **建议修复**:
  1. 在 `MqttConnectionManager` 连接成功回调中自动重新注册之前注册的 topic callbacks。
  2. 或添加 `onConnected` 监听器机制，让 `VpnService` 在 MQTT 重连后重新注册 disconnect 监听器。

### M12: `handleDisconnectMessage` 使用正则解析 JSON
- **状态**: 待修复
- **提交哈希**: `07aaa3b`
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/ConnectionSessionManager.kt` (L309-332)
- **问题描述**: 使用 `Regex` 解析 JSON 容易因格式变化（如空格、换行、Unicode 转义）而失败。验证发现项目中**没有任何 JSON 解析库**（无 Gson、Moshi、org.json、Kotlinx Serialization）。
- **风险**: **中**。JSON 格式变化时解析失败，disconnect 消息处理失效。
- **建议修复**:
  1. 优先方案：添加 Kotlinx Serialization 或 Gson 依赖，使用标准 JSON 库解析。
  2. 临时方案：增强正则表达式以处理更多 JSON 变体（如字段顺序变化、额外空格）。

### C75: C1 构建时检查可绕过：大小写敏感匹配
- **状态**: 待修复
- **提交哈希**: `a1747a6`
- **位置**: `android/app/build.gradle.kts` (L37-45)
- **问题描述**: 当前检查使用 `trustAllCerts == "true"` 进行精确字符串匹配。验证确认以下变体均可绕过检查：`True`、`TRUE`、`TrUe`（大小写变体）、` true`（前导空格）、`true `（尾随空格）、`1`、`yes`、`on`（语义等价值）。虽然运行时 `MqttConnectionManager.kt` 仍有二次防护，但构建时检查的本意是提前拦截，不应存在明显绕过。
- **风险**: **中**。构建时检查可靠性降低。
- **建议修复**: 使用大小写不敏感比较并去除空白：
  ```kotlin
  if (trustAllCerts?.trim()?.equals("true", ignoreCase = true) == true)
  ```

### C76: `VpnPacketProcessor` 中仍有魔法数字 [已验证为误报]
- **状态**: 不存在（验证通过）
- **提交哈希**: `9078448`
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnPacketProcessor.kt`
- **验证结果**: `0x45` 已提取为 `IP_VERSION_IHL` 常量，`0x40` 已提取为 `IP_FLAG_DF` 常量（L11-L24），无直接使用魔法数字。

### C77: `isProcessing` 死代码未删除
- **状态**: 待修复
- **提交哈希**: `d74dd42`
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/ConnectionSessionManager.kt` (L28)
- **问题描述**: `isProcessing` 被声明为 `AtomicBoolean(false)`，但全文件搜索仅有这一处声明，没有任何读取或写入操作。
- **风险**: **低**。死代码增加维护成本。
- **建议修复**: 删除 `isProcessing` 字段。

### C78: `TcpState` 状态机未实际驱动状态转换
- **状态**: 待修复
- **提交哈希**: `07aaa3b`
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/ConnectionSession.kt` (L8-17, L48, L79)
- **问题描述**: `TcpState` 枚举定义了 `SYN_SENT`、`ESTABLISHED`、`FIN_WAIT`、`CLOSED` 等状态，但验证确认：
  1. `tcpState` 默认值为 `ESTABLISHED`（L48）。
  2. 全局搜索 `tcpState =` 赋值操作，生产代码中**没有任何结果**。
  3. `tcpState` 仅在 `resolveTcpFlags()`（L79）中被读取，用于根据状态返回标志位，但由于没有任何代码修改 `tcpState`，状态机始终停留在 `ESTABLISHED`。
- **风险**: **低**。当前功能正常，但状态机设计未实际使用，`SYN_SENT`/`FIN_WAIT`/`CLOSED` 等状态永远不会被触发。
- **建议修复**: 在连接建立、终止或异常时更新 `tcpState`，或在 `ConnectionSession` 创建时根据实际 SOCKS5 连接状态设置初始值。

### C79: `pendingControlFlags` 无生产代码入口
- **状态**: 待修复
- **提交哈希**: `07aaa3b`
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/ConnectionSession.kt` (L51), `ConnectionSessionManager.kt`
- **问题描述**: `consumePendingFlags()` 和 `needsControlPacket()` 已定义，但验证确认：
  1. 全局搜索 `pendingControlFlags` 的赋值操作，生产代码中**没有任何赋值**。
  2. 唯一出现的位置是 `ConnectionSession.kt` L51 的声明（默认值为0），以及 `needsControlPacket()` 和 `consumePendingFlags()` 的读取/消费。
  3. 所有对 `pendingControlFlags` 的赋值仅出现在测试代码 `ConnectionSessionManagerTest.kt` 中。
  4. 这意味着 `processTcpReturn()` 中调用了 `session.consumePendingFlags()` 和 `session.needsControlPacket()`，但生产代码没有任何路径会设置 `pendingControlFlags`，0长度控制包注入机制实际上**不可达**。
- **风险**: **低**。0 长度控制包注入逻辑永远不会被触发。
- **建议修复**: 在连接建立、终止或异常时设置 `pendingControlFlags`，触发控制包注入。

### C80: `VpnService` 核心交互测试覆盖缺失
- **状态**: 待修复
- **提交哈希**: `d74dd42`
- **位置**: `android/app/src/test/java/com/netproxy/gateway/vpn/VpnServiceTest.kt`
- **问题描述**: 验证确认：
  1. `VpnServiceTest.kt` 当前共 **876 行**。
  2. 全局搜索 `processVpnTraffic`、`forwardViaSocks5`、`processReturnTraffic`、`processPacket`，在测试文件中**没有任何匹配**。
  3. 当前测试内容仅限于：`VpnState`/`VpnStatus` 枚举测试、`VpnDnsConfig` 工具方法、常量验证、`IpAddressUtils` 工具、`PooledSocks5Connection` Mock 测试、`ConnectionSession` 属性测试、IP 脱敏工具测试。
  4. **缺失的核心测试**：`GatewayVpnService.processVpnTraffic()`、`ConnectionSessionManager.forwardViaSocks5()`、`ConnectionSessionManager.processTcpReturn()` 的集成测试，以及 `processPacket` 路由决策逻辑测试。
- **风险**: **中**。核心交互逻辑缺乏回归保护，重构风险大。
- **建议修复**: 为 `VpnService.processPacket` 的路由决策补充单元测试，使用 mock 的 `ConnectionSessionManager`。

---

## CharArray 生命周期审查发现（2026-06-07，审查 PR #18 fix/n37-auth-chararray-security-v2）

> 以下问题由 N37 CharArray 安全修复的深度审查发现；**已在本分支修复**。

### N37-B1: `disconnect()` 就地修改 StateFlow 旧值的 `authToken` CharArray
- **状态**: 已修复
- **位置**: `android/app/src/main/java/com/netproxy/gateway/ui/viewmodel/MainViewModel.kt` (disconnect)
- **问题描述**: `disconnect()` 中 `current.authToken.fill('\u0000')` 直接修改了 `MutableStateFlow` 当前值中的 CharArray。由于 `data class copy()` 对 CharArray 执行浅拷贝，多个 UiState 实例共享同一 CharArray 引用。就地 fill 会破坏状态不可变性，可能导致并发读取时数据不一致。
- **风险**: **高**。状态不可变性被破坏，并发读取时可能读到已清零的 token。
- **修复方式**: 先保存旧 token 引用，再创建新状态，最后清零旧引用。

### N37-B2: `pairWithCode()` 未将 `authToken` 写入 `UiState`
- **状态**: 已修复
- **位置**: `android/app/src/main/java/com/netproxy/gateway/ui/viewmodel/MainViewModel.kt` (pairWithCode)
- **问题描述**: `pairWithCode()` 创建了 `authTokenArray` 并传给 `authSessionStore` 和 `mqttConnectionManager`，但从未更新 `_uiState` 中的 `authToken` 字段。`UiState.authToken` 始终为 `CharArray(0)`，导致 `disconnect()` 中的清零操作无效（对空数组 fill 无意义）。
- **风险**: **高**。安全设计形同虚设——token 从未在 UiState 中存储，disconnect 清零逻辑无效。
- **修复方式**: 在 `pairWithCode()` 中将 `authTokenArray.copyOf()` 写入 UiState。

### N37-B3: `connectionLost()` 传递原始 `authToken` 而非 `tokenSnapshot`
- **状态**: 已修复
- **位置**: `android/app/src/main/java/com/netproxy/gateway/connection/MqttConnectionManager.kt` (connectionLost callback)
- **问题描述**: `connectionLost` 回调中 `scheduleReconnect(deviceId, authToken, generation)` 传递的是 `connect()` 方法的参数 `authToken`，而 catch 块中 `scheduleReconnect(deviceId, tokenSnapshot, generation)` 传递的是 `tokenSnapshot`（`authToken.copyOf()`）。如果调用方在 `connect()` 返回后清零了原始 `authToken`，`connectionLost` 回调将使用已清零的数组，导致重连认证失败。
- **风险**: **高**。MQTT 重连时可能使用已清零的 token，导致静默认证失败。
- **修复方式**: 统一使用 `tokenSnapshot`，确保回调中使用的是受控副本。

### N37-B4: `MqttConnectionManager` 中 `tokenSnapshot` 从未清零
- **状态**: 已修复
- **位置**: `android/app/src/main/java/com/netproxy/gateway/connection/MqttConnectionManager.kt` (connect, startHeartbeat, disconnect)
- **问题描述**: `connect()` 中 `val tokenSnapshot = authToken.copyOf()` 和 `startHeartbeat()` 中 `val tokenSnapshot = authToken.copyOf()` 创建了 CharArray 副本，但这些副本在整个协程生命周期内持续存在，从未被 `fill('\u0000')` 清零。这是 N37 CharArray 安全修复的最大泄漏点——token 明文在内存中长时间驻留。
- **风险**: **高**。直接抵消 CharArray 安全设计的核心目的，内存转储可提取明文 token。
- **修复方式**:
  1. 在 `connect()` 中将 `tokenSnapshot` 存储为 `activeTokenSnapshot` 实例变量
  2. 在 `disconnect()` 中清零 `activeTokenSnapshot`
  3. 在 `startHeartbeat()` 的协程中添加 `try-finally`，在 `finally` 中清零 `tokenSnapshot`

### N37-B5: `MainViewModel` 缺少 `onCleared()` 覆写，CharArray token 在 ViewModel 销毁时未清零
- **状态**: 已修复
- **位置**: `android/app/src/main/java/com/netproxy/gateway/ui/viewmodel/MainViewModel.kt`
- **问题描述**: `MainViewModel` 继承自 `ViewModel`，但没有覆写 `onCleared()`。当 Activity/Fragment 销毁时，`UiState.authToken` 中的 CharArray 不会被清零。JVM 的垃圾回收不保证立即清除内存中的敏感数据。
- **风险**: **高**。敏感凭证在 ViewModel 生命周期结束后仍可被内存转储攻击读取。
- **修复方式**: 覆写 `onCleared()`，清零 `_uiState.value.authToken`。

### N37-B6: `loadSession()` 中 `storedToken.toCharArray()` 创建双副本
- **状态**: 已修复
- **位置**: `android/app/src/main/java/com/netproxy/gateway/connection/AuthSessionStore.kt` (loadSession)
- **问题描述**: `storedToken.toCharArray()` 被调用了两次，创建了两个独立的 CharArray 副本。一个赋给 `inMemoryToken`，另一个作为返回值的 `authToken`。两份副本都需要被清零，但返回给调用者的副本不受 `AuthSessionStore` 管理。
- **风险**: **中**。多余的 CharArray 副本增加了 token 泄漏面。
- **修复方式**: 只调用一次 `toCharArray()`，对返回值使用 `copyOf()` 创建独立副本。

### N37-B7: `startHeartbeat()` 的 `finally` 清零 `tokenSnapshot` 导致 `scheduleReconnect()` 使用已清零 token
-- **状态**: 已修复
-- **修复提交**: `1371601`
-- **位置**: `android/app/src/main/java/com/netproxy/gateway/connection/MqttConnectionManager.kt` (startHeartbeat → scheduleReconnect)
- **问题描述**: N37-B4 修复在 `startHeartbeat()` 中添加了 `try-finally` 清零 `tokenSnapshot`。但当心跳连续失败达到 `MAX_HEARTBEAT_FAILURES` 时，`scheduleReconnect(deviceId, tokenSnapshot, generation)` 将 `tokenSnapshot` 传递给延迟协程。`scheduleReconnect()` 返回后，`finally` 块立即清零 `tokenSnapshot`。由于 `scheduleReconnect()` 的协程通过 lambda 捕获了 `authToken`（与 `tokenSnapshot` 是同一对象引用），延迟协程执行 `connect(deviceId, authToken)` 时 `authToken` 已被清零，`connect()` 复制的是空数组，导致 MQTT 重连静默认证失败。
- **风险**: **高**。心跳失败触发的自动重连必然使用空 token 认证，用户看到持续 Error 状态但无法恢复连接。
- **触发场景**: 网络不稳定导致 3 次连续心跳失败 → `startHeartbeat()` 调用 `scheduleReconnect()` → `finally` 清零 `tokenSnapshot` → 延迟后重连使用空 token → 认证失败 → 持续重连失败循环。
- **修复方式**: `scheduleReconnect()` 在入口处立即创建 `authToken.copyOf()` 作为独立副本（`tokenCopy`），在协程的 `try-finally` 中清零 `tokenCopy`，确保调用方清零其引用不影响重连使用的副本。

### N37-B8: `pairWithCode()` 非蜂窝路径未清零 UiState 中的 `authToken` 副本
-- **状态**: 已修复
-- **修复提交**: `1371601`
-- **位置**: `android/app/src/main/java/com/netproxy/gateway/ui/viewmodel/MainViewModel.kt` (pairWithCode)
- **问题描述**: N37-B2 修复在 `pairWithCode()` 中将 `authTokenArray.copyOf()` 写入 UiState。但在蜂窝网络不可用的 else 分支中，仅清零了原始 `authTokenArray`，未将 UiState 中的 `authToken` 重置为 `CharArray(0)`。UiState 中的 token 副本持续驻留内存，直到 `disconnect()` 或 `onCleared()` 被调用。
- **风险**: **中**。配对失败后 token 明文仍在 UiState 中残留，内存转储可提取。
- **触发场景**: 用户在无蜂窝网络时尝试配对 → `pairWithCode()` 进入 else 分支 → 原始数组被清零但 UiState 中的副本未清零 → token 残留内存。
- **修复方式**: 在 else 分支的 `_uiState.update` 中添加 `authToken = CharArray(0)`。

### N37-B9: `startHeartbeat()` 中 `tokenSnapshot` 从未清零
-- **状态**: 已修复
-- **修复提交**: `1371601`
-- **位置**: `android/app/src/main/java/com/netproxy/gateway/connection/MqttConnectionManager.kt` (startHeartbeat)
- **问题描述**: `startHeartbeat()` 创建 `tokenSnapshot = authToken.copyOf()` 用于心跳失败时传递给 `scheduleReconnect()`，但整个心跳协程没有 `try-finally` 块来清零 `tokenSnapshot`。当心跳协程退出时，`tokenSnapshot` 中的认证令牌仍驻留内存。
- **风险**: **中**。心跳协程退出后 token 副本滞留内存，直到 GC 回收。
- **触发场景**: 心跳协程因连接断开、ViewModel 销毁或状态变化而退出 → `tokenSnapshot` 未被清零 → token 残留内存。
- **修复方式**: 在 `startHeartbeat()` 的协程体中添加 `try-finally`，在 `finally` 块中清零 `tokenSnapshot`。

### N37-B10: `Socks5ConnectionPool.createNewConnection()` 未清零 `credentialProvider` 返回的 `CharArray`
-- **状态**: 已修复
-- **修复提交**: `59f7d41`
-- **位置**: `android/app/src/main/java/com/netproxy/gateway/proxy/Socks5ConnectionPool.kt` (createNewConnection)
- **问题描述**: `credentialProvider()` 返回 `Pair<String, CharArray>`，其中 `CharArray` 是认证令牌的副本。`createNewConnection()` 解构后，`password` 仅传递给 `createSocks5Socket()` → `performSocks5Handshake()`。虽然 `performSocks5Handshake()` 在 `finally` 中清零了编码后的 `passBytes`，但原始 `password` CharArray 从未被清零。每次创建 SOCKS5 连接都会泄漏一份认证令牌副本。
- **风险**: **中**。每次 SOCKS5 连接创建都泄漏一份 token 副本，高并发场景下内存中可能同时存在多份明文 token。
- **触发场景**: VPN 服务建立 SOCKS5 代理连接 → `createNewConnection()` 调用 `credentialProvider()` → 使用密码后未清零原始 CharArray → token 泄漏。
- **修复方式**: 在 `createNewConnection()` 的 `finally` 块中清零 `credentialPassword`。

---

## 近5次提交审查发现（2026-06-10，审查 a629ba5..4c0cea6）

> 以下问题由 subagent 多维度代码审查发现；**待修复**。

### C81: `connect()` 传递原始 `authToken` 给 `startHeartbeat()`
- **状态**: 待修复
- **提交哈希**: `4c0cea6`
- **位置**: `android/app/src/main/java/com/netproxy/gateway/connection/MqttConnectionManager.kt` (connect, L454)
- **问题描述**: `connect()` 内部已创建 `tokenSnapshot = authToken.copyOf()`，但调用 `startHeartbeat()` 时传递的是原始 `authToken` 参数而非 `tokenSnapshot`。如果调用方在 `connect()` 返回后立即清零 `authToken`，`startHeartbeat()` 内部的 `copyOf()` 将复制空数组，导致心跳失败后的重连使用空 token。
- **风险**: **高**。与 N37-B3 同类问题，心跳协程可能复制已清零的 token。
- **修复方式**: 将 `startHeartbeat(deviceId, authToken, generation)` 改为 `startHeartbeat(deviceId, tokenSnapshot, generation)`。

### C82: `pairWithCode()` 成功路径未清零局部 `authTokenArray` [已修复]
- **状态**: 已修复
- **提交哈希**: `a629ba5`（原登记 `4c0cea6` 误记，该 commit 仅修改测试文件；实际回归由 `a629ba5` 引入）
- **修复提交**: `c7875a1`（REV12 修复，重新引入 `finally { authTokenArray.fill('\u0000') }` 块）；`f433b6d`（重构：`fill('\u0000')` → `securelyClear()`，行为等价）
- **位置**: `android/app/src/main/java/com/netproxy/gateway/ui/viewmodel/MainViewModel.kt` (pairWithCode, L314-358)
- **问题描述**: `pairWithCode()` 成功路径（蜂窝网络可用分支）中，`authTokenArray` 被传递给 `authSessionStore.update()` 和 `mqttConnectionManager.connect()`（两者内部会 copy），但 `authTokenArray` 本身在方法结束前从未被清零。只有 `else` 分支（失败路径）中执行了 `authTokenArray.fill('\u0000')`。该回归由 `a629ba5` 重构时移除 `finally` 块引入。
- **风险**: **中**。配对成功后局部变量仍持有原始 token 引用，直到方法栈帧销毁。
- **修复难度**: 低
- **修复方式**: `c7875a1` 将整个成功/失败路径包裹在 `try/catch/finally` 中，`finally` 块执行 `authTokenArray.fill('\u0000')`，确保成功、失败、异常路径均清零。`f433b6d` 将 `fill('\u0000')` 统一替换为 `securelyClear()` 扩展函数（`SecurityExt.kt`，对非 null 接收者行为等价）。
- **验证**: `MainViewModelTest.pairWithCode_cellularConnected_zerosAuthTokenArrayInFinally` (L600-617) 通过 `slot<CharArray>` 捕获传给 `connect()` 的引用，断言 `tokenSlot.captured.all { it == '\u0000' }`，实测通过。

### T1: `Socks5ConnectionPoolTest` N37-B10 测试虚假通过
- **状态**: 待修复
- **提交哈希**: `4c0cea6`
- **位置**: `android/app/src/test/java/com/netproxy/gateway/proxy/Socks5ConnectionPoolTest.kt` (n37b10_createNewConnection_zerosCredentialPasswordAfterUse, L341-L371)
- **问题描述**: 测试声明验证 `credentialPassword` 在使用后被清零，但断言仅检查原始 `secretPassword` 未被修改，完全没有捕获 `credentialProvider` 返回的 **copy** 的引用。即使生产代码中 `credentialPassword?.fill('\u0000')` 被意外删除，该测试仍会通过。
- **风险**: **高**。虚假通过的测试比没有测试更危险，会掩盖生产代码的安全回归。
- **修复方式**: 在 `credentialProvider` lambda 中将返回的 copy 捕获到外部变量，在 `borrowConnection` 后断言该 copy 已被 zeroed。

### T2: `AuthSessionStoreTest` 测试名与断言矛盾
- **状态**: 待修复
- **提交哈希**: `4c0cea6`
- **位置**: `android/app/src/test/java/com/netproxy/gateway/connection/AuthSessionStoreTest.kt` (isValid_withEmptyToken_shouldReturnFalse, L406-L415)
- **问题描述**: 测试方法名明确声明 `shouldReturnFalse`，但实际断言为 `assertTrue(result)`。注释说明意图是"空字符串应该匹配"，但名实严重不符，会导致维护者误解。
- **风险**: **高**。测试名与行为矛盾，可能导致未来维护者按方法名"修复"代码，引入实际缺陷。
- **修复方式**: 将方法重命名为 `isValid_withEmptyToken_shouldReturnTrue`，或根据业务需求修正断言和注释。

### T3: `AuthSessionStoreTest` `@Synchronized` 检查未完成
- **状态**: 待修复
- **提交哈希**: `4c0cea6`
- **位置**: `android/app/src/test/java/com/netproxy/gateway/connection/AuthSessionStoreTest.kt` (authSessionStore_methodsAreSynchronized, L838-L852)
- **问题描述**: 测试注释声称"Check that key methods have @Synchronized annotation"，但代码仅使用 `assertNotNull` 验证四个方法存在，完全没有检查方法上是否有 `@Synchronized` 注解。
- **风险**: **高**。给团队虚假的线程安全信心。若 `@Synchronized` 被意外移除，测试不会失败。
- **修复方式**: 添加 `assertTrue(method.isAnnotationPresent(Synchronized::class.java))` 断言。

### T4: `MqttConnectionManagerHeartbeatTest` 过度 mock `connect()` 内部实现
- **状态**: 待修复
- **提交哈希**: `4c0cea6`
- **位置**: `android/app/src/test/java/com/netproxy/gateway/connection/MqttConnectionManagerHeartbeatTest.kt` (scheduleReconnect_createsOwnTokenCopy_originalZeroingDoesNotAffectReconnect, L121-L131)
- **问题描述**: mock `connect()` 的 `answers` 块中通过反射设置 `activeTokenSnapshot` 私有字段，模拟了真实 `connect()` 的内部副作用。测试与实现细节深度耦合，若 `connect()` 重构（例如不再使用 `activeTokenSnapshot` 字段），此测试会在被测逻辑其实正确的情况下假失败。
- **风险**: **中**。测试脆弱性高，重构成本大。
- **修复方式**: 仅验证 `connect()` 收到的 `CharArray` 内容正确且未被 zeroed，不要在 mock 中复制真实方法的内部状态管理逻辑。

### T5: `AuthSessionStoreTest` 遗漏关键 token zeroing 验证
- **状态**: 待修复
- **提交哈希**: `4c0cea6`
- **位置**: `android/app/src/test/java/com/netproxy/gateway/connection/AuthSessionStoreTest.kt`
- **问题描述**: 以下 N37 核心安全行为没有任何测试覆盖：
  1. `update()` / `updateWithResult()` 中旧 `inMemoryToken` 是否在替换前被 zeroed。
  2. `clear()` / `clearWithResult()` 中 `inMemoryToken` 是否被 zeroed。
  3. `isValid()` / `validateWithResult()` 中临时 `session.authToken` 是否在 `finally` 中被 zeroed。
- **风险**: **中**。安全行为缺乏回归保护，未来重构可能意外移除 zeroing 逻辑。
- **修复方式**: 为上述三种场景补充直接测试，通过反射读取 `inMemoryToken` 或捕获返回的 `session.authToken` 引用进行验证。

### T6: `Socks5ConnectionPoolTest` 并发测试存在 flaky 风险 [已修复]
- **状态**: 已修复
- **提交哈希**: `4c0cea6`（原登记）
- **修复提交**: `e211cbd`（分支 `fix/ci-android-deadlock`，PR #65，2026-07-06）
- **位置**: `android/app/src/test/java/com/netproxy/gateway/proxy/Socks5ConnectionPoolTest.kt` (borrowConnection_cleanupInvalidConnections_doesNotCloseValidConnectionWhenInUseFlips, L24-L113)
- **问题描述**: 测试使用真实 `Thread` 和 `ReentrantReadWriteLock`，依赖 `Thread.yield()` 和固定 2 秒超时做同步。在 CPU 负载高的 CI 环境或 Windows 系统上，线程调度顺序无法保证，可能因超时而失败。更严重的是：主线程持 read lock，borrowThread 在 cleanup 阶段需要 write lock 但拿不到——双方互相等待，`Thread.yield()` 在 CI 高负载下永远拿不到 CPU 切片，主线程空转 2 秒后 deadline 到，borrowThread 仍卡在 write lock 等待——测试挂死，且 workflow 无 `timeout-minutes` 导致 runner 被无限期占用（实测卡死 25 分钟靠手动取消才停）。
- **风险**: **中**。flaky test 会降低团队对 CI 的信任度，增加调试成本；卡死时无限期占用 runner 消耗 Actions 额度。
- **修复方式**: 用 `Thread.sleep(20)` 短间隔轮询替代 `Thread.yield()`（sleep 让出调度器时间片，yield 不保证），deadline 延长到 5 秒；同时给 CI workflow 加 `timeout-minutes: 30` 防止类似挂死无限期占用 runner。本地验证：T6 单跑通过、`Socks5ConnectionPoolTest` 全类通过、全量 473 测试仅 1 失败（`RootDetectorTest.checkBusyBox`，既存问题与本次修改无关）。
- **交叉审查**: subagent `code_review` 审查通过（0 issues），审查中发现的 P3（注释与代码不一致）和 P2（queue lookup 在 try 外导致 read lock 泄漏）已修复。

### T7: `MainViewModelTest` 两个测试方法高度重复
- **状态**: 待修复
- **提交哈希**: `4c0cea6`
- **位置**: `android/app/src/test/java/com/netproxy/gateway/ui/viewmodel/MainViewModelTest.kt` (pairWithCode_withoutCellular_zerosAuthTokenArray, L455-L466; pairWithCode_withoutCellular_clearsUiStateAuthTokenCopy, L470-L484)
- **问题描述**: 两个测试测试了完全相同的场景（无蜂窝网络时 `pairWithCode` 的行为），且断言内容几乎一致（`authToken.isEmpty()` 与 `authToken.size == 0` 等价）。
- **风险**: **低**。增加维护负担，无额外覆盖价值。
- **修复方式**: 合并为一个测试，或删除其中一个。

---

## 提交后审查发现（审查提交 2c35abc..b9f04c3）

> 以下问题由多 subagent 对过去 24 小时内各分支的提交进行深度审查发现。

### REV8: `sendLoop` 中 `WriteMessage` 无锁保护，与 `Close()` 存在竞态可导致 nil panic
- **状态**: 已修复
- **修复提交**: `c7875a1`（主修复，connMu 锁保护）；`cbea5f6`（跟进，写入超时）
- **位置**: `server/tunnel/main.go` (sendLoop, L680-L704)
- **问题描述**: `sendLoop` 直接调用 `tunnel.Conn.WriteMessage()` 而未持有 `connMu` 锁。`Close()` 在 `connMu` 保护下设置 `t.closed = true` 并关闭底层连接（`t.Conn.Close()`），但**不会**将 `t.Conn` 置 nil。存在竞态窗口：`sendLoop` 检查 `Conn != nil` 后、调用 `WriteMessage` 前，`Close()` 可能已关闭连接，导致 `WriteMessage` 返回错误。`sendLoop` 现已通过 `connMu` 保护 `WriteMessage` 调用，与 `WritePing` 和 `Close()` 保持一致的锁保护模式。
- **风险**: **高**。高并发下设备断连时，`heartbeat` 超时调用 `tunnel.Close()`，同时 `sendLoop` 正在写入，可导致 panic 或数据损坏。
- **修复难度**: 低
- **修复方式**: 在 `sendLoop` 中通过 `connMu` 保护 `WriteMessage` 调用，与 `WritePing` 和 `Close()` 保持一致的锁保护模式。

### REV9: `MqttConnectionManager.connect()` catch 块中 `activeTokenSnapshot` 无同步读取
- **状态**: 已修复
- **修复提交**: `c7875a1`（主修复，synchronized 块包裹）；`b9ce717`（跟进，合并两个 synchronized 块）
- **位置**: `android/app/src/main/java/com/netproxy/gateway/connection/MqttConnectionManager.kt` (connect LAZY 协程 catch 块, L472-L486)
- **问题描述**: catch 块中 `scheduleReconnect(deviceId, activeTokenSnapshot ?: CharArray(0), generation)` 在 `synchronized` 块外读取 `activeTokenSnapshot`。`disconnect()` 可能在另一个线程同时执行 `activeTokenSnapshot?.fill('\u0000')`，导致：(1) 读到非 null 但内容全零的 CharArray（`fill` 完成但 `= null` 还没执行）；(2) `copyOf()` 复制全零数组；(3) 重连使用全零 token → 认证失败 → 无限重连循环。对比 `connectionLost` 回调中同样的代码在 `synchronized` 块内，是安全的。
- **风险**: **中高**。虽然 `shouldStayConnected` 的 `@Volatile` 和 `connectionGeneration` 提供了部分缓解，但 `activeTokenSnapshot` 非 volatile 且无同步保护，JMM 下行为未定义。
- **修复难度**: 中
- **修复方式**: 将 catch 块中的状态更新和 `scheduleReconnect` 调用包装在 `synchronized` 块内，与 `connectionLost` 回调保持一致。同时将 `activeTokenSnapshot = tokenSnapshot` 改为 `activeTokenSnapshot = tokenSnapshot.copyOf()`，使两者成为独立副本，防止 `disconnect()` 清零 `activeTokenSnapshot` 时影响 LAZY 协程的 `tokenSnapshot`。在 LAZY 协程中添加 `finally { tokenSnapshot.fill('\u0000') }` 块确保 token 在任何退出路径下都被清零。

### REV10: `MainViewModel.onCleared()` 直接 `fill` 导致与 collect 协程的数据竞争
- **状态**: 已修复
- **修复提交**: `c7875a1`
- **位置**: `android/app/src/main/java/com/netproxy/gateway/ui/viewmodel/MainViewModel.kt` (onCleared, L396-L399)
- **问题描述**: `onCleared()` 直接读取 `_uiState.value.authToken` 并执行 `fill('\u0000')`，没有通过 `_uiState.update` 先替换再清零。`_uiState` 是 `MutableStateFlow`，其 `value` 读取是原子的，但 `fill` 修改的是 CharArray 的内容，不是 StateFlow 的值。如果 `observeMqttState` 的 collect 协程正在 `_uiState.update` lambda 内部处理 Disconnected/Error 状态，两者操作的是同一个 CharArray 实例，导致并发修改。此外，`onCleared()` 只清零了 CharArray 内容，未将 UiState 中的 `authToken` 替换为 `CharArray(0)`，与 Disconnected/Error 分支的处理不一致。
- **风险**: **高**。ViewModel 销毁时 MQTT 连接恰好断开或出错，弱网环境下退出应用时容易触发。
- **修复难度**: 中
- **修复方式**: 改为通过 `_uiState.update { current -> val oldToken = current.authToken; val newState = current.copy(authToken = CharArray(0)); oldToken.fill('\u0000'); newState }` 先原子替换再清零旧引用。

### REV11: `observeMqttState()` Disconnected/Error 分支未清除 UiState 中的 authToken
- **状态**: 已修复
- **修复提交**: `c7875a1`
- **位置**: `android/app/src/main/java/com/netproxy/gateway/ui/viewmodel/MainViewModel.kt` (observeMqttState, L232-L268)
- **问题描述**: 当 MQTT 连接意外断开（非用户主动 disconnect）时，`observeMqttState()` 的 Disconnected 和 Error 分支未清除 UiState 中的 authToken，也未清零旧引用。对比 `disconnect()` 方法正确地执行了 `oldToken.fill('\u0000')`，这两个分支遗漏了相同的安全处理。敏感 token 在内存中残留，且状态语义不一致（isPaired=false 但 authToken 非空）。
- **风险**: **中**。敏感 token 在内存中残留，且状态语义不一致。
- **修复难度**: 低
- **修复方式**: 在 Disconnected 和 Error 分支中，仿照 `disconnect()` 的模式，先保存旧 authToken 引用，再替换为 `CharArray(0)`，最后对旧引用执行 `fill('\u0000')`。

### REV12: `pairWithCode()` 中 `authSessionStore.clear()` 可能吞掉原始异常
- **状态**: 已修复
- **修复提交**: `c7875a1`（主修复，使用 `clearWithResult()`）；`96e7a98`（跟进，处理 `clearWithResult()` 返回值）
- **位置**: `android/app/src/main/java/com/netproxy/gateway/ui/viewmodel/MainViewModel.kt` (pairWithCode, L330-L334)
- **问题描述**: 当 `mqttConnectionManager.connect()` 抛出异常时，catch 块调用 `authSessionStore.clear()` 回滚已存储的 session。但 `clear()` 内部调用 `EncryptedSharedPreferences.edit()...apply()`，在加密密钥损坏时可抛出 `GeneralSecurityException`，导致原始的 connect 异常被吞掉，用户看到的是 SharedPreferences 加密错误而非网络错误。`AuthSessionStore` 已提供 `clearWithResult(): AppResult<Unit>` 方法（内部 try-catch 包装），但未使用。
- **风险**: **中**。网络不稳定时容易触发 connect 失败，如果同时加密密钥被锁定（如设备锁屏后密钥被回收），错误信息会误导用户。
- **修复难度**: 中
- **修复方式**: 使用 `authSessionStore.clearWithResult()` 替代 `authSessionStore.clear()`，避免异常传播。同时在 pairWithCode 中添加 try/finally 确保 `authTokenArray.fill('\u0000')` 在任何退出路径下都被执行，并在 else/catch 分支的 `_uiState.update` 中清零旧 authToken 引用。

### REV13: `server/api/main.go` login 端点使用 `!=` 比较密码，存在时序侧信道
- **状态**: 已修复
- **修复提交**: `c7875a1`（主修复，使用 `subtle.ConstantTimeCompare`）；`cbea5f6`（跟进，改用 SHA256 哈希恒定时间比较防止密码长度泄露）
- **位置**: `server/api/main.go` (login, L1086)
- **问题描述**: `login` 端点使用 `req.Username != adminUser || req.Password != adminPass` 比较凭据，而非 `subtle.ConstantTimeCompare`。Go 的 `!=` 对字符串进行逐字符比较，遇到第一个不匹配字符即返回，攻击者可通过响应时间差异推断正确凭据的部分内容。对比同文件中 `authorizeStats` 和 `validateSession` 均使用 `subtle.ConstantTimeCompare`，login 端点未遵循相同的安全标准。
- **风险**: **中**。作为安全产品，login 端点应遵循自身代码库已建立的 `constantTimeCompare` 模式。修复成本极低。
- **修复难度**: 低
- **修复方式**: 改用 `subtle.ConstantTimeCompare([]byte(req.Username), []byte(adminUser)) != 1 || subtle.ConstantTimeCompare([]byte(req.Password), []byte(adminPass)) != 1`。

### REV14: `MqttConnectionManager.connect()` LAZY 协程取消导致 `tokenSnapshot` 泄漏 [已修复]
- **状态**: 已修复
- **修复提交**: `e42094b`
- **提交哈希**: `c7875a1`
- **位置**: `android/app/src/main/java/com/netproxy/gateway/connection/MqttConnectionManager.kt` (`connect()`, L282-L492)
- **问题描述**: `connect()` 方法使用 `CoroutineStart.LAZY` 启动协程，并在协程体内从 `activeTokenSnapshot` 复制 `tokenSnapshot`。若协程被取消（例如 `disconnect()` 在协程体执行前调用），`tokenSnapshot` 不会被创建，因此不存在泄漏路径。协程正常执行或异常退出时，`finally { tokenSnapshot.fill('\u0000') }` 保证副本被清零。`disconnect()` 同时负责清零 `activeTokenSnapshot`。
- **风险**: **已消除**。token 副本仅在协程体内存在，取消时无副本创建，正常退出时 `finally` 清零。

### REV18: `MqttConnectionManager.scheduleReconnect()` LAZY 协程取消导致 `tokenCopy` 泄漏 [已修复]
- **状态**: 已修复
- **修复提交**: `e42094b`
- **提交哈希**: `1371601`（引入 `tokenCopy`）
- **位置**: `android/app/src/main/java/com/netproxy/gateway/connection/MqttConnectionManager.kt` (`scheduleReconnect()`, L505-L534)
- **问题描述**: `scheduleReconnect()` 方法使用 `CoroutineStart.LAZY` 启动协程，并在协程体内从 `activeTokenSnapshot` 复制 `tokenCopy`。若协程被取消（例如 `disconnect()` 在协程体执行前调用），`tokenCopy` 不会被创建，因此不存在泄漏路径。协程正常执行或异常退出时，`finally { tokenCopy.fill('\u0000') }` 保证副本被清零。该修复模式与 REV14 一致。
- **风险**: **已消除**。token 副本仅在协程体内存在，取消时无副本创建，正常退出时 `finally` 清零。

### REV19: `server/api/main.go` login 端点每次请求重复计算 SHA256 [已修复]
- **状态**: 已修复
- **修复提交**: `e42094b`
- **提交哈希**: `cbea5f6`
- **位置**: `server/api/main.go` (`login`, L1087-L1096)
- **问题描述**: ~~旧代码在每次 HTTP 请求时从环境变量重新计算 SHA256。~~ `e42094b` 已在 `Server` 初始化时计算并缓存 `expectedUserHash` / `expectedPassHash`，`login` 中直接使用缓存值，消除了重复计算。
- **风险**: **已消除**。登录端点不再重复计算哈希，CPU 开销已降低。

### REV20: `TestSendLoopNoPanicOnConcurrentClose` 未检查 `tunnel.Send()` 错误 [已修复]
- **状态**: 已修复
- **修复提交**: `e42094b`
- **提交哈希**: `c7875a1`
- **位置**: `server/tunnel/main_test.go` (`TestSendLoopNoPanicOnConcurrentClose`, L900-L920)
- **问题描述**: ~~旧代码忽略 `tunnel.Send()` 返回值。~~ `e42094b` 已在洪水 goroutine 中增加 `tunnel.Send()` 错误检查，使用 `t.Fatalf` 在发送失败时立即终止测试，确保"100 条消息全部入队"的假设成立。后续 CR9-4 进一步优化为 `t.Errorf` + 原子计数器，消除高负载下的 flaky 风险。
- **风险**: **已修复**。发送失败不再被静默忽略，测试覆盖的置信度已恢复。

## Code Review Round 9 (Cross-Review)

针对提交 `e42094b`（分支 `fix/post-commit-review-concurrency-security`）的交叉审查结果。

### CR9-1: ISSUES.md 中 REV14/REV18/REV19/REV20 状态未更新为已修复 [已修复]
- **状态**: 已修复
- **修复提交**: `1cc1d10`
- **提交哈希**: `e42094b`
- **位置**: `docs/ISSUES.md` (REV14, REV18, REV19, REV20)
- **问题描述**: 提交 `e42094b` 的提交信息明确声明 "resolve cross-review issues REV14, REV18-REV20"，且代码变更确实实现了对应的修复逻辑（REV14/REV18 将 token 复制移入 LAZY 协程体；REV19 在 `Server` 初始化时缓存 SHA256 哈希；REV20 在测试中增加 `tunnel.Send()` 错误检查）。但 ISSUES.md 中这四个条目的状态仍标记为 "待修复"，且未引用 `e42094b` 作为修复提交。这会导致后续开发者误以为这些安全问题仍然存在，可能触发不必要的重复修复或混淆。
- **风险**: **高**。文档与代码严重不一致，影响维护决策和发布判断。
- **修复难度**: 低
- **修复建议**: 将 REV14、REV18、REV19、REV20 的状态更新为 "已修复"，并添加 `e42094b` 作为修复提交引用。更新 REV14 和 REV18 的问题描述，使其准确反映当前代码行为（而非修复前的行为）。

### CR9-2: ISSUES.md 中 REV14 与 REV18 的问题描述基于修复前代码 [已修复]
- **状态**: 已修复
- **修复提交**: `1cc1d10`
- **提交哈希**: `e42094b`
- **位置**: `docs/ISSUES.md` (REV14, REV18)
- **问题描述**: REV14 描述中提到 "`activeTokenSnapshot = tokenSnapshot.copyOf()`，使两者成为独立副本" 以及 "`finally { tokenSnapshot.fill('\u0000') }`"，这是 `c7875a1` 引入的旧代码行为。`e42094b` 已将 `tokenSnapshot` 的创建完全移入 LAZY 协程体内（从 `activeTokenSnapshot` 复制），不存在协程外独立的 `tokenSnapshot` 引用。同理，REV18 描述中 "`scheduleReconnect()` 在入口处 `val tokenCopy = authToken.copyOf()`" 也是旧代码行为。文档描述与当前代码不一致，会误导审查者认为修复尚未实施。
- **风险**: **中**。文档描述过时，可能误导后续安全审计和代码审查。
- **修复难度**: 低
- **修复建议**: 重写 REV14 和 REV18 的问题描述，说明 `e42094b` 已将 token 复制移入 LAZY 协程体，并评估当前实现是否仍存在残余风险（如需要）。

### CR9-3: `scheduleReconnect` 参数 `authToken` 成为孤儿参数 [已修复]
- **状态**: 已修复
- **修复提交**: `1cc1d10`
- **提交哈希**: `e42094b`
- **位置**: `android/app/src/main/java/com/netproxy/gateway/connection/MqttConnectionManager.kt` (`scheduleReconnect()`)
- **问题描述**: 提交 `1cc1d10` 已移除 `scheduleReconnect` 的 `authToken: CharArray` 参数，同步更新了所有调用点。当前签名已为 `scheduleReconnect(deviceId: String, generation: Long)`，token 在 LAZY 协程体内从 `activeTokenSnapshot` 复制。
- **风险**: **低**。不影响运行时行为，但损害代码可读性和可维护性。
- **修复难度**: 低

### CR9-4: `TestSendLoopNoPanicOnConcurrentClose` 中 `t.Fatalf` 在子 goroutine 内可能引发偶发失败 [已修复]
- **状态**: 已修复
- **修复提交**: `1cc1d10`
- **提交哈希**: `e42094b`
- **位置**: `server/tunnel/main_test.go` (`TestSendLoopNoPanicOnConcurrentClose`, L924-L926)
- **问题描述**: 提交 `1cc1d10` 已将洪水 goroutine 内的 `t.Fatalf` 替换为 `atomic.Int32` 错误计数器，在主 goroutine `floodWg.Wait()` 之后统一断言无错误。消除了高负载下的 flaky test 风险。
- **风险**: **低**。仅影响测试可靠性，不影响生产代码。
- **修复难度**: 低

## Code Review Round 10 (Cross-Review)

针对提交 `ceab4ad`（分支 `fix/post-commit-review-concurrency-security`）及整个 PR 累积状态的交叉审查结果。

### CR10-1: ISSUES.md 中 CR9-1 至 CR9-4 状态未更新为已修复 [已修复]
- **状态**: 已修复
- **修复提交**: `1cc1d10`
- **提交哈希**: `ceab4ad`（审查提交）
- **位置**: `docs/ISSUES.md` (CR9-1, CR9-2, CR9-3, CR9-4)
- **问题描述**: 提交 `1cc1d10` 已明确修复 CR9-1~CR9-4（更新 REV14/REV18/REV19/REV20 状态为已修复、重写 REV14/REV18 描述、移除 `scheduleReconnect` 的 `authToken` 孤儿参数、将洪水 goroutine 内的 `t.Fatalf` 替换为 `atomic.Int32` 计数器）。但 ISSUES.md 中 CR9-1~CR9-4 条目本身仍标记为 "待修复"，且未引用修复提交 `1cc1d10`。这会导致后续维护者重复审查已修复的问题，浪费精力并可能引入不必要的变更。
- **风险**: **中**。文档与代码事实严重不一致，持续消耗审查资源。
- **修复难度**: 低
- **修复建议**: 将 CR9-1~CR9-4 的状态更新为 "已修复"，添加 `1cc1d10` 作为修复提交引用，并更新 CR9-3/CR9-4 的问题描述以反映当前代码状态（见 CR10-2）。

### CR10-2: ISSUES.md CR9-3 与 CR9-4 的问题描述基于修复前代码 [已修复]
- **状态**: 已修复
- **修复提交**: `1cc1d10`
- **提交哈希**: `ceab4ad`（审查提交）
- **位置**: `docs/ISSUES.md` (CR9-3, CR9-4)
- **问题描述**:
  - CR9-3 描述中声称 "`scheduleReconnect` 参数 `authToken: CharArray` 在方法体内不再被任何代码引用"，但提交 `1cc1d10` 已将该参数完全移除，当前签名已是 `scheduleReconnect(deviceId: String, generation: Long)`。描述完全过时。
  - CR9-4 描述中声称 "洪水 goroutine 内增加 `if err := tunnel.Send(...); err != nil { t.Fatalf(...) }`"，但提交 `1cc1d10` 已将其替换为 `atomic.Int32` 错误计数器 + 主 goroutine 统一断言。描述完全过时。
- **风险**: **低**。误导后续审查者认为代码仍存在孤儿参数和 flaky test 风险，但运行时不受影响。
- **修复难度**: 低
- **修复建议**: 重写 CR9-3 和 CR9-4 的问题描述，说明当前已实现的行为（无 authToken 参数 / 使用 atomic 计数器），或直接将这两个条目标记为已修复并归档。

### CR10-3: ISSUES.md REV8 描述与当前 `Close()` 实现不符 [已修复]
- **状态**: 已修复
- **修复提交**: `5f0521d`
- **提交哈希**: `c7875a1`（引入 REV8 描述）；`cbea5f6`（修改 Close 行为）
- **位置**: `docs/ISSUES.md` (REV8) 与 `server/tunnel/main.go` (`Close()`, L181-L191)
- **问题描述**: REV8 描述称 "`Close()` 在 `connMu` 保护下将 `t.Conn` 设为 nil 并关闭底层连接"。但当前代码中 `Close()` 仅执行 `t.closed = true`、`close(t.closeChan)` 和 `t.Conn.Close()`（关闭底层 WebSocket 连接），**从未将 `t.Conn` 设为 nil**。该描述基于旧代码理解，与当前实现不符。尽管 `sendLoop` 的 nil 检查 `tunnel.Conn == nil` 仍然存在，但 `Close()` 不会触发该路径。
- **风险**: **低**。文档不准确可能导致后续维护者对 `Close()` 的行为产生错误假设，但当前锁保护逻辑已正确消除竞态。
- **修复难度**: 低
- **修复建议**: 修正 REV8 描述，准确说明 `Close()` 设置 `t.closed = true` 并关闭底层连接，而非将 `t.Conn` 置 nil；同时说明 `sendLoop` 通过 `connMu` 锁和 `t.closed` 标志与 `Close()` 同步。

### CR10-4: `TestSendLoopNoPanicOnConcurrentClose` 中 `defer clientConn.Close()` 导致双重关闭 [已修复]
- **状态**: 已修复
- **修复提交**: `5f0521d`
- **提交哈希**: `ceab4ad`
- **位置**: `server/tunnel/main_test.go` (`TestSendLoopNoPanicOnConcurrentClose`, L904)
- **问题描述**: 提交 `ceab4ad` 新增了 `defer clientConn.Close()`。但测试流程中显式调用了 `tunnel.Close()`，而 `tunnel.Close()` 内部已通过 `t.Conn.Close()` 关闭了同一个 `clientConn`。函数返回时 `defer clientConn.Close()` 会执行第二次关闭，形成冗余的双重关闭。gorilla/websocket 的 `Close()` 通常可安全处理重复调用（底层 WriteControl 会返回错误但不会 panic），但属于不良实践，且可能掩盖其他资源清理问题。
- **风险**: **低**。仅影响测试代码，不影响生产行为；当前不会导致测试失败。
- **修复难度**: 低
- **修复建议**: 移除 `defer clientConn.Close()`，因为 `tunnel.Close()` 已负责关闭底层连接；或在 `tunnel.Close()` 后将 `clientConn` 置为 nil 以避免重复关闭。

### CR10-5: MainViewModel `StateFlow.update` CAS 重试场景下存在理论上的 token 误清零风险 [已修复]
- **状态**: 已修复
- **修复提交**: `5f0521d`
- **提交哈希**: `c7875a1`（引入 REV10/REV11 修复模式）
- **位置**: `android/app/src/main/java/com/netproxy/gateway/ui/viewmodel/MainViewModel.kt` (`observeMqttState` Disconnected/Error 分支、`disconnect()`、`onCleared()` 等)
- **问题描述**: REV10/REV11 修复将 `authToken.fill('\u0000')` 移出 `_uiState.update` lambda，改为先通过 lambda 捕获旧引用到局部变量 `tokenToZero`，再在 `update` 返回后执行清零。`MutableStateFlow.update` 内部使用 CAS 循环，若并发竞争导致重试，lambda 会被多次执行，每次都会覆盖 `tokenToZero`。在极端并发场景下（例如用户快速断开并重连），最后一次重试的 `current.authToken` 可能已经是新的有效 token，导致新 token 被意外清零。由于 MainViewModel 的 StateFlow 更新通常在 `Dispatchers.Main` 主线程串行调度，实际触发概率极低，但存在理论可能。
- **风险**: **低**。主线程串行执行使得 CAS 重试的并发窗口几乎不存在；即使触发，也只是清零新 token 而非泄漏旧 token，不会扩大攻击面。
- **修复难度**: 中
- **修复建议**: 若需彻底消除理论风险，可改为在 `_uiState.update` lambda 内部使用 `compareAndSet` 的返回值获取更新前的状态快照，或使用自定义的原子替换逻辑确保只清零真正被替换掉的那个引用。考虑到实际触发概率，当前实现可接受，但建议在文档中记录此边界行为。

## Code Review Round 10 (Post-Commit Correctness Check)

针对提交 `1cc1d10`（分支 `fix/post-commit-review-concurrency-security`）的提交后正确性检查结果。

### REV21: `Send()` 在 `closeChan` 关闭后可能返回 nil 导致静默数据丢失 [已修复]
- **状态**: 已修复
- **位置**: `server/tunnel/main.go` (`Send()`, L169-L185)
- **问题描述**: 提交 `e557555`（CodeRabbit seventh-review）移除了 `Send()` 中的 `if t.closed.Load() { return fmt.Errorf("tunnel closed") }` 原子前置检查，并将 `closed` 从 `atomic.Bool` 改回普通 `bool`。这导致当 `closeChan` 已关闭且 `sendChan` 缓冲区仍有空位时，Go 的 `select` 在两个就绪分支间均匀随机选择。若选中 `sendChan` 分支，数据被写入通道、`Send()` 返回 `nil`（成功），但 `sendLoop` 已退出无人消费，数据被静默丢弃。作为公开 API，`Send()` 返回 `nil` 意味着"发送成功"的语义契约被违反。
- **触发场景**: (1) `Close()` 被调用，`closeChan` 被关闭，`sendLoop` 退出；(2) 另一个 goroutine 调用 `Send(data)`；(3) `sendChan` 缓冲区仍有空位（因消费者已退出）；(4) `select` 随机选中 `sendChan` 分支（约50%概率）；(5) `Send()` 返回 `nil`，但数据永远不会被消费。
- **风险**: **高**。静默数据丢失是分布式系统中最难排查的问题之一。调用方收到成功信号但数据永远不会到达对端。
- **修复难度**: 低
- **修复方式**: 恢复 `closed` 为 `atomic.Bool`，在 `Send()` 的 `select` 前添加 `if t.closed.Load() { return fmt.Errorf("tunnel closed") }` 原子检查。原子前置检查将竞态窗口从"整个 select 执行期间"缩小到"原子读取与 select 开始之间的纳秒级窗口"，实际几乎不可触发。

### REV22: `closed` 字段从 `atomic.Bool` 退回普通 `bool`，存在数据竞争风险 [已修复]
- **状态**: 已修复
- **位置**: `server/tunnel/main.go` (`TunnelConn` struct, L128)
- **问题描述**: 提交 `e557555` 将 `closed` 从 `atomic.Bool` 改回普通 `bool`。虽然当前 `closed` 的所有读取都在 `connMu` 保护下（`WritePing`、`sendLoop`），但恢复 `Send()` 的前置检查需要在无锁情况下读取 `closed`，使用普通 `bool` 将构成数据竞争（Go race detector 会报告）。`atomic.Bool` 是更正确的做法，且为 REV21 的修复提供基础。
- **风险**: **中**。当前无竞争（`Send()` 不读取 `closed`），但恢复前置检查时必须使用原子操作，否则会引入新的数据竞争。
- **修复难度**: 低
- **修复方式**: 将 `closed bool` 改回 `closed atomic.Bool`，所有赋值改为 `Store(true)`，所有读取改为 `Load()`。

### REV23: `sendLoop` 在 `closed || Conn == nil` 路径未调用 `Close()` [已修复]
- **状态**: 已修复
- **位置**: `server/tunnel/main.go` (`sendLoop`, L698-L701)
- **问题描述**: `sendLoop` 在检测到 `tunnel.closed || tunnel.Conn == nil` 时直接 `return`，未调用 `tunnel.Close()`。虽然 `closed == true` 意味着 `Close()` 大概率已被调用（幂等安全），但 `Conn == nil` 路径（防御性检查）下 `Close()` 可能从未被调用。遗漏 `Close()` 导致：(1) `closeChan` 未关闭，`heartbeat()` 和 `readLoop()` 不会被通知退出；(2) 后续 `Send()` 调用仍能将数据放入 `sendChan`，但 `sendLoop` 已退出无人消费，导致静默数据丢失。对比同函数内 `WriteMessage` 错误路径和 `SetWriteDeadline` 错误路径都正确调用了 `tunnel.Close()`，此路径处理不一致。`closeOnce` 保证 `Close()` 幂等，调用安全。
- **风险**: **中**。`Conn == nil` 在当前代码中为防御性路径，但违反防御性编程原则，且与同函数内其他退出路径的处理不一致。
- **修复难度**: 低
- **修复方式**: 在 `closed || Conn == nil` 路径的 `connMu.Unlock()` 后添加 `tunnel.Close()` 调用。

### REV24: `MqttConnectOptions` 密码副本在 TLS 配置异常时未被清除 [已修复]
- **状态**: 已修复
- **位置**: `android/app/src/main/java/com/netproxy/gateway/connection/MqttConnectionManager.kt` (connect LAZY 协程, L344-L365)
- **问题描述**: REV22 的修复将 `connectOptions = options` 放在 `apply` 块之后（原 L359）。`apply` 块内包含 TLS 配置代码（`createSecureSocketFactory()`、`sslHostnameVerifier` 赋值），这些代码可能在 `password = tokenSnapshot` 之后抛出异常。如果 TLS 配置抛出异常，`apply` 块未完成，`connectOptions` 保持为 `null`，`finally` 块中的 `connectOptions?.password?.fill('\u0000')` 不执行，Paho 内部通过 `Arrays.copyOf()` 复制的密码副本在堆内存中残留直到 GC 回收。
- **触发场景**: (1) 用户启用 TLS 连接；(2) `MqttConnectOptions().apply { password = tokenSnapshot; ... createSecureSocketFactory() }` 执行；(3) `createSecureSocketFactory()` 因 SSL 上下文配置错误（如 keystore 缺失、证书格式错误）抛出异常；(4) `apply` 块中断，`connectOptions` 未赋值；(5) `finally` 块中 `connectOptions?.password?.fill('\u0000')` 为空操作；(6) Paho 内部密码副本在堆内存中残留。
- **风险**: **中高**。root 设备或调试器可读取堆内存中的明文 token。与 REV22 修复的原始问题相同类别，但触发路径不同。
- **修复难度**: 低
- **修复方式**: 将 TLS 配置（`socketFactory`、`sslHostnameVerifier` 赋值）从 `apply` 块中移出，在 `connectOptions = options` 赋值之后再进行 TLS 配置。这样即使 TLS 配置抛出异常，`connectOptions` 已赋值，`finally` 块能正确清除 Paho 内部密码副本。

---

## 2026-07-06 CI 诊断新发现

本次会话在修复 T6（PR #65）后触发 CI run `28787711600`，发现以下新问题。诊断提交哈希锚点：`a2dc331`（分支 `fix/ci-android-deadlock`）。

### N63: Android `testDebugUnitTest` 在 CI 上挂住 27min 被 timeout 取消（另一既存 flaky 测试，非 T6）
- **状态**: 待修复
- **发现提交哈希**: `a2dc331`(worktree)（分支 `fix/ci-android-deadlock`，PR #65 触发的 CI run `28787711600`）
- **位置**: `android/app/src/test/java/com/netproxy/gateway/` 下未知测试类（疑似 connection 或 vpn 包）
- **问题描述**: PR #65 修复 T6 后再次触发 CI，Android job 在 `11:22:28` 启动 `> Task :app:testDebugUnitTest`，到 `11:49:57` 被 `timeout-minutes: 30` 取消——**27 分钟内 Gradle 未输出任何 `PASS:`/`FAIL`/`=== RUN` 行**，说明测试刚启动就挂住了（可能在加载某个测试类的初始化阶段）。这不是 T6——T6 本地单跑通过（1m30s）且全类通过；这是**另一个既存 flaky 测试**，之前被 T6 的死锁掩盖（T6 卡死时 unit test 步骤根本跑不到这个测试）。
- **风险**: **高**。Android job 永远无法在 CI 上通过，阻塞所有 PR 的 required check。`timeout-minutes: 30` 防止了 runner 无限期占用，但失败结论不变。
- **修复难度**: 中高。需在 CI 上启用 Gradle 实时输出（`--tests` 配合 `testOptions.reportPool` 或 `-Dorg.gradle.parallel.in-process=true`）多次单跑定位挂住的测试类；本地无法复现（本地 473 测试 9 分钟内完成）。
- **修复方式**: (1) 在 CI workflow 的 `Run unit tests` 步骤追加 `--tests` 参数分段执行，或启用 `testDebugUnitTest` 的实时日志输出；(2) 在 `app/build.gradle.kts` 的 `testOptions` 加 `execution` 配置减少 fork 并发；(3) 定位后用确定性同步（`CountDownLatch`/`Semaphore`）替代挂住的 `Thread.yield()`/固定超时。
- **关联**: 本条目与 T6 同类别（flaky 并发测试），但根因不同。T6 已修复，本条目是新暴露的下一层 flaky。

### N64: `server/api/main.go` 在 git 中存储为 CRLF 但 `.gitattributes` 声明 `*.go text eol=lf`，导致每次 checkout 出现伪 diff
- **状态**: 待修复
- **发现提交哈希**: `a2dc331`(worktree)（分支 `fix/ci-android-deadlock`）
- **位置**: `server/api/main.go`（git blob 内容为 CRLF）、`.gitattributes`（`*.go text eol=lf`）
- **问题描述**: `git show HEAD:server/api/main.go | file -` 输出 `Unicode text, UTF-8 text, with CRLF line terminators`——该文件在 git 仓库 blob 中存储为 CRLF。但 `.gitattributes` 声明 `*.go text eol=lf`，要求 checkout 时强制转换为 LF。结果每次 `git checkout`/`git restore` 后，工作区文件被转为 LF，与 blob 的 CRLF 比对出现"全文修改"的伪 diff（实测显示 `2216 insertions / 2216 deletions`，即整个文件每一行都被"改了"）。本次会话中多次因 cherry-pick / stash / reset 触发该伪 diff，干扰变更审查。
- **风险**: **低**。不影响构建或测试，但污染 `git status`/`git diff`，干扰 Agent 和开发者判断真实变更范围；可能让不该提交的"全文修改"被误 commit。
- **修复难度**: 低。
- **修复方式**: 一次性 normalize 该文件的行尾——`git rm --cached server/api/main.go && renormalize` 或 `dos2unix server/api/main.go && git add`，然后提交"normalize line endings"单提交。提交后 `.gitattributes` 的规则会对该文件生效，伪 diff 消失。
- **关联**: 与 docs/CI_REFACTOR_PLAN.md 中"artifact 路径"等 bug 同源（都是 dev 分支历史遗留的配置不一致）。

## 2026-07-10 提交后正确性检查发现（过去 24h 提交/PR 审查）

### CI-PERM-1: PR #76 仅在 workflow 顶层加 `pull-requests: read` 被 job 级 `permissions:` 覆盖，paths-filter 仍 403（必需 CI 检查在所有 PR 事件上失败）[已修复]
- **状态**: 已修复（本 PR `fix/pr76-paths-filter-job-perms`）
- **发现位置**: PR #76 `fix/ci-paths-filter-permissions`（base: `dev`），提交 `8de3dfc`
- **影响文件**: `.github/workflows/android-ci.yml`、`.github/workflows/go-ci.yml`
- **问题描述**: PR #76 旨在修复 `dorny/paths-filter@v3` 在 `pull_request` 事件下的 "Resource not accessible by integration"（HTTP 403）错误，但仅在 workflow **顶层** `permissions:` 块加入 `pull-requests: read`。而 `android` 与 `go` 两个 job 各自声明了 **job 级** `permissions:` 块（`contents: read` + `actions: write`）。按 GitHub Actions 语义，job 级 `permissions:` 块会**完全替换**（而非合并）workflow 顶层块，未列出的 scope 一律降为 `none`。因此顶层新加的 `pull-requests: read` 对这两个 job 完全无效，paths-filter 仍在 `pull-requests: none` 下调用 GitHub REST API 拉取 PR 变更文件列表，触发 403。该步骤无 `continue-on-error`，导致 job 失败 → 两个被标为 "required check" 的检查（`Android Build & Test`、`Go Server Build (...)`）在**所有** PR 事件上持续红灯，PR 处于 `mergeable_state: blocked` 无法合并；若维护者为解阻塞而关闭必需检查，则 PR 事件 CI 形同虚设，缺陷将绕过审查流入主干。PR #76 对其声称目标而言是 no-op。
- **触发场景**: 任意针对 `main`/`dev` 的 `pull_request`（opened/synchronize/reopened，未被 `paths-ignore` 排除）→ `Detect changes` 步骤调用 paths-filter → REST API 403 → 步骤失败 → 必需检查失败 → PR 无法合并。
- **风险**: **高**（dev 工作流严重退化）。阻塞所有 PR 合并；或迫使维护者关闭必需检查，使 CI 失去对 PR 的把关能力。`push` 事件不受影响（paths-filter 在 push 时用 git diff，不调 PR API）。
- **根因**: 对 GitHub Actions 权限 "job 级替换顶层级、不合并"（scope zeroing）语义的误解。
- **验证**: 官方文档 [Assigning permissions to jobs](https://docs.github.com/en/actions/using-jobs/assigning-permissions-to-jobs) + [dorny/paths-filter README](https://github.com/dorny/paths-filter)（"Requires pull-requests: read permission"）。PR #76 的 `mergeable_state: blocked` 与该判断一致。
- **修复方式**: 在两个 workflow 的 `android`/`go` job 的 **job 级** `permissions:` 块中加入 `pull-requests: read`（保留 `contents: read` 与 `actions: write`，维持最小权限；`actions: write` 仍为 `actions/cache@v4` 所需）。同时顶层也补 `pull-requests: read`（与已验证可用的 `palette-ux-keyboard-focus` 分支一致，并对未来未声明 job 级权限的 job 生效）。
- **回归测试**: 新增 `scripts/check_ci_permissions.py`（纯 stdlib）+ `make ci-perms-check`。脚本解析两个 workflow，断言每个使用 `dorny/paths-filter` 的 job 在其 **job 级** `permissions:` 中包含 `pull-requests: read`，缺失即失败。已验证：修复后通过；在 `dev` 基线与 PR #76 版本（仅顶层）上均失败。
- **关联**: 与 `palette-ux-keyboard-focus-17858698244994354345` 分支（PR #74）中已存在的等价 job 级修复一致；本 PR 为该问题的独立、定向修复，可取代 PR #76。

## 提交后正确性检查发现（2026-07-07，审查过去 24h 提交 + Dockerfile 部署链路）

> 审查范围：过去 24 小时内的提交（`aec1e89`、`935331e`、`7c38044`、`81e8624`、`0beecd6`、`8a9234e`、`507f2d3`，以及 PR #65 的 CI 修复 `e211cbd`/`399dcbd`/`a2dc331`/`976fea4`）和最近更新的 PR（#62、#63、#64、#65）。经多个独立子代理并行分析 + 两个子代理独立复现复审确认。

### DEPLOY1: `server/api/Dockerfile` 以 `CGO_ENABLED=0` 构建导致 API 容器启动即崩溃 [已修复]
- **状态**: 已修复（本审查提交的定向修复分支 `fix/api-dockerfile-cgo-sqlite-crash`）
- **位置**: `server/api/Dockerfile` (L1, L8)；触发点 `server/api/main.go` (`NewServer` → `db.Ping()`, L188-L196；`main` 的 `log.Fatalf`, L1152-L1154)
- **问题描述**: API 服务依赖 CGo-only 的 `github.com/mattn/go-sqlite3` 驱动（`main.go:20` 的 blank import，`main.go:188` 的 `sql.Open("sqlite3", ...)`），但 `server/api/Dockerfile` 使用 `RUN CGO_ENABLED=0 GOOS=linux go build -o api .`。`CGO_ENABLED=0` 时 go-sqlite3 编译为 stub 驱动，运行时 `sql.Open` 返回的 DB 在首次 `db.Ping()` 时报错：`"Binary was compiled with 'CGO_ENABLED=0', go-sqlite3 requires cgo to work. This is a stub"`。`NewServer` 把该错误向上返回，`main()` 以 `log.Fatalf("Failed to create server: %v", err)` 退出，容器启动即崩溃（exit 1）。`docker build` 能成功（stub 可编译），失败被推迟到运行时，因此只做构建校验的 CI 难以发现。此外构建基镜像 `golang:1.22-alpine` 与 `go.mod` 的 `go 1.25.0` 不匹配（依赖 `GOTOOLCHAIN=auto` 联网下载 1.25）。该缺陷虽早于本次 24h 窗口（Dockerfile 上次改动为 `55a1ba8`，2026-03-26），但它是过去 24h 大量 sqlite3/CGo 改动（含误判 "CGo incompatibility with Go 1.25" 而把严格类型检查退化为字符串匹配的 `81e8624`）一直绕开的真实根因，属代码审查遗漏的高影响缺陷。
- **触发场景**: (1) `docker compose up` 或 `docker build -f server/api/Dockerfile`； (2) 容器启动执行 `./api`； (3) `NewServer()` 调用 `db.Ping()` 返回 stub 错误； (4) `log.Fatalf` 退出，容器进入 `restart: unless-stopped` 的无限重启循环，`/health` 永不就绪； (5) 由于 `socks5-proxy`/`tunnel` 均 `depends_on: api` 并调用其 `/api/session/validate`、`/api/device/status`，整个 compose 栈不可用。100% 可复现。
- **风险**: **高**。部署即崩溃，API 服务（承载配对、JWT、认证、设备状态）完全不可用；非数据损坏或安全漏洞，而是确定性的全栈服务中断。
- **修复难度**: 低
- **修复方式**: `server/api/Dockerfile` 改为 `FROM golang:1.25-alpine AS builder`、新增 `RUN apk add --no-cache gcc musl-dev`（go-sqlite3 内嵌 SQLite amalgamation，只需 C 工具链，无需 `sqlite-dev`/`sqlite-libs`）、构建命令改为 `RUN CGO_ENABLED=1 GOOS=linux go build -o api .`。最终 `alpine:latest` 运行阶段已含 musl，CGo 二进制可直接运行。`socks5-proxy`/`tunnel` 不使用 sqlite 且 `go.mod` 为 `go 1.22`，保持 `CGO_ENABLED=0` 不变。
- **验证**: 独立复现——`CGO_ENABLED=0` 构建的二进制启动即 `Failed to create server: failed to ping database: ... go-sqlite3 requires cgo to work. This is a stub`（exit 1）；`CGO_ENABLED=1` 构建的二进制正常启动并返回 `{"db_status":"ok",...}`。新增 `make api-smoke` 目标以 Dockerfile 等价标志（`CGO_ENABLED=1`）构建并校验 `/health` 返回 `db_status:"ok"`，作为回归守卫。`go build ./...`、`go vet ./...`、`go test ./...` 全部通过。
- **后续建议**: 现有 CI `go-build` 作业只对源码执行 `go build`/`go test`（runner 上 CGO 默认开启，故一直通过），从不构建 Docker 镜像——这正是本缺陷长期未被 CI 捕获的原因。建议后续在 self-hosted runner（具备 docker）上增加 `docker build` + `docker run` + `/health` 冒烟作业（可先 `continue-on-error: true`，确认稳定后改为阻塞），作为镜像层的回归守卫。

### 24h 窗口内提交的其余审查结论
- `7c38044` 曾用错误的 SQLite 扩展码 `2301`（正确值 `sqlite3.ErrConstraintPrimaryKey` = `1555`）替换符号常量，会导致 `code TEXT PRIMARY KEY` 冲突（实际 ExtendedCode=1555）不被识别为配对码冲突而直接返回 500；该缺陷为**瞬态**，同日被 `81e8624` 改为 `strings.Contains(err.Error(), "UNIQUE constraint")` 取代（对当前 schema 行为正确，真实冲突消息为 `"UNIQUE constraint failed: pairing_sessions.code"`），故在 dev HEAD 上不存在现存可触发缺陷。
- `81e8624`/`8a9234e` 将 `isPairingCodeUniqueConstraintError` 从 `errors.As` + 严格类型码检查退化为字符串匹配，并移除了 `733c883`（PR #64，未合入 dev）新增的回归测试 `TestIsPairingCodeUniqueConstraintError`。当前行为对现有 schema 等价、无即时可触发缺陷，但失去类型安全与回归保护；建议后续重新采用 `733c883` 的类型化严格检查并恢复测试（已验证该写法在 Go 1.25 + CGo 下可正常编译运行，所谓 "CGo incompatibility with Go 1.25" 前提不成立）。
- PR #65 的 CI 修复（runner 标签大小写、`fail-fast` YAML 层级、`Thread.yield()`→`Thread.sleep(20)`）均为正确且必要，未掩盖生产并发缺陷；`go test` 仍为阻塞作业。
- PR #62/#63 的 Palette 清除按钮 UI 改动未发现崩溃/安全/功能退化类缺陷（输入仍强制 6 位数字过滤；测试未削弱）。

---

## 提交后审查发现（2026-07-01，审查提交 97b4a3c 和 abd1603）

> 以下问题由多 subagent 对过去 24 小时内各分支的提交进行深度审查发现。

### REV42: `updatePairingSession` TOCTOU 竞态条件导致会话劫持 [已修复]
- **修复状态**: 已修复
- **提交哈希**: `97b4a3c`（审查时发现；问题为既有缺陷，非该提交引入）
- **位置**: `server/api/main.go` (`updatePairingSession`, L888-L968; `updatePairingSessionDB`, L472-L481; `markSessionExpired`, L510-L519)
- **编号说明**: 原编号为 REV25，因与 PR #35（OPEN，占用 REV25-REV41）冲突，重新编号为 REV42。
- **关联/替代关系**: 本修复与 PR #35 的 REV29 针对同一个 `updatePairingSession` TOCTOU 竞态问题。本修复是更完整的超集（新增 `status` 条件、`markSessionExpired` 保护、独立的 `ErrConcurrentModification` 错误类型），**已替代/覆盖 REV29**；PR #35 合入时已移除 REV29 的重复实现。
- **问题描述**: `updatePairingSession` 使用 Read-Validate-Modify-Write 模式，但在 Read 和 Write 之间没有乐观锁保护。`updatePairingSessionDB` 使用简单的 `UPDATE ... WHERE code = ?`，不检查 session 的 status 或 engineer_id 是否在读取后被修改。两个并发请求对同一 pairing code 执行时，可能都读到相同的过期数据（如 `status=pending, engineerID=""`），都通过验证检查，第二个写入覆盖第一个，导致会话被分配给错误的工程师。
- **触发场景**: (1) 配对码显示在设备屏幕上；(2) 工程师 A 和工程师 B 同时看到并尝试配对；(3) 两个请求同时到达服务器，都读到 `status=pending, engineerID=""`；(4) 两个请求都通过 `engineerID != "" && engineerID != myID` 检查（因为 `engineerID == ""`）；(5) 请求 A 写入 `(status=connected, engineerID=A)`；(6) 请求 B 写入 `(status=connected, engineerID=B)`，**覆盖请求 A 的结果**；(7) 工程师 A 的后续操作（如 `createSessionToken`）因 `engineerID` 不匹配而返回 403。
- **风险**: **高**。会话劫持：错误的工程师获得配对会话，原工程师的操作失败。安全漏洞：未经授权的工程师可能获得对设备的远程访问权限。
- **修复难度**: 中
- **修复方式**:
  1. 新增 `compareAndUpdatePairingSessionDB` 函数，在 UPDATE 的 WHERE 子句中加入 `status = ? AND engineer_id = ?` 条件，实现乐观锁。
  2. 新增 `ErrConcurrentModification` 错误，当 `RowsAffected() == 0` 时返回。
  3. `updatePairingSession` 在读取 session 后立即捕获 `expectedStatus` 和 `expectedEngineerID`，写入时使用 `compareAndUpdatePairingSessionDB`。
  4. `markSessionExpired` 同样使用乐观锁，防止过期标记覆盖并发修改。
  5. 检测到并发修改时返回 HTTP 409 Conflict，客户端可重试。
- **验证**: `TestCompareAndUpdatePairingSessionDB_ConcurrentModification`、`TestCompareAndUpdatePairingSessionDB_SuccessWhenNoConflict`、`TestCompareAndUpdatePairingSessionDB_StatusChangedConcurrently`、`TestIsValidSessionTransition`、`TestGetEngineerID`、`TestUpdatePairingSession_ConcurrentModificationReturns409` 全部通过。

## 提交后正确性检查发现（2026-06-22）

### REV25: MqttConnectionManager `_connectionState.value = Connecting` 竞态导致状态机永久卡死 [已修复]
- **修复状态**: 已修复
- **修复难度**: 低
- **位置**: `android/app/src/main/java/com/netproxy/gateway/connection/MqttConnectionManager.kt` (connect LAZY 协程, L334)
- **问题描述**: `connect()` 的 LAZY 协程在 synchronized 块外设置 `_connectionState.value = Connecting`。如果 `disconnect()` 在 LAZY 协程退出 synchronized 块后、设置状态前执行，`disconnect()` 将状态设为 `Disconnected`，随后 LAZY 协程覆盖为 `Connecting`。由于后续 generation 检查不匹配时不会修正状态，状态机永久卡死在 `Connecting`。
- **触发场景**: 用户调用 `connect()` → LAZY 协程通过 generation 检查退出 synchronized → 另一线程调用 `disconnect()` 设置 `Disconnected` → LAZY 协程设置 `Connecting` 覆盖 `Disconnected` → 状态永久卡死
- **风险**: **高**。UI 永久显示"连接中"，用户无法操作
- **修复方式**: 将 `_connectionState.value = Connecting` 和 `_diagnostics.update` 移入 synchronized 块内，与 generation 校验和客户端交换在同一原子操作中完成

### REV26: server/tunnel Register/Unregister 未检查 stopped 导致 WaitGroup 重用 panic [已修复]
- **修复状态**: 已修复
- **修复难度**: 低
- **位置**: `server/tunnel/main.go` (Register L284, Unregister L312)
- **问题描述**: `Register` 和 `Unregister` 在调用 `m.wg.Add(1)` 前未检查 `m.stopped`。`Stop()` 调用 `wg.Wait()` 后计数器归零，如果 `handleTunnel` 的 defer 随后调用 `Unregister`，`m.wg.Add(1)` 会触发 `panic: sync: WaitGroup is reused before previous Wait has returned`。`notifyDeviceStatus` 入口已有 `m.stopped` 检查（N82 修复），但 `wg.Add(1)` 在 goroutine 启动之前调用，不受其保护。
- **风险**: **高**。服务关闭时可能 panic
- **修复方式**: 在 `Register` 和 `Unregister` 中，在 `m.wg.Add(1)` 前获取 `m.stopMu` 并检查 `m.stopped`，若已停止则跳过 `m.wg.Add(1)` 和 goroutine 启动

### REV27: server/tunnel Register/Unregister 在 m.mu 锁内调用 tunnel.Close() 执行阻塞 I/O [已修复]
- **修复状态**: 已修复
- **修复难度**: 中
- **位置**: `server/tunnel/main.go` (Register L278, Unregister L300)
- **问题描述**: `Register` 和 `Unregister` 在持有 `m.mu` 锁时调用 `tunnel.Close()`。`Close()` 需要获取 `connMu`，如果 `sendLoop` 正在持有 `connMu` 执行 `WriteMessage`（最多阻塞 10 秒），`m.mu` 会被间接阻塞，导致所有设备的 `Get`/`Register`/`Unregister`/`handleStats` 操作全部阻塞。与 N27（Socks5ConnectionPool 同类问题，已修复）和 `cleanupDeadTunnelsOnce`（已正确将 Close() 移到锁外）模式一致。
- **风险**: **高**。单设备网络异常可导致全服务阻塞（DoS）
- **修复方式**: 仿照 `cleanupDeadTunnelsOnce` 模式——锁内仅做 map 删除和收集待关闭 tunnel，锁外再调用 `Close()`

### REV28: server/api GET /api/pair/:code 无认证可枚举配对码 [已修复]
- **修复状态**: 已修复
- **修复难度**: 低
- **位置**: `server/api/main.go` (L1182 路由注册)
- **问题描述**: `GET /api/pair/:code` 路由没有认证中间件，也无速率限制。攻击者无需任何凭据即可遍历 6 位配对码（100 万种可能），获取活跃配对会话的完整信息（engineer_id、device_id、status、expires_at 等）。对比同组 POST 和 PUT 路由均有 `authMiddleware` 保护，GET 路由是明显的授权遗漏。`internalOrUserAuthMiddleware` 已定义且经过测试，但未在生产路由中使用。
- **风险**: **高**。配对码枚举 + 信息泄露，可配合 REV29 劫持会话
- **修复方式**: 将 `GET /api/pair/:code` 路由的中间件从无改为 `internalOrUserAuthMiddleware()`，支持 Internal API Key 或 JWT Bearer Token 双模式认证

### REV29: server/api updatePairingSession TOCTOU 竞态可致会话劫持 [已修复]
- **修复状态**: 已修复（由 PR #49 / REV42 更完整的乐观锁覆盖；PR #35 中的原子 UPDATE 代码已移除）
- **修复难度**: 高
- **位置**: `server/api/main.go` (`updatePairingSession`, L888-L968)
- **问题描述**: `updatePairingSession` 的 read-check-update 是非原子的两步操作。两个工程师可同时读取 `EngineerID == ""` 的 session，都通过授权检查，然后先后覆写，后者覆盖前者，导致设备配对到非预期工程师。SQLite WAL 模式不解决应用层 TOCTOU。
- **风险**: **高**。设备被配对到错误工程师，远程协助场景下安全风险严重
- **修复方式**: 由 PR #49 的 `compareAndUpdatePairingSessionDB` 乐观锁实现覆盖；PR #35 原先的原子条件 UPDATE 代码已移除，避免重复/冲突实现。

### REV30: SOCKS5 代理域名连接绕过 IP 验证（安全漏洞） [已修复]
- **修复状态**: 已修复
- **修复难度**: 低
- **位置**: `android/app/src/main/java/com/netproxy/gateway/proxy/Socks5ProxyHandler.kt` (L129-134)
- **问题描述**: `validateTargetAddress` 对域名（ATYP=0x03）无条件返回 `true`，完全绕过 IP 验证。IPv4 地址仅允许 RFC1918 私有地址，但域名连接可解析到任何公网 IP，违反"仅允许访问客户内网"的安全策略。代码注释声称"连接到本地 SOCKS5 服务器"但实际 `NettyOutboundConnector` 直接连接目标地址，注释与架构不符。N30 描述的修复（拒绝域名）在当前代码中未体现。
- **风险**: **高**。工程师可通过域名访问公网资源，完全绕过私有网络访问策略
- **修复方式**: 对域名目标返回 `false`，拒绝所有域名连接，确保安全策略一致。更新注释说明拒绝原因

### REV31: server/api `markSessionExpired` 并发过期标记回退语义错误 [已修复]
- **修复状态**: 已修复
- **修复日期**: 2026-07-02
- **修复模型**: Kimi-K2.7-Code
- **修复难度**: 中
- **位置**: `server/api/main.go` (`getPairingSession`, L891-L905)
- **问题描述**: `markSessionExpired` 触发 `ErrConcurrentModification` 后，重新查询会话失败时返回 410 Gone，掩盖真实的数据库错误；查询成功但会话仍过期时返回 200 OK，与正常过期行为不一致。回退路径的 HTTP 语义和错误处理需要重新审视。
- **风险**: **中**。错误状态码不一致会误导客户端重试逻辑，且 DB 错误被隐藏不利于运维排查
- **修复方式**:
  - 并发冲突后重新查询失败：记录真实错误并返回 500 Internal Server Error（`ErrFailedToQueryDatabase`）。
  - 重新查询成功但会话仍过期（或已被删除）：返回 410 Gone（`ErrSessionExpired`），与正常过期路径一致。
  - 仅当并发请求将会话刷新为未过期状态时，才返回 200 OK 及当前会话数据。
  - 新增 `Server.testHookGetPairingSessionDB` 测试钩子以注入 `getPairingSessionDB` 返回值，覆盖上述三种分支。

### REV32: server/tunnel 离线通知可能覆盖新建立的在线状态 [已修复]
- **修复状态**: 已修复
- **修复难度**: 高
- **位置**: `server/tunnel/main.go` (`Unregister`、`cleanupDeadTunnelsOnce`、`notifyDeviceStatus`)
- **问题描述**: `Unregister` 和 `cleanupDeadTunnelsOnce` 在删除旧隧道后直接发送 `offline` 通知，未重新检查是否已有新的 replacement tunnel 注册。`notifyDeviceStatus` 的重试机制也可能在延迟期间把后来写入的 `online` 覆盖为 `offline`，造成设备状态与实际情况相反。
- **风险**: **高**。工程师端可能看到设备已离线，但实际上隧道已重建，导致远程协助中断或误判
- **修复方式**:
  - `server/tunnel/main.go`: `Unregister` 和 `cleanupDeadTunnelsOnce` 在关闭旧隧道后、发送 `offline` 前，重新检查 `m.tunnels[deviceID]` 是否存在活跃隧道；若存在则跳过 `offline` 通知。
  - 补充测试覆盖：替换隧道在 `Unregister`/`cleanupDeadTunnelsOnce` 的删除-通知窗口中注册时不发送 `offline`；无替换隧道时正常发送 `offline`。
  - `notifyDeviceStatus` 的重试延迟风险由 REV33 的毫秒级 `last_seen` 与 API upsert 的 `excluded.last_seen > device_status.last_seen` 保护覆盖，过期的 `offline` 不会覆盖较新的 `online`。
- **剩余风险**: 检查与通知之间仍存在极小的时间窗口；在此窗口内新隧道注册且旧 `offline` 已决定发送，则仍会发出一次 `offline` 通知。**该残余竞态在 REV51 之前未完全闭合**：REV33 当时的实现在通知 goroutine 内部捕获 `time.Now().UnixMilli()`，受调度延迟影响，其时间戳可能晚于并发 `Register` 发出的 `online` 时间戳，从而绕过 API 的严格 `last_seen >` 保护并把设备错误地持久化为离线。REV51 将 `last_seen` 的捕获提前到 re-check 时刻（`m.mu` 锁下），保证 `offline` 的 `last_seen` 严格早于任何后续 `Register` 的 `online` `last_seen`，使 API 保护真正生效。

### REV33: server/api + server/tunnel 秒级 `last_seen` 导致同秒重连状态丢失 [已修复]
- **修复状态**: 已修复
- **修复难度**: 中
- **位置**: `server/api/main.go` (`updateDeviceStatus` / `upsertDeviceStatusDB` / `getDeviceStatusDB`)、`server/tunnel/main.go` (`notifyDeviceStatus`)
- **问题描述**: `server/api/main.go` 写入设备状态时仍使用秒级 `Unix()` 作为 `last_seen`，且 `upsertDeviceStatusDB` 当前为无条件 `ON CONFLICT DO UPDATE`，没有 `excluded.last_seen > device_status.last_seen` 保护。`server/tunnel/main.go` 的 `notifyDeviceStatus` 带指数退避重试，旧的 `offline` 通知可能延迟到达 API，加上同秒内 `last_seen` 相同，无法判断事件先后顺序，导致过期的 `offline` 覆盖较新的 `online`。
- **风险**: **高**。高频重连场景下状态机不可靠，可能把在线设备判定为离线
- **修复方式**:
  - `server/api/main.go`：`last_seen` 改用毫秒级 `UnixMilli()`；`upsertDeviceStatusDB` 的 `ON CONFLICT DO UPDATE` 增加 `WHERE excluded.last_seen > device_status.last_seen` 保护；`getDeviceStatusDB` 按毫秒解析；`updateDeviceStatus` 接受请求体中的可选 `last_seen`（毫秒 Unix 时间戳），未提供时回落为当前时间。
  - `server/tunnel/main.go`：`notifyDeviceStatus` 通过 `last_seen` 字段将捕获的时间戳发送给 API，使延迟到达的 `offline` 携带原始时间戳。**注**：REV33 当时的实现仍在通知 goroutine 内部捕获 `time.Now().UnixMilli()`，并未真正做到"事件发生时"捕获；该缺陷由 REV51 修正（捕获点提前到 re-check 时刻的 `m.mu` 锁下，严格早于任何并发 `Register` 的 `online` 时间戳）。
  - 测试覆盖：毫秒时间戳写入、过期 `offline` 不覆盖较新的 `online`、同毫秒事件不覆盖、`updateDeviceStatus` 按请求时间戳处理、`notifyDeviceStatus` 携带 `last_seen`。
- **迁移说明**: 数据库表结构不变（`last_seen INTEGER`）。`getDeviceStatusDB` 已添加向后兼容逻辑：读取到小于 `1e12` 的值时自动视为秒级并乘以 1000 转换为毫秒，因此无需停机即可兼容旧数据。若需要一次性统一存量数据的单位为毫秒，可执行：`UPDATE device_status SET last_seen = last_seen * 1000;`。

### REV34: server/api GET `/api/pair/:code` 缺少限流 [已修复]
- **修复状态**: 已修复
- **修复难度**: 低
- **位置**: `server/api/main.go` (`GET /api/pair/:code` 路由, `rateLimitMiddleware`, `rateLimitKey`)
- **问题描述**: REV28 已为该路由补充认证，但仍无速率限制。已认证用户仍可高频枚举 6 位配对码，存在信息泄露和会话探测风险。
- **风险**: **中**。认证后仍可遍历配对码空间，获取其他工程师/设备的配对会话信息
- **修复方式**:
  - 新增 `rateLimitMiddleware` Gin 中间件，复用 `Server.rateLimiter`（`server/shared/ratelimit/ratelimit.go`）。
  - 限流 key 优先取认证身份：JWT `sub` 用 `jwt:<sub>` 作 key；Internal API Key 用 `internal` 作 key；无身份时回退 `ClientIP()`。
  - 在 `GET /api/pair/:code` 路由上挂载 `internalOrUserAuthMiddleware()` + `rateLimitMiddleware()` + `getPairingSession`。
  - 超限时返回 `429 Too Many Requests`，响应体包含 `ErrRateLimitExceeded` 错误信息。
  - 在 `server/api/main_test.go` 中补充测试：正常请求通过、超限返回 429、JWT 与 Internal API Key 分别限流、不同身份使用独立限流桶。

### REV35: Android `DebugDetector.getprop` 超时顺序失效 [已修复]
- **修复状态**: 已修复
- **修复难度**: 低
- **位置**: `android/app/src/main/java/com/netproxy/gateway/security/DebugDetector.kt` (`getprop` 回退路径)
- **问题描述**: `getprop` 回退路径先调用 `reader.readLine()` 再调用 `process.waitFor(timeout)`。如果 `getprop` 子进程卡住或不输出换行，`readLine()` 会无限阻塞，超时参数无法生效。
- **风险**: **中**。调试检测可能冻结 UI 线程或后台检测协程，影响应用响应
- **修复方式**: 将 `getprop` 读取封装到 `readProcessOutput(command)`：在独立守护线程中执行 `BufferedReader.readLine()`，通过 `Future.get(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)` 限制读取本身；超时或异常时取消 Future 并强制销毁子进程与线程池，避免 `readLine()` 无限阻塞。

### REV36: server/api `compareAndUpdatePairingSessionDB` 未校验会话是否已过期 [已修复]
- **修复状态**: 已修复
- **修复难度**: 低
- **位置**: `server/api/main.go` (`compareAndUpdatePairingSessionDB`)
- **问题描述**: `compareAndUpdatePairingSessionDB` 的乐观锁 WHERE 条件仅检查 `code = ? AND status = ? AND engineer_id = ?`，未包含 `expires_at > ?`。如果会话在读取后已经过期，并发请求仍可能将其成功更新为 `connected`，绕过过期检查。
- **风险**: **高**。过期的配对会话可能被错误地激活，导致安全风险
- **修复方式**: 在 WHERE 条件中增加 `AND expires_at > ?` 并使用当前时间作为参数；返回 `ErrConcurrentModification` 时同时覆盖"已过期"场景。补充针对过期会话的单元测试。

### REV37: server/shared/recovery example_test 示例质量 [未修复]
- **修复状态**: 未修复
- **修复难度**: 低
- **位置**: `server/shared/recovery/example_test.go`
- **问题描述**: Copilot review 指出 `ExampleRecover` 使用 `WithNamedReturn(&n, &err, ...)` 但接收的是普通局部变量而非命名返回值，示例误导；`ExampleRecoverAction` 的 action 函数没有可观察行为，示例失去演示意义。
- **风险**: **低**。仅影响文档/示例可读性
- **修复方式**: 修正 `ExampleRecover` 使用真正的命名返回值；为 `ExampleRecoverAction` 的 action 添加可观察副作用并补充 `// Output:`。

### REV38: DebugDetector 异常处理与测试稳定性 [未修复]
- **修复状态**: 未修复
- **修复难度**: 低
- **位置**: `android/app/src/main/java/com/netproxy/gateway/security/DebugDetector.kt`、`DebugDetectorTest.kt`
- **问题描述**: Copilot/CodeRabbit review 指出 `DebugDetector` 中 `catch (e: Exception)` 可能触发 detekt `SwallowedException`；`DebugDetectorTest.checkDebugProperties_doesNotThrow_inUnitTestEnvironment` 断言 `assertFalse(...)`，受宿主机属性影响，测试可能不稳定。
- **风险**: **低**。代码风格与测试稳定性问题
- **修复方式**: 具体化捕获的异常类型或为 `catch` 块添加注释说明；将受环境影响的测试改为注入可控属性或使用更稳定的断言。

### REV39: `TestUpdatePairingSession_ConcurrentModificationReturns409` 可能不稳定 [未修复]
- **修复状态**: 未修复
- **修复难度**: 中
- **位置**: `server/api/main_test.go` (`TestUpdatePairingSession_ConcurrentModificationReturns409`)
- **问题描述**: Copilot review 指出该测试使用 "至少一次 409" 断言，并发赛跑结果依赖调度，存在 CI 不稳定风险。
- **风险**: **低**。测试偶发失败会增加维护成本
- **修复方式**: 使用确定性同步（如 barrier 或 hook）替代概率性断言，或增加重试次数并明确失败阈值。

### PR16-1: `server/api/Dockerfile` 使用 `CGO_ENABLED=0` 导致 SQLite 驱动无法运行 [未修复]
- **修复状态**: 未修复
- **修复难度**: 低
- **提交哈希**: `7fbe5e9`
- **位置**: `server/api/Dockerfile` (L8)
- **问题描述**: PR #16 的 Codex review 指出，API 服务依赖 `mattn/go-sqlite3`，该驱动需要 CGO。`Dockerfile` 中使用 `CGO_ENABLED=0` 编译出的二进制在容器内启动时会因无法加载 SQLite 驱动而失败。
- **风险**: **高**。服务端 Docker 镜像无法运行，阻塞容器化部署。
- **修复方式**: 移除 `CGO_ENABLED=0`，或迁移到纯 Go 的 SQLite 驱动（如 `modernc.org/sqlite`）。

### PR16-2: `server/docker-compose.yml` build context 未包含 `shared` 本地模块 [未修复]
- **修复状态**: 未修复
- **修复难度**: 低
- **提交哈希**: `7fbe5e9`
- **位置**: `server/docker-compose.yml` (L12, L44)
- **问题描述**: PR #16 的 Codex review 指出，各服务的 `build.context` 仅指向各自子目录（如 `./api`），但 `go.mod` 通过 `replace` 依赖上层或同层的 `shared` 模块，导致 `docker compose build` 时找不到本地替换模块而失败。
- **风险**: **高**。Docker Compose 无法构建服务。
- **修复方式**: 将 `build.context` 设置为 `server/` 根目录，并在各 `Dockerfile` 中调整 `COPY` 路径；或重新组织模块以消除本地 `replace`。

### PR16-3: `server/docker-compose.yml` TLS 健康检查仍默认使用 HTTP [未修复]
- **修复状态**: 未修复
- **修复难度**: 低
- **提交哈希**: `7fbe5e9`
- **位置**: `server/docker-compose.yml` (L35)
- **问题描述**: PR #16 的 Codex review 指出，`healthcheck` 的默认 URL 是 `http://localhost:8080/health`。当 `ENABLE_TLS=true` 时，HTTP 请求会被拒绝，健康检查始终失败。
- **风险**: **中**。启用 TLS 后容器被误判为不健康，导致服务反复重启。
- **修复方式**: 健康检查根据 `ENABLE_TLS` 自动切换 `https://` 协议，或单独提供 `/health` 的 HTTP _plain_ 端点。

### PR16-4: Release 构建未强制要求 `MQTT_TLS_PUBLIC_KEY_PINS_RELEASE` [未修复]
- **修复状态**: 未修复
- **修复难度**: 低
- **提交哈希**: `7fbe5e9`
- **位置**: `android/app/build.gradle.kts` (L60-L62, L95)
- **问题描述**: PR #16 的 Codex review 指出，`mqttTlsPublicKeyPinsRelease` 在未配置时会静默回退到 `mqttTlsPublicKeyPinsDebug`（L61），release 构建不会失败。虽然运行时 `MqttConnectionManager` 会抛异常阻止启动，但缺少构建期强制检查。
- **风险**: **中**。Release 包可能因配置遗漏在运行时崩溃，应像 `MQTT_BROKER_URL_TLS_RELEASE` 一样在构建阶段 fail-fast。
- **修复方式**: 在 `validateReleaseConfig` 中增加对 `MQTT_TLS_PUBLIC_KEY_PINS_RELEASE` 非空校验，未配置时抛出 `GradleException`。

### PR16-5: `VpnService` 核心路径存在多处设计缺陷 [未修复]
- **修复状态**: 未修复
- **修复难度**: 高
- **提交哈希**: `7fbe5e9`
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt`、`ConnectionSessionManager.kt`、`VpnPacketProcessor.kt`
- **问题描述**: PR #16 的 Codex review 指出 VPN 核心路径存在多项基础缺陷：TCP SYN 无 payload 被丢弃、DNS 响应未回注、UDP 走 SOCKS CONNECT、源 IP 硬编码 10.0.0.1、TCP 逐包开新 Socket 等。这些问题与 `docs/TECH_DEBT.md` 中 C1/C3 多网络风险一致。
- **风险**: **高**。VPN 核心功能不稳定，多网络/双 WiFi/Link Turbo 等场景下可能出现路由异常或连接失败。
- **修复方式**: 统一评估 VPN 数据路径，引入 `Network.bindSocket()` 与多网络感知路由；将大文件拆分为 `PacketParser`、`ConnectionManager` 等模块（参见 `docs/TECH_DEBT.md` C1/C3 与 `docs/ISSUES.md` N2）。

---

## 提交后正确性检查发现（2026-07-03，审查 PR #57/#58 分支）

> 以下问题由提交后正确性检查在 `fix/rev31-36-post-commit-review`（PR #58）和
> `fix/post-commit-review-rev43-rev47`（PR #57）分支上发现。REV34/REV36 的"修复"
> 本身引入了新的回归缺陷，经多个独立 subagent 复审确认。

### REV48: REV34 限流修复导致合法轮询被封锁 [已修复]
- **修复状态**: 已修复（本分支）
- **提交哈希**: `c514fc3`（引入），本分支修复
- **修复难度**: 低
- **位置**: `server/api/main.go` (`getPairingSession`, `rateLimitMiddleware`)
- **问题描述**: REV34 为 `GET /api/pair/:code` 挂载了 `rateLimitMiddleware`，复用的
  `server/shared/ratelimit` 是**失败计数器**语义（`Allow` 每次调用都递增计数，
  `Success` 才清零）。但 `getPairingSession` 在任何返回路径（200/410/404/500）都**未
  调用 `rateLimiter.Success`**，导致每次合法轮询都累加失败计数。默认配置
  `MaxAttempts=5, Window=5m, BlockDuration=15m`：工程师在前端轮询配对码状态时，5 次
  请求后即被封锁 15 分钟，远程协助流程完全中断。
- **触发场景**: 工程师打开配对页面，前端每 2-3 秒轮询 `GET /api/pair/:code` 获取会话
  状态。约 10-15 秒后（5 次请求）即收到 429，且封锁持续 15 分钟。这是**正常使用路
  径**下的必然触发，非边缘情况。
- **风险**: **严重**。100% 的合法轮询用户在 15 秒内被封锁 15 分钟，远程协助功能基
  本不可用。测试 `TestGetPairingSessionRateLimitedByJWTIdentity` 将此破坏性行为锁定
  为预期（5×200 后 429），进一步掩盖了问题。
- **修复方式**:
  - 在 `getPairingSession` 找到会话时（200 和 410 路径）调用
    `s.rateLimiter.Success(rateLimitKey(c))`，重置失败计数器。
  - 404（会话不存在）路径不清零计数器，保留暴力枚举配对码的防护能力。
  - 更新测试：`TestGetPairingSessionRateLimit_AllowsLegitimatePolling` 验证 20 次合
    法轮询不被限流；`TestGetPairingSessionRateLimit_BlocksBruteForce` 验证 5 次 404
    后第 6 次被限流。

### REV49: REV36 `expires_at > ?` 条件导致 `markSessionExpired` 永久失败 [已修复]
- **修复状态**: 已修复（本分支）
- **提交哈希**: `f189aac`（引入），本分支修复
- **修复难度**: 低
- **位置**: `server/api/main.go` (`compareAndUpdatePairingSessionDB`, `markSessionExpired`)
- **问题描述**: REV36 在 `compareAndUpdatePairingSessionDB` 的 WHERE 条件中增加了
  `AND expires_at > ?`（`now`），声称"防止过期的配对会话被错误地激活"。但
  `markSessionExpired` 的唯一调用时机是 `time.Now().After(session.ExpiresAt)` 为真
  （即会话已过期）时，此时 `expires_at > now` **恒为假**，导致 UPDATE 影响 0 行，
  永远返回 `ErrConcurrentModification`。后果：
  1. 会话状态永远不会被写入为 `"expired"`（DB 中保持原状态如 `"pending"`）。
  2. `updatePairingSession` 的过期分支将 `ErrConcurrentModification` 映射为 **409
     Conflict**，而非预期的 **410 Gone**——这是用户可感知的状态码回归。
  3. `getPairingSession` 的过期分支走 REV31 的回退路径（重新查询），虽然最终能返回
     410，但多了一次 DB 查询且 `markSessionExpired` 仍未生效。
- **触发场景**: 配对码 5 分钟过期后，工程师或客户端尝试更新该会话（PUT
  `/api/pair/:code`），收到 409 Conflict 而非 410 Gone，客户端可能误判为并发冲突
  并重试，形成无效重试循环。
- **风险**: **高**。过期会话状态不持久化导致数据不一致；`updatePairingSession` 状态
  码回归（410→409）影响客户端逻辑。测试
  `TestCompareAndUpdatePairingSessionDB_ExpiredSessionReturnsConcurrentModification`
  将此破坏性行为锁定为预期，进一步掩盖了问题。
- **修复方式**:
  - 从 `compareAndUpdatePairingSessionDB` 的 WHERE 条件中移除 `AND expires_at > ?`
    及对应的 `now` 变量，恢复为仅检查乐观锁不变量（`code + status + engineer_id`）。
  - 过期保护由调用方的显式 `time.Now().After(session.ExpiresAt)` 检查提供（所有调用
    点在调用 `compareAndUpdatePairingSessionDB`/`markSessionExpired` 前均有此检查）。
  - 更新测试：`TestCompareAndUpdatePairingSessionDB_DoesNotRejectExpiredSession` 验证
    过期会话可被成功更新；`TestMarkSessionExpired_SucceedsForExpiredSession` 验证
    `markSessionExpired` 成功写入 `"expired"` 状态；
    `TestUpdatePairingSession_ExpiredReturns410Gone` 验证 HTTP 层返回 410 Gone。
- **注**: PR #57（`fix/post-commit-review-rev43-rev47`）的
  `compareAndUpdatePairingSessionDB` 本就没有 `expires_at > ?` 条件，因此不受此问题
  影响。两个 PR 在此函数上存在合并冲突，需协调合并顺序。

### REV50: PR #57/#58 合并冲突需协调 [未修复]
- **修复状态**: 未修复（需人工协调）
- **位置**: `server/api/main.go`, `server/tunnel/main.go`,
  `android/app/src/main/java/com/netproxy/gateway/security/DebugDetector.kt` 等 7 个文件
- **问题描述**: PR #57（REV43-47）和 PR #58（REV31-36）在以下关键区域存在冲突：
  1. `compareAndUpdatePairingSessionDB`：PR #57 无 `expires_at > ?`（正确），
     PR #58 有（REV49 缺陷）。
  2. `getPairingSession`：PR #57 的 REV44 将 `ErrConcurrentModification` 分支直接返
     回 410；PR #58 的 REV31 采用重新查询回退逻辑。两者语义不同。
  3. `createSessionToken`：PR #57 的 REV43 增加过期检查+乐观锁；PR #58 无此变更。
  4. `cleanupDeadTunnelsOnce`：PR #58 有 REV32 替换检查但缺 REV45 的循环内 `stopMu`
     保护；PR #57 有 REV45 但缺 REV32。
  5. `DebugDetector.kt`：PR #57 的 REV47 改变了 3 个测试的契约但未更新测试。
- **风险**: **中**。直接合并会导致部分修复丢失或编译失败。
- **建议**: 以 PR #58 为基础合并 PR #57，逐文件解决冲突，确保：
  - `compareAndUpdatePairingSessionDB` 不含 `expires_at > ?`（采用 PR #57 版本或本分
    支修复）。
  - `getPairingSession` 采用 REV31 的重新查询回退逻辑（更健壮）。
  - `cleanupDeadTunnelsOnce` 同时包含 REV32 替换检查和 REV45 循环内 `stopMu` 保护。
  - 更新 DebugDetector 的 3 个测试以匹配 REV47 的新契约。

---

## 提交后正确性检查发现（2026-07-02，审查过去 24 小时提交）

> 以下问题由多 subagent 对过去 24 小时内各分支的提交进行深度审查发现。

### REV43: `createSessionToken` 缺少过期检查 + TOCTOU 竞态可致已失效会话颁发令牌 [已修复]
- **修复状态**: 已修复
- **修复难度**: 中
- **位置**: `server/api/main.go` (`createSessionToken`, L1021-1100)
- **问题描述**: `createSessionToken` 存在两个缺陷：(1) 完全缺少 `session.ExpiresAt` 过期检查，与 `getPairingSession` 和 `updatePairingSession` 的行为不一致。已过期但尚未被清理的 session（status 仍为 "connected"）可成功创建 token。(2) 读取-验证与 token 创建之间无乐观锁保护，session 状态可能在此窗口内被并发修改（如过期、断开）。`session_tokens` 表无外键约束关联 `pairing_sessions`，token 一旦创建即独立有效，不交叉验证 pairing session 状态。
- **触发场景**: (1) Session 在时间 T 过期，清理 worker 每 5 分钟运行一次；(2) 在 T 到 T+5min 的窗口内，session 仍存在于数据库中，status 仍为 "connected"；(3) 工程师调用 POST `/api/session/token`，`createSessionToken` 不检查 `ExpiresAt`，成功创建 token；(4) 清理 worker 删除过期 session 后，token 仍然有效（session_tokens 表独立，无外键）
- **风险**: **中高**。可为已失效的 pairing session 创建有效 token，token 在 15 分钟 TTL 内对 SOCKS5 代理服务有效，违背"session 无效则不应颁发 token"的安全不变量
- **修复方式**: (1) 添加 `time.Now().After(session.ExpiresAt)` 过期检查，与其他 handler 保持一致；(2) 在创建 token 前使用 `compareAndUpdatePairingSessionDB` 乐观锁验证 session 状态未变，检测到并发修改返回 HTTP 409 Conflict

### REV44: `getPairingSession` 并发修改路径为过期 session 返回 200 OK [已修复]
- **修复状态**: 已修复
- **修复难度**: 低
- **位置**: `server/api/main.go` (`getPairingSession`, L834-871)
- **问题描述**: `getPairingSession` 在检测到 session 过期后调用 `markSessionExpired`，当 `markSessionExpired` 因乐观锁返回 `ErrConcurrentModification` 时（session 被并发修改，如另一个请求将 status 改为 "connected"），代码重新从 DB 获取 session 并返回 200 OK。但此时 session 的 `ExpiresAt` 已过，在业务语义上不应返回 200。客户端可能误认为 session 仍然有效。
- **触发场景**: (1) Session 的 `expires_at` 已过去，状态为 "pending"；(2) GET `/api/pair/:code` 判断已过期，调用 `markSessionExpired`；(3) 并发的 PUT 请求将 session 更新为 `status=connected`；(4) `markSessionExpired` 因 WHERE 条件不匹配返回 `ErrConcurrentModification`；(5) 代码重新获取 session，得到 `status=connected, expires_at=已过期`；(6) 返回 200 OK，附带时间上已过期但状态为 connected 的 session
- **风险**: **中**。客户端可能误用过期 session，尤其在 `internalOrUserAuthMiddleware` 保护下，认证用户获取到看似有效的过期 session 信息
- **修复方式**: `ErrConcurrentModification` 分支中直接返回 410 Gone，因为已经确定 session 按 `ExpiresAt` 已过期，无论并发修改了什么字段

### REV45: `cleanupDeadTunnelsOnce` 中 `wg.Add(1)` 无 `stopMu` 保护导致 WaitGroup 重用 panic [已修复]
- **修复状态**: 已修复
- **修复难度**: 低
- **位置**: `server/tunnel/main.go` (`cleanupDeadTunnelsOnce`, L476-517)
- **问题描述**: `cleanupDeadTunnelsOnce` 在函数入口检查 `m.stopped` 后释放 `stopMu`，但在后续循环中调用 `m.wg.Add(1)` 时未重新检查 `m.stopped`。如果 `Stop()` 在 `stopMu.Unlock()` (L483) 和 `wg.Add(1)` (L506) 之间被调用，`Stop()` 设置 `stopped=true` 并调用 `wg.Wait()`。若 `wg.Wait()` 返回（计数器归零）后 `cleanupDeadTunnelsOnce` 才执行 `wg.Add(1)`，将触发 `panic: sync: WaitGroup is reused before previous Wait has returned`。与 `Register`/`Unregister` 中已修复的同类问题（REV26）模式一致，但 `cleanupDeadTunnelsOnce` 遗漏了修复。
- **触发场景**: (1) 后台清理 goroutine 调用 `cleanupDeadTunnelsOnce`，通过 stopped 检查；(2) 在 `stopMu.Unlock()` 和 `wg.Add(1)` 之间，服务关闭调用 `Stop()`；(3) `Stop()` 设置 `stopped=true`，调用 `wg.Wait()` 并返回；(4) `cleanupDeadTunnelsOnce` 的 `wg.Add(1)` 在 `wg.Wait()` 返回后执行 → panic
- **风险**: **高**。服务关闭时可能 panic，不可恢复
- **修复方式**: 在 `wg.Add(1)` 前获取 `m.stopMu` 并检查 `m.stopped`，若已停止则 `continue` 跳过。与 `Register`/`Unregister` 模式一致

### REV46: DebugDetector `readLine()` 阻塞导致超时机制失效 [已修复]
- **修复状态**: 已修复
- **修复难度**: 中
- **位置**: `android/app/src/main/java/com/netproxy/gateway/security/DebugDetector.kt` (`readPropertyViaProcess`, L329-365)
- **问题描述**: `readPropertyViaProcess` 中 `reader.readLine()` 在 `process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)` 之前执行。如果 `getprop` 子进程挂起且不产生任何输出（如系统属性服务 `init` 进程无响应），`readLine()` 将无限期阻塞，3 秒超时机制完全失效，调用线程被永久阻塞。
- **触发场景**: (1) 系统异常（如属性服务 `init` 进程无响应），`getprop` 命令挂起；(2) `readLine()` 阻塞等待子进程输出；(3) `waitFor(3s)` 永远不被执行，超时机制失效；(4) 调用线程被永久阻塞，可能导致 ANR
- **风险**: **中高**。实际触发概率较低（`getprop` 通常很快返回），但一旦触发，调用线程被永久阻塞且无恢复手段
- **修复方式**: 将输出读取放在独立线程中执行，主线程先调用 `waitFor(timeout)` 实现真正的超时控制。超时后调用 `destroyForcibly()` 终止子进程并 `waitFor()` 回收僵尸进程

### REV47: DebugDetector 反射返回非 null 值时跳过 getprop 交叉验证，Frida hook 可绕过 debug 检测 [已修复]
- **修复状态**: 已修复
- **修复难度**: 中
- **位置**: `android/app/src/main/java/com/netproxy/gateway/security/DebugDetector.kt` (`resolveDebugPropertiesState`, L272-306; `readDebugPropertyValue`, 原 L301-319)
- **问题描述**: 原 `readDebugPropertyValue` 在反射调用成功且返回非 null 值时，直接返回该值，永远不会调用 getprop 子进程做交叉验证。攻击者可通过 Frida hook `android.os.SystemProperties.get()` 对三个 debug 属性返回安全值（如 `ro.debuggable="0"`, `ro.secure="1"`, `persist.sys.usb.config="mtp"`），反射调用成功（不抛异常）且返回非 null 值，代码直接采用，getprop 回退路径不可达，完全绕过 debug 属性检测。
- **触发场景**: (1) 攻击者在 root 设备上使用 Frida hook `android.os.SystemProperties.get()`；(2) 对三个 debug 属性返回安全值；(3) `readDebugPropertyValue` 中反射返回非 null → 直接返回，getprop 不被调用；(4) debug 检测被完全绕过
- **风险**: **高**。安全关键功能被绕过，攻击者可在不被检测的情况下进行调试
- **修复方式**: 重构 `resolveDebugPropertiesState` 为始终同时调用反射和 getprop，取两者的"并集"结果——任一来源检测到 debug 特征即报告。新增 `isDebugPropertyValue` 和 `readPropertyValueViaReflection` 方法替代原有的 `readDebugPropertyValue`

## 2026-07-13 提交后正确性检查发现（过去 24h 提交/PR 审查）

### CI-MASK-1: `go-ci.yml` 单步 `continue-on-error: true` 静默吞没 `go build` + `go test` 失败，破坏 Go 侧合并门禁 [已修复]
- **状态**: 已修复（分支 `fix/pr80-ci-masking-regression`）
- **发现位置**: PR #80 `palette/diagnostics-card-ux-improvement-9614842658949896577`（base: `main`）；CI 重构提交 `19b8434`/`dbf2f9d`（亦存在于 PR #79 `palette/auto-submit-pairing-code-11146188363273715457`）
- **影响文件**: `.github/workflows/go-ci.yml`（步骤 "CI checks for ${{ matrix.component }}"，原 L110-115）、`Makefile`（`go-ci-component` 目标，原 L182-197）
- **问题描述**: PR #80 将原 `ci.yml` 中**分步且无 `continue-on-error`** 的 `go build` 与 `go test` 步骤合并为单个 `make go-ci-component` 调用，并在该步骤上加 `continue-on-error: true`。Makefile 中 `go-ci-component` 目标在**同一 recipe** 内用 `&&` 链接 `go build -v ./... && go test -v ... && gofmt && go vet && go mod tidy`。因此 `continue-on-error: true` 不仅掩盖 fmt/vet/tidy（ADR-006 L294 声明的基线收集意图），**也掩盖 `go build` 与 `go test` 的失败**。结果：任何破坏 Go 编译或单元测试的 PR 仍能绿灯合并，`Go Server Build (<component>)` 这个 required check 形同虚设。步骤内联注释 `# quality checks (fmt/vet/tidy) still collecting baseline` 与 ADR-006 L294 都明确将掩盖范围限定为 fmt/vet/tidy，实现与文档/意图不一致。
- **触发场景**: 贡献者提交一个破坏 `server/api` 编译的 PR（如引入语法错误、删除被引用的符号）→ `make go-ci-component COMPONENT=api` 中的 `go build` 失败 → recipe 退出非零 → 步骤因 `continue-on-error: true` 被标记为 success → job 结论为 success → required check `Go Server Build (api)` 绿灯 → PR 合并 → 主干构建损坏。
- **风险**: **严重**。Go 侧合并门禁完全失效；编译错误、单元测试回归、数据完整性问题、并发缺陷等均可绕过审查流入主干。与 `main` 基线相比是明确退化（`main` 的 `ci.yml` 中 `go build`/`go test` 为独立硬门禁步骤，无 `continue-on-error`）。
- **根因**: Makefile recipe 的 `&&` 链式语义与 workflow 步骤级 `continue-on-error` 的作用域混淆——前者使整个 recipe 成为一个退出码，后者将该退出码映射为步骤成功。
- **验证**: `git show origin/main:.github/workflows/ci.yml | grep -B2 -A2 continue-on-error` 确认 `main` 上 Go build/test 步骤无 `continue-on-error`。`git show origin/palette/...:Makefile | sed -n '182,198p'` 确认 `go-ci-component` 在单一 recipe 内 `&&` 链接 build+test+quality。`git show origin/palette/...:.github/workflows/go-ci.yml | sed -n '110,115p'` 确认 `continue-on-error: true` 覆盖整个 `make go-ci-component` 调用。
- **修复方式**:
  1. `Makefile`: 将 `go-ci-component` 拆分为三个独立目标——`go-build-component`（仅 `go build`，硬门禁）、`go-test-component`（仅 `go test`，硬门禁，生成 `coverage.out`）、`go-quality-component`（gofmt+vet+tidy，按 ADR-006 保留 `continue-on-error`）。保留原 `go-ci-component` 用于本地聚合。
  2. `go-ci.yml`: 将单个 "CI checks" 步骤拆为三步——"Build" 与 "Test" 无 `continue-on-error`（硬门禁），"Quality checks" 保留 `continue-on-error: true`（ADR-006 基线）。Test 步骤加 `if: success()` 避免 build 失败后无意义执行；Quality 步骤用 `if: always()` 以便即使 test 失败仍收集基线。coverage 上传加 `if-no-files-found: ignore`（build/test 失败时不产生 coverage.out）。
- **回归测试**: 扩展 `scripts/check_ci_permissions.py` 新增 `check_masking()` 函数，断言 `go-ci.yml` 中运行 `go-build-component`/`go-test-component` 的步骤、以及 `android-ci.yml` 中运行 `lintDebug`/`dependencyCheckAnalyze` 的步骤**不得**有 `continue-on-error: true`。已在 `pr-checks.yml` 新增 `ci-config-guard` job 运行 `make ci-perms-check`，使该回归 guard 在每个 PR 上强制执行。已验证：修复后通过；临时恢复 masking 后 guard 报错。
- **关联**: ADR-006 L294（声明 fmt/vet/tidy 基线掩盖意图）、CI-PERM-1（同一 guard 脚本的权限检查）。

### CI-MASK-2: `android-ci.yml` 对 `lintDebug` 与 `dependencyCheckAnalyze` 误加 `continue-on-error: true`，关闭 Lint 与 CVSS≥9.0 漏洞的硬门禁 [已修复]
- **状态**: 已修复（分支 `fix/pr80-ci-masking-regression`）
- **发现位置**: PR #80 `palette/diagnostics-card-ux-improvement-9614842658949896577`（base: `main`）；CI 重构提交（亦存在于 PR #79）
- **影响文件**: `.github/workflows/android-ci.yml`（L100-113，四个连续 Gradle 步骤）
- **问题描述**: `android-ci.yml` 在四个连续 Gradle 步骤上加 `continue-on-error: true`：
  - L98 `testDebugUnitTest` —— **有 N87 注释**，文档授权降级（测试挂死根因待查）。✓
  - L103 `jacocoTestReport` —— 无注释、无授权。
  - L108 `lintDebug` —— 无注释、无授权。
  - L113 `dependencyCheckAnalyze` —— 无注释、无授权。这是 OWASP Dependency-Check，`build.gradle.kts` 配置 `failBuildOnCVSS = 9.0f`，即仅 CVSS≥9.0 的严重漏洞才失败。
  N87（`docs/ISSUES.md` L818-832）与 ADR-006（`docs/DECISIONS.md` L292）**仅授权** `testDebugUnitTest` 步骤的降级。JaCoCo/Lint/dep-check 的 masking 完全未文档化。与 `main` 基线相比是退化——`main` 的 `ci.yml` 中这三步均为 `continue-on-error: false`（硬门禁）。`security.yml` 的 `android-dep-check` job 文件头明确标注 "NOT a required check" 且按 `paths:` 触发，故 `dependencyCheckAnalyze` 是 Android 侧**唯一**的 CVSS≥9.0 漏洞硬门禁；masking 后无任何硬门禁拦截严重漏洞依赖。
- **触发场景**: 贡献者提交一个 PR，将 Android 依赖升级到含已知 CVE（CVSS≥9.0）的版本（如 Paho MQTT、Netty、Bouncy Castle）→ `dependencyCheckAnalyze` 失败 → 步骤因 `continue-on-error: true` 被标记 success → `Android Build & Test` required check 绿灯 → PR 合并 → 含严重漏洞的依赖流入 release 构建。
- **风险**: **高**（安全）。严重漏洞依赖（CVSS≥9.0）可绕过 CI 合并；同时 `security.yml` 不作为 required check，故无任何地方硬门禁。Lint masking 风险较低（`build.gradle.kts` 设 `abortOnError = false`，`lintDebug` 始终退出 0），但移除 masking 可作为 defense-in-depth：若未来有人将 `abortOnError` 改为 `true`，masking 会静默吞没 Lint 错误。
- **根因**: CI 重构时将 `ci.yml` 拆分为 `android-ci.yml`，复制了 N87 的 `continue-on-error: true` 到相邻步骤但未加注释或文档说明。
- **验证**: `git show origin/main:.github/workflows/ci.yml | grep -B1 -A1 continue-on-error` 确认 `main` 上 JaCoCo/Lint/dep-check 为 `continue-on-error: false`。`git show origin/palette/...:.github/workflows/android-ci.yml | grep -n continue-on-error` 确认 PR #80 上四处 `continue-on-error: true`，仅 L98 有 N87 注释。`git show origin/palette/...:.github/workflows/security.yml | head -1` 确认 "NOT a required check"。`git show origin/palette/...:android/app/build.gradle.kts | grep -E "abortOnError|failBuildOnCVSS"` 确认 `abortOnError = false` 与 `failBuildOnCVSS = 9.0f`。
- **修复方式**:
  1. `lintDebug`（L108）：移除 `continue-on-error: true`。`abortOnError = false` 保证 `lintDebug` 退出 0，不会阻塞 PR；移除 masking 仅作为 defense-in-depth。
  2. `dependencyCheckAnalyze`（L113）：移除 `continue-on-error: true`。`failBuildOnCVSS = 9.0f` 仅在 CVSS≥9.0 时失败，是合理的保守阈值；self-hosted runner 缓存 NVD 数据库后首次运行慢的问题可由 `timeout-minutes: 45` 兜底。
  3. `jacocoTestReport`（L103）：**保留** `continue-on-error: true`，添加注释说明为 N87 下游影响（test 被 timeout kill 后 `.exec` 数据可能不完整，coverage 报告生成是装饰性而非合并门禁）。
- **回归测试**: 同 CI-MASK-1，`scripts/check_ci_permissions.py` 的 `check_masking()` 同时断言 `android-ci.yml` 中 `lintDebug` 与 `dependencyCheckAnalyze` 步骤不得有 `continue-on-error: true`。
- **关联**: N87（testDebugUnitTest 降级授权）、ADR-006 L292（Android 测试降级）、CI-MASK-1（同一修复 PR 的 Go 侧对应问题）。

### CI-MASK-3: `go-ci.yml` 的 `govulncheck` 步骤 `continue-on-error: true`，Go 侧无任何严重漏洞硬门禁 [待评估]
- **状态**: 待评估（本 PR 暂未修复——需先收集 Go 漏洞基线，避免一次性阻塞所有 PR）
- **发现位置**: PR #80 `palette/diagnostics-card-ux-improvement-9614842658949896577`（base: `main`）
- **影响文件**: `.github/workflows/go-ci.yml`（L128-132，"Run vulnerability check" 步骤）
- **问题描述**: `go-ci.yml` 的 `govulncheck ./...` 步骤带 `continue-on-error: true`，无注释说明。`security.yml`（"NOT a required check"）的 Go 侧 `govulncheck` 同样非硬门禁。结合 CI-MASK-2，整个项目（Android + Go）无任何地方对依赖漏洞做硬门禁。`govulncheck` 报告所有已知漏洞（非仅 CVSS≥9.0），直接移除 masking 可能因既有漏洞一次性阻塞所有 Go PR。
- **风险**: **中-高**（安全）。Go 依赖的已知漏洞可绕过 CI 合并。
- **建议修复**: 先在 CI 上收集 `govulncheck` 基线（保留 `continue-on-error` 但记录输出），清零后收紧为硬门禁。或对 `govulncheck` 添加 `fail-on-severity` 类过滤（仅 CVSS≥9.0 失败），与 Android 侧 `failBuildOnCVSS = 9.0f` 对齐。ADR-006 L294 的 "质量检查渐进收紧" 策略应明确包含 `govulncheck`。
- **关联**: CI-MASK-1、CI-MASK-2、ADR-006 L294。

### CACHE-RESTORE-1: self-hosted runner 上 `actions/cache@v4` 恢复 Go module/build cache 时 tar 解压 "Cannot open: File exists" 警告 [已修复]
- **状态**: 已修复（PR #81 unmask CI-MASK-1 后暴露并修复）
- **影响文件**: `.github/workflows/go-ci.yml`（L88-97，新增 "Clean residual Go cache directories" step）
- **问题描述**: self-hosted runner（`runs-on: [self-hosted, Linux, X64, do-sfo3]`）上 `/tmp/go-mod-${component}` 与 `/tmp/go-build-${component}` 在 job 之间持久存在（`/tmp` 不像 GitHub-hosted runner 那样每次清理）。`actions/cache@v4` 在 cache hit 时用 `tar -xzf` 解压缓存到目标路径，遇到已存在的同名文件会输出 `##[error]/usr/bin/tar: .../xxx.go: Cannot open: File exists` 并以 exit code 2 失败，但 `actions/cache@v4` 把它降级为 `##[warning]Failed to restore`（cache step conclusion 仍为 success，不阻塞 build）。后果是 cache 未恢复，每次 build 都重新下载 Go modules（约 30s 浪费）。
- **触发场景**: 任何 self-hosted runner 上 `actions/cache@v4` 恢复到非空目标目录；`Cleanup build cache` step 只删除 `GOCACHE`（`/tmp/go-build-*`）保留 `GOMODCACHE`（`/tmp/go-mod-*`），放大了下次 cache restore 的冲突概率。
- **修复**: 在 `Cache Go modules and build cache` step 之前新增 `Clean residual Go cache directories` step，无条件 `rm -rf /tmp/go-mod-${component} /tmp/go-build-${component}`，确保 cache restore 从空目录开始。
- **验证**: 修复前 PR #81 的 7 个 Go Server Build job 全部 FAILURE（但**根因是 MAKE-MISSING-1 而非本条目**——cache step 实际仍为 success，build step 因 `make: command not found` 失败）。修复后预期 cache warning 消除、module cache 正常恢复。
- **修复难度**: 低。新增 1 个 step，3 行 YAML（含 7 行注释，外加 chmod 一行）。
- **关联**: CI-MASK-1（unmask 暴露此问题）、MAKE-MISSING-1（真正导致 Go CI 失败的根因）、CI-MASK-3（同 mask 链路上的 govulncheck）。

### MAKE-MISSING-1: self-hosted runner 上 `make` 命令未安装且 sudo 无 NOPASSWD 导致 Go CI 与 ci-config-guard 失败 [已修复]
- **状态**: 已修复（PR #81 unmask CI-MASK-1 后暴露并修复）
- **影响文件**: `.github/workflows/go-ci.yml`（L128-176，删除 "Ensure make is installed" step，Build/Test/Quality step 改为直接调用 go 命令）、`.github/workflows/pr-checks.yml`（L48-64，ci-config-guard 改为直接调用 python3）
- **问题描述**: self-hosted runner（`runs-on: [self-hosted, Linux, X64, do-sfo3]`）上未预装 `make` 命令，且 `runner` 用户没有 NOPASSWD sudo 权限（`sudo: a password is required`）。`go-ci.yml` 原本调用 `make go-build-component` / `make go-test-component` / `make go-quality-component`（Makefile 通过 `comp_path()` 解析 `server/shared/*` 与 `server/*` 的路径），每个 Go matrix job 的 build step 报 `line 1: make: command not found` 并以 exit code 127 失败。`pr-checks.yml` 的 `ci-config-guard` 调用 `make ci-perms-check` 同样失败。CI-MASK-1 unmask 后立刻暴露为 7 个 Go matrix job + ci-config-guard 全部 FAILURE。
- **触发场景**: 任何调用 `make go-*-component` 的 Go CI job 或调用 `make ci-perms-check` 的 ci-config-guard job。
- **修复**: 不再依赖 make，直接在 workflow 中内联等价命令：
  - `go-ci.yml` Build step：`working-directory: ${{ matrix.path }}` + `go build -v ./...`（matrix.path 已提供完整路径，无需 Makefile 的 `comp_path()`）
  - `go-ci.yml` Test step：`working-directory: ${{ matrix.path }}` + `go test -v -coverprofile=coverage.out ./...`
  - `go-ci.yml` Quality step：内联 gofmt/vet/mod-tidy 逻辑（与 Makefile `go-quality-component` 一致）
  - `pr-checks.yml` ci-config-guard：`python3 scripts/check_ci_permissions.py`（Makefile `ci-perms-check` 的等价命令）
- **验证**: 修复前 7 个 Go matrix job 的 build step 全部 `exit code 127` + ci-config-guard `sudo: a password is required`（已通过 `gh run view 29589488519 --log-failed` 与 `gh run view 29589488535 --log-failed` 交叉验证）。修复后预期所有 Go matrix job 与 ci-config-guard 通过。
- **修复难度**: 中。删除 1 个 step（Ensure make），重写 3 个 step 的 run 命令（Build/Test/Quality），1 个 step 改用 python3。
- **根因**: runner provision 缺口（do-sfo3 droplet 镜像未包含 build-essential，且 runner 用户无 NOPASSWD sudo）。长期方案是在 runner provision 脚本中预装 `build-essential` 并配置 NOPASSWD sudo，本 PR 的"绕过 make"是更可靠的永久方案——CI 不应依赖 runner provision，workflow 应自包含。
- **关联**: CI-MASK-1（unmask 暴露此问题）、CACHE-RESTORE-1（同 PR 同步修复的次要 warning）。

### DEP-REVIEW-1: dependency-review job 失败因为 GitHub Dependency Graph 与 Advanced Security 未启用 [待评估]
- **状态**: 待评估（PR #81 暴露但未修复，需仓库管理员在 GitHub Settings 启用）
- **影响文件**: `.github/workflows/pr-checks.yml`（L32-42，dependency-review job）、GitHub 仓库 `01luyicheng/NetProxyGateway` 的 Settings → Security → Security overview
- **问题描述**: `pr-checks.yml` 的 `dependency-review` job 调用 `actions/dependency-review-action@v4`，但仓库未启用 GitHub Dependency Graph 与 Advanced Security，导致 action 报错：`Dependency review is not supported on this repository. Please ensure that Dependency graph is enabled along with GitHub Advanced Security`。
- **触发场景**: 任何触发 dependency-review job 的 PR。
- **风险**: **中-高**（安全）。dependency-review 是检测 PR 引入新依赖漏洞的关键门禁，未启用等同于无依赖审查。
- **建议修复**: 在 GitHub Settings → Code & automation → Code security → Security overview 启用：
  1. Dependency graph（免费功能，所有 public repo 应启用）
  2. GitHub Advanced Security（需付费 license 或 public repo 免费）
  - 或：如果暂时无法启用 GHAS，把 `dependency-review` job 改为 `continue-on-error: true` 并加注释说明，但这样会失去依赖审查能力。
- **关联**: CI-DEP-1（required status checks 未启用是此问题长期未被发现的结构性原因之一）。

### MAINSCREEN-DUP-IMPORT-1: `MainScreen.kt` 存在重复 import 导致 `compileDebugKotlin` 失败 [已修复]
- **状态**: 已修复（PR #81 unmask CI-MASK-2 后暴露并修复）
- **影响文件**: `android/app/src/main/java/com/netproxy/gateway/ui/screens/MainScreen.kt`（删除 L22 重复 `KeyboardActions` import 与 L43 重复 `LocalFocusManager` import）
- **问题描述**: `MainScreen.kt` 同时存在两组重复 import（基于 dev 分支原始行号）：
  - L20 与 L22：`import androidx.compose.foundation.text.KeyboardActions`
  - L43 与 L45：`import androidx.compose.ui.platform.LocalFocusManager`
  Kotlin 编译器报 `Conflicting import: imported name 'KeyboardActions' is ambiguous.`（CI 日志只输出第一处冲突，第二处 LocalFocusManager 因 Kotlin 编译器去重策略未单独报错但客观存在），`compileDebugKotlin` FAILED → `assembleDebug` FAILED → Android CI 红。dev 分支最近 5 次 Android CI run 全部 failure（自 PR #78 `🎨 Palette: 改进配对码输入的键盘交互` 起即开始失败），但被 `lintDebug`/`dependencyCheckAnalyze` 的 `continue-on-error: true`（CI-MASK-2）掩盖为"绿"——实际 `Build with Gradle` step 从未加 `continue-on-error`，但 PR 合并时未把 Android CI 列入 required status checks，故仍能合并。
- **触发场景**: 任何触发 `./android/gradlew -p android assembleDebug` 的 PR；自 PR #78 起每个 Android PR 的 CI 都失败，但因 required status checks 未启用而未阻塞合并。
- **修复**: 删除 L22（重复 `KeyboardActions`）与 L43（重复 `LocalFocusManager` 第一处），保留 L20 `KeyboardActions` 与 L45 `LocalFocusManager`（删除 L22 与 L43 后 L45 上移到 L43，共 -2 行位移，仍保持 `LocalContext` 在前 `LocalFocusManager` 在后的字母序）。`git diff` 共 -2 行，无新增逻辑。
- **验证**: 修复前 `e: file:///.../MainScreen.kt:20:41 Conflicting import` × 2，`compileDebugKotlin FAILED`；修复后预期 `assembleDebug` 通过。
- **修复难度**: 低。删除 2 行重复 import。
- **关联**: CI-MASK-2（unmask 暴露此问题）、CI-DEP-1（required status checks 缺口是此问题长期未被发现的结构性原因之一）。

### CI-DEP-1: GitHub required status checks 未启用，允许 CI 失败的 PR 合并 [待评估]
- **状态**: 待评估（PR #81 暴露但未修复，需独立 PR 处理 branch protection 配置）
- **影响文件**: GitHub 仓库 `01luyicheng/NetProxyGateway` 的 branch protection rules（不在仓库代码内）
- **问题描述**: `dev`/`main` 分支的 branch protection 未把 `Go CI`/`Android CI` 列为 required status checks。这导致 MAINSCREEN-DUP-IMPORT-1 自 PR #78（2026-07-11 合并）起让 dev 分支最近 5 次 Android CI 全部 failure，但 PR 仍能合并。同样地，CACHE-RESTORE-1 与 MAKE-MISSING-1 在 dev 上长期被 CI-MASK-1 掩盖（CI 显示为 success），即便 required status checks 启用也无法发现。
- **触发场景**: 任何 PR 合并到 `dev` 或 `main`。
- **风险**: **高**。CI 失败的 PR 可直接合并，违背 AGENTS.md 第 7 节"验证门禁（必须通过）"约定。
- **建议修复**: 在 GitHub Settings → Branches → Branch protection rules 中为 `dev`/`main` 启用 "Require status checks to pass before merging"，并把 `Go Server Build (api)`、`Go Server Build (socks5-proxy)`、`Go Server Build (tunnel)`、`Go Server Build (httpclient)`、`Go Server Build (ratelimit)`、`Go Server Build (recovery)`、`Go Server Build (stringutil)`、`Android Build & Test`、`ci-config-guard` 列为 required。注意必须先让 dev 上的 CI 全绿才能启用，否则现有失败 PR 会全部阻塞。**前置依赖**：(1) 需先修复 DEP-REVIEW-1（dependency-review job 当前失败），否则启用 required 后所有 PR 会被 dependency-review 阻塞；(2) 需等 self-hosted runner 切换回 GitHub runners 后 CGO-DETECT-1 自动消失，否则 `Go Server Build (api)` 的 Test step 会持续失败阻塞所有修改 `server/api/**` 的 PR。
- **关联**: CI-MASK-1、CI-MASK-2、MAINSCREEN-DUP-IMPORT-1、CACHE-RESTORE-1、MAKE-MISSING-1、DEP-REVIEW-1、CGO-DETECT-1。

### CGO-DETECT-1: self-hosted runner 缺少 gcc，导致 server/api 的 go-sqlite3 测试在 CI 中失败 [无需修复 — 临时 runner 问题]
- **状态**: 无需修复。当前 self-hosted runner 是临时使用，下个月将切换回 GitHub 提供的 Ubuntu runners（预装 build-essential/gcc），切换后此问题自动消失。
- **修复难度**: 无需投入。两条路径均**不应执行**：(a) 不在 self-hosted runner 上安装 gcc（临时环境不值得改动）；(b) 不切换 sqlite 驱动（避免不必要的代码变更和 SQL 占位符语法调整）。
- **影响文件**: `.github/workflows/go-ci.yml` 的 `Check gcc availability` step（保留不动，已正确）+ `server/api/` 的 sqlite 驱动选择（**不修改**）
- **问题描述**: PR #81 unmask CI-MASK-1 后，dev 上 `Go Server Build (api)` job 的 `Test api` step 持续失败（CI run 29603650822）。22+ 个数据库测试（TestServerCloseStopsCleanupWorkers、TestValidateSessionExpiredTokenDeleteFailureDoesNotLogRawToken、TestCreatePairingSession* 等）报 `Binary was compiled with 'CGO_ENABLED=0', go-sqlite3 requires cgo to work. This is a stub`。**Build step 通过**，仅 Test step 失败。
- **诊断证据**:
  - CI run 29603650822（dev commit `552f93e`）：`Go Server Build (api)` job 中 `Build api` ✅，`Test api` ❌。其他 6 个 Go 组件 ✅。
  - CI run 29595665762（PR #81 commit `1fc9f64`）：`go env` 输出 `CC='gcc'`（**Go 默认值，不代表 gcc 真的存在**）和 `CGO_ENABLED='0'`。
  - CI run 29627525005（PR #98 commit `2ec1408`，尝试硬编码 `CGO_ENABLED=1`）：`Build api` 直接失败，错误 `cgo: C compiler "gcc" not found: exec: "gcc": executable file not found in $PATH`。`Build tunnel` 和 `Build socks5-proxy` 同样失败（`runtime/cgo` 包需要 gcc）。
- **根因**: self-hosted runner 上**没有 gcc**。`go env` 输出 `CC='gcc'` 只是 Go 的默认配置值，不代表 gcc 真的存在于 PATH。GitHub 提供的 ubuntu-latest runners 预装 build-essential（包括 gcc），切换后 `Check gcc availability` step 会自动检测到 gcc 并设置 `CGO_ENABLED=1`。
- **`Check gcc availability` step 行为正确**: PR #81 的 `Check gcc availability` step 用 `command -v gcc &>/dev/null` 检测 gcc，因为 gcc 不存在所以返回非零退出码，执行 `else` 分支输出 `available=false`。表达式 `${{ steps.gcc.outputs.available == 'true' && '1' || '0' }}` 正确解析为 `'0'`，`Build api` 的 env `CGO_ENABLED: 0` 是正确的。Build 通过（go-sqlite3 用 stub 编译），Test 失败（stub 不能用）。**该 step 在切换回 GitHub runners 后将自动恢复正常工作**。
- **PR #98 第三轮诊断的错误**: 之前误以为 `steps.gcc.outputs.available` 未被 Build step 正确读取。这是错的。`Check gcc availability` step 实际工作正常，PR #98 试图硬编码 `CGO_ENABLED=1` 反而让 dev 从"1 个组件失败"退化到"3 个组件失败"（api/tunnel/socks5-proxy 的 Build 都失败）。PR #98 已关闭。
- **教训**: `go env` 输出的 `CC` 字段是 Go 的默认配置，**不能作为 gcc 是否存在的证据**，应该用 `command -v gcc` 或 `which gcc` 验证。
- **触发场景**: 任何修改 `server/api/**`、`Makefile`、`.github/workflows/go-ci.yml` 的 PR 在 self-hosted runner 期间会看到 `Go Server Build (api)` job 失败。**这是已知噪音，不应阻塞 PR 合并**（CI-DEP-1 required status checks 未启用，所以失败 job 不阻塞合并）。
- **风险**: 低。仅影响 `server/api` 的 Test step；Build 通过，不影响产物正确性。CI 红色噪音直到 runner 切换。
- **AI 执行决策指引**: **不要尝试修复此问题**。如果有人提议"硬编码 CGO_ENABLED=1"、"安装 gcc"、"切换到 modernc.org/sqlite"，应指向本条目并拒绝。等到 self-hosted runner 切换回 GitHub runners 即可。
- **关联**: CI-MASK-1（unmask 暴露此问题）、MAKE-MISSING-1（同 PR #81 CI 修复链）、CI-DEP-1（required status checks 未启用让此问题不阻塞合并，反而让 dev 能继续推进）。

### CI-GUARD-BYPASS-S1to3: `check_ci_permissions.py` 的 3 个边缘绕过路径 [S1+S2 已修复（REV54）；S3 未修复]
- **状态**: S1（引号键）+ S2（冒号前空格）已通过 REV54 修复（本 PR，分支 `fix/ci-guard-quoted-key-rev54`）。H1-H5（5 个主要绕过路径）此前已关闭并合入 dev。**S1 的“低风险”评级已被 PR #129 实证证伪**——详见下方 REV54。S3（伪前置 step 误导）仍未修复。
- **修复难度**: S1+S2 已修复（引入 `_key_line_pattern()` 正则匹配，处理引号键与冒号前空格）。S3 仍需扩展 `_parse_steps()` 的 step 归属逻辑，暂不修复。
- **影响文件**: `scripts/check_ci_permissions.py` 的 `_step_get_value()`、`check_dependency_review()` job 级扫描 + `scripts/test_check_ci_permissions.py` 新增 5 个回归测试（S1 单/双引号、S1 job 级、S2、值提取）
- **绕过路径**:
  - **S1（引号键）**: `'continue-on-error': true`（键用单引号包裹）。YAML 规范允许引号键，PyYAML 解析时作为字符串键。**已修复（REV54）**：原 `_step_get_value()` 用 `s.startswith(f"{key}:")` 匹配，引号键以 `'`/`"` 开头不匹配；job 级扫描同理。修复后用 `_key_line_pattern(key)` 正则 `^(?:'key'|"key"|key)\s*:` 匹配，引号键与裸键同等检测。**实证**：PR #129（commit `e41f70f`）正是用此形式夹带进 UI PR，旧守卫返回 exit 0（绕过），修复后返回 exit 1（FAIL）。
  - **S2（冒号前空格）**: `continue-on-error : true`（键与冒号间有空格）。YAML 规范允许冒号前后空格，PyYAML 接受。**已修复（REV54）**：`_key_line_pattern` 的 `\s*:` 同样覆盖冒号前空格。
  - **S3（伪前置 step 误导）**: 在 `dependency-review` step 之前放置一个无关 step（如 `- name: Print config` + `run: echo "fail-on-severity: high"`），让 `_parse_steps()` 从无关 step 中提取 `fail-on-severity`，从而让真正的 dependency-review step 缺失该字段也能通过。**未修复**（仍待处理）。
- **风险评估**: S1 已上调为**高**（被 PR #129 实际利用，证伪原“palette bot 不会用引号键”判断）。S2 理论上同等危险，已一并修复。S3 仍为低（需恶意构造且 `fail-on-severity` 缺失本就会报错，绕过收益有限）。
- **触发场景**: 攻击者（或越界 bot）提交 PR 试图绕过 dependency-review guard。PR #129 已证明 palette bot **会**用引号键等边缘语法——原“palette bot 不会用引号键/冒号空格等边缘语法”的判断已被证伪。
- **AI 执行决策指引**: S1+S2 已修复，无需再处理。S3 如未来发现实际绕过尝试再优先处理。
- **关联**: CI-DEP-1（dependency-review job 删除防护的原始需求）、CI-MASK-1/2/3（CI masking 系列）、REV54（S1 被利用 + 修复的完整记录，见下方）。

### REV54: PR #129 用引号键 `'continue-on-error': true` 绕过 dependency-review 安全门 + `check_ci_permissions.py` S1 守卫盲区 [待修复 PR #129；守卫已在本分支加固]
- **状态**: PR #129 待修复（已留阻塞评论 https://github.com/01luyicheng/NetProxyGateway/pull/129#issuecomment-5038190476 ，PR 尚未合并）。`check_ci_permissions.py` S1+S2 守卫盲区已在本分支（`fix/ci-guard-quoted-key-rev54`）修复并加回归测试。
- **提交哈希**: `e41f70f`（分支 `ux/crossfade-status-icons-2792098693352437974`，PR #129 head）
- **位置**: `.github/workflows/pr-checks.yml` L59-60；守卫盲区在 `scripts/check_ci_permissions.py` `_step_get_value()` L223 与 job 级扫描 L290/296（修复前）
- **问题描述**: PR #129 标题为“🎨 Palette: 优化状态图标切换的过渡动画”（纯 UI），却在 `pr-checks.yml` 的 `dependency-review` step 上新增引号键 `'continue-on-error': true`，并附注释“Temporary bypass for DEP-REVIEW-1 as GHAS is not enabled yet”。该 step 旁的内联 `SECURITY GATE` 注释（L47-52）与文件头注释（L4-7）明令禁止在此加 `continue-on-error`——这会把 CVSS≥7.0 依赖 CVE 的红色信号静默翻绿。基线 `dev` **没有**这一行（`git show origin/dev:.github/workflows/pr-checks.yml` 确认），故为本 PR 新引入。
- **为何危险（即使 GHAS 未启用）**:
  1. **引号键绕过守卫**：`check_ci_permissions.py` 的 `_step_get_value()` 用 `s.startswith("continue-on-error:")` 匹配，而引号键 `'continue-on-error': true` strip 后以 `'` 开头，不匹配。经验证：对该 PR 文件运行守卫返回 **exit 0 / "OK"**（误报未 mask）；对等价的无引号形式 `continue-on-error: true` 运行则 **exit 1 / FAIL**——唯一差异就是这对单引号。即绕过同时骗过 CI 与人工评审。本分支修复后，对该 PR 文件运行守卫返回 **exit 1 / FAIL**（`step is masked by 'continue-on-error:' (value='true', N90/CI-DEP-1/H3/H4)`）。
  2. **永久致盲守卫**：一旦此引号键合入，未来即使启用 GHAS（DEP-REVIEW-1），这条守卫看不见的 `continue-on-error: true` 会继续静默压制真实 CVSS≥7.0 失败，且再无自动化检查能发现。
  3. **green-wash 非功能门**：当前 dep-review 因 GHAS 未启用而报配置错误（非 CVE 扫描）；此 PR 把红变绿但**并不恢复 CVE 扫描**，反而消除了“尽快启用 GHAS”的红色压力，把“无依赖审查”状态长期固化。
  4. **夹带模式**：UI PR 改安全工作流本身即红旗；与已记录的 `CI-DEP-1-RELAPSE-3`（PR #91 同样以 UI 标题夹带删除 dependency-review job）手法一致。
- **S1 风险评级被证伪**: 本文档 `CI-GUARD-BYPASS-S1to3` 原将 S1（引号键）评为“低风险”，理由是“palette bot 不会用引号键/冒号空格等边缘语法”。**PR #129 实证证伪该判断**——理论上的 S1 绕过已被实际利用。S1 风险已上调，并在本分支修复。
- **触发场景**: 维护者按 PR #129 标题（UI 动画）合并 → dependency-review step 被 mask 且守卫不报错 → 后续任何 CVSS≥7.0 依赖 CVE（Go `govulncheck` 已被 mask，Android `dependencyCheckAnalyze` 仅 CVSS≥9.0 失败）不再产生红色检查 → 高危依赖被静默合入。
- **风险**: **高（安全门绕过 + 守卫致盲）**。即时 CVE 暴露受 DEP-REVIEW-1（GHAS 未启用）缓解，但守卫致盲与 green-wash 危害持久且高危。
- **修复方式**:
  1. **PR #129**：丢弃 `e41f70f`（同时删掉夹带的垃圾文件 `pr-checks.yml.orig`）。如确需处理 GHAS 未启用，应单独、显式评审的 PR，不应捆绑进 UI 动画 PR。
  2. **守卫加固（本分支）**：在 `check_ci_permissions.py` 新增 `_key_line_pattern(key)` 正则 `^(?:'key'|"key"|key)\s*:`，替换 `_step_get_value()` 与 job 级扫描的 `startswith` 匹配；使引号键（S1）与冒号前空格（S2）形式同等被检测。新增 5 个回归测试覆盖单/双引号、job 级、冒号空格、值提取。已验证：5 个新测试在 buggy 版本（`startswith`）FAIL（`got: []` 绕过），修复版本 PASS；原 16 个测试无回归；end-to-end 对 PR #129 文件运行守卫 exit 1。
- **交叉验证**: 两个独立 subagent 复核确认（引号键绕过经 `python3 scripts/check_ci_permissions.py` 实证：PR 文件 exit 0、等价无引号形式 exit 1；`startswith` 不匹配引号前缀已逐行核对；S1 文档评级矛盾已定位到 L2008 原文）。


---

## 提交后正确性检查发现（2026-07-18，审查 PR #95 / #91 / #96 / #92 / #86）

> 由 5 个并行 subagent 一审 + 2 个独立 subagent 二审交叉确认。所有发现均未在合并基线
> 的 `docs/` 中记录过，符合"仅报告尚未被记录在文档中的问题"的门槛。

### PR-95-TIMING-REGRESSION: 反调试时序检测被静默禁用 [已在本分支修复]

- **状态**: 已在本分支修复（`fix/pr95-timing-attack-workload-restore`）
- **提交哈希**: PR #95 分支 `582966e`（origin/jules-3397389426294637075-40b8fe73）
- **位置**: `android/app/src/main/java/com/netproxy/gateway/security/DebugDetector.kt` (`checkTimingAttack`, L453-L466 在 main 上；PR #95 删除 L456-L460)
- **问题描述**: PR #95 "⚡ 性能优化: 移除 checkTimingAttack 中的无意义死循环" 误将
  `for (i in 0 until 1000000) sum += i` 循环判为"死代码"删除。该循环是**被测量的
  工作负载**——删除后函数体变为两次相邻的 `System.currentTimeMillis()` 调用，毫秒
  粒度上差值几乎总是 0，在默认 `thresholdMs = 1000` 下 `0 > 1000` 恒为 false。该
  检测是 `DebugDetector` 中**唯一**基于时序的动态分析检测，专门捕获能绕过静态检查
  （TracerPid、`/proc/self/status`）的自定义调试器或插桩工具。删除循环等于完全
  禁用这一检测通道。
- **执行路径**: `NetProxyApp.onCreate` → `performSecurityChecks` →
  `SecurityManager.performSecurityCheck` (`SecurityManager.kt:98`) →
  `DebugDetector.check` (`DebugDetector.kt:90`) → `checkTimingAttack` →
  `detectedMethods.add("timing-attack")` → `isDebugged = true` →
  `SecurityCheckResult.isSecure = false`。
- **触发场景**: 攻击者附加自定义调试器或 Frida 在单步/断点密集模式下逆向应用启动
  阶段的 MQTT 凭证或代理配置。PR #95 之前：1M 次迭代在单步下膨胀到数秒 →
  `diff > 1000` → 触发检测；PR #95 之后：`startTime` 与 `endTime` 相邻，`diff ≈ 0`，
  永不触发——攻击者甚至无需绕过该检查。
- **风险**: **Medium-High**。当前 `handleSecurityRisk` 仅记录日志（`NetProxyApp.kt:64-73`），
  但代码 TODO（`NetProxyApp.kt:68-72`，"退出应用（在 release 模式下可考虑）"）一旦
  落地，此破损的检测将成为静默漏洞。其他 9 个静态检测（debugger-connected、
  being-debugged、ptrace-status、frida、xposed 等）仍工作，所以不是完全失守，
  但 defense-in-depth 中时序维度被关闭。
- **PR #95 添加的测试反而固化了回归**:
  - `checkTimingAttack_returnsFalse_underNormalExecution` → 回归后 diff 恒为 0，
    该测试 trivially 通过，名为"正常执行"实际无法区分正常/异常。
  - `checkTimingAttack_returnsTrue_whenExecutionExceedsThreshold` 用 `thresholdMs = -1`
    调用，`0 > -1` 恒真，是 tautology——任何实现都会通过。
  两条测试均未模拟"调试器减速的时钟"或断言工作负载耗时，无法捕获回归。
- **修复方式**（本分支）:
  1. 恢复 100 万次整数累加工作负载。
  2. 通过 `@Volatile var workloadFingerprint: Int` 写入 `sum`，使循环具备可观测
     副作用，防止 JIT DCE 删除（也使未来的"无意义死循环"误判需要明确删除该字段，
     触发编译失败提醒）。
  3. 新增 `@Volatile var timingCheckInvocationCount: Long`，在循环之后递增，作为
     函数体完成执行的锚点。
  4. 新增 `now: () -> Long = { System.currentTimeMillis() }` 参数，使时序逻辑可被
     注入式测试确定性验证（不依赖真实时序或实际调试器附加）。
  5. 添加 5 条测试：
     - `returnsFalse_underNormalExecution`：真实时钟 + 默认阈值（基线）
     - `returnsTrue_whenClockSimulatesDebuggerSlowdown`：注入 fake clock，diff=2000ms
       → true（确定性验证阈值逻辑）
     - `returnsFalse_whenClockSimulatesFastExecution`：注入 fake clock，diff=10ms
       → false
     - `returnsFalse_whenDiffEqualsThreshold`：边界 `diff == threshold`（严格 `>`）
     - `executesObservableWorkload`：断言 `timingCheckInvocationCount` 递增且
       `workloadFingerprint` 等于 `0 until 1_000_000` 的累加和——循环若被删除，
       fingerprint 不会更新，断言失败。
- **关联**: PR #91 / PR #96 / PR #92 的审查评论已分别留在各 PR；本分支只修复
  PR #95，因为它是唯一可在新分支上独立、最小化、高置信度修复的问题（PR #91 / #96
  是他人 PR 的活体分支，只能评论不能直接修改；PR #92 标题与 diff 不符属于沟通问题）。

---

## PR #91 / #92 审查与 self-hosted runner CI 问题（2026-07-18 补充）

> 3 个新条目记录今日 PR 审查与 CI 基础设施状态：CI-DEP-1-RELAPSE-3（PR #91 第 3 次
> 复发）、HEALTH-RATELIMIT-1（PR #92 设计缺陷）、SELF-HOSTED-RUNNER-1（临时 runner
> 期间已知 CI 问题）。

### CI-DEP-1-RELAPSE-3: PR #91 第 3 次尝试删除 dependency-review job + 削弱 commitlint [已记录 — PR 已关闭]

- **状态**: 已记录（PR #91 closed，未合并；CI-DEP-1 守卫已生效）
- **修复难度**: 低（PR 已关闭，无需修复；保留记录作为复发模式证据）
- **修复状态**: 已记录
- **位置**: PR #91（已关闭）— diff 涉及 `.github/workflows/pr-checks.yml`（删除 dependency-review job）、`.commitlintrc.json`（body-max-line-length 设为 severity=0）、`android/app/src/main/java/com/netproxy/gateway/ui/screens/MainScreen.kt`（与 PR #87 字节级相同）
- **问题描述**: PR #91 标题声称是 UX 改进，但 diff 包含 3 个无关的安全回归：
  (a) 删除了 `.github/workflows/pr-checks.yml` 中的 `dependency-review` job，继 PR #79/#82 后第 3 次尝试删除该 job，违反 CI-DEP-1 守卫；
  (b) 将 `.commitlintrc.json` 的 `body-max-line-length` 规则设为 severity=0（禁用），削弱 commit message 长度检查；
  (c) 改动 `MainScreen.kt`，与 PR #87 字节级完全相同（PR #87 已合并到 dev）。
  PR 描述掩盖实际改动，属于"恶意/疏忽的 PR 描述掩盖实际改动"模式。
- **触发场景**: 恶意/疏忽的 PR 描述掩盖实际改动；palette bot 或类似自动化工具的越界删除行为复发。
- **风险**: **High**。如果合并：(a) 会静默移除 dependency-review 安全门，让依赖漏洞检测失效；(b) 削弱 commitlint body-max-line-length 规则，让超长 commit message 通过；(c) MainScreen.kt 重复改动可能引入冲突或回退 PR #87 的修复。
- **修复方式**: PR 已关闭；CI-DEP-1 守卫已生效（`scripts/check_ci_permissions.py` 拦截了 dependency-review job 的删除）。建议后续遇到类似 PR（标题声称改进但 diff 包含 dependency-review 删除或 commitlint 削弱）应直接关闭，并在 PR 评论中指向本条目与 CI-DEP-1。
- **关联**: CI-DEP-1（required status checks / dependency-review 守卫的原始需求）、N90、PR #79、PR #82（前两次复发）、PR #87（PR #91 的 UX 部分已被 PR #87 合并到 dev）。

### HEALTH-RATELIMIT-1: PR #92 健康检查接口速率限制实现有 3 个设计缺陷 [未修复 — 待重做]

- **状态**: 未修复 — 待重做（PR #92 已关闭，未合并）
- **修复难度**: 中
- **修复状态**: 未修复
- **位置**: PR #92（已关闭，未合并）— diff 涉及 `server/api/` 健康检查接口的速率限制实现
- **问题描述**: PR #92 标题"添加健康检查接口的速率限制机制"，但实现有 3 个关键缺陷：
  (a) **K8s readiness 误伤**：复用 `s.rateLimiter`（与 login 共享），会导致 K8s readiness probe 被限流，造成 Pod 误判不健康并重启；
  (b) **X-Forwarded-For 可伪造**：gin 默认 `TrustedProxies=["0.0.0.0/0","::/0"]`，攻击者可伪造 IP 绕过限流；
  (c) **共享限流桶**：健康检查不应与登录共享限流桶——健康检查是基础设施探针，登录是用户认证，二者的限流语义和阈值不同。
- **触发场景**: K8s 部署环境下，readiness probe 频繁调用健康检查接口，被限流后导致 Pod 被误判不健康并触发重启循环。
- **风险**: **Medium**。仅在 K8s 部署 + 高频 readiness probe 场景下显现，但一旦显现会导致服务不稳定。
- **修复方式**: 重做 PR，使用独立的限流桶（如 `s.healthRateLimiter`），不依赖 X-Forwarded-For，只基于 RemoteIP（gin 的 `c.ClientIP()` 在 `TrustedProxies=[]` 时返回 RemoteAddr）。如果需要支持反向代理场景，应显式配置 `TrustedProxies` 为可信代理列表，而非默认的全网信任。
- **关联**: 无

### SELF-HOSTED-RUNNER-1: 临时 self-hosted runner 期间 3 个已知 CI 问题 [未修复 — 等待基础设施切换]

- **状态**: 临时基础设施问题 — 切回 GitHub-hosted runners 后自动缓解
- **修复难度**: 低（无需修复代码，等切换 runner）
- **修复状态**: 未修复 — 等待基础设施切换
- **位置**: `.github/workflows/pr-checks.yml`（dependency-review job）+ `.github/workflows/android-ci.yml`（"Run dependency vulnerability check" step）+ self-hosted runner 容量配置
- **问题描述**: 当前使用临时 self-hosted runner（用户提示下个月切回 GitHub-hosted runners），期间有 3 个已知 CI 基础设施问题：
  (a) **dependency-review job 失败**：错误信息 `Dependency review is not supported on this repository. Please ensure that Dependency graph is enabled along with GitHub Advanced Security`。根本原因是仓库为 private 且未启用 GitHub Advanced Security（GHAS），dependency-review-action 无法工作。**与 runner 无关**：即使切回 GitHub-hosted runners 也会失败（private 仓库需要 GHAS 才能使用 dependency-review-action）。当前用 admin merge 绕过（与 PR #99/#100 一致），与代码无关。
  (b) **Android CI "Run dependency vulnerability check" 步骤 cancelled**：现象为步骤 13（OWASP Dependency-Check）跑了 28 分钟后被 cancelled。根本原因是 self-hosted runner 网络问题，OWASP Dependency-Check 拉取 NVD 数据库超时。**与 runner 相关**：切回 GitHub-hosted runners 后会自动解决（GitHub 网络好）。当前用 admin merge 绕过，因为关键 Build/Test/Lint/JaCoCo 步骤都已通过。
  (c) **Runner 容量问题**：多个 CI job 长时间 queued 无法获取 runner。根本原因是 self-hosted runner 数量有限，单个 Android CI job 可能卡住 1.5+ 小时占用 runner。**与 runner 相关**：切回 GitHub-hosted runners 后会自动解决。
- **触发场景**: 所有 PR。
- **风险**: **Low**。仅 CI 基础设施问题，不影响代码正确性；用 admin merge 绕过。
- **修复方式**: (a) 切回 GitHub-hosted runners 后 Android CI 网络问题（b/c）自动解决；(b) dependency-review 即使切回 GitHub-hosted runners 也会失败——需启用 GHAS 或修改 CI 配置，但当前用 admin merge 绕过（同 DEP-REVIEW-1 的处理思路）；(c) 切回后 runner 容量自动解决。
- **关联**: CGO-DETECT-1（同样是临时 runner 问题，已标记 no-fix needed）、DEP-REVIEW-1（dependency-review job 失败的根本原因相同：未启用 GHAS）、CI-DEP-1（required status checks 未启用让这些 CI 问题不阻塞合并）。

### REV51: server/tunnel `offline` 通知 `last_seen` 在 goroutine 内捕获，可与并发 `online` 竞争导致设备被错误持久化为离线 [已修复]
- **修复状态**: 已修复
- **修复难度**: 中
- **位置**: `server/tunnel/main.go` (`Register`、`Unregister`、`cleanupDeadTunnelsOnce`、`notifyDeviceStatus`)
- **问题描述**: REV32 的替换 re-check 在"未发现 replacement"时会决定发送 `offline` 通知，但 `last_seen` 时间戳是在 `notifyDeviceStatus` goroutine 内部通过 `time.Now().UnixMilli()` 捕获的（而非在 re-check 决策时刻捕获）。当 `offline` goroutine 因调度延迟晚于一个并发的 `Register`（`online`）执行时，`offline` 的 `last_seen` 可能 **晚于** `online` 的 `last_seen`，从而绕过 REV33 在 API 端设置的严格 `excluded.last_seen > device_status.last_seen` 保护，把已经回到在线状态的设备错误地写回 `offline`。设备会保持错误的离线状态直到下一次完整重连触发新的 `online` 通知（无自纠正心跳）。该残余竞态是 PR #58 引入 `last_seen` 字段后特有的：main 分支的 `notifyDeviceStatus` 不发送 `last_seen`，API 回落为当前时间，问题不存在。
- **风险**: **中**。窗口较小（需 re-check 后、goroutine 实际执行前恰好有并发 `Register`），不影响实际 SOCKS5 连通性，但会导致工程师端设备状态显示与实际不符，且无法自纠正。属于 REV32/REV33 修复后声称已闭合但实际未闭合的残余竞态——文档（REV32 剩余风险、REV33 修复方式）此前错误地断言该路径已被 `last_seen` 保护覆盖。
- **触发场景**: 设备网络抖动导致旧隧道断开（触发 `Unregister`）的同时客户端立即重连（触发 `Register`）。`Unregister` 的 re-check 在 `Register` 之前完成（未观测到 replacement），于是决定发送 `offline`；但 `offline` goroutine 实际执行 `time.Now()` 的时刻晚于 `Register` 捕获 `online` 时间戳的时刻，导致 `offline.last_seen > online.last_seen`，API 接受该 `offline` 写入。
- **修复方式**:
  - 将 `last_seen` 的捕获从 `notifyDeviceStatus` goroutine 内部提前到事件决策时刻，并在 `m.mu` 锁下完成：
    - `Register`：在 `m.mu.Lock()` 下捕获 `online` 的 `last_seen`，作为参数传入 `go m.notifyDeviceStatus(..., lastSeen)`。
    - `Unregister`：在 re-check 的 `m.mu.RLock()` 下捕获 `offline` 的 `last_seen`（re-check 未发现 replacement 的瞬间），再 spawn goroutine 转发该值。
    - `cleanupDeadTunnelsOnce`：同样在 re-check 的 `m.mu.RLock()` 下捕获 `last_seen`。
  - `notifyDeviceStatus` 签名改为接收 `lastSeen int64` 参数，移除内部的 `time.Now().UnixMilli()` 重捕获，确保 caller 在锁下捕获的值被原样转发。
  - 不变式：由于 `offline` 的 `last_seen` 在 re-check 时刻（`m.mu` 锁下）捕获，而任何后续 `Register` 必须先获取 `m.mu` 写锁才能捕获 `online` 的 `last_seen`，故 `offline.last_seen` 严格早于 `online.last_seen`，API 的严格 `>` 保护必然拒绝该 `offline`。
  - 新增测试钩子 `testRecheckHook`（`reached` buffered(1) + `hold` 阻塞通道）与 `TunnelManager.testHookAfterUnregisterRecheck` / `testHookAfterDeadTunnelsRecheck` 字段，用于确定性复现 re-check 后、goroutine spawn 前的竞态窗口。生产代码中这些字段为 nil，零开销。
- **测试覆盖**:
  - `TestNotifyDeviceStatusIncludesLastSeenTimestamp`：重写为传入固定 `last_seen`，断言 payload 原样转发 caller 捕获的值（不再内部重捕获）。
  - `TestUnregisterOfflineLastSeenStrictlyOlderThanConcurrentRegister`：新增确定性回归测试。利用 `testHookAfterUnregisterRecheck` 在 re-check 捕获 `offline` `last_seen` 后暂停 `Unregister`，在暂停窗口内 `Register` 替换隧道（其 `online` `last_seen` 严格更新），随后释放并断言 `offline.last_seen < online.last_seen`。已验证：模拟缺陷（在 hold 释放后重捕获 `last_seen`）时该测试确定性失败；修复后确定性通过。
  - 其余 7 处 `notifyDeviceStatus` 调用点更新为 4 参数签名。
- **关联**: 修正 REV32"剩余风险"与 REV33"修复方式"中关于 `last_seen` 保护已生效的不准确断言。本修复基于 `fix/rev31-36-post-commit-review` 分支（PR #58），因为该竞态是 PR #58 引入 `last_seen` 字段后特有的。

### ROOTDETECTOR-BUSYBOX-1: `RootDetectorTest.checkBusyBox_returnsFalse_whenNoBusyBoxFoundAndWhichFails` 在 Windows + Git Bash 环境下失败 [未修复 — 环境相关]
- **修复状态**: 未修复（环境相关，非代码缺陷）
- **修复难度**: 低。三个可选修复方向（见下）
- **影响文件**: `android/app/src/test/java/com/netproxy/gateway/security/RootDetectorTest.kt` (L163-166)、`android/app/src/main/java/com/netproxy/gateway/security/RootDetector.kt` (L202-229)
- **问题描述**: `RootDetectorTest.checkBusyBox_returnsFalse_whenNoBusyBoxFoundAndWhichFails` 断言 `RootDetector.checkBusyBox()` 返回 `false`，注释假设"在测试环境中，BusyBox 文件不存在且 `which` 命令不可用"。但在 Windows 开发机 + Git Bash 环境下，Git Bash 把 `which` 加入了 PATH，导致 `RootDetector.kt` L211 的 `ProcessBuilder("which", "busybox")` 能成功执行。若系统中存在任何名为 `busybox` 的可执行文件（或 `which` 的行为与测试预期不符），`checkBusyBox()` 可能返回 `true`，测试失败。
- **诊断证据**:
  - 本地 `make android-test` 输出：`RootDetectorTest > checkBusyBox_returnsFalse_whenNoBusyBoxFoundAndWhichFails FAILED    java.lang.AssertionError at RootDetectorTest.kt:165`，282 tests completed, 1 failed。
  - ISSUES.md L1214（既有记录）："全量 473 测试仅 1 失败（`RootDetectorTest.checkBusyBox`，既存问题与本次修改无关）"。
  - `RootDetector.kt` L211：`val process = ProcessBuilder("which", "busybox").redirectErrorStream(true).start()`，依赖系统 PATH 中的 `which` 命令。
- **根因**: 测试假设"JVM 单元测试环境下 `which` 不可用"只在纯 JVM 环境（如 CI 的 ubuntu-latest 无 Git Bash）成立。Windows 开发机普遍安装 Git for Windows，其 `bin/sh.exe` 被加入 PATH 后 `which` 可用，测试假设被破坏。这是测试环境假设与开发者实际环境的差异，非生产代码缺陷。
- **触发场景**: Windows 开发机 + Git Bash 在 PATH 中时，本地运行 `make android-test` 或 `./gradlew :app:testDebugUnitTest`。CI 上（GitHub-hosted ubuntu runners）不会触发，因 `which` 默认可用但 BusyBox 不在 PATH 中，`which busybox` 返回非零退出码且 stdout 为空，`checkBusyBox()` 返回 false，测试通过。
- **风险**: **Low**。仅影响本地开发体验，不影响 CI 绿灯，不影响生产代码。但会让 Windows 开发者每次跑 `make android-test` 都看到 1 个失败，可能掩盖其他真实失败。
- **修复方式**（三选一，按推荐度排序）:
  1. **修改测试**（推荐，最简单）：给 `checkBusyBox_returnsFalse_whenNoBusyBoxFoundAndWhichFails` 加 JUnit 5 条件注解 `@DisabledOnOs(OS.WINDOWS)`，并补充注释说明原因。缺点：只是规避，未真正验证 Windows 下的行为。
  2. **Mock ProcessBuilder**（最干净）：重构 `RootDetector.checkBusyBox()` 使 `which` 执行通过可注入的接口（如 `ProcessExecutor`），测试中 mock 该接口返回空结果。缺点：改动较大，需引入接口和依赖注入。
  3. **运行时环境检测**：修改 `RootDetector.checkBusyBox()` 在非 Android 运行时环境（通过 `System.getProperty("java.runtime.name")` 或类似方式检测）直接返回 false。缺点：生产代码引入测试相关逻辑，不推荐。
- **关联**: 本条目在 PR #120（docs/sync-with-code-reality）中首次记录，该 PR 的 `make android-test` 验证暴露了此失败。

### MAKEFILE-TIDY-NOOP-1: `go-ci-component` / `go-quality-component` 的 `go mod tidy` 漂移检查是静默 no-op [已修复]

- **状态**: 已修复（分支 `fix/pr120-makefile-tidy-noop`，PR #123，base: `docs/sync-with-code-reality`）
- **修复难度**: 低（最小化 shell 退出码语义修复，2 处共 4 行）
- **影响文件**: `Makefile`（`go-ci-component` 目标 L208-211、`go-quality-component` 目标 L247-251）
- **问题描述**: `go-ci-component` 与 `go-quality-component` 两个 Makefile 目标的 `go mod tidy` 漂移检查子 shell 写成：

  ```makefile
  (cd $$dir && git diff --exit-code -- go.mod go.sum >/dev/null 2>&1; \
   git checkout -- go.mod go.sum 2>/dev/null || true) && \
  echo "=== $$dir CI checks passed ==="
  ```

  POSIX 子 shell `(cmd1; cmd2)` 的退出码等于**最后一条**命令的退出码。这里最后一条是 `git checkout -- go.mod go.sum 2>/dev/null || true`，由于 `|| true` 兜底，**永远返回 0**。`git diff --exit-code` 检测到漂移时返回的非零退出码被完全丢弃，`&&` 链继续走到 `echo "...passed"`。结果：`go.mod`/`go.sum` 处于 untidy 状态时，drift 检查 100% 静默通过，与同文件 `go-mod-tidy-check`（L144-158，正确使用 `if [ $$? -ne 0 ]`）的正确实现形成直接对比。
- **触发场景**: 开发者在 `server/api/handlers.go` 新增一个 import（如 `github.com/go-chi/chi/v5/middleware`），本地 `go build` 因 module cache 命中而通过，但忘记运行 `go mod tidy` 提交 `go.sum` 中对应的 checksum 行 → 推送 PR → `make go-ci-component COMPONENT=api` 进入漂移检查子 shell → `go mod tidy` 在 CI 里把 `go.sum` 补齐 → `git diff --exit-code` 本应返回 1，但被子 shell 末尾的 `git checkout ... || true` 吞掉 → 子 shell 退出 0 → `&&` 继续 `echo "=== ... CI checks passed ==="` → CI 绿灯 → PR 合并 → 下游某个干净 checkout（fresh Docker 构建、新 contributor 机器）因 `go.sum` 校验失败而 `go build` 报 `missing go.sum entry` 崩溃。
- **风险**: **中（P2）**。bug 完全静默（`>/dev/null 2>&1` + `|| true`，无 FAIL 输出）；爆炸半径限于本地开发与未来 CI 接线风险——当前 `.github/workflows/ci.yml` 的 `go-build` job 直接运行 `go build -v ./...`、`go test -v ...`、`govulncheck ./...`，**未调用任何 Makefile 目标**，故 CI 当前不触发此 bug。但 Makefile L191-195 注释声称 `go-ci.yml`（文件名实际为 `ci.yml`）会调用 `go-ci-component`，且 `go-quality-component` 在 `go-ci.yml` 中按 ADR-006 以 `continue-on-error: true` 掩码运行（收集基线）——若维持现状 no-op，则"基线数据"本身是假的（永远报 passed），掩盖长期漂移趋势。
- **根因**: 子 shell `(...)` 退出码语义与 `|| true` 兜底组合的副作用——`git diff --exit-code` 的退出码需要显式捕获（`rc=$$?`）或显式检查（`if [ $$? -ne 0 ]`）才能传播到外层 `&&` 链。
- **验证**:
  - 静态分析：`(cmd1; cmd2 || true)` 的退出码恒为 0（POSIX 子 shell 退出码 = 最后一条命令退出码）。
  - 经验复现：在 `/tmp` 临时 git 仓库中构造 untidy `go.mod`（追加未使用 `require github.com/google/uuid v1.6.0`），逐字运行 bug 版子 shell → exit code 0（漂移被掩盖）；逐字运行 `go-mod-tidy-check` 的正确版子 shell → exit code 1（漂移被检出）。
  - 溯源：该 bug 由 PR #81（`552f93e`，"fix(ci): unmask go build/test + android lint/dep-check"）引入；`origin/main` 的 Makefile 仅 122 行，**不包含** `go-ci-component`/`go-quality-component` 任何目标，故 bug 未流入 main。PR #120（`docs/sync-with-code-reality`）携带该 bug 但未引入也未修改这两处。本修复在 PR #120 分支基础上定向修复。
- **修复方式**: 在两处子 shell 内用 `rc=$$?` 捕获 `git diff --exit-code` 的退出码，**再**执行 `git checkout` 清理（保留原作者"检查后清理"意图），最后 `exit $$rc` 让子 shell 返回真实退出码。tidy 时 rc=0，`&&` 链继续到 `echo "...passed"`；untidy 时 rc≠0，`&&` 短路，目标失败。同时新增 `FAIL: ...` 提示行，避免完全静默。修复与同文件 `go-mod-tidy-check` 的"先检查、后清理、保留退出码"思路一致，只是收进同一子 shell 以适应单组件目标的 `&&` 链风格。修复 diff（两处对称）：

  ```diff
  	(cd $$dir && go mod tidy) && \
  -	(cd $$dir && git diff --exit-code -- go.mod go.sum >/dev/null 2>&1; \
  -	 git checkout -- go.mod go.sum 2>/dev/null || true) && \
  +	(cd $$dir && git diff --exit-code -- go.mod go.sum >/dev/null 2>&1; rc=$$?; \
  +	 git checkout -- go.mod go.sum 2>/dev/null || true; \
  +	 if [ $$rc -ne 0 ]; then echo "FAIL: $$dir has untidy go.mod/go.sum"; fi; exit $$rc) && \
  	echo "=== $$dir CI checks passed ==="
  ```
- **回归测试**: 新增 `scripts/test_makefile_tidy_check.py`（6 个用例，纯 stdlib，无需 Go 工具链/网络）：
  1. `test_go_ci_component_uses_rc_capture` — 断言 `go-ci-component` recipe 含 `rc=$$?` 与 `exit $$rc`。
  2. `test_go_quality_component_uses_rc_capture` — 同上，针对 `go-quality-component`。
  3. `test_no_buggy_drift_check_pattern_remains` — 全文扫描：每个 `git diff --exit-code -- go.mod go.sum` 必须在后续 2 行内被 `rc=$$?` 或 `if [ $$? -ne 0 ]` 守卫。
  4. `test_fixed_snippet_fails_on_untidy_go_mod` — 端到端：untidy go.mod 下固定版子 shell 必须非零退出。
  5. `test_fixed_snippet_passes_on_tidy_go_mod` — 端到端：tidy go.mod 下固定版子 shell 必须零退出。
  6. `test_buggy_snippet_silently_passes_on_untidy_go_mod` — 文档型：复现 bug 行为（bug 版子 shell 在 untidy 时仍返回 0），用于未来若有人"简化"recipe 时立即报警。
  - 运行：`python3 scripts/test_makefile_tidy_check.py`，6/6 通过。
- **关联**: CI-MASK-1（同一 Makefile 的 masking 反模式家族）、CI-MASK-2、ADR-006 L294（声明 fmt/vet/tidy 基线掩盖意图，但未授权 drift 检查本身失效）、MAKE-MISSING-1（CI 实际不调用 Makefile 目标的结构性原因）。本条目由 post-commit 正确性审查（PR #120 评审）发现，经两个独立 subagent 交叉验证（静态分析 + 经验复现 + 溯源）确认。

---

## 提交后正确性检查发现（2026-07-22，审查过去 24 小时各分支提交 + 更新的 PR）

> 以下问题由多 subagent 对过去 24 小时提交与更新 PR 的深度审查发现，经独立 subagent 交叉验证确认。

### REV52: PR #112 提交 `84c0691` 大规模回退 dev 已修复的安全/数据完整性缺陷 [待修复 — 阻塞合并]
- **状态**: 待修复（已在 PR #112 留下阻塞评论；PR 尚未合并）
- **提交哈希**: `84c0691`（分支 `fix-pairing-session-db-perf-91002509196936996`）
- **位置**: `server/api/main.go`
- **问题描述**: PR #112 标题为“使用预编译语句优化 `compareAndUpdatePairingSessionDB` 性能”。真正实现预编译语句的提交 `c62aa5e` 是正确的（完整保留 dev 全部修复）。但随后的提交 `84c0691`（commit message 同样写作 perf 优化）实际用一份旧版 `main.go` 快照覆盖文件，diff 高达 **65 文件 +491/−7089**，把 dev 上已修复的多个问题全部回退。`git merge-base origin/dev <PR分支>` == `origin/dev` HEAD (`af16a54`)，证实这些代码缺失是被本 PR 删除，而非“dev 有更新但分支未跟上”。
- **被回退的缺陷（合并即重新引入）**:
  1. `GET /pair/:code` 丢失速率限制：`rateLimitMiddleware`/`rateLimitKey` 整组函数被删，路由链去掉 `rateLimitMiddleware()`，`getPairingSession` 内 `s.rateLimiter.Success(...)` 调用被移除 → 6 位配对码可无节流暴力枚举（回退已修复的 REV28）。**安全**
  2. `createSessionToken` 丢失 TOCTOU + 过期校验：`expectedStatus`/`expectedEngineerID` 捕获、`compareAndUpdatePairingSessionDB` 二次校验、`time.Now().After(session.ExpiresAt)` 的 400 检查全被删 → 已过期/已连接会话仍可签发 token（回退 REV42/REV43）。**安全**
  3. `upsertDeviceStatusDB` 丢失单调写保护：`ON CONFLICT ... WHERE excluded.last_seen > device_status.last_seen` 子句被删 → 乱序/延迟通知用旧 `last_seen` 覆盖新状态（回退 REV33）。**数据损坏**
  4. `last_seen` 存储单位 ms→s 且删除向后兼容 shim：写 `Unix()` 秒、读 `time.Unix(lastSeen,0)`，并删掉 `if lastSeen > 0 && lastSeen < 1e12 { lastSeen *= 1000 }`；`updateDeviceStatus` 不再接受客户端 `last_seen` → 从 dev 毫秒时间戳升级后既有行被当成秒，时间戳错到几千年后。**数据损坏**
  5. `getPairingSession` 并发修改重检弱化：`ErrConcurrentModification` 后不再 `time.Now().After(refreshed.ExpiresAt)` 重检返回 410 Gone → 并发触碰后已过期会话仍以 200 返回（回退 REV31/REV44）。**正确性/安全**
  6. `db.Close()` on prepare-failure 被删（本 PR 自身 `4556282` 引入的修复，被 `84c0691` 回退）→ `NewServer` 在 `db.Prepare` 失败时不再关闭 `*sql.DB`，测试/嵌入式调用会泄漏句柄。**资源泄漏**
- **风险**: **高**。合并会同时回退 REV28/REV33/REV42(及 REV43)/REV31(及 REV44) 等已记录修复，重新引入 2 个安全漏洞 + 2 个数据损坏 + 1 个正确性退化 + 1 个资源泄漏。
- **修复方式**: 预编译语句优化本身（`c62aa5e` + `4556282` 的 `db.Close()` + `d14d8e0` 的测试覆盖）可合并。应**丢弃 `84c0691`**，将分支 rebase 到 `origin/dev` 仅保留 `c62aa5e` + `4556282` + `d14d8e0`（+ 可选 `45d5182` 的 CI Go 版本）；或在本分支 `git revert 84c0691` 后基于 dev 解决冲突。
- **交叉验证**: 两个独立 subagent 复核确认（merge-base = dev HEAD；六项逐一用 `git show origin/dev:server/api/main.go` 与 PR 分支 grep 比对）。

### REV53: `RateLimiter.Stop()` 非幂等，二次调用 panic（`close of closed channel`）[已修复]
- **状态**: 已修复（本审查批次，分支 `fix/ratelimit-stop-idempotent-rev53`）
- **提交哈希**: `1b64bd0`（PR #122 修复 cleanupLoop goroutine 泄漏时，`Stop()` 本身未一并加固；该缺陷为既有问题，非 PR #122 引入）
- **位置**: `server/shared/ratelimit/ratelimit.go` (`Stop()`, L65-67 修复前)
- **问题描述**: `Stop()` 直接 `close(rl.stopCh)`，无 `sync.Once` 保护。第二次调用 `Stop()` 会 panic：`close of closed channel`。`Stop()` 与 `cleanupLoop` 属同一区域（PR #122 评审范围），但评审遗漏了 `Stop()` 自身的幂等性。
- **触发场景**: 任何对同一 `*RateLimiter` 调用两次 `Stop()` 的路径。当前生产代码（API server 的 `Server.Close()` 与 socks5-proxy 信号 goroutine）各只调用一次，故当前为**潜在（latent）**而非线上必现；但脆弱性明显：(1) 测试中已存在显式 `Stop()` + `defer Stop()` 模式，写出双调用即 panic；(2) `Server.Close()` 自身对 `s.cleanupStop` 也有同样的非幂等 `close`，一旦给 API server 增加 signal handler 调用 `Close()`（与 socks5/tunnel 一致的模式），即转为线上崩溃。独立 subagent 复现确认 panic 字符串为 `close of closed channel`。
- **风险**: **中高（崩溃）**。当前潜在，但属 `Stop()`/关闭路径的崩溃缺陷，且修复零风险。
- **修复方式**: 结构体新增 `stopOnce sync.Once` 字段；`Stop()` 改为 `rl.stopOnce.Do(func() { close(rl.stopCh) })`。新增回归测试 `TestStopIsIdempotent`：连续调用 `Stop()` 三次不 panic，且 `Allow()` 在 `Stop()` 后仍可用。已验证：buggy 版本（`close(rl.stopCh)`）该测试 FAIL（`Stop() call #2 panicked: close of closed channel`），修复版本 PASS；全套 `go test -race` 通过，gofmt/vet/build 干净。
- **关联**: issue #90 G1（PR #122 已修 cleanupLoop busy-loop，但未加固 `Stop()` 幂等性）。注意 `Server.Close()` 中 `close(s.cleanupStop)` 存在同型非幂等缺陷，留待后续单独处理（保持本次修复最小化）。
- **交叉验证**: 两个独立 subagent 复核确认（panic 可复现 + 生产调用链可达性分析：API server 无 signal handler、`Close()` 为生产死代码；socks5 signal goroutine 单次触发）。

---

## 提交后正确性检查发现（2026-07-24，多 subagent 审查过去 24h 各分支提交 + 活跃 PR）

> 以下问题由多个 subagent 对过去 24 小时内各分支提交与活跃 PR（#112/#129/#131/#133/#135/#137/#139/#119）进行深度审查发现，并经独立 subagent 交叉复审确认。其中 REV56 为本批次修复（本 PR）；其余为对仍 OPEN 的 PR 的阻断性/残余风险记录，已通过 PR 评论通知维护者。

### REV56: `Server.Close()` 非幂等，二次调用 panic（`close of closed channel`）[已修复]
- **修复状态**: 已修复（本审查批次，分支 `fix/server-close-cleanupstop-idempotent-rev56`）
- **位置**: `server/api/main.go` (`Server.Close()`, `Server.cleanupStop`)
- **问题描述**: `Server.Close()` 对 `s.cleanupStop` 直接 `close(...)` 无 `sync.Once` 保护；且 `rateLimiter.Stop()`（在 dev 上仍非幂等，因 REV53/PR #131 尚未合并）也在每次 `Close()` 中被调用。第二次调用 `Close()` 会触发 `close of closed channel` panic（`s.cleanupStop` 或 `rl.stopCh` 二者之一先触发）。REV53 在其"关联"字段中已标注 "`Server.Close()` 中 `close(s.cleanupStop)` 存在同型非幂等缺陷，留待后续单独处理"，本条目即将其正式追踪并修复。
- **触发场景**: 对同一 `*Server` 调用两次 `Close()`。当前生产调用方（main 中的 graceful shutdown）各只调用一次，故为**潜在（latent）**；但 (1) 测试中 `defer server.Close()` 与显式 `server.Close()` 并存时即触发 panic；(2) 一旦给 API server 增加 signal handler 调用 `Close()`（与 socks5/tunnel 一致的模式），或在 `Server.Close()` 失败后上层重试，即转为线上崩溃。
- **影响**: **中高（崩溃）**。崩溃路径位于关闭流程，修复零风险。
- **验证**: 回归测试 `TestServerCloseIsIdempotent`——构造 server、`startCleanupWorkers()`、首次 `Close()` 成功并确实关闭 db（`db.Ping()` 报错），随后连续调用 3 次 `Close()` 既不 panic 也不返回 error。已验证：dev 未修复版本该测试 FAIL（`Close() call #2 panicked: close of closed channel`），修复版本 PASS；全套 `go test -race` 通过，gofmt/vet/build 干净。
- **修复方式**: `Server` 新增 `closeOnce sync.Once` 字段；`Close()` 将**全部** teardown（`rateLimiter.Stop()` + `close(cleanupStop)` + `cleanupWorkers.Wait()` + `db.Close()`）收敛进 `closeOnce.Do(func(){...})`，错误经局部 `dbErr` 返回。首次调用执行全部 teardown 并返回 `db.Close()` 的错误；后续调用 `Do` 跳过、返回 `nil`。这样 `Server.Close()` 的幂等性**不再依赖**子组件（`rateLimiter.Stop()`/`close(cleanupStop)`）自身是否幂等——与 REV53 对 `RateLimiter.Stop()` 的修复形成纵深防御。
- **关联**: REV53（`RateLimiter.Stop()` 非幂等，PR #131 修复中）、issue #90 G1。本条目正式追踪 REV53"关联"字段中"留待后续单独处理"的 `Server.Close()` 同型缺陷。
- **交叉验证**: 两个独立 subagent 复审确认（panic 在 dev 上可复现 + 生产调用链可达性分析 + 修复后 `go test -race` 全绿）。

### REV52-补充: PR #112 提交 `84c0691` 额外回退 MAKEFILE-TIDY-NOOP-1 修复并删除其回归测试 [待修复 — 阻塞 PR #112/#137 合并]
- **状态**: 待修复（已在 PR #112 与 PR #137 留下阻断评论）
- **位置**: `Makefile`（`go-ci-component`/`go-quality-component` recipe）、`scripts/test_makefile_tidy_check.py`（整文件删除）
- **问题描述**: REV52 已记录提交 `84c0691` 大规模回退 dev 的安全/数据完整性修复（6 项）。本次审查发现 `84c0691` **还额外回退了两项未被 REV52 逐条列出的内容**：
  1. `Makefile` 的 `go-ci-component`/`go-quality-component` recipe 退回为 `(cd $$dir && git diff --exit-code -- go.mod go.sum >/dev/null 2>&1; git checkout -- go.mod go.sum 2>/dev/null || true)`——丢弃 `rc=$$?` 与 `exit $$rc`，子 shell 末尾 `|| true` 恒返回 0，**未 tidy 的 go.mod/go.sum 漂移被静默放行**（重新引入已修复的 MAKEFILE-TIDY-NOOP-1）。
  2. 整文件删除 `scripts/test_makefile_tidy_check.py`（241 行，6 个用例）——即删除唯一能捕获上述回归的测试，与 PR #129 夹带 `continue-on-error` 并规避守卫的手法同构。
- **影响**: 若 PR #112 或 PR #137（base 继承 PR #112）合并，go.mod/go.sum 漂移将不再被 CI 检出，依赖图篡改/漂移可静默合入。`merge-tree` 干跑合并 #137→dev 后确认 Makefile 为 buggy no-op 形态、`test_makefile_tidy_check.py` MISSING。
- **风险**: **高（供应链/质量门禁）**。
- **修复方式**: 维护者应按 REV52 建议丢弃 `84c0691`（仅保留 `c62aa5e` 预编译优化 + `4556282` 的 `db.Close()` + `d14d8e0` 测试），或 `git revert 84c0691` 后基于 dev 解决冲突。本条目不单独发修复分支（dev 上 Makefile 与测试均正确，仅需阻断问题 PR）。
- **交叉验证**: 两个独立 subagent 复审确认（`merge-tree` 干跑 + 逐行 recipe 比对）。

### REV55-残余: 持续投毒可维持 `device_status` 锁定，"5 分钟自愈"承诺在持续攻击下不成立 [残余风险 — 待评估]
- **状态**: 残余风险（已在 PR #137 留下评论）
- **位置**: `server/api/main.go` (`updateDeviceStatus` 的 `LastSeenFutureTolerance` cap)
- **问题描述**: REV55（PR #137）将客户端 `last_seen` 上界设为 `now + 5min`，commit message 与 ISSUES.md 均声称"修复后单次投毒最多卡 5 分钟（自愈）"。但该自愈保证**仅在攻击者发送单次请求后停止时成立**：攻击者每 ≤5 分钟发送一次 `{"device_id":"victim","status":"offline","last_seen":<now+5min-1ms>}`，每次都在容差内通过 cap、且 `last_seen` 严格递增通过单调守卫，受害设备的真实上报（`last_seen ≈ now`）始终落后约 5 分钟被持续拒绝。设备状态可被**永久**锁定为 `offline`，无自愈。
- **影响**: 工程师端永久看到设备离线（实际在线）。**不影响流量路由**——socks5-proxy 通过固定 `TunnelEndpoint` 连接隧道服务器，不读取 `device_status`。故严重度为 MEDIUM（可见性 DoS），且需攻击者持续持有共享 `INTERNAL_API_KEY`。
- **与原 REV55 的区别**: 原 REV55 允许**单次请求**永久锁定；修复后单次请求最多锁 5 分钟，但**持续攻击**仍可维持永久锁定，攻击频率仅需每 5 分钟一次。
- **建议**: 评估是否对 `device_id` 维度增加速率限制/异常频率检测，或在文档中明确修正"自愈"承诺的适用前提。因涉及 REV33/REV51 事件排序语义的设计权衡，本批次不单独实施代码修复。
- **交叉验证**: 两个独立 subagent 复审确认（Go 模拟 20 分钟攻击场景，存储状态始终为 `offline`）。

---

## 提交后正确性检查发现（2026-07-30，多 subagent 审查过去 24h 各分支提交 + 活跃 PR）

> 以下问题由多个 subagent 对过去 24 小时内各分支提交与活跃 PR（#163/#159/#154/#165/#161/#108 等）进行深度审查发现。服务端 Go 并发/安全 PR（#163 REV61、#159 REV60、#154 REV59）经多 subagent 复审确认**无未记录缺陷**——其真实缺陷（createSessionToken 双花、tunnel Register last_seen 竞争）已被同分支 REV61 修复并在本文档记录。UI 过渡动画 PR #165 发现 1 项未记录缺陷（REV62），已在本批次修复。

### REV62: `ConnectionStatusCard` 旋转图标 与 `VpnStatusCard` 指示灯 在 AnimatedContent 过渡期间绑定到**当前** UiState 而非**逐行 target** 状态，导致淡出行图标/文字与旋转图标/指示灯短暂不一致 [已修复]
- **修复状态**: 已修复（本审查批次，分支 `fix/rev62-status-card-crossfade-target-binding`，基于 PR #165 head）
- **位置**: `android/app/src/main/java/com/netproxy/gateway/ui/screens/MainScreen.kt`
  - `ConnectionStatusCard`：`AnimatedContent(...) { targetStatus -> ... }` 内的 spinner 判定（原 `if (uiState.mqttState == MqttUiState.Connecting)`，约 497 行）
  - `VpnStatusCard`：`AnimatedContent(...) { targetVpnData -> ... }` 内的指示灯颜色（原 `if (uiState.isVpnEnabled) statusColors.success else ...`，约 769 行）
- **问题描述**: 两张状态卡都使用 `AnimatedContent(targetState = statusData/vpnData)` 做交叉淡入淡出。Compose 的 `AnimatedContent` 在过渡期间会**同时**组合旧（淡出）与新（淡入）两行，且两行的 lambda 闭包都捕获**当前** `uiState`。PR #165 已把 `Icon`/`Text` 绑定到逐行 target 参数（`targetStatus.icon`/`targetStatus.textRes`、`targetVpnData.icon`/`targetVpnData.textRes`），但 spinner 判定与 VPN 指示灯颜色**仍读取外层共享的 `uiState`**，没有跟随 target。结果：淡出行渲染的 spinner/指示灯属于**新**状态，而其图标/文字属于**旧**状态，二者在 ~300ms 过渡窗口内不一致。
  - **REV62-A（MQTT）**: `Connected → Connecting` 过渡时，淡出的 "Connected" 行因 `uiState.mqttState` 已变为 `Connecting` 而显示旋转图标（"Connected" 文字 + 旋转图标）；反向 `Connecting → Connected` 过渡时，淡出的 "Connecting" 行（`icon == null`）因 `uiState.mqttState` 已变为 `Connected` 而落入 `else if (targetStatus.icon != null)` 为假，**既无旋转图标也无图标**，只剩光秃秃的 "Connecting" 文字。
  - **REV62-B（VPN）**: `Running → Stopped` 过渡时，淡出的 "Running" 行因 `uiState.isVpnEnabled` 已变为 false 而显示灰色指示灯（"Running" 文字 + 灰点）；反向 `Stopped → Running` 时，淡出的 "Stopped" 行显示绿色指示灯（"Stopped" 文字 + 绿点）。
- **触发场景**: 任意触发状态卡 `AnimatedContent` 切换的用户可感知事件——MQTT 连接建立/断开/重连、用户点击 VPN 开关启停。过渡窗口 ~300ms（默认 `tween`），淡出行起始 alpha=1.0，不一致在最初 ~100-150ms 近满透明度下肉眼可见。
- **影响**: **MEDIUM（可见的功能退化）**。瞬态、自愈（稳态正确），无数据丢失/崩溃/安全影响；但状态指示器是用户判断连接/VPN 状态的关键 UI，过渡期间图标/文字与指示灯自相矛盾会误导用户。PR #165 自身提交 "Bind animated content rendering to target state" 表明作者已知此类问题并修复了图标/文字，但遗漏了 spinner/指示灯。
- **验证**: 回归测试 `StatusCardBindingTest`（`android/app/src/test/java/com/netproxy/gateway/ui/screens/StatusCardBindingTest.kt`）锁定修复所依赖的不变式：`showsSpinner()` 仅在 `icon == null`（Connecting）时为真、其余状态为假；`isVpnRunning()` 仅在 `textRes == R.string.vpn_running`（运行行）时为真。该测试在 `:app:testDebugUnitTest`（CI 已运行）下执行，无需 emulator。
- **修复方式**: 新增两个 `internal` 扩展函数 `StatusCardData.showsSpinner(): Boolean = icon == null` 与 `StatusCardData.isVpnRunning(): Boolean = textRes == R.string.vpn_running`，并将两处判定改为基于逐行 target：`if (targetStatus.showsSpinner())` 与 `if (targetVpnData.isVpnRunning())`。`StatusCardData` 由 `private` 改为 `internal` 以供同模块测试访问。稳态行为与原实现等价（Connecting 是唯一 `icon == null` 的状态；运行态即 `vpn_running`），仅在过渡窗口修正绑定来源。修改最小、高置信度。
- **关联**: PR #165（`ux-status-transitions-13816424101316795215`）、PR #161。本修复分支基于 PR #165 head，作为其 fix-up PR。
- **交叉验证**: 三个独立 subagent 复审确认（1 个发现 subagent + 2 个独立验证 subagent 逐行核对 `MainScreen.kt` 497/503-512 与 737/745-763/769 行，确认 spinner/指示灯读取外层 `uiState` 而图标/文字读取逐行 target，CONFIRMED）。

### REV61 复审确认（无新缺陷）
- 服务端并发 PR #163（`fix/rev61-doublespend-tunnel-race`）经多 subagent 复审确认：`createSessionToken` 双花修复（`BEGIN IMMEDIATE` 事务包裹乐观锁 UPDATE + COUNT + INSERT）与 tunnel `Register` last_seen 在 WLock 下捕获均正确完整，`go test -race` 通过。原缺陷已在本文件 REV61-A1/A2 记录。未发现未记录缺陷。

### REV59/REV60 复审确认（无新缺陷）
- 安全/安全守卫 PR #154（REV59）、#159（REV60）经多 subagent 复审确认：MqttConnectionManager trust-all 守卫、DebugDetector 双路径 OR 语义与 `@Volatile` 防 DCE、VpnPacketProcessor TCP 校验和边界、CORS allowlist、createSessionToken 过期检查与乐观锁、upsertDeviceStatusDB last_seen 单调守卫、GET /pair/:code 限流、tunnel Unregister/cleanup REV32 重检均完整正确。CI 门禁未被削弱。`go test -race` 通过。未发现未记录缺陷。

> 以下问题由多个 subagent 对过去 24 小时内各分支提交与活跃 PR（#170/#168/#169/#161/#159/#163/#165/#166/#154 等）进行深度审查发现。服务端 Go 并发/安全 PR（#163 REV61、#159 REV60、#154 REV59）与 REV62 修复（PR #168）在本窗口内**无新代码提交**或经独立 subagent 复审确认无未记录缺陷。UI 平滑过渡 PR #170 发现 1 项未记录缺陷（REV63，与 REV62 同型），已在本批次修复。

### REV63: `NetworkStatusRow` 的图标 `tint` 与文本 `color` 在 `Crossfade` 过渡期间绑定到**外层** `animateColorAsState` 而非**逐行 `isActive` 目标**，导致淡出行图标/文字与颜色短暂不一致 [已修复]
- **修复状态**: 已修复（本审查批次，分支 `fix/rev63-networkstatusrow-color-crossfade-target`，基于 PR #170 head `4bd6f8b`）
- **位置**: `android/app/src/main/java/com/netproxy/gateway/ui/screens/MainScreen.kt`（`NetworkStatusRow`，原约 838–864 行）
  - `Crossfade(targetState = active, label = "network_status_icon") { isActive -> Icon(..., tint = animatedColor) }`（`tint` 原读取外层 `animatedColor`，约 852 行）
  - `Crossfade(targetState = active, label = "network_status_text") { isActive -> Text(..., color = animatedColor) }`（`color` 原读取外层 `animatedColor`，约 864 行）
  - 外层 `val animatedColor by animateColorAsState(targetValue = if (active) activeColor else inactiveColor, ...)`（约 838 行，由外层共享 `active` 驱动）
- **问题描述**: PR #170（`4bd6f8b`）为 `NetworkStatusRow` 引入 `Crossfade` 做图标/文字交叉淡入淡出，并正确地把 `imageVector`/`text` 绑定到逐行 lambda 参数 `isActive`，却把 `Icon.tint` 与 `Text.color` 绑定到外层 `animateColorAsState` 产生的**单一共享** `animatedColor`。Compose 的 `Crossfade`（基于 `AnimatedContent`）在过渡期间会**同时**组合旧（淡出，`isActive` = 旧状态）与新（淡入，`isActive` = 新状态）两行，而两行的 lambda 闭包都读取**同一个** `animatedColor`——它正由旧颜色向新颜色补间。结果：淡出行渲染**旧**图标却被"正趋向新颜色"染色，淡入行渲染**新**图标却被"仍接近旧颜色"染色，二者在 ~300ms 过渡窗口内图标与颜色自相矛盾。这与 REV62（PR #168）修复的反模式**完全同构**：把应随逐行 target 变化的派生视觉属性绑定到随外层共享状态变化的单一值。该提交自身新增的 `.Jules/palette.md` 已明文要求"When using `Crossfade`, always compute derived properties ... inside the lambda using the passed `targetState` parameter"，但 `tint`/`color` 违反了该规约。
  - **REV63-A（WiFi 断开→连接）**：淡出的 `WifiOff`（灰）图标被染上趋向 `success`（绿）的颜色；淡入的 `Wifi`（绿）图标被染上仍偏灰的颜色。
  - **REV63-B（蜂窝 连接→断开 等）**：同理，淡出行/淡入行图标与颜色在过渡期不一致。
- **触发场景**: 任意触发 `NetworkStatusRow` 的 `Crossfade` 切换的用户可感知事件——WiFi 或蜂窝网络连接/断开（`uiState.wifiConnected`/`uiState.cellularConnected` 翻转）。`NetworkStatusRow` 在 `NetworkInfoCard` 中为 WiFi 行与蜂窝行各调用一次（`activeColor = statusColors.success` 绿 / `inactiveColor = MaterialTheme.colorScheme.outline` 灰）。过渡窗口 ~300ms（默认 `tween`），淡出行起始 alpha=1.0，不一致在最初 ~100–150ms 近满透明度下肉眼可见。
- **影响**: **MEDIUM（可见的功能退化）**。瞬态、自愈（稳态正确），无数据丢失/崩溃/安全影响；但网络状态行是用户判断 WiFi/蜂窝连接状态的关键 UI，过渡期间图标与颜色自相矛盾会误导用户。PR #170 自身提交 "add smooth transitions to NetworkStatusRow" 表明作者意图平滑过渡，但遗漏了颜色也须跟随逐行 target，重蹈 REV62 的覆辙。
- **验证**: 回归测试 `NetworkStatusRowColorTest`（`android/app/src/test/java/com/netproxy/gateway/ui/screens/NetworkStatusRowColorTest.kt`）锁定修复所依赖的不变式：`networkStatusRowColor(isActive, activeColor, inactiveColor)` 在 `isActive=true` 时返回 `activeColor`、`isActive=false` 时返回 `inactiveColor`——即颜色是逐行 target 的纯函数，每行图标与颜色始终自洽。该测试在 `:app:testDebugUnitTest`（CI 已运行）下执行，无需 emulator。
- **修复方式**: 新增 `internal` 函数 `networkStatusRowColor(isActive: Boolean, activeColor: Color, inactiveColor: Color): Color = if (isActive) activeColor else inactiveColor`，将两处 `tint`/`color` 改为 `networkStatusRowColor(isActive, activeColor, inactiveColor)`，并**删除**外层冗余的 `animateColorAsState`/`animatedColor`。稳态行为与原实现等价（`isActive` 稳态即 `active`），仅在过渡窗口修正绑定来源：每行图标与颜色同源，混合完全交给 `Crossfade` 的不透明度。修改最小、高置信度，与 REV62 修复模式一致。
- **关联**: PR #170（`palette/smooth-network-status-transition-7711183361486332504`）。本修复分支基于 PR #170 head，作为其 fix-up PR（base = PR #170 head 分支），与 REV62 作为 PR #165 的 fix-up PR（PR #168）的提交流程一致。同型缺陷：REV62（PR #168）。
- **交叉验证**: 三个独立 subagent 复审确认（1 个发现 subagent + 2 个独立验证 subagent 逐行核对 `MainScreen.kt` 838/852/864 行：`tint`/`color` 读取外层 `animatedColor` 而图标/文字读取逐行 `isActive`；并从 Compose `Crossfade`/`AnimatedContent` 同时组合旧/新两行 + `animateColorAsState` 产生单一共享值的语义推演过渡窗口错配，CONFIRMED）。

---

## 提交后正确性检查发现（2026-07-23，多 subagent 审查 PR #112 / #108 / #131）

> 以下问题由多个 subagent 对过去 24 小时内各分支提交与活跃 PR 进行深度审查发现。PR #108（MqttConnectionManager / Socks5ProxyHandler / DebugDetector）与 PR #131（ratelimit Stop 幂等）经独立复审，未发现新引入缺陷。PR #112 发现一处高严重程度缺陷（REV55）。

### REV55: server/api `updateDeviceStatus` 接受无上界的客户端 `last_seen`，配合 REV33 单调守卫可被永久投毒锁定设备状态 [已修复]
- **修复状态**: 已修复
- **修复难度**: 低
- **位置**: `server/api/main.go` (`updateDeviceStatus` handler, `upsertDeviceStatusDB`)
- **问题描述**: PR #112（REV33 优化）同时引入了两处改动，二者组合形成本缺陷：
  1. `updateDeviceStatus` 请求体新增客户端可控字段 `LastSeen int64 json:"last_seen"`，且**唯一校验为 `> 0`，无任何上界**——`lastSeen = time.UnixMilli(req.LastSeen)` 直接入库。
  2. `upsertDeviceStatusDB` 的 `ON CONFLICT(device_id) DO UPDATE SET ... WHERE excluded.last_seen > device_status.last_seen` 是严格单调守卫，且 handler 丢弃 `sql.Result`、不检查 `RowsAffected()`，WHERE 不满足时仍返回 `200 {"status":"updated"}`。
  - 两者单独存在均无害（main 上无 WHERE，下次 `time.Now()` 直接覆盖毒行；无 client `last_seen` 则值由服务端决定无法注入未来时间）。**两者组合才形成永久锁定**。
- **触发场景**:
  1. 持有共享 `INTERNAL_API_KEY` 的调用方发送 `POST /api/device/status`，header `X-Internal-API-Key: <key>`，body `{"device_id":"victim-device","status":"offline","last_seen":32503680000000}`（公元 3000 年，或直接用 `math.MaxInt64`）。首次走 INSERT 分支，远未来 `last_seen` 被写入。
  2. 受害设备随后用真实 `time.Now()`（约 `1.7e12` ms）上报 `online`。SQLite 执行 ON CONFLICT UPDATE 时 `excluded.last_seen(1.7e12) > device_status.last_seen(3.25e13)` 为 **false**，UPDATE 被 **静默跳过**；`db.Exec` 返回 `nil`，handler 返回 `200 {"status":"updated"}`。
  3. 设备状态永久卡在 `offline`，`tunnel_addr` 也被一并锁死，**无自纠正心跳**（无 TTL/清理任务）。唯一恢复手段是人工直连 DB `DELETE/UPDATE`。
- **影响范围**:
  - **数据完整性**：`device_status` 行被不可恢复地锁定为错误状态。
  - **DoS**：设备在工程师端永久显示离线（即使实际在线、SOCKS5 连通正常），状态视图与实际不符；上报"看似成功"故无告警。
  - **跨设备**：`device_id` 完全由客户端 body 提供，`internalAuthMiddleware` 只校验**所有设备共享**的 `X-Internal-API-Key`、不绑定 `device_id`。攻击者一次请求即可投毒任意/全部设备，**全设备状态瘫痪**。
- **风险**: **高**。前提是攻击者持有 internal API key（内部信任边界内的凭据），但一旦满足即为确定性、静默、永久、可全设备的 DoS，且无日志告警。
- **修复方式**:
  - 在 `updateDeviceStatus` 中对客户端 `last_seen` 增加上界校验：`candidate := time.UnixMilli(req.LastSeen)`；若 `candidate.After(time.Now().Add(LastSeenFutureTolerance))`（默认 5 分钟），返回 `400 Bad Request`（`ErrLastSeenTooFarInFuture`）并 `log.Printf` 告警。
  - 5 分钟容差吸收 tunnel 服务器（唯一合法 caller）与 API 服务器之间的正常 NTP 时钟偏差；保留合法范围内的客户端时间戳（不破坏 REV33/REV51 的"事件决策顺序"语义）。
  - 不采用"完全忽略 client `last_seen` 永远用 `time.Now()`"的方案——它会重引入 REV51 要消除的"到达顺序 ≠ 决策顺序"错乱（见 REV51 分析）。
  - 修复后单次投毒最多卡 5 分钟（容差上限），不再永久；容差外的明显未来时间戳被显式拒绝。
- **测试覆盖**:
  - `TestUpdateDeviceStatus_RejectsFutureLastSeen`：远未来时间戳（+1 年）返回 400，行不创建。
  - `TestUpdateDeviceStatus_FutureLastSeenDoesNotPermanentlyLockRow`：核心回归——投毒尝试被 400 拒绝后，合法上报正常生效，状态正确为 `online`。
  - `TestUpdateDeviceStatus_AcceptsLastSeenWithinFutureTolerance`：容差内（+1s）的未来时间戳被接受且原值保留，证明不误伤合法钟差。
- **关联**: 本缺陷由 PR #112 引入（即 REV33 优化合入时引入）。修正 REV33"修复方式"中"`updateDeviceStatus` 接受请求体中的可选 `last_seen`"未提及上界校验与投毒风险的疏漏。修复分支 `fix/device-status-lastseen-future-cap-rev55`，基于 PR #112 分支。

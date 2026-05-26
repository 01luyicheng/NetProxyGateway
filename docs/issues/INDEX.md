# 问题清单索引

> 最后验证: commit f3722c8
> 生成日期: 2026-05-26
> 生成模型: SOLO

## 活跃问题

| 编号 | 标题 | 模块 | 严重程度 | 代码指纹 | 引入提交 |
|------|------|------|----------|----------|----------|
| H4 | SOCKS5代理DNS重绑定攻击风险 | proxy | High | Socks5ProxyHandler/validateTargetAddress/DNS解析 | e89e00d |
| H5 | 连接池清理竞争条件（残余） | proxy | High | Socks5ConnectionPool/cleanupIdleConnections/write锁内阻塞IO | 1f9acee |
| H8 | MQTT TLS证书固定配置可能为空 | connection | High | MqttConnectionManager/createProductionSocketFactory/空配置回退 | e89e00d |
| H12 | activeConnections复合操作非原子 | vpn | High | VpnService/forwardViaSocks5/检查-获取-更新 | d01ddd1 |
| H14 | processTcpReturn阻止0长度TCP控制包注入 | vpn | High | VpnService/processTcpReturn/available>0门槛 | d01ddd1 |
| H15 | VpnService测试直接实例化Android Service | vpn | High | VpnServiceTest/直接实例化/生命周期违规 | d01ddd1 |
| H16 | VpnService测试过度使用反射 | vpn | High | VpnServiceTest/反射访问/维护困难 | d01ddd1 |
| N27 | Socks5ConnectionPool cleanupIdleConnections在write锁内执行阻塞IO | proxy | High | Socks5ConnectionPool/removeConnection/close阻塞 | 1f9acee |
| N28 | Socks5ProxyHandler RelayHandler释放语义优化引入新问题 | proxy | High | Socks5ProxyHandler/RelayHandler/safeRelease | 4de9b42 |
| N30 | Socks5ProxyHandler DNS解析阻塞EventLoop | proxy | High | Socks5ProxyHandler/InetAddress.getByName/同步阻塞 | 1f9acee |
| N34 | MainViewModel VPN状态与真实服务状态可能不一致 | ui | High | MainViewModel/toggleVpn/缺少ServiceConnection | 1f9acee |
| N36 | VpnService TCP固定标志位不符合协议状态机 | vpn | High | VpnService/constructReturnPacket/PSH+ACK固定 | 1f9acee |
| N37 | AuthSessionStore CharArray安全设计被String抵消 | connection | High | AuthSessionStore/loadSession/CharArray转String | 1f9acee |
| N43 | MqttConnectionManager MQTT回调无法注销导致内存泄漏 | connection | Medium | MqttConnectionManager/subscribeWithResult/无unsubscribe | 1f9acee |
| N56 | StreamConn deadline方法空实现导致goroutine泄漏 | server | High | server/socks5-proxy/SetReadDeadline/空实现 | 9f4b1b9 |
| N57 | VpnService processReturnTraffic单协程串行处理模型导致回包处理停滞 | vpn | Medium | VpnService/processReturnTraffic/forEach串行 | d01ddd1 |
| N58 | VpnService processTcpReturn依赖InputStream.available()不可靠 | vpn | Medium | VpnService/processTcpReturn/available估计值 | d01ddd1 |
| N59 | M13修复后首次连接失败的首轮重连延迟变为10秒 | connection | Low | MqttConnectionManager/onReconnectAttemptFailed/首次倍增 | 4e965e2 |
| N66 | VirtualIpAllocator分配失败时nextVirtualIp泄漏 | vpn | Low | VirtualIpAllocator/getOrAllocateVirtualIp/require前递增 | 690d572 |
| N70 | StreamConn.SetReadDeadline更新无法被阻塞中的Read感知 | server | Low | server/socks5-proxy/SetReadDeadline/动态更新无效 | 15414b05 |
| N72 | readLoop defer不调用detachTunnel() | server | Low | server/socks5-proxy/readLoop/defer缺detachTunnel | 15414b05 |
| N77 | server/tunnel sendLoop/readLoop存在数据竞争风险 | server | Medium | server/tunnel/sendLoop/connMu缺失 | 15414b05 |
| C2 | 循环依赖风险（ModuleCoordinator作为Event Bus） | di | Medium | ModuleCoordinator/modules Map/生命周期管理 | 1f9acee |
| C3 | 测试工具类与生产代码逻辑重复 | vpn | Low | VpnTestUtils/复制生产代码/同步风险 | 1f9acee |
| C4 | 内存中敏感数据处理不当 | connection | Medium | AuthSessionStore/loadSession/String转换 | 1f9acee |
| C5 | 服务端缺少共享库 | server | Low | server/api,socks5-proxy,tunnel/代码重复 | 1f9acee |
| C6 | 缺少API契约定义 | server | Low | 全局/无protobuf或OpenAPI | 1f9acee |
| C7 | Go项目结构不规范 | server | Low | server/未按标准分层 | 1f9acee |
| C8 | VpnService清理逻辑不一致 | vpn | Medium | VpnService/stopVpn与onDestroy/顺序不一致 | 1f9acee |
| C9 | Tunnel Gateway单点故障与状态丢失风险 | server | Medium | server/tunnel/内存状态/无状态化 | 1f9acee |
| C10 | 服务发现硬编码与迁移成本 | server | Low | docker-compose/静态配置 | 1f9acee |
| C12 | VpnServiceTest未验证关键生命周期场景 | vpn | Medium | VpnServiceTest/缺少stop-start集成测试 | 1f9acee |
| C14 | 函数过长且职责不单一 | vpn,connection | Medium | MqttConnectionManager/connect/240行 | 1f9acee |
| C15 | 嵌套层级过深 | vpn | Low | VpnService/forwardViaSocks5/多层嵌套 | 1f9acee |
| C16 | 代码缺少适当分组和空行 | vpn,proxy | Low | VpnService/属性定义无分组 | 1f9acee |
| C17 | Go服务端代码风格不一致 | server | Low | server/错误处理风格不一致 | 1f9acee |
| C18 | generateSecureRandomString性能可优化 | server | Low | server/api/循环分配内存 | 1f9acee |
| C19 | 开发模式安全检查可进一步增强 | server | Low | server/api/仅检查ENABLE_TLS | 1f9acee |
| C20 | VpnService日志模板格式不一致 | vpn | Low | VpnService/日志分隔符不一致 | fc9552b |
| C21 | VpnLogRedaction缺少KDoc文档 | vpn | Low | VpnLogRedaction/无文档注释 | fc9552b |
| C22 | VpnLogRedaction魔法值未命名 | vpn | Low | VpnLogRedaction/字面量常量 | fc9552b |
| C29 | VpnService forwardViaWifi TCP无响应读取 | vpn | Medium | VpnService/forwardViaWifi/只发不收 | 1f9acee |
| C30 | VpnService cleanupStaleConnections removeIf遍历风险 | vpn | Medium | VpnService/cleanupStaleConnections/removeIf阻塞 | 1f9acee |
| C33 | VpnService processReturnTraffic遍历视图不一致 | vpn | Medium | VpnService/processReturnTraffic/forEach修改 | 1f9acee |
| C34 | VirtualIpAllocator锁内require异常导致死锁 | vpn | Medium | VirtualIpAllocator/synchronized/require崩溃 | 1f9acee |
| C35 | VirtualIpAllocator破坏外部AtomicInteger封装 | vpn | Medium | VirtualIpAllocator/nextVirtualIp.set/外部参数 | 1f9acee |
| C36 | Socks5ConnectionPool borrowConnection连接追踪泄漏 | proxy | Medium | Socks5ConnectionPool/borrowConnection/异常泄漏 | 1f9acee |
| C37 | Socks5ConnectionPool returnConnection O(n)性能瓶颈 | proxy | Medium | Socks5ConnectionPool/returnConnection/遍历计数 | 1f9acee |
| C38 | Socks5ConnectionPool readFully无限循环风险 | proxy | Medium | Socks5ConnectionPool/readFully/持续返回0 | 1f9acee |
| C39 | Socks5ProxyHandler NettyOutboundConnector未设置连接超时 | proxy | Medium | Socks5ProxyHandler/Bootstrap/无CONNECT_TIMEOUT | 1f9acee |
| C40 | Socks5ProxyService closeFuture.sync阻塞协程 | proxy | Medium | Socks5ProxyService/closeFuture.sync/协程泄漏 | 1f9acee |
| C41 | Socks5ProxyService shutdownGracefully无超时 | proxy | Low | Socks5ProxyService/shutdownGracefully/默认无超时 | 1f9acee |
| C42 | MqttConnectionManager subscribe回调可重复注册 | connection | Medium | MqttConnectionManager/subscribeWithResult/无去重 | 1f9acee |
| C43 | MqttConnectionManager startHeartbeat并发启动风险 | connection | Medium | MqttConnectionManager/startHeartbeat/cancel非原子 | 1f9acee |
| C44 | NetworkStateManager onAvailable瞬态未验证状态 | connection | Medium | NetworkStateManager/onAvailable/VALIDATED前触发 | 1f9acee |
| C45 | AuthSessionStore锁内执行加密磁盘IO可能ANR | connection | Medium | AuthSessionStore/@Synchronized/EncryptedSharedPreferences | 1f9acee |
| C46 | GatewayWifiManager activeSuggestions非线程安全 | wifi | Medium | GatewayWifiManager/activeSuggestions/普通List | 1f9acee |
| C47 | GatewayWifiManager disconnect未移除遗留网络配置 | wifi | Medium | GatewayWifiManager/disconnect/无removeNetwork | 1f9acee |
| C48 | GatewayWifiManager hashCode去重不可靠 | wifi | Low | GatewayWifiManager/distinctBy/hashCode碰撞 | 1f9acee |
| C49 | AppModule新分离式接口未提供Hilt绑定 | di | Medium | AppModule/ModuleInterfaces/无Binds | 1f9acee |
| C50 | ModuleCoordinator modules Map非线程安全 | di | Medium | ModuleCoordinator/mutableMapOf/并发修改 | 1f9acee |
| C51 | ModuleCoordinator SharedFlow事件静默丢失 | di | Medium | ModuleCoordinator/MutableSharedFlow/buffer=0 | 1f9acee |
| C52 | ModuleCoordinator强引用导致生命周期对象泄漏 | di | Medium | ModuleCoordinator/registerModule/强引用 | 1f9acee |
| C53 | SecurityManager安全检测报告包含敏感信息 | security | Medium | SecurityManager/getSecurityReport/设备指纹 | 1f9acee |
| C54 | AppAuditLogStore日志内容未脱敏 | debug | Medium | AppAuditLogStore/sanitizeMessage/未脱敏IP | 1f9acee |
| C55 | MainScreen配对码输入无验证 | ui | Medium | MainScreen/OutlinedTextField/无过滤 | 1f9acee |
| C56 | MainScreen审计日志滚动位置丢失 | ui | Low | MainScreen/AuditLogsScreen/无rememberLazyListState | 1f9acee |
| C57 | server/socks5-proxy relay错误处理不完整 | server | Low | server/socks5-proxy/relay/丢弃第二个错误 | 1f9acee |
| C58 | server/socks5-proxy relay测试flaky | server | Low | server/socks5-proxy/main_test/固定超时 | 1f9acee |
| C59 | server/tunnel sendLoop双重select效率低 | server | Low | server/tunnel/sendLoop/嵌套select | 1f9acee |
| C60 | server/tunnel notify测试仍有flaky风险 | server | Low | server/tunnel/main_test/300ms固定窗口 | 1f9acee |
| C61 | server/socks5-proxy IPFilter IPv6处理不完整 | server | Low | server/socks5-proxy/IPFilter/仅IPv4 | 1f9acee |
| C62 | VpnService connectionKey格式未来IPv6冲突 | vpn | Low | VpnService/connectionKey/冒号歧义 | 1f9acee |
| C63 | VpnService onDestroy重复调用stopProxyService | vpn | Low | VpnService/onDestroy/冗余调用 | 1f9acee |
| C64 | VpnDnsConfig isValidIpv4接受前导零 | vpn | Low | VpnDnsConfig/isValidIpv4/01合法 | 1f9acee |
| C65 | VpnLogRedaction IPv6验证缺陷 | vpn | Low | VpnLogRedaction/isValidIpv6/空字符串 | 1f9acee |
| C66 | AuthSessionStore constantTimeEquals空指针风险 | connection | Low | AuthSessionStore/constantTimeEquals/无null检查 | 1f9acee |
| C67 | GatewayWifiManager WiFiScan Flow receiver注销竞态 | wifi | Low | GatewayWifiManager/callbackFlow/awaitClose | 1f9acee |
| C68 | ModuleInterfaces新接口返回类型设计缺陷 | di | Low | ModuleInterfaces/publish返回Unit | 1f9acee |
| C69 | MqttTlsPinning每次创建新MessageDigest | connection | Low | MqttTlsPinning/createPinningTrustManager/SHA-256 | 1f9acee |
| C70 | server/api管理员密码明文存储 | server | Medium | server/api/ADMIN_PASS/字符串比较 | f8497b8 |
| C71 | server/tunnel Stats接口单一Token长期有效 | server | Medium | server/tunnel/authorizeStats/无过期 | f8497b8 |
| C72 | 内部API调用缺少重试和熔断机制 | server | Medium | server/validateWithAPI/无重试 | f8497b8 |
| C73 | Go服务端缺少结构化日志 | server | Low | server/标准库log/无级别 | f8497b8 |
| C74 | SOCKS5连接池缺少并发回归测试 | proxy | Medium | Socks5ConnectionPoolTest/单线程场景 | f8497b8 |
| C76 | VpnService回包缓冲区缺少分配行为回归测试 | vpn | Low | VpnServiceTest/未验证局部变量分配 | d01ddd1 |
| C77 | Socks5ProxyHandler double-free修复缺少write-failure回归测试 | proxy | Low | Socks5ProxyHandlerTest/未覆盖write失败 | 9f4b1b9 |
| C78 | VpnService回包路径缺少TCP状态机 | vpn | Medium | VpnService/constructReturnPacket/无状态机 | 1f9acee |
| C79 | StreamConn deadline方法空实现导致goroutine泄漏 | server | High | server/socks5-proxy/SetReadDeadline/空实现 | 9f4b1b9 |
| C80 | processReturnTraffic单协程串行处理模型 | vpn | Medium | VpnService/processReturnTraffic/串行遍历 | d01ddd1 |
| M2 | WiFi管理器权限检查不一致 | wifi | Medium | GatewayWifiManager/双版本API | e89e00d |
| M9 | TCP回包状态管理不完整 | vpn | Medium | VpnService/constructReturnPacket/seq=0 | e89e00d |
| L2 | TODO注释未处理 | connection | Low | MqttConnectionManager/TODO注释 | e89e00d |
| L3 | EmulatorDetector权限检查重复 | security | Low | EmulatorDetector/重复权限检查 | e89e00d |
| L5 | 缺少集成测试 | global | Low | 测试目录/仅单元测试 | e89e00d |
| N1 | 双版本API增加维护负担 | wifi,connection | Medium | GatewayWifiManager,AuthSessionStore/双版本 | e89e00d |
| N2 | VpnService过于庞大 | vpn | Medium | VpnService/1054行 | e89e00d |
| N3 | 过度使用@Synchronized | connection | Low | AuthSessionStore/全方法同步 | e89e00d |
| N4 | DI模块接口设计混乱 | di | Medium | ModuleInterfaces/@Deprecated | e89e00d |
| N5 | 状态管理分散 | global | Low | 多处/VpnState,MqttConnectionState,WiFiState | e89e00d |
| N6 | 测试命名不一致 | global | Low | 测试目录/下划线vs驼峰 | e89e00d |
| N7 | 测试质量不高 | vpn | Low | VpnServiceTest/测试自动生成方法 | e89e00d |
| N8 | 核心业务逻辑测试缺失 | vpn,proxy,connection | High | 测试目录/processVpnTraffic等无测试 | e89e00d |
| N9 | 日志级别使用不当 | global | Low | 多处/warn滥用 | e89e00d |
| N10 | 监控指标缺失 | global | Medium | 全局/无性能指标 | e89e00d |
| N12 | 运行时配置缺失 | vpn | Low | VpnService/硬编码DNS | e89e00d |
| N13 | 已弃用API使用 | global | Medium | 多处/EncryptedSharedPreferences等 | e89e00d |
| N14 | gorilla/websocket已归档 | server | Medium | server/go.mod/已归档 | e89e00d |
| N15 | Paho MQTT维护不活跃 | connection | Medium | android/app/build.gradle/维护不活跃 | e89e00d |

## 已修复问题索引

| 编号 | 标题 | 修复提交 | 验证状态 |
|------|------|----------|----------|
| C1 | SSL信任所有证书配置风险（已降级为Medium） | 运行时防护已存在 | make android-test通过 |
| H10 | VpnService stopVpn()竞态条件 | SOLO 2026-05-15 | make android-test通过 |
| H11 | writeBufferPool线程安全问题 | d01ddd1 | make android-test通过 |
| H17 | VirtualIpAllocator AtomicInteger溢出 | e89e00d | make android-test通过 |
| M1 | 边界条件：IP地址解析验证 | 21ffa4c | make android-test通过 |
| M3 | 安全检测命令执行未超时 | e89e00d | make android-test通过 |
| M11 | 连接池状态检查与清理的竞态条件 | b1e18bd | make android-test通过 |
| N11 | 硬编码默认值不安全 | 21ffa4c | make android-test通过 |
| N21 | 配对码输入状态配置变更丢失 | 已修复 | make android-test通过 |
| N22 | MainViewModel状态更新竞争条件 | 已修复 | make android-test通过 |
| N23 | 测试直接实例化Android Service | 已修复 | make android-test通过 |
| N24 | Socks5ProxyService通知ID使用魔法数字 | 已修复 | make android-test通过 |
| N25 | MainViewModel状态更新方式不一致 | 已修复 | make android-test通过 |
| N26 | Service语言监听器残留风险 | 已修复 | make android-test通过 |
| N31 | NetworkStateManager onLost多网络状态误判 | 1f9acee | make android-test通过 |
| N35 | MqttConnectionManager connect阻塞Default调度器 | 1f9acee | 误报，无需修复 |
| N39 | server/api JWT Secret长度未验证 | 202bb95 | go test通过 |
| N40 | server/socks5-proxy GetOrConnectTunnel连接存活检查竞态 | 1f9acee | go test通过 |
| N44 | VirtualIpAllocator floorMod边界偏移 | 2ca17ee | make android-test通过 |
| N45 | Socks5ProxyHandler double-free风险 | 9f4b1b9 | make android-test通过 |
| N46 | DebugDetector语义隐晦代码 | 165c272 | make android-test通过 |
| N51 | server/socks5-proxy StreamConn.Read在关闭边界可能丢失已排队数据 | 已修复 | go test通过 |
| N52 | VpnService ThreadLocal writeBuffer在IO线程池上长期滞留 | d01ddd1 | 中间发现，最终方案见N54 |
| N53 | server/socks5-proxy StreamConn.Read在小缓冲区下会直接截断数据 | 已修复 | go test通过 |
| N54 | VpnService ThreadLocal writeBuffer.remove()抵消缓冲区复用价值 | d01ddd1 | make android-test通过 |
| N55 | DebugDetector 4个方法正常完成路径未调用process.destroy() | 1ba8a50 | make android-test通过 |
| N60 | N47测试修复中runTest未共享testScope的调度器 | 299d6da | make android-test通过 |
| N63 | cleanupStream中streamConn.Close()在tc.mu锁内执行 | 已修复 | go test通过 |
| N64 | MqttConnectionManager.disconnect()在synchronized块内修改StateFlow | 3c3784f | 误报，无需修复 |
| N65 | MqttConnectionManager.connectionLost在非协程线程直接修改StateFlow | 3c3784f | 误报，无需修复 |
| N67 | RootDetector.checkMagiskProps()严重误报 | 已修复 | make android-test通过 |
| N69 | StreamConn.Read中readMu锁持有时间过长 | 15414b05 | 误报，无需修复 |
| N74 | MainViewModelTest使用mockkConstructor(Intent)全局静态污染 | cc86ec21 | 误报，无需修复 |
| N75 | MainViewModel.durationUpdateJob使用Dispatchers.Default无测试覆盖 | cc86ec21 | 误报（已知限制） |
| N78 | readLoop defer中conn.Close()仍在tc.mu锁内执行 | f93054a | go test通过 |
| C11 | Makefile统一构建流程 | a8ab465 | make android-test通过 |
| C23 | notifyStatusBackoff位移溢出风险 | d06f584 | go test通过 |
| C24 | http.Client未复用连接池 | d06f584 | go test通过 |
| C25 | notifyDeviceStatus goroutine泄漏风险 | d06f584 | go test通过 |
| C27 | notifyDeviceStatus测试覆盖不足 | d06f584 | go test通过 |
| C28 | VpnService processVpnTraffic FileInputStream未关闭 | 8398c13 | make android-test通过 |
| C31 | VpnService startVpn状态与资源初始化顺序不一致 | bd36914 | make android-test通过 |
| C32 | VpnService injectPacket静默丢弃注入失败 | bd36914 | make android-test通过 |
| L10 | Tunnel服务设备状态通知无重试 | d06f584 | go test通过 |

## 误报问题索引

| 编号 | 标题 | 原因 | 记录提交 |
|------|------|------|----------|
| N35 | MqttConnectionManager connect阻塞Default调度器 | 实际使用@ApplicationScope(Dispatchers.IO)，非Main调度器 | 1f9acee |
| N64 | MqttConnectionManager.disconnect()在synchronized块内修改StateFlow | StateFlow.value setter线程安全，无死锁条件 | 3c3784f |
| N65 | MqttConnectionManager.connectionLost在非协程线程直接修改StateFlow | StateFlow设计允许任意线程发布，标准用法 | 3c3784f |
| N69 | StreamConn.Read中readMu锁持有时间过长 | StreamConn.Read单goroutine调用，锁保护readRemainder状态 | 15414b05 |
| N71 | getExistingConn返回的连接可能在调用方使用前失效 | 竞态窗口极短，isConnAlive已验证即时状态，后续Write会返回错误 | 15414b05 |
| N74 | MainViewModelTest使用mockkConstructor(Intent)全局静态污染 | @After中unmockkConstructor清理，Gradle默认串行执行 | cc86ec21 |
| N75 | MainViewModel.durationUpdateJob使用Dispatchers.Default无测试覆盖 | 计时逻辑简单，注入调度器复杂度超测试收益 | cc86ec21 |

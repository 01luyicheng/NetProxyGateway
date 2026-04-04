# 技术债务清单

> 这是技术债务清单，记录需要重构但短期内无法进行的代码债务。
> 这些问题不影响功能正确性，但会降低代码可维护性、可读性和扩展性。

---

## Critical Severity

### C1: 网络出口控制机制缺失
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt`
- **问题描述**: 当前使用 `protect()` 绕过 VPN 实现 WiFi 直连，但无法强制指定使用 WiFi 还是移动数据。在厂商双 WiFi 加速、Link Turbo 等场景下，系统可能将流量路由到非预期网卡。
- **技术影响**:
  - 无法保证内网流量一定走 WiFi 出口
  - 厂商定制 ROM 可能绕过应用层控制
  - 远程协助场景下网络行为不可预测
- **建议重构方案**:
  1. 使用 `ConnectivityManager.getAllNetworks()` 获取所有可用网络
  2. 识别 WiFi 网络和蜂窝网络
  3. 使用 `Network.bindSocket()` 强制绑定 Socket 到指定网络
  4. 为不同流量类型（内网/外网/DNS）绑定到对应网络
  5. 添加网络绑定失败回退逻辑
- **预估工作量**: 2-3 天（含测试）

### C2: 厂商定制 ROM 适配缺失
- **位置**: 全局（无相关代码）
- **问题描述**: 项目中完全没有针对小米、华为、OPPO、vivo 等厂商定制 ROM 的适配代码。这些厂商的电池优化、后台限制、网络加速等功能可能影响 VPN 服务稳定性。
- **技术影响**:
  - VPN 服务可能被系统杀死
  - 双 WiFi 加速导致流量走向不可控
  - 后台限制导致 MQTT 连接断开
- **建议重构方案**:
  1. 添加厂商检测工具类（基于 `Build.MANUFACTURER`）
  2. 实现电池优化白名单申请
  3. 添加后台启动权限检查
  4. 针对各厂商实现特定的保活策略
  5. 添加厂商网络加速检测和提示
- **预估工作量**: 3-5 天（需多设备测试）

---

## High Severity

### H5: VpnService 类过大
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt` (类名：`GatewayVpnService`, 959 行)
- **问题描述**: 该类承担过多职责，包括 VPN 生命周期管理、TUN 设备处理、IP 数据包解析、路由决策、连接池管理、TCP/UDP 协议处理等
- **技术影响**:
  - 违反单一职责原则 (SRP)，代码难以理解和维护
  - 单元测试困难，需要模拟大量依赖
  - 修改一个功能可能意外影响其他功能
  - 代码复用性低，相似逻辑无法在其他地方使用
- **建议重构方案**:
  1. 提取 `TunPacketProcessor`：负责从 TUN 设备读取和写入数据包
  2. 提取 `IpPacketParser`：负责解析 IP 头部、TCP/UDP 头部
  3. 提取 `RouteManager`：负责路由决策（DIRECT/PROXY/CLOUD）
  4. 提取 `ConnectionPoolManager`：负责管理 TCP/UDP 连接池
  5. 提取 `VirtualIpAllocator`：负责虚拟 IP 地址分配
  6. VpnService 仅保留生命周期管理和协调各组件的职责
- **预估工作量**: 3-4 天（含测试重构）

---

## Medium Severity

### ISSUE-014: 接口过度设计
- **位置**: `android/app/src/main/java/com/netproxy/gateway/di/ModuleInterfaces.kt`, `ModuleImplementations.kt`
- **问题描述**: `CommandHandler` 和 `QueryHandler` 接口拆分增加了不必要的复杂度，每个接口只有一个实现，增加了不必要的复杂性
- **技术影响**:
  - 增加代码复杂度，没有带来实际好处
  - 新增功能需要修改多处（接口 + 实现）
  - 过度抽象使代码流程难以追踪
- **建议重构方案**:
  1. 评估是否真正需要接口抽象（是否有多实现需求、测试需求）
  2. 如无多实现需求，直接合并接口和实现类
  3. 如有测试需求，考虑使用具体类配合依赖注入框架的 mock 功能
  4. 简化 DI 模块配置
- **预估工作量**: 0.5 天

### L9: 代码重复：IP 地址解析逻辑
- **位置**: `VpnService.kt` 和 `VpnTestUtils.kt`
- **问题描述**: VpnTestUtils 中完全复制了 VpnService 的 IP 解析实现代码
- **技术影响**:
  - 违反 DRY 原则，修改时需要同步修改多处
  - 测试代码与生产代码耦合，测试脆弱
  - 重复代码增加维护成本
- **建议重构方案**:
  1. 将 IP 解析逻辑提取到独立的工具类 `IpPacketParser`
  2. 生产代码和测试代码共用同一实现
  3. 为工具类编写独立单元测试
  4. 删除 VpnTestUtils 中的重复代码
- **预估工作量**: 0.5 天

### L16: 测试验证语言特性而非业务逻辑
- **位置**: `android/app/src/test/java/com/netproxy/gateway/connection/MqttConnectionManagerTest.kt` (L21-L68)
- **问题描述**: 测试验证 Kotlin 的 object 特性和数据类行为，而非实际的 MQTT 连接管理业务逻辑
- **技术影响**:
  - 测试没有实际价值，无法发现业务逻辑 bug
  - 浪费 CI/CD 资源
  - 给开发者错误的信心
- **建议重构方案**:
  1. 删除验证语言特性的测试
  2. 补充实际的业务逻辑测试：
     - MQTT 连接建立和断开
     - 消息发布和订阅
     - 重连机制
     - 错误处理
  3. 使用 Mockito 或 MockK 模拟 MQTT 客户端
  4. 增加集成测试验证端到端流程
- **预估工作量**: 1-2 天

### L17: 隐式依赖网
- **位置**: `android/app/src/main/java/com/netproxy/gateway/di/ModuleCoordinator.kt`
- **问题描述**: ModuleCoordinator 与 Hilt 注入并存，形成隐式依赖网，依赖关系不清晰
- **技术影响**:
  - 依赖关系难以追踪和理解
  - 测试时需要处理复杂的依赖设置
  - 可能导致循环依赖问题
  - 违反显式优于隐式原则
- **建议重构方案**:
  1. 统一使用 Hilt 进行依赖注入，移除 ModuleCoordinator
  2. 或明确 ModuleCoordinator 的职责边界，与 Hilt 互补使用
  3. 绘制依赖关系图，识别和打破循环依赖
  4. 将隐式依赖改为构造函数显式注入
- **预估工作量**: 1-2 天

---

## Low Severity

### L1: 魔法数字
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt` (L269-L282)
- **问题描述**: 多处使用未命名的协议号（17=UDP, 6=TCP）和其他常量
- **技术影响**:
  - 代码可读性差，需要查阅文档才能理解含义
  - 容易引入错误（如混淆协议号）
  - 维护困难，修改时需要全局搜索替换
- **建议重构方案**:
  1. 定义常量：`const val PROTOCOL_TCP = 6`、`const val PROTOCOL_UDP = 17`
  2. 定义 IP 版本常量：`const val IP_VERSION_4 = 4`
  3. 定义其他魔术数字（如端口号、标志位等）
  4. 考虑使用 Kotlin 枚举类表示协议类型
- **预估工作量**: 0.5 天

### L10: 重复的错误处理模式
- **位置**: `android/app/src/main/java/com/netproxy/gateway/connection/MqttConnectionManager.kt`
- **问题描述**: 多个方法中存在重复的错误处理代码块（try-catch-log-return error 模式）
- **技术影响**:
  - 代码冗余，增加维护成本
  - 错误处理逻辑不一致的风险
  - 核心逻辑被错误处理代码淹没
- **建议重构方案**:
  1. 使用 Kotlin 的 `runCatching` 或自定义高阶函数统一错误处理
  2. 提取通用的错误转换逻辑
  3. 使用扩展函数包装错误处理模式
  4. 考虑使用 AOP 或装饰器模式
- **预估工作量**: 0.5 天

### L15: 重复的 Result 包装方法
- **位置**: `android/app/src/main/java/com/netproxy/gateway/wifi/WifiManager.kt` (L210-L275)
- **问题描述**: 为每个操作提供两种版本（直接返回和 Result 包装）是冗余的
- **技术影响**:
  - API 表面过大，增加维护负担
  - 调用方选择困难，不知道使用哪个版本
  - 重复代码增加 bug 风险
- **建议重构方案**:
  1. 统一使用一种返回类型（推荐标准库 Result）
  2. 删除重复的方法版本
  3. 使用扩展函数在需要时转换返回类型
  4. 更新所有调用方代码
- **预估工作量**: 0.5 天

### L18: 硬编码配置值
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/GatewayVpnService.kt` (L73-97)
- **问题描述**: DNS 服务器、IP 段、MTU、端口号等配置硬编码在代码中
- **技术影响**:
  - 配置变更需要修改代码并重新编译
  - 无法根据不同环境动态调整配置
  - 增加维护成本，配置分散在代码各处
- **建议重构方案**:
  1. 提取配置到配置文件或 BuildConfig
  2. 使用依赖注入提供配置
  3. 考虑支持运行时配置更新
- **预估工作量**: 1 天

### L19: 缺少文档注释
- **位置**: 多个文件
- **问题描述**: 公共方法、类和复杂逻辑缺少 KDoc 文档注释
- **技术影响**:
  - 新开发者难以理解代码意图
  - 接口使用方式不明确
  - 维护困难，容易引入 bug
- **建议重构方案**:
  1. 为所有公共 API 添加 KDoc 注释
  2. 说明参数、返回值、异常和副作用
  3. 对复杂算法添加实现注释
- **预估工作量**: 2-3 天

### L20: 线程安全问题
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/GatewayVpnService.kt` (L110-121)
- **问题描述**: ConcurrentHashMap 与 AtomicInteger 使用但未明确线程安全策略，跨线程访问模式不清晰
- **技术影响**:
  - 潜在的竞态条件风险
  - 难以验证线程安全性
  - 未来修改可能引入并发 bug
- **建议重构方案**:
  1. 明确文档化线程安全策略
  2. 考虑使用不可变数据结构
  3. 必要时添加同步注释或封装线程安全类
- **预估工作量**: 0.5 天

### L21: 异常处理不一致
- **位置**: 多个文件
- **问题描述**: 异常处理方式不一致，有些使用 logger.error，有些直接抛出，有些静默处理
- **技术影响**:
  - 错误处理逻辑难以预测
  - 某些错误可能被静默忽略
  - 调试困难，日志信息不完整
- **建议重构方案**:
  1. 制定统一的异常处理策略
  2. 区分可恢复错误和致命错误
  3. 统一日志记录格式和级别
  4. 对静默处理的代码添加注释说明原因
- **预估工作量**: 1-2 天

### L22: 测试覆盖率不足
- **位置**: 整个测试目录
- **问题描述**: 大量测试只验证语言特性，缺少业务逻辑测试。例如 MqttConnectionManagerTest 验证 Kotlin object 特性而非 MQTT 连接逻辑
- **技术影响**:
  - 测试无法发现业务逻辑 bug
  - 给开发者错误的信心
  - 回归风险高
- **建议重构方案**:
  1. 删除验证语言特性的无价值测试
  2. 补充核心业务逻辑测试
  3. 使用 Mockito/MockK 模拟外部依赖
  4. 增加集成测试验证端到端流程
- **预估工作量**: 3-5 天

### L23: MQTT 错误日志缺少堆栈信息
- **位置**: `android/app/src/main/java/com/netproxy/gateway/connection/MqttConnectionManager.kt` (L190, L233, L277, L295, L311)
- **问题描述**: 多处错误日志只记录 `e.message`，不记录完整异常堆栈。例如：`logger.error("Publish error: ${e.message}")` 应该为 `logger.error("Publish error", e)`
- **技术影响**:
  - 生产环境问题难以调试，缺少完整的调用栈信息
  - 无法定位异常的根本原因（root cause）
  - 增加问题排查时间和难度
- **建议重构方案**:
  1. 统一错误日志记录方式，使用 `logger.error("message", exception)` 形式
  2. 参考 `AuthSessionStore.kt:52` 的正确实现：`logger.warn("Encrypted prefs pre-warm failed", error)`
  3. 制定日志记录规范，明确要求记录完整堆栈
- **预估工作量**: 0.5 天

---

## 统计汇总

| 严重程度 | 数量 | 预估总工作量 |
|---------|------|-------------|
| Critical | 2 | 5-8 天 |
| High | 1 | 3-4 天 |
| Medium | 4 | 3.5-5 天 |
| Low | 10 | 8-11.5 天 |
| **总计** | **17** | **19.5-28.5 天** |

---

## 重构优先级建议

1. **紧急（立即）**: C1 网络出口控制、C2 厂商适配 - 影响核心功能正确性
2. **第一阶段（立即）**: L1 魔法数字、L18 硬编码配置 - 低风险，快速收益
3. **第二阶段（短期）**: L9 代码重复、L10 重复错误处理、L20 线程安全、L21 异常处理、L23 MQTT 错误日志 - 改善代码质量
4. **第三阶段（中期）**: ISSUE-014/L15 不必要抽象、L16 测试改进、L17 依赖网、L19 文档注释 - 架构优化
5. **第四阶段（长期）**: H5 VpnService 拆分、L22 测试覆盖率 - 重大重构，需要充分测试

---

## 变更记录

### L24: 已弃用的 WiFi API 使用
- **位置**: `android/app/src/main/java/com/netproxy/gateway/wifi/WifiManager.kt` (L211-218, L228-255, L283-303)
- **问题描述**: 代码中使用了多个在 Android API 28+ 和 API 29+ 中已弃用的 WiFi API：
  - `WifiManager.startScan()` - API 28+ 已弃用，有更严格的限流
  - `WifiManager.scanResults` - API 29+ 建议使用 `registerScanResultsCallback`
  - `WifiManager.connectionInfo` - API 29+ 已弃用，建议使用 `ConnectivityManager.getLinkProperties`
- **技术影响**:
  - 在 Android 10+ 设备上可能行为不一致
  - 未来 Android 版本可能完全移除这些 API
  - 无法使用新的 WiFi 扫描回调机制
- **建议重构方案**:
  1. 为 API 29+ 实现 `WifiManager.registerScanResultsCallback` 替代方案
  2. 使用 `ConnectivityManager` 获取连接信息替代 `WifiManager.connectionInfo`
  3. 保持向后兼容性，对旧版本使用现有实现
- **预估工作量**: 1-2 天
- **参考文档**: 详见 `docs/DEPENDENCY_VERSIONS.md` 和 Context7 文档

### L25: Go 模块依赖版本不一致
- **位置**: `server/tunnel/go.mod`, `server/socks5-proxy/go.mod`
- **问题描述**: `gorilla/websocket` 在两个模块中使用不同版本：
  - `socks5-proxy`: v1.5.3（最新）
  - `tunnel`: v1.5.1（落后）
- **技术影响**:
  - 可能导致行为不一致
  - 安全修复和 bug 修复未同步
  - 依赖管理混乱
- **建议重构方案**:
  1. 统一 `tunnel` 模块的 `gorilla/websocket` 到 v1.5.3
  2. 运行 `go mod tidy` 清理依赖
  3. 考虑使用 workspace 模式统一管理 Go 模块
- **预估工作量**: 0.5 天
- **参考文档**: 详见 `docs/DEPENDENCY_VERSIONS.md`

### L26: Netty NioEventLoopGroup 已产生 deprecation 警告
- **位置**: `android/app/src/main/java/com/netproxy/gateway/proxy/Socks5ProxyService.kt`
- **问题描述**: 当前代码使用 `NioEventLoopGroup()` 直接实例化，build 输出已产生 deprecation 警告。Netty 引入了新的 API: `MultiThreadIoEventLoopGroup(NioIoHandler.newFactory())`
- **技术影响**:
  - build 输出中大量警告影响可读性，掩盖真正问题
  - 未来 Netty 版本可能移除旧 API
  - 无法使用新版本 Netty 的特性
- **建议重构方案**:
  1. 升级 Netty 版本（当前 4.1.118.Final），评估 4.2.x 的兼容性
  2. 迁移到新 API `MultiThreadIoEventLoopGroup(NioIoHandler.newFactory())`
  3. 或在 gradle 中 suppress 警告作为临时方案
- **预估工作量**: 1 天（含测试）
- **参考文档**: 详见 `docs/DEPENDENCY_VERSIONS.md` 和 Context7 Netty 文档

### L27: Go 版本可升级
- **位置**: `server/*/go.mod`
- **问题描述**: 当前使用 Go 1.22，最新版本为 Go 1.24
- **技术影响**:
  - 无法使用 Go 1.23+ 的新特性（如迭代器、泛型改进等）
  - 安全更新和性能改进未及时跟进
- **建议重构方案**:
  1. 评估 Go 1.24 的兼容性
  2. 升级 Go 版本并测试所有模块
  3. 更新 CI/CD 配置中的 Go 版本
- **预估工作量**: 0.5 天
- **参考文档**: 详见 `docs/DEPENDENCY_VERSIONS.md`

### L28: EncryptedSharedPreferences / MasterKey 已弃用
- **位置**: `android/app/src/main/java/com/netproxy/gateway/connection/AuthSessionStore.kt`, `android/app/src/main/java/com/netproxy/gateway/wifi/WifiManager.kt`
- **问题描述**: Android 12+ 已弃用 `EncryptedSharedPreferences` 和 `MasterKey.Builder(Context)` 构造方式，推荐使用 `androidx.security.crypto` 的新 API 或 Android Keystore
- **技术影响**:
  - 在 Android 12+ 设备上可能触发安全审核警告
  - 未来 Android 版本可能移除这些 API
  - 影响敏感凭据存储的安全性
- **建议重构方案**:
  1. 使用 `MasterKey.Builder(activity)` 替代 `MasterKey.Builder(context)`
  2. 评估迁移到 Android Keystore-backed key store
  3. 保持向后兼容性
- **预估工作量**: 0.5 天
- **参考文档**: [AndroidX Crypto docs](https://developer.android.com/reference/androidx/security/crypto/package-summary)

### L29: DI Module 接口被标记为 deprecated
- **位置**: `android/app/src/main/java/com/netproxy/gateway/di/ModuleInterfaces.kt`, `ModuleImplementations.kt`
- **问题描述**: `CoreModule`、`CommunicationModule`、`NetworkModule`、`WiFiModule`、`UIModule`、`ConfigModule` 等接口被标记为 deprecated，建议直接使用 `*CommandHandler` / `*QueryHandler` 接口
- **技术影响**:
  - 代码可读性差，deprecation 警告分散在 build 输出中
  - 长期会阻碍维护和重构
  - 警告掩盖真正重要的编译错误
- **建议重构方案**:
  1. 移除中间接口层，直接注入 `CommandHandler` / `QueryHandler`
  2. 简化 DI 模块配置
  3. 与 ISSUE-014（接口过度设计）合并处理
- **预估工作量**: 0.5 天

### L30: hiltViewModel() 迁移到新包
- **位置**: `android/app/src/main/java/com/netproxy/gateway/ui/screens/MainScreen.kt`
- **问题描述**: `dagger.hilt.android.lifecycle.hiltViewModel()` 已迁移到 `androidx.hilt.lifecycle.compose.hiltViewModel()`，当前导入路径 deprecated
- **技术影响**:
  - 未来 Hilt 版本可能移除旧导入
  - 警告影响 build 输出可读性
- **建议重构方案**:
  1. 将 `import dagger.hilt.android.lifecycle.hiltViewModel` 替换为 `import androidx.hilt.lifecycle.compose.hiltViewModel`
  2. 验证功能行为不变
- **预估工作量**: 0.25 天

### L31: VpnService.extractTransportPayload 已弃用
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt`
- **问题描述**: `extractTransportPayload()` 方法被标记为 deprecated，建议使用 `extractTransportPayloadInfo` 替代以避免不必要的数组拷贝
- **技术影响**:
  - 产生不必要的内存分配，影响性能
  - 未来版本可能移除旧 API
- **建议重构方案**:
  1. 迁移到 `extractTransportPayloadInfo` API
  2. 验证功能行为不变
  3. 如新 API 不兼容当前使用场景，需调整调用方
- **预估工作量**: 0.5 天

### L32: StatusBarColor 属性已弃用
- **位置**: `android/app/src/main/java/com/netproxy/gateway/ui/theme/Theme.kt`
- **问题描述**: `window.statusBarColor` 在 API 30+ 已弃用，推荐使用 `WindowInsetsController`
- **技术影响**:
  - Android 11+ 设备上可能无法正确设置状态栏颜色
  - 与现代 Android 设计规范不一致
- **建议重构方案**:
  1. 使用 `window.insetsController?.setSystemBarsAppearance()` 控制状态栏
  2. 配合 Compose 的 `enableEdgeToEdge()` API
  3. 保持向后兼容性
- **预估工作量**: 0.25 天

---

## 统计汇总

| 严重程度 | 数量 | 预估总工作量 |
|---------|------|-------------|
| Critical | 2 | 5-8 天 |
| High | 1 | 3-4 天 |
| Medium | 4 | 3.5-5 天 |
| Low | 10 | 7-9 天 |
| **总计** | **26** | **18.5-26 天** |

---

## 重构优先级建议

1. **紧急（立即）**: C1 网络出口控制、C2 厂商适配 - 影响核心功能正确性
2. **第一阶段（立即）**: L1 魔法数字、L18 硬编码配置、L25 Go 依赖版本不一致、L30 hiltViewModel() 迁移、L32 StatusBarColor 迁移 - 低风险，快速收益
3. **第二阶段（短期）**: L9 代码重复、L10 重复错误处理、L20 线程安全、L21 异常处理、L23 MQTT 错误日志、L24 已弃用 WiFi API、L28 EncryptedSharedPreferences、L29 DI Module 接口、L31 extractTransportPayload - 改善代码质量
4. **第三阶段（中期）**: ISSUE-014/L15 不必要抽象、L16 测试改进、L17 依赖网、L19 文档注释、L26 Netty NioEventLoopGroup 迁移 - 架构优化
5. **第四阶段（长期）**: H5 VpnService 拆分、L22 测试覆盖率、L27 Go 升级 - 重大重构，需要充分测试

---

## 变更记录

| 日期 | 变更内容 | 变更人 |
|------|----------|--------|
| 2026-03-31 | 初始创建，从原 ISSUES.md 迁移技术债务项 | AI Agent |
| 2026-03-31 | 合并重复项：将 ISSUE-014（接口过度设计）与 L15（重复的 Result 包装方法）统一归类 | AI Agent |
| 2026-03-31 | 统计修正：重新统计各严重程度债务数量，总计 10 项，预估工作量 10-13.5 天 | AI Agent |
| 2026-03-31 | 分级调整：根据代码影响和维护成本重新调整严重程度分级 | AI Agent |
| 2026-03-31 | 新增债务项：L17（隐式依赖网）、L16（测试验证语言特性而非业务逻辑） | AI Agent |
| 2026-03-31 | 删除 L8（过长且重复的方法名）和 L12（未使用 Kotlin 标准库 Result 类型）条目，更新统计为 14 项 | AI Agent |
| 2026-03-31 | 新增 Critical 级别债务：C1（网络出口控制机制缺失）、C2（厂商定制 ROM 适配缺失），更新统计为 16 项 | AI Agent |
| 2026-04-03 | 新增 L23（MQTT 错误日志缺少堆栈信息），更新统计为 17 项 | AI Agent |
| 2026-04-04 | 新增 L24-L27（依赖相关技术债务），更新统计为 21 项，新增 `docs/DEPENDENCY_VERSIONS.md` | AI Agent |
| 2026-04-04 | 新增 L28-L32（deprecated API 警告未追踪），更新统计为 26 项 | Claude |

*最后更新：2026-04-04*
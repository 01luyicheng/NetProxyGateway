# NetProxyGateway 问题清单

## 当前状态

本次已先进行问题存在性复核，再完成一批可安全落地的修复，并从待修列表中移除已修复项。

## 高优先级问题（7个）

### ISSUE-002: TLS证书验证配置不当
- **问题描述**: 未进行证书固定（Certificate Pinning），存在中间人攻击风险
- **风险等级**: 高
- **问题位置**: `android/app/src/main/java/com/netproxy/gateway/connection/MqttConnectionManager.kt`
- **修复建议**: 实施证书固定策略并支持证书轮换

### ISSUE-005: 状态管理混乱 - 多源状态流冲突
- **问题描述**: 项目存在多个状态管理源，容易产生不一致
- **影响程度**: 高
- **问题位置**: `android/app/src/main/java/com/netproxy/gateway/di/ModuleCoordinator.kt`, `android/app/src/main/java/com/netproxy/gateway/di/AppModule.kt`, `android/app/src/main/java/com/netproxy/gateway/ui/viewmodel/MainViewModel.kt`
- **修复建议**: 采用单一数据源原则

### ISSUE-006: 内存泄漏风险 - CoroutineScope未绑定生命周期
- **问题描述**: ModuleCoordinator和AppModule中创建的CoroutineScope没有绑定生命周期
- **影响程度**: 高
- **问题位置**: `android/app/src/main/java/com/netproxy/gateway/di/ModuleCoordinator.kt`, `android/app/src/main/java/com/netproxy/gateway/di/AppModule.kt`
- **修复建议**: 使用applicationScope或viewModelScope

### ISSUE-007: 依赖注入滥用 - 接口匿名实现方式
- **问题描述**: AppModule中大量使用匿名对象实现接口，无法被Hilt正确管理
- **影响程度**: 高
- **问题位置**: `android/app/src/main/java/com/netproxy/gateway/di/AppModule.kt`
- **修复建议**: 将匿名对象改为具体的类实现

### ISSUE-008: ViewModel职责过重
- **问题描述**: MainViewModel直接处理VPN启动、MQTT连接、WiFi扫描等多种业务逻辑
- **影响程度**: 高
- **问题位置**: `android/app/src/main/java/com/netproxy/gateway/ui/viewmodel/MainViewModel.kt`
- **修复建议**: 引入UseCase/Interactor层

### ISSUE-011: 错误处理不一致
- **问题描述**: 不同模块的错误处理方式不统一
- **影响程度**: 高
- **问题位置**: 整个项目
- **修复建议**: 定义统一的Result<T>密封类并统一上抛/落日志

### ISSUE-012: 过时WiFi API
- **问题描述**: 使用WifiConfiguration和addNetwork()等已在Android 10弃用的API
- **影响程度**: 高
- **问题位置**: `android/app/src/main/java/com/netproxy/gateway/wifi/WifiManager.kt`
- **修复建议**: 使用WifiNetworkSuggestion或WifiNetworkSpecifier

## 中优先级问题（8个）

### ISSUE-014: SOCKS5代理未启用TLS
- **问题描述**: createSocks5Tunnel()方法创建的SOCKS5连接未使用TLS加密
- **风险等级**: 中
- **问题位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt`
- **修复建议**: 在SOCKS5连接上启用TLS包装

### ISSUE-016: 日志敏感信息泄露风险（部分修复）
- **问题描述**: VPN服务中仍有日志点需要继续统一脱敏/分级
- **风险等级**: 中
- **问题位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt`
- **修复建议**: 持续收敛到统一日志门面并在release禁用明细日志

### ISSUE-019: 过长方法
- **问题描述**: createSocks5Tunnel方法超过70行，包含多个逻辑步骤
- **影响程度**: 中
- **问题位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt`
- **修复建议**: 将方法拆分为多个小方法

### ISSUE-020: IP地址验证逻辑重复
- **问题描述**: 两个文件都包含几乎相同的isPrivateRfc1918/isPrivateIp方法
- **影响程度**: 中
- **问题位置**: `android/app/src/main/java/com/netproxy/gateway/proxy/Socks5ProxyHandler.kt`, `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt`
- **修复建议**: 提取到共享的工具类IpAddressUtils

### ISSUE-024: EncryptedSharedPreferences主线程操作
- **问题描述**: 首次访问可能触发主线程密钥初始化
- **影响程度**: 中
- **问题位置**: `android/app/src/main/java/com/netproxy/gateway/connection/AuthSessionStore.kt`
- **修复建议**: 使用协程在IO线程预热初始化

### ISSUE-025: 模块接口混合关注点
- **问题描述**: 模块接口混合了命令操作和查询操作，违反CQRS原则
- **影响程度**: 中
- **问题位置**: `android/app/src/main/java/com/netproxy/gateway/di/ModuleInterfaces.kt`
- **修复建议**: 将命令和查询分离

### ISSUE-026: 缺少Repository层
- **问题描述**: 项目缺少Repository模式，数据访问逻辑分散
- **影响程度**: 中
- **问题位置**: 整个项目
- **修复建议**: 创建AuthRepository、NetworkRepository等

### ISSUE-027: 测试覆盖率不足
- **问题描述**: 核心业务缺少充分测试
- **影响程度**: 中
- **问题位置**: 整个项目
- **修复建议**: 为核心业务类添加单元测试并提升覆盖率

## 低优先级问题（5个）

### ISSUE-029: 硬编码DNS服务器
- **问题描述**: 使用硬编码的公共DNS服务器
- **风险等级**: 低
- **问题位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt`
- **修复建议**: 允许用户配置DNS服务器

### ISSUE-030: 魔法数字
- **问题描述**: 协议号、字节偏移量等使用魔法数字，可读性差
- **影响程度**: 低
- **问题位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt`
- **修复建议**: 定义为命名常量

### ISSUE-031: 缺少反调试保护
- **问题描述**: 项目未配置反调试、反模拟器检测等防护机制
- **风险等级**: 低
- **修复建议**: 评估后逐步引入反调试、模拟器检测、Root检测

### ISSUE-032: 包结构不够清晰
- **问题描述**: 包结构按技术分层，缺少按功能分层的domain包
- **影响程度**: 低
- **修复建议**: 按功能重新组织代码结构

### ISSUE-033: 日志记录不一致
- **问题描述**: 代码中混合使用android.util.Log、SLF4J和LoggerFactory
- **影响程度**: 低
- **问题位置**: 整个项目
- **修复建议**: 统一日志框架与日志等级策略

## 本轮已修复并移除的问题

- ISSUE-001: 设备ID明文存储（此前已修复，已确认）
- ISSUE-003: 位置权限过度申请（此前已修复，已确认）
- ISSUE-004: ProGuard规则过度保留
- ISSUE-009: VPN回包处理忙等待
- ISSUE-010: Netty EventLoopGroup线程数未限制
- ISSUE-013: 调试版本使用明文MQTT连接
- ISSUE-015: 配对码生成随机性不足
- ISSUE-017: 静默异常处理
- ISSUE-018: 空值检查不完整
- ISSUE-021: 缓冲区池竞争条件（此前已修复，已确认）
- ISSUE-022: 非原子操作 - 虚拟IP分配
- ISSUE-023: MQTT连接超时配置过长
- ISSUE-028: 目标地址验证不完整
- ISSUE-034: MainActivity exported配置（经复核为可接受，不再作为待修问题）

## 待办事项

- [ ] 修复剩余高优先级问题（7个）
- [ ] 优先推进中优先级中的安全与稳定项（ISSUE-014/016/024）
- [ ] 逐步完成架构重构项（ISSUE-005/007/008/011/025/026）
- [ ] 提升测试覆盖率（ISSUE-027）

## 验证记录

- **验证时间**: 2026-03-24
- **验证方式**: Sub Agent 交叉核验 + 本地代码修改后静态检查/测试
- **结果**: 活跃问题列表已根据修复结果更新

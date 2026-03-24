# NetProxyGateway 待修复问题清单

## 高优先级

### ISSUE-006: CoroutineScope生命周期绑定
- **问题**: 多个类使用自定义CoroutineScope而非生命周期感知的scope
- **位置**: 
  - `MqttConnectionManager.kt` 第54行
  - `ModuleCoordinator.kt` 第29行
  - `AppModule.kt` 第39行
  - `VpnService.kt` 第96行
  - `Socks5ProxyService.kt` 第55行
- **修复**: 单例类使用applicationScope，Service使用lifecycleScope

### ISSUE-012: 过时WiFi API
- **问题**: 使用Android 10+弃用的WifiConfiguration和addNetwork() API
- **位置**: `WifiManager.kt` 第10行、第122-154行
- **修复**: 使用WifiNetworkSuggestion或WifiNetworkSpecifier替代

### ISSUE-014: SOCKS5代理未启用TLS
- **问题**: createSocks5Tunnel()使用普通Socket，未启用TLS加密
- **位置**: `VpnService.kt` 第426-496行
- **修复**: 使用SSLSocket启用TLS包装

### ISSUE-002: TLS证书固定
- **问题**: 已配置基础TLS但未实现证书固定（Certificate Pinning）
- **位置**: `MqttConnectionManager.kt` 第85-92行
- **修复**: 实施证书固定策略并支持证书轮换

### ISSUE-011: 统一错误处理
- **问题**: 未定义统一的Result<T>密封类，各模块错误处理方式不一致
- **位置**: 整个项目
- **修复**: 定义AppResult<T>密封类并统一使用

---

## 中优先级

### ISSUE-025: 模块接口混合关注点
- **问题**: 模块接口混合命令和查询操作，违反CQRS原则
- **位置**: `ModuleInterfaces.kt`
- **修复**: 分离为CommandHandler和QueryHandler

### ISSUE-027: 测试覆盖率不足
- **问题**: 核心业务类（VpnService、AuthSessionStore、MqttConnectionManager等）无测试
- **位置**: 整个项目
- **修复**: 为核心业务类添加单元测试

---

## 低优先级

### ISSUE-029: 硬编码DNS服务器
- **问题**: 使用硬编码的公共DNS服务器
- **位置**: `VpnService.kt` 第66行、第90-93行
- **修复**: 允许用户配置DNS服务器

### ISSUE-031: 缺少反调试保护
- **问题**: 未实现反调试、反模拟器、Root检测机制
- **位置**: 整个项目
- **修复**: 评估后逐步引入安全防护机制

### ISSUE-032: 包结构完善
- **问题**: 缺少domain层和共享工具类包
- **位置**: 整个项目
- **修复**: 添加domain层和utils包

### ISSUE-033: 日志框架不统一
- **问题**: 混合使用android.util.Log和SLF4J
- **位置**: 整个项目
- **修复**: 统一日志框架与日志等级策略

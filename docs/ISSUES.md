# NetProxyGateway 待修复问题清单

## 高优先级

### ISSUE-011: 统一错误处理

- **问题**: 未定义统一的Result<T>密封类，各模块错误处理方式不一致
- **位置**: 整个项目
- **现状**: Kotlin 标准库的 `Result<T>` 未被使用，且缺少项目级的 `AppResult<T>` 密封类统一错误处理
- **修复**: 定义AppResult<T>密封类并统一使用

### ISSUE-014: SOCKS5代理TLS配置未启用

- **问题**: 服务端 SOCKS5 代理代码已支持 TLS，但默认配置可能未启用
- **位置**:
  - `server/socks5-proxy/main.go` - 已实现 TLS 支持（`EnableTLS` 配置项），但默认可能未启用
  - `VpnService.kt` - 客户端连接方式需验证是否使用 `SSLSocket`
- **现状**:
  - 移动端 `Socks5ProxyService` 监听 `127.0.0.1:1080`，供本地连接，无需 TLS
  - 服务端 `socks5-proxy` 代码已支持 TLS（通过 `tls.NewListener`），但需确认生产环境配置
- **修复**: 确保生产环境启用 TLS 配置，客户端连接时使用 `SSLSocket`

### ISSUE-026: 连接池未实现

- **问题**: VpnService 没有真正的 SOCKS5 连接池，每次都重新创建 SOCKS5 tunnel
- **位置**: `VpnService.kt` 第369-409行 `forwardViaSocks5()` 方法
- **现状**: 代码使用四元组会话缓存 `activeConnections`，但 `createSocks5Tunnel()` 每次都创建新的 Socket 到本地 SOCKS5 代理
- **分析**: 现有实现是"会话缓存"（按四元组映射连接），而非"连接池"（复用同一代理连接）
- **修复**: 实现真正的 SOCKS5 连接池，复用活跃连接到代理服务器

### ISSUE-034: VpnService回包处理退避延迟评估

- **问题**: `processReturnTraffic()` 中空闲轮询退避延迟最大值为20ms，可能仍不足以避免高频CPU轮询
- **位置**: `VpnService.kt` 第559行
- **现状**: 代码使用 `minOf(1L shl minOf(idleRounds, 4), MAX_RETURN_TRAFFIC_IDLE_DELAY_MS)`，其中 `MAX_RETURN_TRAFFIC_IDLE_DELAY_MS = 20L`
- **分析**: 实际最大延迟为 **20ms**（不是16ms），但当空闲轮询频繁时，20ms可能仍导致不必要的CPU使用
- **修复**: 评估是否需要增大最大退避延迟（如100ms），或使用更平滑的算法（如指数退避到更高上限）

***

## 中优先级

### ISSUE-025: 模块接口混合关注点

- **问题**: 模块接口混合命令和查询操作，违反CQRS原则
- **位置**: `ModuleInterfaces.kt`
- **修复**: 分离为CommandHandler和QueryHandler

### ISSUE-027: 测试覆盖率不足

- **问题**: 核心业务类（VpnService、AuthSessionStore、MqttConnectionManager等）无测试
- **位置**: 整个项目
- **现有测试**: `MqttTlsPinningTest.kt`、`VpnDnsConfigTest.kt`、`IpAddressUtilsTest.kt`、`Socks5ProxyHandlerTest.kt`、`GatewayWifiManagerLogicTest.kt`、`MainViewModelTest.kt`
- **缺失测试**: `VpnService`、`AuthSessionStore`、`MqttConnectionManager`、`ModuleCoordinator`
- **修复**: 为核心业务类添加单元测试

***

## 低优先级

### ISSUE-031: 缺少反调试保护

- **问题**: 未实现反调试、反模拟器、Root检测机制
- **位置**: 整个项目
- **修复**: 评估后逐步引入安全防护机制

### ISSUE-032: 包结构不完整

- **问题**: 缺少domain层
- **位置**: 整个项目
- **现状**: `utils` 包存在于 `android/app/src/main/java/com/netproxy/gateway/utils/`
- **修复**: 评估是否需要添加domain层

### ISSUE-033: 日志框架不统一

- **问题**: 混合使用android.util.Log和SLF4J
- **位置**:
  - `VpnService.kt` - 使用 `android.util.Log`
  - `Socks5ProxyHandler.kt` - 使用 SLF4J (`LoggerFactory`)
  - `MqttConnectionManager.kt` - 使用 `android.util.Log`
- **修复**: 统一日志框架与日志等级策略

***

## 服务端问题 (Server)

### SERVER-001: SOCKS5与隧道网关未桥接

- **严重程度**: 🔴 严重
- **问题**: SOCKS5服务的handleConnect直接连接目标地址，未通过隧道网关转发
- **位置**: `server/socks5-proxy/main.go` 第543-561行
- **现状**: 代码注释标注"简化版本：直接连接到目标（仅用于测试）"，直接使用`net.DialTimeout`连接目标
- **修复**: 需要实现与隧道服务的桥接机制

### SERVER-002: 隧道服务凭证验证未实现

- **严重程度**: 🔴 严重
- **问题**: validateDeviceToken()直接返回true，未调用API验证
- **位置**: `server/tunnel/main.go` 第250-255行
- **现状**: `return true`（代码注释标注"简化版本：直接返回true"）
- **修复**: 需要调用API服务验证设备令牌

### SERVER-003: TLS证书配置缺失

- **严重程度**: 🔴 严重
- **问题**: docker-compose.yml未配置TLS证书，所有服务以明文运行
- **位置**: `server/docker-compose.yml`
- **现状**: 所有服务端口均为明文（8080, 1080, 8443）
- **修复**: 为生产环境添加TLS证书配置

### SERVER-004: 内存存储无持久化

- **严重程度**: 🟠 主要
- **问题**: API服务使用内存map存储会话，服务重启后数据丢失
- **位置**: `server/api/main.go` 第64-72行
- **现状**: 使用`sync.RWMutex`保护的内存map（sessions, sessionTokens, deviceStatus）
- **修复**: 考虑引入数据库支持（如SQLite/PostgreSQL）

### SERVER-005: 设备状态更新无认证

- **严重程度**: 🟠 主要
- **问题**: /api/device/status接口未使用authMiddleware
- **位置**: `server/api/main.go` 第599行
- **现状**: `api.POST("/device/status", server.updateDeviceStatus)` 无认证中间件，与其他设备接口（`/device/:id/status`使用authMiddleware）不一致
- **修复**: 为`/device/status`添加认证保护

### SERVER-006: JWT密钥管理问题

- **严重程度**: 🟠 主要
- **问题**: JWT密钥为空时自动生成随机值，每次重启都变化
- **位置**: `server/api/main.go` 第78-83行
- **现状**: 支持环境变量`JWT_SECRET`配置，但代码中默认为随机生成值（仅用于开发）
- **注意**: docker-compose.yml已配置`JWT_SECRET=${JWT_SECRET:-changeme-in-production}`
- **修复**: 生产环境应强制要求配置JWT\_SECRET，代码中移除随机默认值

### SERVER-007: 限流逻辑评估

- **严重程度**: 🟡 次要
- **问题**: 限流判断实现方式需要评估
- **位置**: `server/api/main.go` 第120-160行
- **验证**: 使用互斥锁保护loginAttempts，逻辑基本正确
- **结论**: 当前实现可接受（已确认有互斥锁保护）
- **建议**: 可考虑使用滑动窗口算法优化

### SERVER-008: 无连接数限制

- **严重程度**: 🟡 次要
- **问题**: MaxConnections字段定义但未使用
- **位置**: `server/socks5-proxy/main.go` 第27行（定义）、第602行（未使用）
- **现状**: MaxConnections=1000定义在Config中，但SOCKS5服务启动时未检查
- **修复**: 实现连接数限制逻辑

### SERVER-009: 配对码生成偏斜

- **严重程度**: 💡 建议
- **问题**: 模运算导致000000-048575出现概率略高
- **位置**: `server/api/main.go` 第94-102行
- **现状**: 使用`binary.BigEndian.Uint32(b) % 1000000`
- **修复**: 使用拒绝采样算法重新实现generateCode()


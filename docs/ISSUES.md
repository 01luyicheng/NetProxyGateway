# NetProxyGateway 待修复问题清单

## 高优先级

### ISSUE-014: docker-compose.yml 未预配置 TLS 证书

- **问题**: 代码已完整支持 TLS，但 docker-compose.yml 未预配置 TLS 证书路径
- **位置**:
  - `server/socks5-proxy/main.go` - 已实现 TLS 支持（`EnableTLS` 配置项，第 727-742 行）
  - `server/docker-compose.yml` - 未配置 TLS 证书路径
- **现状**: 所有服务以明文运行（端口 8080, 1080, 8443）
- **修复**: 为生产环境添加 TLS 证书配置，启用 `EnableTLS` 并配置 `TLSCert`/`TLSKey`

### ISSUE-027: 测试覆盖率不足

- **问题**: 核心业务类缺少单元测试
- **位置**: 整个项目
- **现有测试**: `MqttTlsPinningTest.kt`、`VpnDnsConfigTest.kt`、`IpAddressUtilsTest.kt`、`Socks5ProxyHandlerTest.kt`、`GatewayWifiManagerLogicTest.kt`、`MainViewModelTest.kt`
- **缺失测试**:
  - `VpnService.kt` (960 行代码) - VPN 核心逻辑
  - `AuthSessionStore.kt` (171 行代码) - 会话管理
  - `MqttConnectionManager.kt` (308 行代码) - MQTT 连接管理
  - `ModuleCoordinator.kt` (102 行代码) - 模块协调
- **修复**: 为核心业务类添加单元测试

***

## 中优先级

### ISSUE-031: 缺少反调试保护

- **问题**: 未实现反调试、反模拟器、Root 检测机制
- **位置**: 整个 Android 项目
- **现状**: 未发现任何反调试、反模拟器或 Root 检测相关代码
- **修复**: 评估后逐步引入安全防护机制

***

## 服务端问题 (Server)

### SERVER-003: TLS 证书配置缺失

- **严重程度**: 严重
- **问题**: docker-compose.yml 未配置 TLS 证书，所有服务以明文运行
- **位置**: `server/docker-compose.yml`
- **现状**: 端口 8080(API)、1080(SOCKS5)、8443(隧道) 均为明文；代码已支持 TLS 但未启用
- **修复**: 为生产环境添加 TLS 证书配置

### SERVER-004: 内存存储无持久化

- **严重程度**: 主要
- **问题**: API 服务使用内存 map 存储会话，服务重启后数据丢失
- **位置**: `server/api/main.go` 第 62-72 行
- **现状**: 使用 `sync.RWMutex` 保护的内存 map（sessions, sessionTokens, deviceStatus, loginAttempts）
- **修复**: 引入数据库支持（如 SQLite/PostgreSQL）

### SERVER-006: JWT 密钥管理问题

- **严重程度**: 主要
- **问题**: 代码中 JWT 密钥为空时自动生成随机值，存在安全风险
- **位置**: `server/api/main.go` 第 78-83 行
- **现状**: docker-compose.yml 已配置 `JWT_SECRET=${JWT_SECRET:-changeme-in-production}`，但代码仍有随机生成逻辑
- **修复**: 生产环境强制要求配置 JWT_SECRET，移除代码中的随机默认值

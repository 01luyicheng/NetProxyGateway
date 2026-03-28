# NetProxyGateway 待修复问题清单

## 高优先级

### ISSUE-027: 核心业务类测试覆盖率不足

- **严重程度**: 高
- **问题**: 以下核心业务类缺少单元测试，变更时无法及时发现潜在问题
- **位置**: `android/app/src/main/java/com/netproxy/gateway/`
- **缺失测试**:

| 文件 | 代码行数 | 职责 |
|------|----------|------|
| `vpn/VpnService.kt` | 959 行 | VPN 核心逻辑 |
| `connection/AuthSessionStore.kt` | 167 行 | 安全会话存储管理 |
| `connection/MqttConnectionManager.kt` | 308 行 | MQTT 连接管理 |
| `di/ModuleCoordinator.kt` | 102 行 | 模块间通信和状态同步 |

- **修复建议**: 为上述核心业务类添加单元测试，优先覆盖关键业务逻辑
- **状态**: 🔄 部分完成 - 已添加 135 个测试，但核心业务逻辑仍需补充

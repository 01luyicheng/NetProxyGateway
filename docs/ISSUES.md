# NetProxyGateway 问题清单

## 当前状态

本轮已完成对原有 ISSUE-001 ~ ISSUE-011 的代码核查与修复，已修复项已从待办列表中移除。
当前剩余 1 个未完全闭环问题：ISSUE-002（VPN 核心转发完整数据面）。

## 修复结论

- ISSUE-001: 已修复（TLS 连接改为显式系统信任链初始化）
- ISSUE-002: 部分修复（已从日志存根升级为实际转发尝试，但 TUN 双向完整会话仍待实现）
- ISSUE-003: 已修复（使用 BuildConfig 区分环境配置）
- ISSUE-004: 已修复（移除 MQTT 地址硬编码）
- ISSUE-005: 已修复（会话数据改为 EncryptedSharedPreferences + 内存清理）
- ISSUE-006: 已修复（测试凭证改为测试常量）
- ISSUE-007: 已修复（替换 printStackTrace 为结构化日志）
- ISSUE-008: 已修复（空 catch 改为日志记录）
- ISSUE-009: 已修复（AppModule 增加模块接口实现）
- ISSUE-010: 已修复（VPN 缓冲区复用）
- ISSUE-011: 已核实（`proguard-rules.pro` 文件原本已存在）

## 待审查清单

- [ ] ISSUE-002 完整修复：实现 TUN 双向数据面（回包注入、会话映射、生命周期管理）
- [ ] 运行端到端网络回归测试（真机 + 内外网混合流量）

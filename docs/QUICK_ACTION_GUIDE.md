# NetProxyGateway 项目 - 快速行动指南

**发布日期**: 2026年3月22日  
**当前状态**: ✅ v1 条件性可开工（v2 继续 POC）

---

## 核心结论

- 可以启动 v1：以“云端 SOCKS5 入口 + 设备反向隧道 + WiFi 内网访问”为主链路。
- 暂不全面启动 v2：`VpnService` 全局接管与高级分流继续 POC。
- 开工前需先完成文档口径收敛与安全默认值修订。

---

## 开工前清单（0.5-1 天）

1. 统一架构口径
- README、SPEC、计划文档统一为 v1 云端入口主路径。
- 将“历史审查结论”与“当前结论”明确分区。

2. 修复安全默认值
- 移除示例中的弱默认账号/密码。
- 修复 CORS 过宽示例与随机码弱降级示例。
- 明确示例均为 TLS 链路前提。

3. 冻结 v1 边界
- v1：CONNECT（TCP/IPv4）、会话绑定、目标过滤。
- v2：VPN 全接管、Tun2Socks/等价方案、动态分流引擎。

---

## 可以立即开始的任务（v1）

- Task 1: Gradle 项目初始化
- Task 2: Application + DI 框架
- Task 3D: WiFi 模块基础能力
- Task 3E: UI 框架骨架
- Task 4: 服务端实现（以 `docs/modules/server-socks5-relay.md` 为准）

---

## 暂不全面启动的任务（v2）

- Task 3A: 连接管理高级分流
- Task 3B: VPN 全局接管
- Task 3C: 本地 SOCKS5 与 VPN 深度集成

说明：以上任务可做 POC 与技术验证，但不作为当前里程碑的主交付路径。

---

## 本周建议节奏

1. 第 1 天上午
- 完成文档口径收敛与安全默认值修订。

2. 第 1 天下午
- 召开 60-90 分钟评审会，冻结 v1 接口与验收标准。

3. 第 2 天起
- 并行推进 Android 基础模块与服务端 v1 链路。

---

## 参考文档

- `docs/modules/server-socks5-relay.md`
- `docs/modules/integration-vpn-socks5.md`
- `docs/modules/traffic-split-engine.md`
- `docs/modules/13-tech-stack.md`
- `docs/plans/server/task-04-server-implementation.md`
- `docs/REVIEW_REPORT.md`

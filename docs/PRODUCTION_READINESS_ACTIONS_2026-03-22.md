# NetProxyGateway 生产可用差距与行动清单（2026-03-22）

## 0. 云服务选型结论（Supabase vs 普通 VPS）

结论：采用“分层混合”方案，避免技术债。

1. 控制面与后台管理：优先 Supabase
- 适合内容：配对会话、设备元数据、审计日志、管理 API、控制台鉴权。
- 原因：RLS、Auth、托管 Postgres、可观测性与运维成本更低，能更快进入生产可用。

2. 实时数据面中转：优先 VPS（或容器平台）
- 适合内容：SOCKS5 入口、反向隧道网关、高并发长连接转发。
- 原因：该类服务更依赖连接模型、内核网络参数、连接跟踪和带宽，VPS 更可控。

3. 架构建议
- Supabase 做“控制平面”，VPS 做“数据平面”。
- 通过标准 API 与短时令牌把两者解耦，避免后续迁移成本。

## 1. 本次已确认问题（按优先级）

### P0 - 构建与发布阻断

1. 系统 Gradle 9.x 与旧插件矩阵不兼容（已通过升级矩阵部分修复）
- 症状: Kotlin KAPT 在 Gradle 9 API 上报 `NoSuchMethodError`。
- 已采取: AGP/Kotlin/Compose 插件版本升级，构建已通过配置阶段。
- 风险: 仍需在 CI 固化版本矩阵，避免本地环境漂移。

2. Android 资源主题缺失导致 AAPT 失败（已修复）
- 症状: `Theme.NetProxyGateway` not found。
- 已采取: 新增 `android/app/src/main/res/values/themes.xml`。

3. 本地 Android SDK 路径无效（未修复，环境问题）
- 症状: `local.properties` 的 `sdk.dir` 不存在。
- 处理建议: 使用本机真实 SDK 路径或环境变量统一管理，不将机器私有路径写入仓库。

### P1 - 代码正确性与可维护性

1. 类名与 Android 框架同名导致歧义（已修复）
- `WifiManager` -> `GatewayWifiManager`
- `VpnService` -> `GatewayVpnService`
- 同步更新 DI、Manifest、ViewModel 引用。

2. 配对状态过早置成功（已修复）
- 变更为: 仅当 MQTT 状态 `Connected` 时置 `isPaired=true`。

3. 断连/错误时配对状态未及时回收（已修复）
- 变更为: MQTT `Disconnected` 与 `Error` 统一置 `isConnected=false` 且 `isPaired=false`，避免 UI 假阳性。

4. VPN 包解析未使用有效长度（已修复）
- 变更为: 目标 IP 解析使用 `read` 返回的 `length`，避免读取缓冲区历史数据导致误判。

5. VPN 前台服务启动时序风险（已修复）
- 变更为: `GatewayVpnService` 在启动阶段立即 `startForeground`，并补齐通知渠道与常驻通知。

### P1 - 安全与生产基线

1. SOCKS5 鉴权仍偏弱（待修）
- 当前仍存在“仅非空校验/无强绑定令牌”的风险。
- 建议: 采用短时、可撤销、绑定 deviceId 的会话令牌；服务端限速与封禁。

2. MQTT 凭证强度不足（待修）
- 6 位识别码直接作为认证凭证风险高。
- 建议: 使用高熵 token（>=128bit）+ 短 TTL + 重放防护。

3. 文档能力宣称与实现差距（待修）
- v1 云端链路仍需服务端工程落地与联调。
- 建议: README 与版本计划明确“已实现/进行中/规划中”。

## 2. 技术债务控制策略

1. 版本矩阵治理
- 固化: AGP、Kotlin、Compose、Gradle Wrapper 版本到文档与 CI。
- 新增: 每次升级执行 `assembleDebug + testDebugUnitTest + lint`。

2. 构建环境治理
- 不提交开发机私有 `sdk.dir`。
- 在 CI 使用标准 Android SDK 镜像与缓存。

3. 安全基线治理
- 控制面消息加入 `message_id + expires_at + 去重`。
- 鉴权失败加入限流、冷却、短期封禁。
- 日志脱敏，禁打 payload 和凭证。

4. 测试与回归
- 优先补齐：配对状态机、MQTT 重连状态机、SOCKS5 鉴权/目标过滤。

## 4. 本轮验证结果（2026-03-22）

1. 单元测试
- 命令: `gradle -p android :app:testDebugUnitTest --stacktrace`
- 结果: 通过（此前 `Socks5ProxyHandlerTest` 的 2 个失败用例已修复并通过）。

2. 构建验证
- 命令: `gradle -p android assembleDebug --stacktrace`
- 结果: 通过。

3. 剩余告警（非阻断）
- `local.properties` 中 `sdk.dir` 路径无效。
- KAPT/Kotlin 2.0 兼容性提示与 Gradle 10 前向兼容性 deprecation 提示。

## 5. 下一步执行顺序（建议）

1. 修复/确认本机 SDK 路径，保证构建稳定。
2. 完成系统 Gradle 9.x 下的完整构建与测试闭环。
3. 推进服务端 v1 最小闭环（Socks5 entry + reverse tunnel + session binding）。
4. 按安全基线补齐鉴权与限流。

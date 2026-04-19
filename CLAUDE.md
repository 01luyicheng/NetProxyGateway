本文档是 AI 代理在此仓库中的唯一执行契约。AGENTS.md是CLAUDE.md的符号链接。

## 1) 目标（当前阶段）

为远程网络协助工作流提供可靠的 Android 行为。
本阶段不要扩大产品范围。要支持国际化。

## 2) 现实约束

- 仓库包含 Android 客户端代码和 Go 服务端代码。
- 服务端组件：`server/api/`、`server/socks5-proxy/`、`server/tunnel/`

## 3) 事实来源

- 运行时行为：位于 `android/app/src/main/java/com/netproxy/gateway/` 下的 Kotlin 代码。
- 构建配置：`android/build.gradle.kts`、`android/app/build.gradle.kts`、`android/gradle/wrapper/gradle-wrapper.properties`。
- 测试：`android/app/src/test/`。

如果文档与代码冲突，以代码为准并更新本文档。

## 4) 禁止的操作

禁止：

- 捏造不存在的模块、文件或服务端状态。
- 编写推测性的架构文档。
- 保留不需要的下一步实施步骤的重复设计文档。

## 5) 当前技术风险（主要记录在 docs/TECH_DEBT.md 和 docs/ISSUES.md 中）

### 运行时风险
- WiFi 连接路径使用传统 API，在 Android 10+ 上受限。
- MQTT 安全性使用 TLS 1.2 和证书固定（当 MQTT\_TLS\_PUBLIC\_KEY\_PINS 配置时）；为空时回退到默认 CA 验证。
- **网络出口控制**: 当前使用 `protect()` 绕过 VPN，但无法强制指定 Socket 使用 WiFi 或移动数据。在厂商双 WiFi 加速、Link Turbo 等场景下，流量可能被系统路由到非预期网卡。
- **厂商定制 ROM**: 项目中完全没有针对小米、华为、OPPO、vivo 等厂商的适配代码。这些厂商的电池优化、后台限制、网络加速等功能可能影响 VPN 服务稳定性。
- **多网络 API 缺失**: 代码未使用 `Network.bindSocket()`、`excludeRoute()` 等 Android 原生多网络 API，无法实现真正的网卡强制绑定。
- **多网络场景处理不完善**: 手机可能同时连接多个网络（双WiFi、蓝牙PAN、OTG有线网络等），当前代码只能识别单一网络类型，无法正确处理多网络共存和切换场景。详见 `docs/TECH_DEBT.md` 中 C1 和 C3。

### 关键缺陷（需立即修复）
- **连接池竞态**: 连接池清理竞争条件（docs/ISSUES.md H5），read锁和write锁之间连接状态可能变化。

### 依赖与维护风险
- **依赖风险**: `gorilla/websocket` 库已归档不再维护（docs/ISSUES.md N14），存在安全漏洞无法及时修复的风险。
- **MQTT客户端**: Paho MQTT 维护不活跃（docs/ISSUES.md N15），新功能和 bug 修复可能延迟。
- **已弃用API**: 大量使用已弃用 API（`EncryptedSharedPreferences`、`WifiConfiguration`、`NioEventLoopGroup` 等，docs/ISSUES.md N13）。

### 工程化与架构债务
- **测试缺口**: 核心业务逻辑（`processVpnTraffic`、`forwardViaSocks5`）缺乏测试覆盖（docs/ISSUES.md N8）。
- **监控缺失**: 没有性能指标收集、健康检查端点、错误上报机制（docs/ISSUES.md N10）。
- **构建流程**: 缺少 Makefile 统一构建流程（docs/TECH_DEBT.md C11）。
- **服务发现**: 服务间使用硬编码地址通信（docs/TECH_DEBT.md C10）。
- **单点故障**: Tunnel Gateway 单点部署，无法水平扩展（docs/TECH_DEBT.md C9）。
- **代码组织**: Go 项目结构不规范，未按标准分层（docs/TECH_DEBT.md C7）；VpnService 过于庞大（1036行，docs/ISSUES.md N2）。
- 任何超出这两点的声明必须先在代码中验证。

## 6) 执行优先级队列

1. 保持 Gradle wrapper 上的构建和目标测试通过。
2. 修复影响连接、VPN、代理、WiFi 和 UI 状态流的正确性错误。
3. 在广泛重构之前，围绕更改的逻辑增加测试覆盖率。
4. 协调 Android 客户端与 Go 服务端组件。

## 7) 验证门禁（必须通过）

- 构建：`android\\gradlew.bat -p android assembleDebug --stacktrace --no-daemon`
- 单元测试：`android\\gradlew.bat -p android :app:testDebugUnitTest --stacktrace --no-daemon`
- 对于有针对性的更改，先运行最窄相关的测试，然后根据需要运行更广泛的测试套件。
- 任何更改都要让subagents交叉审查，确保代码质量。

## 8) AI 变更输出格式

- 从说明变更内容和原因开始。
- 包含受影响的文件路径。
- 包含实际运行的验证命令及结果。
- 明确说明未解决的阻塞问题和发现的新问题（新发现的待修复问题应该记录到对应的文档）。
- 下一步建议。

## 9) 文档极简主义规则

只保留会改变 AI 执行决策的文档。
删除过时、重复或理想化的文档以减少上下文噪音。Claude不需要被教怎么写代码。

## 10) 多Agent协作标准

- 每次修改代码完成后必须让多个subagents交叉审查变更，在通过审查没有发现新引入的问题以后及时提交变更并推送。
- 要多使用subagents进行规划、验证问题、更改代码、审查等，但不要并行subagent更改代码，因为可能遇到文件锁冲突等问题。

**分支策略**：

- `main`: 生产就绪代码，只能通过 PR 合并
- `dev`: 主开发分支，所有功能分支从此创建
- `dev-reviewed`: 经过代码审查后的dev分支

## 11) 验证跟踪（持续性）

为维护跨会话的连续性：

- 每个通过验证的合并提交必须注明Agent使用的模型名称和日期
- 在开始工作前，必须验证：`android\gradlew.bat -p android :app:testDebugUnitTest` 通过
- 如果 main 分支测试失败，通过在第 5 节（技术风险）中提交明确的阻塞问题来解除阻塞

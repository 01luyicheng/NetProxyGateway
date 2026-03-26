本文档是 AI 代理在此仓库中的唯一执行契约。

## 1) 目标（当前阶段）

为远程网络协助工作流提供可靠的 Android 行为。
本阶段不要扩大产品范围。

## 2) 现实约束

- 仓库包含 Android 客户端代码和 Go 服务端代码。
- 服务端组件：`server/api/`、`server/socks5-proxy/`、`server/tunnel/`
- 如果需求依赖于未实现的服务端功能，将其视为已阻塞并记录差距。

## 3) 事实来源

- 运行时行为：位于 `android/app/src/main/java/com/netproxy/gateway/` 下的 Kotlin 代码。
- 构建配置：`android/build.gradle.kts`、`android/app/build.gradle.kts`、`android/gradle/wrapper/gradle-wrapper.properties`。
- 测试：`android/app/src/test/`。

如果文档与代码冲突，以代码为准并更新本文档。

## 4) 允许和禁止的操作

允许：

- 修复 Android 缺陷。
- 添加或更新 Android 单元测试。
- 当行为被保留或由测试覆盖时，重构 Android 内部代码。

禁止：

- 捏造不存在的模块、文件或服务端状态。
- 编写推测性的架构文档。
- 保留不需要的下一步实施步骤的重复设计文档。

## 5) 当前技术风险

- WiFi 连接路径使用传统 API，在 Android 10+ 上受限。
- MQTT 安全性使用 TLS 1.2 和证书固定（当 MQTT_TLS_PUBLIC_KEY_PINS 配置时）；为空时回退到默认 CA 验证。
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

## 8) AI 变更输出格式

- 从说明变更内容和原因开始。
- 包含受影响的文件路径。
- 包含实际运行的验证命令及结果。
- 明确说明未解决的阻塞问题。

## 9) 文档极简主义规则

只保留会改变 AI 执行决策的文档。
删除过时、重复或理想化的文档以减少上下文噪音。

## 10) 多代理协作标准

当多个 AI 代理并行或顺序工作时：

- 每个代理在开始工作前必须阅读本 CLAUDE.md
- 代理之间不进行通信；所有约束通过本文档向下传递
- 代理之间变更发生冲突时，以本文档最近一次提交为准
- 所有代理通过 git pull request 合并变更（永不 force-push）
- 如果两个代理修改了同一个代码文件，第二个代理必须在拉取 main 后进行 rebase

## 11) 年度文档审计（维护协议）

每 12 个月执行一次健康审查：

1. 一旦在生产环境中验证完成，移除已解决的技术风险（第 5 节）
2. 验证目标仍与产品现实保持一致；如有调整则更新
3. 使用新的 Android OS 限制或已弃用的 API 更新现实约束
4. 将已完成的优先级队列项目存档到 git 提交历史中（不留活文档）
5. 强制执行 CLAUDE.md 行数上限：保持在 400 行以内

规则：超过 6 个月且已解决的内容应存在于代码注释或测试文件中，而非本文档中。这确保上下文始终聚焦于当前工作。

## 12) 验证跟踪（持续性）

为维护跨会话的代理连续性：

- 每个通过验证的合并提交必须注明代理名称和日期
- 在开始工作前，代理必须验证：`android\gradlew.bat -p android :app:testDebugUnitTest` 通过
- 如果 main 分支测试失败，通过在第 5 节（技术风险）中提交明确的阻塞问题来解除阻塞

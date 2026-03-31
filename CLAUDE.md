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
- MQTT 安全性使用 TLS 1.2 和证书固定（当 MQTT\_TLS\_PUBLIC\_KEY\_PINS 配置时）；为空时回退到默认 CA 验证。
- **网络出口控制**: 当前使用 `protect()` 绕过 VPN，但无法强制指定 Socket 使用 WiFi 或移动数据。在厂商双 WiFi 加速、Link Turbo 等场景下，流量可能被系统路由到非预期网卡。
- **厂商定制 ROM**: 项目中完全没有针对小米、华为、OPPO、vivo 等厂商的适配代码。这些厂商的电池优化、后台限制、网络加速等功能可能影响 VPN 服务稳定性。
- **多网络 API 缺失**: 代码未使用 `Network.bindSocket()`、`excludeRoute()` 等 Android 原生多网络 API，无法实现真正的网卡强制绑定。
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
删除过时、重复或理想化的文档以减少上下文噪音。Claude不需要被教怎么写代码。

## 10) 多Agent协作标准

当多个 AI  Agent并行或顺序工作时：

- 每个代理在开始工作前必须阅读本文档
- 代理之间不进行通信；所有约束通过本文档向下传递
- 代理之间变更发生冲突时，以本文档最近一次提交为准
- 所有代理通过 git pull request 合并变更
- 如果两个代理修改了同一个代码文件，第二个代理必须在拉取 main 后进行 rebase

**分支策略**：

- `main`: 生产就绪代码，只能通过 PR 合并
- `dev`: 主开发分支，所有功能分支从此创建

## 11) 验证跟踪（持续性）

为维护跨会话的连续性：

- 每个通过验证的合并提交必须注明代理名称和日期
- 在开始工作前，必须验证：`android\gradlew.bat -p android :app:testDebugUnitTest` 通过
- 如果 main 分支测试失败，通过在第 5 节（技术风险）中提交明确的阻塞问题来解除阻塞


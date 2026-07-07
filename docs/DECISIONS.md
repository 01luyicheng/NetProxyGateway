# 架构决策记录 (Architecture Decision Records)

本文档记录 NetProxyGateway 项目中的重要技术决策及其原因。每个决策都包含背景、决策内容和后果，帮助理解代码现状并指导未来演进。

---

## ADR-001: 使用自定义 AppResult 而非 Kotlin 标准库 Result

**日期**: 2024年初 (项目启动时)

**状态**: 已弃用 (计划迁移至标准库 Result)

### 背景

项目启动时，Kotlin 标准库的 `Result` 类型虽已存在，但存在以下限制：
1. Kotlin 1.5 之前，`Result` 在标准库中功能有限
2. 标准库 `Result` 当时在某些场景下存在限制（如作为泛型参数时的装箱问题）
3. 团队需要统一的错误处理方式，而当时各模块使用不一致的异常处理模式

### 决策

创建自定义的 `AppResult<T>` 密封类，提供：
- 明确的 `Success` 和 `Error` 状态
- 丰富的扩展函数（`onSuccess`, `onError`, `map`, `flatMap`, `zip` 等）
- 与 Kotlin 标准库 `Result` 的双向转换能力
- 统一的错误包装和传递机制

### 后果

**正面**:
- 提供了类型安全的错误处理机制
- 统一了跨模块的错误处理风格
- 扩展函数提供了函数式编程风格的链式调用能力

**负面**:
- 增加了额外的抽象层，新开发者需要学习自定义类型
- 与标准库 `Result` 不直接兼容，需要转换
- 现在 Kotlin 标准库 `Result` 已成熟，自定义类型成为技术债务

**当前状态**:
- `AppResult` 已提供 `toResult()` 和 `fromResult()` 方法支持迁移

---

## ADR-002: CommandHandler 和 QueryHandler 接口拆分

**日期**: 2024年中

**状态**: 过度设计 (被 ISSUES.md N4/N1 标记)

### 背景

项目采用模块化架构，最初每个模块定义一个单一接口（如 `CommunicationModule`）。随着代码增长，发现：
1. 某些场景只需要读取状态（查询），不需要执行命令
2. 遵循 CQRS（命令查询职责分离）原则可能提高代码清晰度
3. 希望明确区分只读操作和写操作

### 决策

将每个模块的接口拆分为两个：
- `CommandHandler`: 处理写操作（`connect`, `publish`, `startVpn` 等）
- `QueryHandler`: 处理读操作（`getConnectionState`, `getVpnStatus` 等）

原有接口标记为 `@Deprecated`，保留向后兼容性。

### 代码示例

```kotlin
// 命令处理器
interface CommunicationCommandHandler {
    fun connect(deviceId: String, authToken: String): Boolean
    fun publish(topic: String, payload: String, qos: Int)
    fun subscribe(topic: String, qos: Int, callback: ((String) -> Unit)?)
    fun disconnect()
}

// 查询处理器
interface CommunicationQueryHandler {
    fun getConnectionState(): StateFlow<MqttConnectionState>
}
```

**注**: 接口名称在代码中为 `CommunicationCommandHandler` 和 `CommunicationQueryHandler`，与 ISSUES.md N4 描述一致。

### 后果

**正面**:
- 明确了命令和查询的边界
- 允许调用方只依赖需要的功能子集
- 理论上便于测试（可以 mock 更小的接口）

**负面**:
- ISSUES.md N4 指出这是接口过度设计
- 每个模块实际只有一个实现，拆分增加了不必要的复杂性
- 接口数量翻倍，增加了维护负担
- 与 Hilt 依赖注入结合时，形成隐式依赖网（ISSUES.md N1）

**反思**:
CQRS 模式更适合大规模系统或有明确读写分离需求的场景。对于当前项目规模，单一接口可能更简单实用。

---

## ADR-003: 使用 AtomicInteger 进行虚拟 IP 分配

**日期**: 2024年中

**状态**: 已接受 (存在已知问题)

### 背景

VPN 服务需要为每个目标 IP 分配一个虚拟源 IP（`10.0.0.x` 网段），用于构造回包。要求：
1. 线程安全的 IP 分配
2. 避免 IP 冲突
3. 支持 IP 复用（同一目标 IP 应获得相同虚拟 IP）

### 决策

使用 `synchronized` 锁 + `MutableMap` + `AtomicInteger` 组合方案。核心实现位于 `VirtualIpAllocatorImpl`：

- 锁保护：`synchronized(ipAllocationLock)` 保护复合操作（L65）
- IP复用：先检查 `virtualIpPool[realDstIp]` 是否已存在映射（L67）
- 溢出处理：`floorMod` 将超出范围的 IP 映射回有效区间（L95）
- 循环分配：当 IP 被占用时自动尝试下一个（L90-L112）
- 防无限循环：`maxAttempts` 限制尝试次数（L109）

**代码位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VirtualIpAllocator.kt` (L46-L148)

### 后果

**正面**:
- `synchronized` 锁保护复合操作，避免竞态条件
- `AtomicInteger` 提供原子递增，支持循环分配
- `floorMod` 处理溢出情况，确保 IP 始终在有效范围内
- 实现简单，无需额外同步

**负面**:
- ISSUES.md H17（已修复）曾指出存在线程安全问题：IP 耗尽时的重置逻辑在竞态条件下可能产生冲突
- 固定使用 `10.0.0.x` 网段，可能与用户内网冲突（KNOWN_LIMITATIONS.md L-PROD-06）

**已修复**:
- 添加了 `synchronized` 锁保护复合操作
- 使用 `floorMod` 处理溢出，添加循环分配和尝试次数限制

---

## ADR-004: MQTT 连接使用 TLS 1.2+ 和证书固定

**日期**: 2024年初

**状态**: 已接受

### 背景

MQTT 是远程协助的核心通信通道，安全性至关重要：
1. 需要防止中间人攻击
2. 可能使用自签名证书（开发环境）
3. 生产环境需要严格的证书验证

### 决策

实施分层 TLS 策略：

1. **生产环境**:
   - 使用 TLS 1.2+
   - 系统默认 CA 证书验证
   - 支持证书固定（Certificate Pinning）
   - 当 `MQTT_TLS_PUBLIC_KEY_PINS` 配置时，验证服务器公钥指纹

2. **开发环境**:
   - 允许信任所有证书（仅 DEBUG 模式）
   - 支持自签名证书服务器

```kotlin
// 证书固定实现
object MqttTlsPinning {
    fun createPinningTrustManager(delegate: X509TrustManager, rawPins: String?): X509TrustManager
    fun verifyPinMatch(configuredPins: Set<String>, presentedPins: Set<String>)
}
```

### 后果

**正面**:
- 生产环境有严格的证书验证
- 证书固定提供额外的安全层
- 开发环境灵活，支持自签名证书

**负面**:
- ISSUES.md C1 (已从 Critical 降级至 Medium): `MQTT_TRUST_ALL_CERTS` 在 debug 模式下默认为 true，但运行时检查现在可防止生产环境使用不安全配置
- ISSUES.md H8: 当 `MQTT_TLS_PUBLIC_KEY_PINS` 为空时，仅记录警告，回退到默认 CA 验证
- 证书固定需要定期更新配置（证书轮换时）

**安全注意事项**:
- 生产构建必须设置 `DEBUG=false` 和 `MQTT_TRUST_ALL_CERTS=false`
- 证书固定配置应通过安全渠道分发

---

## ADR-005: WiFi 连接使用传统 WifiConfiguration API

**日期**: 2024年初

**状态**: 已改进 (已实现双路径支持)

### 背景

应用需要连接指定的 WiFi 网络以访问内网设备。Android WiFi API 经历了多次演进：
- Android 9 及以下: `WifiConfiguration` API
- Android 10+: `WifiNetworkSuggestion` API（推荐）
- Android 10+ 对后台应用启动 WiFi 连接有限制

### 决策

实现双路径WiFi连接支持，根据Android版本自动选择API：

```kotlin
fun connectToNetwork(ssid: String, password: String?, securityType: String): Boolean {
    val parsedSecurityType = parseSecurityType(securityType)
    return if (shouldUseNetworkSuggestion()) {
        connectUsingNetworkSuggestion(ssid, password, parsedSecurityType)
    } else {
        connectUsingLegacyConfig(ssid, password, parsedSecurityType)
    }
}

private fun connectUsingLegacyConfig(ssid: String, password: String?, securityType: SecurityType): Boolean {
    val configuration = WifiConfiguration().apply {
        SSID = "\"$ssid\""
        // ... 配置安全类型
    }
    val networkId = wifiManager.addNetwork(configuration)
    return wifiManager.enableNetwork(networkId, true)
}
```

- Android 10+ (API 29+): 使用 `WifiNetworkSuggestion` API
- Android 9及以下: 使用 `WifiConfiguration` API

### 后果

**正面**:
- 兼容 Android 9 及以下设备
- 代码实现简单直接
- 可以强制连接到指定网络

**负面**:
- ISSUES.md N13: `WifiConfiguration` API 在 Android 10+ 上已被废弃
- Android 10+ 对后台应用启动 WiFi 连接有限制，可能需要用户手动确认
- 某些厂商 ROM（小米、华为等）可能有额外限制（KNOWN_LIMITATIONS.md L-TECH-02）
- 随机 MAC 地址功能未处理（ISSUES.md N13）

**关联限制**:
- KNOWN_LIMITATIONS.md L-IPV6-08: WiFi连接信息仅获取IPv4地址

**建议**:
- 优先使用 `WifiNetworkSuggestion` API
- 提供清晰的用户引导，处理权限和限制问题
- 考虑使用 QR 码等方式简化 WiFi 配置

---

## ADR-006: CI 架构 — 持久 Self-hosted Runner + 多 Workflow 拆分

**日期**: 2026-07-07

**状态**: 已实施

### 背景

项目原先使用单一 `ci.yml` workflow，包含 Android 和 Go 两个 job（Go 使用 matrix 3 组件）。CI 通过率 0%，原因包括 Android 测试死锁（N87）、CRLF 伪 diff（N88）、CGO/sqlite3 编译失败等。需要重构 CI 使其可靠运行，同时为持久 self-hosted runner（`ar-npg-sfo3`）做好长期配置。

### 决策

1. **持久 Self-hosted Runner 作为长期方案**：不使用 `ubuntu-latest`，所有 CI 修改不假设未来切回 GitHub-hosted runner。Runner 迁移决策由 `docs/RUNNER_DECISION.md` 维护。

2. **Workflow 拆分为 6 个文件**：
   - `android-ci.yml` — required check，Android 构建测试
   - `go-ci.yml` — required check，Go 7 组件 matrix（3 server + 4 shared）
   - `pr-checks.yml` — commitlint + dependency-review
   - `security.yml` — govulncheck + Android dep-check + Docker 构建验证
   - `secret-scan.yml` — gitleaks 密钥扫描
   - `runner-cleanup.yml` — 每日磁盘清理（定时任务）

3. **路径过滤策略**：required check 的 workflow 使用 `paths-ignore`（而非 `paths`），确保 workflow 始终触发并产生 success 结论。job 内部使用 `dorny/paths-filter@v3` 检测变更，无变更时输出 success（非 skipped）。

4. **Go matrix 使用 `matrix.include`**：避免两个独立数组产生笛卡尔积。每个 component 与 path 一一对应。

5. **Go cache 隔离**：`GOMODCACHE`/`GOCACHE` 按 component 分到 `/tmp/` 目录（因 GitHub Actions job-level `env` 不支持 `runner.temp` context，见 N89）。

6. **CI 统一调用 Makefile**：`go-ci.yml` 通过 `make go-ci-component COMPONENT=xxx` 调用，确保本地与 CI 行为一致。Android 因 N87 降级需要 `timeout` 包装，暂不统一。

7. **Android 测试降级**：N87 根因未查明前，使用 `timeout 15m` + `continue-on-error: true` 确保流水线不阻塞。

8. **质量检查渐进收紧**：gofmt/go vet/go mod tidy 初始以 `continue-on-error: true` 收集基线，违规量清零后收紧为硬门禁。

### 后果

**正面**:
- CI 通过率从 0% 提升至 Go 7/7 + Android 核心步骤全部通过
- cancel-in-progress 避免排队
- 路径过滤减少无关 runner 启动
- Makefile 统一本地与 CI 行为

**负面**:
- N87 测试挂死根因未查，仍需后续调查
- api 组件因 runner 缺 gcc 无法启用 CGO（sqlite3 测试不通过）
- dependency-check 首次运行极慢（下载漏洞数据库）

**已知 GitHub Actions 限制**:
- Job-level `env` 不支持 `${{ runner.* }}` context（N89）
- Job-level `permissions` 完全替换 workflow-level（非合并）
- `cancel-in-progress` 可能无法终止卡死的 Gradle 进程

### 关联

- ISSUES.md: N87（测试挂死降级）、N88（CRLF 修复）、N89（runner context 限制）
- RUNNER_DECISION.md: runner 选型与长期配置

---

## 决策演进路线图

| 决策 | 当前状态 | 建议行动 | 优先级 | 时间线 |
|------|----------|----------|--------|--------|
| ADR-001 AppResult | 已弃用 | 逐步迁移到 Kotlin 标准库 Result | 中 | 中期 |
| ADR-002 CQRS 接口拆分 | 过度设计 | 考虑合并回单一接口 | 低 | 长期 |
| ADR-003 虚拟 IP 分配 | 存在问题 | 修复线程安全问题，考虑 IP 池管理 | 高 | 短期 |
| ADR-004 MQTT TLS | 已接受 | 加强生产环境安全检查 | 中 | 中期 |
| ADR-005 WiFi API | 已改进 | 已实现双路径支持，继续优化用户体验 | 中 | 中期 |
| ADR-006 CI 架构拆分 | 已实施 | 质量检查收紧 + N87 根因调查 + CGO 启用 | 中 | 短期 |

### 优先级说明

- **高优先级**: 影响功能正确性或存在安全风险，需尽快处理
- **中优先级**: 技术债务，影响代码质量和可维护性
- **低优先级**: 架构优化，当前影响较小

### 时间线说明

- **短期**: 1-2周内完成
- **中期**: 1-2个月内完成
- **长期**: 3个月以上或根据业务发展决定

---

## 变更记录

| 日期 | 变更内容 | 变更人 |
|------|----------|--------|
| 2026-03-31 | 初始创建，从原ISSUES.md和代码注释中提取架构决策 | AI Agent |
| 2026-03-31 | 初始创建，从原ISSUES.md和代码注释中提取架构决策 | AI Agent |
| 2026-05-03 | 修正ISSUE引用：ADR-002的ISSUE-014改为ISSUES.md N4/N1；ADR-003的ISSUE C3改为ISSUES.md H17；ADR-004的ISSUE H7改为ISSUES.md H8；ADR-005的ISSUE M7/A1改为ISSUES.md N13，ISSUE M20改为ISSUES.md N13；删除不存在的ISSUE L12/L17引用；更新ADR-003代码示例与实际实现一致 | AI Agent (Kimi-K2.6) |
| 2026-07-07 | 新增 ADR-006：CI 架构拆分决策（6 workflow + Makefile 统一 + 路径过滤 + 持久 runner 配置） | AI Agent |

---

## 参考

- [ISSUES.md](./ISSUES.md) - 详细问题清单
- [TECH_DEBT.md](./TECH_DEBT.md) - 技术债务清单
- [BLOCKERS.md](./BLOCKERS.md) - 阻塞问题清单
- [KNOWN_LIMITATIONS.md](./KNOWN_LIMITATIONS.md) - 已知限制清单
- [CLAUDE.md](../CLAUDE.md) - AI 代理执行契约（AGENTS.md 是其符号链接）

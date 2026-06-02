# Dev 分支提交审查发现

审查日期: 2026-06-02
审查范围: dev 分支 f29d9b2 (Merge PR #12) .. 93fccd9 (HEAD)
审查模型: solo-agent

---

## 验证状态汇总

| 问题 | 描述 | 验证结果 | 优先级 |
|------|------|----------|--------|
| P1 | 9ef4d7e WebSocket readLoop defer 条件关闭导致连接泄漏 | 已验证，修复正确 | 高 |
| P2 | cc9ab45/4a1eb2c 重复修复：锁内 close + .gitattributes + N83 注释 | 已验证，4a1eb2c 是 cc9ab45 的重复 | 中 |
| P3 | 4ab337d WIP 提交直接合并到 dev | 已验证，存在代码问题 | 高 |
| P4 | b0a86f5 discardConnection 修复了 WIP 提交的计数泄漏 | 已验证，修复正确 | 高 |
| P5 | 1a92b24 CI 配置改进（concurrency + commit lint 内联） | 已验证，改进合理 | 低 |
| P6 | 9d99a57 测试验证目标修正 + virtualIpAllocator mock | 已验证，修复正确 | 中 |
| P7 | a1c463c 合并提交包含重复文件变更 | 已验证，合并历史不干净 | 低 |
| P8 | 1fcc430 合并冲突解决正确保留 discardConnection | 已验证，合并正确 | 低 |
| P9 | 93fccd9 恢复域名请求支持（REVIEW_FINDINGS P2） | 已验证，修复正确 | 中 |
| P10 | N86 修复后 cleanupVpnResources 仍使用 close() 而非 discardConnection | 已验证，代码不一致 | 中 |
| P11 | N86 修复后 ISSUES.md 状态未及时更新为"已修复" | 已验证，文档滞后 | 低 |
| P12 | VpnServiceTest 直接实例化 GatewayVpnService（H15 遗留） | 已验证，问题仍存在 | 高 |

---

## 问题列表

### P1: 9ef4d7e WebSocket readLoop defer 条件关闭导致连接泄漏 [已修复]
- **提交**: `9ef4d7e` - fix(socks5-proxy): unconditionally close WebSocket conn in readLoop defer
- **文件**: `server/socks5-proxy/main.go`
- **描述**: readLoop defer 原仅在连接仍存在于 connections map 时才关闭 WebSocket 连接。当 getExistingConn 检测到死连接并调用 removeConnectionAndCollectStreams 将其从 map 移除后，readLoop defer 发现连接已不在 map 中，跳过关闭，导致 WebSocket 连接泄漏（FD + ~150KB 内存/次）。
- **修复**: readLoop defer 中无条件调用 conn.Close()，利用 Close 的幂等性避免双关问题。
- **验证**: 代码确认 defer 中已移除 map 存在性检查，直接执行 conn.Close()
- **建议**: 无。修复正确且必要。

### P2: cc9ab45 与 4a1eb2c 是实质相同的重复修复 [已识别]
- **提交**: `cc9ab45` (fix/websocket-conn-leak 分支) 和 `4a1eb2c` (fix/p8-n27-pool-lock-close 分支)
- **文件**: `.gitattributes`, `REVIEW_FINDINGS.md`, `Socks5ConnectionPool.kt`, `docs/ISSUES.md`, `server/tunnel/main.go`
- **描述**: 两个提交都实现了相同的三项修复：
  1. P8/N27: 将 socket close() 移出 write lock
  2. N83: 恢复 server/tunnel/main.go 的并发安全注释
  3. N81: 添加 .gitattributes 强制 LF 换行符
  cc9ab45 先合入 main（通过 PR #7），4a1eb2c 后合入 dev（声称"Supersedes #13"）。两者 diff 内容几乎完全一致。
- **验证**: git diff cc9ab45 4a1eb2c 显示仅作者信息和时间戳差异，代码变更一致
- **影响**: 合并 a1c463c 时因为 dev 分支已包含 4a1eb2c 的变更，与 origin/dev 的 cc9ab45 产生冲突，1fcc430 专门解决此冲突。
- **建议**: 分支管理需改进，避免同一修复在多个分支重复提交。合并前应检查目标分支是否已包含相同变更。

### P3: 4ab337d WIP 提交直接合并到 dev，代码存在缺陷 [已修复]
- **提交**: `4ab337d` - WIP: N86修复 - 过期/无效会话连接直接关闭而非归还连接池
- **文件**: `VpnService.kt`, `Socks5ConnectionPool.kt`, `Makefile`, `docker-compose.yml`, 文档等
- **描述**: WIP（Work In Progress）提交不应直接合并到主开发分支。该提交中 VpnService.kt 直接调用 pooledConnection.close()，但 close() 不会从连接池的 allConnections 中移除连接，也不会递减 totalConnections。死连接在 allConnections 中残留最多 30 秒，totalConnections 虚高，可能导致 tryReserveConnectionSlot 错误拒绝新连接。
- **验证**: 代码确认 4ab337d 中 VpnService 多处使用 close() 而非从连接池移除跟踪
- **后续修复**: b0a86f5 新增 discardConnection() 方法解决了此问题

### P4: b0a86f5 discardConnection 修复了 WIP 提交的计数泄漏 [已修复]
- **提交**: `b0a86f5` - fix: 新增 discardConnection 方法替换直接 close() 调用
- **文件**: `Socks5ConnectionPool.kt`, `VpnService.kt`, `Socks5ConnectionPoolTest.kt`, `VpnServiceTest.kt`
- **描述**: 针对 4ab337d 中直接 close() 导致连接池计数泄漏的问题，新增 discardConnection() 方法：在 write 锁下从 availableConnections 和 allConnections 中移除连接并递减计数，然后关闭 socket。VpnService 中 5 处 close() 替换为 discardConnection()。
- **验证**: 代码确认 discardConnection() 在锁内完成移除和计数递减，锁外关闭 socket
- **问题**: VpnServiceTest.kt 中测试验证的是 mockPool.discardConnection()，但 mock 是 relaxed 的，不会实际调用 connection.close()。测试注释说明"verify mockSocket.close() 实际未被调用"，这是测试设计的已知限制。

### P5: 1a92b24 CI 配置改进合理 [已验证]
- **提交**: `1a92b24` - refactor(ci): 添加 concurrency 控制，移除 push 触发和独立 lint job
- **文件**: `.github/workflows/ci.yml`
- **描述**:
  1. 移除 push 触发，仅保留 pull_request 触发，避免重复运行
  2. 添加 concurrency 配置，同一 PR 的新提交会取消旧工作流
  3. 将独立的 commit-lint job 合并为 android-build job 的一个步骤，节省 runner 启动时间
- **验证**: diff 确认配置正确，concurrency 的 group 使用 `${{ github.workflow }}-${{ github.ref }}` 合理
- **建议**: 无。改进合理。

### P6: 9d99a57 测试修正正确 [已验证]
- **提交**: `9d99a57` - fix(test): 修正 N86 测试验证目标为 discardConnection 而非 mockSocket.close()
- **文件**: `VpnServiceTest.kt`
- **描述**: 修正 N86 测试中：
  1. 将 verify mockSocket.close() 改为 verify mockPool.discardConnection()
  2. 为 test3 添加 virtualIpAllocator mock 防止 UninitializedPropertyAccessException
  3. 为 test1 添加 vpnOutputStream mock 和 lastActivity 断言
  4. 提取 createSessionWithLastActivity 辅助方法
- **验证**: 代码确认测试目标已修正，辅助方法已提取
- **问题**: 测试仍直接实例化 GatewayVpnService（H15 问题），但这是既有问题，不在本次修复范围。

### P7: a1c463c 合并提交包含重复文件变更 [已识别]
- **提交**: `a1c463c` - Merge branch 'fix/p8-n27-pool-lock-close' into dev
- **描述**: 该合并提交将 fix/p8-n27-pool-lock-close 分支（含 4a1eb2c、4ab337d、b0a86f5、9d99a57）合并到 dev。由于 4a1eb2c 与 cc9ab45 内容重复，合并后 dev 分支的历史包含了两份几乎相同的变更。
- **验证**: git show --stat a1c463c 显示修改了 13 个文件，与 4a1eb2c + 4ab337d + b0a86f5 + 9d99a57 的累积变更一致
- **建议**: 合并前应先 rebase 或检查目标分支是否已包含相同变更，避免重复历史。

### P8: 1fcc430 合并冲突解决正确 [已验证]
- **提交**: `1fcc430` - Merge remote-tracking branch 'origin/dev' into dev
- **描述**: 合并 origin/dev 到本地 dev 时，Socks5ConnectionPool.kt 存在冲突（本地有 discardConnection()，origin/dev 有 cc9ab45 的 close-outside-lock 模式）。冲突解决正确保留了两者的变更：discardConnection() 方法 + close()  outside write lock 模式。
- **验证**: 当前代码确认两者均存在且正确
- **建议**: 无。冲突解决正确。

### P9: 93fccd9 恢复域名请求支持正确 [已验证]
- **提交**: `93fccd9` - fix(socks5): 恢复域名请求支持（REVIEW_FINDINGS P2）
- **文件**: `Socks5ProxyHandler.kt`, `Socks5ProxyHandlerTest.kt`
- **描述**: 修复了 P2 中发现的 SOCKS5 域名功能退化问题。validateTargetAddress 不再直接拒绝域名，而是接受域名并将 DNS 解析委托给 Netty 异步处理。保持 IPv4/IPv6 私有地址验证不变。新增 2 个域名相关单元测试。
- **验证**: 代码确认域名通过 validateTargetAddress 后，由 Netty Bootstrap.connect 进行异步解析。测试新增 domain_callsConnectorAndReturnsSuccess 和 domainWithUpstreamFailure_returnsHostUnreachable。
- **风险**: H4（DNS 重绑定攻击）风险仍然存在。验证时进行 DNS 解析，但实际连接时 Netty 会再次解析，攻击者可能控制 DNS 服务器在两次解析间返回不同 IP。
- **建议**: 短期接受此修复恢复功能；中长期需按 H4 建议实施缓存解析结果或 DoH。

### P10: cleanupVpnResources 仍使用 close() 而非 discardConnection() [待修复]
- **位置**: `VpnService.kt` L1138-L1145
- **描述**: N86 修复将大部分场景的 close() 替换为 discardConnection()，但 cleanupVpnResources() 中仍使用 `session.pooledConnection?.close()`。虽然 shutdown() 会清理所有连接，但 close() 不会立即从 allConnections 中移除，可能导致 30 秒内 totalConnections 虚高。
- **验证**: 代码确认 L1141 仍为 close() 而非 discardConnection()
- **风险**: 低。VPN 停止后连接池即将 shutdown，影响时间窗口极短。
- **建议**: 统一使用 discardConnection()，保持代码一致性。

### P11: N86 在 ISSUES.md 中状态未及时更新 [待修复]
- **位置**: `docs/ISSUES.md`
- **描述**: N86 修复已合并到 dev，但 ISSUES.md 中 N86 条目标记为"已修复"，缺少修复提交哈希。根据文档规范，应记录修复提交以便追溯。
- **验证**: ISSUES.md N86 条目无"修复提交"字段
- **建议**: 补充修复提交列表：`4ab337d` (WIP), `b0a86f5` (discardConnection), `9d99a57` (测试修正), `a1c463c` (合并到 dev)

### P12: VpnServiceTest 仍直接实例化 GatewayVpnService（H15 遗留） [待修复]
- **位置**: `VpnServiceTest.kt` L1697, L1718, L1752, L1793, L1796, L1845, L1879
- **描述**: 测试代码直接实例化 `GatewayVpnService()`，违反 Android 组件生命周期规范。虽然这是 H15 记录的既有问题，但本次新增的 N86 测试（n86_cleanupStaleConnections_expiredSession 等）延续了此模式。
- **验证**: 代码确认新增测试仍使用 `val service = GatewayVpnService()`
- **风险**: 测试不可靠，与实际运行时不一致。
- **建议**: 新测试应避免延续此反模式。可考虑使用 Robolectric 或将测试逻辑下沉为纯 Kotlin 组件。

---

## 新问题记录

### P13: Socks5ConnectionPool.discardConnection() 关闭 socket 在锁外，但异常时计数可能不一致
- **位置**: `Socks5ConnectionPool.kt` L245-L252
- **描述**: discardConnection() 在 write 锁内移除 availableConnections 和 allConnections 并递减计数，然后在锁外调用 connection.close()。如果 close() 抛出异常（极罕见），连接已被移除但 socket 未关闭，可能导致 FD 泄漏。但 close() 的 try-catch 在 PooledSocks5Connection.close() 中已处理，此场景风险极低。
- **风险**: 极低
- **建议**: 无需修复。PooledSocks5Connection.close() 已捕获异常。

### P14: 合并提交 1fcc430 的提交信息未说明冲突文件
- **提交**: `1fcc430`
- **描述**: 合并提交信息提到"Resolve conflict in Socks5ConnectionPool.kt"，但未说明具体冲突内容和解决策略。虽然当前代码正确，但历史追溯时无法快速了解冲突细节。
- **风险**: 低
- **建议**: 合并冲突时应在提交信息中简要说明冲突内容和解决策略。

---

## 总结

本次审查的 11 个提交（f29d9b2 .. 93fccd9）整体质量良好，核心修复（N86、P8/N27、WebSocket 泄漏、域名支持恢复）均正确实现。主要问题：

1. **重复提交**: cc9ab45 和 4a1eb2c 实质相同，导致合并历史不干净
2. **WIP 提交混入**: 4ab337d 作为 WIP 直接合并，后续 b0a86f5 才修复其缺陷
3. **代码一致性**: cleanupVpnResources 中仍使用 close() 而非 discardConnection()
4. **测试债务**: 新测试延续直接实例化 Android Service 的反模式

**构建验证**: `make android-test` 通过（40 tasks up-to-date）。

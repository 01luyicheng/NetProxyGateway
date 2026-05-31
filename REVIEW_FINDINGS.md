# Dev分支最近10次提交审查发现

审查日期: 2026-05-30
审查范围: dev分支最近10次提交 (a7e17e5 .. f5dfde4)

---

## 验证状态汇总

| 问题 | 描述 | 验证结果 |
|------|------|----------|
| P1 | 提交8aa69d0误提交大文件pr11_full.diff | 已验证，真实存在 |
| P2 | 提交1930572引入SOCKS5域名功能退化 | 已验证，真实存在 |
| P3 | 提交7459a73是无意义测试提交 | 已验证，真实存在 |
| P4 | N34修复提交哈希错误 | 已验证，真实存在 |
| P5 | N85状态错误 | 已验证，真实存在 |
| P6 | N82修复提交记录不完整 | 已验证，真实存在 |
| P7 | H12残余竞态 | 已验证，真实存在 |
| P8 | N27残余锁内close | 已验证，真实存在 |
| P9 | 重复提交 | 已验证，不成立 |
| P10 | connectionLost回调遗留竞态 | 已验证，真实存在 |

---

## 问题列表

### P1: 提交8aa69d0误提交大文件pr11_full.diff
- **提交**: `8aa69d0` - fix(tunnel): address review feedback for TOCTOU race fix
- **文件**: `pr11_full.diff` (新增6288行，约249KB)
- **描述**: 该文件是某个PR（PR #11）的完整diff导出，属于临时工作文件，与提交目标（修复TOCTOU race的审查反馈）完全无关。污染git历史，永久增加仓库体积。
- **验证**: 文件存在于工作区，git show确认其为git diff格式，包含大量无关变更
- **建议**: 从dev分支移除该文件，考虑将`*.diff`添加到`.gitignore`

### P2: 提交1930572引入SOCKS5域名功能退化
- **提交**: `1930572` - fix: avoid blocking DNS lookup in SOCKS5 validateTargetAddress
- **文件**: `android/app/src/main/java/com/netproxy/gateway/proxy/Socks5ProxyHandler.kt`
- **描述**: 将`InetAddress.getByName()`替换为纯IP格式验证后，`validateTargetAddress()`只接受IPv4/IPv6格式，所有域名请求（如`example.com`）被直接拒绝。SOCKS5协议标准支持域名类型（ATYP=0x03），当前实现违反协议标准。测试文件中没有任何域名类型的CONNECT测试用例。
- **验证**: 代码确认`return false`在IPv4校验失败后执行；测试确认无域名用例
- **建议**: 区分地址类型处理（IPv4/IPv6/DOMAINNAME），或将域名解析延迟到连接阶段；补充域名测试用例

### P3: 提交7459a73是无意义测试提交
- **提交**: `7459a73` - Add comment for write permissions test
- **作者**: bytecategory <nettopology@proton.me>
- **文件**: `server/socks5-proxy/main.go`
- **描述**: 在import块和SOCKS5协议常量块之间添加一行注释`// Test for write permissions.`，与下方代码完全无关。属于外部协作者测试写入权限的提交。
- **验证**: git show确认仅添加1行注释；当前代码中该注释仍存在
- **建议**: 删除该无意义注释

### P4: ISSUES.md文档记录错误 - N34修复提交哈希错误
- **文档位置**: docs/ISSUES.md N34
- **文档记录**: 修复提交 `a188bb6`
- **实际情况**: 提交历史中不存在`a188bb6`。实际修复提交是`2f89166`（fix: sync UI VPN state with actual service state），修改了MainViewModel.kt
- **验证**: git log确认`a188bb6`不存在；`2f89166`是实际修复提交
- **建议**: 将N34修复提交更正为`2f89166`

### P5: ISSUES.md文档记录错误 - N85状态错误
- **文档位置**: docs/ISSUES.md N85
- **文档记录**: 状态"已修复"，修复提交`9efb84e`
- **实际情况**: `9efb84e`是5月27日的文档同步提交，修改的是H5、H8、N2、N56的状态同步，与N85描述的"文档格式不一致"完全无关。N85尚未修复。
- **验证**: git show 9efb84e --stat确认修改内容与N85无关
- **建议**: 将N85状态改为"待修复"，删除错误的提交哈希`9efb84e`

### P6: ISSUES.md文档记录不完整 - N82修复提交遗漏
- **文档位置**: docs/ISSUES.md N82
- **文档记录**: 仅记录修复提交`8aa69d0`（添加stopMu + stopped标志）
- **实际情况**: 主要修复提交是`f9a628d`（fix(tunnel): fix TOCTOU race in notifyDeviceStatus and cleanupDeadTunnels），该提交在`notifyDeviceStatus`入口添加了`stopMu`和`stopped`标志。`8aa69d0`只是对`f9a628d`的审查反馈处理（添加注释、移除冗余nil检查、在cleanupDeadTunnelsOnce中添加stopped检查）。
- **验证**: git log确认`f9a628d`是主要修复提交；`8aa69d0`在其之后，是审查改进
- **建议**: 补充记录主要修复提交`f9a628d`，`8aa69d0`作为审查改进提交保留

### P7: H12残余竞态 - cleanupStaleConnections与forwardViaSocks5跨方法竞态
- **相关提交**: `41e7468` - fix: make activeConnections access atomic
- **文件**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt`
- **描述**: `forwardViaSocks5`已使用`computeIfPresent()`原子操作，但`cleanupStaleConnections`仍使用非原子的`removeIf`。竞态场景：cleanup的removeIf判定连接过期并准备移除，同时forwardViaSocks5的computeIfPresent看到同一entry仍存在但isValid()返回false，调用pool.returnConnection()归还；随后cleanup也调用returnConnection()，导致同一连接被归还两次。连接池的`allConnections.containsKey()`检查无法防止连接被重复放入available队列。
- **验证**: 代码确认cleanupStaleConnections使用removeIf；竞态场景分析成立
- **建议**: 将`cleanupStaleConnections`改为`computeIfPresent`或`compute`模式，与`forwardViaSocks5`统一

### P8: N27残余问题 - returnConnection和borrowConnection仍有锁内close
- **相关提交**: `f5dfde4` - fix: move socket.close() out of write lock
- **文件**: `android/app/src/main/java/com/netproxy/gateway/proxy/Socks5ConnectionPool.kt`
- **描述**: `cleanupIdleConnections`已将close移出write锁（先锁内收集+移除，再锁外close），但`returnConnection()`在L194、L200、L205（通过removeConnection）、L223（通过removeConnection）仍在write锁内close。`borrowConnection()`在L167也在write锁内close。`removeConnection()`辅助方法本身在调用者持有的锁内执行close。
- **验证**: 代码确认returnConnection和borrowConnection中仍有锁内close
- **建议**: 统一采用cleanupIdleConnections的两阶段模式（锁内集合操作+锁外close）

### P9: 重复提交
- **初始假设**: `b18d832`和`1930572`是完全相同的提交；`b2256ff`和`41e7468`是完全相同的提交
- **验证结果**: 不成立。git diff显示这些提交之间存在差异（主要是CLAUDE.md中Root检测行的差异）。它们不是完全相同的重复提交，而是不同的提交对各自做了类似的修改。
- **结论**: 问题不成立，从关注列表中移除

### P10: connectionLost回调遗留竞态（低优先级）
- **文件**: `android/app/src/main/java/com/netproxy/gateway/connection/MqttConnectionManager.kt`
- **描述**: `connectionLost`回调运行在Paho内部线程中。场景：网络断开导致Paho触发connectionLost，回调通过开头的shouldStayConnected检查后，用户同时调用disconnect()。disconnect()设置shouldStayConnected=false和_connectionState=Disconnected，但connectionLost继续执行设置_connectionState=Error，导致Error状态覆盖Disconnected。两个线程对`_connectionState`的写操作没有任何同步保护。
- **验证**: 代码确认connectionLost在Paho线程执行，disconnect在调用者线程执行，两者无同步保护
- **建议**: 在connectionLost中设置_errorState前增加二次shouldStayConnected检查，或将_connectionState修改纳入synchronized保护

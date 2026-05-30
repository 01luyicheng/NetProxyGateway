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
| P9 | 重复提交 | 待验证 |
| P10 | connectionLost回调遗留竞态 | 待验证 |

---

## 问题列表

### P1: 提交8aa69d0误提交大文件pr11_full.diff
- **提交**: `8aa69d0` - fix(tunnel): address review feedback for TOCTOU race fix
- **文件**: `pr11_full.diff` (新增6288行)
- **描述**: 该文件是某个PR的完整diff，属于临时工作文件，与提交目标无关。污染git历史，增加仓库体积。
- **建议**: 从dev分支移除该文件

### P2: 提交1930572引入SOCKS5域名功能退化
- **提交**: `1930572` - fix: avoid blocking DNS lookup in SOCKS5 validateTargetAddress
- **文件**: `android/app/src/main/java/com/netproxy/gateway/proxy/Socks5ProxyHandler.kt`
- **描述**: 将`InetAddress.getByName()`替换为纯IP格式验证(`IpAddressUtils.validateIpv4WithResult()`)后，SOCKS5协议允许的域名请求被完全拒绝。这违反了SOCKS5协议标准。
- **建议**: 区分地址类型处理(IPv4/IPv6/DOMAINNAME)，或明确文档化此限制

### P3: 提交7459a73是无意义测试提交
- **提交**: `7459a73` - Add comment for write permissions test
- **作者**: bytecategory <nettopology@proton.me>
- **文件**: `server/socks5-proxy/main.go`
- **描述**: 仅添加一行无关注释"// Test for write permissions."。属于测试写入权限的提交，不应进入代码库。
- **建议**: 回退该提交

### P4: ISSUES.md文档记录错误 - N34修复提交哈希错误
- **文档位置**: docs/ISSUES.md N34
- **文档记录**: 修复提交 `a188bb6`
- **实际情况**: 提交历史中不存在`a188bb6`，实际修复提交是`2f89166`
- **建议**: 将N34修复提交更正为`2f89166`

### P5: ISSUES.md文档记录错误 - N85状态错误
- **文档位置**: docs/ISSUES.md N85
- **文档记录**: 状态"已修复"，修复提交`9efb84e`
- **实际情况**: `9efb84e`是5月27日的文档同步提交，与N85无关。N85尚未修复。
- **建议**: 将N85状态改为"待修复"，删除错误的提交哈希

### P6: ISSUES.md文档记录不完整 - N82修复提交遗漏
- **文档位置**: docs/ISSUES.md N82
- **文档记录**: 仅记录修复提交`8aa69d0` (stopMu + stopped)
- **实际情况**: 主要修复在`f9a628d`（添加stopMu和stopped标志），`8aa69d0`只是审查反馈处理（添加注释、移除冗余检查）
- **建议**: 补充记录主要修复提交`f9a628d`

### P7: H12残余竞态 - cleanupStaleConnections与forwardViaSocks5跨方法竞态
- **相关提交**: `41e7468` - fix: make activeConnections access atomic
- **文件**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt`
- **描述**: `forwardViaSocks5`已使用`computeIfPresent()`原子操作，但`cleanupStaleConnections`仍使用非原子的`removeIf`。两者之间的竞态可能导致连接重复归还到连接池。
- **建议**: 将`cleanupStaleConnections`也改为`computeIfPresent`或`compute`模式

### P8: N27残余问题 - returnConnection和borrowConnection仍有锁内close
- **相关提交**: `f5dfde4` - fix: move socket.close() out of write lock
- **文件**: `android/app/src/main/java/com/netproxy/gateway/proxy/Socks5ConnectionPool.kt`
- **描述**: `cleanupIdleConnections`的锁内close已修复，但`returnConnection()`(L205, L223)和`borrowConnection()`(L167)中仍在write锁内调用`removeConnection()`或`close()`。
- **建议**: 统一将所有close操作移出write锁

### P9: 重复提交
- **发现**: `b18d832`和`1930572`是完全相同的提交；`b2256ff`和`41e7468`是完全相同的提交
- **影响**: 可能是cherry-pick或rebase导致，不引入功能问题但增加历史噪音

### P10: connectionLost回调遗留竞态（低优先级）
- **文件**: `android/app/src/main/java/com/netproxy/gateway/connection/MqttConnectionManager.kt`
- **描述**: `connectionLost`回调中`_connectionState.value = Error(...)`与`disconnect()`之间缺乏同步保护，可能短暂覆盖`Disconnected`状态。
- **建议**: 记录为低优先级问题

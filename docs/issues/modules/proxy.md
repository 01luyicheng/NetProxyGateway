# 代理模块问题详情

> 模块路径: `android/app/src/main/java/com/netproxy/gateway/proxy/`
> 最后验证: commit f47d3f6

## 模块文件清单

| 文件 | 职责 | 行数 |
|------|------|------|
| Socks5ProxyService.kt | SOCKS5代理前台服务：Netty服务器生命周期管理 | 213 |
| Socks5ProxyHandler.kt | Netty ChannelHandler：SOCKS5协议握手、认证、CONNECT处理 | 303 |
| Socks5ConnectionPool.kt | SOCKS5连接池：连接复用、空闲清理、统计 | 532 |

## 跨文件依赖

### 调用方（谁调用了代理模块）

| 调用方 | 被调用目标 | 交互方式 | 说明 |
|--------|-----------|----------|------|
| GatewayVpnService | Socks5ProxyService | `startForegroundService(Intent)` / `stopService(Intent)` | VpnService管理代理服务生命周期 |
| GatewayVpnService | Socks5ConnectionPool | 直接实例化 + `borrowConnection()` / `returnConnection()` | VPN流量转发使用连接池复用SOCKS5连接 |
| Android系统 | Socks5ProxyService | `Service` 生命周期回调 | `onCreate`, `onStartCommand`, `onDestroy` |

### 依赖方（代理模块调用了谁）

| 被调用方 | 调用来源 | 交互方式 | 说明 |
|----------|----------|----------|------|
| AuthSessionStore | Socks5ProxyService, Socks5ConnectionPool | `@Inject` / `credentialProvider` lambda | 认证凭据验证与提供 |
| MainActivity | Socks5ProxyService | `PendingIntent.getActivity()` | 通知点击跳转 |
| AppLocale | Socks5ProxyService | 静态方法调用 | 通知国际化 |
| IpAddressUtils | Socks5ProxyHandler | 静态方法调用 | 目标地址IP范围验证 |
| Netty (io.netty) | Socks5ProxyService, Socks5ProxyHandler | 直接API调用 | NioEventLoopGroup, Bootstrap, ChannelPipeline等 |

### 模块内部依赖

```
Socks5ProxyService --> Socks5ProxyHandler (通过ChannelInitializer注册)
Socks5ProxyHandler --> OutboundConnector / NettyOutboundConnector
Socks5ProxyHandler --> RelayHandler (双向流量中继)
GatewayVpnService --> Socks5ConnectionPool (直接实例化)
Socks5ConnectionPool --> AuthSessionStore (通过credentialProvider lambda)
```

## 活跃问题清单

### High 严重程度

#### H4: SOCKS5代理DNS重绑定攻击风险
- **位置**: Socks5ProxyHandler.kt `validateTargetAddress()` (L107-131)
- **代码指纹**: Socks5ProxyHandler/validateTargetAddress/DNS解析
- **问题**: 代码已有IP范围验证（拒绝回环、链路本地、广播、保留地址，仅允许RFC1918私有地址），但DNS重绑定风险仍然存在。攻击者可能通过快速切换DNS记录绕过IP验证窗口
- **风险**: 攻击者可能通过DNS重绑定绕过IP验证，访问内网资源
- **修复建议**:
  1. 使用DNS缓存并验证解析结果
  2. 检查解析后的IP是否与目标域名匹配
  3. 考虑使用DNS-over-HTTPS (DoH)

#### H5: 连接池清理竞争条件（残余）
- **位置**: Socks5ConnectionPool.kt `cleanupIdleConnections()`, `borrowConnection()`
- **代码指纹**: Socks5ConnectionPool/cleanupIdleConnections/write锁内阻塞IO
- **问题**: read锁收集、write锁清理之间连接状态可能变化。`cleanupIdleConnections` 在单次 `write` 锁内完成筛选与移除；`borrowConnection` 在读锁外收集无效连接后，于 `write` 锁内二次校验
- **残余风险**: 写锁内 `removeConnection`/`close()` 仍可能阻塞（N27）
- **修复提交**: b1e18bd（已缓解，非完全消除）

#### N27: Socks5ConnectionPool cleanupIdleConnections在write锁内执行阻塞IO
- **位置**: Socks5ConnectionPool.kt `cleanupIdleConnections()` (L435-L457)
- **代码指纹**: Socks5ConnectionPool/removeConnection/close阻塞
- **问题**: `removeConnection(conn)` 在 `write` 锁内被调用，内部执行 `connection.close()` 阻塞IO操作。在高并发或网络异常时，长时间持有 `write` 锁会阻塞所有 `borrowConnection` 和 `returnConnection` 操作
- **风险**: 严重影响连接池并发性能，可能导致连接获取超时
- **修复难度**: 中。需要将 `socket.close()` 移出锁范围，改为异步关闭或在锁外执行

#### N28: Socks5ProxyHandler RelayHandler释放语义优化引入新问题
- **位置**: Socks5ProxyHandler.kt `RelayHandler` (L283-L285)
- **代码指纹**: Socks5ProxyHandler/RelayHandler/safeRelease
- **问题**: 将 `ReferenceCountUtil.release(msg)` 改为 `ReferenceCountUtil.safeRelease(msg)`，意图避免双重释放。但Netty的 `ChannelOutboundBuffer.remove()` 在write失败时已自动释放msg，`safeRelease` 只是吞掉异常，不能阻止对已经释放的池化ByteBuf进行操作
- **风险**: 池化ByteBuf被重复释放后可能归还到对象池，再次分配时获取到脏数据，导致数据损坏或崩溃
- **修复难度**: 低。回滚该修改，恢复原始不释放逻辑
- **关联**: N45（已修复的double-free问题）
- **修复状态**: 待修复

#### N30: Socks5ProxyHandler DNS解析阻塞EventLoop
- **位置**: Socks5ProxyHandler.kt `validateTargetAddress()` (L111-L128)
- **代码指纹**: Socks5ProxyHandler/InetAddress.getByName/同步阻塞
- **问题**: `InetAddress.getByName(host)` 是同步阻塞调用，在Netty EventLoop线程上执行。DNS查询可能耗时数百毫秒甚至超时（数秒），期间阻塞该EventLoop上的所有I/O事件
- **风险**: 单连接慢DNS查询导致整个SOCKS5服务所有连接停滞
- **修复难度**: 中。需要引入异步DNS解析或使用线程池执行DNS查询

### Medium 严重程度

#### C36: Socks5ConnectionPool borrowConnection连接追踪泄漏
- **位置**: Socks5ConnectionPool.kt `borrowConnection()` (L137-L148)
- **代码指纹**: Socks5ConnectionPool/borrowConnection/异常泄漏
- **问题**: `queue.poll()` 取出无效连接后，在 `write` 锁清理前若发生异常或线程中断，连接可能既不在队列也不在 `allConnections` 中
- **风险**: socket可能未关闭且未被追踪
- **修复难度**: 中

#### C37: Socks5ConnectionPool returnConnection O(n)性能瓶颈
- **位置**: Socks5ConnectionPool.kt `returnConnection()` (L205-L210)
- **代码指纹**: Socks5ConnectionPool/returnConnection/遍历计数
- **问题**: `returnConnection` 在 `write` 锁内遍历 `queue` 和 `allConnections` 计算连接数，O(n)复杂度
- **风险**: 高并发场景下连接归还性能下降
- **修复建议**: 维护每个目标地址的连接计数器，避免遍历

#### C38: Socks5ConnectionPool readFully无限循环风险
- **位置**: Socks5ConnectionPool.kt `readFully()` (L395-L420)
- **代码指纹**: Socks5ConnectionPool/readFully/持续返回0
- **问题**: `readFully` 中若 `input.read()` 持续返回0，while循环无限执行，CPU空转
- **风险**: 某些InputStream实现可能导致线程空转
- **修复难度**: 低。添加最大重试次数或超时检查

#### C39: Socks5ProxyHandler NettyOutboundConnector未设置连接超时
- **位置**: Socks5ProxyHandler.kt `NettyOutboundConnector.connect()` (L245-L270)
- **代码指纹**: Socks5ProxyHandler/Bootstrap/无CONNECT_TIMEOUT
- **问题**: `Bootstrap` 未设置 `ChannelOption.CONNECT_TIMEOUT_MILLIS` 或 `SO_TIMEOUT`
- **风险**: 上游服务器不可达时连接挂起
- **修复难度**: 低

#### C40: Socks5ProxyService closeFuture.sync阻塞协程
- **位置**: Socks5ProxyService.kt `startProxyServer()` (L114-L115)
- **代码指纹**: Socks5ProxyService/closeFuture.sync/协程泄漏
- **问题**: `serverChannel?.closeFuture()?.sync()` 阻塞协程线程，若 `closeFuture` 永远不触发，协程永远挂起
- **风险**: 异常路径下协程泄漏
- **修复难度**: 低。使用 `await()` 替代 `sync()` 或添加超时

#### C74: SOCKS5连接池缺少并发回归测试
- **位置**: Socks5ConnectionPoolTest.kt
- **代码指纹**: Socks5ConnectionPoolTest/单线程场景
- **问题**: 测试仅覆盖单线程场景。H5记录的"连接池清理竞争条件"是关键缺陷，但测试中没有并发借用/归还/清理的竞态测试
- **风险**: 关键缺陷缺乏回归保护
- **修复难度**: 中

#### C77: Socks5ProxyHandler double-free修复缺少write-failure回归测试
- **位置**: Socks5ProxyHandlerTest.kt
- **代码指纹**: Socks5ProxyHandlerTest/未覆盖write失败
- **问题**: N45的修复修改了 `RelayHandler` 的write-failure分支，但当前测试只覆盖认证和CONNECT流程，没有构造 `relayChannel.writeAndFlush(msg)` 失败的路径
- **风险**: 该修复点缺乏测试保护，未来容易被误改回双重释放
- **修复难度**: 低

### Low 严重程度

#### C16: 代码缺少适当分组和空行（Socks5ConnectionPool部分）
- **位置**: Socks5ConnectionPool.kt
- **代码指纹**: Socks5ConnectionPool/属性定义无分组
- **问题**: 逻辑块之间缺少空行，属性定义没有分组

#### C41: Socks5ProxyService shutdownGracefully无超时
- **位置**: Socks5ProxyService.kt `onDestroy()` (L206-L207)
- **代码指纹**: Socks5ProxyService/shutdownGracefully/默认无超时
- **问题**: `shutdownGracefully()` 默认无超时，若存在挂起连接可能长时间阻塞 `onDestroy()`
- **修复难度**: 低。添加超时参数

#### N8: 核心业务逻辑测试缺失（Proxy部分）
- **位置**: 测试目录
- **代码指纹**: 测试目录/forwardViaSocks5等无测试
- **问题**: `forwardViaSocks5()` 等核心业务逻辑缺乏测试
- **风险**: 回归风险大

#### N13: 已弃用API使用（Proxy部分）
- **位置**: Socks5ProxyService.kt
- **代码指纹**: 多处/NioEventLoopGroup
- **问题**: `NioEventLoopGroup` 等Netty API在Android环境下使用已弃用模式

## 已修复问题

| 编号 | 标题 | 修复提交 | 说明 |
|------|------|----------|------|
| M11 | 连接池状态检查与清理的竞态条件 | b1e18bd | write锁内二次校验 |
| N23 | 测试直接实例化Android Service | 已修复 | 使用ServiceController |
| N24 | Socks5ProxyService通知ID使用魔法数字 | 已修复 | 提取为NOTIFICATION_ID常量 |
| N45 | Socks5ProxyHandler double-free风险 | 9f4b1b9 | 恢复原始不释放逻辑 |
| N51 | server/socks5-proxy StreamConn.Read在关闭边界可能丢失已排队数据 | 已修复 | 双select优先消费 |
| N53 | server/socks5-proxy StreamConn.Read在小缓冲区下会直接截断数据 | 已修复 | 缓存未消费remainder |
| N55 | DebugDetector 4个方法正常完成路径未调用process.destroy() | 1ba8a50 | try-finally保证destroy |
| N63 | cleanupStream中streamConn.Close()在tc.mu锁内执行 | 已修复 | 解锁后再Close |
| N78 | readLoop defer中conn.Close()仍在tc.mu锁内执行 | f93054a | 解锁后再close |

## 关联模块

- [vpn.md](vpn.md) - VPN服务与流量转发
- [connection.md](connection.md) - MQTT连接、认证会话
- [cross-file/impact-graph.md](../cross-file/impact-graph.md) - 跨文件影响分析

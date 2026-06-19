# 连接模块问题详情

> 模块路径: `android/app/src/main/java/com/netproxy/gateway/connection/`
> 最后验证: commit f47d3f6

## 模块文件清单

| 文件 | 职责 | 行数 |
|------|------|------|
| MqttConnectionManager.kt | MQTT连接管理：连接、重连、心跳、发布订阅 | 696 |
| MqttTlsPinning.kt | MQTT TLS证书固定实现 | [待验证] |
| AuthSessionStore.kt | 认证会话持久化：EncryptedSharedPreferences + 内存缓存 | 206 |
| NetworkStateManager.kt | 网络状态监听：ConnectivityManager回调Flow | 145 |

## 跨文件依赖

### 调用方（谁调用了连接模块）

| 调用方 | 被调用目标 | 交互方式 | 说明 |
|--------|-----------|----------|------|
| MainViewModel | MqttConnectionManager | `@Inject` + `connect()` / `disconnect()` / `publish()` / `subscribe()` | UI层控制MQTT连接 |
| MainViewModel | NetworkStateManager | `@Inject` + `networkState` Flow / `isCellularConnected()` | 观察网络状态，配对前检查蜂窝网络 |
| MainViewModel | AuthSessionStore | `@Inject` + `getOrCreateDeviceId()` / `update()` / `clear()` / `isValid()` | 设备ID管理与认证 |
| GatewayVpnService | AuthSessionStore | `@Inject` + `getCurrentSession()` | 获取SOCKS5认证凭据 |
| Socks5ProxyService | AuthSessionStore | `@Inject` + `isValid()` | SOCKS5代理认证验证 |
| Socks5ConnectionPool | AuthSessionStore | `credentialProvider` lambda | 获取SOCKS5连接认证凭据 |
| ModuleCoordinator | MqttConnectionManager | [待验证] 通过StateFlow观察 | 状态同步 |

### 依赖方（连接模块调用了谁）

| 被调用方 | 调用来源 | 交互方式 | 说明 |
|----------|----------|----------|------|
| Paho MQTT Client | MqttConnectionManager | 直接API调用 (`MqttClient`, `MqttConnectOptions`) | MQTT协议实现 |
| AppAuditLogStore | MqttConnectionManager | 静态方法调用 | 连接事件审计日志 |
| DebugSettingsStore | MqttConnectionManager | 静态方法调用 | 调试开关读取（跳过证书验证） |
| BuildConfig | MqttConnectionManager | 编译时常量 | MQTT配置（URL、TLS开关、证书固定） |
| EncryptedSharedPreferences | AuthSessionStore | 直接API调用 | 敏感数据加密存储 |
| ConnectivityManager | NetworkStateManager | 系统API + NetworkCallback | 网络状态监听 |

## 活跃问题清单

### High 严重程度

#### H8: MQTT TLS证书固定配置可能为空
- **位置**: MqttConnectionManager.kt `createProductionSocketFactory()` (L131-138)
- **代码指纹**: MqttConnectionManager/createProductionSocketFactory/空配置回退
- **问题**: 当 `MQTT_TLS_PUBLIC_KEY_PINS` 为空时，仅记录警告，仍使用默认CA验证。Release构建虽有强制检查，但debug构建允许空配置
- **风险**: 生产环境可能意外使用不安全的证书验证方式
- **修复建议**:
  1. 生产环境强制要求配置证书固定
  2. 空配置时抛出异常而非仅警告
  3. 添加构建时检查确保配置正确

#### N37: AuthSessionStore CharArray安全设计被String抵消
- **位置**: AuthSessionStore.kt `loadSession()` (L183, L190)
- **代码指纹**: AuthSessionStore/loadSession/CharArray转String
- **问题**: `loadSession()` 将 `CharArray` 转为 `String` 返回，且 `ProxyAuthSession.authToken` 类型也是 `String`。`CharArray` 可清零的安全设计被完全绕过，敏感token以不可变String形式存在于内存
- **风险**: 安全设计意图失效，token无法被主动擦除
- **修复难度**: 中。将 `ProxyAuthSession.authToken` 类型改为 `CharArray`，在业务层传递时保持 `CharArray` 形式

### Medium 严重程度

#### N43: MqttConnectionManager MQTT回调无法注销导致内存泄漏
- **位置**: MqttConnectionManager.kt `subscribeWithResult()` (L570-588)
- **代码指纹**: MqttConnectionManager/subscribeWithResult/无unsubscribe
- **问题**: `subscribeWithResult` 注册回调到 `topicCallbacks` (`ConcurrentHashMap<String, CopyOnWriteArrayList<(String) -> Unit>>`)，但没有提供 `unsubscribe(topic, callback)` API。MQTT长连接期间，持有UI组件闭包的回调永久留存无法清理
- **风险**: MQTT长连接场景下可能导致Activity/ViewModel内存泄漏
- **修复难度**: 中。添加 `unsubscribe(topic, callback)` 方法，支持精细化回调生命周期管理

#### C4: 内存中敏感数据处理不当
- **位置**: AuthSessionStore.kt `loadSession()`
- **代码指纹**: AuthSessionStore/loadSession/String转换
- **问题**: 同N37。内存中的token在 `loadSession()` 中转换为String，失去CharArray清零能力
- **修复建议**: 避免将CharArray转换为String，保持CharArray形式传递

#### C42: MqttConnectionManager subscribe回调可重复注册
- **位置**: MqttConnectionManager.kt `subscribeWithResult()` (L570-588)
- **代码指纹**: MqttConnectionManager/subscribeWithResult/无去重
- **问题**: `subscribeWithResult` 将callback添加到 `CopyOnWriteArrayList` 前不做去重，同一回调可被重复注册导致重复触发
- **风险**: 消息重复处理或UI状态异常抖动
- **修复难度**: 低。添加去重检查或Set结构

#### C43: MqttConnectionManager startHeartbeat并发启动风险
- **位置**: MqttConnectionManager.kt `startHeartbeat()` (L493-542)
- **代码指纹**: MqttConnectionManager/startHeartbeat/cancel非原子
- **问题**: `startHeartbeat` 先 `cancel()` 再赋值新Job，非原子操作，旧Job尚未完成取消时新Job已启动，短暂双心跳并行
- **风险**: 心跳频率翻倍，增加网络负载
- **修复难度**: 低。使用原子操作或Mutex保护Job赋值

#### C44: NetworkStateManager onAvailable瞬态未验证状态
- **位置**: NetworkStateManager.kt `onAvailable()` (L38-40)
- **代码指纹**: NetworkStateManager/onAvailable/VALIDATED前触发
- **问题**: `onAvailable` 可能在 `onCapabilitiesChanged`（携带 `VALIDATED`）之前触发，下游可能收到 `isValidated=false` 瞬态并做出错误决策
- **风险**: MQTT连接决策可能基于未验证的网络状态
- **修复难度**: 中。延迟发射或过滤瞬态状态

#### C45: AuthSessionStore锁内执行加密磁盘IO可能ANR
- **位置**: AuthSessionStore.kt `update()` (L57-68)
- **代码指纹**: AuthSessionStore/@Synchronized/EncryptedSharedPreferences
- **问题**: `update()` 在 `@Synchronized` 锁内执行 `EncryptedSharedPreferences` 读写，涉及MasterKey解密和AES-GCM，主线程调用可能导致ANR
- **风险**: UI线程调用时可能触发ANR
- **修复难度**: 中。将加密IO移到后台线程

#### N8: 核心业务逻辑测试缺失（Connection部分）
- **位置**: 测试目录
- **代码指纹**: 测试目录/startHeartbeat等无测试
- **问题**: `startHeartbeat()` 等核心业务逻辑缺乏测试
- **风险**: 回归风险大

#### N10: 监控指标缺失
- **位置**: 全局
- **代码指纹**: 全局/无性能指标
- **问题**: 没有性能指标收集（连接建立时间、流量统计等），没有健康检查端点，没有错误上报机制
- **风险**: 难以发现和诊断线上问题

#### N15: Paho MQTT维护不活跃
- **位置**: android/app/build.gradle.kts
- **代码指纹**: android/app/build.gradle/维护不活跃
- **问题**: Eclipse Paho MQTT项目维护不活跃
- **风险**: 新功能和bug修复可能延迟
- **修复建议**: 评估迁移到HiveMQ MQTT Client或KMQTT

### Low 严重程度

#### L2: TODO注释未处理
- **位置**: MqttConnectionManager.kt (L458)
- **代码指纹**: MqttConnectionManager/TODO注释
- **问题**: 存在未处理的TODO注释，涉及安全配置
- **修复建议**: 处理TODO或创建正式issue跟踪

#### C66: AuthSessionStore constantTimeEquals空指针风险
- **位置**: AuthSessionStore.kt `constantTimeEquals()` (L193-200)
- **代码指纹**: AuthSessionStore/constantTimeEquals/无null检查
- **问题**: `constantTimeEquals` 未对参数做null检查，未来调用方传入null会NPE
- **修复难度**: 低。添加null检查

#### C69: MqttTlsPinning每次创建新MessageDigest
- **位置**: MqttTlsPinning.kt `createPinningTrustManager()` (L68-71)
- **代码指纹**: MqttTlsPinning/createPinningTrustManager/SHA-256
- **问题**: 每次TLS握手都创建新 `MessageDigest.getInstance("SHA-256")`，高频率重连时造成GC压力
- **修复难度**: 低。缓存MessageDigest实例或使用线程本地存储

#### N1: 双版本API增加维护负担（Connection部分）
- **位置**: AuthSessionStore.kt
- **代码指纹**: GatewayWifiManager,AuthSessionStore/双版本
- **问题**: 每个主要操作都有两个版本（如 `update()` 和 `updateWithResult()`），维护成本翻倍
- **修复建议**: 统一使用 `AppResult` 模式，移除静默失败版本

#### N3: 过度使用@Synchronized
- **位置**: AuthSessionStore.kt
- **代码指纹**: AuthSessionStore/全方法同步
- **问题**: 所有方法都标记 `@Synchronized`，即使只是读取操作；使用类实例作为锁，粒度太粗
- **风险**: 可能影响并发性能
- **修复建议**: 使用 `ReentrantReadWriteLock` 区分读写锁，或使用 `ConcurrentHashMap` 等并发集合

#### N5: 状态管理分散
- **位置**: 多处
- **代码指纹**: 多处/VpnState,MqttConnectionState,WiFiState
- **问题**: VPN状态多处定义 - `VpnState` 在 `VpnService.kt`，`MqttConnectionState` 在 `MqttConnectionManager.kt`，`WiFiState` 在 `ModuleCoordinator.kt`
- **修复建议**: 统一状态定义到 `result` 包或专门的状态管理模块

#### N59: M13修复后首次连接失败的首轮重连延迟变为10秒
- **位置**: MqttConnectionManager.kt `connect()` 的catch路径、`onReconnectAttemptFailed()`
- **代码指纹**: MqttConnectionManager/onReconnectAttemptFailed/首次倍增
- **问题**: `onReconnectAttemptFailed()` 在用户首次 `connect()` 失败时也会执行，将 `reconnectDelay` 从5s倍增为10s后再 `scheduleReconnect`。旧逻辑首次重连仍为5s
- **风险**: 仅影响首次连接失败场景的重连等待时间
- **修复建议**: 仅在「已由scheduleReconnect触发过的重连尝试失败」时递增

## 已修复问题

| 编号 | 标题 | 修复提交 | 说明 |
|------|------|----------|------|
| C1 | SSL信任所有证书配置风险 | 运行时防护已存在 | DEBUG=false且MQTT_TRUST_ALL_CERTS=true时抛异常 |
| M1 | 边界条件：IP地址解析验证 | 21ffa4c | 前置validateIpv4WithResult验证 |
| M3 | 安全检测命令执行未超时 | e89e00d | waitFor(3, TimeUnit.SECONDS) |
| N11 | 硬编码默认值不安全 | 21ffa4c | release构建强制要求配置MQTT地址 |
| N21 | 配对码输入状态配置变更丢失 | 已修复 | remember改为rememberSaveable |
| N22 | MainViewModel状态更新竞争条件 | 已修复 | _uiState.update{}原子操作 |
| N25 | MainViewModel状态更新方式不一致 | 已修复 | 统一使用update{} |
| N31 | NetworkStateManager onLost多网络状态误判 | 1f9acee | activeNetworks ConcurrentHashMap维护多网络 |
| N35 | MqttConnectionManager connect阻塞Default调度器 | 1f9acee | 误报，使用@ApplicationScope(Dispatchers.IO) |
| N39 | server/api JWT Secret长度未验证 | 202bb95 | MinJWTSecretLength=32 |
| N40 | server/socks5-proxy GetOrConnectTunnel连接存活检查竞态 | 1f9acee | RLock改为Lock |
| N44 | VirtualIpAllocator floorMod边界偏移 | 2ca17ee | floorMod(ipNum - START_IP, ...) |
| N46 | DebugDetector语义隐晦代码 | 165c272 | 统一显式false |
| N55 | DebugDetector 4个方法正常完成路径未调用process.destroy() | 1ba8a50 | try-finally保证destroy |
| N60 | N47测试修复中runTest未共享testScope的调度器 | 299d6da | testScope.runTest |
| N64 | MqttConnectionManager.disconnect()在synchronized块内修改StateFlow | 3c3784f | 误报，StateFlow线程安全 |
| N65 | MqttConnectionManager.connectionLost在非协程线程直接修改StateFlow | 3c3784f | 误报，StateFlow设计允许任意线程发布 |
| N67 | RootDetector.checkMagiskProps()严重误报 | 已修复 | 移除错误属性 |
| C23 | notifyStatusBackoff位移溢出风险 | d06f584 | 上限保护maxAttempt=30 |
| C24 | http.Client未复用连接池 | d06f584 | TunnelManager字段复用 |
| C25 | notifyDeviceStatus goroutine泄漏风险 | d06f584 | ctx.Done()监听 |
| C27 | notifyDeviceStatus测试覆盖不足 | d06f584 | 补充3个测试 |

## 关联模块

- [vpn.md](vpn.md) - VPN服务与流量转发
- [proxy.md](proxy.md) - SOCKS5代理与连接池
- [cross-file/impact-graph.md](../cross-file/impact-graph.md) - 跨文件影响分析

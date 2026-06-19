# 跨文件影响分析

> 最后验证: commit f47d3f6
> 生成日期: 2026-05-26
> 生成模型: SOLO

## 模块依赖图

```
[Android系统] --> [MainActivity] (生命周期管理)
[Android系统] --> [GatewayVpnService] (VpnService生命周期)
[Android系统] --> [Socks5ProxyService] (Service生命周期)

[MainActivity] --> [MainScreen] (Compose setContent)
[MainActivity] --> [AppLocale] (attachBaseContext语言包装)

[MainScreen] --> [MainViewModel] (hiltViewModel)
[MainScreen] --> [AppAuditLogStore] (日志记录)
[MainScreen] --> [DebugSettingsStore] (调试设置)
[MainScreen] --> [AppLocale] (语言切换)

[MainViewModel] --> [GatewayVpnService] (startForegroundService/startService + status StateFlow)
[MainViewModel] --> [MqttConnectionManager] (connect/disconnect + connectionState/diagnostics Flow)
[MainViewModel] --> [NetworkStateManager] (networkState Flow + isCellularConnected)
[MainViewModel] --> [GatewayWifiManager] (startScan/wifiScanResults + getCurrentConnection)
[MainViewModel] --> [AuthSessionStore] (getOrCreateDeviceId/update/clear/isValid)

[GatewayVpnService] --> [Socks5ProxyService] (startForegroundService/stopService)
[GatewayVpnService] --> [Socks5ConnectionPool] (直接实例化: borrowConnection/returnConnection/shutdown)
[GatewayVpnService] --> [AuthSessionStore] (@Inject + credentialProvider lambda)
[GatewayVpnService] --> [VirtualIpAllocator] (@Inject + getOrAllocateVirtualIp)
[GatewayVpnService] --> [IpAddressUtils] (静态调用: isPrivateIpv4Rfc1918)
[GatewayVpnService] --> [VpnDnsConfig] (静态调用: shouldRouteDnsViaWifi)
[GatewayVpnService] --> [AppLocale] (静态调用: 通知国际化)
[GatewayVpnService] --> [MainActivity] (PendingIntent.getActivity)

[Socks5ProxyService] --> [Socks5ProxyHandler] (ChannelInitializer注册到Netty Pipeline)
[Socks5ProxyService] --> [AuthSessionStore] (@Inject + isValid)
[Socks5ProxyService] --> [AppLocale] (静态调用: 通知国际化)
[Socks5ProxyService] --> [MainActivity] (PendingIntent.getActivity)

[Socks5ProxyHandler] --> [NettyOutboundConnector] (Bootstrap连接上游)
[Socks5ProxyHandler] --> [RelayHandler] (双向流量中继)
[Socks5ProxyHandler] --> [IpAddressUtils] (静态调用: isPrivateIpv4Rfc1918)

[Socks5ConnectionPool] --> [AuthSessionStore] (credentialProvider lambda)

[MqttConnectionManager] --> [Paho MQTT Client] (MqttClient/MqttConnectOptions)
[MqttConnectionManager] --> [AppAuditLogStore] (静态调用: 审计日志)
[MqttConnectionManager] --> [DebugSettingsStore] (静态调用: 跳过证书验证开关)
[MqttConnectionManager] --> [BuildConfig] (编译时常量: MQTT配置)
[MqttConnectionManager] --> [MqttTlsPinning] (createPinningTrustManager)

[AuthSessionStore] --> [EncryptedSharedPreferences] (加密存储)
[AuthSessionStore] --> [MasterKey] (AES256_GCM密钥)

[NetworkStateManager] --> [ConnectivityManager] (系统API + NetworkCallback)

[GatewayWifiManager] --> [AndroidWifiManager] (系统API)
[GatewayWifiManager] --> [EncryptedSharedPreferences] (建议持久化)

[ModuleCoordinator] --> [AppEvent] (Event Bus)
[ModuleCoordinator] --> [ModuleInterfaces] (模块注册)
```

## 数据流图

```
用户操作 --> MainScreen --> MainViewModel
                              |
              +---------------+---------------+
              |               |               |
         [toggleVpn]    [pairWithCode]   [disconnect]
              |               |               |
              v               v               v
      GatewayVpnService  MqttConnectionManager  MqttConnectionManager
              |               |               |
              v               v               v
      Socks5ProxyService   MQTT Broker      AuthSessionStore.clear()
              |                               |
              v                               v
      Socks5ConnectionPool <----------- credentialProvider
              |
              v
      AuthSessionStore (getCurrentSession)
```

## 状态流图

```
GatewayVpnService.status (StateFlow<VpnStatus>)
    |
    +--> MainViewModel.observeVpnStatus()
        |
        +--> MainScreen.uiState.vpnDetailedStatus

MqttConnectionManager.connectionState (StateFlow<MqttConnectionState>)
    |
    +--> MainViewModel.observeMqttState()
        |
        +--> MainScreen.uiState.mqttState

MqttConnectionManager.diagnostics (StateFlow<MqttDiagnostics>)
    |
    +--> MainViewModel.observeMqttDiagnostics()
        |
        +--> MainScreen.uiState (heartbeatFailures, reconnectCount)

NetworkStateManager.networkState (Flow<NetworkState>)
    |
    +--> MainViewModel.observeNetworkState()
        |
        +--> MainScreen.uiState (wifiConnected, cellularConnected, networkIsValidated)
```

## 高风险变更点

以下修改需要强制跨文件审查：

### 1. VpnService 公共接口变更
- **影响范围**: MainActivity, MainViewModel, MainScreen, Socks5ProxyService, Socks5ConnectionPool, AuthSessionStore, VirtualIpAllocator
- **风险类型**: 调用方未同步更新、生命周期回调处理错误、异常处理不兼容
- **检查清单**:
  - [ ] `GatewayVpnService.status` StateFlow 类型变更时，所有收集器（MainViewModel）同步更新
  - [ ] `VpnStatus` 数据类字段增删时，MainScreen 的 DiagnosticsCard 同步更新
  - [ ] `startVpn()` / `stopVpn()` 触发条件变更时，MainViewModel.toggleVpn() 逻辑同步检查
  - [ ] `SOCKS5_PROXY_HOST` / `SOCKS5_PROXY_PORT` 常量变更时，Socks5ConnectionPool 和 Socks5ProxyService 配置同步
  - [ ] 生命周期回调（`onDestroy`, `onRevoke`）行为变更时，确认不引入资源泄漏

### 2. 连接池接口变更
- **影响范围**: GatewayVpnService, Socks5ConnectionPoolTest, AuthSessionStore
- **风险类型**: 调用方参数不匹配、连接泄漏、测试失效
- **检查清单**:
  - [ ] `borrowConnection()` 签名变更时，VpnService.forwardViaSocks5() 同步更新
  - [ ] `returnConnection()` 行为变更时，确认所有借用路径都有对应归还
  - [ ] `PooledSocks5Connection` 字段变更时，确认 `isValid()` / `close()` 语义一致
  - [ ] `Socks5ConnectionPoolConfig` 默认值变更时，确认 VpnService.initializeConnectionPool() 配置兼容
  - [ ] 新增/删除连接池统计指标时，测试同步更新

### 3. MQTT连接管理器接口变更
- **影响范围**: MainViewModel, MainScreen, ModuleCoordinator, AppAuditLogStore
- **风险类型**: UI状态不一致、回调泄漏、事件丢失
- **检查清单**:
  - [ ] `MqttConnectionState` 密封类分支变更时，MainViewModel.observeMqttState() 的 when 表达式同步更新
  - [ ] `subscribeWithResult()` 签名变更时，确认 callback 生命周期管理
  - [ ] `MqttDiagnostics` 字段变更时，MainScreen.DiagnosticsCard 同步更新
  - [ ] 连接/断开连接行为变更时，确认 `shouldStayConnected` 标志逻辑一致
  - [ ] 重连退避策略变更时，确认 `onReconnectAttemptFailed()` 和 `scheduleReconnect()` 协同

### 4. AuthSessionStore 接口变更
- **影响范围**: MainViewModel, GatewayVpnService, Socks5ProxyService, Socks5ConnectionPool
- **风险类型**: 认证失败、凭据泄漏、会话状态不一致
- **检查清单**:
  - [ ] `ProxyAuthSession` 字段类型变更（如 CharArray -> String）时，所有使用方同步
  - [ ] `isValid()` 行为变更时，Socks5ProxyHandler 的认证流程同步
  - [ ] `getCurrentSession()` 返回值语义变更时，VpnService.credentialProvider 和 Socks5ConnectionPool.credentialProvider 同步
  - [ ] 加密存储方案变更时，确认向后兼容性
  - [ ] 会话清除逻辑变更时，确认 MQTT 断开和 VPN 停止的时序

### 5. 网络状态管理器变更
- **影响范围**: MainViewModel, MainScreen, MqttConnectionManager
- **风险类型**: 网络判断错误、MQTT连接决策失误、UI状态不一致
- **检查清单**:
  - [ ] `NetworkState` 字段变更时，MainViewModel.observeNetworkState() 同步
  - [ ] `NetworkType` 枚举变更时，MainScreen.NetworkInfoCard 同步
  - [ ] 网络验证逻辑（`isValidNetwork`）变更时，确认 MQTT 连接时机判断
  - [ ] 多网络优先级（`getNetworkPriority`）变更时，确认最佳网络选择逻辑

### 6. SOCKS5ProxyHandler 协议处理变更
- **影响范围**: Socks5ProxyService, GatewayVpnService, IpAddressUtils
- **风险类型**: 协议不兼容、安全绕过、连接泄漏
- **检查清单**:
  - [ ] `validateTargetAddress()` 验证逻辑变更时，确认安全边界
  - [ ] `RelayHandler` 释放语义变更时，确认 Netty ByteBuf 生命周期
  - [ ] CONNECT/BIND/UDP_ASSOCIATE 命令处理变更时，确认状态机一致
  - [ ] 认证流程变更时，Socks5ProxyService 的 credentialValidator 同步

## 模块边界与隔离性评估

| 模块 | 耦合度 | 主要耦合点 | 隔离建议 |
|------|--------|-----------|----------|
| vpn | 高 | 依赖proxy、connection、ui | 提取PacketParser和ConnectionManager为独立模块 |
| proxy | 中 | 被vpn调用，依赖connection | 连接池配置注入化，减少硬编码 |
| connection | 高 | 被vpn、proxy、ui调用 | AuthSessionStore接口化，减少直接依赖 |
| ui | 中 | 依赖viewmodel，观察各状态Flow | 保持单向数据流，避免反向调用 |
| di | 中 | 被多处引用 | ModuleCoordinator弱引用化，防止内存泄漏 |

## 关键路径分析

### VPN启动关键路径
```
MainViewModel.toggleVpn(true)
  -> AndroidVpnService.prepare() // 权限检查
  -> context.startForegroundService(Intent[START])
  -> GatewayVpnService.onStartCommand()
  -> GatewayVpnService.startVpn()
     -> Builder.establish() // TUN接口创建
     -> initializeConnectionPool() // 连接池初始化
     -> startProxyService() // Socks5ProxyService启动
     -> processVpnTraffic() // TUN读取协程
     -> processReturnTraffic() // 回包处理协程
```

### MQTT连接关键路径
```
MainViewModel.pairWithCode(code)
  -> networkStateManager.isCellularConnected() // 网络检查
  -> authSessionStore.update() // 会话保存
  -> mqttConnectionManager.connect() // MQTT连接
     -> MqttClient.connect() // Paho连接
     -> subscribe("device/$deviceId/control") // 控制主题订阅
     -> startHeartbeat() // 心跳协程
```

### 断开连接关键路径
```
MainViewModel.disconnect()
  -> mqttConnectionManager.disconnect() // MQTT断开
  -> authSessionStore.clear() // 会话清除
  -> toggleVpn(false) // VPN停止
     -> context.startService(Intent[STOP])
     -> GatewayVpnService.stopVpn()
        -> serviceScope.cancel()
        -> cleanupVpnResources()
        -> stopProxyService()
```

## 关联文档

- [../INDEX.md](../INDEX.md) - 问题清单索引
- [../modules/vpn.md](../modules/vpn.md) - VPN模块问题详情
- [../modules/proxy.md](../modules/proxy.md) - 代理模块问题详情
- [../modules/connection.md](../modules/connection.md) - 连接模块问题详情

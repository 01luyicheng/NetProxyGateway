# NetProxyGateway 问题清单

## Critical

### C1: SSL信任所有证书配置风险
- **位置**: `android/app/src/main/java/com/netproxy/gateway/connection/MqttConnectionManager.kt` (L95-L158)
- **问题**: `MQTT_TRUST_ALL_CERTS` 在debug模式下默认为true，如果构建配置错误，生产环境可能使用不安全的信任所有证书模式
- **代码**:
  ```kotlin
  private val trustAllCerts = BuildConfig.DEBUG || BuildConfig.MQTT_TRUST_ALL_CERTS
  ```

### C3: 虚拟IP分配线程不安全
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt` (L746-L753)
- **问题**: `nextVirtualIp.getAndIncrement()` 可能产生超过255的值，缺少IP地址范围边界检查；多线程环境下IP分配可能冲突
- **代码**:
  ```kotlin
  private val nextVirtualIp = AtomicInteger(2)
  val hostIp = nextVirtualIp.getAndIncrement()
  return "10.0.0.$hostIp"
  ```

## High

### H1: SOCKS5代理DNS重绑定攻击风险
- **位置**: `android/app/src/main/java/com/netproxy/gateway/proxy/Socks5ProxyHandler.kt` (L112)
- **问题**: 使用`InetAddress.getByName()`进行DNS解析，可能受到DNS重绑定攻击
- **代码**:
  ```kotlin
  val inetAddr = java.net.InetAddress.getByName(host)
  ```

### H2: MQTT异常处理不完善
- **位置**: `android/app/src/main/java/com/netproxy/gateway/connection/MqttConnectionManager.kt` (L232-242)
- **问题**: 只捕获MqttException，其他运行时异常未被处理；重连机制没有最大尝试次数限制
- **代码**:
  ```kotlin
  try {
      // MQTT操作
  } catch (e: MqttException) {  // 仅捕获MqttException
      // 处理
  }
  ```

### H4: 连接池清理竞争条件
- **位置**: `android/app/src/main/java/com/netproxy/gateway/proxy/Socks5ConnectionPool.kt` (L343-L366)
- **问题**: read锁和write锁之间连接状态可能变化

### H5: VpnService类过大
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt`
- **问题**: 959行，承担太多职责（VPN生命周期、TUN处理、IP解析、连接池管理等）

### H6: SOCKS5连接池读取未设置超时
- **位置**: `android/app/src/main/java/com/netproxy/gateway/proxy/Socks5ConnectionPool.kt` (L315-L329)
- **问题**: `readFully`方法没有设置超时，可能永久阻塞

### H7: MQTT TLS证书固定配置可能为空
- **位置**: `android/app/src/main/java/com/netproxy/gateway/connection/MqttConnectionManager.kt` (L117-L124)
- **问题**: 当`MQTT_TLS_PUBLIC_KEY_PINS`为空时，仅记录警告，仍使用默认CA验证

## Medium

### M1: 边界条件：IP地址解析未验证数值范围
- **位置**: `android/app/src/main/java/com/netproxy/gateway/utils/IpAddressUtils.kt` (L6-L18)
- **问题**: `isPrivateIpv4Rfc1918`方法没有验证每个octet是否在0-255范围内

### M2: WiFi管理器权限检查不一致
- **位置**: `android/app/src/main/java/com/netproxy/gateway/wifi/WifiManager.kt` (L210-L223)
- **问题**: 同一功能有两个版本，一个静默失败，一个返回错误

### M3: Root检测执行命令未超时
- **位置**: `android/app/src/main/java/com/netproxy/gateway/security/RootDetector.kt` (L203-L214, L242-L252)
- **问题**: `process.waitFor()`没有设置超时，如果命令挂起会阻塞线程

### M4: VPN服务中的忙等待
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt` (L505-L530)
- **问题**: 使用轮询方式检查连接数据，即使空闲时也有1ms延迟的循环

### M5: IPv6数据包直接丢弃
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt` (L269-L283)
- **问题**: `parseDestinationIp`方法在解析到非IPv4版本时返回null，导致IPv6数据包被丢弃
- **代码**:
  ```kotlin
  if (version != 4) return null
  ```

### M6: IP分片未处理
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt` (L802-L817)
- **问题**: 传输层payload提取不处理分片

### M7: WiFi连接API过时
- **位置**: `android/app/src/main/java/com/netproxy/gateway/wifi/WifiManager.kt` (L322-L361)
- **问题**: 使用`WifiConfiguration` API在Android 10+上已被废弃

### M8: 云服务器路由未实现
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt` (L256-L258)
- **问题**: `CLOUD_SERVER`路由类型实际未实现，直接忽略包

### M9: TCP回包状态管理不完整
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt` (L647-L666)
- **问题**: 序列号和确认号固定为0，不符合TCP协议

### M10: MQTT重连延迟计算可能溢出
- **位置**: `android/app/src/main/java/com/netproxy/gateway/connection/MqttConnectionManager.kt` (L245-L255)
- **问题**: `reconnectDelay * 2`在计算时可能发生Long溢出

### M11: 连接池读锁内修改
- **位置**: `android/app/src/main/java/com/netproxy/gateway/proxy/Socks5ConnectionPool.kt` (L119-L136)
- **问题**: 在read锁内调用`removeConnection`，而`removeConnection`需要write锁
- **代码**:
  ```kotlin
  val connection = poolLock.read {
      removeConnection(conn)
  }
  ```

## Low

### L1: 魔法数字
- **位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt` (L269-L282)
- **问题**: 多处使用未命名的协议号（17=UDP, 6=TCP）

### L2: TODO注释未处理
- **位置**: `android/app/src/main/java/com/netproxy/gateway/connection/MqttConnectionManager.kt` (L93)
- **问题**: 存在未处理的TODO注释，涉及安全配置

### L3: EmulatorDetector权限检查重复
- **位置**: `android/app/src/main/java/com/netproxy/gateway/security/EmulatorDetector.kt`
- **问题**: 多个方法重复检查`READ_PHONE_STATE`权限

### L5: 缺少集成测试
- **问题**: 测试主要集中在单元测试，缺少组件间集成测试

### L8: 过长且重复的方法名
- **位置**: `android/app/src/main/java/com/netproxy/gateway/connection/MqttConnectionManager.kt` (L271-L301)
- **问题**: `publishWithResult`, `subscribeWithResult`等方法名过于冗长

### L9: 代码重复：IP地址解析逻辑
- **位置**: `VpnService.kt` 和 `VpnTestUtils.kt`
- **问题**: VpnTestUtils中完全复制了VpnService的实现代码

### L10: 重复的错误处理模式
- **位置**: `android/app/src/main/java/com/netproxy/gateway/connection/MqttConnectionManager.kt`
- **问题**: 重复的错误处理代码块

### L12: 未使用Kotlin标准库Result类型
- **位置**: `android/app/src/main/java/com/netproxy/gateway/result/AppResult.kt`
- **问题**: 项目自定义了AppResult，但Kotlin 1.5+已提供标准库Result

### L13: 硬编码延迟
- **位置**: `android/app/src/main/java/com/netproxy/gateway/ui/viewmodel/MainViewModel.kt` (L165-L174)
- **问题**: 使用`delay(2000)`等待扫描完成是脆弱的设计

### L14: 不必要的抽象层
- **位置**: `android/app/src/main/java/com/netproxy/gateway/di/ModuleInterfaces.kt`, `ModuleImplementations.kt`
- **问题**: 每个接口只有一个实现，增加了不必要的复杂性

### L15: 重复的Result包装方法
- **位置**: `android/app/src/main/java/com/netproxy/gateway/wifi/WifiManager.kt` (L210-L275)
- **问题**: 为每个操作提供两种版本（直接返回和Result包装）是冗余的

### L16: 测试验证语言特性而非业务逻辑
- **位置**: `android/app/src/test/java/com/netproxy/gateway/connection/MqttConnectionManagerTest.kt` (L21-L68)
- **问题**: 测试验证Kotlin的object特性和数据类行为，应补充业务逻辑测试

### L17: 隐式依赖网
- **位置**: `android/app/src/main/java/com/netproxy/gateway/di/ModuleCoordinator.kt`
- **问题**: ModuleCoordinator与Hilt注入并存，形成隐式依赖网

### ISSUE-014: 接口过度设计
- **位置**: `android/app/src/main/java/com/netproxy/gateway/di/ModuleInterfaces.kt`
- **问题**: CommandHandler 和 QueryHandler 拆分增加不必要的复杂度

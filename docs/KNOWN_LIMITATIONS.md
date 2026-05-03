# 已知限制清单

> 这是已知限制清单，记录产品设计上的权衡和已知限制（非缺陷）。
> 本文档所列项目均为设计决策或产品范围限制，而非需要修复的软件缺陷。

---

## 1. IPv6支持限制

### L-IPV6-01: IPv6数据包直接丢弃
- **描述**: VPN服务在解析数据包时，遇到非IPv4版本（版本号不为4）的数据包直接返回null并丢弃。
- **原因（设计决策）**: 当前产品专注于IPv4网络环境，IPv6支持会增加代码复杂度和测试范围。
- **影响范围**: 在IPv6网络环境下，所有IPv6流量将被丢弃，无法通过VPN隧道传输。
- **代码位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt` (L298-L303)
- **未来计划**: 暂无计划支持，需根据市场需求评估。

### L-IPV6-02: SOCKS5代理仅监听IPv4地址
- **描述**: 代理服务器硬编码监听`127.0.0.1`，不支持IPv6本地地址`::1`。
- **原因（设计决策）**: 当前实现假设工程师在IPv4环境下工作，简化配置和测试。
- **影响范围**: 在纯IPv6环境下，工程师无法通过本地回环地址连接SOCKS5代理。
- **代码位置**: `android/app/src/main/java/com/netproxy/gateway/proxy/Socks5ProxyService.kt` (L98)
- **未来计划**: 暂无计划支持，需根据市场需求评估。

### L-IPV6-03: IPv6私有地址检查逻辑不完整
- **描述**: `isPrivateIpv6Address`函数仅检查ULA地址（fc/fd开头），未完整覆盖所有IPv6私有地址范围。
- **原因（设计决策）**: 由于IPv6流量本身被丢弃，完整的IPv6地址检查逻辑并非当前优先级。
- **影响范围**: 即使部分IPv6地址被错误地识别或放行，由于IPv6数据包被整体丢弃，实际影响有限。
- **代码位置**: `android/app/src/main/java/com/netproxy/gateway/proxy/Socks5ProxyHandler.kt` (L133-L141)
- **未来计划**: 若未来支持IPv6，需完整实现RFC 4193和RFC 4291规定的私有地址范围检查。

### L-IPV6-04: VPN服务仅配置IPv4地址和路由
- **描述**: VPN Builder仅添加IPv4地址`10.0.0.2`和IPv4路由`0.0.0.0/0`，未配置IPv6地址和路由。
- **原因（设计决策）**: 当前产品定位为IPv4远程网络协助，IPv6配置会增加系统复杂度和潜在兼容性问题。
- **影响范围**: IPv6流量无法进入VPN隧道，系统会将其路由到默认网络接口而非VPN。
- **代码位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt` (L78-L80, L185-L189)
- **未来计划**: 暂无计划支持，需根据市场需求评估。

### L-IPV6-05: DNS配置仅支持IPv4服务器
- **描述**: DNS服务器解析和验证函数仅处理IPv4地址格式，无法配置或使用IPv6 DNS服务器。
- **原因（设计决策）**: 与整体IPv4优先策略一致，简化DNS配置逻辑。
- **影响范围**: 无法使用IPv6 DNS服务器（如`2001:4860:4860::8888`）。
- **代码位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnDnsConfig.kt` (L54-65, L67-82)
- **未来计划**: 暂无计划支持，需根据市场需求评估。

### L-IPV6-06: IP地址工具类缺少IPv6支持
- **描述**: `IpAddressUtils`工具类仅提供IPv4地址验证和私有地址检查，无任何IPv6相关函数。
- **原因（设计决策）**: 当前所有网络逻辑均基于IPv4，无需IPv6工具函数。
- **影响范围**: 任何需要IPv6地址处理的场景均不支持。
- **代码位置**: `android/app/src/main/java/com/netproxy/gateway/utils/IpAddressUtils.kt`
- **未来计划**: 若未来支持IPv6，需扩展该类以支持IPv6地址验证和范围检查。

### L-IPV6-07: SOCKS5握手使用IPv4固定地址类型
- **描述**: SOCKS5连接请求头硬编码使用`0x01`（IPv4地址类型），且地址解析在IPv6环境下会产生不匹配的16字节地址。
- **原因（设计决策）**: 当前SOCKS5实现仅支持IPv4目标地址，简化协议处理逻辑。
- **影响范围**: 无法通过SOCKS5代理连接到IPv6目标地址。
- **代码位置**: `android/app/src/main/java/com/netproxy/gateway/proxy/Socks5ConnectionPool.kt` (L81, L297-300)
- **未来计划**: 暂无计划支持，需根据市场需求评估。

### L-IPV6-08: WiFi连接信息仅获取IPv4地址
- **描述**: `WifiConnectionInfo`数据类仅包含`ipAddress: Int`字段，用于存储IPv4地址，未获取或存储IPv6地址信息。
- **原因（设计决策）**: 当前WiFi管理功能仅关注IPv4连接状态，用于网络诊断和日志记录。
- **影响范围**: 无法获取或显示设备的IPv6地址信息。
- **代码位置**: `android/app/src/main/java/com/netproxy/gateway/wifi/WifiManager.kt` (L39-L46, L283-L299)
- **未来计划**: 暂无计划支持，需根据市场需求评估。

---

## 2. 协议支持限制

### L-PROTO-01: IP分片未处理
- **描述**: 传输层payload提取逻辑不处理IP分片，假设所有数据包均为完整包。
- **原因（设计决策）**: 处理IP分片需要实现复杂的重组逻辑和缓冲区管理，增加代码复杂度和内存开销。在MTU配置合理的情况下，分片情况较少见。
- **影响范围**: 遇到分片的数据包时，可能无法正确提取payload或导致连接异常。
- **代码位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt` (L840-L854)
- **未来计划**: 暂无计划支持，建议通过合理配置MTU减少分片发生。

### L-PROTO-02: ICMP协议未支持
- **描述**: `parseProtocol`函数未处理ICMP协议（协议号1），ICMP数据包被丢弃。
- **原因（设计决策）**: ICMP协议需要特殊处理（如ping请求/响应、错误报告），实现完整的ICMP支持需要额外的协议状态管理。
- **影响范围**: 工程师无法使用ping、traceroute等基本网络诊断工具测试内网连通性。
- **代码位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt` (L825-L827)
- **未来计划**: 考虑在后续版本中支持基本的ICMP echo请求/响应（ping）。

### L-PROTO-03: 多播/广播流量未转发
- **描述**: 流量处理逻辑未识别多播（224.0.0.0/4, ff00::/8）和广播（255.255.255.255）地址，此类流量未进入转发逻辑。
- **原因（设计决策）**: 多播和广播流量通常用于局域网内设备发现和服务发现，通过VPN隧道转发需要特殊处理（如IGMP代理），复杂度较高。
- **影响范围**: 依赖多播的设备发现协议（如mDNS、SSDP）无法通过VPN工作，工程师无法自动发现内网设备。
- **代码位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt` (L317-L337)
- **未来计划**: 暂无计划支持，工程师需手动输入目标设备IP地址。

### L-PROTO-04: 链路本地地址访问受限
- **描述**: `isPrivateIp`仅检查RFC1918私有地址，未处理链路本地地址（169.254.0.0/16, fe80::/10）。
- **原因（设计决策）**: 链路本地地址通常用于无DHCP环境下的自动配置，远程网络协助场景下较少需要访问此类地址。
- **影响范围**: 某些使用链路本地地址的设备（如部分打印机、IoT设备）可能无法通过当前逻辑访问。
- **代码位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt` (L376-L377)
- **未来计划**: 评估实际使用场景后决定是否支持。

### L-PROTO-05: UDP打洞和NAT穿透缺失
- **描述**: 未实现UDP打洞（UDP hole punching）或STUN/TURN协议。
- **原因（设计决策）**: 当前架构使用云服务器中转（MQTT+SOCKS5代理），不依赖P2P直连，因此无需NAT穿透。
- **影响范围**: 某些需要P2P直连的低延迟应用场景（如实时音视频）可能受限。
- **代码位置**: `android/app/src/main/java/com/netproxy/gateway/proxy/Socks5ConnectionPool.kt`
- **未来计划**: 若未来需要支持P2P直连场景，将评估实现STUN/TURN协议。

---

## 3. 产品功能限制（远程网络调试场景）

### L-PROD-01: 路由器/AP管理界面端口和协议差异
- **描述**: 不同品牌路由器使用不同的默认管理端口（80、443、8080、8443等）和协议（HTTP、HTTPS），当前代码未提供端口扫描或协议识别功能。
- **原因（设计决策）**: 端口扫描和协议识别功能可能被安全软件标记为恶意行为，且不同设备差异过大难以统一处理。
- **影响范围**: 工程师需要手动知道目标设备的访问方式（端口和协议）。
- **代码位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt`, `android/app/src/main/java/com/netproxy/gateway/proxy/Socks5ProxyHandler.kt`
- **未来计划**: 考虑提供常见设备品牌的默认端口参考文档，而非自动扫描。

### L-PROD-02: 缺乏设备发现协议支持
- **描述**: 未实现mDNS（Bonjour）、SSDP（UPnP）、WS-Discovery等设备发现协议。
- **原因（设计决策）**: 设备发现协议通常依赖多播/广播，而当前架构不支持多播/广播流量转发（见L-PROTO-03）。
- **影响范围**: 工程师无法自动发现内网中的路由器、AP、打印机等设备，需要手动输入IP地址。
- **代码位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt` (L317-L337 determineRouteType函数，多播/广播地址检查逻辑)
- **未来计划**: 若未来支持多播转发，将同步评估设备发现协议支持。

### L-PROD-03: 特殊网络管理协议未支持
- **描述**: 未针对SNMP（161/162端口）、TR-069/CWMP（7547端口）、NETCONF（830端口）等网络管理协议进行特殊处理。
- **原因（设计决策）**: 这些协议在远程协助场景中使用频率较低，特殊处理会增加代码复杂度。
- **影响范围**: 某些网络管理协议在当前的TCP/UDP转发逻辑中可能工作不正常。
- **代码位置**: `android/app/src/main/java/com/netproxy/gateway/proxy/Socks5ConnectionPool.kt`, `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt`
- **未来计划**: 根据实际用户反馈，优先支持使用最广泛的协议。

### L-PROD-04: 网络管理工具集成缺失
- **描述**: 缺乏与SSH（22端口）、Telnet（23端口）、Winbox（8291端口）、RouterOS API等常用网络管理工具的集成。
- **原因（设计决策）**: 保持产品简洁性，工程师可通过外部工具通过SOCKS5代理连接。
- **影响范围**: 工程师需要使用外部工具通过SOCKS5代理连接，体验不如内置集成无缝。
- **代码位置**: `android/app/src/main/java/com/netproxy/gateway/proxy/Socks5ProxyService.kt`
- **未来计划**: 暂无计划内置协议客户端，保持产品简洁性。

### L-PROD-05: 多跳网络环境下的延迟和超时
- **描述**: 未实现多跳网络环境下的延迟检测和自适应超时机制。远程协助场景下路径为：工程师 → 云服务器 → 手机VPN → 内网设备。
- **原因（设计决策）**: 自适应超时机制需要复杂的网络质量评估算法，且不同应用场景对延迟敏感度不同。
- **影响范围**: 高延迟可能导致TCP连接超时、SSH会话中断等问题。
- **代码位置**: `android/app/src/main/java/com/netproxy/gateway/connection/NetworkStateManager.kt`
- **未来计划**: 考虑提供可配置的超时参数，供工程师根据实际网络环境调整。

### L-PROD-06: 内网IP地址冲突风险
- **描述**: 虚拟IP池使用固定的`10.0.0.x`网段，如果客户内网恰好使用相同网段，将导致IP冲突。
- **原因（设计决策）**: 固定网段简化了IP分配逻辑，且`10.0.0.0/8`为私有地址范围，冲突概率相对较低。
- **影响范围**: 若客户内网使用`10.0.0.0/24`网段，工程师无法访问内网设备。
- **代码位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt` (L120-L123, L798-L806)
- **未来计划**: 考虑提供可配置的虚拟IP网段选项。

### L-PROD-07: 访问控制和审计机制缺失
- **描述**: 缺乏细粒度的访问控制（如限制可访问的内网IP范围、端口白名单）和操作审计日志。
- **原因（设计决策）**: 当前产品定位为个人/小团队远程协助，企业级安全功能会增加复杂度。
- **影响范围**: 在企业场景下可能存在安全风险，无法限制工程师可访问的范围或追溯操作记录。
- **代码位置**: 全局（当前SecurityManager仅提供Root/调试/模拟器检测，无访问控制功能）
- **未来计划**: 根据企业客户需求评估是否添加。

---

## 4. 功能缺口（Feature Gaps）

> 以下是产品功能缺口记录，属于待实现的功能需求，而非设计决策或技术限制。

### L-FEAT-01: 多设备并发连接支持
- **描述**: 当前架构仅支持单工程师-单设备连接，虚拟IP池设计为单会话场景。
- **影响范围**: 不支持多工程师同时协助同一设备，或多设备同时被协助。
- **代码位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VirtualIpAllocator.kt`
- **未来计划**: 根据需求评估是否支持多会话并发。

---

## 5. 其他技术限制

### L-TECH-01: 云服务器路由未实现
- **描述**: `CLOUD_SERVER`路由类型在代码中定义为枚举值，但实际未实现，相关数据包被直接忽略。
- **原因（设计决策）**: 当前架构中云服务器仅作为MQTT信令通道，不直接转发数据流量（数据通过SOCKS5代理）。
- **影响范围**: 无实际影响，该枚举值为预留设计。
- **代码位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt` (L283-L286)
- **未来计划**: 若未来架构调整，可能启用该路由类型。

### L-TECH-02: WiFi连接API在Android 10+上的限制
- **描述**: WiFi连接路径使用传统API（WifiConfiguration），在Android 10+上已被废弃且受限。Android 10+对后台应用启动WiFi连接有限制，可能导致连接失败或需要用户手动确认。
- **原因（系统限制）**: Android系统API限制，Google官方废弃了旧版WiFi连接API，新版API需要用户交互确认。
- **影响范围**: 在Android 10+设备上，自动WiFi连接功能受限，可能需要用户手动干预。
- **代码位置**: `android/app/src/main/java/com/netproxy/gateway/wifi/WifiManager.kt`
- **未来计划**: 评估使用Suggestion API或NetworkSpecifier进行请求（Android 10+）。

### L-TECH-03: MQTT TLS加密和证书固定限制
- **描述**: MQTT安全性使用TLS加密和证书固定（当MQTT_TLS_PUBLIC_KEY_PINS配置时）；配置为空时回退到默认CA验证。代码当前通过`SSLContext.getInstance("TLSv1.2")`显式创建SSLContext，固定使用TLS 1.2协议版本。此设计在未配置证书固定时仍存在中间人攻击风险。
- **原因（设计决策）**: 为简化部署，允许不配置证书固定，但降低了安全性。
- **影响范围**: 未配置证书固定时，MQTT连接可能受到中间人攻击。
- **代码位置**: `android/app/src/main/java/com/netproxy/gateway/connection/MqttConnectionManager.kt`
- **未来计划**: 考虑强制要求证书固定或提供更强的默认安全策略。

### L-TECH-04: 虚拟IP池大小限制
- **描述**: 虚拟IP池使用固定的10.0.0.x网段（10.0.0.2-10.0.0.254），仅支持254个设备同时连接。
- **原因（设计决策）**: 固定网段简化了IP分配逻辑，且当前产品定位为单设备远程协助场景。
- **影响范围**: 不支持多设备同时连接的大规模部署场景。
- **代码位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt` (L798-L806)
- **未来计划**: 根据需求评估是否扩展IP池大小或支持动态网段配置。

### L-TECH-05: 服务端开发模式仅API服务支持
- **描述**: `APP_ENV=development` 环境变量仅在 API 服务（`server/api`）中支持自动生成 `INTERNAL_API_KEY`。SOCKS5 代理（`server/socks5-proxy`）和 Tunnel 网关（`server/tunnel`）在 `INTERNAL_API_KEY` 未设置时直接退出，无开发模式回退。
- **原因（设计决策）**: 
  - 三个服务需要共享相同的 `INTERNAL_API_KEY` 进行内部认证，自动生成会导致密钥不一致
  - 开发模式下 API 服务生成的临时密钥每次重启都会变化，其他服务无法同步
  - `docker-compose.yml` 使用 `${INTERNAL_API_KEY:?}` 强制要求设置，开发模式在容器化部署中不会触发
- **影响范围**: 
  - 单独启动 API 服务进行开发调试时功能正常
  - 需要多服务联调时，开发者必须显式设置 `INTERNAL_API_KEY` 环境变量
  - 开发模式警告日志已明确提示此限制
- **代码位置**: 
  - API 服务: `server/api/main.go` (L92-121)
  - SOCKS5 代理: `server/socks5-proxy/main.go` (L1198-1201)
  - Tunnel 网关: `server/tunnel/main.go` (L569-572)
- **未来计划**: 若需要完整的开发模式多服务联调支持，需实现密钥共享机制（如写入共享文件或使用配置中心）。

### L-TECH-06: MTU固定值限制
- **描述**: VPN MTU硬编码为1500，未根据实际网络环境自适应调整。
- **原因（设计决策）**: 固定MTU简化了实现，1500是以太网标准MTU，适用于大多数场景。
- **影响范围**: 在某些网络环境下（如PPPoE、VPN over VPN），固定MTU可能导致分片或性能下降。
- **代码位置**: `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt` (L80)
- **未来计划**: 考虑实现MTU自动探测或提供可配置的MTU选项。

---

## 文档维护说明

- 本文档最后更新日期：2026-05-03
- 新增限制应遵循本文档格式，明确说明设计决策原因
- 当限制被解除时，应将其移至"已解除限制"章节并标注解除日期

---

## 变更记录

| 日期 | 变更内容 | 变更人 |
|------|----------|--------|
| 2026-03-31 | 初始创建，从原ISSUES.md迁移已知限制项，建立L-XXX编号体系 | AI Agent |
| 2026-03-31 | 代码引用修正：修正所有代码位置引用，确保与当前代码库一致 | AI Agent |
| 2026-03-31 | 文件路径修正：GatewayVpnService.kt → VpnService.kt，WifiManager.kt → GatewayWifiManager.kt；L-TECH-03描述澄清：TLS 1.2固定 → TLS加密 | AI Agent |
| 2026-03-31 | 新增限制项：L-IPV6-08（WiFi连接信息仅获取IPv4地址）、L-TECH-01（云服务器路由未实现） | AI Agent |
| 2026-03-31 | 文档结构优化：按IPv6支持、协议支持、产品功能、其他技术限制分类组织 | AI Agent |
| 2026-04-15 | 新增限制项：L-TECH-05（服务端开发模式仅API服务支持），记录跨服务开发模式不一致问题 | Kimi-K2.5 |
| 2026-05-03 | 修正编号体系：删除重复的L-PROD-01，合并功能缺口到产品功能限制章节，重新编号为L-PROD-01到L-PROD-07；新增L-FEAT-01；修正所有代码行号引用；修正GatewayVpnService.kt为VpnService.kt；L-PROTO-06改为L-TECH-06 | AI Agent (Kimi-K2.6) |


# Android 网络路由控制分析

> 核心结论：应用层分流在 VPN 模式下部分有效，但无法强制指定网卡出口。

---

## 执行摘要

| 评估维度 | 结论 | 风险等级 |
|---------|------|----------|
| VPN 流量拦截 | 能有效捕获所有 IPv4 流量 | 低 |
| DNS 分流 | 能正确识别传统 DNS（端口 53） | 中 |
| 内网直连 | 实现过于简化，TCP 长连接可能不稳定 | 中 |
| 外网代理 | SOCKS5 连接池工作正常 | 低 |
| 双网卡并发控制 | **无法强制指定出口，依赖系统决策** | **高** |
| 厂商定制适配 | **完全没有适配代码** | **高** |

---

## 当前实现

### 流量路由决策

| 流量类型 | 目标地址 | 出口 | 实现方式 | 代码位置 |
|---------|---------|------|---------|----------|
| 内网流量 | RFC1918 私有 IP | WiFi | `protect()` + Socket 直连 | [VpnService.kt L366-374](android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt#L366-L374) |
| DNS 查询 | DNS 服务器 + 端口 53 | WiFi | `protect()` + DatagramSocket | [VpnService.kt L356-365](android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt#L356-L365) |
| 外网流量 | 公网 IP | 蜂窝数据 (通过代理) | 本地 SOCKS5 代理 | [VpnService.kt L376-387](android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt#L376-L387) |
| 云服务器 | 特定 IP | 蜂窝数据 | **未实现** (`excludeRoute`) | [VpnService.kt L254-258](android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt#L254-L258) |

### 核心限制

**`protect()` API 的局限性**：
- 只能让 Socket 绕过 VPN
- **不能指定使用 WiFi 还是移动数据**
- 系统根据默认网络策略选择出口

```kotlin
// 当前实现：只能绕过 VPN，不能指定网卡
DatagramSocket().use { socket ->
    protect(socket)  // 绕过 VPN，但系统决定用 WiFi 还是移动数据
    socket.send(datagram)
}
```

---

## 厂商定制 ROM 影响

**项目中完全没有针对厂商定制的适配代码**

| 厂商 | 功能 | 影响 |
|------|------|------|
| 小米/Redmi | 双 WiFi 加速 | 同时使用 2.4G + 5G，应用无法区分 |
| 华为/荣耀 | Link Turbo | WiFi + 移动数据并发，Socket 可能被路由到不同网络 |
| OPPO/一加 | 智能网络切换 | WiFi 信号差时自动切移动数据 |
| vivo | 网络加速 | 类似实现，应用层透明 |

**缺失的适配项**：
- 电池优化白名单检测
- 后台启动权限检查
- 网络加速功能检测
- 厂商特定保活机制

---

## 未使用的关键 API

| API | 引入版本 | 用途 | 当前状态 |
|-----|----------|------|----------|
| `Network.bindSocket()` | API 21+ | 强制绑定 Socket 到指定网络 | **未使用** |
| `VpnService.Builder.excludeRoute()` | API 33+ | 硬件级路由排除 | **未实现**（仅注释） |
| `ConnectivityManager.getAllNetworks()` | API 21+ | 获取所有可用网络 | **未使用** |

---

## 已识别的限制

详见 [KNOWN_LIMITATIONS.md](KNOWN_LIMITATIONS.md)：

- **L-NET-01**: 应用层无法强制指定网络出口
- **L-NET-02**: 厂商定制 ROM 网络行为无法控制
- **L-NET-03**: 未使用 Android 原生多网络 API
- **L-NET-04**: 内网 TCP 转发实现过于简化
- **L-NET-05**: 云服务器流量排除未真正实现
- **L-NET-06**: DoH/DoT DNS 无法正确分流

---

## 相关文档

- [KNOWN_LIMITATIONS.md](KNOWN_LIMITATIONS.md) - 已知限制清单
- [AGENTS.md](../AGENTS.md) - AI 代理执行契约

---

*本文档最后更新：2026-03-31*

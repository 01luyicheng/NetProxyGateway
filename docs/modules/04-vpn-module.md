# VPN模块规格（v2：VPN 接管模式）

## 4.1 模块概述

VPN 模块仅用于阶段 2（v2）：当需要通过 `VpnService` 接管设备流量并进行分流时启用。

阶段 1（v1）的“工程师远程访问内网”链路不依赖 VPN（见 [server-socks5-relay.md](server-socks5-relay.md)）。

## 4.2 本地VPN服务

### 4.2.1 TUN虚拟接口

- 使用 Android VpnService API 创建虚拟网络接口
- 分配虚拟 IP 地址
- 设置路由表

### 4.2.2 流量捕获

- 捕获进入 VPN 的流量范围取决于 `VpnService.Builder` 的路由配置（并非天然“所有流量”）。
- 数据面默认以 TCP 为主；UDP/DNS/IPv6 的完整支持需要以 POC 结果为准（见 [integration-vpn-socks5.md](integration-vpn-socks5.md)）。
- 本地 VPN 负责“截获与重定向”，并不提供加密；是否加密由出站链路（TLS/隧道）决定。

### 4.2.3 Split Tunneling (Android 13+)

- 可通过更精细的路由策略减少进入 TUN 的包量（例如研究 `excludeRoute` 的实际行为）。
- 也可通过 allow/disallow app 列表减少回环风险与性能开销，但不足以替代 `VpnService.protect()`（见 v2 集成文档）。

## 4.3 流量转发

### 4.3.1 内网流量

- 目标 IP 属于内网段 → 通过 WiFi 网卡直连
- 保持低延迟

### 4.3.2 外网流量

- 目标属于公网段 → 走 `CELLULAR_PROXY`（蜂窝/云中转）侧出站连接器。
- 具体是“直连公网”还是“经云中转”取决于 v2 的出站架构选择（POC 后定）。

## 4.4 错误处理

| 错误场景 | 处理策略 |
|---------|---------|
| VPN 启动失败 | 提示用户检查权限，重试机制 |
| WiFi 网络断开 | 保持蜂窝控制通道，标记内网访问不可用 |
| SOCKS5 代理不可达 | 尝试重新连接，记录日志 |

---

## 相关文档

- [系统架构](./02-architecture.md) - 了解整体架构
- [连接管理模块](./03-connection-module.md) - 了解网络状态检测
- [代理模块](./05-proxy-module.md) - 了解 SOCKS5 代理实现
- [Android权限](./10-android-permissions.md) - 了解 VPN 所需权限

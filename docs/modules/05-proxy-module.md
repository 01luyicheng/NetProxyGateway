# 代理模块规格

本模块定义 v1/v2 下与 SOCKS5/字节转发相关的组件边界。

## 5.1 模块概述

代理能力在不同阶段由不同组件承担：

| 组件 | 位置 | 角色 | 用途 |
|------|------|------|------|
| Cloud SOCKS5 Entry | 云服务端 | **SOCKS5 服务器** | 工程师入口（v1 必选），对接通用 SOCKS5 客户端 |
| Reverse Tunnel（多路复用） | 云服务端 + 移动端 | **隧道承载** | 工程师入口与设备之间的可靠连接（v1 必选） |
| Device Agent | 移动端 | **转发终端** | 接收隧道的“打开到目标 (ip:port)”请求，在 WiFi 上建立 TCP 连接并转发（v1 必选） |
| Local SOCKS5 Server（可选） | 移动端 | **SOCKS5 服务器** | 仅用于 v2（VPN 接管/tun2socks）的本地出站端点，**不对工程师直连开放** |

v1 典型链路：
```text
Engineer -> Cloud SOCKS5 Entry -> Reverse Tunnel -> Device Agent -> WiFi LAN Target
```

v2（VPN 接管）链路在 [integration-vpn-socks5.md](integration-vpn-socks5.md) 与 [traffic-split-engine.md](traffic-split-engine.md) 定义。

---

## 5.2 云端 SOCKS5 入口（v1）

- 对工程师提供 SOCKS5 服务端（默认 `1080/TCP`，具体端口可配置）。
- 仅需支持 SOCKS5 `CONNECT`（TCP/IPv4）。
- 会话必须绑定 `deviceId`（例如 `username=deviceId` + `password=socks_session_token`），详见 [server-socks5-relay.md](server-socks5-relay.md)。

---

## 5.3 设备侧转发终端（v1）

- 不对公网暴露入站端口。
- 通过反向隧道与云端保持在线。
- 收到“连接到内网目标 (ip:port)”请求后，在 WiFi `Network` 上建立 TCP socket 并做双向字节转发。

---

## 5.4 移动端本地 SOCKS5 服务器（v2 可选）

若 v2 采用 tun2socks/用户态协议栈路线，可能需要一个移动端本地 SOCKS5 服务作为出站端点：

- **监听地址**：必须为 `127.0.0.1`（或仅在 VPN 进程可达的本地环回），避免被局域网/公网访问。
- **用途**：承接 TUN 转换后的连接请求，并根据分流结果选择 WiFi 或蜂窝/云中转侧的出站连接器。

---

## 相关文档

- [系统架构](./02-architecture.md) - 了解整体架构
- [服务端中转](server-socks5-relay.md) - v1 云端入口 + 反向隧道
- [VPN 集成](integration-vpn-socks5.md) - v2 VPN 接管设计

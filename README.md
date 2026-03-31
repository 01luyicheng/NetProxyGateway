# NetProxyGateway

为远程网络协助提供 Android 应用作为进入内网的跳板。

## 项目意义

网络工程师需要远程协助客户解决网络问题，但客户设备通常在 NAT/防火墙后，无法直接访问客户的内网设备（路由器、交换机、光猫等）。

本项目解决的核心问题：

- **穿透 NAT**：通过蜂窝网络建立反向隧道让工程师远程访问客户设备所在的局域网，并且这个局域网可能存在故障例如无法上网
- **零配置连接**：客户只需运行 Android App，无需路由器配置
- **安全隔离**：通过 SOCKS5 代理和 JWT 认证确保访问可控

## 应用场景

**典型工作流**：

1. 客户发现网络故障，联系技术支持，客户被技术人员要求在 Android 设备上运行此 App，建立到云端的反向隧道
2. 工程师登录管理后台，通过配对码关联客户设备
3. 工程师通过 SOCKS5 代理访问客户局域网（如 192.168.1.100）
4. 完成远程协助后，会话自动过期

**目标用户**：IT 支持团队、网络运维工程师、远程技术服务提供商、网络运营商

## 核心功能

**Android 客户端**：

- VPN 模式捕获网络流量
- WebSocket 反向隧道连接云端
- MQTT 控制通道（心跳、指令）
- 配对码绑定机制

**Go 服务端**：

- REST API：工程师认证、配对码管理、会话令牌
- SOCKS5 代理：转发工程师请求到客户局域网
- WebSocket 隧道网关：维护设备长连接
- MQTT Broker：设备控制通道

## AI 代理工作指南

如果您在此项目上工作：

1. **执行契约**：首先阅读 [AGENTS.md](AGENTS.md) — 这是唯一的权威指南
2. **验证门禁**：
   - 构建：`android\gradlew.bat -p android assembleDebug --stacktrace --no-daemon`
   - 测试：`android\gradlew.bat -p android :app:testDebugUnitTest --stacktrace --no-daemon`
3. **代码入口**：[android/app/src/main/java/com/netproxy/gateway/](android/app/src/main/java/com/netproxy/gateway/)

***

## 仓库结构

```
android/                          # Gradle Android 项目
├── app/
│   ├── src/main/java/.../       # 运行时代码（事实来源）
│   ├── src/test/                # 单元测试
│   └── build.gradle.kts         # 应用级配置
├── build.gradle.kts             # 项目级配置
└── gradle/wrapper/              # Gradle 8.13（固定版本）

server/                           # Go 服务端组件
├── api/                         # REST API（Gin + JWT）
├── socks5-proxy/                # SOCKS5 代理服务
├── tunnel/                      # WebSocket 隧道网关
└── docker-compose.yml           # 容器编排

docs/
├── ISSUES.md                    # 待修复问题清单
└── DECISIONS.md                 # 架构决策记录
```

***

## 服务端组件概览

| 组件          | 端口        | 框架/协议     | 功能               |
| ----------- | --------- | --------- | ---------------- |
| REST API    | 8080      | Gin + JWT | 工程师登录、配对码管理、会话令牌 |
| SOCKS5 代理   | 1080      | go-socks5 | 支持认证、RFC1918 过滤  |
| 隧道网关        | 8443      | WebSocket | 设备反向隧道、心跳检测      |
| MQTT Broker | 1883/8883 | TCP/TLS   | 设备控制通道           |

***

## 文档策略

此仓库仅保留会直接改变 AI 执行决策的文档。

**保留规则**：

1. 此文档是否改变了 AI 代理的下一步行动？→ **保留它**
2. 它是否也在代码或测试中准确记录了？→ **删除它并引用代码**
3. 它是已存在于 git 中的过去决策记录吗？→ **删除它**
4. 它反映的是理想化（而非实际）状态吗？→ **删除它**

**需要做决策？** → [AGENTS.md](AGENTS.md)

***

## 当前范围

- 仓库包含 Android 客户端和 Go 服务端组件
- 服务端实现：REST API、SOCKS5 代理、WebSocket 隧道网关
- 主要目标：稳定 Android 行为和测试覆盖率


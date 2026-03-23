# 技术选型文档

## 13.1 移动端技术栈

### 13.1.1 基础技术

| 组件 | 技术选型 | 版本 |
|------|---------|------|
| 语言 | Kotlin | JDK 17 |
| 最小 SDK | Android 8.0 | API 26 |
| 目标 SDK | Android 14 | API 34 |
| UI 框架 | Jetpack Compose | Material Design 3 |

### 13.1.2 网络与通信

| 组件 | 技术选型 | 版本 |
|------|---------|------|
| HTTP 客户端 | OkHttp | 4.12.0 |
| REST 客户端 | Retrofit | 2.9.0 |
| MQTT 客户端 | Eclipse Paho | 1.2.5 |
| WebSocket | OkHttp WebSocket | 4.12.0 |

### 13.1.3 核心功能

| 组件 | 技术选型 | 版本 |
|------|---------|------|
| VPN | Android VpnService | 系统 API |
| SOCKS5 代理 | Netty | 4.1.100.Final |
| 依赖注入 | Hilt | 2.50 |
| 异步编程 | Kotlin Coroutines | 1.7.3 |

### 13.1.4 数据存储

| 组件 | 技术选型 | 说明 |
|------|---------|------|
| 偏好存储 | DataStore | 现代偏好存储 |
| 数据库 | Room | SQLite 封装 |

## 13.2 服务端技术栈（v1 已确定）

### 13.2.1 推荐方案

| 组件 | 推荐选型 |
|------|---------|
| MQTT Broker | EMQX / Mosquitto |
| SOCKS5 服务 | Go + SOCKS5 库（如 things-go/go-socks5） |
| REST API | Go + Gin |
| 数据库 | PostgreSQL / MySQL |

说明：v1 以 Go 方案为主，后续版本可按团队能力评估替代实现。

## 13.3 开发工具

| 工具 | 用途 |
|-----|------|
| Android Studio | Android 开发 |
| Postman | API 测试 |
| MQTT Explorer | MQTT 调试 |
| Wireshark | 网络抓包 |

---

## 相关文档

- [系统架构](./02-architecture.md) - 了解架构设计
- [服务端设计](./08-server-design.md) - 了解服务端架构
- [版本规划](./12-version-plan.md) - 了解发布计划

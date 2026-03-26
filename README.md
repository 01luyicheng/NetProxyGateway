# NetProxyGateway

**AI 优先的 Android 项目** — 为远程网络协助提供可靠的 Android 行为。

---

## 🎯 AI 代理工作指南

如果您是 AI 代理并在此项目上工作：

1. **执行权限**：首先阅读 [CLAUDE.md](CLAUDE.md) — 这是唯一的权威指南
2. **当前范围**：Android 客户端 + Go 服务端
3. **快速命令**：
   - 构建：`android\gradlew.bat -p android assembleDebug --stacktrace --no-daemon`
   - 测试：`android\gradlew.bat -p android :app:testDebugUnitTest --stacktrace --no-daemon`
4. **代码入口**：[android/app/src/main/java/com/netproxy/gateway/](android/app/src/main/java/com/netproxy/gateway/)

---

## 📂 仓库结构

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
├── CLAUDE.md                    # 执行契约（必读）
└── ISSUES.md                    # 待修复问题清单
```

---

## 🔧 构建与验证

在 Windows 上运行（确保可复现性）：

```bash
# 构建
android\gradlew.bat -p android assembleDebug --stacktrace --no-daemon

# 单元测试（验证门禁）
android\gradlew.bat -p android :app:testDebugUnitTest --stacktrace --no-daemon

# IDE：导入 `android/` 文件夹到 Android Studio
```

如果本地构建因 Gradle 版本错误失败，使用固定的 wrapper（不要使用系统 Gradle）。

---

## 🖥️ 服务端组件

NetProxyGateway 服务端提供远程网络协助的云服务基础设施。

### 架构概览

```
┌─────────────────────────────────────────────────────────────┐
│                      云服务器                               │
├─────────────────────────────────────────────────────────────┤
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐        │
│  │ MQTT Broker │  │ SOCKS5代理  │  │  REST API   │        │
│  │  (控制通道) │  │  (数据通道) │  │  (管理)     │        │
│  └─────────────┘  └─────────────┘  └─────────────┘        │
│         │                │                │                 │
│         └────────────────┼────────────────┘                 │
│                          ▼                                  │
│                   ┌─────────────┐                          │
│                   │  隧道网关   │                          │
│                   └─────────────┘                          │
└─────────────────────────────────────────────────────────────┘
```

### 组件说明

| 组件 | 端口 | 框架/协议 | 功能 |
|------|------|-----------|------|
| REST API | 8080 | Gin + JWT | 工程师登录、配对码管理、会话令牌 |
| SOCKS5 代理 | 1080 | go-socks5 | 支持认证、RFC1918过滤、可选TLS |
| 隧道网关 | 8443 | WebSocket | 设备反向隧道、心跳检测 |
| MQTT Broker | 1883/8883 | TCP/TLS | 设备控制通道 |

### 快速开始

**环境要求**：Docker 20.10+, Docker Compose 2.0+

```bash
cd server/

# 创建环境变量文件 .env
# JWT_SECRET=your-secret-key-here
# ADMIN_USER=admin
# ADMIN_PASS=your-secure-password

# 启动所有服务
docker-compose up -d

# 查看状态
docker-compose ps
```

### API 接口示例

**工程师登录**：
```bash
POST /api/login
Content-Type: application/json

{
  "username": "admin",
  "password": "your-password"
}
```

**创建配对会话**：
```bash
POST /api/pair
Authorization: Bearer <token>
Content-Type: application/json

{
  "device_id": "device-123"
}
```

**使用 SOCKS5 代理**：
```bash
curl --socks5 deviceID:session-token@localhost:1080 http://192.168.1.1
```

---

## 🛡️ 安全特性

- **认证与授权**：JWT Token、短期会话令牌（15分钟）、一次性配对码
- **网络安全**：RFC1918 私有地址限制、特殊地址拒绝、可选 TLS 加密
- **限流与防护**：IP 级别限流、登录失败封禁（15分钟）

---

## 📝 文档策略

此仓库特意保持最少的文档。只保留会直接改变 AI 执行决策的文档。

**保留规则**：
1. 此文档是否改变了 AI 代理的下一步行动？→ **保留它**
2. 它是否也在代码或测试中准确记录了？→ **删除它并引用代码**
3. 它是已存在于 git 中的过去决策记录吗？→ **删除它**
4. 它反映的是理想化（而非实际）状态吗？→ **删除它**

**需要做决策？** → [CLAUDE.md](CLAUDE.md)

---

## 📋 当前范围

- 仓库包含 Android 客户端和 Go 服务端组件
- 服务端实现：REST API、SOCKS5 代理、WebSocket 隧道网关
- 主要目标：稳定 Android 行为和测试覆盖率

---

## 📄 许可证

MIT — 参见 [LICENSE](LICENSE)

---

**最后更新**：2026-03-26 | **状态**：稳定

# CLAUDE.md - NetProxyGateway Claude Code 开发指南

> 本文件是 Claude Code 的项目入口指令。每个模块的 Claude Code 实例都应遵循此文件的指导。

## 项目概览

**项目名称**: NetProxyGateway - 远程网络协助网关  
**目标**: 创建 Android 移动应用 + 云服务端，实现双通道（蜂窝+WiFi）分流远程网络协助

**核心特性**:
- 双通道连接：同时使用 WiFi 和蜂窝网络，互为备份
- 智能分流：云服务器IP走蜂窝通道，内网流量走WiFi通道
- 远程内网访问：工程师可通过手机代理访问客户内网
- 远程WiFi控制：可远程查看/连接客户手机的WiFi

---

## 技术栈

| 组件 | 技术 |
|------|------|
| 移动端 | Kotlin, Jetpack Compose, Hilt, Coroutines |
| 网络 | OkHttp, MQTT (Eclipse Paho), VpnService |
| 代理 | Netty (SOCKS5) |
| 最小SDK | Android 8.0 (API 26) |
| 目标SDK | Android 14 (API 34) |

---

## 项目结构

```
NetProxyGateway/
├── android/                    # Android 移动端
│   ├── app/                   # 应用模块
│   │   └── src/main/
│   │       ├── java/com/netproxy/gateway/
│   │       │   ├── connection/   # 网络连接管理 (Task 3A)
│   │       │   ├── vpn/          # VPN服务 (Task 3B)
│   │       │   ├── proxy/        # SOCKS5代理 (Task 3C)
│   │       │   ├── wifi/         # WiFi控制 (Task 3D)
│   │       │   ├── ui/           # 界面 (Task 3E)
│   │       │   └── di/           # 依赖注入
│   │       └── res/
│   └── build.gradle.kts
├── server/                     # 云服务端
│   ├── mqtt-broker/          # MQTT配置
│   ├── socks5-proxy/         # SOCKS5中转服务
│   └── api/                  # REST API
├── docs/                      # 文档
│   ├── modules/              # 模块化文档
│   ├── plans/                # 实现计划
│   │   └── android/          # Android 实现任务
│   │   └── server/           # Server 实现任务
│   └── CLAUDE.md             # 本文件
└── SPEC.md                   # 技术规范
```

---

## 开发模式

### 分布式开发指南

**重要**: 本项目采用多实例并行开发模式。每个模块由独立的 Claude Code 实例处理。

#### 模块分配

| 模块 | 目录 | 负责内容 |
|------|------|---------|
| Task 1 | 项目初始化 | Gradle wrapper, build.gradle.kts, 基础结构 |
| Task 2 | 核心应用 | Application, MainActivity, Theme |
| Task 3A | 连接管理 | NetworkStateManager, MqttConnectionManager |
| Task 3B | VPN服务 | VpnService 实现 |
| Task 3C | 代理服务 | SOCKS5 Proxy Server |
| Task 3D | WiFi控制 | WifiManager |
| Task 3E | UI界面 | Compose 界面 |
| Task 4 | 服务端 | MQTT Broker, SOCKS5中转, REST API |

#### 协作规则

1. **依赖管理**: 每个模块独立开发，但需声明依赖关系
2. **接口定义**: 模块间通过接口通信，接口定义在独立文件
3. **进度同步**: 通过 git 同步，但避免冲突
4. **集成测试**: Task 完成后进行模块集成

---

## 开发命令

### Android 构建

```bash
# 进入 Android 目录
cd android

# 同步依赖
./gradlew --stop
./gradlew clean
./gradlew assembleDebug

# 运行测试
./gradlew test
./gradlew testDebugUnitTest

# 检查代码
./gradlew lint
./gradlew ktlintCheck
```

### 服务端运行 (待实现)

```bash
# 运行 MQTT Broker (Docker)
docker run -d -p 1883:1883 -p 9001:9001 emqx/emqx

# 运行 SOCKS5 代理
cd server/socks5-proxy && go run main.go

# 运行 REST API
cd server/api && go run main.go
```

---

## 验证要求

### 代码质量标准

- ✅ **编译通过**: `./gradlew assembleDebug` 无错误
- ✅ **Lint通过**: `./gradlew lint` 无 error
- ✅ **测试通过**: `./gradlew test` 全部通过
- ✅ **架构合规**: 遵循模块化结构，模块间通过接口通信

### 功能验证

每个任务完成后需验证:
1. 代码编译成功
2. 单元测试通过 (如有)
3. 模块内部逻辑自洽
4. 符合 SPEC.md 中的设计规范

---

## 模块间依赖

```
Task 1 (基础)
    ↓
Task 2 (核心应用) ← Task 1
    ↓
├── Task 3A (连接) ← Task 2
├── Task 3B (VPN) ← Task 2
├── Task 3C (代理) ← Task 2
├── Task 3D (WiFi) ← Task 2
└── Task 3E (UI) ← Task 2, 3A, 3B, 3C, 3D
    ↓
Task 4 (服务端) ← 无依赖 (可并行)
```

**注意**: Task 3E (UI) 依赖所有其他任务，需在其他任务完成后集成。

---

## 重要提示

1. **不要修改其他模块的代码** - 保持模块隔离
2. **接口变更需同步** - 如需修改接口，通知相关模块负责人
3. **提交前验证** - 确保本地编译通过后再提交
4. **详细日志** - 实现时添加足够日志便于调试

---

## 快速开始

1. 阅读 SPEC.md 了解项目全貌
2. 查看 docs/plans/android/ 下的任务文件
3. 选择你的任务模块开始开发
4. 完成后提交 PR 进行集成

---

## 联系方式

项目维护者: [待定]  
讨论群: [待定]

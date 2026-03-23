# NetProxyGateway Android App

Android端实现，提供远程网络协助功能。

## 📂 项目结构

```
android/
├── app/
│   ├── src/main/
│   │   ├── java/com/netproxy/gateway/
│   │   │   ├── connection/       # 网络连接管理 - 双网卡检测、MQTT连接
│   │   │   ├── vpn/              # VPN服务 - VpnService实现
│   │   │   ├── proxy/            # SOCKS5代理 - 代理服务、流量转发
│   │   │   ├── wifi/             # WiFi控制 - WiFi扫描、连接管理
│   │   │   ├── ui/               # 用户界面 - Compose界面、ViewModel
│   │   │   └── di/               # 依赖注入 - Hilt模块
│   │   └── res/                # 资源文件
│   └── build.gradle.kts
├── gradle/
└── README.md           # 本文件
```

## 🎯 模块说明

### 1. Core Module (核心模块)
**职责**：基础架构、依赖注入、工具类
**主要文件**：
- `NetworkStateManager.kt` - 网络状态管理
- `AppModule.kt` - 依赖注入配置
- `Database.kt` - 数据库操作

### 2. Communication Module (通信模块)
**职责**：MQTT通信、协议处理
**主要文件**：
- `MqttConnectionManager.kt` - MQTT连接管理
- `Protocol.kt` - 协议定义
- `MessageHandler.kt` - 消息处理

### 3. Network Module (网络模块)
**职责**：VPN服务、SOCKS5代理
**主要文件**：
- `VpnService.kt` - VPN服务实现
- `Socks5ProxyService.kt` - SOCKS5代理服务
- `TrafficForwarder.kt` - 流量转发

### 4. WiFi Module (WiFi模块)
**职责**：WiFi扫描、连接管理
**主要文件**：
- `WifiManager.kt` - WiFi管理器
- `WifiScanner.kt` - WiFi扫描
- `WifiConnector.kt` - WiFi连接

### 5. UI Module (UI模块)
**职责**：界面展示、用户交互
**主要文件**：
- `MainActivity.kt` - 主Activity
- `MainScreen.kt` - 主界面
- `ViewModel.kt` - 状态管理

### 6. Config Module (配置模块)
**职责**：配置管理、权限处理
**主要文件**：
- `ConfigManager.kt` - 配置管理
- `PermissionManager.kt` - 权限管理
- `VersionManager.kt` - 版本管理

## 🔧 技术栈

- **UI**: Jetpack Compose + Material Design 3
- **架构**: MVVM + Hilt依赖注入
- **异步**: Kotlin Coroutines + Flow
- **网络**: OkHttp + MQTT (Eclipse Paho)
- **代理**: Netty (SOCKS5)
- **VPN**: Android VpnService

## 📋 构建要求

- Android Studio Hedgehog | 2023.1.1+
- JDK 17
- Android 8.0+ (API 26) 设备

## 🚀 构建步骤

1. 打开Android项目
2. 同步Gradle依赖
3. 连接Android设备或启动模拟器
4. 运行应用

## 📝 权限说明

应用需要以下权限：
- 网络访问权限
- WiFi状态访问
- VPN服务权限
- 位置权限（WiFi扫描必需）

详细说明见 [SPEC.md#8-权限需求](../SPEC.md#8-权限需求)

## 🤝 贡献

欢迎贡献！请阅读 [贡献指南](../CONTRIBUTING.md) 了解详情。
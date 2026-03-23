# NetProxyGateway - 远程网络协助网关

## 1. 项目概述

### 1.1 背景与问题

网络工程师在远程协助客户时，客户网络往往存在故障，导致传统VPN方案失效。本项目通过双通道分流设计，利用手机的双网卡特性，同时连接WiFi和蜂窝网络，通过蜂窝网络建立与云服务器的控制通道，确保在客户WiFi网络故障时仍能远程调试。

### 1.2 核心功能

| 功能 | 描述 |
|-----|------|
| 双通道连接 | 同时使用WiFi和蜂窝网络，互为备份 |
| 智能分流 | 云服务器IP走蜂窝通道（控制通道），内网流量走WiFi通道（数据通道） |
| 远程内网访问 | 工程师可通过手机代理访问客户内网 |
| 远程WiFi控制 | 可远程查看/连接客户手机的WiFi |

### 1.3 目标用户

- 网络工程师/IT运维人员
- 远程技术支持人员

---

## 2. 系统架构

### 2.1 网络拓扑

```
┌─────────────────────────────────────────────────────────────────────┐
│                         客户手机端                                    │
│  ┌─────────────┐    ┌─────────────┐    ┌─────────────┐          │
│  │  云服务器   │◄──►│  控制通道   │◄──►│  蜂窝网络   │          │
│  │  (MQTT)    │    │  (MQTT/WS)  │    │  4G/5G     │          │
│  └─────────────┘    └─────────────┘    └─────────────┘          │
│         │                                        │                 │
│         │         ┌─────────────┐                │                 │
│         └────────►│  分流引擎   │◄───────────────┘                 │
│                   └─────────────┘                                  │
│                     │         │                                     │
│              ┌──────┘         └──────┐                           │
│              ▼                         ▼                          │
│     ┌─────────────┐          ┌─────────────┐                    │
│     │  数据通道   │          │  WiFi通道   │                    │
│     │  (SOCKS5)  │          │  (内网直连)  │                    │
│     └─────────────┘          └─────────────┘                    │
└─────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────┐
│                         工程师端                                    │
│  ┌─────────────┐    ┌─────────────┐    ┌─────────────┐          │
│  │  云服务器   │    │  SOCKS5     │    │  内网设备   │          │
│  │  (中转)    │◄──►│  客户端     │◄──►│  (路由器)   │          │
│  └─────────────┘    └─────────────┘    └─────────────┘          │
└─────────────────────────────────────────────────────────────────────┘
```

### 2.2 分流策略

| 流量类型 | 路径 | 说明 |
|---------|------|------|
| 云服务器通信 | 蜂窝网络 | 控制通道，永不断线 |
| 客户内网访问 | WiFi网络 | 数据通道，访问内网设备 |
| DNS查询 | 智能选择 | 内网DNS走WiFi，公网DNS走蜂窝 |

### 2.3 技术架构

```
┌─────────────────────────────────────────────────────────────┐
│                      移动端 (Android)                        │
├─────────────────────────────────────────────────────────────┤
│  UI层     │  Jetpack Compose + Material Design 3           │
├─────────────────────────────────────────────────────────────┤
│  业务层   │  Hilt 依赖注入 / Kotlin Coroutines + Flow      │
├─────────────────────────────────────────────────────────────┤
│  网络层   │  OkHttp │ MQTT (Eclipse Paho) │ VpnService     │
├─────────────────────────────────────────────────────────────┤
│  代理层   │  Netty (SOCKS5)                               │
├─────────────────────────────────────────────────────────────┤
│  系统层   │  Android 8.0+ (API 26)                        │
└─────────────────────────────────────────────────────────────┘
```

---

## 3. 功能模块

### 3.1 连接管理模块

#### 3.1.1 双网卡检测
- 实时检测WiFi和蜂窝网络状态（ConnectivityManager API）
- 监听网络切换事件（NetworkCallback）
- 检测网络质量（延迟、丢包）- 可选优化

#### 3.1.2 控制通道
- 通过蜂窝网络MQTT保持与云服务器的长连接
- 心跳保活：30秒间隔
- 自动重连：指数退避（5s → 60s）

#### 3.1.3 分流规则引擎
- 基于IP段的分流策略（内置路由表）
- 动态规则更新（通过MQTT下发）
- Android 13+ 支持 excludeRoute 硬件分流

### 3.2 VPN模块

#### 3.2.1 本地VPN服务
- 创建TUN虚拟接口
- 捕获所有流量
- split tunneling（Android 13+）

#### 3.2.2 流量转发
- 内网流量 → WiFi网卡直连
- 外网流量 → SOCKS5代理（经云服务器中转）

### 3.3 代理模块

#### 3.3.1 云端 SOCKS5 入口（v1 主路径）
- 工程师连接云端 SOCKS5 入口（加密链路）
- 服务端通过设备反向隧道转发到设备侧
- v1 仅承诺 CONNECT（TCP/IPv4）

#### 3.3.2 移动端本地 SOCKS5（可选/调试）
- 可用于本地联调，不作为 v1 生产主路径
- 若启用需与 VPN/分流策略隔离，避免回环

#### 3.3.3 云中转服务（服务端）
- SOCKS5 代理
- 流量统计
- 连接管理

### 3.4 WiFi控制模块

#### 3.4.1 WiFi扫描
- 获取周围WiFi列表
- 信号强度、加密类型

#### 3.4.2 WiFi连接
- 配置并连接指定WiFi
- 保存WiFi配置（Android系统）

### 3.5 配对模块

#### 3.5.1 识别码配对
- 客户端生成6位数字识别码
- 服务器端配对验证
- 会话管理

---

## 4. 通信协议

### 4.1 MQTT 主题结构

```
device/{deviceId}/status      // 设备状态上报
device/{deviceId}/control     // 工程师控制指令
device/{deviceId}/heartbeat   // 心跳
device/{deviceId}/response    // 操作响应
```

### 4.2 控制指令格式

```json
{
  "cmd": "start_vpn",
  "params": {},
  "timestamp": 1699999999999
}
```

| 指令 | 描述 |
|-----|------|
| start_vpn | 启动VPN隧道 |
| stop_vpn | 停止VPN隧道 |
| scan_wifi | 扫描WiFi |
| connect_wifi | 连接指定WiFi |
| get_status | 获取设备状态 |

### 4.3 响应格式

```json
{
  "success": true,
  "data": {},
  "error": null
}
```

---

## 5. 服务端设计

### 5.1 组件架构

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
│                   │  会话管理   │                          │
│                   └─────────────┘                          │
└─────────────────────────────────────────────────────────────┘
```

### 5.2 MQTT Broker

- 推荐：EMQX、EMQ X、或 Mosquitto
- 端口：1883（TCP）、8883（TLS）
- 认证：用户名/密码或JWT Token
- QoS：控制指令 QoS 1

### 5.3 SOCKS5 中转服务

- 端口：1080
- 认证：与设备配对码关联
- 流量统计：记录连接时长、流量大小

### 5.4 REST API

| 接口 | 方法 | 描述 |
|-----|------|------|
| /api/pair | POST | 创建配对会话 |
| /api/pair/{code} | GET | 验证配对码 |
| /api/device/{id}/status | GET | 获取设备状态 |

---

## 6. 安全性设计

### 6.1 通信安全

- 对公网通信强制 TLS（建议 TLS 1.2+，优先 TLS 1.3）
- MQTT over TLS (8883端口)
- SOCKS5 over TLS
- 证书固定（Certificate Pinning）为增强项，按威胁模型启用

### 6.2 访问控制

- 每次会话生成唯一会话密钥
- 设备认证Token
- 操作审计日志

### 6.3 数据安全

- WiFi密码使用 Android Keystore 加密存储
- 敏感数据内存安全（使用后及时清理）
- 不在日志中输出敏感信息

---

## 7. 错误处理

| 错误场景 | 处理策略 |
|---------|---------|
| 蜂窝网络断开 | 尝试切换到WiFi作为备用控制通道 |
| WiFi网络断开 | 保持蜂窝控制通道，内网访问暂时不可用 |
| 云服务器不可达 | 本地缓存策略，本地日志记录 |
| VPN启动失败 | 提示用户检查权限，重试机制 |
| 会话超时 | 自动重连，通知工程师 |

---

## 8. 权限需求

### 8.1 Android 权限清单

```xml
<!-- 网络权限 -->
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.CHANGE_WIFI_STATE" />
<uses-permission android:name="android.permission.CHANGE_NETWORK_STATE" />

<!-- 位置权限（WiFi扫描必需，Android 8.0+） -->
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />

<!-- VPN权限 -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />

<!-- 通知权限（Android 13+） -->
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

<!-- 附近设备权限（Android 13+） -->
<uses-permission android:name="android.permission.NEARBY_WIFI_DEVICES" />

<!-- 唤醒锁（保持服务运行） -->
<uses-permission android:name="android.permission.WAKE_LOCK" />
```

### 8.2 权限说明

| 权限 | 用途 |
|-----|------|
| INTERNET | MQTT、SOCKS5通信 |
| ACCESS_NETWORK_STATE | 检测网络状态 |
| ACCESS_WIFI_STATE | 获取WiFi信息 |
| CHANGE_WIFI_STATE | 连接/断开WiFi |
| CHANGE_NETWORK_STATE | 切换网络 |
| ACCESS_FINE_LOCATION | WiFi扫描（系统要求） |
| ACCESS_COARSE_LOCATION | WiFi扫描（系统要求） |
| FOREGROUND_SERVICE | 后台服务运行 |
| FOREGROUND_SERVICE_SPECIAL_USE | VPN/代理专用前台服务 |
| POST_NOTIFICATIONS | Android 13+ 通知 |
| NEARBY_WIFI_DEVICES | Android 13+ WiFi扫描 |
| WAKE_LOCK | 保持服务运行 |

---

## 9. 性能目标

| 指标 | 目标值 |
|------|-------|
| 控制通道延迟 | < 500ms |
| 内网访问延迟 | < 100ms (本地网络) |
| 流量转发吞吐量 | > 50Mbps |
| 电池消耗 | < 5%/小时（后台运行） |
| 内存占用 | < 100MB |

---

## 10. 版本规划

### v1.0.0 (MVP)
- 双通道连接（蜂窝+WiFi）
- 基础分流规则
- 远程内网访问
- 识别码配对
- 基础WiFi控制

### v1.1.0
- 完善错误处理
- 性能优化
- 流量统计

### v2.0.0
- 多平台支持（iOS基础版）
- 企业功能（团队管理）
- 高级分流规则

---

## 11. 技术选型

| 组件 | 技术选型 | 版本 |
|------|---------|------|
| 移动端框架 | Kotlin + Jetpack Compose | JDK 17 |
| 最小SDK | Android 8.0 (API 26) | - |
| 目标SDK | Android 14 (API 34) | - |
| 网络库 | OkHttp + Retrofit | 4.12.0 |
| MQTT | Eclipse Paho | 1.2.5 |
| VPN | Android VpnService | 系统API |
| 代理 | Netty | 4.1.100.Final |
| 依赖注入 | Hilt | 2.50 |
| 异步 | Kotlin Coroutines | 1.7.3 |
| 存储 | DataStore + Room | - |
| UI | Material Design 3 | - |

---

## 12. 目录结构

```
NetProxyGateway/
├── android/                          # Android 移动端
│   ├── app/
│   │   └── src/main/
│   │       ├── java/com/netproxy/gateway/
│   │       │   ├── connection/       # 网络连接管理
│   │       │   ├── vpn/              # VPN服务
│   │       │   ├── proxy/            # SOCKS5代理
│   │       │   ├── wifi/             # WiFi控制
│   │       │   ├── ui/               # 界面
│   │       │   ├── di/               # 依赖注入
│   │       │   └── NetProxyApp.kt
│   │       └── res/
│   ├── build.gradle.kts
│   └── gradle/
├── server/                           # 云服务端（待实现）
│   ├── mqtt-broker/                  # MQTT配置
│   ├── socks5-proxy/                 # SOCKS5中转服务
│   └── api/                          # REST API
├── docs/
│   └── plans/
└── SPEC.md
```

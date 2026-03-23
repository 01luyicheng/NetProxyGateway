# NetProxyGateway - 远程网络协助网关

网络工程师在远程协助客户时，客户网络往往存在故障，导致传统VPN方案失效。
本项目通过双通道分流设计，利用手机的双网卡特性，同时连接WiFi和蜂窝网络。

## ✨ 功能特性

- **双通道连接**：同时使用WiFi和蜂窝网络，互为备份
- **智能分流**：云服务器IP走蜂窝通道，内网流量走WiFi通道
- **远程内网访问**：工程师可通过手机代理访问客户内网
- **远程WiFi控制**：可远程查看/连接客户手机的WiFi

## 🚀 快速开始

### 前置要求
- Android Studio Hedgehog | 2023.1.1+
- JDK 17
- Android 8.0+ (API 26) 设备

### 构建步骤
1. 克隆仓库
2. 打开Android项目
3. 同步Gradle依赖
4. 运行应用

## 📚 文档

### 🔴 重要公告：项目审查完成

**项目状态**: 关键设计文档已补齐，进入 **v1 条件性可开工** 状态。  
**建议**: 先完成文档口径收敛与安全默认值修订（0.5-1 天），随后启动 v1 开发。

**👉 请按以下优先级阅读**:

1. **[📋 审查文档导航](docs/REVIEW_INDEX.md)** ⭐ 从这里开始！
   - 快速了解各文档的用途和阅读顺序
   - 选择你的角色，找到对应的文档

2. **[👔 管理层阅读](docs/EXECUTIVE_SUMMARY.md)** - 5 分钟执行总结
   - 项目状态：v1 条件性可开工，v2 继续 POC 验证
   - 投入产出比：先统一口径可避免后期返工（ROI 明显）

3. **[🚀 现在就做](docs/QUICK_ACTION_GUIDE.md)** - 立即行动指南
   - 现在可以启动的 Task（Task 1,2,3D,3E,4）
   - 需要等待的 Task（Task 3A,3B,3C）
   - 时间表和进度跟踪

4. **[📊 完整审查报告](docs/REVIEW_REPORT.md)** - 详细技术分析
   - 历史审查问题与修复进展（含时间线）
   - 风险识别、可行性评估

### 关键设计文档（已补齐）

| 文档 | 状态 | 优先级 |
|------|------|--------|
| [流量分流规则引擎](docs/modules/traffic-split-engine.md) | 已补齐（v2 规范） | ✅ P0 |
| [VPN + SOCKS5 集成](docs/modules/integration-vpn-socks5.md) | 已补齐（v2 规范） | ✅ P0 |
| [服务端 SOCKS5 中转](docs/modules/server-socks5-relay.md) | 已补齐（v1 主链路） | ✅ P0 |

### 其他文档

- [技术规范](SPEC.md) - 详细的技术设计文档
- [实现计划](docs/plans/2026-03-09-NetProxyGateway-implementation-plan.md) - 开发步骤
- [Android项目说明](android/README.md) - Android端开发指南

## 🏗️ 项目架构

```
NetProxyGateway/
├── android/                 # Android移动端
│   ├── app/                # 应用代码
│   │   ├── src/main/
│   │   │   ├── java/com/netproxy/gateway/
│   │   │   │   ├── connection/       # 网络连接管理
│   │   │   │   ├── vpn/              # VPN服务
│   │   │   │   ├── proxy/            # SOCKS5代理
│   │   │   │   ├── wifi/             # WiFi控制
│   │   │   │   ├── ui/               # 界面
│   │   │   │   ├── di/               # 依赖注入
│   │   │   │   └── NetProxyApp.kt    # 应用入口
│   │   │   └── res/                # 资源文件
│   │   └── build.gradle.kts
│   └── README.md           # Android项目说明
├── server/                  # 云服务端（待实现）
└── docs/                    # 文档
```

## 🤝 贡献

欢迎贡献！请阅读 [贡献指南](CONTRIBUTING.md) 了解详情。

## 📄 许可证

MIT License - 详见 [LICENSE](LICENSE)
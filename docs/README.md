# 模块化文档索引

本文档是 NetProxyGateway 项目的模块化文档索引，将原有的 `SPEC.md` 拆分为独立的模块化文档，便于阅读、维护和团队协作。

## 📚 文档结构

```
docs/
├── modules/
│   ├── 01-overview.md           # 项目概述
│   ├── 02-architecture.md       # 系统架构
│   ├── 03-connection-module.md   # 连接管理模块
│   ├── 04-vpn-module.md         # VPN模块
│   ├── 05-proxy-module.md       # 代理模块
│   ├── 06-wifi-module.md        # WiFi控制模块
│   ├── 07-communication-protocol.md  # 通信协议
│   ├── 08-server-design.md     # 服务端设计
│   ├── 09-security.md          # 安全性设计
│   ├── 10-android-permissions.md # Android权限
│   ├── 11-performance.md        # 性能目标
│   ├── 12-version-plan.md       # 版本规划
│   └── 13-tech-stack.md        # 技术选型
├── plans/
│   ├── android/
│   │   ├── task-01-project-setup.md    # 项目初始化
│   │   ├── task-02-core-application.md # 核心应用
│   │   ├── task-03a-connection-module.md  # 连接管理
│   │   ├── task-03b-vpn-module.md      # VPN服务
│   │   ├── task-03c-proxy-module.md    # 代理服务
│   │   ├── task-03d-wifi-module.md    # WiFi控制
│   │   └── task-03e-ui-module.md      # 用户界面
│   └── server/
│       └── task-04-server-implementation.md # 云服务端
├── CLAUDE.md                    # Claude Code 开发指南
├── BUILD_VERIFICATION.md        # 构建验证命令
├── MODULE_ISOLATION.md          # 模块隔离指南
└── README.md                    # 本文件
```

## 🚀 Claude Code 开发

**重要**: 本项目采用多实例并行开发模式。每个模块由独立的 Claude Code 实例处理。

### 开发流程

1. **阅读 CLAUDE.md** - 项目根目录的 Claude Code 指令
2. **选择任务** - 在 plans/ 目录下选择对应的任务文件
3. **开始开发** - 按任务文件中的详细步骤实现
4. **验证提交** - 运行 BUILD_VERIFICATION.md 中的验证命令

### 任务分配

| 任务 | 文件 | 负责模块 |
|------|------|---------|
| Task 1 | plans/android/task-01-project-setup.md | 项目初始化 |
| Task 2 | plans/android/task-02-core-application.md | 核心应用 |
| Task 3A | plans/android/task-03a-connection-module.md | 连接管理 |
| Task 3B | plans/android/task-03b-vpn-module.md | VPN服务 |
| Task 3C | plans/android/task-03c-proxy-module.md | 代理服务 |
| Task 3D | plans/android/task-03d-wifi-module.md | WiFi控制 |
| Task 3E | plans/android/task-03e-ui-module.md | 用户界面 |
| Task 4 | plans/server/task-04-server-implementation.md | 云服务端 |

### 开发规范

- 遵循 MODULE_ISOLATION.md 中的隔离原则
- 提交前运行 BUILD_VERIFICATION.md 中的验证命令
- 更新 CHANGELOG.md 记录变更

## 📖 阅读指南

### 按角色

| 角色 | 推荐阅读 |
|-----|---------|
| 项目经理 | 01-overview, 12-version-plan, CLAUDE.md |
| 架构师 | 02-architecture, 08-server-design, MODULE_ISOLATION.md |
| Android 开发 | 03-connection-module, 04-vpn-module, 05-proxy-module, 06-wifi-module, 10-android-permissions |
| 后端开发 | 07-communication-protocol, 08-server-design, task-04-server-implementation.md |
| 安全工程师 | 09-security |
| 测试工程师 | 11-performance, BUILD_VERIFICATION.md |

### 按阶段

| 阶段 | 推荐阅读 |
|------|---------|
| 需求了解 | 01-overview, 02-architecture |
| 详细设计 | 03-connection-module ~ 10-android-permissions |
| 开发实现 | 对应 plans/ 下的任务文件 |
| 测试验收 | 11-performance, BUILD_VERIFICATION.md |

## 🔗 文档关系

```
CLAUDE.md (Claude Code 入口)
    │
    ├── MODULE_ISOLATION.md (协作规范)
    │
    ├── BUILD_VERIFICATION.md (验证命令)
    │
    └── plans/
            │
            ├── android/
            │   ├── task-01-project-setup.md
            │   │       │
            │   │       └── task-02-core-application.md
            │   │               │
            │   │               ├── task-03a-connection-module.md
            │   │               ├── task-03b-vpn-module.md
            │   │               ├── task-03c-proxy-module.md
            │   │               ├── task-03d-wifi-module.md
            │   │               │
            │   │               └── task-03e-ui-module.md (集成)
            │   │
            │   └── SPEC.md (技术规范)
            │
            └── server/
                └── task-04-server-implementation.md
```

## 📝 更新日志

- **2026-03-10**: 初始版本，将 SPEC.md 拆分为 13 个独立模块文档
- **2026-03-10**: 添加 Claude Code 开发支持文档 (CLAUDE.md, BUILD_VERIFICATION.md, MODULE_ISOLATION.md)
- **2026-03-10**: 添加实现计划任务文件 (plans/android/, plans/server/)

## 🤝 贡献

欢迎贡献！请阅读 [贡献指南](../CONTRIBUTING.md) 了解详情。

确保新增文档遵循本目录结构，并在本文档中添加索引。

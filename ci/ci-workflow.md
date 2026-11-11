# Android CI 工作流设计

## 架构

```
Windows 本地                          GitHub                    手机
    │                                    │                       │
    ├─ 写代码                            │                       │
    ├─ git commit (pre-commit hook)     │                       │
    │   └─ 只做 lint/format 检查         │                       │
    │                                    │                       │
    ├─ git push → CI 仓库                │                       │
    │                                    ├─ Actions 自动构建      │
    │                                    ├─ 运行测试              │
    │                                    ├─ 生成 APK             │
    │                                    │                       │
    ├─ gh run watch (监控构建)            │                       │
    │                                    │                       │
    ├─ gh run download (下载 APK) ───────┼───────────────────────┤
    │                                    │                       ├─ adb install
    │                                    │                       │
    └─ 永远不在本地跑 ./gradlew           │                       │
```

## 核心原则

1. **本地不安装 Gradle** — 彻底避免多 subagent 冲突
2. **Pre-commit 只做轻量检查** — lint、格式化、资源检查
3. **所有编译和测试在云端** — GitHub Actions 免费无限
4. **一键命令封装复杂性** — 用户只需输入简单别名

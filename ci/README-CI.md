# Android CI 工作流

## 核心理念

**本地永远不跑 Gradle，所有构建在 GitHub Actions 上完成。**

```
┌─────────────────────────────────────────────────────────────────┐
│                        Windows 本地                              │
├─────────────────────────────────────────────────────────────────┤
│  写代码 → pre-commit hook (lint/format) → git commit            │
│           ↓                                                     │
│      ci assembleDebug ← 一键命令，自动处理一切                    │
│           ↓                                                     │
│      自动 push 到 CI 仓库 → 触发 GitHub Actions                  │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                     GitHub Actions (云端)                        │
├─────────────────────────────────────────────────────────────────┤
│  checkout → setup JDK → setup Android SDK → ./gradlew assemble  │
│           ↓                                                     │
│      上传 APK 到 Artifacts                                      │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                        Windows 下载                              │
├─────────────────────────────────────────────────────────────────┤
│  ci -Watch (等待完成) → 自动下载 APK → adb install              │
└─────────────────────────────────────────────────────────────────┘
```

## 为什么这样做？

| 问题 | 解决方案 |
|------|----------|
| Claude Code 多 subagent 同时跑 Gradle 冲突 | 本地不装 Gradle，所有构建在云端 |
| Pre-commit hook 需要本地检查 | 只做轻量 lint，不编译 |
| Windows 16GB 内存不够 | GitHub Runner 有 7GB RAM + 14GB 磁盘 |
| 每次构建都要手动操作 | 一键命令封装所有复杂性 |
| 免费额度用完 | 公开仓库 Actions 完全免费 |

## 安装步骤

### 1. 在 Windows 上安装 GitHub CLI

```powershell
winget install GitHub.cli
gh auth login
```

### 2. 创建 CI 仓库

```powershell
# 在项目目录下运行
.\setup-ci-repo.ps1 -CIRepo "你的用户名/android-ci"
```

### 3. 安装 PowerShell 别名

```powershell
# 复制文件到用户目录
Copy-Item gradle-ci.ps1 $env:USERPROFILE\
Copy-Item setup-aliases.ps1 $env:USERPROFILE\

# 运行安装
.\setup-aliases.ps1

# 重启 PowerShell
```

### 4. 配置 Pre-commit Hook

```bash
# 在项目目录下
cp pre-commit-hook.sh .git/hooks/pre-commit
chmod +x .git/hooks/pre-commit
```

## 使用方法

### 基本构建

```powershell
# 构建 Debug APK
ci assembleDebug

# 构建 Release APK
ci assembleRelease

# 运行测试
ci testDebugUnitTest

# 运行 Lint
ci lintDebug
```

### 快捷命令

```powershell
ci-debug      # 快速构建 Debug
ci-release    # 快速构建 Release
ci-test       # 快速测试
ci-lint       # 快速 Lint
ci-install    # 构建并安装到手机
ci-status     # 查看构建状态
```

### 高级选项

```powershell
# 等待构建完成
ci assembleDebug -Watch

# 构建并安装
ci assembleDebug -Watch -Install

# 查看所有最近构建
ci -Status
```

## 工作流示例

### 日常开发

```powershell
# 1. 写代码
# ...

# 2. 提交（pre-commit hook 自动运行 lint）
git add .
git commit -m "feat: add new feature"

# 3. 构建（一键）
ci assembleDebug -Watch

# 4. 安装到手机
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Claude Code 集成

当 Claude Code 需要构建时，它会：
1. 修改代码
2. 运行 `git commit`（触发 pre-commit hook）
3. 运行 `ci assembleDebug`（自动推送并触发构建）
4. 等待构建完成
5. 下载并安装 APK

**多个 subagent 同时工作不会冲突，因为：**
- 本地不运行 Gradle
- 每个 subagent 的构建在独立的 GitHub Actions runner 上执行
- 文件同步通过 git 处理，避免冲突

## 故障排除

### 构建失败

```powershell
# 查看构建日志
gh run view <run-id> --repo 你的用户名/android-ci --log

# 重新运行失败的构建
gh run rerun <run-id> --repo 你的用户名/android-ci
```

### 权限问题

```powershell
# 确保 gh CLI 已登录
gh auth status

# 如果需要，重新登录
gh auth login
```

### Pre-commit Hook 不工作

```bash
# 检查 hook 是否可执行
ls -la .git/hooks/pre-commit

# 手动测试
.git/hooks/pre-commit
```

## 文件清单

| 文件 | 说明 |
|------|------|
| `gradle-ci.ps1` | 主构建脚本 |
| `setup-aliases.ps1` | PowerShell 别名安装 |
| `setup-ci-repo.ps1` | CI 仓库初始化 |
| `pre-commit-hook.sh` | Pre-commit 检查脚本 |
| `.github/workflows/android-ci.yml` | GitHub Actions 工作流 |

## 注意事项

1. **公开仓库**：CI 仓库必须是公开的，Actions 才能免费无限使用
2. **代码隐私**：如果你的代码是私有的，考虑使用 GitHub Pro 的私有仓库 + 付费 Actions
3. **首次构建**：第一次构建会比较慢（需要下载依赖），后续构建会利用缓存
4. **网络要求**：需要能访问 GitHub（可能需要代理）
5. **磁盘空间**：GitHub Runner 有 14GB 可用空间，大型项目可能需要优化

# Build Verification Commands - 构建验证命令

> 本文档定义了所有模块开发过程中需要使用的验证命令。

## Android 构建命令

### 基础构建

```bash
# 进入 Android 目录
cd android

# 清理构建
./gradlew clean

# 调试构建
./gradlew assembleDebug

# 发布构建
./gradlew assembleRelease
```

### 测试

```bash
# 运行单元测试
./gradlew test

# 运行调试单元测试
./gradlew testDebugUnitTest

# 运行 UI 测试
./gradlew connectedAndroidTest

# 生成测试报告
./gradlew testDebugUnitTestReport
```

### 代码质量

```bash
# 运行 Lint
./gradlew lint

# 运行 Lint 分析
./gradlew lintDebug

# 运行 KtLint (如配置)
./gradlew ktlintCheck
./gradlew ktlintFormat
```

### 依赖管理

```bash
# 查看依赖树
./gradlew dependencies

# 检查更新
./gradlew dependencyUpdates
```

### Gradle 任务

```bash
# 查看所有可用任务
./gradlew tasks

# 查看构建任务
./gradlew tasks --group build
```

## 验证检查清单

每次提交前必须验证:

- [ ] `./gradlew clean assembleDebug` 成功
- [ ] `./gradlew lint` 无 error (warning 可接受)
- [ ] `./gradlew testDebugUnitTest` 全部通过

## CI/CD 集成

如配置了 CI/CD，基础验证命令:

```bash
#!/bin/bash
set -e

cd android

# 1. 清理并构建
./gradlew clean assembleDebug

# 2. 运行测试
./gradlew testDebugUnitTest

# 3. 运行 Lint
./gradlew lintDebug
```

## 常见问题排查

### 构建失败

```bash
# 停止所有 Gradle 守护进程
./gradlew --stop

# 清理 Gradle 缓存
rm -rf ~/.gradle/caches

# 重新同步
./gradlew clean
./gradlew assembleDebug --refresh-dependencies
```

### 依赖冲突

```bash
# 查看依赖树找出冲突
./gradlew app:dependencies | grep <package-name>

# 强制使用特定版本
# 在 build.gradle.kts 中:
configurations.all {
    resolutionStrategy {
        force 'com.squareup.okhttp3:okhttp:4.12.0'
    }
}
```

### KtLint 失败

```bash
# 自动修复格式问题
./gradlew ktlintFormat

# 查看具体问题
./gradlew ktlintCheck --info
```

## 性能优化

```bash
# 并行构建
./gradlew assembleDebug --parallel

# 使用 daemon
./gradlew assembleDebug --daemon

# 使用缓存
./gradlew assembleDebug --build-cache

# 增量编译
./gradlew assembleDebug --incremental
```

## 快速验证脚本

创建 `verify-build.sh`:

```bash
#!/bin/bash
set -e

echo "=== Building NetProxyGateway ==="

cd android

echo "1. Cleaning..."
./gradlew clean

echo "2. Building debug APK..."
./gradlew assembleDebug

echo "3. Running unit tests..."
./gradlew testDebugUnitTest

echo "4. Running lint..."
./gradlew lintDebug || true

echo "=== Build verification complete ==="
echo "APK location: app/build/outputs/apk/debug/"
ls -la app/build/outputs/apk/debug/
```

运行:
```bash
chmod +x verify-build.sh
./verify-build.sh
```

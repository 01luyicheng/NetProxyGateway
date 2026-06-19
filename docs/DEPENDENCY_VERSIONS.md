# 项目依赖版本文档

本文档记录了从 Context7 获取的项目依赖最新版本信息，以及当前项目使用的版本对比。

**生成日期**: 2026-05-03

---

## Android 依赖

### Gradle 插件

| 依赖 | 当前版本 | 最新版本 | 状态 |
|------|----------|----------|------|
| Android Gradle Plugin | 8.13.2 | 8.13.2 | ✅ 最新 |
| Kotlin | 2.1.21 | 2.1.21 | ✅ 最新 |
| Jetpack Compose BOM | 2026.03.01 | 2026.03.01 | ✅ 最新 |
| Hilt | 2.58 | 2.58 | ✅ 最新 |
| KSP | 2.1.21-2.0.1 | 2.1.21-2.0.1 | ✅ 最新 |

### AndroidX 库

| 依赖 | 当前版本 | 最新版本 | 状态 |
|------|----------|----------|------|
| androidx.core:core-ktx | 1.17.0 | 1.17.0 | ✅ 最新 |
| androidx.lifecycle:lifecycle-runtime-ktx | 2.10.0 | 2.10.0 | ✅ 最新 |
| androidx.lifecycle:lifecycle-viewmodel-compose | 2.10.0 | 2.10.0 | ✅ 最新 |
| androidx.activity:activity-compose | 1.12.4 | 1.12.4 | ✅ 最新 |
| androidx.hilt:hilt-navigation-compose | 1.3.0 | 1.3.0 | ✅ 最新 |
| androidx.security:security-crypto | 1.1.0 | 1.1.0 | ✅ 最新 |

### 第三方库

| 依赖 | 当前版本 | 最新版本 | 状态 |
|------|----------|----------|------|
| Kotlinx Coroutines | 1.10.2 | 1.10.2 | ✅ 最新 |
| Eclipse Paho MQTT | 1.2.5 | 1.2.5 | ✅ 最新稳定版 |
| Netty | 4.2.12.Final | 4.2.12.Final | ✅ 最新 |
| SLF4J | 2.0.17 | 2.0.17 | ✅ 最新 |

### 测试依赖

| 依赖 | 当前版本 | 最新版本 | 状态 |
|------|----------|----------|------|
| JUnit | 4.13.2 | 4.13.2 | ✅ 最新 |
| MockK | 1.14.9 | 1.14.9 | ✅ 最新 |
| AndroidX Test Ext | 1.3.0 | 1.3.0 | ✅ 最新 |
| Espresso | 3.6.1 | 3.6.1 | ✅ 最新 |

---

## Go 依赖

| 模块 | 依赖 | 当前版本 | 最新版本 | 状态 |
|------|------|----------|----------|------|
| server/api | Go | 1.22 | 1.24 | ⚠️ 可升级 |
| server/api | gin-gonic/gin | 1.9.1 | 1.10.0 | ⚠️ 可升级 |
| server/api | golang-jwt/jwt | v5.2.0 | v5.2.2 | ⚠️ 可升级 |
| server/socks5-proxy | Go | 1.22 | 1.24 | ⚠️ 可升级 |
| server/socks5-proxy | gorilla/websocket | 1.5.3 | 1.5.3 | ✅ 最新 |
| server/tunnel | Go | 1.22 | 1.24 | ⚠️ 可升级 |
| server/tunnel | gorilla/websocket | 1.5.1 | 1.5.1 | ⚠️ 与socks5-proxy不一致 |

---

## 版本检查命令

### Android
```bash
# 检查依赖漏洞
make android-dep-check

# 查看依赖树
make android-build
# 或直接在 android 目录下运行：
# ./gradlew dependencies --configuration implementation
```

### Go
```bash
# 检查依赖更新
cd server/api && go list -u -m all
cd server/socks5-proxy && go list -u -m all
cd server/tunnel && go list -u -m all

# 更新依赖
go get -u ./...
go mod tidy
```

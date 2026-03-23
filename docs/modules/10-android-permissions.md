# Android 权限清单

## 10.1 权限清单

### 10.1.1 网络权限

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.CHANGE_WIFI_STATE" />
```

### 10.1.2 位置权限（WiFi 扫描必需）

```xml
<!-- Android 8.0+ -->
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
```

### 10.1.3 VPN 权限

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
```

### 10.1.4 附近设备权限（Android 13+）

```xml
<uses-permission android:name="android.permission.NEARBY_WIFI_DEVICES" />
```

## 10.2 权限说明

| 权限 | 用途 | 危险级别 |
|-----|------|---------|
| INTERNET | MQTT、SOCKS5通信 | 普通 |
| ACCESS_NETWORK_STATE | 检测网络状态 | 普通 |
| ACCESS_WIFI_STATE | 获取WiFi信息 | 普通 |
| CHANGE_WIFI_STATE | 连接/断开WiFi | 普通 |
| ACCESS_FINE_LOCATION | WiFi扫描（系统要求） | 危险 |
| FOREGROUND_SERVICE | VPN后台运行 | 普通 |
| NEARBY_WIFI_DEVICES | Android 13 WiFi扫描 | 危险 |

## 10.3 权限申请流程

### 10.3.1 运行时权限

对于危险权限，需要在运行时动态申请：

```kotlin
// 检查权限
fun checkPermissions(): List<String>

// 申请权限
fun requestPermissions(permissions: List<String>)

// 处理结果
fun onRequestPermissionsResult(permissions: Map<String, Boolean>)
```

### 10.3.2 权限清单

| 权限 | 申请时机 |
|-----|---------|
| ACCESS_FINE_LOCATION | 首次使用 WiFi 扫描功能 |
| NEARBY_WIFI_DEVICES | Android 13+ 首次使用 WiFi 功能 |
| FOREGROUND_SERVICE | 首次启动 VPN 服务 |

---

## 相关文档

- [WiFi模块](./06-wifi-module.md) - 了解 WiFi 功能
- [VPN模块](./04-vpn-module.md) - 了解 VPN 功能
- [错误处理](./04-vpn-module.md) - 了解权限相关错误处理

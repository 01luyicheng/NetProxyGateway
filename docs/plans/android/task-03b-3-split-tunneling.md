# Task 3B-3: Split Tunneling 实现

> **任务级别**: 高级任务  
> **前置依赖**: Task 3B-2 (VpnService 核心)  
> **后续任务**: Task 3E (UI)  
> **预计工作量**: 2 小时

## 任务目标

实现基于 IP 范围的分流逻辑，使云服务器 IP 走蜂窝通道，内网流量走 WiFi 通道。

## 交付物

1. `android/app/src/main/java/com/netproxy/gateway/vpn/VpnManager.kt` - VPN 管理器
2. 分流逻辑实现

## 详细步骤

### Step 1: 创建 VpnManager.kt

创建 VPN 管理器，协调分流逻辑：

```kotlin
package com.netproxy.gateway.vpn

import android.content.Context
import android.net.Network
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VpnManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        // 云服务器 IP 范围（需要走蜂窝网络）
        private val CLOUD_SERVER_IPS = listOf(
            // 添加云服务器 IP
            // 例如: "203.0.113.1"
        )
        
        // 内网 IP 范围（走 WiFi）
        private val INTERNAL_NETWORKS = listOf(
            "10.0.0.0/8",
            "172.16.0.0/12",
            "192.168.0.0/16"
        )
    }

    private val _vpnStatus = MutableStateFlow(VpnStatus())
    val vpnStatus: StateFlow<VpnStatus> = _vpnStatus.asStateFlow()

    /**
     * 判断目标 IP 应该走哪个通道
     * @param targetIp 目标 IP 地址
     * @return 通道类型: "wifi", "cellular", "proxy"
     */
    fun resolveRoute(targetIp: String): String {
        return when {
            // 云服务器 IP 走蜂窝网络
            isCloudServer(targetIp) -> "cellular"
            // 内网 IP 走 WiFi
            isInternalNetwork(targetIp) -> "wifi"
            // 其他流量走代理
            else -> "proxy"
        }
    }

    private fun isCloudServer(ip: String): Boolean {
        return CLOUD_SERVER_IPS.contains(ip)
    }

    private fun isInternalNetwork(ip: String): Boolean {
        // 简化实现：检查常见内网段
        return ip.startsWith("10.") ||
               ip.startsWith("172.16.") ||
               ip.startsWith("172.17.") ||
               ip.startsWith("172.18.") ||
               ip.startsWith("172.19.") ||
               ip.startsWith("172.2") ||
               ip.startsWith("172.30.") ||
               ip.startsWith("172.31.") ||
               ip.startsWith("192.168.")
    }

    fun startVpn() {
        // 启动 VPN 服务
        val intent = android.content.Intent(context, VpnService::class.java).apply {
            action = "START"
        }
        context.startForegroundService(intent)
    }

    fun stopVpn() {
        // 停止 VPN 服务
        val intent = android.content.Intent(context, VpnService::class.java).apply {
            action = "STOP"
        }
        context.startService(intent)
    }
}
```

### Step 2: 更新 processPacket 实现

在 VpnService 中更新 processPacket 方法，调用 VpnManager 的分流逻辑：

```kotlin
private fun processPacket(packet: ByteArray, length: Int) {
    // 解析 IP 头获取目标地址
    // 根据分流逻辑决定走向
    // 
    // 注意：这是简化实现
    // 完整实现需要解析 IP 包头
}
```

### Step 3: (可选) Android 13+ Split Tunneling

如果目标 SDK 是 Android 13+，可以使用系统原生的 excludeRoute：

```kotlin
private fun configureSplitTunneling(builder: Builder) {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        try {
            // 原生排除特定路由
            // builder.excludeRoute(InetAddress.getByName("cloud-server-ip"), 32)
        } catch (e: Exception) {
            // Handle exception
        }
    }
}
```

## 验证标准

- [ ] VpnManager.kt 正确实现
- [ ] 分流逻辑可以正确判断目标 IP 的走向
- [ ] 代码编译通过

## 下一步

完成后请:
1. 运行 `./gradlew assembleDebug` 验证编译
2. 提交代码到 git
3. 通知 Task 3E 开发者开始工作

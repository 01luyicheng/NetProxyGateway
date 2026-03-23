# Task 3B: VPN Module - VPN 服务

> **任务级别**: 核心任务  
> **前置依赖**: Task 2 (Core Application)  
> **后续任务**: Task 3E (UI 依赖此模块)  
> **预计工作量**: 4 小时

## 任务目标

实现 Android VpnService 创建 TUN 虚拟接口，捕获流量并实现基础分流逻辑。

## 交付物

1. `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt`
2. `android/app/src/main/java/com/netproxy/gateway/vpn/VpnManager.kt`
3. 相关常量定义

## 详细步骤

### Step 1: 创建 VPN 常量

创建 `android/app/src/main/java/com/netproxy/gateway/vpn/VpnConfig.kt`:

```kotlin
package com.netproxy.gateway.vpn

object VpnConfig {
    // Virtual IP for the VPN interface
    const val VPN_ADDRESS = "10.0.0.2"
    const val VPN_ROUTE = "0.0.0.0" // Route all traffic through VPN
    const val VPN_DNS = "8.8.8.8"
    const val VPN_MTU = 1500
    
    // Excluded routes for split tunneling (Android 13+)
    // Cloud server IPs will be excluded to use cellular directly
    val EXCLUDED_ROUTES = listOf(
        // Add your cloud server IP ranges here
        // Pair of (IP, prefixLength)
    )
}

enum class VpnState {
    STOPPED,
    STARTING,
    RUNNING,
    ERROR
}

data class VpnStatus(
    val state: VpnState = VpnState.STOPPED,
    val errorMessage: String? = null,
    val connectedClients: Int = 0
)
```

### Step 2: 实现 VpnService

创建 `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt`:

```kotlin
package com.netproxy.gateway.vpn

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.netproxy.gateway.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream

class VpnService : VpnService() {

    companion object {
        private const val TAG = "VpnService"
        const val ACTION_START = "com.netproxy.gateway.START_VPN"
        const val ACTION_STOP = "com.netproxy.gateway.STOP_VPN"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var vpnInterface: ParcelFileDescriptor? = null
    
    private val _status = MutableStateFlow(VpnStatus())
    val status: StateFlow<VpnStatus> = _status.asStateFlow()

    override fun onCreate() {
        super.onCreate()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startVpn()
            ACTION_STOP -> stopVpn()
        }
        return START_STICKY
    }

    private fun startVpn() {
        if (_status.value.state == VpnState.RUNNING) {
            return
        }

        _status.value = VpnStatus(state = VpnState.STARTING)

        try {
            // Configure the VPN interface
            val builder = Builder()
                .setSession("NetProxyGateway")
                .setMtu(VpnConfig.VPN_MTU)
                .addAddress(VpnConfig.VPN_ADDRESS, 32)
                .addRoute(VpnConfig.VPN_ROUTE, 0)
                .addDnsServer(VpnConfig.VPN_DNS)
                .setBlocking(true)

            // Configure excluded routes for split tunneling (Android 13+)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                configureSplitTunneling(builder)
            }

            // Configure intent for VPN settings
            val configureIntent = PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.setConfigureIntent(configureIntent)

            // Establish the VPN interface
            vpnInterface = builder.establish()
            
            if (vpnInterface != null) {
                _status.value = VpnStatus(state = VpnState.RUNNING)
                
                // Start processing traffic
                serviceScope.launch {
                    processVpnTraffic()
                }
            } else {
                _status.value = VpnStatus(
                    state = VpnState.ERROR,
                    errorMessage = "Failed to establish VPN"
                )
            }
        } catch (e: Exception) {
            _status.value = VpnStatus(
                state = VpnState.ERROR,
                errorMessage = e.message
            )
        }
    }

    private fun configureSplitTunneling(builder: Builder) {
        // Android 13+ supports excludeRoute for split tunneling
        // Traffic to these IPs will bypass the VPN and use regular network
        // Implementation depends on your cloud server IP ranges
    }

    private suspend fun processVpnTraffic() {
        val vpnFd = vpnInterface ?: return
        val inputStream = FileInputStream(vpnFd.fileDescriptor)
        val outputStream = FileOutputStream(vpnFd.fileDescriptor)
        val packet = ByteArray(32767)

        try {
            while (_status.value.state == VpnState.RUNNING) {
                val length = inputStream.read(packet)
                if (length > 0) {
                    processPacket(packet, length)
                }
            }
        } catch (e: Exception) {
            if (_status.value.state == VpnState.RUNNING) {
                _status.value = VpnStatus(
                    state = VpnState.ERROR,
                    errorMessage = e.message
                )
            }
        }
    }

    private fun processPacket(packet: ByteArray, length: Int) {
        // TODO: Implement split tunneling logic
        // 1. Parse the IP header to determine destination
        // 2. Forward to WiFi or SOCKS5 proxy based on routing rules
        //
        // Example logic:
        // - If destination is cloud server IP → exclude (use cellular)
        // - If destination is private IP range (192.168.x.x, 10.x.x.x) → forward via WiFi
        // - Otherwise → forward via SOCKS5 proxy
    }

    private fun stopVpn() {
        _status.value = VpnStatus(state = VpnState.STOPPED)
        
        try {
            vpnInterface?.close()
            vpnInterface = null
        } catch (e: Exception) {
            // Handle cleanup
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        stopVpn()
        super.onDestroy()
    }

    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }
}
```

### Step 3: 实现 VpnManager (可选，用于 UI 控制)

创建 `android/app/src/main/java/com/netproxy/gateway/vpn/VpnManager.kt`:

```kotlin
package com.netproxy.gateway.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VpnManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun startVpn() {
        val intent = Intent(context, VpnService::class.java).apply {
            action = VpnService.ACTION_START
        }
        context.startForegroundService(intent)
    }

    fun stopVpn() {
        val intent = Intent(context, VpnService::class.java).apply {
            action = VpnService.ACTION_STOP
        }
        context.startService(intent)
    }

    fun prepareVpn(): Intent? {
        return VpnService.prepare(context)
    }
    
    fun isVpnPrepared(): Boolean {
        return VpnService.prepare(context) == null
    }
}
```

## 验证标准

- [ ] `./gradlew assembleDebug` 编译成功
- [ ] VPN 服务可以正确启动/停止
- [ ] 网络流量被正确捕获
- [ ] 遵循 Android VPN 权限规范

## 注意事项

1. VPN 需要用户明确授权，`prepareVpn()` 返回 null 表示已授权
2. 需要在 AndroidManifest 中声明 VPN 相关权限
3. 分流逻辑需要根据实际云服务器 IP 范围进行配置

## 下一步

完成后请:
1. 运行 `./gradlew assembleDebug` 验证编译
2. 提交代码到 git
3. 通知 Task 3E (UI) 开发者可以开始集成

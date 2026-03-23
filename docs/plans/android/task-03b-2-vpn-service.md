# Task 3B-2: VpnService 核心实现

> **任务级别**: 核心任务  
> **前置依赖**: Task 3B-1 (VPN 基础框架)  
> **后续任务**: Task 3B-3  
> **预计工作量**: 2 小时

## 任务目标

实现 VpnService 核心功能，创建 TUN 接口并捕获流量。

## 交付物

1. `android/app/src/main/java/com/netproxy/gateway/vpn/VpnService.kt` - VPN 服务核心实现

## 详细步骤

### Step 1: 创建 VpnService.kt

实现 Android VpnService：

```kotlin
package com.netproxy.gateway.vpn

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.netproxy.gateway.ui.MainActivity
import com.netproxy.gateway.proxy.Socks5ProxyService
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
        const val VPN_ADDRESS = "10.0.0.2"
        const val VPN_ROUTE = "0.0.0.0"
        const val VPN_DNS = "8.8.8.8"
        const val VPN_MTU = 1500
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
            "START" -> startVpn()
            "STOP" -> stopVpn()
        }
        return START_STICKY
    }

    private fun startVpn() {
        if (_status.value.state == VpnState.RUNNING) return

        _status.value = VpnStatus(state = VpnState.STARTING)

        try {
            val builder = Builder()
                .setSession("NetProxyGateway")
                .setMtu(VPN_MTU)
                .addAddress(VPN_ADDRESS, 32)
                .addRoute(VPN_ROUTE, 0)
                .addDnsServer(VPN_DNS)
                .setBlocking(true)

            val configureIntent = PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.setConfigureIntent(configureIntent)

            vpnInterface = builder.establish()
            
            if (vpnInterface != null) {
                _status.value = VpnStatus(state = VpnState.RUNNING)
                serviceScope.launch { processVpnTraffic() }
                startProxyService()
            } else {
                _status.value = VpnStatus(state = VpnState.ERROR, errorMessage = "Failed to establish VPN")
            }
        } catch (e: Exception) {
            _status.value = VpnStatus(state = VpnState.ERROR, errorMessage = e.message)
        }
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
                _status.value = VpnStatus(state = VpnState.ERROR, errorMessage = e.message)
            }
        }
    }

    private fun processPacket(packet: ByteArray, length: Int) {
        // TODO: 实现分流逻辑
        // 根据目标 IP 决定走 WiFi 还是 SOCKS5 代理
    }

    private fun startProxyService() {
        val intent = Intent(this, Socks5ProxyService::class.java)
        startForegroundService(intent)
    }

    private fun stopVpn() {
        _status.value = VpnStatus(state = VpnState.STOPPED)
        try {
            vpnInterface?.close()
            vpnInterface = null
        } catch (e: Exception) {
            // Handle cleanup
        }
        stopProxyService()
    }

    private fun stopProxyService() {
        val intent = Intent(this, Socks5ProxyService::class.java)
        stopService(intent)
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

## 验证标准

- [ ] VpnService.kt 正确实现 VpnService 接口
- [ ] VPN 接口可以成功创建
- [ ] 流量可以被捕获
- [ ] SOCKS5 代理服务可以被启动
- [ ] 代码编译通过

## 下一步

完成后请:
1. 运行 `./gradlew assembleDebug` 验证编译
2. 提交代码到 git
3. 通知 Task 3B-3 开发者开始工作

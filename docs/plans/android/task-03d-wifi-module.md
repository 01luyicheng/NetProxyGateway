# Task 3D: WiFi Control Module - WiFi 控制模块

> **任务级别**: 核心任务  
> **前置依赖**: Task 2 (Core Application)  
> **后续任务**: Task 3E (UI 依赖此模块)  
> **预计工作量**: 3 小时

## 任务目标

实现 WiFi 扫描、连接和断开功能，支持远程控制客户手机的 WiFi。

## 交付物

1. `android/app/src/main/java/com/netproxy/gateway/wifi/WifiManager.kt`
2. `android/app/src/main/java/com/netproxy/gateway/wifi/WifiModels.kt`

## 详细步骤

### Step 1: 创建 WiFi 数据模型

创建 `android/app/src/main/java/com/netproxy/gateway/wifi/WifiModels.kt`:

```kotlin
package com.netproxy.gateway.wifi

data class WifiNetwork(
    val ssid: String,
    val bssid: String,
    val signalStrength: Int,
    val frequency: Int,
    val capabilities: String,
    val isSecure: Boolean
)

data class WifiConnectionInfo(
    val ssid: String?,
    val bssid: String?,
    val ipAddress: Int,
    val linkSpeed: Int,
    val frequency: Int,
    val signalStrength: Int
)

enum class WifiSecurityType {
    OPEN,
    WEP,
    WPA,
    WPA2,
    WPA3,
    UNKNOWN
}
```

### Step 2: 实现 WifiManager

创建 `android/app/src/main/java/com/netproxy/gateway/wifi/WifiManager.kt`:

```kotlin
package com.netproxy.gateway.wifi

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WifiManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "WifiManager"
    }

    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    val wifiScanResults: Flow<List<WifiNetwork>> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
                    val success = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
                    Log.d(TAG, "WiFi scan completed: success=$success")
                    
                    val results = getScanResults()
                    trySend(results)
                }
            }
        }

        val intentFilter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        context.registerReceiver(receiver, intentFilter)

        // Emit initial results
        trySend(getScanResults())

        awaitClose {
            context.unregisterReceiver(receiver)
        }
    }

    fun startScan(): Boolean {
        return wifiManager.startScan()
    }

    fun getScanResults(): List<WifiNetwork> {
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return emptyList()
        }

        return wifiManager.scanResults
            .filter { !it.SSID.isNullOrEmpty() }
            .map { result ->
                WifiNetwork(
                    ssid = result.SSID,
                    bssid = result.BSSID,
                    signalStrength = result.level,
                    frequency = result.frequency,
                    capabilities = result.capabilities,
                    isSecure = result.capabilities.contains("WPA") || result.capabilities.contains("WEP")
                )
            }
            .sortedByDescending { it.signalStrength }
    }

    fun getCurrentConnection(): WifiConnectionInfo? {
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }

        val connectionInfo = wifiManager.connectionInfo ?: return null
        
        return WifiConnectionInfo(
            ssid = connectionInfo.ssid?.replace("\"", ""),
            bssid = connectionInfo.bssid,
            ipAddress = connectionInfo.ipAddress,
            linkSpeed = connectionInfo.linkSpeed,
            frequency = connectionInfo.frequency,
            signalStrength = connectionInfo.rssi
        )
    }

    fun connectToNetwork(ssid: String, password: String?, securityType: WifiSecurityType): Boolean {
        val configuration = WifiConfiguration().apply {
            SSID = "\"$ssid\""
            
            when (securityType) {
                WifiSecurityType.WPA2, WifiSecurityType.WPA -> {
                    preSharedKey = "\"$password\""
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
                }
                WifiSecurityType.WEP -> {
                    wepKeys[0] = "\"$password\""
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                }
                WifiSecurityType.OPEN -> {
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                }
                else -> {
                    // Default to WPA2
                    password?.let {
                        preSharedKey = "\"$it\""
                    }
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
                }
            }
        }

        val networkId = wifiManager.addNetwork(configuration)
        if (networkId == -1) {
            Log.e(TAG, "Failed to add network configuration")
            return false
        }

        val success = wifiManager.enableNetwork(networkId, true)
        if (!success) {
            Log.e(TAG, "Failed to enable network")
            return false
        }

        return true
    }

    fun disconnect(): Boolean {
        return wifiManager.disconnect()
    }

    fun parseSecurityType(capabilities: String): WifiSecurityType {
        return when {
            capabilities.contains("WPA3") -> WifiSecurityType.WPA3
            capabilities.contains("WPA2") -> WifiSecurityType.WPA2
            capabilities.contains("WPA") -> WifiSecurityType.WPA
            capabilities.contains("WEP") -> WifiSecurityType.WEP
            capabilities.contains("ESS") && !capabilities.contains("WPA") && !capabilities.contains("WEP") -> WifiSecurityType.OPEN
            else -> WifiSecurityType.UNKNOWN
        }
    }
}
```

### Step 3: 更新 DI Module

在 `di/AppModule.kt` 中添加:

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object WifiModule {
    
    @Provides
    @Singleton
    fun provideWifiManager(
        @ApplicationContext context: Context
    ): WifiManager {
        return WifiManager(context)
    }
}
```

## 验证标准

- [ ] `./gradlew assembleDebug` 编译成功
- [ ] WiFi 扫描功能正常工作
- [ ] 可以获取当前 WiFi 连接信息
- [ ] WiFi 连接功能正常

## 注意事项

1. WiFi 扫描需要位置权限 (ACCESS_FINE_LOCATION)
2. Android 13+ 需要 NEARBY_WIFI_DEVICES 权限
3. 某些设备可能需要位置权限才能获取 WiFi 列表

## 下一步

完成后请:
1. 运行 `./gradlew assembleDebug` 验证编译
2. 提交代码到 git
3. 通知 Task 3E (UI) 开发者可以开始集成

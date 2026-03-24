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
import android.net.wifi.WifiManager as AndroidWifiManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

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

@Singleton
class GatewayWifiManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "GatewayWifiManager"
    }

    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as AndroidWifiManager

    val wifiScanResults: Flow<List<WifiNetwork>> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == AndroidWifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
                    val success = intent.getBooleanExtra(AndroidWifiManager.EXTRA_RESULTS_UPDATED, false)
                    Log.d(TAG, "WiFi scan completed: success=$success")
                    
                    val results = getScanResults()
                    trySend(results)
                }
            }
        }

        val intentFilter = IntentFilter(AndroidWifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        context.registerReceiver(receiver, intentFilter)

        trySend(getScanResults())

        awaitClose {
            context.unregisterReceiver(receiver)
        }
    }

    companion object {
        private const val TAG = "GatewayWifiManager"

        /**
         * 检查WiFi扫描所需的权限
         * Android 13+ (API 33+): 可以使用 NEARBY_WIFI_DEVICES 替代位置权限
         * Android 10-12 (API 29-32): 需要 ACCESS_FINE_LOCATION
         * Android 9及以下 (API 28-): 需要 ACCESS_FINE_LOCATION
         */
        fun hasWifiScanPermission(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Android 13+ 可以使用 NEARBY_WIFI_DEVICES 权限
                ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
            } else {
                // Android 12及以下需要位置权限
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            }
        }

        /**
         * 获取WiFi扫描所需的权限名称（用于运行时权限申请）
         */
        fun getRequiredWifiPermission(): String {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.NEARBY_WIFI_DEVICES
            } else {
                Manifest.permission.ACCESS_FINE_LOCATION
            }
        }
    }

    fun startScan(): Boolean {
        if (!hasWifiScanPermission(context)) {
            Log.w(TAG, "Cannot start scan: permission not granted")
            return false
        }
        return wifiManager.startScan()
    }

    fun getScanResults(): List<WifiNetwork> {
        if (!hasWifiScanPermission(context)) {
            Log.w(TAG, "Cannot get scan results: permission not granted")
            return emptyList()
        }

        return try {
            wifiManager.scanResults
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
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException when getting scan results: ${e.message}")
            emptyList()
        }
    }

    fun getCurrentConnection(): WifiConnectionInfo? {
        if (!hasWifiScanPermission(context)) {
            Log.w(TAG, "Cannot get current connection: permission not granted")
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

    fun connectToNetwork(ssid: String, password: String?, securityType: String): Boolean {
        val configuration = WifiConfiguration().apply {
            SSID = "\"$ssid\""
            
            when {
                securityType.contains("WPA2") || securityType.contains("WPA") -> {
                    preSharedKey = "\"$password\""
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
                }
                securityType.contains("WEP") -> {
                    wepKeys[0] = "\"$password\""
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                }
                else -> {
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
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
}

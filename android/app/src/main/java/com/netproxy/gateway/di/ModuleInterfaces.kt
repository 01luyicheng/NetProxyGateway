package com.netproxy.gateway.di

import com.netproxy.gateway.connection.NetworkState
import com.netproxy.gateway.connection.MqttConnectionState
import com.netproxy.gateway.vpn.VpnStatus
import com.netproxy.gateway.wifi.WifiNetwork
import com.netproxy.gateway.wifi.WifiConnectionInfo
import com.netproxy.gateway.ui.viewmodel.UiState
import kotlinx.coroutines.flow.StateFlow

/**
 * 模块化架构接口定义
 * 每个模块通过这些接口进行通信
 */

// ==================== Core Module ====================
interface CoreModule {
    fun getNetworkState(): StateFlow<NetworkState>
    fun getCoroutineScope(): kotlinx.coroutines.CoroutineScope
}

// ==================== Communication Module ====================
interface CommunicationModule {
    fun connect(deviceId: String, authToken: String): Boolean
    fun publish(topic: String, payload: String, qos: Int = 0)
    fun subscribe(topic: String, qos: Int = 0, callback: (String) -> Unit)
    fun getConnectionState(): StateFlow<MqttConnectionState>
    fun disconnect()
}

// ==================== Network Module ====================
interface NetworkModule {
    fun startVpn(): Boolean
    fun stopVpn(): Boolean
    fun startProxy(): Boolean
    fun stopProxy(): Boolean
    fun getVpnStatus(): StateFlow<VpnStatus>
    fun getProxyStatus(): StateFlow<Boolean>
}

// ==================== WiFi Module ====================
interface WiFiModule {
    fun scanWiFi(): List<WifiNetwork>
    fun connectToWiFi(ssid: String, password: String?, securityType: String): Boolean
    fun disconnectWiFi(): Boolean
    fun getCurrentConnection(): WifiConnectionInfo?
    fun getWifiNetworks(): StateFlow<List<WifiNetwork>>
}

// ==================== UI Module ====================
interface UIModule {
    fun updateUiState(state: UiState)
    fun getUiState(): StateFlow<UiState>
}

// ==================== Config Module ====================
interface ConfigModule {
    fun getDeviceId(): String
    fun getAuthToken(): String
    fun setDeviceId(deviceId: String)
    fun setAuthToken(token: String)
    fun checkPermissions(): List<String>
    fun requestPermissions(permissions: List<String>): Boolean
}

// ==================== Event Bus ====================
sealed class AppEvent {
    data class ConnectionStateChange(val state: MqttConnectionState) : AppEvent()
    data class VpnStateChange(val state: VpnStatus) : AppEvent()
    data class WiFiStateChange(val connected: Boolean, val ssid: String?) : AppEvent()
    data class MessageReceived(val topic: String, val payload: String) : AppEvent()
    object AppStarted : AppEvent()
    object AppStopped : AppEvent()
}
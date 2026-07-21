package com.netproxy.gateway.di

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

import com.netproxy.gateway.connection.MqttConnectionState
import com.netproxy.gateway.connection.NetworkState
import com.netproxy.gateway.ui.viewmodel.UiState
import com.netproxy.gateway.vpn.VpnStatus
import com.netproxy.gateway.wifi.WifiConnectionInfo
import com.netproxy.gateway.wifi.WifiNetwork

/**
 * 模块化架构接口定义
 * 遵循CQRS原则：命令和查询分离
 */

// ==================== Core Module ====================

/**
 * Core模块查询处理器
 */
interface CoreQueryHandler {
    fun getNetworkState(): StateFlow<NetworkState>
    fun getCoroutineScope(): CoroutineScope
}

/**
 * Core模块接口（向后兼容）
 * @deprecated 使用CoreQueryHandler替代
 */
@Deprecated("使用CoreQueryHandler替代", ReplaceWith("CoreQueryHandler"))
interface CoreModule : CoreQueryHandler

// ==================== Communication Module ====================

/**
 * Communication模块命令处理器
 */
interface CommunicationCommandHandler {
    fun connect(deviceId: String, authToken: CharArray): Boolean
    fun publish(topic: String, payload: String, qos: Int)
    fun subscribe(topic: String, qos: Int, callback: ((String) -> Unit)?)
    fun disconnect()
}

/**
 * Communication模块查询处理器
 */
interface CommunicationQueryHandler {
    fun getConnectionState(): StateFlow<MqttConnectionState>
}


// ==================== Network Module ====================

/**
 * Network模块命令处理器
 */
interface NetworkCommandHandler {
    fun startVpn(): Boolean
    fun stopVpn(): Boolean
    fun startProxy(): Boolean
    fun stopProxy(): Boolean
}

/**
 * Network模块查询处理器
 */
interface NetworkQueryHandler {
    fun getVpnStatus(): StateFlow<VpnStatus>
    fun getProxyStatus(): StateFlow<Boolean>
}

/**
 * Network模块接口（向后兼容）
 * @deprecated 使用NetworkCommandHandler和NetworkQueryHandler替代
 */
@Deprecated("使用NetworkCommandHandler和NetworkQueryHandler替代")
interface NetworkModule : NetworkCommandHandler, NetworkQueryHandler {
    override fun startVpn(): Boolean
    override fun stopVpn(): Boolean
    override fun startProxy(): Boolean
    override fun stopProxy(): Boolean
    override fun getVpnStatus(): StateFlow<VpnStatus>
    override fun getProxyStatus(): StateFlow<Boolean>
}

// ==================== WiFi Module ====================

/**
 * WiFi模块命令处理器
 */
interface WiFiCommandHandler {
    fun scanWiFi(): List<WifiNetwork>
    fun connectToWiFi(ssid: String, password: String?, securityType: String): Boolean
    fun disconnectWiFi(): Boolean
}

/**
 * WiFi模块查询处理器
 */
interface WiFiQueryHandler {
    fun getCurrentConnection(): WifiConnectionInfo?
    fun getWifiNetworks(): StateFlow<List<WifiNetwork>>
}

/**
 * WiFi模块接口（向后兼容）
 * @deprecated 使用WiFiCommandHandler和WiFiQueryHandler替代
 */
@Deprecated("使用WiFiCommandHandler和WiFiQueryHandler替代")
interface WiFiModule : WiFiCommandHandler, WiFiQueryHandler {
    override fun scanWiFi(): List<WifiNetwork>
    override fun connectToWiFi(ssid: String, password: String?, securityType: String): Boolean
    override fun disconnectWiFi(): Boolean
    override fun getCurrentConnection(): WifiConnectionInfo?
    override fun getWifiNetworks(): StateFlow<List<WifiNetwork>>
}

// ==================== UI Module ====================

/**
 * UI模块命令处理器
 */
interface UICommandHandler {
    fun updateUiState(state: UiState)
}

/**
 * UI模块查询处理器
 */
interface UIQueryHandler {
    fun getUiState(): StateFlow<UiState>
}

/**
 * UI模块接口（向后兼容）
 * @deprecated 使用UICommandHandler和UIQueryHandler替代
 */
@Deprecated("使用UICommandHandler和UIQueryHandler替代")
interface UIModule : UICommandHandler, UIQueryHandler {
    override fun updateUiState(state: UiState)
    override fun getUiState(): StateFlow<UiState>
}

// ==================== Config Module ====================

/**
 * Config模块命令处理器
 */
interface ConfigCommandHandler {
    fun setDeviceId(deviceId: String)
    fun setAuthToken(token: CharArray)
    fun requestPermissions(permissions: List<String>): Boolean
}

/**
 * Config模块查询处理器
 */
interface ConfigQueryHandler {
    fun getDeviceId(): String
    fun getAuthToken(): CharArray
    fun checkPermissions(): List<String>
}

/**
 * Config模块接口（向后兼容）
 * @deprecated 使用ConfigCommandHandler和ConfigQueryHandler替代
 */
@Deprecated("使用ConfigCommandHandler和ConfigQueryHandler替代")
interface ConfigModule : ConfigCommandHandler, ConfigQueryHandler {
    override fun getDeviceId(): String
    override fun getAuthToken(): CharArray
    override fun setDeviceId(deviceId: String)
    override fun setAuthToken(token: CharArray)
    override fun checkPermissions(): List<String>
    override fun requestPermissions(permissions: List<String>): Boolean
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

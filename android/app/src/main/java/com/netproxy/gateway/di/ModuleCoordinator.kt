package com.netproxy.gateway.di

import com.netproxy.gateway.connection.NetworkState
import com.netproxy.gateway.connection.MqttConnectionState
import com.netproxy.gateway.vpn.VpnStatus
import com.netproxy.gateway.wifi.WifiNetwork
import com.netproxy.gateway.wifi.WifiConnectionInfo
import com.netproxy.gateway.ui.viewmodel.UiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 模块协调器
 * 负责模块间通信和状态同步
 */
@Singleton
class ModuleCoordinator @Inject constructor(
    @ApplicationScope private val coordinatorScope: CoroutineScope
) {
    
    // ==================== Event Bus ====================
    private val _events = MutableSharedFlow<AppEvent>()
    val events: SharedFlow<AppEvent> = _events.asSharedFlow()
    
    fun publishEvent(event: AppEvent) {
        coordinatorScope.launch {
            _events.emit(event)
        }
    }
    
    // ==================== Global State ====================
    private val _connectionState = MutableStateFlow<MqttConnectionState>(MqttConnectionState.Disconnected)
    val connectionState: StateFlow<MqttConnectionState> = _connectionState.asStateFlow()
    
    private val _vpnState = MutableStateFlow<VpnStatus>(VpnStatus())
    val vpnState: StateFlow<VpnStatus> = _vpnState.asStateFlow()
    
    private val _wifiState = MutableStateFlow<WiFiState>(WiFiState.Disconnected)
    val wifiState: StateFlow<WiFiState> = _wifiState.asStateFlow()
    
    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()
    
    // ==================== Module Registration ====================
    private val modules = mutableMapOf<String, Any>()
    
    fun registerModule(name: String, module: Any) {
        modules[name] = module
    }
    
    fun <T> getModule(name: String): T? {
        @Suppress("UNCHECKED_CAST")
        return modules[name] as? T
    }
    
    // ==================== State Update Methods ====================
    fun updateConnectionState(state: MqttConnectionState) {
        _connectionState.value = state
        publishEvent(AppEvent.ConnectionStateChange(state))
    }
    
    fun updateVpnState(state: VpnStatus) {
        _vpnState.value = state
        publishEvent(AppEvent.VpnStateChange(state))
    }
    
    fun updateWifiState(connected: Boolean, ssid: String?) {
        _wifiState.value = if (connected) {
            WiFiState.Connected(ssid ?: "")
        } else {
            WiFiState.Disconnected
        }
        publishEvent(AppEvent.WiFiStateChange(connected, ssid))
    }
    
    fun updateUiState(state: UiState) {
        _uiState.value = state
    }
    
    // ==================== WiFi State ====================
    sealed class WiFiState {
        object Disconnected : WiFiState()
        data class Connected(val ssid: String) : WiFiState()
    }
    
    // ==================== Module Lifecycle ====================
    fun onAppStarted() {
        publishEvent(AppEvent.AppStarted)
    }
    
    fun onAppStopped() {
        publishEvent(AppEvent.AppStopped)
    }
}
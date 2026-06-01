package com.netproxy.gateway.di

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

import javax.inject.Inject
import javax.inject.Singleton

import com.netproxy.gateway.connection.MqttConnectionState
import com.netproxy.gateway.connection.NetworkState
import com.netproxy.gateway.ui.viewmodel.UiState
import com.netproxy.gateway.vpn.VpnStatus
import com.netproxy.gateway.wifi.WifiConnectionInfo
import com.netproxy.gateway.wifi.WifiNetwork

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

    /**
     * 将给定的 AppEvent 发布到协调器的事件总线，供订阅者接收。
     *
     * @param event 要发布的事件。
     */
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

    /**
     * 在协调器中注册或覆盖一个模块实例，以供跨模块访问。
     *
     * 将给定的 `module` 存入内部模块映射，键为 `name`。若映射中已存在相同 `name` 的模块实例，则会被覆盖。
     *
     * @param name 用于注册模块的唯一键字符串。
     * @param module 要注册的模块实例，类型不限。
     */
    fun registerModule(name: String, module: Any) {
        modules[name] = module
    }

    /**
     * 按名称检索已注册的模块实例并尝试将其转换为请求的类型。
     *
     * @param name 模块注册时使用的键。
     * @return `T` 类型的模块实例（若存在且能成功转换），否则 `null`。
     */
    fun <T> getModule(name: String): T? {
        @Suppress("UNCHECKED_CAST")
        return modules[name] as? T
    }

    /**
     * 更新全局 MQTT 连接状态并发布相应的变更事件。
     *
     * 将内部 `connectionState` 更新为提供的 `state`，并通过事件总线发布 `AppEvent.ConnectionStateChange` 以通知订阅者。
     *
     * @param state 新的 MQTT 连接状态
     */
    fun updateConnectionState(state: MqttConnectionState) {
        _connectionState.update { state }
        publishEvent(AppEvent.ConnectionStateChange(state))
    }

    /**
     * 更新全局的 VPN 状态并广播对应的应用事件。
     *
     * @param state 新的 VPN 状态，用于替换当前的全局状态并通知订阅者。
     */
    fun updateVpnState(state: VpnStatus) {
        _vpnState.update { state }
        publishEvent(AppEvent.VpnStateChange(state))
    }

    /**
     * 根据给定的连接标志和 SSID 更新内部的 WiFi 状态并发布对应的 `AppEvent.WiFiStateChange` 事件。
     *
     * @param connected 表示当前是否已连接到 Wi‑Fi；`true` 表示已连接，`false` 表示未连接。
     * @param ssid 在已连接时表示网络的 SSID，可能为 `null`（将被视为空字符串）；在未连接时忽略该值。
     */
    fun updateWifiState(connected: Boolean, ssid: String?) {
        _wifiState.update {
            if (connected) {
                WiFiState.Connected(ssid ?: "")
            } else {
                WiFiState.Disconnected
            }
        }
        publishEvent(AppEvent.WiFiStateChange(connected, ssid))
    }

    /**
     * 更新全局 UI 状态的 StateFlow 值。
     *
     * @param state 新的 UI 状态，用于替换当前的 uiState 值。
     */
    fun updateUiState(state: UiState) {
        _uiState.update { state }
    }

    // ==================== WiFi State ====================
    sealed class WiFiState {
        object Disconnected : WiFiState()
        data class Connected(val ssid: String) : WiFiState()
    }

    /**
     * 通知所有订阅者应用已启动，以便执行启动时的相关逻辑。
     */
    fun onAppStarted() {
        publishEvent(AppEvent.AppStarted)
    }

    /**
     * 发布应用停止事件以通知各模块应用已停止。
     *
     * 将 `AppEvent.AppStopped` 发送到协调器的事件总线，供订阅者接收并响应应用停止的生命周期变化。
     */
    fun onAppStopped() {
        publishEvent(AppEvent.AppStopped)
    }
}

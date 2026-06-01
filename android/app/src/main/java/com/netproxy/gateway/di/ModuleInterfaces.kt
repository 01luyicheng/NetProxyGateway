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
    /**
 * 提供当前网络状态及其后续变更的可观察数据流。
 *
 * @return `StateFlow<NetworkState>`，始终发出当前的 `NetworkState` 值并在状态变化时继续发出更新。
 */
fun getNetworkState(): StateFlow<NetworkState>
    /**
 * 提供一个用于执行模块级异步任务的作用域。
 *
 * @return `CoroutineScope`：用于启动协程并管理该模块异步任务生命周期的作用域。
 */
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
    /**
 * 使用指定的设备 ID 和身份验证令牌建立通信连接。
 *
 * @param deviceId 目标设备的标识符，用于识别要连接的设备。
 * @param authToken 用于验证身份的令牌或凭证。
 * @return `true` 如果连接建立成功，`false` 否则。
 */
fun connect(deviceId: String, authToken: String): Boolean
    /**
 * 向指定的 MQTT 主题发布消息。
 *
 * @param topic 目标主题的完整字符串（例如 "devices/+/messages"）。
 * @param payload 要发送的消息内容。
 * @param qos MQTT 消息的服务质量等级，通常为 0、1 或 2。 
 */
fun publish(topic: String, payload: String, qos: Int)
    /**
 * 订阅指定主题以接收消息。
 *
 * @param topic 要订阅的 MQTT 主题。
 * @param qos 消息的服务质量级别，取值为 0、1 或 2。
 * @param callback 可选回调函数；在收到该主题的消息时以消息负载（字符串）作为参数调用。若为 `null`，不提供回调。
 */
fun subscribe(topic: String, qos: Int, callback: ((String) -> Unit)?)
    /**
 * 请求终止当前通信会话。
 */
fun disconnect()
}

/**
 * Communication模块查询处理器
 */
interface CommunicationQueryHandler {
    /**
 * 提供 MQTT 连接状态的可观察流。
 *
 * @return 当前 MQTT 连接状态的 StateFlow，在连接状态变化时发出相应的 `MqttConnectionState` 值。
 */
fun getConnectionState(): StateFlow<MqttConnectionState>
}

/**
 * Communication模块接口（向后兼容）
 * @deprecated 使用CommunicationCommandHandler和CommunicationQueryHandler替代
 */
@Deprecated("使用CommunicationCommandHandler和CommunicationQueryHandler替代")
interface CommunicationModule : CommunicationCommandHandler, CommunicationQueryHandler {
    /**
 * 尝试使用提供的设备 ID 和认证令牌建立通信连接。
 *
 * @param deviceId 要连接的设备标识符。
 * @param authToken 用于验证设备身份的认证令牌。
 * @return `true` 表示连接已成功建立，`false` 表示连接失败。
 */
override fun connect(deviceId: String, authToken: String): Boolean
    /**
 * 发布消息到指定主题，使用给定的 QoS 等级传送负载。
 *
 * @param topic 要发布的主题。
 * @param payload 要发送的消息内容。
 * @param qos 消息的服务质量等级（通常为 0、1 或 2）。
 */
override fun publish(topic: String, payload: String, qos: Int)
    /**
 * 订阅指定主题并在收到消息时通过回调分发消息负载。
 *
 * @param topic 要订阅的 MQTT 主题名称（可包含通配符）。
 * @param qos 消息传输质量等级，取值为 0、1 或 2。
 * @param callback 可选回调，在收到该主题的消息时接收消息的负载字符串；若为 `null` 则不执行本地回调。
 */
override fun subscribe(topic: String, qos: Int, callback: ((String) -> Unit)?)
    /**
 * 提供 MQTT 连接状态的响应式可观察流。
 *
 * @return `StateFlow<MqttConnectionState>` 当前 MQTT 连接状态；在连接状态变化时会发出更新。
 */
override fun getConnectionState(): StateFlow<MqttConnectionState>
    /**
 * 终止当前通信会话。
 */
override fun disconnect()
}

// ==================== Network Module ====================

/**
 * Network模块命令处理器
 */
interface NetworkCommandHandler {
    /**
 * 启动虚拟专用网络（VPN）会话。
 *
 * @return `true` 表示已成功启动 VPN，`false` 表示启动失败。
 */
fun startVpn(): Boolean
    /**
 * 停止 VPN 连接。
 *
 * @return `true` 表示成功停止 VPN，`false` 表示停止失败或 VPN 未在运行。
 */
fun stopVpn(): Boolean
    /**
 * 启动代理服务。
 *
 * 发起代理组件的启动以开始代理流量的转发或拦截。
 *
 * @return `true` 如果代理启动成功，`false` 否则。
 */
fun startProxy(): Boolean
    /**
 * 请求停止代理服务。
 *
 * @return `true` 如果代理已成功停止，`false` 否则。
 */
fun stopProxy(): Boolean
}

/**
 * Network模块查询处理器
 */
interface NetworkQueryHandler {
    /**
 * 提供当前 VPN 状态及其后续更新的可观察流。
 *
 * @return 包含当前 VPN 状态并会在状态变化时发出更新的 `StateFlow<VpnStatus>`。
 */
fun getVpnStatus(): StateFlow<VpnStatus>
    /**
 * 提供当前代理运行状态的可观察流。
 *
 * @return `true` 表示代理正在运行，`false` 表示未运行。
 */
fun getProxyStatus(): StateFlow<Boolean>
}

/**
 * Network模块接口（向后兼容）
 * @deprecated 使用NetworkCommandHandler和NetworkQueryHandler替代
 */
@Deprecated("使用NetworkCommandHandler和NetworkQueryHandler替代")
interface NetworkModule : NetworkCommandHandler, NetworkQueryHandler {
    /**
 * 启动 VPN 服务。
 *
 * @return `true` 表示 VPN 启动成功，`false` 表示启动失败。
 */
override fun startVpn(): Boolean
    /**
 * 停止正在运行的 VPN 连接。
 *
 * @return `true` 如果 VPN 成功停止，`false` 否则。
 */
override fun stopVpn(): Boolean
    /**
 * 启动代理服务。
 *
 * @return `true` 如果代理已成功启动，`false` 表示启动失败。
 */
override fun startProxy(): Boolean
    /**
 * 停止代理服务。
 *
 * @return `true` 表示代理已成功停止，`false` 表示停止失败或代理未在运行。
 */
override fun stopProxy(): Boolean
    /**
 * 提供当前 VPN 运行状态的可观察流。
 *
 * @return 包含当前 VPN 状态的 StateFlow，当状态变化时会发出新的 `VpnStatus` 值。
 */
override fun getVpnStatus(): StateFlow<VpnStatus>
    /**
 * 提供当前代理运行状态的可观察流。
 *
 * @return `true` 表示代理正在运行，`false` 表示未运行。
 */
override fun getProxyStatus(): StateFlow<Boolean>
}

// ==================== WiFi Module ====================

/**
 * WiFi模块命令处理器
 */
interface WiFiCommandHandler {
    /**
 * 执行无线网络扫描并提供当前可用的 Wi‑Fi 网络列表。
 *
 * @return 当前可用的 WifiNetwork 列表；如果未发现网络则返回空列表。
 */
fun scanWiFi(): List<WifiNetwork>
    /**
 * 连接到指定的 WiFi 网络。
 *
 * @param ssid 要连接的网络 SSID。
 * @param password 网络密码；如果为 `null` 则表示连接到一个开放网络（无密码）。
 * @param securityType WiFi 的安全类型（例如 `"WPA2"`, `"WEP"` 等）。
 * @return `true` 表示连接成功，`false` 表示连接失败。
 */
fun connectToWiFi(ssid: String, password: String?, securityType: String): Boolean
    /**
 * 断开当前的 WiFi 连接。
 *
 * @return `true` 表示已成功断开连接，`false` 表示断开失败或未建立连接。
 */
fun disconnectWiFi(): Boolean
}

/**
 * WiFi模块查询处理器
 */
interface WiFiQueryHandler {
    /**
 * 获取当前的 WiFi 连接信息。
 *
 * @return 当前的 WifiConnectionInfo 实例；如果设备未连接到任何 WiFi，则返回 `null`。
 */
fun getCurrentConnection(): WifiConnectionInfo?
    /**
 * 提供当前可观察的可用 Wi-Fi 网络列表。
 *
 * @return 一个包含当前可见 Wi-Fi 网络列表的 StateFlow，流中值为可用网络的列表。
 */
fun getWifiNetworks(): StateFlow<List<WifiNetwork>>
}

/**
 * WiFi模块接口（向后兼容）
 * @deprecated 使用WiFiCommandHandler和WiFiQueryHandler替代
 */
@Deprecated("使用WiFiCommandHandler和WiFiQueryHandler替代")
interface WiFiModule : WiFiCommandHandler, WiFiQueryHandler {
    /**
 * 扫描并返回当前可用的 Wi-Fi 网络列表。
 *
 * @return 当前可用 Wi-Fi 网络的列表；如果没有可用网络则返回空列表。
 */
override fun scanWiFi(): List<WifiNetwork>
    /**
 * 使用指定凭据连接到指定的 WiFi 网络。
 *
 * @param ssid 要连接的网络 SSID（网络名称）。
 * @param password 连接密码；开放网络时传入 `null`。
 * @param securityType 网络的安全类型（例如 `"WPA2"`、`"WEP"` 或 `"OPEN"`），用于选择认证方式。
 * @return `true` 如果成功连接到该网络，`false` 否则。
 */
override fun connectToWiFi(ssid: String, password: String?, securityType: String): Boolean
    /**
 * 断开当前 WiFi 连接。
 *
 * @return `true` 如果已成功断开连接，`false` 否则。
 */
override fun disconnectWiFi(): Boolean
    /**
 * 获取当前的 WiFi 连接信息。
 *
 * @return `WifiConnectionInfo` 表示当前连接信息；未连接时返回 `null`。
 */
override fun getCurrentConnection(): WifiConnectionInfo?
    /**
 * 提供可用 WiFi 网络列表的响应式流，随可用网络变化持续推送最新列表。
 *
 * @return `StateFlow`，包含当前可用的 `List<WifiNetwork>`，当可用网络集合改变时会发出更新。
 */
override fun getWifiNetworks(): StateFlow<List<WifiNetwork>>
}

// ==================== UI Module ====================

/**
 * UI模块命令处理器
 */
interface UICommandHandler {
    /**
 * 请求更新全局 UI 状态。
 *
 * @param state 要应用的 UI 状态。
 */
fun updateUiState(state: UiState)
}

/**
 * UI模块查询处理器
 */
interface UIQueryHandler {
    /**
 * 提供应用当前 UI 状态的可观察流，供观察和响应 UI 状态变化。
 *
 * @return `StateFlow<UiState>`：始终包含当前 UI 状态，并在状态变更时发出新值。
 */
fun getUiState(): StateFlow<UiState>
}

/**
 * UI模块接口（向后兼容）
 * @deprecated 使用UICommandHandler和UIQueryHandler替代
 */
@Deprecated("使用UICommandHandler和UIQueryHandler替代")
interface UIModule : UICommandHandler, UIQueryHandler {
    /**
 * 请求将 UI 状态更新为指定值。
 *
 * @param state 新的 UI 状态。
 */
override fun updateUiState(state: UiState)
    /**
 * 提供当前 UI 状态的可观察流。
 *
 * @return `StateFlow<UiState>`，发出当前的 UI 状态以及后续的状态更新。
 */
override fun getUiState(): StateFlow<UiState>
}

// ==================== Config Module ====================

/**
 * Config模块命令处理器
 */
interface ConfigCommandHandler {
    /**
 * 设置当前设备的标识符。
 *
 * @param deviceId 设备的唯一标识字符串。
 */
fun setDeviceId(deviceId: String)
    /**
 * 设置用于认证的令牌，供后续网络请求或会话使用。
 *
 * @param token 要设置的身份验证令牌字符串。
 */
fun setAuthToken(token: String)
    /**
 * 请求一组权限并指示这些权限是否已被授予。
 *
 * @param permissions 要请求的权限名列表（例如 Android 的 Manifest.permission.* 值）。
 * @return `true` 如果所有请求的权限都被授予，`false` 否则。
 */
fun requestPermissions(permissions: List<String>): Boolean
}

/**
 * Config模块查询处理器
 */
interface ConfigQueryHandler {
    /**
 * 获取当前已配置的设备标识符。
 *
 * @return 当前设备 ID 字符串。
 */
fun getDeviceId(): String
    /**
 * 获取当前配置的认证令牌以用于鉴权。
 *
 * @return 当前配置的认证令牌字符串。
 */
fun getAuthToken(): String
    /**
 * 获取当前已批准的权限列表。
 *
 * @return 当前已批准的权限名列表；如果没有已批准的权限，则返回空列表。
 */
fun checkPermissions(): List<String>
}

/**
 * Config模块接口（向后兼容）
 * @deprecated 使用ConfigCommandHandler和ConfigQueryHandler替代
 */
@Deprecated("使用ConfigCommandHandler和ConfigQueryHandler替代")
interface ConfigModule : ConfigCommandHandler, ConfigQueryHandler {
    /**
 * 获取当前设备标识。
 *
 * @return 当前配置的设备 ID 字符串。
 */
override fun getDeviceId(): String
    /**
 * 检索当前存储的认证令牌。
 *
 * @return 当前存储的认证令牌字符串。
 */
override fun getAuthToken(): String
    /**
 * 设置当前设备的标识，用于后续鉴权和配置关联。
 *
 * @param deviceId 要保存并关联到设备的标识字符串（例如设备 UUID 或注册 ID）。
 */
override fun setDeviceId(deviceId: String)
    /**
 * 设置用于后续鉴权请求的认证令牌。
 *
 * @param token 要保存的认证令牌，实现可将其持久化以供后续请求使用。
 */
override fun setAuthToken(token: String)
    /**
 * 获取当前已授予或已批准的权限列表。
 *
 * @return 当前已授予或已批准的权限名称列表；如果没有权限则返回空列表。
 */
override fun checkPermissions(): List<String>
    /**
 * 请求一组权限并返回请求结果。
 *
 * @param permissions 要请求的权限列表，使用平台权限名（例如 `android.Manifest.permission.*`）。
 * @return `true` 如果所有权限均已授予，`false` 否则。
 */
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

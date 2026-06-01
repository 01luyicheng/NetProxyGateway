package com.netproxy.gateway.ui.viewmodel

import java.security.SecureRandom

import javax.inject.Inject

import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext

import android.content.Context
import android.content.Intent
import android.net.VpnService as AndroidVpnService
import android.os.SystemClock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope

import com.netproxy.gateway.R
import com.netproxy.gateway.connection.AuthSessionStore
import com.netproxy.gateway.connection.MqttConnectionManager
import com.netproxy.gateway.connection.MqttConnectionState
import com.netproxy.gateway.connection.NetworkStateManager
import com.netproxy.gateway.connection.NetworkType
import com.netproxy.gateway.i18n.AppLocale
import com.netproxy.gateway.vpn.GatewayVpnService
import com.netproxy.gateway.vpn.VpnState
import com.netproxy.gateway.vpn.VpnStatus
import com.netproxy.gateway.wifi.GatewayWifiManager
import com.netproxy.gateway.wifi.WifiNetwork

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

enum class MqttUiState {
    Disconnected,
    Connecting,
    Connected,
    Error
}

data class UiState(
    val isConnected: Boolean = false,
    val isPaired: Boolean = false,
    val isPairingInProgress: Boolean = false,
    val peerId: String = "",
    val deviceId: String = "",
    val authToken: String = "",
    val isVpnEnabled: Boolean = false,
    val wifiConnected: Boolean = false,
    val cellularConnected: Boolean = false,
    val currentWifiSsid: String = "",
    val wifiNetworks: List<WifiNetwork> = emptyList(),
    val errorMessage: String? = null,
    val mqttState: MqttUiState = MqttUiState.Disconnected,
    val mqttErrorMessage: String? = null,
    val connectionDurationMs: Long = 0,
    val lastHeartbeatTimeMs: Long = 0,
    val heartbeatFailures: Int = 0,
    val reconnectCount: Int = 0,
    val vpnDetailedStatus: VpnStatus = VpnStatus(),
    val networkIsValidated: Boolean = false
)

@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val networkStateManager: NetworkStateManager,
    private val mqttConnectionManager: MqttConnectionManager,
    private val wifiManager: GatewayWifiManager,
    private val authSessionStore: AuthSessionStore
) : ViewModel() {

    private val secureRandom = SecureRandom()

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var connectionStartTime: Long = 0
    private var durationUpdateJob: kotlinx.coroutines.Job? = null

    init {
        val deviceId = authSessionStore.getOrCreateDeviceId()
        _uiState.update { it.copy(deviceId = deviceId) }

        observeNetworkState()
        observeMqttState()
        observeMqttDiagnostics()
        observeVpnStatus()
    }

    /**
     * 安全地获取 elapsedRealtime，在单元测试环境（未 mock）回退到 currentTimeMillis
     */
    private fun safeElapsedRealtime(): Long {
        return try {
            SystemClock.elapsedRealtime()
        } catch (e: RuntimeException) {
            System.currentTimeMillis()
        }
    }

    /**
     * 启动或重启一个定时器任务，定期更新 UI 状态中的 `connectionDurationMs` 字段以反映自连接开始后的已用时长。
     *
     * 该方法会先取消已有的定时器（若存在），然后每秒基于 `connectionStartTime` 计算持续时长并仅在值发生变化时将新时长写入 `_uiState`。
     */
    private fun startDurationTimer() {
        durationUpdateJob?.cancel()
        durationUpdateJob = viewModelScope.launch(Dispatchers.Default) {
            while (isActive) {
                kotlinx.coroutines.delay(1000)
                if (connectionStartTime > 0) {
                    val duration = safeElapsedRealtime() - connectionStartTime
                    _uiState.update { current ->
                        if (current.connectionDurationMs != duration) {
                            current.copy(connectionDurationMs = duration)
                        } else current
                    }
                }
            }
        }
    }

    /**
     * 取消正在运行的连接时长更新任务并清除其引用。
     *
     * 如果存在正在执行的计时协程，则将其取消并将内部作业引用设置为 null。
     */
    private fun stopDurationTimer() {
        durationUpdateJob?.cancel()
        durationUpdateJob = null
    }

    /**
     * 订阅网络状态并将相关字段同步到 UI 状态。
     *
     * 当网络状态发生变化时，更新 uiState 中的 `wifiConnected`、`cellularConnected`、`currentWifiSsid`
     * 和 `networkIsValidated` 字段以反映当前连接类型、当前 Wi‑Fi SSID（无值时为空串）以及网络验证状态。
     */
    private fun observeNetworkState() {
        viewModelScope.launch {
            networkStateManager.networkState.collect { networkState ->
                val wifiInfo = wifiManager.getCurrentConnection()
                _uiState.update { current ->
                    current.copy(
                        wifiConnected = networkState.networkType == NetworkType.Wifi,
                        cellularConnected = networkState.networkType == NetworkType.Cellular,
                        currentWifiSsid = wifiInfo?.ssid ?: "",
                        networkIsValidated = networkState.isValidated
                    )
                }
            }
        }
    }

    /**
     * 监听 MQTT 连接状态流并将对应的连接、配对、错误和连接时长信息映射到 UI 状态。
     *
     * 根据不同的 MQTT 连接状态更新 UI：
     * - Connected：记录连接起始时间、启动时长计时器，将 UI 标记为已连接且已配对，清除 MQTT 错误并重置时长。
     * - Connecting：标记配对进行中并清除 MQTT 错误。
     * - Disconnected：清除连接起始时间、停止时长计时器，将 UI 标记为断开且未配对，清除 MQTT 错误并重置时长。
     * - Error：清除连接起始时间、停止时长计时器，设置 UI 的错误消息和 MQTT 错误状态并重置时长。
     */
    private fun observeMqttState() {
        viewModelScope.launch {
            mqttConnectionManager.connectionState.collect { state ->
                when (state) {
                    is MqttConnectionState.Connected -> {
                        connectionStartTime = safeElapsedRealtime()
                        startDurationTimer()
                        _uiState.update {
                            it.copy(
                                isConnected = true,
                                isPaired = true,
                                isPairingInProgress = false,
                                mqttState = MqttUiState.Connected,
                                mqttErrorMessage = null,
                                connectionDurationMs = 0
                            )
                        }
                    }
                    is MqttConnectionState.Connecting -> {
                        _uiState.update {
                            it.copy(
                                isPairingInProgress = true,
                                mqttState = MqttUiState.Connecting,
                                mqttErrorMessage = null
                            )
                        }
                    }
                    is MqttConnectionState.Disconnected -> {
                        connectionStartTime = 0
                        stopDurationTimer()
                        _uiState.update {
                            it.copy(
                                isConnected = false,
                                isPaired = false,
                                isPairingInProgress = false,
                                mqttState = MqttUiState.Disconnected,
                                mqttErrorMessage = null,
                                connectionDurationMs = 0
                            )
                        }
                    }
                    is MqttConnectionState.Error -> {
                        connectionStartTime = 0
                        stopDurationTimer()
                        _uiState.update {
                            it.copy(
                                isConnected = false,
                                isPaired = false,
                                isPairingInProgress = false,
                                errorMessage = state.message,
                                mqttState = MqttUiState.Error,
                                mqttErrorMessage = state.message,
                                connectionDurationMs = 0
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * 监听 MQTT 诊断流并将诊断指标写入 UI 状态。
     *
     * 每当收到新的诊断数据时，更新 `lastHeartbeatTimeMs`、`heartbeatFailures` 和 `reconnectCount` 字段。
     */
    private fun observeMqttDiagnostics() {
        viewModelScope.launch {
            mqttConnectionManager.diagnostics.collect { diagnostics ->
                _uiState.update { current ->
                    current.copy(
                        lastHeartbeatTimeMs = diagnostics.lastHeartbeatTime,
                        heartbeatFailures = diagnostics.consecutiveHeartbeatFailures,
                        reconnectCount = diagnostics.reconnectCount
                    )
                }
            }
        }
    }

    /**
     * 监听 GatewayVpnService 的 VPN 状态流并将最新状态同步到 UI 状态。
     *
     * 根据收到的 `VpnStatus` 更新两个字段：`vpnDetailedStatus` 写入完整的状态对象，`isVpnEnabled` 按以下规则设置：
     * - 当 `VpnState.RUNNING` 时设为 `true`；
     * - 当 `VpnState.STOPPED` 或 `VpnState.ERROR` 时设为 `false`；
     * - 当 `VpnState.STARTING` 或 `VpnState.STOPPING` 时保持当前 `_uiState.value.isVpnEnabled` 的值不变。
     */
    private fun observeVpnStatus() {
        viewModelScope.launch {
            GatewayVpnService.status.collect { status ->
                val isEnabled = when (status.state) {
                    VpnState.RUNNING -> true
                    VpnState.STOPPED, VpnState.ERROR -> false
                    VpnState.STARTING, VpnState.STOPPING -> _uiState.value.isVpnEnabled
                }
                _uiState.update {
                    it.copy(
                        vpnDetailedStatus = status,
                        isVpnEnabled = isEnabled
                    )
                }
            }
        }
    }

    /**
     * 生成一个六位数字配对码并写入 UI 状态的 `peerId` 字段。
     *
     * 生成范围为 100000 到 999999 的随机整数并将其作为字符串保存到 `_uiState.peerId`。
     */
    fun generatePairingCode() {
        viewModelScope.launch {
            val code = (secureRandom.nextInt(900_000) + 100_000).toString()
            _uiState.update { it.copy(peerId = code) }
        }
    }

    /**
     * 使用给定的配对码更新配对状态并在满足网络条件时保存凭证并发起 MQTT 连接。
     *
     * 将 UI 状态的 `peerId` 设置为 `code` 并将 `isPairingInProgress` 置为 true，同时清除之前的错误信息。若当前为蜂窝网络连接，则把当前 `deviceId` 与该配对码写入持久存储（用于认证）并触发 MQTT 连接；若非蜂窝网络，则将 `isPairingInProgress` 复原为 false 并在 UI 中设置提示需要蜂窝网络的错误消息。
     *
     * @param code 要用于配对/认证的六位配对码，将作为 `peerId` 和身份验证令牌使用。
     */
    fun pairWithCode(code: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(peerId = code, isPairingInProgress = true, errorMessage = null) }

            if (networkStateManager.isCellularConnected()) {
                val deviceIdSnapshot = _uiState.value.deviceId
                authSessionStore.update(
                    deviceId = deviceIdSnapshot,
                    authToken = code
                )
                mqttConnectionManager.connect(
                    deviceId = deviceIdSnapshot,
                    authToken = code
                )
            } else {
                _uiState.update {
                    it.copy(isPairingInProgress = false, errorMessage = AppLocale.getString(context, R.string.error_cellular_required))
                }
            }
        }
    }

    /**
     * 清除 UI 状态中的错误消息。
     *
     * 将 `errorMessage` 和 `mqttErrorMessage` 设为 `null`。
     */
    fun clearErrorMessage() {
        _uiState.update { it.copy(errorMessage = null, mqttErrorMessage = null) }
    }

    /**
     * 启用或禁用应用内的 VPN 功能；在启用时如需用户授权会发起权限请求。
     *
     * 启用时会启动作为前台服务的 GatewayVpnService；若系统要求用户授予 VPN 权限，会启动请求权限的 Activity 并在 UI 状态中设置对应的错误提示。禁用时会发送停止指令给 GatewayVpnService。
     *
     * @param enable 为 `true` 时尝试启用 VPN，为 `false` 时停止已运行的 VPN 服务。
     */
    fun toggleVpn(enable: Boolean) {
        if (enable) {
            val prepareIntent = AndroidVpnService.prepare(context)
            if (prepareIntent != null) {
                prepareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(prepareIntent)
                _uiState.update {
                    it.copy(
                        errorMessage = AppLocale.getString(context, R.string.error_vpn_permission_required)
                    )
                }
                return
            }

            val intent = Intent(context, GatewayVpnService::class.java).apply {
                action = "START"
            }
            context.startForegroundService(intent)
        } else {
            val intent = Intent(context, GatewayVpnService::class.java).apply {
                action = "STOP"
            }
            context.startService(intent)
        }
    }

    /**
     * 启动一次 Wi‑Fi 扫描并将结果写入 UI 状态。
     *
     * 等待最多 10 秒以获取新的扫描结果；如果超时，则使用缓存的扫描结果作为回退。成功或回退时都会更新 UiState 中的 `wifiNetworks` 字段。
     */
    fun scanWifi() {
        viewModelScope.launch {
            try {
                val results = withTimeout(10_000) {
                    coroutineScope {
                        val deferred = async {
                            wifiManager.wifiScanResults.drop(1).first()
                        }
                        wifiManager.startScan()
                        deferred.await()
                    }
                }
                _uiState.update { it.copy(wifiNetworks = results) }
            } catch (_: TimeoutCancellationException) {
                val fallback = wifiManager.getScanResults()
                _uiState.update { it.copy(wifiNetworks = fallback) }
            }
        }
    }

    /**
     * 断开当前会话并重置相关状态。
     *
     * 执行后会断开 MQTT 连接、清除持久的认证会话、停止 VPN 并将 UI 状态中的连接与配对信息（isConnected、isPaired、peerId）重置为未连接/空值。
     */
    fun disconnect() {
        mqttConnectionManager.disconnect()
        authSessionStore.clear()
        toggleVpn(false)
        _uiState.update {
            it.copy(
                isConnected = false,
                isPaired = false,
                peerId = ""
            )
        }
    }
}

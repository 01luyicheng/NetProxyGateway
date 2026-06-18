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
import kotlinx.coroutines.flow.getAndUpdate
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
    val authToken: CharArray = CharArray(0),
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
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as UiState
        return isConnected == other.isConnected &&
            isPaired == other.isPaired &&
            isPairingInProgress == other.isPairingInProgress &&
            peerId == other.peerId &&
            deviceId == other.deviceId &&
            authToken.contentEquals(other.authToken) &&
            isVpnEnabled == other.isVpnEnabled &&
            wifiConnected == other.wifiConnected &&
            cellularConnected == other.cellularConnected &&
            currentWifiSsid == other.currentWifiSsid &&
            wifiNetworks == other.wifiNetworks &&
            errorMessage == other.errorMessage &&
            mqttState == other.mqttState &&
            mqttErrorMessage == other.mqttErrorMessage &&
            connectionDurationMs == other.connectionDurationMs &&
            lastHeartbeatTimeMs == other.lastHeartbeatTimeMs &&
            heartbeatFailures == other.heartbeatFailures &&
            reconnectCount == other.reconnectCount &&
            vpnDetailedStatus == other.vpnDetailedStatus &&
            networkIsValidated == other.networkIsValidated
    }

    override fun hashCode(): Int {
        var result = isConnected.hashCode()
        result = 31 * result + isPaired.hashCode()
        result = 31 * result + isPairingInProgress.hashCode()
        result = 31 * result + peerId.hashCode()
        result = 31 * result + deviceId.hashCode()
        result = 31 * result + authToken.contentHashCode()
        result = 31 * result + isVpnEnabled.hashCode()
        result = 31 * result + wifiConnected.hashCode()
        result = 31 * result + cellularConnected.hashCode()
        result = 31 * result + currentWifiSsid.hashCode()
        result = 31 * result + wifiNetworks.hashCode()
        result = 31 * result + (errorMessage?.hashCode() ?: 0)
        result = 31 * result + mqttState.hashCode()
        result = 31 * result + (mqttErrorMessage?.hashCode() ?: 0)
        result = 31 * result + connectionDurationMs.hashCode()
        result = 31 * result + lastHeartbeatTimeMs.hashCode()
        result = 31 * result + heartbeatFailures
        result = 31 * result + reconnectCount
        result = 31 * result + vpnDetailedStatus.hashCode()
        result = 31 * result + networkIsValidated.hashCode()
        return result
    }

    override fun toString(): String {
        return "UiState(isConnected=$isConnected, isPaired=$isPaired, isPairingInProgress=$isPairingInProgress, peerId='$peerId', deviceId='$deviceId', authToken=[REDACTED], isVpnEnabled=$isVpnEnabled, wifiConnected=$wifiConnected, cellularConnected=$cellularConnected, currentWifiSsid='$currentWifiSsid', wifiNetworks=$wifiNetworks, errorMessage=$errorMessage, mqttState=$mqttState, mqttErrorMessage=$mqttErrorMessage, connectionDurationMs=$connectionDurationMs, lastHeartbeatTimeMs=$lastHeartbeatTimeMs, heartbeatFailures=$heartbeatFailures, reconnectCount=$reconnectCount, vpnDetailedStatus=$vpnDetailedStatus, networkIsValidated=$networkIsValidated)"
    }
}

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

    private fun stopDurationTimer() {
        durationUpdateJob?.cancel()
        durationUpdateJob = null
    }

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
                        val tokenToZero = _uiState.getAndUpdate { current ->
                            current.copy(
                                isConnected = false,
                                isPaired = false,
                                isPairingInProgress = false,
                                mqttState = MqttUiState.Disconnected,
                                mqttErrorMessage = null,
                                connectionDurationMs = 0,
                                authToken = CharArray(0)
                            )
                        }.authToken
                        tokenToZero?.fill('\u0000')
                    }
                    is MqttConnectionState.Error -> {
                        connectionStartTime = 0
                        stopDurationTimer()
                        val tokenToZero = _uiState.getAndUpdate { current ->
                            current.copy(
                                isConnected = false,
                                isPaired = false,
                                isPairingInProgress = false,
                                errorMessage = state.message,
                                mqttState = MqttUiState.Error,
                                mqttErrorMessage = state.message,
                                connectionDurationMs = 0,
                                authToken = CharArray(0)
                            )
                        }.authToken
                        tokenToZero?.fill('\u0000')
                    }
                }
            }
        }
    }

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

    fun generatePairingCode() {
        viewModelScope.launch {
            val code = (secureRandom.nextInt(900_000) + 100_000).toString()
            _uiState.update { it.copy(peerId = code) }
        }
    }

    fun pairWithCode(code: String) {
        viewModelScope.launch {
            val authTokenArray = code.toCharArray()
            _uiState.update { it.copy(peerId = code, isPairingInProgress = true, errorMessage = null) }

            var sessionUpdated = false
            try {
                if (networkStateManager.isCellularConnected()) {
                    val deviceIdSnapshot = _uiState.value.deviceId
                    authSessionStore.update(
                        deviceId = deviceIdSnapshot,
                        authToken = authTokenArray
                    )
                    sessionUpdated = true
                    mqttConnectionManager.connect(
                        deviceId = deviceIdSnapshot,
                        authToken = authTokenArray
                    )
                    val tokenToZero = _uiState.getAndUpdate { current ->
                        current.copy(authToken = authTokenArray.copyOf())
                    }.authToken
                    tokenToZero?.fill('\u0000')
                } else {
                    val tokenToZero = _uiState.getAndUpdate { current ->
                        current.copy(isPairingInProgress = false, errorMessage = AppLocale.getString(context, R.string.error_cellular_required), authToken = CharArray(0))
                    }.authToken
                    tokenToZero?.fill('\u0000')
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                val tokenToZero = _uiState.getAndUpdate { current ->
                    current.copy(isPairingInProgress = false, errorMessage = e.message, authToken = CharArray(0))
                }.authToken
                tokenToZero?.fill('\u0000')
                if (sessionUpdated) {
                    val clearResult = authSessionStore.clearWithResult()
                    if (clearResult is com.netproxy.gateway.result.AppResult.Error) {
                        e.addSuppressed(clearResult.exception)
                    }
                }
            } finally {
                authTokenArray.fill('\u0000')
            }
        }
    }

    fun clearErrorMessage() {
        _uiState.update { it.copy(errorMessage = null, mqttErrorMessage = null) }
    }

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

    fun disconnect() {
        mqttConnectionManager.disconnect()
        authSessionStore.clear()
        val tokenToZero = _uiState.getAndUpdate { current ->
            current.copy(
                isConnected = false,
                isPaired = false,
                peerId = "",
                authToken = CharArray(0)
            )
        }.authToken
        tokenToZero?.fill('\u0000')
        toggleVpn(false)
    }

    override fun onCleared() {
        super.onCleared()
        val tokenToZero = _uiState.getAndUpdate { current ->
            current.copy(authToken = CharArray(0))
        }.authToken
        tokenToZero?.fill('\u0000')
    }
}

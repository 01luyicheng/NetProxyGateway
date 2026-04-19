package com.netproxy.gateway.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.net.VpnService as AndroidVpnService
import com.netproxy.gateway.connection.AuthSessionStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.netproxy.gateway.connection.MqttConnectionManager
import com.netproxy.gateway.connection.MqttConnectionState
import com.netproxy.gateway.connection.NetworkStateManager
import com.netproxy.gateway.connection.NetworkType
import com.netproxy.gateway.R
import com.netproxy.gateway.i18n.AppLocale
import com.netproxy.gateway.wifi.GatewayWifiManager
import com.netproxy.gateway.wifi.WifiNetwork
import com.netproxy.gateway.vpn.GatewayVpnService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.security.SecureRandom
import javax.inject.Inject

data class UiState(
    val isConnected: Boolean = false,
    val isPaired: Boolean = false,
    val peerId: String = "",
    val deviceId: String = "",
    val authToken: String = "",
    val isVpnEnabled: Boolean = false,
    val wifiConnected: Boolean = false,
    val cellularConnected: Boolean = false,
    val currentWifiSsid: String = "",
    val wifiNetworks: List<WifiNetwork> = emptyList(),
    val errorMessage: String? = null
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

    init {
        val deviceId = authSessionStore.getOrCreateDeviceId()
        _uiState.update { it.copy(deviceId = deviceId) }
        
        observeNetworkState()
        observeMqttState()
    }

    private fun observeNetworkState() {
        viewModelScope.launch {
            networkStateManager.networkState.collect { networkState ->
                val wifiInfo = wifiManager.getCurrentConnection()
                _uiState.update { current ->
                    current.copy(
                        wifiConnected = networkState.networkType == NetworkType.Wifi,
                        cellularConnected = networkState.networkType == NetworkType.Cellular,
                        currentWifiSsid = wifiInfo?.ssid ?: ""
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
                        _uiState.update { it.copy(isConnected = true, isPaired = true) }
                    }
                    is MqttConnectionState.Disconnected -> {
                        _uiState.update { it.copy(isConnected = false, isPaired = false) }
                    }
                    is MqttConnectionState.Error -> {
                        _uiState.update {
                            it.copy(isConnected = false, isPaired = false, errorMessage = state.message)
                        }
                    }
                    else -> {}
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
            _uiState.update { it.copy(peerId = code) }
            
            if (networkStateManager.isCellularConnected()) {
                authSessionStore.update(
                    deviceId = _uiState.value.deviceId,
                    authToken = code
                )
                mqttConnectionManager.connect(
                    deviceId = _uiState.value.deviceId,
                    authToken = code
                )
            } else {
                _uiState.update {
                    it.copy(errorMessage = AppLocale.getString(context, R.string.error_cellular_required))
                }
            }
        }
    }

    fun toggleVpn(enable: Boolean) {
        if (enable) {
            val prepareIntent = AndroidVpnService.prepare(context)
            if (prepareIntent != null) {
                prepareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(prepareIntent)
                _uiState.update {
                    it.copy(
                        isVpnEnabled = false,
                        errorMessage = AppLocale.getString(context, R.string.error_vpn_permission_required)
                    )
                }
                return
            }

            val intent = Intent(context, GatewayVpnService::class.java).apply {
                action = "START"
            }
            context.startForegroundService(intent)
            _uiState.update {
                it.copy(
                    isVpnEnabled = true,
                    errorMessage = null
                )
            }
        } else {
            val intent = Intent(context, GatewayVpnService::class.java).apply {
                action = "STOP"
            }
            context.startService(intent)
            _uiState.update { it.copy(isVpnEnabled = false) }
        }
    }

    fun scanWifi() {
        viewModelScope.launch {
            wifiManager.startScan()
            
            kotlinx.coroutines.delay(2000)
            
            val results = wifiManager.getScanResults()
            _uiState.update { it.copy(wifiNetworks = results) }
        }
    }

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

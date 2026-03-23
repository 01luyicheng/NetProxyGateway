package com.netproxy.gateway.di

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.netproxy.gateway.connection.AuthSessionStore
import com.netproxy.gateway.connection.MqttConnectionManager
import com.netproxy.gateway.connection.NetworkState
import com.netproxy.gateway.connection.NetworkStateManager
import com.netproxy.gateway.ui.viewmodel.UiState
import com.netproxy.gateway.vpn.GatewayVpnService
import com.netproxy.gateway.vpn.VpnStatus
import com.netproxy.gateway.vpn.VpnState
import com.netproxy.gateway.wifi.GatewayWifiManager
import com.netproxy.gateway.wifi.WifiConnectionInfo
import com.netproxy.gateway.wifi.WifiNetwork
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

	@Provides
	@Singleton
	fun provideCoreModule(networkStateManager: NetworkStateManager): CoreModule {
		return object : CoreModule {
			private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
			private val networkState = MutableStateFlow(NetworkState())

			init {
				scope.launch {
					networkStateManager.networkState.collect { state ->
						networkState.value = state
					}
				}
			}

			override fun getNetworkState(): StateFlow<NetworkState> = networkState

			override fun getCoroutineScope(): CoroutineScope = scope
		}
	}

	@Provides
	@Singleton
	fun provideCommunicationModule(mqttConnectionManager: MqttConnectionManager): CommunicationModule {
		return object : CommunicationModule {
			override fun connect(deviceId: String, authToken: String): Boolean {
				mqttConnectionManager.connect(deviceId, authToken)
				return true
			}

			override fun publish(topic: String, payload: String, qos: Int) {
				mqttConnectionManager.publish(topic, payload, qos)
			}

			override fun subscribe(topic: String, qos: Int, callback: (String) -> Unit) {
				mqttConnectionManager.subscribe(topic, qos, callback)
			}

			override fun getConnectionState(): StateFlow<com.netproxy.gateway.connection.MqttConnectionState> {
				return mqttConnectionManager.connectionState
			}

			override fun disconnect() {
				mqttConnectionManager.disconnect()
			}
		}
	}

	@Provides
	@Singleton
	fun provideNetworkModule(@ApplicationContext context: Context): NetworkModule {
		return object : NetworkModule {
			private val vpnStatus = MutableStateFlow(VpnStatus())
			private val proxyStatus = MutableStateFlow(false)

			override fun startVpn(): Boolean {
				val intent = Intent(context, GatewayVpnService::class.java).apply {
					action = "START"
				}
				context.startForegroundService(intent)
				vpnStatus.value = VpnStatus(state = VpnState.STARTING)
				return true
			}

			override fun stopVpn(): Boolean {
				val intent = Intent(context, GatewayVpnService::class.java).apply {
					action = "STOP"
				}
				context.startService(intent)
				vpnStatus.value = VpnStatus(state = VpnState.STOPPED)
				return true
			}

			override fun startProxy(): Boolean {
				proxyStatus.value = true
				return true
			}

			override fun stopProxy(): Boolean {
				proxyStatus.value = false
				return true
			}

			override fun getVpnStatus(): StateFlow<VpnStatus> = vpnStatus

			override fun getProxyStatus(): StateFlow<Boolean> = proxyStatus
		}
	}

	@Provides
	@Singleton
	fun provideWiFiModule(wifiManager: GatewayWifiManager): WiFiModule {
		return object : WiFiModule {
			private val networks = MutableStateFlow<List<WifiNetwork>>(emptyList())

			override fun scanWiFi(): List<WifiNetwork> {
				wifiManager.startScan()
				val results = wifiManager.getScanResults()
				networks.value = results
				return results
			}

			override fun connectToWiFi(ssid: String, password: String?, securityType: String): Boolean {
				return wifiManager.connectToNetwork(ssid, password, securityType)
			}

			override fun disconnectWiFi(): Boolean {
				return wifiManager.disconnect()
			}

			override fun getCurrentConnection(): WifiConnectionInfo? {
				return wifiManager.getCurrentConnection()
			}

			override fun getWifiNetworks(): StateFlow<List<WifiNetwork>> = networks
		}
	}

	@Provides
	@Singleton
	fun provideUIModule(): UIModule {
		return object : UIModule {
			private val uiState = MutableStateFlow(UiState())

			override fun updateUiState(state: UiState) {
				uiState.value = state
			}

			override fun getUiState(): StateFlow<UiState> = uiState
		}
	}

	@Provides
	@Singleton
	fun provideConfigModule(
		@ApplicationContext context: Context,
		authSessionStore: AuthSessionStore
	): ConfigModule {
		return object : ConfigModule {
			private val prefs = context.getSharedPreferences("gateway_config", Context.MODE_PRIVATE)

			override fun getDeviceId(): String {
				return prefs.getString("device_id", "") ?: ""
			}

			override fun getAuthToken(): String {
				return authSessionStore.getCurrentSession()?.authToken ?: ""
			}

			override fun setDeviceId(deviceId: String) {
				prefs.edit().putString("device_id", deviceId).apply()
				authSessionStore.getCurrentSession()?.let { session ->
					authSessionStore.update(deviceId, session.authToken)
				}
			}

			override fun setAuthToken(token: String) {
				val deviceId = getDeviceId()
				if (deviceId.isNotBlank()) {
					authSessionStore.update(deviceId, token)
				}
			}

			override fun checkPermissions(): List<String> {
				val requiredPermissions = listOf(
					android.Manifest.permission.ACCESS_FINE_LOCATION,
					android.Manifest.permission.ACCESS_NETWORK_STATE,
					android.Manifest.permission.CHANGE_WIFI_STATE
				)
				return requiredPermissions.filter { permission ->
					ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
				}
			}

			override fun requestPermissions(permissions: List<String>): Boolean {
				return true
			}
		}
	}
}

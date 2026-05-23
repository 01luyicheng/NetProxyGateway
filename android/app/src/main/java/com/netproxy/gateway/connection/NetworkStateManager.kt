package com.netproxy.gateway.connection

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

sealed class NetworkType {
    object Wifi : NetworkType()
    object Cellular : NetworkType()
    object Ethernet : NetworkType()
    object None : NetworkType()
}

data class NetworkState(
    val isConnected: Boolean = false,
    val networkType: NetworkType = NetworkType.None,
    val isValidated: Boolean = false,
    val network: Network? = null
)

@Singleton
class NetworkStateManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val activeNetworks = ConcurrentHashMap<Network, NetworkCapabilities>()

    val networkState: Flow<NetworkState> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val capabilities = connectivityManager.getNetworkCapabilities(network)
                if (capabilities != null) {
                    activeNetworks[network] = capabilities
                    trySend(getBestNetworkState())
                }
            }

            override fun onLost(network: Network) {
                activeNetworks.remove(network)
                trySend(getBestNetworkState())
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                activeNetworks[network] = networkCapabilities
                trySend(getBestNetworkState())
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        // 注册前同步当前已连接的网络到 activeNetworks
        syncActiveNetworks()

        connectivityManager.registerNetworkCallback(request, callback)

        // 使用统一的状态获取逻辑发送初始状态
        trySend(getBestNetworkState())

        awaitClose {
            connectivityManager.unregisterNetworkCallback(callback)
        }
    }.distinctUntilChanged()

    private fun syncActiveNetworks() {
        connectivityManager.allNetworks.forEach { network ->
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            if (capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                activeNetworks[network] = capabilities
            }
        }
    }

    private fun buildNetworkState(network: Network, capabilities: NetworkCapabilities): NetworkState {
        val networkType = resolveNetworkType(capabilities)
        return NetworkState(
            isConnected = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            networkType = networkType,
            isValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            network = network
        )
    }

    internal fun getBestNetworkState(): NetworkState {
        if (activeNetworks.isEmpty()) {
            return NetworkState(isConnected = false, networkType = NetworkType.None)
        }

        val bestNetwork = activeNetworks.maxByOrNull { getNetworkPriority(it.value) }

        return bestNetwork?.let { (network, capabilities) ->
            buildNetworkState(network, capabilities)
        } ?: NetworkState(isConnected = false, networkType = NetworkType.None)
    }

    private fun resolveNetworkType(capabilities: NetworkCapabilities): NetworkType {
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.Wifi
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.Cellular
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.Ethernet
            else -> NetworkType.None
        }
    }

    private fun getNetworkPriority(capabilities: NetworkCapabilities): Int {
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 3
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 2
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 1
            else -> 0
        }
    }

    fun getCurrentNetworkType(): NetworkType {
        return getBestNetworkState().networkType
    }

    fun isWifiConnected(): Boolean {
        return getCurrentNetworkType() == NetworkType.Wifi
    }

    fun isCellularConnected(): Boolean {
        return getCurrentNetworkType() == NetworkType.Cellular
    }
}

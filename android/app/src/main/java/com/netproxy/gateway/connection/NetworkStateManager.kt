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

    val networkState: Flow<NetworkState> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(getCurrentNetworkState(network))
            }

            override fun onLost(network: Network) {
                trySend(getStateAfterNetworkLost())
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                trySend(getCurrentNetworkState(network))
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        connectivityManager.registerNetworkCallback(request, callback)

        // Emit initial state
        trySend(getCurrentNetworkState(null))

        awaitClose {
            connectivityManager.unregisterNetworkCallback(callback)
        }
    }.distinctUntilChanged()

    internal fun getCurrentNetworkState(network: Network?): NetworkState {
        val activeNetwork = network ?: connectivityManager.activeNetwork
        val capabilities = activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }

        if (capabilities == null) {
            return NetworkState(isConnected = false, networkType = NetworkType.None)
        }

        val networkType = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.Wifi
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.Cellular
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.Ethernet
            else -> NetworkType.None
        }

        return NetworkState(
            isConnected = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            networkType = networkType,
            isValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            network = activeNetwork
        )
    }

    internal fun getStateAfterNetworkLost(): NetworkState {
        return getCurrentNetworkState(null)
    }

    fun getCurrentNetworkType(): NetworkType {
        return getCurrentNetworkState(null).networkType
    }

    fun isWifiConnected(): Boolean {
        return getCurrentNetworkType() == NetworkType.Wifi
    }

    fun isCellularConnected(): Boolean {
        return getCurrentNetworkType() == NetworkType.Cellular
    }
}

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
            /**
             * 处理系统报告的网络可用事件：当网络具有所需能力时将其加入活动网络缓存并发送更新后的最佳网络状态。
             *
             * @param network 新可用的 `Network` 实例。
             */
            override fun onAvailable(network: Network) {
                val capabilities = connectivityManager.getNetworkCapabilities(network)
                if (isValidNetwork(capabilities)) {
                    activeNetworks[network] = capabilities!!
                    trySend(getBestNetworkState())
                }
            }

            /**
             * 处理网络断开事件：移除对应网络并将更新后的最佳网络状态发送到观察者。
             *
             * @param network 已丢失且不再可用的网络。
             */
            override fun onLost(network: Network) {
                activeNetworks.remove(network)
                trySend(getBestNetworkState())
            }

            /**
             * 在网络能力变化时更新活动网络缓存并发出当前最佳网络状态。
             *
             * 如果提供的 `networkCapabilities` 被视为有效，则将其写入 `activeNetworks`，否则从 `activeNetworks` 中移除该 `network`；随后通过 `trySend` 发送 `getBestNetworkState()`。
             *
             * @param network 发生能力变化的网络。
             * @param networkCapabilities 该网络的新能力信息，用于决定是否将网络视为有效并更新缓存。
             */
            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                if (isValidNetwork(networkCapabilities)) {
                    activeNetworks[network] = networkCapabilities
                } else {
                    activeNetworks.remove(network)
                }
                trySend(getBestNetworkState())
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        // 先清空，再同步，再注册，避免与 callback 竞态
        activeNetworks.clear()
        syncActiveNetworks()
        connectivityManager.registerNetworkCallback(request, callback)

        // 使用统一的状态获取逻辑发送初始状态
        trySend(getBestNetworkState())

        awaitClose {
            connectivityManager.unregisterNetworkCallback(callback)
            activeNetworks.clear()
        }
    }.distinctUntilChanged()

    /**
     * 判断给定的 `NetworkCapabilities` 是否表示一个可用且已验证的互联网网络。
     *
     * @param capabilities 要检查的 `NetworkCapabilities`，可能为 `null`。
     * @return `true` 如果 `capabilities` 非空且同时具备 `NET_CAPABILITY_INTERNET` 和 `NET_CAPABILITY_VALIDATED`，`false` 否则。
     */
    private fun isValidNetwork(capabilities: NetworkCapabilities?): Boolean {
        return capabilities != null &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /**
     * 从系统当前可见的网络列表同步并缓存所有被视为有效的网络能力到 `activeNetworks`。
     *
     * 遍历 `connectivityManager.allNetworks`，获取每个网络的 `NetworkCapabilities`，并在满足 `isValidNetwork` 时将该网络及其能力存入 `activeNetworks`。
     */
    private fun syncActiveNetworks() {
        connectivityManager.allNetworks.forEach { network ->
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            if (isValidNetwork(capabilities)) {
                activeNetworks[network] = capabilities!!
            }
        }
    }

    /**
     * 根据给定的 Network 及其 NetworkCapabilities 构造对应的 NetworkState。
     *
     * @param network 要描述的 Android `Network` 实例。
     * @param capabilities 与该 network 关联的 `NetworkCapabilities`，用于决定连接性、验证状态与传输类型。
     * @return 一个包含以下信息的 `NetworkState`：`isConnected`（是否具有 NET_CAPABILITY_INTERNET）、`isValidated`（是否具有 NET_CAPABILITY_VALIDATED）、`networkType`（由 capabilities 决定的传输类型）以及原始的 `network` 引用。
     */
    private fun buildNetworkState(network: Network, capabilities: NetworkCapabilities): NetworkState {
        val networkType = resolveNetworkType(capabilities)
        return NetworkState(
            isConnected = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            networkType = networkType,
            isValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            network = network
        )
    }

    /**
     * 从当前活动网络缓存中选择优先级最高的网络并返回该网络对应的网络状态。
     *
     * @return `NetworkState`：若存在优先级最高的网络，则基于该网络及其 `NetworkCapabilities` 构建并返回对应状态；否则返回一个 `isConnected = false` 且 `networkType = NetworkType.None` 的默认状态。
     */
    internal fun getBestNetworkState(): NetworkState {
        val bestNetwork = activeNetworks.maxByOrNull { getNetworkPriority(it.value) }
        return bestNetwork?.let { (network, capabilities) ->
            buildNetworkState(network, capabilities)
        } ?: NetworkState(isConnected = false, networkType = NetworkType.None)
    }

    /**
     * 根据 NetworkCapabilities 的传输类型解析并返回对应的 NetworkType。
     *
     * 会按以太网、Wi‑Fi、蜂窝的优先顺序检测传输类型；未匹配时返回 `NetworkType.None`。
     *
     * @param capabilities 表示网络能力的对象，用于检查其传输类型。
     * @return `NetworkType.Ethernet`、`NetworkType.Wifi`、`NetworkType.Cellular` 或 `NetworkType.None`，对应检测到的传输类型。
     */
    private fun resolveNetworkType(capabilities: NetworkCapabilities): NetworkType {
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.Ethernet
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.Wifi
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.Cellular
            else -> NetworkType.None
        }
    }

    /**
     * 为给定网络能力计算用于比较的优先级值。
     *
     * 基于传输类型分配基础优先级；若具备已验证能力（NET_CAPABILITY_VALIDATED）则在基础上加 100，
     * 使已验证网络相比未验证网络具有显著更高的优先级。
     *
     * @param capabilities 要评估的 NetworkCapabilities。
     * @return 计算得到的优先级整数；数值越大表示优先级越高。
     */
    private fun getNetworkPriority(capabilities: NetworkCapabilities): Int {
        val basePriority = when (resolveNetworkType(capabilities)) {
            NetworkType.Wifi -> 3
            NetworkType.Cellular -> 2
            NetworkType.Ethernet -> 4
            NetworkType.None -> 0
        }
        val validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        return if (validated) basePriority + 100 else basePriority
    }

    /**
     * 获取当前被认为是“最佳”网络的传输类型。
     *
     * @return 当前最佳网络的 `NetworkType`（`Wifi`、`Cellular`、`Ethernet` 或 `None`）。
     */
    fun getCurrentNetworkType(): NetworkType {
        return getBestNetworkState().networkType
    }

    /**
     * 判断当前最佳网络是否为 WiFi。
     *
     * @return `true` 如果当前最佳网络为 WiFi，`false` 否则。
     */
    fun isWifiConnected(): Boolean {
        return getCurrentNetworkType() == NetworkType.Wifi
    }

    fun isCellularConnected(): Boolean {
        return getCurrentNetworkType() == NetworkType.Cellular
    }
}

package com.netproxy.gateway.connection

import android.content.Context
import android.util.Log
import com.netproxy.gateway.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLContext
import java.security.KeyStore
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory

sealed class MqttConnectionState {
    object Disconnected : MqttConnectionState()
    object Connecting : MqttConnectionState()
    object Connected : MqttConnectionState()
    data class Error(val message: String) : MqttConnectionState()
}

@Singleton
class MqttConnectionManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "MqttConnectionManager"

        private const val CLIENT_ID = "NetProxyGateway"
        private const val HEARTBEAT_INTERVAL = 30000L
        private const val CONNECTION_TIMEOUT_SECONDS = 10
        private const val MAX_RECONNECT_DELAY = 60000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var mqttClient: MqttClient? = null
    private var reconnectDelay = 5000L
    private var reconnectJob: Job? = null
    private var heartbeatJob: Job? = null
    @Volatile private var shouldStayConnected: Boolean = false
    private val connectionGeneration = AtomicLong(0)

    private val _connectionState = MutableStateFlow<MqttConnectionState>(MqttConnectionState.Disconnected)
    val connectionState: StateFlow<MqttConnectionState> = _connectionState.asStateFlow()

    private val _messages = MutableStateFlow<String?>(null)
    val messages: StateFlow<String?> = _messages.asStateFlow()
    private val topicCallbacks = ConcurrentHashMap<String, CopyOnWriteArrayList<(String) -> Unit>>()

    private fun isTlsEnabled(): Boolean = BuildConfig.MQTT_USE_TLS

    private fun brokerUrl(): String = if (isTlsEnabled()) {
        BuildConfig.MQTT_BROKER_URL_TLS
    } else {
        BuildConfig.MQTT_BROKER_URL_PLAIN
    }

    private fun validateBrokerUrl(url: String) {
        val normalized = url.trim().lowercase()
        require(!normalized.contains("your-server")) { "MQTT broker URL must not use placeholder host" }
        if (isTlsEnabled()) {
            require(normalized.startsWith("ssl://")) { "TLS-enabled MQTT must use ssl:// URL" }
        }
    }

    private fun createSecureSocketFactory(): SSLSocketFactory {
        val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        trustManagerFactory.init(null as KeyStore?)

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustManagerFactory.trustManagers, SecureRandom())
        return sslContext.socketFactory
    }

    fun connect(deviceId: String, authToken: String) {
        shouldStayConnected = true
        reconnectJob?.cancel()
        val generation = connectionGeneration.incrementAndGet()
        scope.launch {
            try {
                _connectionState.value = MqttConnectionState.Connecting

                // 根据配置选择 MQTT 连接 URL
                val brokerUrl = brokerUrl()
                validateBrokerUrl(brokerUrl)
                val clientId = "${CLIENT_ID}_$deviceId"
                mqttClient?.close()
                mqttClient = MqttClient(brokerUrl, clientId, MemoryPersistence())

                val options = MqttConnectOptions().apply {
                    isCleanSession = true
                    connectionTimeout = CONNECTION_TIMEOUT_SECONDS
                    keepAliveInterval = 30
                    userName = deviceId
                    password = authToken.toCharArray()
                    setAutomaticReconnect(false) // We handle reconnection manually

                    if (isTlsEnabled()) {
                        socketFactory = createSecureSocketFactory()
                    }
                }

                mqttClient?.setCallback(object : MqttCallback {
                    override fun connectionLost(cause: Throwable?) {
                        if (generation != connectionGeneration.get()) {
                            return
                        }
                        Log.w(TAG, "Connection lost: ${cause?.message}")
                        _connectionState.value = MqttConnectionState.Error(cause?.message ?: "Connection lost")
                        if (shouldStayConnected) {
                            scheduleReconnect(deviceId, authToken, generation)
                        }
                    }

                    override fun messageArrived(topic: String?, message: MqttMessage?) {
                        message?.let {
                            val payload = String(it.payload)
                            _messages.value = payload
                            Log.d(TAG, "Message received: $topic - $payload")
                            if (topic != null) {
                                topicCallbacks[topic]?.forEach { callback ->
                                    callback(payload)
                                }
                            }
                        }
                    }

                    override fun deliveryComplete(token: IMqttDeliveryToken?) {
                        // Message delivered
                    }
                })

                mqttClient?.connect(options)
                if (!shouldStayConnected || generation != connectionGeneration.get()) {
                    mqttClient?.disconnect()
                    mqttClient?.close()
                    mqttClient = null
                    _connectionState.value = MqttConnectionState.Disconnected
                    return@launch
                }
                _connectionState.value = MqttConnectionState.Connected
                reconnectDelay = 5000L

                subscribe("device/$deviceId/control")
                startHeartbeat(deviceId)

            } catch (e: MqttException) {
                if (generation != connectionGeneration.get()) {
                    return@launch
                }
                Log.e(TAG, "MQTT connection error: ${e.message}")
                _connectionState.value = MqttConnectionState.Error(e.message ?: "Connection failed")
                if (shouldStayConnected) {
                    scheduleReconnect(deviceId, authToken, generation)
                }
            }
        }
    }

    private fun scheduleReconnect(deviceId: String, authToken: String, generation: Long) {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(reconnectDelay)
            if (!shouldStayConnected || generation != connectionGeneration.get()) {
                return@launch
            }
            reconnectDelay = minOf(reconnectDelay * 2, MAX_RECONNECT_DELAY)
            connect(deviceId, authToken)
        }
    }

    private fun startHeartbeat(deviceId: String) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (shouldStayConnected && _connectionState.value == MqttConnectionState.Connected) {
                delay(HEARTBEAT_INTERVAL)
                publish("device/$deviceId/heartbeat", "{\"status\":\"alive\"}")
            }
        }
    }

    fun publish(topic: String, payload: String, qos: Int = 0) {
        publishWithResult(topic, payload, qos)
    }

    fun publishWithResult(topic: String, payload: String, qos: Int = 0): Result<Unit> {
        try {
            val client = mqttClient ?: return Result.failure(IllegalStateException("MQTT client is not connected"))
            val message = MqttMessage(payload.toByteArray()).apply {
                this.qos = qos
            }
            client.publish(topic, message)
            return Result.success(Unit)
        } catch (e: MqttException) {
            Log.e(TAG, "Publish error: ${e.message}")
            return Result.failure(e)
        }
    }

    fun subscribe(topic: String, qos: Int = 0, callback: ((String) -> Unit)? = null) {
        subscribeWithResult(topic, qos, callback)
    }

    fun subscribeWithResult(topic: String, qos: Int = 0, callback: ((String) -> Unit)? = null): Result<Unit> {
        try {
            val client = mqttClient ?: return Result.failure(IllegalStateException("MQTT client is not connected"))
            callback?.let {
                topicCallbacks.computeIfAbsent(topic) { CopyOnWriteArrayList() }.add(it)
            }
            client.subscribe(topic, qos)
            return Result.success(Unit)
        } catch (e: MqttException) {
            Log.e(TAG, "Subscribe error: ${e.message}")
            return Result.failure(e)
        }
    }

    fun disconnect() {
        shouldStayConnected = false
        connectionGeneration.incrementAndGet()
        reconnectJob?.cancel()
        heartbeatJob?.cancel()
        try {
            mqttClient?.disconnect()
            mqttClient?.close()
            mqttClient = null
            _connectionState.value = MqttConnectionState.Disconnected
        } catch (e: MqttException) {
            Log.e(TAG, "Disconnect error: ${e.message}")
        }
    }
}

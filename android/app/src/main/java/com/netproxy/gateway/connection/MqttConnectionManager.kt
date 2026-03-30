package com.netproxy.gateway.connection

import android.content.Context
import com.netproxy.gateway.BuildConfig
import com.netproxy.gateway.di.ApplicationScope
import com.netproxy.gateway.result.AppResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
import org.slf4j.LoggerFactory
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
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
    @ApplicationContext private val context: Context,
    @ApplicationScope private val scope: CoroutineScope
) {
    companion object {
        private val logger = LoggerFactory.getLogger(MqttConnectionManager::class.java)

        private const val CLIENT_ID = "NetProxyGateway"
        private const val HEARTBEAT_INTERVAL = 30000L
        private const val CONNECTION_TIMEOUT_SECONDS = 10
        private const val MAX_RECONNECT_DELAY = 60000L
    }

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

    /**
     * 创建SSLSocketFactory
     * 根据BuildConfig配置决定使用哪种证书验证方式：
     * - 生产环境：使用系统默认CA证书（验证服务器证书）
     * - 开发环境：信任所有证书（仅用于开发测试自签名证书）
     * TODO: 上线前将BuildConfig.MQTT_TRUST_ALL_CERTS改为false
     */
    private fun createSecureSocketFactory(): SSLSocketFactory {
        // 安全检查：生产环境(DEBUG=false)且启用信任所有证书时抛出异常
        if (!BuildConfig.DEBUG && BuildConfig.MQTT_TRUST_ALL_CERTS) {
            throw IllegalStateException(
                "TRUST_ALL_CERTS is not allowed in production builds. " +
                "Please set MQTT_TRUST_ALL_CERTS to false in build configuration."
            )
        }

        return if (BuildConfig.MQTT_TRUST_ALL_CERTS) {
            // 开发模式：信任所有证书（支持自签名证书）
            createDevSocketFactory()
        } else {
            // 生产模式：使用系统默认CA证书
            createProductionSocketFactory()
        }
    }

    /**
     * 生产环境：使用系统默认CA证书验证
     */
    private fun createProductionSocketFactory(): SSLSocketFactory {
        val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        trustManagerFactory.init(null as KeyStore?)

        val defaultTrustManager = trustManagerFactory.trustManagers
            .filterIsInstance<X509TrustManager>()
            .firstOrNull()
            ?: throw IllegalStateException("No X509TrustManager available for MQTT TLS")

        val configuredPins = MqttTlsPinning.parseConfiguredPins(BuildConfig.MQTT_TLS_PUBLIC_KEY_PINS)
        if (configuredPins.isEmpty()) {
            logger.warn("MQTT TLS pinning is disabled: MQTT_TLS_PUBLIC_KEY_PINS is empty. Falling back to default CA validation.")
        }
        val pinningTrustManager = MqttTlsPinning.createPinningTrustManager(
            delegate = defaultTrustManager,
            rawPins = BuildConfig.MQTT_TLS_PUBLIC_KEY_PINS
        )

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(pinningTrustManager), SecureRandom())
        return sslContext.socketFactory
    }

    /**
     * 开发环境：信任所有证书（仅用于开发测试）
     * 警告：此方式不安全，仅用于开发环境连接自签名证书服务器
     * 安全限制：仅在 BuildConfig.DEBUG 为 true 时允许使用
     */
    private fun createDevSocketFactory(): SSLSocketFactory {
        logger.warn("Using development SSL socket factory - trusts all certificates!")
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                // 开发环境：信任所有客户端证书
            }
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                // 开发环境：信任所有服务器证书（包括自签名）
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustAllCerts, SecureRandom())
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
                        logger.warn("Connection lost: ${cause?.message}")
                        _connectionState.value = MqttConnectionState.Error(cause?.message ?: "Connection lost")
                        if (shouldStayConnected) {
                            scheduleReconnect(deviceId, authToken, generation)
                        }
                    }

                    override fun messageArrived(topic: String?, message: MqttMessage?) {
                        message?.let {
                            val payload = String(it.payload)
                            _messages.value = payload
                            logger.debug("Message received: $topic - $payload")
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
                logger.error("MQTT connection error: ${e.message}")
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

    fun publishWithResult(topic: String, payload: String, qos: Int = 0): AppResult<Unit> {
        return try {
            val client = mqttClient ?: return AppResult.error(IllegalStateException("MQTT client is not connected"))
            val message = MqttMessage(payload.toByteArray()).apply {
                this.qos = qos
            }
            client.publish(topic, message)
            AppResult.success(Unit)
        } catch (e: MqttException) {
            logger.error("Publish error: ${e.message}")
            AppResult.error(e)
        }
    }

    fun subscribe(topic: String, qos: Int = 0, callback: ((String) -> Unit)? = null) {
        subscribeWithResult(topic, qos, callback)
    }

    fun subscribeWithResult(topic: String, qos: Int = 0, callback: ((String) -> Unit)? = null): AppResult<Unit> {
        return try {
            val client = mqttClient ?: return AppResult.error(IllegalStateException("MQTT client is not connected"))
            callback?.let {
                topicCallbacks.computeIfAbsent(topic) { CopyOnWriteArrayList() }.add(it)
            }
            client.subscribe(topic, qos)
            AppResult.success(Unit)
        } catch (e: MqttException) {
            logger.error("Subscribe error: ${e.message}")
            AppResult.error(e)
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
            logger.error("Disconnect error: ${e.message}")
        }
    }
}

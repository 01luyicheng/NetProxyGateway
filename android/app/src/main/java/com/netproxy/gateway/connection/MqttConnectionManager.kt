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
import javax.net.ssl.HttpsURLConnection
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
        private const val RECONNECT_BACKOFF_MULTIPLIER = 2
        private const val MAX_HEARTBEAT_FAILURES = 3
    }

    @Volatile private var mqttClient: MqttClient? = null
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
     * 根据 BuildConfig 配置决定使用哪种证书验证方式：
     * - release 构建：使用系统默认 CA 证书（验证服务器证书，release 构建安全）
     * - debug 构建：信任所有证书（仅用于开发测试自签名证书，禁止用于生产）
     * 
     * 安全限制：MQTT_TRUST_ALL_CERTS 在 release 构建中必须为 false
     */
    private fun createSecureSocketFactory(): SSLSocketFactory {
        // 安全检查：release 构建 (DEBUG=false) 不允许启用信任所有证书
        if (!BuildConfig.DEBUG && BuildConfig.MQTT_TRUST_ALL_CERTS) {
            throw IllegalStateException(
                "TRUST_ALL_CERTS is not allowed in release builds. " +
                "Please set MQTT_TRUST_ALL_CERTS to false in build configuration."
            )
        }

        return if (BuildConfig.MQTT_TRUST_ALL_CERTS) {
            // debug 构建：信任所有证书（支持自签名证书）
            createDevSocketFactory()
        } else {
            // release 构建：使用系统默认 CA 证书
            createProductionSocketFactory()
        }
    }

    /**
     * release 构建：使用系统默认 CA 证书验证
     * 安全特性：
     * - 使用 TLS 1.2（兼容 Android 5.0+）
     * - 启用主机名验证
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

        // 显式指定 TLS 1.2（Android 5.0+ 支持）
        val sslContext = SSLContext.getInstance("TLSv1.2")
        sslContext.init(null, arrayOf<TrustManager>(pinningTrustManager), SecureRandom())
        return sslContext.socketFactory
    }

    /**
     * debug 构建：信任所有证书（仅用于开发测试）
     * 警告：此方式不安全，仅用于 debug 构建连接自签名证书服务器
     * 安全限制：仅在 BuildConfig.DEBUG 为 true 时允许使用
     * 注意：debug 版本仍启用主机名验证以防止中间人攻击
     */
    private fun createDevSocketFactory(): SSLSocketFactory {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                // debug 构建：信任所有客户端证书
            }
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                // debug 构建：信任所有服务器证书（包括自签名）
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        // 显式指定 TLS 1.2
        val sslContext = SSLContext.getInstance("TLSv1.2")
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

                // 1. 在同步块内只获取旧客户端引用并清空 mqttClient
                val oldClient = synchronized(this@MqttConnectionManager) {
                    mqttClient.also { mqttClient = null }
                }

                // 2. 在同步块外执行 close() IO 操作（避免阻塞其他线程调用 disconnect()）
                oldClient?.close()

                // 3. 在同步块外创建新客户端
                val newClient = MqttClient(brokerUrl, clientId, MemoryPersistence())

                // 4. 在新同步块内设置新客户端
                val localClient = synchronized(this@MqttConnectionManager) {
                    mqttClient = newClient
                    newClient
                }

                val options = MqttConnectOptions().apply {
                    isCleanSession = true
                    connectionTimeout = CONNECTION_TIMEOUT_SECONDS
                    keepAliveInterval = 30
                    userName = deviceId
                    password = authToken.toCharArray()
                    setAutomaticReconnect(false) // We handle reconnection manually

                    if (isTlsEnabled()) {
                        socketFactory = createSecureSocketFactory()
                        // 启用主机名验证，防止中间人攻击
                        // 使用 Android 默认的主机名验证器（与 HTTPS 相同）
                        sslHostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
                    }
                }

                localClient.setCallback(object : MqttCallback {
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
                            try {
                                val payload = String(it.payload)
                                _messages.value = payload
                                logger.debug("Message received: $topic - $payload")
                                if (topic != null) {
                                    topicCallbacks[topic]?.forEach { callback ->
                                        try {
                                            callback(payload)
                                        } catch (e: Exception) {
                                            logger.error("Callback error for topic $topic", e)
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                logger.error("Message processing error", e)
                            }
                        }
                    }

                    override fun deliveryComplete(token: IMqttDeliveryToken?) {
                        // Message delivered
                    }
                })

                localClient.connect(options)

                // 使用 synchronized 块保护所有状态检查和更新，防止竞态条件
                val shouldProceed = synchronized(this@MqttConnectionManager) {
                    // 检查是否仍应保持连接且 generation 匹配
                    if (!shouldStayConnected || generation != connectionGeneration.get()) {
                        // 只有当前连接仍是有效引用时才清理
                        if (mqttClient === localClient) {
                            mqttClient = null
                        }
                        false
                    } else {
                        // 确认是当前有效连接，可以设置为 Connected
                        if (mqttClient === localClient) {
                            _connectionState.value = MqttConnectionState.Connected
                            reconnectDelay = 5000L
                            true
                        } else {
                            // mqttClient 已被其他线程替换，不设置状态
                            false
                        }
                    }
                }

                if (!shouldProceed) {
                    // 在同步块外执行关闭操作
                    try {
                        localClient.disconnect()
                    } catch (e: MqttException) {
                        logger.error("Disconnect error", e)
                    } finally {
                        try {
                            localClient.close()
                        } catch (e: Exception) {
                            logger.error("Close error", e)
                        }
                    }
                    // 注意：不在此处设置状态，因为：
                    // 1. 如果是 generation 过期，状态可能已被新连接设置
                    // 2. 如果是 disconnect() 被调用，状态已在 disconnect() 中设置
                    return@launch
                }

                subscribe("device/$deviceId/control")
                startHeartbeat(deviceId, authToken, generation)

            } catch (e: Exception) {
                if (generation != connectionGeneration.get()) {
                    return@launch
                }
                logger.error("MQTT connection error", e)
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
            reconnectDelay = minOf(reconnectDelay * RECONNECT_BACKOFF_MULTIPLIER, MAX_RECONNECT_DELAY)
            connect(deviceId, authToken)
        }
    }

    private fun startHeartbeat(deviceId: String, authToken: String, generation: Long) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            var consecutiveFailures = 0
            while (shouldStayConnected && _connectionState.value == MqttConnectionState.Connected) {
                delay(HEARTBEAT_INTERVAL)
                try {
                    publish("device/$deviceId/heartbeat", "{\"status\":\"alive\"}")
                    consecutiveFailures = 0
                } catch (e: Exception) {
                    logger.error("Heartbeat publish error", e)
                    consecutiveFailures++
                    if (consecutiveFailures >= MAX_HEARTBEAT_FAILURES) {
                        logger.warn("Max heartbeat failures reached, triggering reconnect")
                        _connectionState.value = MqttConnectionState.Error("Max heartbeat failures reached")
                        if (shouldStayConnected && generation == connectionGeneration.get()) {
                            scheduleReconnect(deviceId, authToken, generation)
                        }
                        break
                    }
                }
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
        } catch (e: Exception) {
            logger.error("Publish error", e)
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
            callback?.let {
                topicCallbacks[topic]?.remove(it)
                if (topicCallbacks[topic]?.isEmpty() == true) {
                    topicCallbacks.remove(topic)
                }
            }
            logger.error("Subscribe error", e)
            AppResult.error(e)
        }
    }

    fun disconnect() {
        shouldStayConnected = false
        connectionGeneration.incrementAndGet()
        reconnectJob?.cancel()
        heartbeatJob?.cancel()
        topicCallbacks.clear()
        val client = synchronized(this) {
            val c = mqttClient
            mqttClient = null
            c
        }
        _connectionState.value = MqttConnectionState.Disconnected
        try {
            client?.disconnect()
        } catch (e: MqttException) {
            logger.error("Disconnect error", e)
        } finally {
            try {
                client?.close()
            } catch (e: Exception) {
                logger.error("Close error", e)
            }
        }
    }
}

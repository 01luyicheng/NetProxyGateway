package com.netproxy.gateway.connection

import android.content.Context
import com.netproxy.gateway.BuildConfig
import com.netproxy.gateway.di.ApplicationScope
import com.netproxy.gateway.result.AppResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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
    private var connectJob: Job? = null
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
        var generation = 0L
        lateinit var jobToStart: Job
        synchronized(this@MqttConnectionManager) {
            shouldStayConnected = true
            reconnectJob?.cancel()
            reconnectJob = null
            heartbeatJob?.cancel()
            heartbeatJob = null
            connectJob?.cancel()
            connectJob = null

            generation = connectionGeneration.incrementAndGet()
            jobToStart = scope.launch(start = CoroutineStart.LAZY) {
                var localClient: MqttClient? = null
                try {
                    if (!shouldStayConnected || generation != connectionGeneration.get()) {
                        return@launch
                    }

                    ensureActive()

                // 根据配置选择 MQTT 连接 URL
                val brokerUrl = brokerUrl()
                validateBrokerUrl(brokerUrl)
                val clientId = "${CLIENT_ID}_$deviceId"

                // 在同一个同步块内完成"generation 校验 + 交换客户端引用"，避免并发 connect() 覆盖 mqttClient
                val (oldClient, createdClient) = synchronized(this@MqttConnectionManager) {
                    if (!shouldStayConnected || generation != connectionGeneration.get()) {
                        null
                    } else {
                        val created = MqttClient(brokerUrl, clientId, MemoryPersistence())
                        val old = mqttClient
                        mqttClient = created
                        old to created
                    }
                } ?: return@launch

                // 在 generation 校验通过后更新状态，避免竞态条件
                _connectionState.value = MqttConnectionState.Connecting

                localClient = createdClient

                // 在同步块外执行 close() IO 操作（避免阻塞其他线程调用 disconnect()）
                if (oldClient != null) {
                    try {
                        if (oldClient.isConnected) {
                            oldClient.disconnect()
                        }
                    } catch (disconnectError: MqttException) {
                        logger.error("Disconnect old MQTT client error", disconnectError)
                    } finally {
                        try {
                            oldClient.close()
                        } catch (closeError: Exception) {
                            logger.error("Close old MQTT client error", closeError)
                        }
                    }
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

                createdClient.setCallback(object : MqttCallback {
                    override fun connectionLost(cause: Throwable?) {
                        if (!shouldStayConnected || generation != connectionGeneration.get()) {
                            return
                        }
                        logger.warn("Connection lost: ${cause?.message}")
                        _connectionState.value = MqttConnectionState.Error(cause?.message ?: "Connection lost")
                        if (shouldStayConnected) {
                            scheduleReconnect(deviceId, authToken, generation)
                        }
                    }

                    override fun messageArrived(topic: String?, message: MqttMessage?) {
                        if (!shouldStayConnected || generation != connectionGeneration.get()) {
                            return
                        }
                        message?.let {
                            try {
                                val payload = String(it.payload)
                                _messages.value = payload
                                logger.debug("Message received: $topic (payloadBytes=${it.payload.size})")
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

                createdClient.connect(options)

                ensureActive()

                // 使用 synchronized 块保护所有状态检查和更新，防止竞态条件
                val shouldProceed = synchronized(this@MqttConnectionManager) {
                    // 检查是否仍应保持连接且 generation 匹配
                    if (!shouldStayConnected || generation != connectionGeneration.get()) {
                        // 只有当前连接仍是有效引用时才清理
                        if (mqttClient === createdClient) {
                            mqttClient = null
                        }
                        false
                    } else {
                        // 确认是当前有效连接，可以设置为 Connected
                        if (mqttClient === createdClient) {
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
                        createdClient.disconnect()
                    } catch (e: MqttException) {
                        logger.error("Disconnect error", e)
                    } finally {
                        try {
                            createdClient.close()
                        } catch (e: Exception) {
                            logger.error("Close error", e)
                        }
                    }
                    // 注意：不在此处设置状态，因为：
                    // 1. 如果是 generation 过期，状态可能已被新连接设置
                    // 2. 如果是 disconnect() 被调用，状态已在 disconnect() 中设置
                    return@launch
                }

                val shouldMarkConnected = synchronized(this@MqttConnectionManager) {
                    shouldStayConnected &&
                        generation == connectionGeneration.get() &&
                        mqttClient === createdClient
                }
                if (!shouldMarkConnected) {
                    synchronized(this@MqttConnectionManager) {
                        if (mqttClient === createdClient) {
                            mqttClient = null
                        }
                    }
                    try {
                        createdClient.disconnect()
                    } catch (e: MqttException) {
                        logger.error("Disconnect error", e)
                    } finally {
                        try {
                            createdClient.close()
                        } catch (e: Exception) {
                            logger.error("Close error", e)
                        }
                    }
                    return@launch
                }

                ensureActive()
                if (!shouldStayConnected || generation != connectionGeneration.get()) {
                    return@launch
                }
                _connectionState.value = MqttConnectionState.Connected

                subscribe("device/$deviceId/control")
                startHeartbeat(deviceId, authToken, generation)

                } catch (e: Exception) {
                    val clientToClose = localClient
                    if (clientToClose != null) {
                        synchronized(this@MqttConnectionManager) {
                            if (mqttClient === clientToClose) {
                                mqttClient = null
                            }
                        }

                        try {
                            clientToClose.disconnect()
                        } catch (ex: MqttException) {
                            logger.error("Disconnect error during exception handling", ex)
                        } finally {
                            try {
                                clientToClose.close()
                            } catch (ex: Exception) {
                                logger.error("Close error during exception handling", ex)
                            }
                        }
                    }

                    if (e is CancellationException) {
                        throw e
                    }

                    if (generation != connectionGeneration.get()) {
                        return@launch
                    }
                    logger.error("MQTT connection error", e)
                    if (!shouldStayConnected || generation != connectionGeneration.get()) {
                        return@launch
                    }
                    _connectionState.value = MqttConnectionState.Error(e.message ?: "Connection failed")
                    if (shouldStayConnected) {
                        scheduleReconnect(deviceId, authToken, generation)
                    }
                }
            }

            connectJob = jobToStart
        }

        jobToStart.start()
    }

    private fun scheduleReconnect(deviceId: String, authToken: String, generation: Long) {
        lateinit var jobToStart: Job
        synchronized(this@MqttConnectionManager) {
            reconnectJob?.cancel()
            jobToStart = scope.launch(start = CoroutineStart.LAZY) {
                val delayMs = synchronized(this@MqttConnectionManager) { reconnectDelay }
                delay(delayMs)
                if (!shouldStayConnected || generation != connectionGeneration.get()) {
                    return@launch
                }
                synchronized(this@MqttConnectionManager) {
                    reconnectDelay = minOf(reconnectDelay * RECONNECT_BACKOFF_MULTIPLIER, MAX_RECONNECT_DELAY)
                }
                connect(deviceId, authToken)
            }
            reconnectJob = jobToStart
        }
        jobToStart.start()
    }

    private fun startHeartbeat(deviceId: String, authToken: String, generation: Long) {
        if (!shouldStayConnected || generation != connectionGeneration.get()) {
            return
        }

        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            var consecutiveFailures = 0
            while (
                shouldStayConnected &&
                generation == connectionGeneration.get() &&
                _connectionState.value == MqttConnectionState.Connected
            ) {
                delay(HEARTBEAT_INTERVAL)
                if (!shouldStayConnected || generation != connectionGeneration.get()) {
                    break
                }
                val result = publishWithResult(
                    "device/$deviceId/heartbeat",
                    "{\"status\":\"alive\"}",
                    logError = false
                )
                if (result.isSuccess()) {
                    consecutiveFailures = 0
                    continue
                }

                val heartbeatException = result.exceptionOrNull()
                if (heartbeatException == null) {
                    logger.error("Heartbeat publish error")
                } else {
                    val summary = when (heartbeatException) {
                        is MqttException -> "MqttException(reasonCode=${heartbeatException.reasonCode})"
                        else -> heartbeatException.javaClass.simpleName
                    }
                    logger.error("Heartbeat publish error: $summary")
                }
                consecutiveFailures++
                if (consecutiveFailures >= MAX_HEARTBEAT_FAILURES) {
                    logger.warn("Max heartbeat failures reached, triggering reconnect")
                    if (shouldStayConnected && generation == connectionGeneration.get()) {
                        _connectionState.value = MqttConnectionState.Error("Max heartbeat failures reached")
                        scheduleReconnect(deviceId, authToken, generation)
                    }
                    break
                }
            }
        }
    }

    fun publish(topic: String, payload: String, qos: Int = 0) {
        publishWithResult(topic, payload, qos)
    }

    fun publishWithResult(topic: String, payload: String, qos: Int = 0, logError: Boolean = true): AppResult<Unit> {
        return try {
            val client = mqttClient ?: return AppResult.error(IllegalStateException("MQTT client is not connected"))
            val message = MqttMessage(payload.toByteArray()).apply {
                this.qos = qos
            }
            client.publish(topic, message)
            AppResult.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (logError) {
                logger.error("Publish error", e)
            }
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
        val client = synchronized(this@MqttConnectionManager) {
            shouldStayConnected = false
            connectionGeneration.incrementAndGet()
            reconnectJob?.cancel()
            reconnectJob = null
            heartbeatJob?.cancel()
            heartbeatJob = null
            connectJob?.cancel()
            connectJob = null
            reconnectDelay = 5000L

            val c = mqttClient
            mqttClient = null
            c
        }
        topicCallbacks.clear()
        _connectionState.value = MqttConnectionState.Disconnected

        if (client == null) {
            return
        }

        scope.launch {
            try {
                client.disconnect()
            } catch (e: MqttException) {
                logger.error("Disconnect error", e)
            } finally {
                try {
                    client.close()
                } catch (e: Exception) {
                    logger.error("Close error", e)
                }
            }
        }
    }
}

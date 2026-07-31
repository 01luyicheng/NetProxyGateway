package com.netproxy.gateway.connection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.slf4j.LoggerFactory

import dagger.hilt.android.qualifiers.ApplicationContext

import android.content.Context

import com.netproxy.gateway.BuildConfig
import com.netproxy.gateway.debug.AppAuditLogStore
import com.netproxy.gateway.di.ApplicationScope
import com.netproxy.gateway.result.AppResult
import com.netproxy.gateway.utils.securelyClear

sealed class MqttConnectionState {
    object Disconnected : MqttConnectionState()
    object Connecting : MqttConnectionState()
    object Connected : MqttConnectionState()
    data class Error(val message: String) : MqttConnectionState()
}

data class MqttDiagnostics(
    val connectionGeneration: Long = 0,
    val reconnectDelay: Long = 5000L,
    val reconnectCount: Int = 0,
    val lastHeartbeatTime: Long = 0,
    val consecutiveHeartbeatFailures: Int = 0
)

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
        private const val INITIAL_RECONNECT_DELAY = 5000L
        private const val MAX_RECONNECT_DELAY = 60000L
        private const val RECONNECT_BACKOFF_MULTIPLIER = 2
        private const val MAX_HEARTBEAT_FAILURES = 3

        private val secureRandom by lazy { SecureRandom() }
    }

    private @Volatile var mqttClient: MqttClient? = null
    private var reconnectDelay = INITIAL_RECONNECT_DELAY
    private var reconnectJob: Job? = null
    private var heartbeatJob: Job? = null
    private var connectJob: Job? = null
    private @Volatile var shouldStayConnected: Boolean = false
    private val connectionGeneration = AtomicLong(0)
    private var activeTokenSnapshot: CharArray? = null

    private val _connectionState = MutableStateFlow<MqttConnectionState>(MqttConnectionState.Disconnected)
    val connectionState: StateFlow<MqttConnectionState> = _connectionState.asStateFlow()

    private val _diagnostics = MutableStateFlow(MqttDiagnostics())
    val diagnostics: StateFlow<MqttDiagnostics> = _diagnostics.asStateFlow()

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

    internal fun buildConnectionLostAuditMessage(
        cause: Throwable?,
        isDebugBuild: Boolean = BuildConfig.DEBUG
    ): String {
        return if (isDebugBuild) {
            "Connection lost: ${cause?.message ?: "unknown reason"}"
        } else {
            "Connection lost"
        }
    }

    internal fun buildConnectionErrorAuditMessage(
        error: Throwable,
        isDebugBuild: Boolean = BuildConfig.DEBUG
    ): String {
        return if (isDebugBuild) {
            "Connection error: ${error.message ?: "unknown"}"
        } else {
            "Connection error"
        }
    }

    internal fun shouldTrustAllCertificatesForCurrentBuild(isDebugBuild: Boolean = BuildConfig.DEBUG): Boolean {
        return false
    }

    /**
     * 安全关闭 MQTT 客户端
     * M21修复：使用 withContext(Dispatchers.IO) 包装阻塞IO操作，
     * 防止 disconnect() (默认30秒超时) 和 close() 阻塞 Default 调度器
     *
     * @param client 要关闭的 MQTT 客户端
     * @param logContext 日志上下文标识
     * @param checkConnected 是否先检查 isConnected
     * @param rethrowCancellation 是否重新抛出 CancellationException
     */
    private suspend fun safeCloseMqttClient(
        client: MqttClient,
        logContext: String,
        checkConnected: Boolean = false,
        rethrowCancellation: Boolean = false
    ) {
        // M21: 在 IO 调度器上执行阻塞操作，避免阻塞 Default 调度器
        withContext(Dispatchers.IO) {
            try {
                if (!checkConnected || client.isConnected) {
                    client.disconnect()
                }
            } catch (e: CancellationException) {
                if (rethrowCancellation) throw e
            } catch (e: MqttException) {
                logger.error("Disconnect error ($logContext)", e)
            } finally {
                try {
                    client.close()
                } catch (e: Exception) {
                    logger.error("Close error ($logContext)", e)
                }
            }
        }
    }

    /**
     * 证书策略：
     * - release 构建：始终使用生产证书校验。
     * - debug 构建：可通过高级开关临时跳过证书校验。
     */
    private fun createSecureSocketFactory(): SSLSocketFactory {
        // 防御性检查：生产环境绝对不能允许信任所有证书
        if (!BuildConfig.DEBUG && BuildConfig.MQTT_TRUST_ALL_CERTS) {
            throw IllegalStateException(
                "SECURITY VIOLATION: MQTT_TRUST_ALL_CERTS is enabled in non-debug build. " +
                "This would bypass all TLS certificate validation and is insecure."
            )
        }

        AppAuditLogStore.info("MQTT", "TLS certificate validation enabled")
        return createProductionSocketFactory()
    }

    /**
     * 统一创建 TLS 1.2 SSLContext
     */
    private fun createSSLContext(trustManagers: Array<TrustManager>?): SSLContext {
        val sslContext = SSLContext.getInstance("TLSv1.2")
        sslContext.init(null, trustManagers, secureRandom)
        return sslContext
    }

    /**
     * release 构建：使用系统默认 CA 证书验证
     * 安全特性：
     * - 使用 TLS 1.2（兼容 Android 5.0+）
     * - 启用主机名验证
     * - Release 构建强制要求配置证书固定（防止配置遗漏导致不安全连接）
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
            // M20 修复：Release 构建强制要求证书固定，防止配置遗漏导致意外使用不安全验证
            if (!BuildConfig.DEBUG) {
                throw IllegalStateException(
                    "SECURITY VIOLATION: MQTT_TLS_PUBLIC_KEY_PINS is not configured in release build. " +
                    "Certificate pinning is mandatory for release builds to prevent MITM attacks."
                )
            }
            // Debug 构建允许空配置，仅记录警告
            logger.warn("MQTT TLS pinning is disabled: MQTT_TLS_PUBLIC_KEY_PINS is empty. Falling back to default CA validation.")
            AppAuditLogStore.warn(
                "MQTT",
                "TLS pinning not configured; fallback to system CA validation"
            )
        }
        val pinningTrustManager = MqttTlsPinning.createPinningTrustManager(
            delegate = defaultTrustManager,
            rawPins = BuildConfig.MQTT_TLS_PUBLIC_KEY_PINS
        )

        return createSSLContext(arrayOf<TrustManager>(pinningTrustManager)).socketFactory
    }



    fun connect(deviceId: String, authToken: CharArray) {
        var generation = 0L
        lateinit var jobToStart: Job
        synchronized(this@MqttConnectionManager) {
            activeTokenSnapshot.securelyClear()
            activeTokenSnapshot = authToken.copyOf()
            shouldStayConnected = true
            reconnectJob?.cancel()
            reconnectJob = null
            heartbeatJob?.cancel()
            heartbeatJob = null
            connectJob?.cancel()
            connectJob = null

            generation = connectionGeneration.incrementAndGet()
            _diagnostics.update {
                MqttDiagnostics(
                    connectionGeneration = generation,
                    reconnectDelay = reconnectDelay
                )
            }
            jobToStart = scope.launch(start = CoroutineStart.LAZY) {
                val tokenSnapshot = synchronized(this@MqttConnectionManager) {
                    activeTokenSnapshot?.copyOf()
                } ?: CharArray(0)
                var localClient: MqttClient? = null
                var connectOptions: MqttConnectOptions? = null
                try {
                    if (!shouldStayConnected || generation != connectionGeneration.get()) {
                        return@launch
                    }

                    ensureActive()

                // 根据配置选择 MQTT 连接 URL
                val brokerUrl = brokerUrl()
                validateBrokerUrl(brokerUrl)
                val clientId = "${CLIENT_ID}_$deviceId"

                // 在同一个同步块内完成"generation 校验 + 交换客户端引用 + 状态更新"，
                // 避免并发 connect()/disconnect() 覆盖 mqttClient 或状态。
                // 状态更新必须在 synchronized 块内，否则 disconnect() 可能在状态设置前
                // 将状态设为 Disconnected，随后被 Connecting 覆盖，导致状态机永久卡死。
                val (oldClient, createdClient) = synchronized(this@MqttConnectionManager) {
                    if (!shouldStayConnected || generation != connectionGeneration.get()) {
                        null
                    } else {
                        val created = MqttClient(brokerUrl, clientId, MemoryPersistence())
                        val old = mqttClient
                        mqttClient = created
                        _connectionState.value = MqttConnectionState.Connecting
                        _diagnostics.update { it.copy(connectionGeneration = generation) }
                        old to created
                    }
                } ?: return@launch

                localClient = createdClient

                // 在同步块外执行 close() IO 操作（避免阻塞其他线程调用 disconnect()）
                if (oldClient != null) {
                    safeCloseMqttClient(oldClient, "old client cleanup", checkConnected = true)
                }

                val options = MqttConnectOptions().apply {
                    isCleanSession = true
                    connectionTimeout = CONNECTION_TIMEOUT_SECONDS
                    keepAliveInterval = 30
                    userName = deviceId
                    password = tokenSnapshot
                    setAutomaticReconnect(false) // We handle reconnection manually
                }
                // Assign connectOptions immediately after password is set so that
                // the finally block can clear the Paho-internal CharArray copy even
                // if TLS configuration below throws.  Paho's setPassword() copies
                // the CharArray via Arrays.copyOf(), so options.password and
                // tokenSnapshot are independent — only connectOptions?.password
                // can clear the Paho copy.
                connectOptions = options

                if (isTlsEnabled()) {
                    options.socketFactory = createSecureSocketFactory()
                    // 启用主机名验证，防止中间人攻击
                    // 使用 Android 默认的主机名验证器（与 HTTPS 相同）
                    options.sslHostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
                }

                createdClient.setCallback(object : MqttCallback {
                    override fun connectionLost(cause: Throwable?) {
                        if (!shouldStayConnected || generation != connectionGeneration.get()) {
                            return
                        }
                        logger.warn("Connection lost: ${cause?.message}")
                        AppAuditLogStore.warn(
                            "MQTT",
                            buildConnectionLostAuditMessage(cause)
                        )
                        synchronized(this@MqttConnectionManager) {
                            if (!shouldStayConnected || generation != connectionGeneration.get()) {
                                return@synchronized
                            }
                            _connectionState.value = MqttConnectionState.Error(cause?.message ?: "Connection lost")
                            if (shouldStayConnected) {
                                scheduleReconnect(deviceId, generation)
                            }
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
                            reconnectDelay = INITIAL_RECONNECT_DELAY
                            _diagnostics.update { it.copy(reconnectDelay = INITIAL_RECONNECT_DELAY) }
                            _connectionState.value = MqttConnectionState.Connected
                            _diagnostics.update { it.copy(lastHeartbeatTime = System.currentTimeMillis()) }
                            true
                        } else {
                            // mqttClient 已被其他线程替换，不设置状态
                            false
                        }
                    }
                }

                if (!shouldProceed) {
                    // 在同步块外执行关闭操作
                    safeCloseMqttClient(createdClient, "connection abort")
                    // 注意：不在此处设置状态，因为：
                    // 1. 如果是 generation 过期，状态可能已被新连接设置
                    // 2. 如果是 disconnect() 被调用，状态已在 disconnect() 中设置
                    return@launch
                }

                ensureActive()
                if (!shouldStayConnected || generation != connectionGeneration.get()) {
                    return@launch
                }
                AppAuditLogStore.info("MQTT", "Connection established")

                // N80: 先触发已注册的 topicCallbacks，再执行默认订阅
                // 这样 VpnService 中通过 subscribe() 注册的 disconnect 监听器会被保留
                startHeartbeat(deviceId, generation)

                } catch (e: Exception) {
                    val clientToClose = localClient
                    if (clientToClose != null) {
                        synchronized(this@MqttConnectionManager) {
                            if (mqttClient === clientToClose) {
                                mqttClient = null
                            }
                        }

                        safeCloseMqttClient(clientToClose, "exception handling")
                    }

                    if (e is CancellationException) {
                        throw e
                    }

                    if (generation != connectionGeneration.get()) {
                        return@launch
                    }
                    logger.error("MQTT connection error", e)
                    AppAuditLogStore.error("MQTT", buildConnectionErrorAuditMessage(e))
                    synchronized(this@MqttConnectionManager) {
                        if (!shouldStayConnected || generation != connectionGeneration.get()) {
                            return@synchronized
                        }
                        _connectionState.value = MqttConnectionState.Error(e.message ?: "Connection failed")
                        if (shouldStayConnected) {
                            onReconnectAttemptFailed()
                            scheduleReconnect(deviceId, generation)
                        }
                    }
                } finally {
                    tokenSnapshot.securelyClear()
                    // CR14-1: Paho MqttConnectOptions.setPassword() internally copies the
                    // CharArray via Arrays.copyOf(), so options.password and tokenSnapshot
                    // are independent.  Clear the copy held by MqttConnectOptions so that
                    // the password does not linger in heap memory after the client is closed.
                    connectOptions?.password?.securelyClear()
                }
            }

            connectJob = jobToStart
            jobToStart.start()
        }
    }

    private fun onReconnectAttemptFailed() {
        synchronized(this) {
            reconnectDelay = minOf(
                reconnectDelay * RECONNECT_BACKOFF_MULTIPLIER,
                MAX_RECONNECT_DELAY
            )
            _diagnostics.update { it.copy(reconnectDelay = reconnectDelay) }
        }
    }

    private fun scheduleReconnect(deviceId: String, generation: Long) {
        lateinit var jobToStart: Job
        synchronized(this@MqttConnectionManager) {
            reconnectJob?.cancel()
            jobToStart = scope.launch(start = CoroutineStart.LAZY) {
                val tokenCopy = synchronized(this@MqttConnectionManager) {
                    activeTokenSnapshot?.copyOf()
                } ?: CharArray(0)
                try {
                    val delayMs = synchronized(this@MqttConnectionManager) { reconnectDelay }
                    delay(delayMs)
                    if (!shouldStayConnected || generation != connectionGeneration.get()) {
                        return@launch
                    }
                    synchronized(this@MqttConnectionManager) {
                        _diagnostics.update {
                            it.copy(
                                reconnectDelay = reconnectDelay,
                                reconnectCount = it.reconnectCount + 1
                            )
                        }
                    }
                    connect(deviceId, tokenCopy)
                } finally {
                    tokenCopy.securelyClear()
                }
            }
            reconnectJob = jobToStart
            jobToStart.start()
        }
    }

    private fun startHeartbeat(deviceId: String, generation: Long) {
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
                    _diagnostics.update {
                        it.copy(
                            lastHeartbeatTime = System.currentTimeMillis(),
                            consecutiveHeartbeatFailures = 0
                        )
                    }
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
                _diagnostics.update {
                    it.copy(consecutiveHeartbeatFailures = consecutiveFailures)
                }
                if (consecutiveFailures >= MAX_HEARTBEAT_FAILURES) {
                    logger.warn("Max heartbeat failures reached, triggering reconnect")
                    AppAuditLogStore.warn("MQTT", "Max heartbeat failures reached; reconnecting")
                    if (shouldStayConnected && generation == connectionGeneration.get()) {
                        _connectionState.value = MqttConnectionState.Error("Max heartbeat failures reached")
                        onReconnectAttemptFailed()
                        scheduleReconnect(deviceId, generation)
                    }
                    break
                }
            }
        }
    }

    fun publish(topic: String, payload: String, qos: Int = 0) {
        publishWithResult(topic, payload, qos)
    }

    private fun formatConnectionState(state: MqttConnectionState): String {
        return when (state) {
            MqttConnectionState.Disconnected -> "Disconnected"
            MqttConnectionState.Connecting -> "Connecting"
            MqttConnectionState.Connected -> "Connected"
            is MqttConnectionState.Error -> "Error(${state.message})"
        }
    }

    private fun notConnectedOperationError(operation: String, state: MqttConnectionState): IllegalStateException {
        return IllegalStateException(
            "Cannot $operation because MQTT is not connected (currentState=${formatConnectionState(state)})"
        )
    }

    fun publishWithResult(topic: String, payload: String, qos: Int = 0, logError: Boolean = true): AppResult<Unit> {
        return try {
            val state = _connectionState.value
            if (state != MqttConnectionState.Connected) {
                return AppResult.error(notConnectedOperationError("publish", state))
            }
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
            val state = _connectionState.value
            if (state != MqttConnectionState.Connected) {
                return AppResult.error(notConnectedOperationError("subscribe", state))
            }
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
        AppAuditLogStore.info("MQTT", "Disconnect requested")
        val client = synchronized(this@MqttConnectionManager) {
            shouldStayConnected = false
            connectionGeneration.incrementAndGet()
            reconnectJob?.cancel()
            reconnectJob = null
            heartbeatJob?.cancel()
            heartbeatJob = null
            connectJob?.cancel()
            connectJob = null
            reconnectDelay = INITIAL_RECONNECT_DELAY

            activeTokenSnapshot.securelyClear()
            activeTokenSnapshot = null

            val c = mqttClient
            mqttClient = null
            topicCallbacks.clear()
            _connectionState.value = MqttConnectionState.Disconnected
            _diagnostics.update { MqttDiagnostics() }

            c
        }

        if (client == null) {
            return
        }

        scope.launch {
            safeCloseMqttClient(client, "disconnect", rethrowCancellation = true)
        }
    }
}

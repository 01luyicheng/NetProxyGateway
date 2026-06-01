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
import com.netproxy.gateway.debug.DebugSettingsStore
import com.netproxy.gateway.di.ApplicationScope
import com.netproxy.gateway.result.AppResult

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

    /**
     * 验证 MQTT broker URL 的格式并强制 TLS 相关约束。
     *
     * @param url 待验证的 broker URL 字符串（允许包含前后空白，会在比较前被修剪并转为小写）。
     * @throws IllegalArgumentException 当 URL 包含占位符主机名 `your-server` 时抛出。
     * @throws IllegalArgumentException 当启用 TLS 且 URL 不以 `ssl://` 开头时抛出。
     */
    private fun validateBrokerUrl(url: String) {
        val normalized = url.trim().lowercase()
        require(!normalized.contains("your-server")) { "MQTT broker URL must not use placeholder host" }
        if (isTlsEnabled()) {
            require(normalized.startsWith("ssl://")) { "TLS-enabled MQTT must use ssl:// URL" }
        }
    }

    /**
     * 构建用于审计的“连接丢失”消息字符串。
     *
     * 当 isDebugBuild 为 true 时，消息会包含 cause 的 message（若为 null 则使用 "unknown reason"）；否则返回不含细节的通用文本 "Connection lost"。
     *
     * @param cause 导致连接丢失的异常，可能为 null；仅在调试构建中其 message 会被包含进审计消息。
     * @param isDebugBuild 指示是否为调试构建；为 true 时在消息中包含 cause 的详细信息。
     * @return 审计消息字符串；调试模式下为 "Connection lost: <原因或 unknown reason>"，发布模式下为 "Connection lost"。
     */
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

    /**
     * 构建用于审计的连接错误消息。
     *
     * @param error 要报告的异常，其 message 可能会被包含在审计消息中。
     * @param isDebugBuild 当为 `true` 时在消息中包含 `error.message`，为 `false` 时返回通用文本（不泄露详细错误信息）。
     * @return 审计用的错误消息字符串；在调试构建中包含异常的消息文本，否则返回通用的 `"Connection error"`。
     */
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

    /**
     * 确定当前构建是否应信任所有 TLS 证书（跳过证书校验）。
     *
     * @param isDebugBuild 是否为调试构建；如果为 `false` 始终返回 `false`，为 `true` 时会进一步检查调试设置。
     * @return `true` 表示应跳过证书校验并信任所有证书，`false` 表示应执行正常的证书校验。
     */
    internal fun shouldTrustAllCertificatesForCurrentBuild(isDebugBuild: Boolean = BuildConfig.DEBUG): Boolean {
        if (!isDebugBuild) {
            return false
        }
        return DebugSettingsStore.isSkipMqttCertValidationEnabled(context)
    }

    /**
     * 在 IO 线程上安全地断开并关闭指定的 MQTT 客户端。
     *
     * 在执行阻塞的 disconnect()/close() 操作时切换到 Dispatchers.IO，确保不会阻塞默认协程调度器。
     *
     * @param client 要关闭的 MQTT 客户端
     * @param logContext 日志上下文标识，用于错误日志的上下文说明
     * @param checkConnected 若为 `true`，仅在 `client.isConnected` 为 `true` 时调用 `disconnect()`；为 `false` 则无条件尝试断开
     * @param rethrowCancellation 若为 `true`，在捕获到 `CancellationException` 时会重新抛出该异常
     * @throws CancellationException 如果 `rethrowCancellation` 为 `true` 且在断开过程中发生协程取消
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
     * 根据当前构建与调试配置返回用于 MQTT 的 SSLSocketFactory。
     *
     * 在非调试（release）构建中始终使用生产证书校验；在调试构建且经调试开关允许时，会记录警告并返回一个信任所有证书的开发用工厂。
     *
     * @return 已配置的 `SSLSocketFactory`，用于建立 TLSv1.2 连接。
     * @throws IllegalStateException 当配置指示在非调试构建中信任所有证书（`BuildConfig.MQTT_TRUST_ALL_CERTS`）时抛出，以防止不安全的运行时设置在生产环境生效。
     */
    private fun createSecureSocketFactory(): SSLSocketFactory {
        // 防御性检查：生产环境绝对不能允许信任所有证书
        if (!BuildConfig.DEBUG && BuildConfig.MQTT_TRUST_ALL_CERTS) {
            throw IllegalStateException(
                "SECURITY VIOLATION: MQTT_TRUST_ALL_CERTS is enabled in non-debug build. " +
                "This would bypass all TLS certificate validation and is insecure."
            )
        }

        val trustAllCertificates = shouldTrustAllCertificatesForCurrentBuild()

        return if (trustAllCertificates) {
            AppAuditLogStore.warn(
                "MQTT",
                "TLS certificate validation disabled (debug override)"
            )
            createDevSocketFactory()
        } else {
            AppAuditLogStore.info("MQTT", "TLS certificate validation enabled")
            createProductionSocketFactory()
        }
    }

    /**
     * 创建并返回一个使用 TLSv1.2 协议初始化的 SSLContext。
     *
     * @param trustManagers 用于初始化 SSLContext 的 `TrustManager` 数组；传入 `null` 时将使用默认信任管理器。
     * @return 已初始化为 TLSv1.2 的 `SSLContext` 实例，使用提供的 trustManagers 和内部的安全随机源进行初始化。
     */
    private fun createSSLContext(trustManagers: Array<TrustManager>?): SSLContext {
        val sslContext = SSLContext.getInstance("TLSv1.2")
        sslContext.init(null, trustManagers, secureRandom)
        return sslContext
    }

    /**
     * 为生产环境构建并返回采用证书钉扎策略的 TLSv1.2 `SSLSocketFactory`。
     *
     * 在非调试（release）构建中要求配置公钥钉扎（`MQTT_TLS_PUBLIC_KEY_PINS`），若未配置则抛出异常以防止不安全的连接；在调试构建中允许未配置钉扎并回退到系统 CA 验证（同时产生日志/审计告警）。
     *
     * @return 配置好的用于 MQTT 连接的 `SSLSocketFactory`，基于 TLSv1.2 并使用已配置的证书钉扎策略（release 必需，debug 可回退到系统 CA）。
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

    /**
     * 为调试构建创建一个信任所有证书的 SSLSocketFactory。
     *
     * 仅在调试环境用于连接自签名或不受信任证书的服务器；此工厂不会验证证书链，存在安全风险，生产环境禁用。主机名验证仍应由调用方或连接配置保持启用以降低中间人攻击风险。
     *
     * @return 一个在调试环境下接受所有 X.509 证书（不执行证书链验证）的 `SSLSocketFactory`。
     */
    private fun createDevSocketFactory(): SSLSocketFactory {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            /**
             * 永不验证客户端证书，始终接受传入的客户端证书链（仅用于开发/调试场景）。
             *
             * @param chain 客户端证书链。
             * @param authType 认证类型（如 "RSA"）。
             */
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                // debug 构建：信任所有客户端证书
            }
            /**
             * 在调试构建中接受并信任任何服务器证书，不对证书链或主机名执行验证。
             *
             * 仅用于开发/调试场景以跳过 TLS 证书校验，生产环境不应使用此实现。
             *
             * @param chain 服务器提供的 X509 证书链。
             * @param authType 认证算法类型（例如 `"RSA"`、`"ECDHE"` 等）。
             */
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                // debug 构建：信任所有服务器证书（包括自签名）
            }
            /**
 * 指示不存在任何被接受的证书。
 *
 * @return 空的 `X509Certificate` 数组。
 */
override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        return createSSLContext(trustAllCerts).socketFactory
    }

    /**
     * 启动并管理与 MQTT 代理的连接生命周期，建立客户端、设置回调、维护连接状态与诊断信息并在必要时发起重连。
     *
     * 该方法会创建并启动一个协程来执行连接过程；连接建立后会订阅控制主题并启动心跳监控；在连接丢失或发生错误时会更新状态与诊断信息并触发重连逻辑。
     *
     * @param deviceId 用于构建 MQTT 客户端 ID 以及作为连接用户名的设备标识。
     * @param authToken 用于作为连接密码的认证令牌。
     */
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
            _diagnostics.update {
                MqttDiagnostics(
                    connectionGeneration = generation,
                    reconnectDelay = reconnectDelay
                )
            }
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
                _diagnostics.update { it.copy(connectionGeneration = generation) }

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
                                scheduleReconnect(deviceId, authToken, generation)
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
                    if (!shouldStayConnected || generation != connectionGeneration.get()) {
                        return@launch
                    }
                    _connectionState.value = MqttConnectionState.Error(e.message ?: "Connection failed")
                    if (shouldStayConnected) {
                        onReconnectAttemptFailed()
                        scheduleReconnect(deviceId, authToken, generation)
                    }
                }
            }

            connectJob = jobToStart
        }

        jobToStart.start()
    }

    /**
     * 在重连尝试失败时增加重连延迟并更新诊断信息。
     *
     * 将当前的 `reconnectDelay` 按后退倍增因子增大，但不会超过最大允许值，然后将更新后的延迟写入 `_diagnostics`。
     */
    private fun onReconnectAttemptFailed() {
        synchronized(this) {
            reconnectDelay = minOf(
                reconnectDelay * RECONNECT_BACKOFF_MULTIPLIER,
                MAX_RECONNECT_DELAY
            )
            _diagnostics.update { it.copy(reconnectDelay = reconnectDelay) }
        }
    }

    /**
     * 在当前的重连延迟后安排一次重连尝试。
     *
     * 该函数会取消任何已有的重连任务，创建并启动一个延迟的重连协程：先读取当前的重连延迟并等待该时长，若在等待结束时仍要求保持连接且提供的 `generation` 与当前连接代一致，则更新诊断信息中的 `reconnectDelay` 与 `reconnectCount`，随后调用 `connect(deviceId, authToken)` 发起重连。
     *
     * @param deviceId 要用于重连的设备 ID（用于构建客户端标识与订阅话题）。
     * @param authToken 重连时使用的认证令牌。
     * @param generation 发起此重连请求时的连接代号；在协程恢复前若与当前管理器的 `connectionGeneration` 不匹配，则不会执行重连。
     */
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
                    _diagnostics.update {
                        it.copy(
                            reconnectDelay = reconnectDelay,
                            reconnectCount = it.reconnectCount + 1
                        )
                    }
                }
                connect(deviceId, authToken)
            }
            reconnectJob = jobToStart
        }
        jobToStart.start()
    }

    /**
     * 启动一个周期性心跳任务以维持 MQTT 连接并在连续发送失败达到阈值时触发重连。
     *
     * 该任务周期性向 "device/{deviceId}/heartbeat" 发送心跳消息，成功时更新 diagnostics 的 lastHeartbeatTime 和 consecutiveHeartbeatFailures；失败时累积连续失败次数并更新 diagnostics，当连续失败次数达到 MAX_HEARTBEAT_FAILURES 时将连接状态置为 Error 并调用重连逻辑（onReconnectAttemptFailed / scheduleReconnect）。
     *
     * @param deviceId 用于构造心跳主题的设备标识符。
     * @param authToken 用于在触发重连时重新建立连接的认证令牌。
     * @param generation 启动该任务时的 connectionGeneration 值；任务仅在该 generation 与当前 connectionGeneration 匹配时继续执行，以避免过期或已被替换的任务影响当前连接状态。
     */
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
                        scheduleReconnect(deviceId, authToken, generation)
                    }
                    break
                }
            }
        }
    }

    /**
     * 将给定负载作为 MQTT 消息发布到指定主题。
     *
     * @param topic 目标 MQTT 主题。
     * @param payload 消息载荷（字符串形式）。
     * @param qos MQTT 服务质量等级，0、1 或 2。
     */
    fun publish(topic: String, payload: String, qos: Int = 0) {
        publishWithResult(topic, payload, qos)
    }

    /**
     * 将 MqttConnectionState 转换为可读的字符串表示。
     *
     * @param state 要格式化的连接状态。
     * @return 状态的可读字符串：`Disconnected`、`Connecting`、`Connected` 或带错误信息的 `Error(<message>)`。
     */
    private fun formatConnectionState(state: MqttConnectionState): String {
        return when (state) {
            MqttConnectionState.Disconnected -> "Disconnected"
            MqttConnectionState.Connecting -> "Connecting"
            MqttConnectionState.Connected -> "Connected"
            is MqttConnectionState.Error -> "Error(${state.message})"
        }
    }

    /**
     * 构建一个说明因 MQTT 未连接而无法执行指定操作的 IllegalStateException。
     *
     * @param operation 尝试执行的操作名称（用于异常消息中说明哪个操作被阻止）。
     * @param state 当前的 MQTT 连接状态，异常消息中会包含该状态的可读表示。
     * @return `IllegalStateException`，其消息包含被阻止的操作和当前连接状态的可读描述。
     */
    private fun notConnectedOperationError(operation: String, state: MqttConnectionState): IllegalStateException {
        return IllegalStateException(
            "Cannot $operation because MQTT is not connected (currentState=${formatConnectionState(state)})"
        )
    }

    /**
     * 发布一条 MQTT 消息到指定主题并以 AppResult 封装操作结果。
     *
     * 当当前连接状态不是 Connected 或底层客户端为空时返回失败结果；在发布过程中发生异常时返回包含该异常的失败结果。
     *
     * @param qos MQTT 的服务质量等级（通常为 0、1 或 2）。
     * @param logError 是否在发生异常时记录错误日志；为 false 时异常仅作为返回值，不会被记录。
     * @return `AppResult<Unit>`，成功时包含 `Unit`，失败时包含导致发布失败的异常。
     * @throws CancellationException 当调用协程被取消时会重新抛出该异常。
     */
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

    /**
     * 订阅指定的 MQTT 主题并可选地注册消息回调。
     *
     * 当该主题收到消息时（QoS 由 `qos` 决定），若提供 `callback` 则以消息负载的字符串形式调用该回调。
     *
     * @param topic 要订阅的 MQTT 主题。
     * @param qos 消息服务质量等级，通常为 0、1 或 2，默认为 0。
     * @param callback 可选的消息处理回调，接收消息的字符串负载；为 `null` 表示仅订阅但不注册本地处理器。
     */
    fun subscribe(topic: String, qos: Int = 0, callback: ((String) -> Unit)? = null) {
        subscribeWithResult(topic, qos, callback)
    }

    /**
     * 在当前 MQTT 连接上订阅指定主题并可选地注册用于接收消息的回调。
     *
     * 如果当前连接状态不是 Connected 或底层客户端不可用，会返回包含描述性错误的 `AppResult.error`。
     * 如果在注册回调后订阅操作失败，已注册的回调会被移除并返回错误。
     *
     * @param topic 要订阅的 MQTT 主题。
     * @param qos 消息服务质量等级（0、1 或 2）。
     * @param callback 可选的消息回调；在该主题收到消息时以消息负载的字符串形式调用。
     * @return `AppResult.success(Unit)` 表示订阅成功；`AppResult.error(e)` 包含导致订阅失败的异常（例如连接状态错误或 `MqttException`）。
     */
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

    /**
     * 立即停止 MQTT 连接的维持并异步关闭当前客户端。
     *
     * 在同步块内将管理器置于不再保持连接的状态：使连接生成号失效、取消并清除重连/心跳/连接任务、重置重连延迟、清除主题回调、将连接状态设为 Disconnected 并重置诊断信息；如果存在已创建的客户端实例，则在协程中调用 `safeCloseMqttClient` 以在 IO 线程上安全地关闭该客户端。
     */
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

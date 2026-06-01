package com.netproxy.gateway.wifi

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiInfo
import android.net.wifi.WifiNetworkSuggestion
import android.net.wifi.WifiManager as AndroidWifiManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.netproxy.gateway.result.AppResult
import com.netproxy.gateway.result.getOrDefault
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.slf4j.LoggerFactory
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

data class WifiNetwork(
    val ssid: String,
    val bssid: String,
    val signalStrength: Int,
    val frequency: Int,
    val capabilities: String,
    val isSecure: Boolean
)

data class WifiConnectionInfo(
    val ssid: String?,
    val bssid: String?,
    val ipAddress: Int,
    val linkSpeed: Int,
    val frequency: Int,
    val signalStrength: Int
)

@Singleton
class GatewayWifiManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    enum class SecurityType(val requiresPassword: Boolean) {
        OPEN(false),
        WEP(true),
        WPA_PSK(true),
        WPA2_PSK(true),
        WPA3_SAE(true)
    }

    internal data class SuggestionRecord(
        val ssid: String,
        val password: String?,
        val securityType: SecurityType
    )

    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as AndroidWifiManager
    private var activeSuggestions: List<WifiNetworkSuggestion> = emptyList()
    private val suggestionStorage by lazy { createSuggestionPreferences() }
    private val suggestionPrefs: SharedPreferences
        get() = suggestionStorage.preferences
    private val canPersistSensitiveSuggestionData: Boolean
        get() = suggestionStorage.isEncrypted

    val wifiScanResults: Flow<List<WifiNetwork>> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            /**
             * 处理 WiFi 扫描完成的广播并将扫描结果发送到关联的回调流。
             *
             * 当收到 Action 为 `SCAN_RESULTS_AVAILABLE_ACTION` 的广播时，获取当前扫描结果并发送：
             * 如果成功则发送解析后的网络列表，若获取失败则发送空列表。
             *
             * @param context 广播接收器所在的上下文，可能为 `null`。
             * @param intent 收到的广播意图，期望其 `action` 为 `SCAN_RESULTS_AVAILABLE_ACTION`，并可包含 `EXTRA_RESULTS_UPDATED` 标记。 
             */
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == AndroidWifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
                    val success = intent.getBooleanExtra(AndroidWifiManager.EXTRA_RESULTS_UPDATED, false)
                    logger.debug("WiFi scan completed: success=$success")

                    when (val result = getScanResultsWithResult()) {
                        is AppResult.Success -> trySend(result.data)
                        is AppResult.Error -> {
                            logger.error("Failed to get scan results", result.exception)
                            trySend(emptyList())
                        }
                    }
                }
            }
        }

        val intentFilter = IntentFilter(AndroidWifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        context.registerReceiver(receiver, intentFilter)

        when (val initial = getScanResultsWithResult()) {
            is AppResult.Success -> trySend(initial.data)
            is AppResult.Error -> {
                logger.error("Failed to get initial scan results", initial.exception)
                trySend(emptyList())
            }
        }

        awaitClose {
            context.unregisterReceiver(receiver)
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(GatewayWifiManager::class.java)
        private const val SUGGESTION_PREFS_NAME = "gateway_wifi_suggestions"
        private const val SUGGESTION_PREFS_KEY = "last_suggestion"

        /**
         * 检查WiFi扫描所需的权限
         * Android 13+ (API 33+): 可以使用 NEARBY_WIFI_DEVICES 替代位置权限
         * Android 10-12 (API 29-32): 需要 ACCESS_FINE_LOCATION
         * Android 9及以下 (API 28-): 需要 ACCESS_FINE_LOCATION
         */
        fun hasWifiScanPermission(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Android 13+ 可以使用 NEARBY_WIFI_DEVICES 权限
                ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
            } else {
                // Android 12及以下需要位置权限
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            }
        }

        /**
         * 获取WiFi扫描所需的权限名称（用于运行时权限申请）
         */
        fun getRequiredWifiPermission(): String {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.NEARBY_WIFI_DEVICES
            } else {
                Manifest.permission.ACCESS_FINE_LOCATION
            }
        }

        /**
         * 判断是否应使用网络建议（Network Suggestion）API。
         *
         * @param apiLevel 要检查的 Android API 等级，默认为当前运行时的 SDK 版本。
         * @return `true` 当 `apiLevel` 大于或等于 Android Q（API 29），`false` 否则。
         */
        internal fun shouldUseNetworkSuggestion(apiLevel: Int = Build.VERSION.SDK_INT): Boolean {
            return apiLevel >= Build.VERSION_CODES.Q
        }

        /**
         * 根据原始安全描述字符串解析并返回对应的 SecurityType。
         *
         * @param rawSecurityType 来自平台或扫描结果的原始安全描述（例如 ScanResult.capabilities）。
         * @return 对应的 SecurityType：`WPA3_SAE`、`WPA2_PSK`、`WPA_PSK`、`WEP` 或 `OPEN`。
         */
        internal fun parseSecurityType(rawSecurityType: String): SecurityType {
            val normalized = rawSecurityType.uppercase()
            return when {
                normalized.contains("WPA3") || normalized.contains("SAE") -> SecurityType.WPA3_SAE
                normalized.contains("WPA2") -> SecurityType.WPA2_PSK
                normalized.contains("WPA") -> SecurityType.WPA_PSK
                normalized.contains("WEP") -> SecurityType.WEP
                else -> SecurityType.OPEN
            }
        }

        /**
         * 判断给定的能力字符串是否表示受保护的 Wi‑Fi 网络。
         *
         * @param capabilities 来自扫描结果的能力字符串（例如 ScanResult.capabilities）。
         * @return `true` 如果解析后的安全类型不是 `OPEN`，`false` 否则。
         */
        internal fun isSecureCapabilities(capabilities: String): Boolean {
            return parseSecurityType(capabilities) != SecurityType.OPEN
        }

        /**
         * 将 SuggestionRecord 序列化为便于存储的一行字符串。
         *
         * 格式为：`<base64(ssid)>|<SECURITY_TYPE_NAME>|<base64(password)>`，当 password 为 null 时第三段为空字符串。
         *
         * @param record 要序列化的建议记录，包含 ssid、可选 password 与 securityType。
         * @return 按约定格式编码后的单行字符串，可直接写入 SharedPreferences。 
         */
        internal fun serializeSuggestionRecord(record: SuggestionRecord): String {
            val ssidEncoded = Base64.getEncoder().encodeToString(record.ssid.toByteArray(Charsets.UTF_8))
            val passwordEncoded = record.password
                ?.let { Base64.getEncoder().encodeToString(it.toByteArray(Charsets.UTF_8)) }
                .orEmpty()
            return "$ssidEncoded|${record.securityType.name}|$passwordEncoded"
        }

        /**
         * 解析持久化的网络建议记录字符串并重建对应的 `SuggestionRecord` 对象。
         *
         * @param raw 持久化的编码字符串，期望格式为三个使用竖线分隔的部分：`<ssidBase64>|<SECURITY_TYPE>|<passwordBase64>`（若无密码则第三部分为空）。
         * @return 解析成功时返回包含 ssid、可选 password 和 securityType 的 `SuggestionRecord`；当输入为空、格式不是三部分、Base64 解码失败、ssid 为空或枚举解析失败时返回 `null`。
         */
        internal fun deserializeSuggestionRecord(raw: String): SuggestionRecord? {
            if (raw.isBlank()) {
                return null
            }

            val parts = raw.split('|')
            if (parts.size != 3) {
                return null
            }

            return try {
                val ssid = String(Base64.getDecoder().decode(parts[0]), Charsets.UTF_8)
                if (ssid.isBlank()) {
                    return null
                }

                val securityType = SecurityType.valueOf(parts[1])
                val password = if (parts[2].isBlank()) {
                    null
                } else {
                    String(Base64.getDecoder().decode(parts[2]), Charsets.UTF_8)
                }

                SuggestionRecord(
                    ssid = ssid,
                    password = password,
                    securityType = securityType
                )
            } catch (e: IllegalArgumentException) {
                null
            }
        }

        /**
         * 判断网络建议提交的状态码是否表示成功。
         *
         * @param status 来自 Wi-Fi 管理器的网络建议提交/移除返回状态码。
         * @return `true` 表示状态为 `STATUS_NETWORK_SUGGESTIONS_SUCCESS`，`false` 表示其他状态。
         */
        internal fun isSuggestionSubmissionAccepted(status: Int): Boolean {
            return status == AndroidWifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS
        }

        /**
         * 决定基于建议提交结果与当前连接状态的连接操作最终结果。
         *
         * @param submissionAccepted 表示系统是否接受了提交的网络建议。
         * @param connectedToTargetSsid 表示设备当前是否已连接到目标 SSID。
         * @return `true` 如果系统接受了网络建议提交，`false` 否则。
         */
        internal fun resolveSuggestionConnectResult(
            submissionAccepted: Boolean,
            connectedToTargetSsid: Boolean
        ): Boolean {
            // On Android 10+, accepted suggestion submission is asynchronous and
            // does not guarantee immediate association to the target SSID.
            return submissionAccepted
        }

        /**
         * 判断在当前存储可用性下是否可以安全地持久化 Wi‑Fi 建议记录。
         *
         * @param isEncryptedStorageAvailable 当加密持久化（EncryptedSharedPreferences）可用时为 `true`。
         * @param securityType 网络的安全类型，用于判断是否需要保存密码。
         * @return `true` 表示可以安全持久化（加密存储可用，或该安全类型不需要保存密码），`false` 否则。
         */
        internal fun canPersistSuggestionSafely(
            isEncryptedStorageAvailable: Boolean,
            securityType: SecurityType
        ): Boolean {
            return isEncryptedStorageAvailable || !securityType.requiresPassword
        }

        /**
         * 将 `AppResult<Boolean>` 的结果转换为与旧版 `startScan` 返回值兼容的布尔值。
         *
         * @param result 包含扫描操作结果的 `AppResult`；当结果为错误或未包含值时视为失败。
         * @return `true` 如果内部结果为 `true`，否则 `false`。
         */
        internal fun toLegacyStartScanReturn(result: AppResult<Boolean>): Boolean {
            return result.getOrDefault(false)
        }

        /**
         * 解包 AppResult 包裹的扫描结果并返回列表以供旧接口使用。
         *
         * @param result 包含扫描结果或错误的 AppResult。
         * @return 扫描到的 WifiNetwork 列表；如果 result 表示错误或不存在值，则返回空列表。
         */
        internal fun toLegacyScanResultsReturn(result: AppResult<List<WifiNetwork>>): List<WifiNetwork> {
            return result.getOrDefault(emptyList())
        }
    }

    /**
     * 触发一次 Wi‑Fi 网络扫描请求。
     *
     * @return `true` 表示系统已接受扫描请求，`false` 表示请求未被接受或发生错误。
     */
    @Suppress("DEPRECATION")
    fun startScan(): Boolean {
        return toLegacyStartScanReturn(startScanWithResult())
    }

    /**
     * 向系统请求开始一次 Wi‑Fi 扫描。
     *
     * 成功时返回包含 `true` 或 `false` 的 `AppResult`：`true` 表示扫描请求被系统接受，`false` 表示请求被拒绝。
     * 当缺少必要权限或发生安全异常时返回一个包含对应异常的错误 `AppResult`。
     */
    fun startScanWithResult(): AppResult<Boolean> {
        if (!hasWifiScanPermission(context)) {
            logger.warn("Cannot start scan: permission not granted")
            return AppResult.error(SecurityException("WiFi scan permission not granted"))
        }
        return try {
            AppResult.success(wifiManager.startScan())
        } catch (e: SecurityException) {
            logger.error("SecurityException when starting scan", e)
            AppResult.error(e)
        }
    }

    /**
     * 获取当前可见的 Wi‑Fi 网络列表（兼容旧版同步调用）。
     *
     * @return `List<WifiNetwork>`：当前可见的 Wi‑Fi 网络，若无法获取或因权限问题失败则返回空列表。
     */
    @Suppress("DEPRECATION")
    fun getScanResults(): List<WifiNetwork> {
        return toLegacyScanResultsReturn(getScanResultsWithResult())
    }

    /**
     * 获取当前可见的 WiFi 网络并将结果以 AppResult 包装返回。
     *
     * 只包含具有非空 SSID 的网络，结果按信号强度（RSSI）降序排序。
     *
     * @return `AppResult.success` 包含按信号强度降序排序的 `List<WifiNetwork>`；当缺少 WiFi 扫描权限或发生安全相关异常时返回 `AppResult.error`，包含相应的 `SecurityException`。
     */
    fun getScanResultsWithResult(): AppResult<List<WifiNetwork>> {
        if (!hasWifiScanPermission(context)) {
            logger.warn("Cannot get scan results: permission not granted")
            return AppResult.error(SecurityException("WiFi scan permission not granted"))
        }

        return try {
            val networks = wifiManager.scanResults
                .filter { !it.SSID.isNullOrEmpty() }
                .map { result ->
                    WifiNetwork(
                        ssid = result.SSID,
                        bssid = result.BSSID,
                        signalStrength = result.level,
                        frequency = result.frequency,
                        capabilities = result.capabilities,
                        isSecure = isSecureCapabilities(result.capabilities)
                    )
                }
                .sortedByDescending { it.signalStrength }
            AppResult.success(networks)
        } catch (e: SecurityException) {
            logger.error("SecurityException when getting scan results", e)
            AppResult.error(e)
        }
    }

    /**
     * 获取当前的 Wi-Fi 连接信息。
     *
     * 当缺少所需的 Wi-Fi 扫描权限或系统未提供连接信息时会返回 `null`。
     *
     * @return 当前的 `WifiConnectionInfo` 实例；若无可用连接信息或权限不足则返回 `null`。
     */
    @Suppress("DEPRECATION")
    fun getCurrentConnection(): WifiConnectionInfo? {
        if (!hasWifiScanPermission(context)) {
            logger.warn("Cannot get current connection: permission not granted")
            return null
        }

        val connectionInfo = wifiManager.connectionInfo ?: return null

        // Note: These APIs are deprecated in API 29+ but still functional.
        // For API 31+, consider using ConnectivityManager#getLinkProperties for IP
        // and WifiInfo#getLinkSpeedMbps for link speed.
        return WifiConnectionInfo(
            ssid = connectionInfo.ssid?.replace("\"", ""),
            bssid = connectionInfo.bssid,
            ipAddress = connectionInfo.ipAddress,
            linkSpeed = connectionInfo.linkSpeed,
            frequency = connectionInfo.frequency,
            signalStrength = connectionInfo.rssi
        )
    }

    /**
     * 尝试连接到指定的 Wi‑Fi 网络，运行时根据平台选择使用网络建议（API 29 及以上）或旧式配置方式。
     *
     * @param ssid 目标网络的 SSID（网络名称）。
     * @param password 目标网络的密码；对开放网络可传 null 或空字符串。
     * @param securityType 描述网络安全类型的字符串（例如 `"OPEN"`, `"WEP"`, `"WPA2"`, `"WPA3"`），函数会解析该值以决定连接方式。
     * @return `true` 如果连接操作已被发起并被系统接受（在 API 29+ 上表示建议提交被接受；在旧平台上表示已启用并尝试连接到该网络），`false` 否则。
     */
    fun connectToNetwork(ssid: String, password: String?, securityType: String): Boolean {
        val parsedSecurityType = parseSecurityType(securityType)

        // API 29+ returns whether the suggestion submission is accepted, not whether association is established.
        return if (shouldUseNetworkSuggestion()) {
            connectUsingNetworkSuggestion(ssid, password, parsedSecurityType)
        } else {
            connectUsingLegacyConfig(ssid, password, parsedSecurityType)
        }
    }

    /**
     * 尝试连接到指定的 Wi‑Fi 网络（根据平台选择网络建议或旧版配置方案）。
     *
     * 会根据当前平台/策略选择使用网络建议（Network Suggestion，API 29+）还是旧的 WifiConfiguration 路径来发起连接请求，并将最终结果封装为 `AppResult<Unit>`。
     *
     * @param ssid 目标网络的 SSID（名称）。
     * @param password 连接密码；在无需密码的安全类型下可为 `null` 或空字符串。
     * @param securityType 表示网络安全类型的字符串（例如 "WPA2", "WPA3", "WEP", "OPEN" 等），会被解析为内部的 `SecurityType` 用于选择连接参数。
     * @return `AppResult.success(Unit)` 当所选连接路径成功执行并返回成功标志；否则返回 `AppResult.error(IllegalStateException)`，其消息为 "Failed to connect to network: <ssid>"。
     */
    fun connectToNetworkWithResult(ssid: String, password: String?, securityType: String): AppResult<Unit> {
        val parsedSecurityType = parseSecurityType(securityType)

        val success = if (shouldUseNetworkSuggestion()) {
            connectUsingNetworkSuggestion(ssid, password, parsedSecurityType)
        } else {
            connectUsingLegacyConfig(ssid, password, parsedSecurityType)
        }

        return if (success) {
            AppResult.success(Unit)
        } else {
            AppResult.error(IllegalStateException("Failed to connect to network: $ssid"))
        }
    }

    /**
     * 使用旧版 WifiConfiguration 配置并尝试连接到指定的 Wi‑Fi 网络。
     *
     * 如果安全类型要求密码且未提供有效密码，则直接失败并返回 `false`。
     *
     * @param ssid 目标网络的 SSID（不含引号）。
     * @param password 用于连接的密码，若安全类型为开放网络则可为 `null` 或空字符串。
     * @param securityType 目标网络的安全类型，用以决定配置的字段和密钥管理策略。
     * @return `true` 表示已成功添加并启用该网络配置，`false` 表示添加或启用失败（包括缺少必需密码的情况）。
     */
    private fun connectUsingLegacyConfig(ssid: String, password: String?, securityType: SecurityType): Boolean {
        if (securityType.requiresPassword && password.isNullOrBlank()) {
            logger.error("Password is required for security type: $securityType")
            return false
        }

        val configuration = WifiConfiguration().apply {
            SSID = "\"$ssid\""

            when (securityType) {
                SecurityType.WPA3_SAE,
                SecurityType.WPA2_PSK,
                SecurityType.WPA_PSK -> {
                    preSharedKey = "\"${password ?: ""}\""
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
                }
                SecurityType.WEP -> {
                    wepKeys[0] = "\"${password ?: ""}\""
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                }
                SecurityType.OPEN -> {
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                }
            }
        }

        val networkId = wifiManager.addNetwork(configuration)
        if (networkId == -1) {
            logger.error("Failed to add network configuration")
            return false
        }

        val success = wifiManager.enableNetwork(networkId, true)
        if (!success) {
            logger.error("Failed to enable network")
            return false
        }

        return true
    }

    /**
     * 使用 WifiNetworkSuggestion 路径提交并持久化一个网络建议以尝试连接指定的 SSID。
     *
     * 函数会在提交前进行安全与一致性校验、清理现有建议、向系统提交单个建议并将建议元数据持久化以便后续移除或恢复。
     *
     * @param ssid 目标网络的 SSID。
     * @param password 目标网络的密码；仅在安全类型要求密码时提供，其他情况可为 `null` 或空字符串。
     * @param securityType 目标网络的安全类型，用以决定是否需要并如何设置密码/密钥。
     * @return `true` 如果网络建议已被系统接受并成功提交，`false` 表示提交被拒绝或校验/清理步骤失败。 
     */
    private fun connectUsingNetworkSuggestion(ssid: String, password: String?, securityType: SecurityType): Boolean {
        if (securityType == SecurityType.WEP) {
            logger.error("WEP is not supported by WifiNetworkSuggestion path")
            return false
        }

        if (!canPersistSuggestionSafely(canPersistSensitiveSuggestionData, securityType)) {
            logger.error(
                "Encrypted suggestion storage unavailable; refusing secured network suggestion to avoid stale unremovable records"
            )
            return false
        }

        if (securityType.requiresPassword && password.isNullOrBlank()) {
            logger.error("Password is required for security type: $securityType")
            return false
        }

        if (!clearNetworkSuggestions()) {
            logger.error("Failed to clear existing network suggestions before submitting a new one")
            return false
        }

        val suggestion = WifiNetworkSuggestion.Builder()
            .setSsid(ssid)
            .apply {
                when (securityType) {
                    SecurityType.WPA3_SAE -> setWpa3Passphrase(password ?: "")
                    SecurityType.WPA2_PSK,
                    SecurityType.WPA_PSK -> setWpa2Passphrase(password ?: "")
                    SecurityType.OPEN -> Unit
                    SecurityType.WEP -> Unit
                }
            }
            .build()

        val suggestions = listOf(suggestion)
        val status = wifiManager.addNetworkSuggestions(suggestions)
        val submissionAccepted = isSuggestionSubmissionAccepted(status)
        if (!submissionAccepted) {
            logger.error("Failed to add network suggestion: status=$status")
            return false
        }

        activeSuggestions = suggestions
        saveSuggestionRecord(
            SuggestionRecord(
                ssid = ssid,
                password = password,
                securityType = securityType
            )
        )

        val connectedToTarget = isConnectedToSsid(ssid)
        if (!connectedToTarget) {
            logger.warn(
                "Network suggestion submitted but target SSID is not connected yet: ssid=$ssid"
            )
        }

        return resolveSuggestionConnectResult(submissionAccepted, connectedToTarget)
    }

    /**
     * 检查当前设备是否连接到指定的 Wi-Fi SSID。
     *
     * @param targetSsid 要检查的目标 SSID（精确匹配）。
     * @return `true` 如果当前连接的 SSID 与 `targetSsid` 完全相同，`false` 否则。
     */
    fun isConnectedToSsid(targetSsid: String): Boolean {
        val currentSsid = getCurrentConnection()?.ssid ?: return false
        return currentSsid == targetSsid
    }

    /**
     * 断开当前 Wi‑Fi 连接或移除已提交的网络建议以实现断开。
     *
     * 当运行环境使用网络建议机制时，移除所有网络建议并清理持久化记录；否则调用系统的断开接口。
     *
     * @return `true` 表示已成功断开或已成功移除网络建议，`false` 表示操作失败。
     */
    fun disconnect(): Boolean {
        if (shouldUseNetworkSuggestion()) {
            return clearNetworkSuggestions()
        }

        return wifiManager.disconnect()
    }

    /**
     * 移除当前生效的网络建议及对应的持久化记录。
     *
     * 如果内存中的 `activeSuggestions` 与可能存在的持久化建议均为空，则仅清除持久化记录并返回成功。
     * 否则向系统请求移除这些建议；在移除成功后清空 `activeSuggestions` 并删除持久化记录。
     *
     * @return `true` 表示操作完成且（若有建议）已被系统成功移除，`false` 表示尝试移除时系统返回失败状态。
     */
    private fun clearNetworkSuggestions(): Boolean {
        val persistedRecord = loadSuggestionRecord()
        val persistedSuggestion = persistedRecord?.let { buildSuggestionFromRecord(it) }

        val suggestionsToRemove = buildList {
            addAll(activeSuggestions)
            if (persistedSuggestion != null) {
                add(persistedSuggestion)
            }
        }.distinctBy { it.hashCode() }

        if (suggestionsToRemove.isEmpty()) {
            clearSuggestionRecord()
            return true
        }

        val status = wifiManager.removeNetworkSuggestions(suggestionsToRemove)
        if (status != AndroidWifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
            logger.error("Failed to remove network suggestions: status=$status")
            return false
        }

        activeSuggestions = emptyList()
        clearSuggestionRecord()
        return true
    }

    /**
     * 将给定的建议记录持久化到应用偏好存储中，并在无法安全存储敏感数据时从要保存的记录中移除密码。
     *
     * 如果无法在加密存储中保存敏感信息，会记录警告并保存一个 password 字段为 null 的副本；否则保存完整记录（包括密码）。
     *
     * @param record 要持久化的建议记录（包含 ssid、可选 password 与 securityType）
     */
    private fun saveSuggestionRecord(record: SuggestionRecord) {
        val recordToPersist = if (canPersistSensitiveSuggestionData) {
            record
        } else {
            if (!record.password.isNullOrEmpty()) {
                logger.warn("Encrypted suggestion storage unavailable, persisting suggestion metadata without password")
            }
            record.copy(password = null)
        }

        suggestionPrefs.edit()
            .putString(SUGGESTION_PREFS_KEY, serializeSuggestionRecord(recordToPersist))
            .apply()
    }

    /**
     * 从持久化偏好中加载并解析已保存的网络建议记录。
     *
     * 如果偏好中不存在序列化字符串则返回 `null`；如果存在但解析失败，会清除该错误条目并返回 `null`。
     *
     * @return 解析得到的 `SuggestionRecord` 实例，解析失败或不存在时返回 `null`。
     */
    private fun loadSuggestionRecord(): SuggestionRecord? {
        val serialized = suggestionPrefs.getString(SUGGESTION_PREFS_KEY, null) ?: return null
        val record = deserializeSuggestionRecord(serialized)
        if (record == null) {
            clearSuggestionRecord()
        }
        return record
    }

    /**
     * 从持久化偏好中移除已保存的网络建议记录。
     *
     * 仅删除与 SUGGESTION_PREFS_KEY 对应的条目。
     */
    private fun clearSuggestionRecord() {
        suggestionPrefs.edit().remove(SUGGESTION_PREFS_KEY).apply()
    }

    /**
     * 基于给定的 SSID、可选密码和安全类型构建一个 WifiNetworkSuggestion。
     *
     * @param ssid 目标网络的 SSID。
     * @param password 用于需要口令的安全类型的密码，可能为 null；在需要时会作为相应的 passphrase 使用。
     * @param securityType 指定网络的安全类型，决定是否以及以何种形式设置 passphrase。
     * @return 已配置好的 `WifiNetworkSuggestion` 实例。
     */
    private fun buildSuggestion(
        ssid: String,
        password: String?,
        securityType: SecurityType
    ): WifiNetworkSuggestion {
        return WifiNetworkSuggestion.Builder()
            .setSsid(ssid)
            .apply {
                when (securityType) {
                    SecurityType.WPA3_SAE -> setWpa3Passphrase(password ?: "")
                    SecurityType.WPA2_PSK,
                    SecurityType.WPA_PSK -> setWpa2Passphrase(password ?: "")
                    SecurityType.OPEN -> Unit
                    SecurityType.WEP -> Unit
                }
            }
            .build()
    }

    /**
     * 从持久化的建议记录构建对应的 `WifiNetworkSuggestion`。
     *
     * 如果记录表示需要密码但密码为空或仅空白，则不会构建建议并返回 `null`。
     *
     * @param record 持久化的建议记录，包含 SSID、可选密码与安全类型。
     * @return 构建出的 `WifiNetworkSuggestion`，或在缺少必要密码时返回 `null`。
     */
    private fun buildSuggestionFromRecord(record: SuggestionRecord): WifiNetworkSuggestion? {
        if (record.securityType.requiresPassword && record.password.isNullOrBlank()) {
            logger.warn("Skipping persisted secured suggestion removal because password is unavailable")
            return null
        }
        return buildSuggestion(record.ssid, record.password, record.securityType)
    }

    /**
     * 创建用于保存网络建议元数据的首选项存储，优先使用加密的 SharedPreferences；若初始化加密存储失败则回退到普通 SharedPreferences。
     *
     * @return 一个包含已准备好用于持久化的 `SharedPreferences` 实例的 `SuggestionStorage`，以及指示该存储是否为加密存储的 `isEncrypted` 标志。
     */
    private fun createSuggestionPreferences(): SuggestionStorage = try {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        SuggestionStorage(
            preferences = EncryptedSharedPreferences.create(
                context.applicationContext,
                SUGGESTION_PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            ),
            isEncrypted = true
        )
    } catch (e: Exception) {
        logger.warn("Encrypted suggestion storage unavailable; falling back to metadata-only persistence", e)
        SuggestionStorage(
            preferences = context.getSharedPreferences(SUGGESTION_PREFS_NAME, Context.MODE_PRIVATE),
            isEncrypted = false
        )
    }

    private data class SuggestionStorage(
        val preferences: SharedPreferences,
        val isEncrypted: Boolean
    )
}

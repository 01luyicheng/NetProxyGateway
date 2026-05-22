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
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == AndroidWifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
                    val success = intent.getBooleanExtra(AndroidWifiManager.EXTRA_RESULTS_UPDATED, false)
                    logger.debug("WiFi scan completed: success=$success")

                    val results = getScanResults()
                    trySend(results)
                }
            }
        }

        val intentFilter = IntentFilter(AndroidWifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        context.registerReceiver(receiver, intentFilter)

        trySend(getScanResults())

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

        internal fun shouldUseNetworkSuggestion(apiLevel: Int = Build.VERSION.SDK_INT): Boolean {
            return apiLevel >= Build.VERSION_CODES.Q
        }

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

        internal fun isSecureCapabilities(capabilities: String): Boolean {
            return parseSecurityType(capabilities) != SecurityType.OPEN
        }

        internal fun serializeSuggestionRecord(record: SuggestionRecord): String {
            val ssidEncoded = Base64.getEncoder().encodeToString(record.ssid.toByteArray(Charsets.UTF_8))
            val passwordEncoded = record.password
                ?.let { Base64.getEncoder().encodeToString(it.toByteArray(Charsets.UTF_8)) }
                .orEmpty()
            return "$ssidEncoded|${record.securityType.name}|$passwordEncoded"
        }

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

        internal fun isSuggestionSubmissionAccepted(status: Int): Boolean {
            return status == AndroidWifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS
        }

        internal fun resolveSuggestionConnectResult(
            submissionAccepted: Boolean,
            connectedToTargetSsid: Boolean
        ): Boolean {
            // On Android 10+, accepted suggestion submission is asynchronous and
            // does not guarantee immediate association to the target SSID.
            return submissionAccepted
        }

        internal fun canPersistSuggestionSafely(
            isEncryptedStorageAvailable: Boolean,
            securityType: SecurityType
        ): Boolean {
            return isEncryptedStorageAvailable || !securityType.requiresPassword
        }

        internal fun toLegacyStartScanReturn(result: AppResult<Boolean>): Boolean {
            return result.getOrDefault(false)
        }

        internal fun toLegacyScanResultsReturn(result: AppResult<List<WifiNetwork>>): List<WifiNetwork> {
            return result.getOrDefault(emptyList())
        }
    }

    @Suppress("DEPRECATION")
    fun startScan(): Boolean {
        return toLegacyStartScanReturn(startScanWithResult())
    }

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

    @Suppress("DEPRECATION")
    fun getScanResults(): List<WifiNetwork> {
        return toLegacyScanResultsReturn(getScanResultsWithResult())
    }

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

    fun connectToNetwork(ssid: String, password: String?, securityType: String): Boolean {
        val parsedSecurityType = parseSecurityType(securityType)

        // API 29+ returns whether the suggestion submission is accepted, not whether association is established.
        return if (shouldUseNetworkSuggestion()) {
            connectUsingNetworkSuggestion(ssid, password, parsedSecurityType)
        } else {
            connectUsingLegacyConfig(ssid, password, parsedSecurityType)
        }
    }

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

    fun isConnectedToSsid(targetSsid: String): Boolean {
        val currentSsid = getCurrentConnection()?.ssid ?: return false
        return currentSsid == targetSsid
    }

    fun disconnect(): Boolean {
        if (shouldUseNetworkSuggestion()) {
            return clearNetworkSuggestions()
        }

        return wifiManager.disconnect()
    }

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

    private fun loadSuggestionRecord(): SuggestionRecord? {
        val serialized = suggestionPrefs.getString(SUGGESTION_PREFS_KEY, null) ?: return null
        val record = deserializeSuggestionRecord(serialized)
        if (record == null) {
            clearSuggestionRecord()
        }
        return record
    }

    private fun clearSuggestionRecord() {
        suggestionPrefs.edit().remove(SUGGESTION_PREFS_KEY).apply()
    }

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

    private fun buildSuggestionFromRecord(record: SuggestionRecord): WifiNetworkSuggestion? {
        if (record.securityType.requiresPassword && record.password.isNullOrBlank()) {
            logger.warn("Skipping persisted secured suggestion removal because password is unavailable")
            return null
        }
        return buildSuggestion(record.ssid, record.password, record.securityType)
    }

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

package com.netproxy.gateway.connection

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.netproxy.gateway.di.ApplicationScope
import com.netproxy.gateway.result.AppResult
import com.netproxy.gateway.utils.securelyClear
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthSessionStore @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val appScope: CoroutineScope
) {

    companion object {
        private val logger = LoggerFactory.getLogger(AuthSessionStore::class.java)
        private const val PREF_NAME = "auth_session_store"
        private const val KEY_INSTALLATION_DEVICE_ID = "installation_device_id"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_AUTH_TOKEN = "auth_token"
    }

    private val encryptedPrefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            PREF_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private var inMemoryToken: CharArray? = null
    private var inMemoryDeviceId: String? = null
    private var inMemoryInstallationDeviceId: String? = null

    init {
        // Pre-warm encrypted storage off the main thread to avoid first-use UI jank.
        appScope.launch {
            runCatching { encryptedPrefs }
                .onFailure { error -> logger.warn("Encrypted prefs pre-warm failed", error) }
        }
    }

    @Synchronized
    fun update(deviceId: String, authToken: CharArray) {
        inMemoryToken.securelyClear()
        inMemoryToken = authToken.copyOf()
        inMemoryDeviceId = deviceId
        inMemoryInstallationDeviceId = deviceId

        // EncryptedSharedPreferences API 限制：putString 只接受 String，无法避免中间 String 转换
        encryptedPrefs.edit()
            .putString(KEY_INSTALLATION_DEVICE_ID, deviceId)
            .putString(KEY_DEVICE_ID, deviceId)
            .putString(KEY_AUTH_TOKEN, String(authToken))
            .apply()
    }

    @Synchronized
    fun updateWithResult(deviceId: String, authToken: CharArray): AppResult<Unit> {
        return try {
            inMemoryToken.securelyClear()
            inMemoryToken = authToken.copyOf()
            inMemoryDeviceId = deviceId
            inMemoryInstallationDeviceId = deviceId

            // 当前限制：EncryptedSharedPreferences 的 putString 只接受 String，
            // 这会导致临时的 String 对象。后续可考虑迁移到支持 ByteArray 的加密存储。
            encryptedPrefs.edit()
                .putString(KEY_INSTALLATION_DEVICE_ID, deviceId)
                .putString(KEY_DEVICE_ID, deviceId)
                .putString(KEY_AUTH_TOKEN, String(authToken))
                .apply()
            AppResult.success(Unit)
        } catch (e: Exception) {
            logger.error("Failed to update session", e)
            AppResult.error(e)
        }
    }

    @Synchronized
    fun clear() {
        inMemoryToken.securelyClear()
        inMemoryToken = null
        inMemoryDeviceId = null
        inMemoryInstallationDeviceId = null
        encryptedPrefs.edit()
            .remove(KEY_INSTALLATION_DEVICE_ID)
            .remove(KEY_DEVICE_ID)
            .remove(KEY_AUTH_TOKEN)
            .apply()
    }

    @Synchronized
    fun clearWithResult(): AppResult<Unit> {
        return try {
            inMemoryToken.securelyClear()
            inMemoryToken = null
            inMemoryDeviceId = null
            inMemoryInstallationDeviceId = null
            encryptedPrefs.edit()
                .remove(KEY_INSTALLATION_DEVICE_ID)
                .remove(KEY_DEVICE_ID)
                .remove(KEY_AUTH_TOKEN)
                .apply()
            AppResult.success(Unit)
        } catch (e: Exception) {
            logger.error("Failed to clear session", e)
            AppResult.error(e)
        }
    }

    @Synchronized
    fun getOrCreateDeviceId(): String {
        inMemoryInstallationDeviceId?.let { return it }

        val storedDeviceId = encryptedPrefs.getString(KEY_INSTALLATION_DEVICE_ID, null)
        if (!storedDeviceId.isNullOrBlank()) {
            inMemoryInstallationDeviceId = storedDeviceId
            return storedDeviceId
        }

        val deviceId = UUID.randomUUID().toString()
        inMemoryInstallationDeviceId = deviceId
        encryptedPrefs.edit()
            .putString(KEY_INSTALLATION_DEVICE_ID, deviceId)
            .apply()
        return deviceId
    }

    @Synchronized
    fun isValid(username: String, password: CharArray): Boolean {
        val session = loadSession() ?: return false
        return try {
            if (session.deviceId != username) false
            else constantTimeEquals(session.authToken, password)
        } finally {
            session.authToken.securelyClear()
        }
    }

    @Synchronized
    fun validateWithResult(username: String, password: CharArray): AppResult<Boolean> {
        return try {
            val session = loadSession()
                ?: return AppResult.success(false)
            return try {
                if (session.deviceId != username) {
                    AppResult.success(false)
                } else {
                    AppResult.success(constantTimeEquals(session.authToken, password))
                }
            } finally {
                session.authToken.securelyClear()
            }
        } catch (e: Exception) {
            AppResult.error(e)
        }
    }

    @Synchronized
    fun getCurrentSession(): ProxyAuthSession? {
        return loadSession()
    }

    @Synchronized
    fun getCurrentSessionWithResult(): AppResult<ProxyAuthSession> {
        return try {
            val session = loadSession()
                ?: return AppResult.error(IllegalStateException("No active session found"))
            AppResult.success(session)
        } catch (e: Exception) {
            AppResult.error(e)
        }
    }

    @Synchronized
    private fun loadSession(): ProxyAuthSession? {
        val cachedToken = inMemoryToken
        val cachedDeviceId = inMemoryDeviceId
        if (cachedToken != null && cachedDeviceId != null) {
            return ProxyAuthSession(cachedDeviceId, cachedToken.copyOf())
        }

        val storedDeviceId = encryptedPrefs.getString(KEY_DEVICE_ID, null) ?: return null
        val storedToken = encryptedPrefs.getString(KEY_AUTH_TOKEN, null) ?: return null
        inMemoryDeviceId = storedDeviceId
        val tokenArray = storedToken.toCharArray()
        inMemoryToken = tokenArray
        return ProxyAuthSession(storedDeviceId, tokenArray.copyOf())
    }

    private fun constantTimeEquals(left: CharArray, right: CharArray): Boolean {
        if (left.size != right.size) return false
        var diff = 0
        for (i in left.indices) {
            diff = diff or (left[i].code xor right[i].code)
        }
        return diff == 0
    }
}

data class ProxyAuthSession(
    val deviceId: String,
    val authToken: CharArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ProxyAuthSession
        return deviceId == other.deviceId && authToken.contentEquals(other.authToken)
    }

    override fun hashCode(): Int {
        var result = deviceId.hashCode()
        result = 31 * result + authToken.contentHashCode()
        return result
    }

    override fun toString(): String {
        return "ProxyAuthSession(deviceId='$deviceId', authToken=[REDACTED])"
    }
}

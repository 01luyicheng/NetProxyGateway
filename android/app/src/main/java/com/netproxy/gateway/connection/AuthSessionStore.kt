package com.netproxy.gateway.connection

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.netproxy.gateway.di.ApplicationScope
import com.netproxy.gateway.result.AppResult
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

    /**
     * 更新并持久化当前认证会话的设备 ID 与认证令牌。
     *
     * 在内存中缓存设备 ID、安装设备 ID 与认证令牌（认证令牌以 `CharArray` 形式存储），
     * 并将它们写入加密的持久化存储中以便后续恢复。
     *
     * @param deviceId 要保存的设备标识符，同时作为安装设备 ID 缓存并持久化。
     * @param authToken 要保存的认证令牌，会以字符数组形式在内存中缓存并持久化为字符串。
     */
    @Synchronized
    fun update(deviceId: String, authToken: String) {
        inMemoryToken?.fill('\u0000')
        inMemoryToken = authToken.toCharArray()
        inMemoryDeviceId = deviceId
        inMemoryInstallationDeviceId = deviceId

        encryptedPrefs.edit()
            .putString(KEY_INSTALLATION_DEVICE_ID, deviceId)
            .putString(KEY_DEVICE_ID, deviceId)
            .putString(KEY_AUTH_TOKEN, authToken)
            .apply()
    }

    /**
     * 更新并持久化当前认证会话（设备 ID 与认证令牌），并返回操作结果。
     *
     * @param deviceId 要设置为会话设备 ID，同时作为安装设备 ID 存储。
     * @param authToken 要保存的认证令牌。
     * @return `AppResult.success(Unit)` 表示更新成功并已写入加密存储；`AppResult.error(e)` 表示发生异常并返回该异常。
     */
    @Synchronized
    fun updateWithResult(deviceId: String, authToken: String): AppResult<Unit> {
        return try {
            inMemoryToken?.fill('\u0000')
            inMemoryToken = authToken.toCharArray()
            inMemoryDeviceId = deviceId
            inMemoryInstallationDeviceId = deviceId

            encryptedPrefs.edit()
                .putString(KEY_INSTALLATION_DEVICE_ID, deviceId)
                .putString(KEY_DEVICE_ID, deviceId)
                .putString(KEY_AUTH_TOKEN, authToken)
                .apply()
            AppResult.success(Unit)
        } catch (e: Exception) {
            logger.error("Failed to update session", e)
            AppResult.error(e)
        }
    }

    /**
     * 清除当前认证会话的内存与加密持久化数据。
     *
     * 会擦除并置空内存中的认证 token、设备 ID 与安装设备 ID，并从加密的 SharedPreferences 中移除安装设备 ID、设备 ID 和认证 token 的存储条目。
     */
    @Synchronized
    fun clear() {
        inMemoryToken?.fill('\u0000')
        inMemoryToken = null
        inMemoryDeviceId = null
        inMemoryInstallationDeviceId = null
        encryptedPrefs.edit()
            .remove(KEY_INSTALLATION_DEVICE_ID)
            .remove(KEY_DEVICE_ID)
            .remove(KEY_AUTH_TOKEN)
            .apply()
    }

    /**
     * 清除当前认证会话的所有状态并返回操作结果。
     *
     * 覆盖并清空内存中的认证令牌与设备标识（包括安装设备 ID），并从加密持久化存储中移除安装设备 ID、会话设备 ID 与认证令牌三个键。
     *
     * @return `AppResult.success(Unit)` 表示已成功清除；`AppResult.error(e)` 表示发生异常并包含该异常。
     */
    @Synchronized
    fun clearWithResult(): AppResult<Unit> {
        return try {
            inMemoryToken?.fill('\u0000')
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

    /**
     * 获取当前安装的设备标识符；若不存在则生成一个新的唯一标识符、缓存并持久化后返回。
     *
     * 这个方法会将标识符保存在内存缓存并写入加密的持久存储，以便后续调用复用同一值。
     *
     * @return 安装设备的唯一标识符字符串。
     */
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

    /**
     * 验证给定的用户名和密码是否与当前保存的会话匹配。
     *
     * @param username 要比对的设备标识（应与存储的 deviceId 匹配）。
     * @param password 要比对的凭证（与存储的 auth token 进行常量时间比较以防止计时攻击）。
     * @return `true` 如果存在活动会话且其 `deviceId` 等于 `username` 并且存储的 auth token 与 `password` 在常量时间比较中相等，`false` 否则。
     */
    @Synchronized
    fun isValid(username: String, password: String): Boolean {
        val session = loadSession() ?: return false
        if (session.deviceId != username) return false
        return constantTimeEquals(session.authToken.toCharArray(), password.toCharArray())
    }

    /**
     * 验证给定的设备 ID 和密码是否与当前会话匹配。
     *
     * @param username 要验证的设备 ID（与会话的 deviceId 比较）。
     * @param password 要验证的密码/授权令牌（与会话中存储的 authToken 进行常量时间比较）。
     * @return `true` 如果当前存在会话且 `username` 与会话的 `deviceId` 相同并且 `password` 与存储的 `authToken` 常量时间比较相等，`false` 否则。
     */
    @Synchronized
    fun validateWithResult(username: String, password: String): AppResult<Boolean> {
        return try {
            val session = loadSession()
                ?: return AppResult.success(false)
            if (session.deviceId != username) {
                return AppResult.success(false)
            }
            val isValid = constantTimeEquals(session.authToken.toCharArray(), password.toCharArray())
            AppResult.success(isValid)
        } catch (e: Exception) {
            AppResult.error(e)
        }
    }

    /**
     * 获取当前保存的身份验证会话（如果存在）。
     *
     * @return 当前活动的 `ProxyAuthSession`，如果没有活动会话则返回 `null`。
     */
    @Synchronized
    fun getCurrentSession(): ProxyAuthSession? {
        return loadSession()
    }

    /**
     * 获取当前活动的代理认证会话并将其作为 `AppResult` 返回。
     *
     * 如果存在会话则返回包含 `ProxyAuthSession` 的成功结果；如果不存在或在加载时发生异常则返回封装相应异常的错误结果。
     *
     * @return 成功时包含当前 `ProxyAuthSession`，失败时为包含具体异常的错误结果。
     */
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

    /**
     * 获取当前活动的认证会话；优先使用内存缓存，若不存在则从加密首选项中加载并缓存到内存。
     *
     * @return 包含 `deviceId` 和 `authToken` 的 `ProxyAuthSession`，如果无有效会话则返回 `null`。
     */
    @Synchronized
    private fun loadSession(): ProxyAuthSession? {
        val cachedToken = inMemoryToken
        val cachedDeviceId = inMemoryDeviceId
        if (cachedToken != null && cachedDeviceId != null) {
            return ProxyAuthSession(cachedDeviceId, String(cachedToken))
        }

        val storedDeviceId = encryptedPrefs.getString(KEY_DEVICE_ID, null) ?: return null
        val storedToken = encryptedPrefs.getString(KEY_AUTH_TOKEN, null) ?: return null
        inMemoryDeviceId = storedDeviceId
        inMemoryToken = storedToken.toCharArray()
        return ProxyAuthSession(storedDeviceId, storedToken)
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
    val authToken: String
)

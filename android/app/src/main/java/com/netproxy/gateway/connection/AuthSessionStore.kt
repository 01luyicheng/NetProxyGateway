package com.netproxy.gateway.connection

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.netproxy.gateway.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthSessionStore @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val appScope: CoroutineScope
) {

    companion object {
        private const val TAG = "AuthSessionStore"
        private const val PREF_NAME = "auth_session_store"
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

    init {
        // Pre-warm encrypted storage off the main thread to avoid first-use UI jank.
        appScope.launch {
            runCatching { encryptedPrefs }
                .onFailure { error -> Log.w(TAG, "Encrypted prefs pre-warm failed", error) }
        }
    }

    @Synchronized
    fun update(deviceId: String, authToken: String) {
        inMemoryToken?.fill('\u0000')
        inMemoryToken = authToken.toCharArray()
        inMemoryDeviceId = deviceId

        encryptedPrefs.edit()
            .putString(KEY_DEVICE_ID, deviceId)
            .putString(KEY_AUTH_TOKEN, authToken)
            .apply()
    }

    @Synchronized
    fun clear() {
        inMemoryToken?.fill('\u0000')
        inMemoryToken = null
        inMemoryDeviceId = null
        encryptedPrefs.edit().clear().apply()
    }

    @Synchronized
    fun isValid(username: String, password: String): Boolean {
        val session = loadSession() ?: return false
        if (session.deviceId != username) return false
        return constantTimeEquals(session.authToken.toCharArray(), password.toCharArray())
    }

    @Synchronized
    fun getCurrentSession(): ProxyAuthSession? {
        return loadSession()
    }

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

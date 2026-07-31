package com.netproxy.gateway.debug

import android.content.Context
import com.netproxy.gateway.BuildConfig

object DebugSettingsStore {
    private const val DEBUG_SETTINGS_PREFS = "debug_settings"
    private const val KEY_SKIP_MQTT_CERT_VALIDATION = "skip_mqtt_cert_validation"

    // Certificate validation bypass is permanently disabled in all builds.
    val isSkipMqttCertValidationSupported: Boolean
        get() = false

    fun isSkipMqttCertValidationEnabled(context: Context): Boolean {
        if (!BuildConfig.DEBUG) {
            return false
        }
        return prefs(context).getBoolean(KEY_SKIP_MQTT_CERT_VALIDATION, BuildConfig.MQTT_TRUST_ALL_CERTS)
    }

    fun setSkipMqttCertValidationEnabled(context: Context, enabled: Boolean): Boolean {
        if (!BuildConfig.DEBUG) {
            return false
        }
        return try {
            prefs(context).edit().putBoolean(KEY_SKIP_MQTT_CERT_VALIDATION, enabled).apply()
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(DEBUG_SETTINGS_PREFS, Context.MODE_PRIVATE)
}

package com.netproxy.gateway.debug

import android.content.Context
import com.netproxy.gateway.BuildConfig

object DebugSettingsStore {
    private const val DEBUG_SETTINGS_PREFS = "debug_settings"
    private const val KEY_SKIP_MQTT_CERT_VALIDATION = "skip_mqtt_cert_validation"

    val isSkipMqttCertValidationSupported: Boolean
        get() = BuildConfig.DEBUG

    /**
     * 获取是否启用跳过 MQTT 证书校验的调试开关。
     *
     * 当 `BuildConfig.DEBUG` 为 `false` 时始终返回 `false`；否则从调试偏好设置中读取 `skip_mqtt_cert_validation`，默认值为 `BuildConfig.MQTT_TRUST_ALL_CERTS`。
     *
     * @param context 用于访问应用范围 SharedPreferences 的上下文。
     * @return `true` 表示启用跳过 MQTT 证书校验，`false` 表示未启用。
     */
    fun isSkipMqttCertValidationEnabled(context: Context): Boolean {
        if (!BuildConfig.DEBUG) {
            return false
        }
        return prefs(context).getBoolean(KEY_SKIP_MQTT_CERT_VALIDATION, BuildConfig.MQTT_TRUST_ALL_CERTS)
    }

    /**
     * 在调试构建中将跳过 MQTT 证书校验的设置写入持久化存储。
     *
     * 如果当前不是调试构建则不执行写入并返回 `false`；若写入过程中抛出异常也返回 `false`。
     *
     * @param context 用于获取应用级的 SharedPreferences 的 Context（会使用 applicationContext）。
     * @param enabled 是否启用跳过 MQTT 证书校验。
     * @return `true` 表示写入尝试已成功，`false` 表示未写入（非调试构建或写入出错）。
     */
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

    /**
         * 获取用于存储调试设置的 SharedPreferences 实例。
         *
         * 使用传入的 Context 的 applicationContext 打开名为 `DEBUG_SETTINGS_PREFS` 的私有首选项文件。
         *
         * @param context 用于获取 applicationContext 的 Context。
         * @return 名为 `DEBUG_SETTINGS_PREFS`、以 `Context.MODE_PRIVATE` 模式打开的 `SharedPreferences` 实例。
         */
        private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(DEBUG_SETTINGS_PREFS, Context.MODE_PRIVATE)
}

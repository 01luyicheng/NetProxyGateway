package com.netproxy.gateway.i18n

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Build
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.netproxy.gateway.R
import org.slf4j.LoggerFactory
import java.util.Locale

object AppLocale {
    private val logger = LoggerFactory.getLogger(AppLocale::class.java)
    private const val prefsName = "app_settings"
    private const val keyLanguageTag = "app_language_tag"

    // 语言变更回调（支持多个监听者，避免服务之间互相覆盖）
    private val languageChangeListeners = mutableMapOf<String, (String?) -> Unit>()
    private val listenersLock = Any()

    data class LanguageOption(
        val languageTag: String?,
        @StringRes val displayNameResId: Int
    )

    /**
     * Adding a new language should be a small, localized change:
     * 1) Add translations under res/values-<lang>/strings.xml
     * 2) Add an option here with the BCP-47 language tag
     * 3) Update res/xml/locales_config.xml (for Android 13+ system language UI)
     */
    val supportedLanguages: List<LanguageOption> = listOf(
        LanguageOption(languageTag = null, displayNameResId = R.string.language_follow_system),
        LanguageOption(languageTag = "en", displayNameResId = R.string.language_english),
        LanguageOption(languageTag = "zh-CN", displayNameResId = R.string.language_simplified_chinese)
    )

    private val supportedLanguageTags: Set<String> =
        supportedLanguages.mapNotNull { it.languageTag }.toSet()

    fun getSelectedLanguageTag(context: Context): String? {
        val raw = prefs(context).getString(keyLanguageTag, null)
        return normalizeLanguageTag(raw)
    }

    fun setSelectedLanguageTag(context: Context, languageTag: String?) {
        val normalizedTag = normalizeLanguageTag(languageTag)
        prefs(context)
            .edit()
            .putString(keyLanguageTag, normalizedTag)
            .apply()
    }

    /**
     * 注册语言变更回调。当语言切换时触发回调，通知前台服务等监听者更新状态。
     * H27 修复：运行中前台服务通知可通过此回调即时刷新
     */
    fun registerLanguageChangeListener(listenerId: String, listener: (String?) -> Unit) {
        synchronized(listenersLock) {
            languageChangeListeners[listenerId] = listener
        }
    }

    /**
     * 注销语言变更回调
     */
    fun unregisterLanguageChangeListener(listenerId: String) {
        synchronized(listenersLock) {
            languageChangeListeners.remove(listenerId)
        }
    }

    /**
     * 应用语言设置并触发UI更新。
     * 在Android 13+上使用AppCompatDelegate.setApplicationLocales()实现无重启切换，
     * 在旧版本上使用Activity.recreate()作为fallback。
     * 
     * 修复 H27：变更后触发语言变更回调，通知前台服务更新通知文案
     */
    fun applyLanguage(activity: Activity, languageTag: String?) {
        val normalizedTag = normalizeLanguageTag(languageTag)
        setSelectedLanguageTag(activity, normalizedTag)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val localeList = if (normalizedTag != null) {
                LocaleListCompat.forLanguageTags(normalizedTag)
            } else {
                LocaleListCompat.getEmptyLocaleList()
            }
            AppCompatDelegate.setApplicationLocales(localeList)
        } else {
            activity.recreate()
        }

        // H27: 通知监听者语言已变更，触发前台服务通知更新
        notifyLanguageChanged(normalizedTag)
    }

    private fun notifyLanguageChanged(languageTag: String?) {
        val snapshot = synchronized(listenersLock) {
            languageChangeListeners.values.toList()
        }
        snapshot.forEach { listener ->
            try {
                listener(languageTag)
            } catch (e: Exception) {
                logger.warn("Language change listener failed", e)
            }
        }
    }

    fun wrap(base: Context): Context {
        val languageTag = getSelectedLanguageTag(base) ?: return base
        val locale = Locale.forLanguageTag(languageTag)

        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)

        return base.createConfigurationContext(config)
    }

    fun getString(context: Context, @StringRes resId: Int, vararg formatArgs: Any): String {
        val localizedContext = wrap(context)
        return if (formatArgs.isEmpty()) {
            localizedContext.getString(resId)
        } else {
            localizedContext.getString(resId, *formatArgs)
        }
    }

    private fun prefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    }

    private fun normalizeLanguageTag(languageTag: String?): String? {
        val trimmed = languageTag?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val canonicalTag = Locale.forLanguageTag(trimmed).toLanguageTag()
        return canonicalTag.takeIf { it in supportedLanguageTags }
    }
}

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
    private const val PREFS_NAME = "app_settings"
    private const val KEY_LANGUAGE_TAG = "app_language_tag"

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

    /**
     * 获取并归一化当前保存的语言标签。
     *
     * @param context 用于访问应用 SharedPreferences 的上下文。
     * @return 当前已选择并经过归一化的 BCP-47 语言标签；如果未设置或不在支持列表中则返回 `null`。
     */
    fun getSelectedLanguageTag(context: Context): String? {
        val raw = prefs(context).getString(KEY_LANGUAGE_TAG, null)
        return normalizeLanguageTag(raw)
    }

    /**
     * 将指定语言标签归一化后写入应用首选项以保存用户选择；传入 `null` 或不被支持的标签会被归一化为 `null` 并保存，表示“跟随系统”。
     *
     * @param languageTag 要设置的 BCP-47 语言标签，或 `null` 表示跟随系统
     */
    fun setSelectedLanguageTag(context: Context, languageTag: String?) {
        val normalizedTag = normalizeLanguageTag(languageTag)
        prefs(context)
            .edit()
            .putString(KEY_LANGUAGE_TAG, normalizedTag)
            .apply()
    }

    /**
     * 注册语言变更监听器，在语言切换后调用以通知注册者。
     *
     * 如果已存在相同 `listenerId` 的监听器则会被替换；调用时会传入当前已归一化的语言标签或 `null`（表示跟随系统）。
     *
     * @param listenerId 唯一标识符，用于后续取消注册或替换该监听器。
     * @param listener 在语言变更时被调用，参数为归一化后的语言标签或 `null`。
     */
    fun registerLanguageChangeListener(listenerId: String, listener: (String?) -> Unit) {
        synchronized(listenersLock) {
            languageChangeListeners[listenerId] = listener
        }
    }

    /**
     * 通过监听器 ID 注销先前注册的语言变更回调。
     *
     * @param listenerId 要移除的监听器的唯一标识符。
     */
    fun unregisterLanguageChangeListener(listenerId: String) {
        synchronized(listenersLock) {
            languageChangeListeners.remove(listenerId)
        }
    }

    /**
     * 应用并持久化用户选定的语言设置，并通知已注册的监听器以更新 UI 和通知文案。
     *
     * 持久化后会触发语言变更通知；传入为 `null` 表示跟随系统语言。对于无法原位切换语言的运行时环境，可能会重建当前 Activity 以应用变更。
     *
     * @param activity 当前用于应用语言变更的 Activity（在某些平台上用于触发重建）。
     * @param languageTag 要应用的 BCP-47 语言标签，或 `null` 表示跟随系统。
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

    /**
     * 向已注册的监听器同步分发语言变更通知。
     *
     * 在调用每个监听器时会捕获并记录抛出的异常，确保一个监听器的失败不会中断其它监听器的通知。
     *
     * @param languageTag 规范化的语言标签；为 `null` 表示“跟随系统”。
     */
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

    /**
     * 为已选择的应用语言创建并返回一个包装后的 Context。
     *
     * @param base 原始 Context，用于基于其资源和配置构建新的上下文。
     * @return 基于已选语言及对应布局方向的 Context；当当前选择为“跟随系统”（即无语言标签）时返回原始 `base`。
     */
    fun wrap(base: Context): Context {
        val languageTag = getSelectedLanguageTag(base) ?: return base
        val locale = Locale.forLanguageTag(languageTag)

        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)

        return base.createConfigurationContext(config)
    }

    /**
     * 在当前选定的应用语言上下文中获取字符串资源。
     *
     * @param context 用于解析资源的上下文（会基于已选择的语言进行包装）。
     * @param resId 字符串资源的资源 ID。
     * @param formatArgs 可选的格式化参数；如果提供则用于格式化返回的字符串。
     * @return 指定资源 ID 在已选择语言下的字符串，若提供 `formatArgs` 则为格式化后的结果。
     */
    fun getString(context: Context, @StringRes resId: Int, vararg formatArgs: Any): String {
        val localizedContext = wrap(context)
        return if (formatArgs.isEmpty()) {
            localizedContext.getString(resId)
        } else {
            localizedContext.getString(resId, *formatArgs)
        }
    }

    /**
     * 获取用于存储应用设置的 SharedPreferences 实例。
     *
     * @param context 用于访问 SharedPreferences 的上下文。
     * @return 名称为 `app_settings` 的 `SharedPreferences` 实例（模式为 `Context.MODE_PRIVATE`）。
     */
    private fun prefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * 将输入语言标签规范化为受支持的规范化 BCP‑47 语言标签或解析为“跟随系统”。
     *
     * 对传入字符串去除首尾空白并转换为 Locale 的规范化语言标签；若结果不在受支持语言集合中则视为 `null`。
     *
     * @param languageTag 要规范化的语言标签；`null` 或空字符串表示“跟随系统”。
     * @return 规范化后的 BCP‑47 语言标签（例如 `"en"`、`"zh-CN"`），如果输入表示“跟随系统”或不被支持则返回 `null`。
     */
    private fun normalizeLanguageTag(languageTag: String?): String? {
        val trimmed = languageTag?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val canonicalTag = Locale.forLanguageTag(trimmed).toLanguageTag()
        return canonicalTag.takeIf { it in supportedLanguageTags }
    }
}

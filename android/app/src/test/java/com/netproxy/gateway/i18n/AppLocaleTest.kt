package com.netproxy.gateway.i18n

import androidx.activity.ComponentActivity
import androidx.appcompat.app.AppCompatDelegate
import com.netproxy.gateway.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class AppLocaleTest {

    companion object {
        private const val TEST_LISTENER_1 = "test_listener_1"
        private const val TEST_LISTENER_2 = "test_listener_2"
        private const val TEST_LISTENER_3 = "test_listener_3"
    }

    @After
    fun tearDown() {
        AppCompatDelegate.setApplicationLocales(androidx.core.os.LocaleListCompat.getEmptyLocaleList())
        AppLocale.unregisterLanguageChangeListener(TEST_LISTENER_1)
        AppLocale.unregisterLanguageChangeListener(TEST_LISTENER_2)
        AppLocale.unregisterLanguageChangeListener(TEST_LISTENER_3)
    }

    // === 原有测试 ===

    @Test
    @Config(sdk = [33])
    fun applyLanguage_android13Plus_persistsLanguageTagForSingleSourceOfTruth() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

        AppLocale.applyLanguage(activity, "zh-CN")

        assertEquals("zh-CN", AppLocale.getSelectedLanguageTag(activity))
    }

    @Test
    @Config(sdk = [33])
    fun applyLanguage_android13Plus_followSystemPersistsNullForSingleSourceOfTruth() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

        AppLocale.applyLanguage(activity, "en")
        AppLocale.applyLanguage(activity, null)

        assertNull(AppLocale.getSelectedLanguageTag(activity))
    }

    @Test
    @Config(sdk = [32])
    fun applyLanguage_preAndroid13_persistsLanguageTag() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

        AppLocale.applyLanguage(activity, "en")

        assertEquals("en", AppLocale.getSelectedLanguageTag(activity))
    }

    // === M21: 新增关键路径测试 ===

    @Test
    fun normalizeLanguageTag_rejectedUnsupportedTag() {
        // 不支持的语言标签应被正规化为 null
        val context = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        
        AppLocale.setSelectedLanguageTag(context, "fr-FR")  // 不支持的语言
        
        assertNull(AppLocale.getSelectedLanguageTag(context))
    }

    @Test
    fun normalizeLanguageTag_trimsWhitespaceAndNormalizes() {
        val context = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        
        // 应该清理首尾空格并正规化为规范形式
        AppLocale.setSelectedLanguageTag(context, "  en  ")
        
        assertEquals("en", AppLocale.getSelectedLanguageTag(context))
    }

    @Test
    fun normalizeLanguageTag_emptyStringBecomesNull() {
        val context = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        
        AppLocale.setSelectedLanguageTag(context, "")
        
        assertNull(AppLocale.getSelectedLanguageTag(context))
    }

    @Test
    @Config(sdk = [33])
    fun multipleSwitches_sequentialCalls_persists() {
        // 关键路径：多次语言切换应正确持久化
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

        AppLocale.applyLanguage(activity, "en")
        assertEquals("en", AppLocale.getSelectedLanguageTag(activity))

        AppLocale.applyLanguage(activity, "zh-CN")
        assertEquals("zh-CN", AppLocale.getSelectedLanguageTag(activity))

        AppLocale.applyLanguage(activity, null)
        assertNull(AppLocale.getSelectedLanguageTag(activity))

        AppLocale.applyLanguage(activity, "en")
        assertEquals("en", AppLocale.getSelectedLanguageTag(activity))
    }

    @Test
    fun setSelectedLanguageTag_directCall_persists() {
        val context = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

        AppLocale.setSelectedLanguageTag(context, "zh-CN")
        assertEquals("zh-CN", AppLocale.getSelectedLanguageTag(context))

        AppLocale.setSelectedLanguageTag(context, "en")
        assertEquals("en", AppLocale.getSelectedLanguageTag(context))
    }

    @Test
    fun wrap_withoutLanguageTag_returnsOriginalContext() {
        val context = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        AppLocale.setSelectedLanguageTag(context, null)

        val wrappedContext = AppLocale.wrap(context)

        // wrap 应该返回原始 context（当没有设置语言时）
        assertEquals(context, wrappedContext)
    }

    @Test
    fun wrap_withValidLanguageTag_wrapsContext() {
        val context = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        AppLocale.setSelectedLanguageTag(context, "en")

        val wrappedContext = AppLocale.wrap(context)

        // wrap 应该返回不同的 context（配置了 locale）
        assertNotEquals(context, wrappedContext)
    }

    // === H27: 语言变更回调测试 ===

    @Test
    @Config(sdk = [33])
    fun languageChangeCallback_triggersOnApplyLanguage() {
        // H27: 当语言切换时应触发回调，以通知前台服务
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        
        val callbackTriggered = mutableListOf<String?>()
        AppLocale.registerLanguageChangeListener(TEST_LISTENER_1) { newTag ->
            callbackTriggered.add(newTag)
        }

        AppLocale.applyLanguage(activity, "en")

        assertTrue("Language change callback should be triggered", callbackTriggered.isNotEmpty())
        assertEquals("en", callbackTriggered[0])
    }

    @Test
    @Config(sdk = [33])
    fun languageChangeCallback_multipleCallbacks() {
        // H27: 多次切换应多次触发回调
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        
        val callbackTriggered = mutableListOf<String?>()
        AppLocale.registerLanguageChangeListener(TEST_LISTENER_2) { newTag ->
            callbackTriggered.add(newTag)
        }

        AppLocale.applyLanguage(activity, "en")
        AppLocale.applyLanguage(activity, "zh-CN")
        AppLocale.applyLanguage(activity, null)

        assertEquals("Should trigger callback 3 times", 3, callbackTriggered.size)
        assertEquals("en", callbackTriggered[0])
        assertEquals("zh-CN", callbackTriggered[1])
        assertNull(callbackTriggered[2])
    }

    @Test
    @Config(sdk = [33])
    fun languageChangeCallback_multipleListeners_allReceiveEvents() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

        val listener1Events = mutableListOf<String?>()
        val listener2Events = mutableListOf<String?>()

        AppLocale.registerLanguageChangeListener(TEST_LISTENER_1) { newTag ->
            listener1Events.add(newTag)
        }
        AppLocale.registerLanguageChangeListener(TEST_LISTENER_3) { newTag ->
            listener2Events.add(newTag)
        }

        AppLocale.applyLanguage(activity, "en")

        assertEquals(1, listener1Events.size)
        assertEquals(1, listener2Events.size)
        assertEquals("en", listener1Events[0])
        assertEquals("en", listener2Events[0])
    }

    @Test
    @Config(sdk = [33])
    fun wrap_usesLatestPreferenceWithoutContextRecreation() {
        val context = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

        AppLocale.setSelectedLanguageTag(context, "en")
        val wrappedEn = AppLocale.wrap(context)
        val enTag = wrappedEn.resources.configuration.locales[0].toLanguageTag()

        AppLocale.setSelectedLanguageTag(context, "zh-CN")
        val wrappedZh = AppLocale.wrap(context)
        val zhTag = wrappedZh.resources.configuration.locales[0].toLanguageTag()

        assertEquals("en", enTag)
        assertTrue(zhTag.startsWith("zh"))
    }

    @Test
    @Config(sdk = [33])
    fun languageChangeCallback_oneListenerThrows_othersStillRun() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val listener2Events = mutableListOf<String?>()

        AppLocale.registerLanguageChangeListener(TEST_LISTENER_1) {
            throw IllegalStateException("boom")
        }
        AppLocale.registerLanguageChangeListener(TEST_LISTENER_2) { newTag ->
            listener2Events.add(newTag)
        }

        AppLocale.applyLanguage(activity, "en")

        assertEquals(1, listener2Events.size)
        assertEquals("en", listener2Events[0])
    }
}

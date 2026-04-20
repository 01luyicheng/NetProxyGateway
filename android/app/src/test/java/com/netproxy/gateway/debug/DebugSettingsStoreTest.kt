package com.netproxy.gateway.debug

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.netproxy.gateway.BuildConfig
import dagger.hilt.android.testing.HiltTestApplication
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class DebugSettingsStoreTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("debug_settings", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @After
    fun tearDown() {
        context.getSharedPreferences("debug_settings", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun isSkipMqttCertValidationEnabled_defaultsToBuildConfigValue() {
        assertEquals(
            BuildConfig.MQTT_TRUST_ALL_CERTS,
            DebugSettingsStore.isSkipMqttCertValidationEnabled(context)
        )
    }

    @Test
    fun setSkipMqttCertValidationEnabled_true_isPersisted() {
        DebugSettingsStore.setSkipMqttCertValidationEnabled(context, true)

        assertTrue(DebugSettingsStore.isSkipMqttCertValidationEnabled(context))
    }

    @Test
    fun setSkipMqttCertValidationEnabled_false_isPersisted() {
        DebugSettingsStore.setSkipMqttCertValidationEnabled(context, true)
        DebugSettingsStore.setSkipMqttCertValidationEnabled(context, false)

        assertFalse(DebugSettingsStore.isSkipMqttCertValidationEnabled(context))
    }
}

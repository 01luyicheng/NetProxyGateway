package com.netproxy.gateway.connection

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class MqttConnectionManagerTlsPolicyTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var testScope: TestScope
    private lateinit var context: Context
    private lateinit var manager: MqttConnectionManager

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        testScope = TestScope(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("debug_settings", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        manager = MqttConnectionManager(context, testScope)
    }

    @After
    fun tearDown() {
        manager.disconnect()
        testScope.advanceUntilIdle()
        context.getSharedPreferences("debug_settings", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        Dispatchers.resetMain()
    }

    @Test
    fun shouldTrustAllCertificatesForCurrentBuild_releaseBuildAlwaysFalse() {
        val result = manager.shouldTrustAllCertificatesForCurrentBuild(isDebugBuild = false)
        assertFalse(result)
    }

    @Test
    fun shouldTrustAllCertificatesForCurrentBuild_debugBuildAlwaysFalse() {
        val result = manager.shouldTrustAllCertificatesForCurrentBuild(isDebugBuild = true)
        assertFalse(result)
    }

    @Test
    fun buildConnectionLostAuditMessage_releaseBuildHidesRawMessage() {
        val message = manager.buildConnectionLostAuditMessage(
            cause = IllegalStateException("sensitive connection detail"),
            isDebugBuild = false
        )

        assertEquals("Connection lost", message)
    }

    @Test
    fun buildConnectionLostAuditMessage_debugBuildKeepsRawMessage() {
        val message = manager.buildConnectionLostAuditMessage(
            cause = IllegalStateException("certificate mismatch"),
            isDebugBuild = true
        )

        assertEquals("Connection lost: certificate mismatch", message)
    }

    @Test
    fun buildConnectionErrorAuditMessage_releaseBuildHidesRawMessage() {
        val message = manager.buildConnectionErrorAuditMessage(
            error = IllegalArgumentException("token expired"),
            isDebugBuild = false
        )

        assertEquals("Connection error", message)
    }

    @Test
    fun buildConnectionErrorAuditMessage_debugBuildKeepsRawMessage() {
        val message = manager.buildConnectionErrorAuditMessage(
            error = IllegalArgumentException("token expired"),
            isDebugBuild = true
        )

        assertEquals("Connection error: token expired", message)
    }

    @Test
    fun buildConnectionLostAuditMessage_nullCause_returnsUnknownReason() {
        val message = manager.buildConnectionLostAuditMessage(
            cause = null,
            isDebugBuild = true
        )

        assertEquals("Connection lost: unknown reason", message)
    }

    @Test
    fun buildConnectionLostAuditMessage_nullCause_releaseBuild() {
        val message = manager.buildConnectionLostAuditMessage(
            cause = null,
            isDebugBuild = false
        )

        assertEquals("Connection lost", message)
    }

    @Test
    fun buildConnectionErrorAuditMessage_nullMessage_returnsUnknown() {
        val error = Throwable().apply { }
        val message = manager.buildConnectionErrorAuditMessage(
            error = error,
            isDebugBuild = true
        )

        assertEquals("Connection error: unknown", message)
    }

    @Test
    fun buildConnectionErrorAuditMessage_nullMessage_releaseBuild() {
        val error = Throwable().apply { }
        val message = manager.buildConnectionErrorAuditMessage(
            error = error,
            isDebugBuild = false
        )

        assertEquals("Connection error", message)
    }

    @Test
    fun buildConnectionLostAuditMessage_emptyMessage_returnsEmpty() {
        val message = manager.buildConnectionLostAuditMessage(
            cause = IllegalStateException(""),
            isDebugBuild = true
        )

        assertEquals("Connection lost: ", message)
    }

    @Test
    fun buildConnectionErrorAuditMessage_emptyMessage_returnsEmpty() {
        val message = manager.buildConnectionErrorAuditMessage(
            error = IllegalArgumentException(""),
            isDebugBuild = true
        )

        assertEquals("Connection error: ", message)
    }
}

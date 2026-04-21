package com.netproxy.gateway.security

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EmulatorDetectorTest {

    private val context = mockk<Context>(relaxed = true)
    private val telephonyManager = mockk<TelephonyManager>(relaxed = true)

    @Before
    fun setUp() {
        mockkStatic(ContextCompat::class)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun isKnownEmulatorPhoneNumber_returnsFalse_forNull() {
        assertFalse(EmulatorDetector.isKnownEmulatorPhoneNumber(null))
    }

    @Test
    fun isKnownEmulatorPhoneNumber_returnsFalse_forEmpty() {
        assertFalse(EmulatorDetector.isKnownEmulatorPhoneNumber(""))
    }

    @Test
    fun isKnownEmulatorPhoneNumber_returnsFalse_forNormalNumber() {
        assertFalse(EmulatorDetector.isKnownEmulatorPhoneNumber("13800138000"))
    }

    @Test
    fun isKnownEmulatorPhoneNumber_returnsTrue_forKnownEmulatorNumber() {
        assertTrue(EmulatorDetector.isKnownEmulatorPhoneNumber("15555215554"))
    }

    @Test
    fun checkPhoneNumber_returnsFalse_whenPermissionDenied() {
        every {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
        } returns PackageManager.PERMISSION_DENIED

        assertFalse(EmulatorDetector.checkPhoneNumber(context))
    }

    @Test
    fun checkPhoneNumber_returnsFalse_whenPhoneNumberIsNull() {
        every {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
        } returns PackageManager.PERMISSION_GRANTED
        every { context.getSystemService(Context.TELEPHONY_SERVICE) } returns telephonyManager
        every { telephonyManager.line1Number } returns null

        assertFalse(EmulatorDetector.checkPhoneNumber(context))
    }

    @Test
    fun checkPhoneNumber_returnsTrue_whenPhoneNumberIsKnownEmulatorNumber() {
        every {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
        } returns PackageManager.PERMISSION_GRANTED
        every { context.getSystemService(Context.TELEPHONY_SERVICE) } returns telephonyManager
        every { telephonyManager.line1Number } returns "15555215554"

        assertTrue(EmulatorDetector.checkPhoneNumber(context))
    }
}

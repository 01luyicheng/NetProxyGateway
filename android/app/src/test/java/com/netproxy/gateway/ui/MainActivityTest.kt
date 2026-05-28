package com.netproxy.gateway.ui

import android.util.TypedValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class MainActivityTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    @Test
    @Config(sdk = [33])
    fun mainActivity_launchesWithoutThemeCrash() {
        Robolectric.buildActivity(MainActivity::class.java).create().get()
    }

    @Test
    @Config(sdk = [33])
    fun mainActivity_themeResolvesAppCompatColorPrimary() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().get()
        val typedValue = TypedValue()
        val resolved = activity.theme.resolveAttribute(androidx.appcompat.R.attr.colorPrimary, typedValue, true)

        assertTrue(
            "MainActivity theme should resolve AppCompat colorPrimary at runtime",
            resolved
        )
    }
}

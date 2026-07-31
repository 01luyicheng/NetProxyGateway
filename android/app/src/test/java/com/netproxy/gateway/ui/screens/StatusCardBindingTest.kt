package com.netproxy.gateway.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.ui.graphics.Color
import com.netproxy.gateway.R
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for REV62: the spinner (ConnectionStatusCard) and the VPN indicator
 * color (VpnStatusCard) must be derived from the per-row AnimatedContent **target**
 * StatusCardData, not from the shared current UiState.
 *
 * During an AnimatedContent crossfade Compose composes both the fading-out and the
 * fading-in rows against the *current* UiState. If the spinner/indicator decision read
 * `uiState.mqttState`/`uiState.isVpnEnabled` (as it did before REV62), the fading-out row
 * would render a spinner/indicator belonging to the NEW state — disagreeing with its own
 * icon and text for the ~300ms transition window.
 *
 * These tests pin the invariant the fix relies on:
 *   - the spinner shows iff the target row has no icon (the Connecting state);
 *   - the VPN indicator is "running" iff the target row is the running row.
 * Both decisions are now pure functions of the per-row target, so a fading-out row always
 * renders the spinner/indicator that matches its own icon and text.
 */
class StatusCardBindingTest {

    @Test
    fun showsSpinner_trueOnlyForConnectingRow_nullIcon() {
        // Connecting is the only StatusCardData with a null icon -> spinner.
        val connecting = StatusCardData(
            containerColor = Color.Yellow,
            contentColor = Color.Black,
            textRes = R.string.status_connecting,
            icon = null
        )
        assertTrue(connecting.showsSpinner())

        // Every other MQTT state carries a non-null icon -> no spinner, render the icon.
        val connected = StatusCardData(
            containerColor = Color.Green,
            contentColor = Color.White,
            textRes = R.string.status_connected,
            icon = Icons.Default.CheckCircle
        )
        val error = StatusCardData(
            containerColor = Color.Red,
            contentColor = Color.White,
            textRes = R.string.status_error,
            icon = Icons.Default.Error
        )
        val disconnected = StatusCardData(
            containerColor = Color.Gray,
            contentColor = Color.Black,
            textRes = R.string.status_disconnected,
            icon = Icons.Default.CloudOff
        )
        assertFalse(connected.showsSpinner())
        assertFalse(error.showsSpinner())
        assertFalse(disconnected.showsSpinner())
    }

    @Test
    fun isVpnRunning_trueOnlyForRunningRow() {
        val running = StatusCardData(
            containerColor = Color.Green,
            contentColor = Color.White,
            textRes = R.string.vpn_running,
            icon = Icons.Default.VpnKey
        )
        val stopped = StatusCardData(
            containerColor = Color.Gray,
            contentColor = Color.Black,
            textRes = R.string.vpn_stopped,
            icon = Icons.Default.VpnKey
        )
        assertTrue(running.isVpnRunning())
        assertFalse(stopped.isVpnRunning())
    }
}

package com.netproxy.gateway.ui.screens

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression tests for REV63: the `NetworkStatusRow` color (Icon `tint` and trailing
 * Text `color`) must be derived from the per-row Crossfade **target** (`isActive`), not
 * from an outer `animateColorAsState` driven by the shared `active` flag.
 *
 * During a `Crossfade` transition Compose composes BOTH the fading-out row
 * (`isActive` = OLD state) and the fading-in row (`isActive` = NEW state) against the
 * *current* outer `active`. If the color read an outer `animateColorAsState` (as it did
 * before REV63, via the shared `animatedColor`), both rows would share a single animated
 * color moving toward the NEW color — so the fading-out row rendered the OLD icon tinted
 * with a color moving toward the NEW color, while the fading-in row rendered the NEW icon
 * tinted with a color still near the OLD color (a ~300ms user-visible icon/color mismatch
 * on every WiFi/cellular connect/disconnect). This is the same anti-pattern REV62 fixed
 * for the `ConnectionStatusCard` spinner and the `VpnStatusCard` indicator.
 *
 * These tests pin the invariant the fix relies on: the row color is a pure function of
 * the per-row target `isActive`, so each crossfade row is always self-consistent
 * (icon + color always agree). `networkStatusRowColor` is `internal` for same-module
 * test access. Runs under `:app:testDebugUnitTest`; no emulator required.
 */
class NetworkStatusRowColorTest {

    @Test
    fun activeRowUsesActiveColor() {
        val active = Color.Green
        val inactive = Color.Gray
        assertEquals(
            active,
            networkStatusRowColor(isActive = true, activeColor = active, inactiveColor = inactive)
        )
    }

    @Test
    fun inactiveRowUsesInactiveColor() {
        val active = Color.Green
        val inactive = Color.Gray
        assertEquals(
            inactive,
            networkStatusRowColor(isActive = false, activeColor = active, inactiveColor = inactive)
        )
    }

    @Test
    fun colorFollowsPerRowTarget_notOuterSharedState() {
        // Pin the per-row invariant: flipping `isActive` flips the color, independent of
        // any outer shared/animated state. This is what makes each Crossfade row
        // self-consistent during the ~300ms transition window.
        val active = Color(0xFF1B5E20)
        val inactive = Color(0xFF9E9E9E)
        assertEquals(
            active,
            networkStatusRowColor(isActive = true, activeColor = active, inactiveColor = inactive)
        )
        assertEquals(
            inactive,
            networkStatusRowColor(isActive = false, activeColor = active, inactiveColor = inactive)
        )
    }
}

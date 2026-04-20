package com.netproxy.gateway.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class MainScreenNavigationTest {

    @Test
    fun backTargetPageName_followsHomeSettingsAuditLogsChain() {
        assertEquals(
            MainScreenNavigation.homePageName,
            MainScreenNavigation.backTargetPageName(MainScreenNavigation.homePageName)
        )
        assertEquals(
            MainScreenNavigation.homePageName,
            MainScreenNavigation.backTargetPageName(MainScreenNavigation.settingsPageName)
        )
        assertEquals(
            MainScreenNavigation.settingsPageName,
            MainScreenNavigation.backTargetPageName(MainScreenNavigation.auditLogsPageName)
        )
    }

    @Test
    fun normalizePageName_invalidValueFallsBackToHome() {
        assertEquals(
            MainScreenNavigation.homePageName,
            MainScreenNavigation.normalizePageName("Unknown")
        )
    }

    @Test
    fun backTargetPageName_invalidValueFallsBackToHome() {
        assertEquals(
            MainScreenNavigation.homePageName,
            MainScreenNavigation.backTargetPageName("Unknown")
        )
    }
}

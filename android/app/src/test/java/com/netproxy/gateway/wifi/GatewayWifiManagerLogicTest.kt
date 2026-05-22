package com.netproxy.gateway.wifi

import com.netproxy.gateway.result.AppResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayWifiManagerLogicTest {

    @Test
    fun shouldUseNetworkSuggestion_returnsExpectedByApiLevel() {
        assertFalse(GatewayWifiManager.shouldUseNetworkSuggestion(28))
        assertTrue(GatewayWifiManager.shouldUseNetworkSuggestion(29))
        assertTrue(GatewayWifiManager.shouldUseNetworkSuggestion(35))
    }

    @Test
    fun parseSecurityType_classifiesCommonCapabilities() {
        assertEquals(
            GatewayWifiManager.SecurityType.WPA3_SAE,
            GatewayWifiManager.parseSecurityType("[WPA3-SAE-CCMP][ESS]")
        )
        assertEquals(
            GatewayWifiManager.SecurityType.WPA2_PSK,
            GatewayWifiManager.parseSecurityType("[WPA2-PSK-CCMP][ESS]")
        )
        assertEquals(
            GatewayWifiManager.SecurityType.WPA_PSK,
            GatewayWifiManager.parseSecurityType("[WPA-PSK-TKIP][ESS]")
        )
        assertEquals(
            GatewayWifiManager.SecurityType.WEP,
            GatewayWifiManager.parseSecurityType("[WEP][ESS]")
        )
        assertEquals(
            GatewayWifiManager.SecurityType.OPEN,
            GatewayWifiManager.parseSecurityType("[ESS]")
        )
    }

    @Test
    fun isSecureCapabilities_matchesSecurityType() {
        assertTrue(GatewayWifiManager.isSecureCapabilities("[WPA2-PSK-CCMP][ESS]"))
        assertFalse(GatewayWifiManager.isSecureCapabilities("[ESS]"))
    }

    @Test
    fun suggestionRecord_serializationRoundTrip_preservesFields() {
        val record = GatewayWifiManager.SuggestionRecord(
            ssid = "Office WiFi",
            password = "P@ss|with:symbols",
            securityType = GatewayWifiManager.SecurityType.WPA2_PSK
        )

        val serialized = GatewayWifiManager.serializeSuggestionRecord(record)
        val deserialized = GatewayWifiManager.deserializeSuggestionRecord(serialized)

        assertEquals(record, deserialized)
    }

    @Test
    fun suggestionRecord_deserialize_handlesEmptyOrInvalidInput() {
        assertNull(GatewayWifiManager.deserializeSuggestionRecord(""))
        assertNull(GatewayWifiManager.deserializeSuggestionRecord("not|valid"))
        assertNull(GatewayWifiManager.deserializeSuggestionRecord("|||"))
    }

    @Test
    fun suggestionRecord_deserialize_handlesBlankPasswordAsNull() {
        val serialized = "VGVzdA==|WPA2_PSK|"
        val deserialized = GatewayWifiManager.deserializeSuggestionRecord(serialized)

        assertEquals(
            GatewayWifiManager.SuggestionRecord(
                ssid = "Test",
                password = null,
                securityType = GatewayWifiManager.SecurityType.WPA2_PSK
            ),
            deserialized
        )
    }

    @Test
    fun suggestionSubmissionAccepted_semanticsMatchStatusCode() {
        assertTrue(
            GatewayWifiManager.isSuggestionSubmissionAccepted(
                android.net.wifi.WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS
            )
        )
        assertFalse(GatewayWifiManager.isSuggestionSubmissionAccepted(-1))
    }

    @Test
    fun suggestionConnectResult_returnsFalseWhenSubmissionNotAccepted() {
        assertFalse(
            GatewayWifiManager.resolveSuggestionConnectResult(
                submissionAccepted = false,
                connectedToTargetSsid = true
            )
        )
    }

    @Test
    fun suggestionConnectResult_returnsTrueWhenSubmissionAcceptedButNotConnectedYet() {
        assertTrue(
            GatewayWifiManager.resolveSuggestionConnectResult(
                submissionAccepted = true,
                connectedToTargetSsid = false
            )
        )
    }

    @Test
    fun suggestionConnectResult_returnsTrueOnlyWhenAcceptedAndConnected() {
        assertTrue(
            GatewayWifiManager.resolveSuggestionConnectResult(
                submissionAccepted = true,
                connectedToTargetSsid = true
            )
        )
    }

    @Test
    fun canPersistSuggestionSafely_returnsFalseForSecuredNetworkWhenStorageNotEncrypted() {
        assertFalse(
            GatewayWifiManager.canPersistSuggestionSafely(
                isEncryptedStorageAvailable = false,
                securityType = GatewayWifiManager.SecurityType.WPA2_PSK
            )
        )
    }

    @Test
    fun canPersistSuggestionSafely_returnsTrueForOpenNetworkWhenStorageNotEncrypted() {
        assertTrue(
            GatewayWifiManager.canPersistSuggestionSafely(
                isEncryptedStorageAvailable = false,
                securityType = GatewayWifiManager.SecurityType.OPEN
            )
        )
    }

    @Test
    fun canPersistSuggestionSafely_returnsTrueWhenStorageEncrypted() {
        assertTrue(
            GatewayWifiManager.canPersistSuggestionSafely(
                isEncryptedStorageAvailable = true,
                securityType = GatewayWifiManager.SecurityType.WPA3_SAE
            )
        )
    }

    @Test
    fun toLegacyStartScanReturn_returnsFalseOnError() {
        val fallback = GatewayWifiManager.toLegacyStartScanReturn(
            AppResult.error(IllegalStateException("scan failed"))
        )
        assertFalse(fallback)
    }

    @Test
    fun toLegacyScanResultsReturn_returnsEmptyOnError() {
        val fallback = GatewayWifiManager.toLegacyScanResultsReturn(
            AppResult.error(IllegalStateException("read failed"))
        )
        assertTrue(fallback.isEmpty())
    }

    @Test
    fun toLegacyStartScanReturn_returnsTrueOnSuccess() {
        val result = GatewayWifiManager.toLegacyStartScanReturn(
            AppResult.success(true)
        )
        assertTrue(result)
    }

    @Test
    fun toLegacyStartScanReturn_returnsFalseOnSuccessFalse() {
        val result = GatewayWifiManager.toLegacyStartScanReturn(
            AppResult.success(false)
        )
        assertFalse(result)
    }

    @Test
    fun toLegacyScanResultsReturn_returnsDataOnSuccess() {
        val networks = listOf(
            WifiNetwork(
                ssid = "TestNetwork",
                bssid = "00:11:22:33:44:55",
                signalStrength = -50,
                frequency = 2412,
                capabilities = "[WPA2-PSK-CCMP]",
                isSecure = true
            )
        )
        val result = GatewayWifiManager.toLegacyScanResultsReturn(
            AppResult.success(networks)
        )
        assertEquals(1, result.size)
        assertEquals("TestNetwork", result[0].ssid)
    }

    @Test
    fun toLegacyScanResultsReturn_returnsEmptyOnSuccessEmptyList() {
        val result = GatewayWifiManager.toLegacyScanResultsReturn(
            AppResult.success(emptyList())
        )
        assertTrue(result.isEmpty())
    }
}

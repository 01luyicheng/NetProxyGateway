package com.netproxy.gateway.connection

import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.cert.CertificateException
import java.util.Base64

class MqttTlsPinningTest {

    @Test
    fun parseConfiguredPins_parsesValidPinsAndIgnoresInvalidValues() {
        val pinA = Base64.getEncoder().encodeToString(ByteArray(32) { 1 })
        val pinB = Base64.getEncoder().encodeToString(ByteArray(32) { 2 })

        val parsed = MqttTlsPinning.parseConfiguredPins(
            " sha256/$pinA, $pinB,invalid,sha256/not_base64 "
        )

        assertEquals(setOf(pinA, pinB), parsed)
    }

    @Test
    fun verifyPinMatch_acceptsWhenAnyConfiguredPinMatches() {
        val pinA = Base64.getEncoder().encodeToString(ByteArray(32) { 3 })
        val pinB = Base64.getEncoder().encodeToString(ByteArray(32) { 4 })
        val pinC = Base64.getEncoder().encodeToString(ByteArray(32) { 5 })

        MqttTlsPinning.verifyPinMatch(
            configuredPins = setOf(pinA, pinB),
            presentedPins = setOf(pinC, pinB)
        )
    }

    @Test(expected = CertificateException::class)
    fun verifyPinMatch_throwsWhenNoPinMatches() {
        val pinA = Base64.getEncoder().encodeToString(ByteArray(32) { 6 })
        val pinB = Base64.getEncoder().encodeToString(ByteArray(32) { 7 })
        val pinC = Base64.getEncoder().encodeToString(ByteArray(32) { 8 })

        MqttTlsPinning.verifyPinMatch(
            configuredPins = setOf(pinA, pinB),
            presentedPins = setOf(pinC)
        )
    }
}

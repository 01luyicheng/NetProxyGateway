package com.netproxy.gateway.connection

import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.X509TrustManager

object MqttTlsPinning {
    private const val PIN_PREFIX = "sha256/"
    private const val SHA256_LENGTH_BYTES = 32

    fun parseConfiguredPins(rawPins: String?): Set<String> {
        if (rawPins.isNullOrBlank()) {
            return emptySet()
        }
        return rawPins
            .split(',')
            .asSequence()
            .mapNotNull { normalizePin(it) }
            .toSet()
    }

    fun createPinningTrustManager(delegate: X509TrustManager, rawPins: String?): X509TrustManager {
        val configuredPins = parseConfiguredPins(rawPins)
        return object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                delegate.checkClientTrusted(chain, authType)
            }

            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                delegate.checkServerTrusted(chain, authType)
                verifyPinMatch(
                    configuredPins = configuredPins,
                    presentedPins = extractPresentedPins(chain)
                )
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers
        }
    }

    @Throws(CertificateException::class)
    fun verifyPinMatch(configuredPins: Set<String>, presentedPins: Set<String>) {
        if (configuredPins.isEmpty()) {
            return
        }
        val matched = presentedPins.any { pin -> pin in configuredPins }
        if (!matched) {
            throw CertificateException(
                "MQTT TLS public key pin verification failed. " +
                "Configured pins: ${configuredPins.size}, " +
                "Presented pins: ${presentedPins.size}"
            )
        }
    }

    private fun extractPresentedPins(chain: Array<X509Certificate>?): Set<String> {
        if (chain.isNullOrEmpty()) {
            return emptySet()
        }
        return chain
            .asSequence()
            .map { cert -> computePublicKeyPin(cert.publicKey.encoded) }
            .toSet()
    }

    private fun computePublicKeyPin(publicKeyEncoded: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(publicKeyEncoded)
        return Base64.getEncoder().encodeToString(digest)
    }

    private fun normalizePin(rawPin: String): String? {
        val trimmed = rawPin.trim()
        if (trimmed.isEmpty()) {
            return null
        }
        val withoutPrefix = if (trimmed.startsWith(PIN_PREFIX, ignoreCase = true)) {
            trimmed.substring(PIN_PREFIX.length)
        } else {
            trimmed
        }
        val decoded = try {
            Base64.getDecoder().decode(withoutPrefix)
        } catch (_: IllegalArgumentException) {
            return null
        }
        return if (decoded.size == SHA256_LENGTH_BYTES) {
            withoutPrefix
        } else {
            null
        }
    }
}
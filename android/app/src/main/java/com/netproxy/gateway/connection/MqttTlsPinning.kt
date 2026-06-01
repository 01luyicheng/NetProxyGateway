package com.netproxy.gateway.connection

import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.X509TrustManager

object MqttTlsPinning {
    private const val PIN_PREFIX = "sha256/"
    private const val SHA256_LENGTH_BYTES = 32

    /**
     * 从逗号分隔的原始字符串解析并规范化公钥 pin 集合。
     *
     * 对输入进行拆分、修剪并使用规范化规则过滤无效或空的条目；接受带或不带 `sha256/` 前缀的 Base64 表示并校验其为 SHA-256 长度。
     *
     * @param rawPins 逗号分隔的 pin 字符串，可能为 `null` 或空白；单个条目可带有可选前缀 `sha256/`。
     * @return 通过验证并已去除可选前缀的 Base64 pin 的集合；当输入为 `null`、空白或没有有效条目时返回空集合。
     */
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

    /**
     * 为指定的 `X509TrustManager` 创建一个包装的 `X509TrustManager`，在委托的服务器证书验证后执行公钥 pinning 验证。
     *
     * 该包装管理器将所有客户端/接受者相关的信任决策委托给传入的 `delegate`；对服务器证书的验证，在委托验证通过后，会将证书链导出的公钥 pin 与从 `rawPins` 解析得到的配置 pins 进行比对（当配置 pins 为空时不进行强制 pinning）。
     *
     * @param delegate 要委托实际证书链验证的 `X509TrustManager`。
     * @param rawPins 可选的逗号分隔 pin 列表（支持可选的 `"sha256/"` 前缀）；为 `null` 或空字符串表示不启用 pinning。
     * @return 一个 `X509TrustManager`：客户端验证完全委托给 `delegate`，服务器验证在委托验证后会执行配置的公钥 pin 校验。
     */
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

    /**
     * 验证已呈现的公钥 pin 是否至少与一个配置的 pin 匹配。
     *
     * @param configuredPins 已配置并规范化的 pin 集合（Base64 SHA-256 表示，已去除可选前缀），为空集表示不启用 pin 校验。
     * @param presentedPins 从对端证书链提取出的 pin 集合（Base64 SHA-256 表示）。
     * @throws CertificateException 当配置的 pin 非空且没有任何已呈现的 pin 与之匹配时抛出，异常信息包含配置与已呈现 pin 的计数。 
     */
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

    /**
     * 从证书链中提取每个证书的公钥 pin（Base64 编码的 SHA-256 摘要）。
     *
     * @param chain X.509 证书数组，可能为 `null` 或空。
     * @return 包含每个证书公钥 pin 的集合；当 `chain` 为 `null` 或空时返回空集。
     */
    private fun extractPresentedPins(chain: Array<X509Certificate>?): Set<String> {
        if (chain.isNullOrEmpty()) {
            return emptySet()
        }
        return chain
            .asSequence()
            .map { cert -> computePublicKeyPin(cert.publicKey.encoded) }
            .toSet()
    }

    /**
     * 计算公钥编码字节的 SHA-256 摘要并以 Base64 字符串返回。
     *
     * @return `publicKeyEncoded` 的 SHA-256 摘要经 Base64 编码后的字符串。
     */
    private fun computePublicKeyPin(publicKeyEncoded: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(publicKeyEncoded)
        return Base64.getEncoder().encodeToString(digest)
    }

    /**
     * 规范化并验证单个公钥 pin 字符串。
     *
     * 对输入进行修剪并可接受可选的 `sha256/` 前缀；验证去除前缀后的内容为可 Base64 解码且解码后长度为 32 字节（SHA-256 长度）。
     *
     * @param rawPin 原始 pin 字符串，可能包含前后空白或可选的 `sha256/` 前缀。
     * @return 去除 `sha256/` 前缀后的 Base64 表示（用于匹配）如果合法，非法或不符合长度时返回 `null`。
     */
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

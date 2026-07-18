package com.netproxy.gateway.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class VpnLogRedactionTest {

    @Test
    fun redactIp_validIpv4_returnsRedactedIpv4() {
        assertEquals("*.*.*.*", redactIp("192.168.1.1"))
        assertEquals("*.*.*.*", redactIp("0.0.0.0"))
        assertEquals("*.*.*.*", redactIp("255.255.255.255"))
    }

    @Test
    fun redactIp_validIpv4WithWhitespace_returnsRedactedIpv4() {
        assertEquals("*.*.*.*", redactIp(" 10.0.0.1 "))
    }

    @Test
    fun redactIp_validIpv6_returnsRedactedIpv6() {
        assertEquals("****:****:****:****:****:****:****:****", redactIp("2001:0db8:85a3:0000:0000:8a2e:0370:7334"))
        assertEquals("****:****:****:****:****:****:****:****", redactIp("::1"))
        assertEquals("****:****:****:****:****:****:****:****", redactIp("fe80::1ff:fe23:4567:890a"))
    }

    @Test
    fun redactIp_validIpv6WithWhitespace_returnsRedactedIpv6() {
        assertEquals("****:****:****:****:****:****:****:****", redactIp("\t::1\n"))
    }

    @Test
    fun redactIp_invalidIpv4_returnsRedactedUnknown() {
        assertEquals("***", redactIp("256.1.1.1")) // out of range
        assertEquals("***", redactIp("1.2.3"))     // not enough parts
        assertEquals("***", redactIp("1.2.3.4.5")) // too many parts
        assertEquals("***", redactIp("1.a.3.4"))   // non-digit
        assertEquals("***", redactIp("1..3.4"))    // empty part
    }

    @Test
    fun redactIp_invalidIpv6_returnsRedactedUnknown() {
        assertEquals("***", redactIp("2001:0db8:85a3:0000:0000:8a2e:0370:7334:1234"))
        assertEquals("***", redactIp("invalid:ipv6::"))
    }

    @Test
    fun redactIp_arbitraryString_returnsRedactedUnknown() {
        assertEquals("***", redactIp("localhost"))
        assertEquals("***", redactIp("some.domain.com"))
        assertEquals("***", redactIp("not an ip"))
        assertEquals("***", redactIp(""))
    }

    @Test
    fun redactConnectionKey_validFormat_returnsRedactedKey() {
        // "srcIp:srcPort-dstIp:dstPort"
        val key = "192.168.1.100:12345-8.8.8.8:53"
        val expected = "*.*.*.*- *.*.*.*"
        assertEquals(expected, redactConnectionKey(key))
    }

    @Test
    fun redactConnectionKey_validFormatIpv6_returnsRedactedKey() {
        // TODO(VPNLOG-IPV6-1): redactConnectionKey uses substringBefore(":") (L94-95) which
        // truncates IPv6 addresses at the first colon. See docs/ISSUES.md (VPNLOG-IPV6-1).
        // This test records the current (buggy) behavior. When the implementation is fixed to
        // correctly parse IPv6 connection keys, update expected to:
        // "****:****:****:****:****:****:****:****- ****:****:****:****:****:****:****:****"
        // Since VpnLogRedaction strips everything after the first ':',
        // it may actually break for IPv6 if we're not careful.
        // Let's test what redactConnectionKey actually does for IPv6.
        // Left part: "fe80::1ff" stringBefore(":") returns "fe80", which is invalid IPv6 on its own!
        // Right part: "2001:4860..." returns "2001".
        // So they will both be redacted as "***".
        val key = "fe80::1ff:fe23:4567:890a:54321-2001:4860:4860::8888:443"
        val expected = "***- ***"
        assertEquals(expected, redactConnectionKey(key))
    }

    @Test
    fun redactConnectionKey_invalidFormat_returnsRedactedMask() {
        // Missing '-'
        assertEquals("***", redactConnectionKey("192.168.1.1:12345 to 8.8.8.8:53"))
        // More than 2 segments separated by '-'
        assertEquals("***", redactConnectionKey("192.168.1.1:123-8.8.8.8:53-extra"))
    }

    @Test
    fun redactConnectionKey_invalidIpInFormat_returnsMixedRedaction() {
        val key = "not_an_ip:12345-8.8.8.8:53"
        val expected = "***- *.*.*.*"
        assertEquals(expected, redactConnectionKey(key))
    }
}

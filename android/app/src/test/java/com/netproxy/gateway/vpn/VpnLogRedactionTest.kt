package com.netproxy.gateway.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class VpnLogRedactionTest {

    @Test
    fun redactIp_withValidIpv4_returnsIpv4Redacted() {
        assertEquals("*.*.*.*", redactIp("192.168.1.1"))
        assertEquals("*.*.*.*", redactIp("0.0.0.0"))
        assertEquals("*.*.*.*", redactIp("255.255.255.255"))
        assertEquals("*.*.*.*", redactIp("  10.0.0.1  "))
    }

    @Test
    fun redactIp_withValidIpv6_returnsIpv6Redacted() {
        assertEquals("****:****:****:****:****:****:****:****", redactIp("2001:0db8:85a3:0000:0000:8a2e:0370:7334"))
        assertEquals("****:****:****:****:****:****:****:****", redactIp("::1"))
        assertEquals("****:****:****:****:****:****:****:****", redactIp("  ::1  "))
    }

    @Test
    fun redactIp_withInvalidInputs_returnsUnknownRedacted() {
        assertEquals("***", redactIp("invalid"))
        assertEquals("***", redactIp("192.168.1")) // incomplete
        assertEquals("***", redactIp("192.168.1.256")) // out of range
        assertEquals("***", redactIp("192.168.1.1.1")) // too many parts
        assertEquals("***", redactIp("192.168..1")) // empty part
        assertEquals("***", redactIp("192.168.1.-1")) // negative
        assertEquals("***", redactIp("2001:0gb8:85a3:0000:0000:8a2e:0370:7334")) // invalid hex
        assertEquals("***", redactIp("::1::")) // invalid ipv6 structure
        assertEquals("***", redactIp("")) // empty
        assertEquals("***", redactIp("   ")) // blank
    }

    @Test
    fun redactConnectionKey_withValidFormat_returnsRedactedKey() {
        assertEquals("*.*.*.*- *.*.*.*", redactConnectionKey("192.168.1.1:12345-10.0.0.1:80"))
        assertEquals("***- *.*.*.*", redactConnectionKey("invalid:123-192.168.1.1:80"))
    }

    @Test
    fun redactConnectionKey_withInvalidFormat_returnsRedactMask() {
        assertEquals("***", redactConnectionKey("192.168.1.1:12345")) // no hyphen
        assertEquals("***", redactConnectionKey("192.168.1.1:12345-10.0.0.1:80-192.168.1.2:90")) // too many segments
        assertEquals("***", redactConnectionKey("just-a-string")) // invalid format but has one hyphen
    }
}

package com.netproxy.gateway.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IpAddressUtilsTest {

    @Test
    fun isPrivateIpv4Rfc1918_acceptsBoundaryPrivateRanges() {
        assertTrue(IpAddressUtils.isPrivateIpv4Rfc1918("10.0.0.0"))
        assertTrue(IpAddressUtils.isPrivateIpv4Rfc1918("10.255.255.255"))

        assertTrue(IpAddressUtils.isPrivateIpv4Rfc1918("172.16.0.0"))
        assertTrue(IpAddressUtils.isPrivateIpv4Rfc1918("172.31.255.255"))

        assertTrue(IpAddressUtils.isPrivateIpv4Rfc1918("192.168.0.0"))
        assertTrue(IpAddressUtils.isPrivateIpv4Rfc1918("192.168.255.255"))
    }

    @Test
    fun isPrivateIpv4Rfc1918_rejectsOutsideBoundaryAndInvalidValues() {
        assertFalse(IpAddressUtils.isPrivateIpv4Rfc1918("172.15.255.255"))
        assertFalse(IpAddressUtils.isPrivateIpv4Rfc1918("172.32.0.0"))
        assertFalse(IpAddressUtils.isPrivateIpv4Rfc1918("192.167.255.255"))
        assertFalse(IpAddressUtils.isPrivateIpv4Rfc1918("192.169.0.0"))
        assertFalse(IpAddressUtils.isPrivateIpv4Rfc1918("127.0.0.1"))
        assertFalse(IpAddressUtils.isPrivateIpv4Rfc1918("10.256.0.1"))
        assertFalse(IpAddressUtils.isPrivateIpv4Rfc1918("192.168.-1.1"))
        assertFalse(IpAddressUtils.isPrivateIpv4Rfc1918("abc"))
    }
}

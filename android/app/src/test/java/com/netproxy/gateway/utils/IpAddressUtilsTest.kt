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
        assertFalse(IpAddressUtils.isPrivateIpv4Rfc1918("abc"))
        assertFalse(IpAddressUtils.isPrivateIpv4Rfc1918("10.256.0.1"))
        assertFalse(IpAddressUtils.isPrivateIpv4Rfc1918("192.168.-1.1"))
    }

    @Test
    fun isPrivateIpv4Rfc1918WithResult_rejectsInvalidOctets() {
        val result1 = IpAddressUtils.isPrivateIpv4Rfc1918WithResult("10.256.0.1")
        assertTrue(result1.isSuccess())
        assertFalse(result1.getOrNull()!!)

        val result2 = IpAddressUtils.isPrivateIpv4Rfc1918WithResult("192.168.-1.1")
        assertTrue(result2.isSuccess())
        assertFalse(result2.getOrNull()!!)

        val result3 = IpAddressUtils.isPrivateIpv4Rfc1918WithResult("abc")
        assertTrue(result3.isError())
    }

    @Test
    fun validateIpv4WithResult_acceptsValidIpv4() {
        val result = IpAddressUtils.validateIpv4WithResult("192.168.1.1")
        assertTrue(result.isSuccess())
        assertTrue(result.getOrNull()!!)
    }

    @Test
    fun validateIpv4WithResult_rejectsInvalidOctet() {
        val result = IpAddressUtils.validateIpv4WithResult("10.256.0.1")
        assertTrue(result.isSuccess())
        assertFalse(result.getOrNull()!!)
    }

    @Test
    fun validateIpv4WithResult_returnsErrorForNonNumericFormat() {
        val result = IpAddressUtils.validateIpv4WithResult("abc")
        assertTrue(result.isError())
    }
}

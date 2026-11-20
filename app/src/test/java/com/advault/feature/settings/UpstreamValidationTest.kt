package com.advault.feature.settings

import com.advault.R
import com.advault.core.preferences.UpstreamProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpstreamValidationTest {
    @Test fun plainAcceptsIpv4AndIpv6Lists() {
        assertNull(UpstreamValidation.validate(UpstreamProtocol.PLAIN, "9.9.9.9"))
        assertNull(UpstreamValidation.validate(UpstreamProtocol.PLAIN, "1.1.1.1, 2606:4700:4700::1111"))
        assertNull(UpstreamValidation.validate(UpstreamProtocol.PLAIN, "::1"))
    }

    @Test fun plainRejectsHostnamesAndBadIps() {
        assertEquals(R.string.dns_custom_error_ip, UpstreamValidation.validate(UpstreamProtocol.PLAIN, "dns.google"))
        assertEquals(R.string.dns_custom_error_ip, UpstreamValidation.validate(UpstreamProtocol.PLAIN, "256.1.1.1"))
        assertEquals(R.string.dns_custom_error_ip, UpstreamValidation.validate(UpstreamProtocol.PLAIN, "1:2:3::4::5"))
        assertEquals(R.string.dns_custom_error_empty, UpstreamValidation.validate(UpstreamProtocol.PLAIN, "  "))
    }

    @Test fun ipv6Shapes() {
        assertTrue(UpstreamValidation.isIpv6("2001:db8:0:0:0:0:0:1"))
        assertTrue(UpstreamValidation.isIpv6("::ffff:192.0.2.1"))
        assertFalse(UpstreamValidation.isIpv6("2001:db8::12345"))
        assertFalse(UpstreamValidation.isIpv6("1:2:3:4:5:6:7"))
    }

    @Test fun dotNeedsHostname() {
        assertNull(UpstreamValidation.validate(UpstreamProtocol.DOT, "dns.quad9.net"))
        assertEquals(R.string.dns_custom_error_host, UpstreamValidation.validate(UpstreamProtocol.DOT, "https://dns.quad9.net"))
    }

    @Test fun dohNeedsHttpsUrl() {
        assertNull(UpstreamValidation.validate(UpstreamProtocol.DOH, "https://dns.google/dns-query"))
        assertEquals(R.string.dns_custom_error_https, UpstreamValidation.validate(UpstreamProtocol.DOH, "http://dns.google/dns-query"))
        assertEquals(R.string.dns_custom_error_url, UpstreamValidation.validate(UpstreamProtocol.DOH, "https:///dns-query"))
    }
}

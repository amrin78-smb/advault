package com.advault.filtering

import com.advault.filtering.parser.CustomRuleValidator
import com.advault.filtering.parser.CustomRuleValidator.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomRuleValidatorTest {

    private fun ok(input: String) = (CustomRuleValidator.validate(input) as? Outcome.Valid)?.rule

    @Test fun acceptsSupportedForms() {
        assertEquals("example.com", ok("example.com"))
        assertEquals("example.com", ok("  Example.COM. "))
        assertEquals("*.example.com", ok("*.example.com"))
        assertEquals("example.com", ok("||example.com^"))
        assertEquals("example.com", ok("@@||example.com^"))
        assertEquals("ads.example.com", ok("0.0.0.0 ads.example.com"))
        assertEquals("ads.example.com", ok("https://ads.example.com/path?q=1#x"))
        assertEquals("ads.example.com", ok("http://user:pw@ads.example.com:8080/x"))
        assertEquals("ads.example.com", ok("ads.example.com/banner.js"))
        assertEquals("xn--bcher-kva.de", ok("bücher.de"))
        assertEquals("example.com", ok(".example.com"))
    }

    @Test fun rejectsInvalid() {
        for (bad in listOf(
            "", "   ", "localhost", "com", "*.com", "*", "1.2.3.4", "https://192.168.0.1/x", "[::1]", "2001:db8::1",
            "exa mple.com", "ex*ample.com", "a.*.example.com", "-bad.com", "bad-.com", "||example.com^\$third-party",
            "a".repeat(64) + ".com", "javascript://alert(1)", "@@", "||^",
        )) {
            assertTrue("should reject '$bad'", CustomRuleValidator.validate(bad) is Outcome.Invalid)
        }
    }
}

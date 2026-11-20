package com.advault.filtering.parser

import com.advault.core.common.DomainUtils
import java.net.IDN

/**
 * Validates user-entered custom rules and converts them to the stored form:
 * "example.com" (domain + subdomains) or "*.example.com" (subdomains only).
 *
 * Accepted input: "example.com", "*.example.com", "||example.com^", "@@||example.com^",
 * "0.0.0.0 example.com", URLs such as "https://ads.example.com:443/path?q" (host is extracted),
 * and internationalised names (converted to punycode). IP addresses, single labels, wildcard
 * TLDs ("*.com") and anything with invalid characters are rejected.
 */
object CustomRuleValidator {

    sealed interface Outcome {
        data class Valid(val rule: String) : Outcome
        data class Invalid(val reason: String) : Outcome
    }

    fun validate(input: String): Outcome {
        var s = input.trim()
        if (s.isEmpty()) return Outcome.Invalid("Enter a domain")
        if (s.length > 2048) return Outcome.Invalid("Input is too long")

        // Adblock syntax.
        if (s.startsWith("@@")) s = s.substring(2)
        if (s.startsWith("||")) {
            s = s.substring(2)
            val caret = s.indexOf('^')
            if (caret >= 0) {
                val tail = s.substring(caret + 1).removePrefix("|")
                if (tail.isNotEmpty()) return Outcome.Invalid("Rule options are not supported")
                s = s.substring(0, caret)
            }
        }
        // Hosts syntax: "0.0.0.0 example.com".
        val parts = s.split(' ', '\t').filter { it.isNotEmpty() }
        if (parts.size == 2 && (parts[0] == "0.0.0.0" || parts[0] == "127.0.0.1" || parts[0] == "::" || parts[0] == "::1")) {
            s = parts[1]
        } else if (parts.size != 1) {
            return Outcome.Invalid("Enter a single domain")
        }

        // URL -> host.
        val scheme = s.indexOf("://")
        if (scheme >= 0) {
            val proto = s.substring(0, scheme)
            if (proto.isEmpty() || !proto.all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' }) {
                return Outcome.Invalid("Not a valid URL")
            }
            s = s.substring(scheme + 3)
        }
        s = s.substringBefore('/').substringBefore('?').substringBefore('#')
        if (s.contains('@')) s = s.substringAfterLast('@')
        if (s.startsWith("[") || s.count { it == ':' } > 1) return Outcome.Invalid("IP addresses are not supported, enter a domain")
        s = s.substringBefore(':')

        var wildcard = false
        if (s.startsWith("*.")) {
            wildcard = true
            s = s.substring(2)
        } else if (s.startsWith(".")) {
            s = s.substring(1)
        }
        if (s.contains('*')) return Outcome.Invalid("Wildcards are only supported as a leading \"*.\"")

        if (s.any { it.code > 0x7f }) {
            s = try {
                IDN.toASCII(s, IDN.ALLOW_UNASSIGNED)
            } catch (e: IllegalArgumentException) {
                return Outcome.Invalid("Not a valid internationalised domain")
            }
        }
        if (isIpv4(s)) return Outcome.Invalid("IP addresses are not supported, enter a domain")
        val d = DomainUtils.normalize(s) ?: return Outcome.Invalid(
            if (!s.contains('.')) "Enter a full domain such as example.com" else "Not a valid domain"
        )
        return Outcome.Valid(if (wildcard) "*.$d" else d)
    }

    private fun isIpv4(s: String): Boolean {
        val p = s.trimEnd('.').split('.')
        return p.size == 4 && p.all { it.isNotEmpty() && it.length <= 3 && it.all(Char::isDigit) }
    }
}

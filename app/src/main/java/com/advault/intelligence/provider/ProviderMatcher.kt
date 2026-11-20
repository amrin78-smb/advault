package com.advault.intelligence.provider

import com.advault.data.models.AdProvider
import com.advault.data.models.ProviderMatch

/**
 * Immutable domain → provider index. Safe to share between threads without locking.
 *
 * Pattern semantics (same as custom rules):
 *  - "example.com"   matches example.com and every subdomain.
 *  - "*.example.com" matches subdomains of example.com only.
 * The most specific (longest) matching suffix wins, so "googleads.g.doubleclick.net" beats "doubleclick.net".
 */
class ProviderMatcher private constructor(
    private val suffix: HashMap<String, ProviderMatch>,
    private val subOnly: HashMap<String, ProviderMatch>,
) {
    val size: Int get() = suffix.size + subOnly.size

    /** [domain] must be normalised (lowercase, no trailing dot). Allocation is one substring per label. */
    fun match(domain: String): ProviderMatch? {
        if (domain.isEmpty()) return null
        suffix[domain]?.let { return it }
        var i = domain.indexOf('.')
        while (i >= 0 && i < domain.length - 1) {
            val s = domain.substring(i + 1)
            suffix[s]?.let { return it }
            subOnly[s]?.let { return it }
            i = domain.indexOf('.', i + 1)
        }
        return null
    }

    companion object {
        val EMPTY = ProviderMatcher(HashMap(), HashMap())

        fun build(providers: List<AdProvider>): ProviderMatcher {
            val suffix = HashMap<String, ProviderMatch>()
            val subOnly = HashMap<String, ProviderMatch>()
            for (p in providers) for (d in p.domains) {
                val m = ProviderMatch(p, d)
                if (d.pattern.startsWith("*.")) subOnly.putIfAbsent(d.pattern.substring(2), m)
                else suffix.putIfAbsent(d.pattern, m)
            }
            return ProviderMatcher(suffix, subOnly)
        }
    }
}

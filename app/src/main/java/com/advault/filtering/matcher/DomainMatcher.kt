package com.advault.filtering.matcher

/**
 * Immutable, lock-free domain matcher.
 *
 * Design: rules are stored as 64-bit hashes of the rule's domain in three [DomainTable]s:
 *  - [suffix]      "example.com"   matches example.com and every subdomain (blocklist semantics),
 *  - [subdomains]  "*.example.com" matches subdomains only,
 *  - [exact]       "=example.com"  matches only example.com (used by the built-in allow list).
 *
 * A lookup walks the query once from right to left (see [DomainHash]) and probes each label-aligned
 * suffix that has at least two labels. A query such as "a.b.example.com" costs 3 hash probes per
 * non-empty table, no allocation, no locks. Values are 64-bit sets (one bit per source list).
 *
 * Why hashes instead of a trie or HashMap<String, ...>: 250k entries cost ~8 MB (two LongArrays at
 * load <= 0.6) versus ~35-50 MB for HashMap<String, Long> or a node-per-label trie on ART, build is a
 * single pass, and probes are cache friendly. The trade-off is a theoretical false positive when two
 * distinct names share a 64-bit hash: for 250k rules the probability that a given non-listed query
 * collides is ~250k / 2^64 ≈ 1e-14 per probe, which is negligible (and custom allow rules always win).
 */
class DomainMatcher(
    val suffix: DomainTable,
    val subdomains: DomainTable,
    val exact: DomainTable,
) {
    val size: Int get() = suffix.size + subdomains.size + exact.size
    val isEmpty: Boolean get() = size == 0
    val memoryBytes: Long get() = suffix.memoryBytes + subdomains.memoryBytes + exact.memoryBytes

    /**
     * Invokes [block] for every matching rule from the most specific suffix to the least specific.
     * [block] receives the start index of the matched suffix in [domain], the rule kind and its value,
     * and returns false to stop. Inlined: the lambda never allocates.
     */
    inline fun forEachMatch(domain: String, block: (start: Int, kind: Int, value: Long) -> Boolean) {
        if (size == 0) return
        var end = domain.length
        if (end > 0 && domain[end - 1] == '.') end--
        var h = DomainHash.SEED
        var sawDot = false
        var i = end - 1
        while (i >= 0) {
            val c = domain[i]
            h = DomainHash.step(h, c)
            if (c == '.') sawDot = true
            if (sawDot && (i == 0 || domain[i - 1] == '.')) {
                val key = DomainHash.finish(h)
                val v = suffix.get(key)
                if (v != 0L && !block(i, KIND_SUFFIX, v)) return
                if (i == 0) {
                    val e = exact.get(key)
                    if (e != 0L && !block(i, KIND_EXACT, e)) return
                } else {
                    val s = subdomains.get(key)
                    if (s != 0L && !block(i, KIND_SUBDOMAINS, s)) return
                }
            }
            i--
        }
    }

    /** OR of the values of every rule matching [domain]; 0 when nothing matches. */
    fun unionMask(domain: String): Long {
        var acc = 0L
        forEachMatch(domain) { _, _, v -> acc = acc or v; true }
        return acc
    }

    /** Most specific match, packed as `(start shl 2) or kind`, or -1 if none. */
    fun firstMatch(domain: String): Int {
        var r = -1
        forEachMatch(domain) { start, kind, _ -> r = (start shl 2) or kind; false }
        return r
    }

    /** Most specific match whose value intersects [bits], packed like [firstMatch]. */
    fun firstMatchFor(domain: String, bits: Long): Int {
        var r = -1
        forEachMatch(domain) { start, kind, v ->
            if (v and bits != 0L) { r = (start shl 2) or kind; false } else true
        }
        return r
    }

    class Builder(expected: Int = 16) {
        private val suffix = DomainTable.Builder(expected)
        private val subdomains = DomainTable.Builder(16)
        private val exact = DomainTable.Builder(16)

        /** Adds a rule. [domain] must be normalised (no "*." prefix — pass [kind] instead). */
        fun add(domain: String, kind: Int, bits: Long): Boolean {
            val key = DomainHash.of(domain)
            return when (kind) {
                KIND_SUFFIX -> suffix.put(key, bits)
                KIND_SUBDOMAINS -> subdomains.put(key, bits)
                KIND_EXACT -> exact.put(key, bits)
                else -> throw IllegalArgumentException("kind $kind")
            }
        }

        /** Adds "example.com", "*.example.com" or "=example.com". */
        fun addRule(rule: String, bits: Long): Boolean = when {
            rule.startsWith("*.") -> add(rule.substring(2), KIND_SUBDOMAINS, bits)
            rule.startsWith("=") -> add(rule.substring(1), KIND_EXACT, bits)
            else -> add(rule, KIND_SUFFIX, bits)
        }

        val count: Int get() = suffix.count + subdomains.count + exact.count

        fun build() = DomainMatcher(suffix.build(), subdomains.build(), exact.build())
    }

    companion object {
        const val KIND_SUFFIX = 0
        const val KIND_SUBDOMAINS = 1
        const val KIND_EXACT = 2

        val EMPTY = DomainMatcher(DomainTable.EMPTY, DomainTable.EMPTY, DomainTable.EMPTY)

        /** Human readable rule text for a packed match result. */
        fun ruleText(domain: String, packed: Int): String {
            val start = packed ushr 2
            var end = domain.length
            if (end > 0 && domain[end - 1] == '.') end--
            val base = domain.substring(start, end).lowercase()
            return when (packed and 3) {
                KIND_SUBDOMAINS -> "*.$base"
                else -> base
            }
        }
    }
}

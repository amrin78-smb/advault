package com.advault.filtering.matcher

/**
 * 64-bit hashing of domain suffixes.
 *
 * Characters are consumed from the END of the name towards the start (FNV-1a 64 over the reversed
 * string). This lets a single right-to-left pass over a query yield the hash of every label-aligned
 * suffix ("com", "example.com", "ads.example.com") without allocating substrings.
 *
 * ASCII upper-case is folded to lower-case and a single trailing dot is ignored, so the hot path is
 * robust against non-normalised input at zero cost.
 */
object DomainHash {
    const val SEED: Long = -0x340d631b7bdddcdbL // 0xcbf29ce484222325 (FNV-1a 64 offset basis)
    const val PRIME: Long = 0x100000001b3L

    @Suppress("NOTHING_TO_INLINE")
    inline fun step(h: Long, c: Char): Long {
        val code = if (c in 'A'..'Z') c.code + 32 else c.code
        return (h xor code.toLong()) * PRIME
    }

    /** MurmurHash3 fmix64 finaliser: spreads FNV output over all bits. Never returns 0 (0 = empty slot). */
    fun finish(h: Long): Long {
        var x = h
        x = x xor (x ushr 33)
        x *= -0xae502812aa7333L // 0xff51afd7ed558ccd
        x = x xor (x ushr 33)
        x *= -0x3b314601e57a13adL // 0xc4ceb9fe1a85ec53
        x = x xor (x ushr 33)
        return if (x == 0L) 1L else x
    }

    /** Hash of the whole of [domain] (as stored for a rule). Must equal the suffix hash computed in lookups. */
    fun of(domain: CharSequence): Long {
        var end = domain.length
        if (end > 0 && domain[end - 1] == '.') end--
        var h = SEED
        var i = end - 1
        while (i >= 0) {
            h = step(h, domain[i])
            i--
        }
        return finish(h)
    }
}

package com.advault.vpn.core.dns

/**
 * Bounded, TTL-respecting LRU cache of upstream DNS responses.
 *
 * - Keyed by (lower-cased qname, qtype, qclass).
 * - Expiry = response TTL (min answer TTL, or RFC 2308 negative TTL) clamped to [minTtlSeconds, maxTtlSeconds].
 * - Only NOERROR and NXDOMAIN, non-truncated responses are cached.
 * - Served copies get the client's transaction ID, the client's exact question bytes (preserving 0x20 case),
 *   and TTLs decremented to the remaining lifetime.
 *
 * Thread-safe (single lock; operations are O(1) and tiny).
 */
class DnsCache(
    private val maxEntries: Int = 2048,
    private val minTtlSeconds: Long = 10,
    private val maxTtlSeconds: Long = 3600,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Key(val name: String, val type: Int, val qclass: Int)

    private class Entry(val response: ByteArray, val ttlOffsets: IntArray, val storedAt: Long, val expiresAt: Long)

    private val map = object : LinkedHashMap<Key, Entry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Entry>?): Boolean = size > maxEntries
    }

    private var hits = 0L
    private var misses = 0L

    val size: Int @Synchronized get() = map.size
    val hitRate: Double @Synchronized get() = if (hits + misses == 0L) 0.0 else hits.toDouble() / (hits + misses)

    fun keyFor(q: DnsQuestion) = Key(q.normalizedName, q.type, q.qclass)

    /** Returns a ready-to-send response for [query] or null on miss/expiry. Only single-question queries are cached. */
    fun get(query: DnsQuery): ByteArray? {
        if (query.questions.size != 1) return null
        val key = keyFor(query.questions[0])
        val now = clock()
        val entry: Entry
        synchronized(this) {
            val e = map[key]
            if (e == null || e.expiresAt <= now) {
                if (e != null) map.remove(key)
                misses++
                return null
            }
            hits++
            entry = e
        }
        val out = entry.response.copyOf()
        DnsMessageBuilder.put16(out, 0, query.id)
        // Echo the client's own question bytes (same wire length, case may differ).
        val qLen = query.questionSectionEnd - 12
        if (12 + qLen <= out.size && sameQuestionLength(out, qLen)) {
            System.arraycopy(query.raw, 12, out, 12, qLen)
        }
        val elapsedSec = (now - entry.storedAt) / 1000
        for (o in entry.ttlOffsets) {
            val ttl = DnsParser.u32(out, o)
            DnsMessageBuilder.put32(out, o, (ttl - elapsedSec).coerceAtLeast(1))
        }
        return out
    }

    private fun sameQuestionLength(resp: ByteArray, qLen: Int): Boolean = try {
        if (DnsParser.u16(resp, 4) != 1) false
        else {
            val end = DnsParser.readName(resp, 12).second + 4
            end - 12 == qLen
        }
    } catch (_: Exception) { false }

    /** Caches [response] (an upstream answer to [query]) if cacheable. Returns true if stored. */
    fun put(query: DnsQuery, response: ByteArray): Boolean {
        if (query.questions.size != 1) return false
        val info = DnsParser.parseResponse(response) ?: return false
        if (info.truncated) return false
        if (info.rcode != DnsRcode.NOERROR && info.rcode != DnsRcode.NXDOMAIN) return false
        val ttl = (info.cacheTtlSeconds ?: minTtlSeconds).coerceIn(minTtlSeconds, maxTtlSeconds)
        val now = clock()
        val stored = response.copyOf()
        // Cap stored record TTLs at the cache lifetime; they are decremented by elapsed time on read.
        for (o in info.ttlOffsets) {
            DnsMessageBuilder.put32(stored, o, minOf(DnsParser.u32(stored, o), ttl))
        }
        synchronized(this) { map[keyFor(query.questions[0])] = Entry(stored, info.ttlOffsets, now, now + ttl * 1000) }
        return true
    }

    @Synchronized fun clear() { map.clear() }

    @Synchronized fun resetStats() { hits = 0; misses = 0 }
}

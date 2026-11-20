package com.advault.vpn.core.dns

/** DNS wire-format constants. */
object DnsType {
    const val A = 1
    const val NS = 2
    const val CNAME = 5
    const val SOA = 6
    const val PTR = 12
    const val MX = 15
    const val TXT = 16
    const val AAAA = 28
    const val OPT = 41
    const val SVCB = 64
    const val HTTPS = 65
    const val ANY = 255
}

object DnsRcode {
    const val NOERROR = 0
    const val FORMERR = 1
    const val SERVFAIL = 2
    const val NXDOMAIN = 3
    const val NOTIMP = 4
    const val REFUSED = 5
}

class DnsFormatException(message: String) : Exception(message)

data class DnsQuestion(
    /** Name as received (original case), without trailing dot; "" for the root. */
    val name: String,
    val type: Int,
    val qclass: Int,
) {
    /** Lower-cased name used for filtering and cache keys. */
    val normalizedName: String get() = name.lowercase()
}

/** A parsed DNS query (the parts the tunnel needs). */
class DnsQuery(
    val id: Int,
    val flags: Int,
    val questions: List<DnsQuestion>,
    /** The raw message bytes (exact copy of the UDP payload). */
    val raw: ByteArray,
    /** Offset just past the question section in [raw]. */
    val questionSectionEnd: Int,
) {
    val opcode: Int get() = (flags ushr 11) and 0xF
    val recursionDesired: Boolean get() = flags and 0x0100 != 0
    val isResponse: Boolean get() = flags and 0x8000 != 0
}

/** Metadata extracted by walking every resource record of a response. */
class DnsResponseInfo(
    val id: Int,
    val rcode: Int,
    val truncated: Boolean,
    val answerCount: Int,
    /** Absolute offsets of each record's 32-bit TTL field (answer + authority sections; OPT excluded). */
    val ttlOffsets: IntArray,
    /** TTL to use for caching: min answer TTL, or SOA-derived negative TTL; null if not derivable. */
    val cacheTtlSeconds: Long?,
    val questions: List<DnsQuestion>,
)

object DnsParser {
    private const val MAX_POINTER_JUMPS = 32
    private const val MAX_NAME_LENGTH = 255

    fun u16(b: ByteArray, o: Int): Int = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
    fun u32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or
            ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)

    /** Parses a query. Never throws: returns null for anything malformed. */
    fun parseQuery(msg: ByteArray, maxQuestions: Int = 16): DnsQuery? = try {
        parseQueryOrThrow(msg, maxQuestions)
    } catch (_: DnsFormatException) {
        null
    } catch (_: IndexOutOfBoundsException) {
        null
    }

    fun parseQueryOrThrow(msg: ByteArray, maxQuestions: Int = 16): DnsQuery {
        if (msg.size < 12) throw DnsFormatException("message shorter than header")
        val id = u16(msg, 0)
        val flags = u16(msg, 2)
        val qd = u16(msg, 4)
        if (qd > maxQuestions) throw DnsFormatException("too many questions: $qd")
        var off = 12
        val questions = ArrayList<DnsQuestion>(qd)
        repeat(qd) {
            val (name, next) = readName(msg, off)
            if (next + 4 > msg.size) throw DnsFormatException("question truncated")
            questions += DnsQuestion(name, u16(msg, next), u16(msg, next + 2))
            off = next + 4
        }
        return DnsQuery(id, flags, questions, msg, off)
    }

    /**
     * Reads a (possibly compressed) domain name at [start]. Returns the dotted name (no trailing dot) and
     * the offset immediately after the name in the original position. Loops and overlong names are rejected.
     */
    fun readName(msg: ByteArray, start: Int): Pair<String, Int> {
        val sb = StringBuilder()
        var off = start
        var end = -1
        var jumps = 0
        var wireLength = 0
        while (true) {
            if (off >= msg.size) throw DnsFormatException("name runs past end")
            val len = msg[off].toInt() and 0xFF
            when (len and 0xC0) {
                0x00 -> {
                    if (len == 0) {
                        if (end < 0) end = off + 1
                        return sb.toString() to end
                    }
                    if (off + 1 + len > msg.size) throw DnsFormatException("label runs past end")
                    wireLength += len + 1
                    if (wireLength > MAX_NAME_LENGTH) throw DnsFormatException("name too long")
                    if (sb.isNotEmpty()) sb.append('.')
                    for (i in off + 1..off + len) {
                        val c = msg[i].toInt() and 0xFF
                        // Keep printable ASCII; escape anything else so it can never match a rule by accident.
                        if (c in 0x21..0x7E && c != '.'.code && c != '\\'.code) sb.append(c.toChar())
                        else sb.append('\\').append(c.toString().padStart(3, '0'))
                    }
                    off += 1 + len
                }
                0xC0 -> {
                    if (off + 1 >= msg.size) throw DnsFormatException("pointer runs past end")
                    if (++jumps > MAX_POINTER_JUMPS) throw DnsFormatException("compression loop")
                    val ptr = ((len and 0x3F) shl 8) or (msg[off + 1].toInt() and 0xFF)
                    if (end < 0) end = off + 2
                    if (ptr >= msg.size) throw DnsFormatException("pointer out of range")
                    off = ptr
                }
                else -> throw DnsFormatException("unsupported label type")
            }
        }
    }

    /** Skips a name without decoding it. */
    private fun skipName(msg: ByteArray, start: Int): Int {
        var off = start
        var guard = 0
        while (guard++ < 128) {
            if (off >= msg.size) throw DnsFormatException("name runs past end")
            val len = msg[off].toInt() and 0xFF
            when (len and 0xC0) {
                0x00 -> { if (len == 0) return off + 1; off += 1 + len }
                0xC0 -> { if (off + 1 >= msg.size) throw DnsFormatException("pointer truncated"); return off + 2 }
                else -> throw DnsFormatException("unsupported label type")
            }
        }
        throw DnsFormatException("name too long")
    }

    /** Walks a full response. Returns null if malformed. */
    fun parseResponse(msg: ByteArray): DnsResponseInfo? = try {
        parseResponseOrThrow(msg)
    } catch (_: DnsFormatException) {
        null
    } catch (_: IndexOutOfBoundsException) {
        null
    }

    fun parseResponseOrThrow(msg: ByteArray): DnsResponseInfo {
        if (msg.size < 12) throw DnsFormatException("message shorter than header")
        val flags = u16(msg, 2)
        val qd = u16(msg, 4)
        val an = u16(msg, 6)
        val ns = u16(msg, 8)
        val ar = u16(msg, 10)
        var off = 12
        val questions = ArrayList<DnsQuestion>(qd)
        repeat(qd) {
            val (name, next) = readName(msg, off)
            if (next + 4 > msg.size) throw DnsFormatException("question truncated")
            questions += DnsQuestion(name, u16(msg, next), u16(msg, next + 2))
            off = next + 4
        }
        val ttlOffsets = ArrayList<Int>()
        var minAnswerTtl = Long.MAX_VALUE
        var negativeTtl: Long? = null
        for (section in 0 until 3) {
            val count = when (section) { 0 -> an; 1 -> ns; else -> ar }
            repeat(count) {
                off = skipName(msg, off)
                if (off + 10 > msg.size) throw DnsFormatException("record header truncated")
                val type = u16(msg, off)
                val ttl = u32(msg, off + 4)
                val rdLen = u16(msg, off + 8)
                val rdStart = off + 10
                if (rdStart + rdLen > msg.size) throw DnsFormatException("rdata truncated")
                if (type != DnsType.OPT && section < 2) {
                    ttlOffsets += off + 4
                    if (section == 0) minAnswerTtl = minOf(minAnswerTtl, ttl)
                    if (section == 1 && type == DnsType.SOA) {
                        // RFC 2308: negative TTL = min(SOA TTL, SOA MINIMUM). MINIMUM is the last 4 rdata bytes.
                        if (rdLen >= 22) negativeTtl = minOf(ttl, u32(msg, rdStart + rdLen - 4))
                    }
                }
                off = rdStart + rdLen
            }
        }
        val cacheTtl = when {
            an > 0 && minAnswerTtl != Long.MAX_VALUE -> minAnswerTtl
            else -> negativeTtl
        }
        return DnsResponseInfo(
            id = u16(msg, 0),
            rcode = flags and 0xF,
            truncated = flags and 0x0200 != 0,
            answerCount = an,
            ttlOffsets = ttlOffsets.toIntArray(),
            cacheTtlSeconds = cacheTtl,
            questions = questions,
        )
    }
}

/** Builders for synthesized responses. All responses echo the query's question section verbatim. */
object DnsMessageBuilder {

    /** Header flags for a response to [query]: QR=1, same opcode, RD copied, RA=1. */
    private fun responseFlags(query: DnsQuery, rcode: Int): Int =
        0x8000 or (query.flags and 0x7800) or (query.flags and 0x0100) or 0x0080 or (rcode and 0xF)

    /** A response with no records and the given rcode (SERVFAIL, NXDOMAIN, REFUSED, FORMERR, NOTIMP, NOERROR/NODATA). */
    fun emptyResponse(query: DnsQuery, rcode: Int): ByteArray {
        val qLen = query.questionSectionEnd - 12
        val out = ByteArray(12 + qLen)
        writeHeader(out, query.id, responseFlags(query, rcode), query.questions.size, 0)
        System.arraycopy(query.raw, 12, out, 12, qLen)
        return out
    }

    /**
     * Minimal error response when only the 12-byte header could be read (question section unusable).
     * Returns null if even the header is missing.
     */
    fun headerOnlyError(raw: ByteArray, rcode: Int): ByteArray? {
        if (raw.size < 12) return null
        val flags = DnsParser.u16(raw, 2)
        val out = ByteArray(12)
        writeHeader(out, DnsParser.u16(raw, 0), 0x8000 or (flags and 0x7800) or (flags and 0x0100) or 0x0080 or rcode, 0, 0)
        return out
    }

    /**
     * Null-IP block response: A -> 0.0.0.0, AAAA -> ::, everything else NOERROR with no answers
     * (NODATA), which makes HTTPS/SVCB lookups fall back to A/AAAA which are then also nulled.
     */
    fun nullIpResponse(query: DnsQuery, ttlSeconds: Int): ByteArray {
        // Locate each question's start offset inside the raw message so answers can point at it.
        val qOffsets = IntArray(query.questions.size)
        var off = 12
        for (i in query.questions.indices) {
            qOffsets[i] = off
            off = DnsParser.readName(query.raw, off).second + 4
        }
        val answers = ArrayList<ByteArray>()
        query.questions.forEachIndexed { i, q ->
            if (q.qclass != 1 && q.qclass != 255) return@forEachIndexed
            val rdata = when (q.type) {
                DnsType.A -> ByteArray(4)
                DnsType.AAAA -> ByteArray(16)
                else -> null
            } ?: return@forEachIndexed
            // Compression pointers can only address the first 16 KiB; questions always sit well within it.
            val ptr = qOffsets[i]
            val rr = ByteArray(12 + rdata.size)
            rr[0] = (0xC0 or (ptr ushr 8)).toByte(); rr[1] = ptr.toByte()
            put16(rr, 2, q.type); put16(rr, 4, 1)
            put32(rr, 6, ttlSeconds.toLong())
            put16(rr, 10, rdata.size)
            System.arraycopy(rdata, 0, rr, 12, rdata.size)
            answers += rr
        }
        val qLen = query.questionSectionEnd - 12
        val out = ByteArray(12 + qLen + answers.sumOf { it.size })
        writeHeader(out, query.id, responseFlags(query, DnsRcode.NOERROR), query.questions.size, answers.size)
        System.arraycopy(query.raw, 12, out, 12, qLen)
        var w = 12 + qLen
        for (a in answers) { System.arraycopy(a, 0, out, w, a.size); w += a.size }
        return out
    }

    /** Copy of [message] with its transaction ID replaced. */
    fun withId(message: ByteArray, id: Int): ByteArray {
        val c = message.copyOf()
        put16(c, 0, id)
        return c
    }

    private fun writeHeader(out: ByteArray, id: Int, flags: Int, qd: Int, an: Int) {
        put16(out, 0, id); put16(out, 2, flags); put16(out, 4, qd); put16(out, 6, an); put16(out, 8, 0); put16(out, 10, 0)
    }

    internal fun put16(b: ByteArray, o: Int, v: Int) { b[o] = (v ushr 8).toByte(); b[o + 1] = v.toByte() }
    internal fun put32(b: ByteArray, o: Int, v: Long) {
        b[o] = (v ushr 24).toByte(); b[o + 1] = (v ushr 16).toByte(); b[o + 2] = (v ushr 8).toByte(); b[o + 3] = v.toByte()
    }
}

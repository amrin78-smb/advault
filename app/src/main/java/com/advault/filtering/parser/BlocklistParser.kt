package com.advault.filtering.parser

import com.advault.core.common.BlocklistFormat
import com.advault.core.common.DomainUtils
import com.advault.filtering.matcher.DomainHash
import com.advault.filtering.matcher.LongHashSet
import java.io.BufferedInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.util.zip.GZIPInputStream

/** Hard limits that protect against hostile or broken input. */
data class ParseLimits(
    /** Maximum decompressed bytes read from a source. */
    val maxBytes: Long = 40L * 1024 * 1024,
    /** Maximum number of valid, unique rules. */
    val maxRules: Int = 2_000_000,
    /** Lines longer than this are counted invalid and skipped without being buffered. */
    val maxLineLength: Int = 4096,
)

class BlocklistParseException(message: String) : IOException(message)

data class ParseStats(
    val validRules: Int,
    val invalidRules: Int,
    val duplicateRules: Int,
    /** Syntactically recognised rules AdVault cannot apply at DNS level (cosmetic, path, regex, exceptions, $options). */
    val unsupportedRules: Int,
    /** localhost / broadcasthost / 0.0.0.0 style entries found in hosts files. */
    val skippedRules: Int,
    val detectedFormat: BlocklistFormat,
)

/**
 * Parser for HOSTS, DOMAINS and ADBLOCK (domain-anchored network rules only) blocklists.
 *
 * Every line is classified independently, so mixed files parse correctly regardless of the declared
 * format. Output rules are normalised domains, optionally prefixed with "*." (subdomains only).
 *
 * Supported:
 *  - hosts:    "0.0.0.0 a.com b.com", "127.0.0.1 a.com", ":: a.com" (any IP in the first column)
 *  - domains:  "a.com", "*.a.com" (subdomains only), inline "# comment"
 *  - adblock:  "||a.com^", "||a.com^|", "||a.com^$important" (options limited to important/all/document)
 *
 * Ignored (counted as unsupported, never applied): cosmetic rules (##, #@#, #?#, #$#), exception
 * rules ("@@||a.com^" — upstream exceptions are not imported; use custom allow rules instead),
 * rules with path/regex/wildcard patterns, and rules with scope-restricting options such as
 * $third-party or $domain= which cannot be represented at DNS level.
 *
 * Content is only ever read as text and validated with [DomainUtils]; nothing is executed.
 */
object BlocklistParser {

    private val SKIP_HOSTS = setOf(
        "localhost", "localhost.localdomain", "local", "broadcasthost", "0.0.0.0",
        "ip6-localhost", "ip6-loopback", "ip6-localnet", "ip6-mcastprefix", "ip6-allnodes",
        "ip6-allrouters", "ip6-allhosts", "localdomain",
    )
    private val ALLOWED_ADBLOCK_OPTIONS = setOf("important", "all", "document", "doc")

    sealed interface LineResult {
        data class Rule(val domain: String, val subdomainsOnly: Boolean) : LineResult
        data class Rules(val rules: List<Rule>, val invalid: Int, val skipped: Int) : LineResult
        data object Blank : LineResult
        data object Unsupported : LineResult
        data object Skipped : LineResult
        data object Invalid : LineResult
        data object Markup : LineResult
    }

    private const val STYLE_HOSTS = 0
    private const val STYLE_ADBLOCK = 1
    private const val STYLE_DOMAIN = 2

    /** Classifies a single line. Exposed for tests. */
    fun parseLine(raw: String): LineResult = parseLineInternal(raw, null)

    private fun parseLineInternal(raw: String, style: IntArray?): LineResult {
        var line = raw.trim()
        if (line.isEmpty()) return LineResult.Blank
        val first = line[0]
        if (first == '!' || first == '[') return LineResult.Blank // adblock comment / header
        if (first == '#') {
            return if (line.startsWith("##") || line.startsWith("#@#") || line.startsWith("#?#")) LineResult.Unsupported
            else LineResult.Blank
        }
        if (first == '<') return LineResult.Markup
        if (line.contains("##") || line.contains("#@#") || line.contains("#?#") || line.contains("#$#")) {
            return LineResult.Unsupported // cosmetic
        }
        if (line.startsWith("@@")) return LineResult.Unsupported // exception rule
        if (line.startsWith("||")) {
            style?.let { it[STYLE_ADBLOCK]++ }
            return parseAdblock(line)
        }
        if (first == '|' || first == '/') return LineResult.Unsupported // URL anchor / regex

        // Strip trailing comments for hosts / domains syntax.
        val hash = line.indexOf('#')
        if (hash >= 0) line = line.substring(0, hash).trim()
        if (line.isEmpty()) return LineResult.Blank
        if (line.indexOf('$') >= 0 || line.indexOf('^') >= 0 || line.indexOf('/') >= 0) return LineResult.Unsupported

        val tokens = line.split(' ', '\t').filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return LineResult.Blank
        return if (tokens.size >= 2 && looksLikeIp(tokens[0])) {
            style?.let { it[STYLE_HOSTS]++ }
            val rules = ArrayList<LineResult.Rule>(tokens.size - 1)
            var invalid = 0
            var skipped = 0
            for (t in 1 until tokens.size) {
                when (val r = hostToken(tokens[t], allowWildcard = false)) {
                    is LineResult.Rule -> rules += r
                    LineResult.Skipped -> skipped++
                    else -> invalid++
                }
            }
            if (rules.size == 1 && invalid == 0 && skipped == 0) rules[0]
            else if (rules.isEmpty() && invalid == 0) LineResult.Skipped
            else LineResult.Rules(rules, invalid, skipped)
        } else if (tokens.size == 1) {
            style?.let { it[STYLE_DOMAIN]++ }
            if (looksLikeIp(tokens[0])) LineResult.Invalid else hostToken(tokens[0], allowWildcard = true)
        } else {
            LineResult.Invalid
        }
    }

    private fun parseAdblock(line: String): LineResult {
        var i = 2
        while (i < line.length) {
            val c = line[i]
            if (c == '^' || c == '$' || c == '/' || c == '|' || c == '*' || c == ':' || c == '?') break
            i++
        }
        if (i >= line.length || line[i] != '^') return LineResult.Unsupported // path / wildcard / unterminated
        val host = line.substring(2, i)
        var rest = line.substring(i + 1)
        if (rest.startsWith("|")) rest = rest.substring(1)
        if (rest.isNotEmpty()) {
            if (!rest.startsWith("$")) return LineResult.Unsupported
            val opts = rest.substring(1).split(',').map { it.trim().lowercase() }
            if (opts.any { it !in ALLOWED_ADBLOCK_OPTIONS }) return LineResult.Unsupported
        }
        return hostToken(host, allowWildcard = false)
    }

    private fun hostToken(token: String, allowWildcard: Boolean): LineResult {
        var t = token.lowercase()
        if (t in SKIP_HOSTS || t.startsWith("ip6-")) return LineResult.Skipped
        var sub = false
        if (t.startsWith("*.")) {
            if (!allowWildcard) return LineResult.Invalid
            sub = true
            t = t.substring(2)
        }
        val d = DomainUtils.normalize(t) ?: return LineResult.Invalid
        if (d in SKIP_HOSTS) return LineResult.Skipped
        return LineResult.Rule(d, sub)
    }

    private fun looksLikeIp(s: String): Boolean {
        if (s.indexOf(':') >= 0) return s.all { it.isLetterOrDigit() || it == ':' || it == '.' || it == '%' }
        var dots = 0
        for (c in s) {
            if (c == '.') dots++ else if (!c.isDigit()) return false
        }
        return dots == 3
    }

    /**
     * Streams [input] (plain text or gzip, auto-detected), emitting every valid unique rule to [sink].
     * Throws [BlocklistParseException] for oversized, markup (HTML) or rule-less content.
     */
    fun parse(
        input: InputStream,
        limits: ParseLimits = ParseLimits(),
        sink: (domain: String, subdomainsOnly: Boolean) -> Unit,
    ): ParseStats {
        val buffered = BufferedInputStream(input, 64 * 1024)
        buffered.mark(2)
        val b1 = buffered.read()
        val b2 = buffered.read()
        buffered.reset()
        val decoded: InputStream = if (b1 == 0x1f && b2 == 0x8b) GZIPInputStream(buffered, 64 * 1024) else buffered
        val limited = LimitedInputStream(decoded, limits.maxBytes)
        val reader = BoundedLineReader(InputStreamReader(limited, Charsets.UTF_8), limits.maxLineLength)

        val seen = LongHashSet(4096)
        val style = IntArray(3)
        var valid = 0
        var invalid = 0
        var duplicates = 0
        var unsupported = 0
        var skipped = 0
        var meaningfulLines = 0

        fun emit(rule: LineResult.Rule) {
            val key = DomainHash.of(rule.domain).let { if (rule.subdomainsOnly) DomainHash.finish(it xor 0x2a2eL) else it }
            if (!seen.add(key)) { duplicates++; return }
            if (valid >= limits.maxRules) throw BlocklistParseException("List exceeds ${limits.maxRules} rules")
            valid++
            sink(rule.domain, rule.subdomainsOnly)
        }

        while (true) {
            val line = reader.readLine() ?: break
            if (reader.lastLineTooLong) { invalid++; continue }
            when (val r = parseLineInternal(line, style)) {
                LineResult.Blank -> Unit
                LineResult.Markup -> {
                    if (meaningfulLines == 0) throw BlocklistParseException("Content is HTML/XML, not a blocklist")
                    invalid++
                }
                LineResult.Unsupported -> { unsupported++; meaningfulLines++ }
                LineResult.Skipped -> { skipped++; meaningfulLines++ }
                LineResult.Invalid -> { invalid++; meaningfulLines++ }
                is LineResult.Rule -> { emit(r); meaningfulLines++ }
                is LineResult.Rules -> {
                    r.rules.forEach(::emit); invalid += r.invalid; skipped += r.skipped; meaningfulLines++
                }
            }
        }
        if (valid == 0) throw BlocklistParseException("No valid rules found ($invalid invalid, $unsupported unsupported)")
        val format = when {
            style[STYLE_ADBLOCK] >= style[STYLE_HOSTS] && style[STYLE_ADBLOCK] >= style[STYLE_DOMAIN] -> BlocklistFormat.ADBLOCK
            style[STYLE_HOSTS] >= style[STYLE_DOMAIN] -> BlocklistFormat.HOSTS
            else -> BlocklistFormat.DOMAINS
        }
        return ParseStats(valid, invalid, duplicates, unsupported, skipped, format)
    }

    /** Convenience for tests and small inputs. */
    fun parseToList(text: String, limits: ParseLimits = ParseLimits()): Pair<List<String>, ParseStats> {
        val out = ArrayList<String>()
        val stats = parse(text.byteInputStream(), limits) { d, sub -> out += if (sub) "*.$d" else d }
        return out to stats
    }
}

/** Throws once more than [max] bytes have been read. */
internal class LimitedInputStream(input: InputStream, private val max: Long) : FilterInputStream(input) {
    private var count = 0L

    override fun read(): Int {
        val r = super.read()
        if (r >= 0) add(1)
        return r
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val r = super.read(b, off, len)
        if (r > 0) add(r.toLong())
        return r
    }

    private fun add(n: Long) {
        count += n
        if (count > max) throw BlocklistParseException("List exceeds size limit of ${max / (1024 * 1024)} MB")
    }
}

/** Line reader that never buffers more than [maxLen] chars of a line. */
internal class BoundedLineReader(private val reader: Reader, private val maxLen: Int) {
    private val buf = CharArray(16 * 1024)
    private var pos = 0
    private var lim = 0
    private var eof = false
    private val sb = StringBuilder(256)
    var lastLineTooLong = false
        private set

    fun readLine(): String? {
        sb.setLength(0)
        lastLineTooLong = false
        var any = false
        while (true) {
            if (pos >= lim) {
                if (eof) return if (any) finish() else null
                val n = reader.read(buf)
                if (n <= 0) { eof = true; continue }
                pos = 0; lim = n
            }
            val c = buf[pos++]
            any = true
            if (c == '\n') return finish()
            if (c == '\r') continue
            if (sb.length < maxLen) sb.append(c) else lastLineTooLong = true
        }
    }

    private fun finish(): String = if (lastLineTooLong) "" else sb.toString()
}

package com.advault.filtering.engine

import com.advault.core.common.DomainUtils
import com.advault.core.common.FilterCategory
import com.advault.core.common.RuleType
import com.advault.core.database.CustomRuleEntity
import com.advault.data.models.AdProvider
import com.advault.filtering.matcher.DomainMatcher
import com.advault.filtering.parser.BlocklistParser
import java.io.BufferedReader
import java.io.InputStream

/**
 * Builds a [FilterSnapshot]. Not thread-safe; used by one reload at a time.
 *
 * Each (source id, category) pair gets one bit of the 64-bit value. If more than 64 sources are
 * added, additional sources share the bit of the first source with the same category (matching is
 * unaffected; only [com.advault.data.models.FilterVerdict.listId] attribution becomes approximate).
 */
class FilterSnapshotBuilder(expectedDomains: Int = 1024) {
    private val lists = DomainMatcher.Builder(expectedDomains)
    private val sources = ArrayList<FilterSource>()
    private val builtinAllow = DomainMatcher.Builder()
    private val customAllow = DomainMatcher.Builder()
    private val customBlock = DomainMatcher.Builder()

    /** Returns the bit for [id]/[category], allocating one if needed. */
    fun source(id: String, category: FilterCategory): Long {
        val existing = sources.indexOfFirst { it.id == id && it.category == category }
        if (existing >= 0) return 1L shl existing
        if (sources.size < 64) {
            sources += FilterSource(id, category)
            return 1L shl (sources.size - 1)
        }
        val shared = sources.indexOfFirst { it.category == category }
        return if (shared >= 0) 1L shl shared else 1L shl 63
    }

    /** Adds a compiled rule ("example.com" or "*.example.com"). The rule is assumed already validated. */
    fun addListRule(rule: String, bits: Long) {
        lists.addRule(rule, bits)
    }

    /**
     * Reads a compiled list file (one rule per line, written by the repository). Lines are cheaply
     * sanity checked; anything unexpected is skipped. Returns the number of rules read.
     */
    fun addCompiledList(id: String, category: FilterCategory, input: InputStream): Int {
        val bits = source(id, category)
        var n = 0
        BufferedReader(input.reader(Charsets.UTF_8), 64 * 1024).useLines { seq ->
            for (line in seq) {
                if (line.isEmpty() || line.length > 255 || line[0] == '#') continue
                lists.addRule(line, bits)
                n++
            }
        }
        return n
    }

    /** Adds "associated filtering rules" from the provider intelligence database. */
    fun addProviders(providers: List<AdProvider>, sourceId: String): Int {
        var n = 0
        for (p in providers) for (d in p.domains) {
            // Shared-infrastructure domains (e.g. graph.facebook.com = Facebook Login) are classification-only.
            if (!d.blockable) continue
            val rule = normalisePattern(d.pattern) ?: continue
            lists.addRule(rule, source(sourceId, d.category))
            n++
        }
        return n
    }

    /** Parses built-in allow exceptions ("example.com", "*.example.com", "=example.com", # comments). */
    fun addBuiltinAllow(text: String): Int {
        var n = 0
        text.lineSequence().forEach { raw ->
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) return@forEach
            val exact = line.startsWith("=")
            val body = if (exact) line.substring(1) else line
            val rule = normalisePattern(body) ?: return@forEach
            if (exact && rule.startsWith("*.")) return@forEach
            builtinAllow.addRule(if (exact) "=$rule" else rule, 1L)
            n++
        }
        return n
    }

    fun addCustomRules(rules: List<CustomRuleEntity>) {
        for (r in rules) {
            val rule = normalisePattern(r.domain) ?: continue
            when (r.type) {
                RuleType.ALLOW -> customAllow.addRule(rule, 1L)
                RuleType.BLOCK -> customBlock.addRule(rule, 1L)
            }
        }
    }

    fun build(): FilterSnapshot {
        val matcher = lists.build()
        val perCategory = IntArray(FilterCategory.entries.size)
        val catBits = LongArray(perCategory.size)
        sources.forEachIndexed { bit, s -> catBits[s.category.ordinal] = catBits[s.category.ordinal] or (1L shl bit) }
        val count: (Long) -> Unit = { v ->
            for (c in perCategory.indices) if (v and catBits[c] != 0L) perCategory[c]++
        }
        matcher.suffix.forEachValue(count)
        matcher.subdomains.forEachValue(count)
        matcher.exact.forEachValue(count)
        val map = FilterCategory.entries.filter { perCategory[it.ordinal] > 0 }.associateWith { perCategory[it.ordinal] }
        return FilterSnapshot(matcher, sources.toList(), builtinAllow.build(), customAllow.build(), customBlock.build(), map)
    }

    companion object {
        /** "Example.COM." -> "example.com", "*.x.com" -> "*.x.com"; null if invalid. */
        fun normalisePattern(pattern: String): String? {
            val p = pattern.trim()
            return if (p.startsWith("*.")) DomainUtils.normalize(p.substring(2))?.let { "*.$it" }
            else when (val r = BlocklistParser.parseLine(p)) {
                is BlocklistParser.LineResult.Rule -> if (r.subdomainsOnly) "*.${r.domain}" else r.domain
                else -> null
            }
        }
    }
}

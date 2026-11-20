package com.advault.filtering.engine

import com.advault.core.common.DecisionReason
import com.advault.core.common.FilterCategory
import com.advault.core.common.FilterMode
import com.advault.filtering.matcher.DomainMatcher
import com.advault.data.models.FilterVerdict

/** A rule source occupying one bit of the list matcher's value. */
data class FilterSource(val id: String, val category: FilterCategory)

/**
 * Immutable compiled filter state. Built off the hot path by [FilterSnapshotBuilder] and published
 * atomically by the engine; [evaluate] is lock-free and allocates only for non-trivial verdicts.
 *
 * Precedence:
 *  1. custom ALLOW              -> ALLOWED  / CUSTOM_ALLOW
 *  2. custom BLOCK              -> BLOCKED  / CUSTOM_BLOCK
 *  3. no list/provider match    -> ALLOWED  / NO_MATCH
 *  4. built-in allow exception  -> ALLOWED  / NO_MATCH (listId = [BUILTIN_ALLOW_ID], matchedRule = exception)
 *  5. a matched category enabled in settings AND included by mode -> BLOCKED / BLOCKLIST
 *  6. matched category enabled but excluded by mode -> ALLOWED / MODE_EXCLUDES_CATEGORY
 *  7. otherwise (all matched categories disabled)   -> ALLOWED / CATEGORY_DISABLED
 */
class FilterSnapshot(
    val lists: DomainMatcher,
    val sources: List<FilterSource>,
    val builtinAllow: DomainMatcher,
    val customAllow: DomainMatcher,
    val customBlock: DomainMatcher,
    val domainsPerCategory: Map<FilterCategory, Int>,
) {
    /** Category index -> bit set of sources in that category. */
    private val categorySources = LongArray(CATEGORY_COUNT).also { arr ->
        sources.forEachIndexed { bit, s -> arr[s.category.ordinal] = arr[s.category.ordinal] or (1L shl bit) }
    }
    private val sourceIds: Array<String> = sources.map { it.id }.toTypedArray()

    val totalDomains: Int get() = lists.size

    fun evaluate(domain: String, mode: FilterMode, enabledCategoryMask: Int): FilterVerdict {
        if (!customAllow.isEmpty) {
            val m = customAllow.firstMatch(domain)
            if (m >= 0) return FilterVerdict(false, DecisionReason.CUSTOM_ALLOW, matchedRule = DomainMatcher.ruleText(domain, m))
        }
        if (!customBlock.isEmpty) {
            val m = customBlock.firstMatch(domain)
            if (m >= 0) return FilterVerdict(true, DecisionReason.CUSTOM_BLOCK, matchedRule = DomainMatcher.ruleText(domain, m))
        }
        val mask = lists.unionMask(domain)
        if (mask == 0L) return NO_MATCH
        if (!builtinAllow.isEmpty) {
            val a = builtinAllow.firstMatch(domain)
            if (a >= 0) {
                return FilterVerdict(false, DecisionReason.NO_MATCH, matchedRule = DomainMatcher.ruleText(domain, a), listId = BUILTIN_ALLOW_ID)
            }
        }

        val modeMask = MODE_MASKS[mode.ordinal]
        var firstMatched = -1
        var firstEnabled = -1
        for (c in PRIORITY) {
            if (mask and categorySources[c] == 0L) continue
            val bit = 1 shl c
            if (enabledCategoryMask and bit != 0) {
                if (modeMask and bit != 0) return verdict(domain, mask, c, true, DecisionReason.BLOCKLIST)
                if (firstEnabled < 0) firstEnabled = c
            }
            if (firstMatched < 0) firstMatched = c
        }
        return if (firstEnabled >= 0) verdict(domain, mask, firstEnabled, false, DecisionReason.MODE_EXCLUDES_CATEGORY)
        else verdict(domain, mask, firstMatched, false, DecisionReason.CATEGORY_DISABLED)
    }

    private fun verdict(domain: String, mask: Long, category: Int, blocked: Boolean, reason: DecisionReason): FilterVerdict {
        val bits = mask and categorySources[category]
        val packed = lists.firstMatchFor(domain, bits)
        var rule: String? = null
        var listId: String? = null
        if (packed >= 0) {
            rule = DomainMatcher.ruleText(domain, packed)
            // Attribute to the lowest-numbered source in this category whose rule matched at that level.
            var b = 0L
            lists.forEachMatch(domain) { start, kind, v ->
                if ((start shl 2) or kind == packed) { b = v and bits; false } else true
            }
            if (b == 0L) b = bits
            listId = sourceIds[java.lang.Long.numberOfTrailingZeros(b)]
        }
        return FilterVerdict(blocked, reason, CATEGORIES[category], rule, listId)
    }

    companion object {
        const val BUILTIN_ALLOW_ID = "builtin_allow"
        private val CATEGORIES = FilterCategory.entries.toTypedArray()
        private val CATEGORY_COUNT = CATEGORIES.size

        /** Order used to attribute a domain present in several categories. */
        private val PRIORITY = intArrayOf(
            FilterCategory.MALICIOUS.ordinal,
            FilterCategory.GAME_ADS.ordinal,
            FilterCategory.ADVERTISING.ordinal,
            FilterCategory.TRACKING.ordinal,
            FilterCategory.ANALYTICS.ordinal,
            FilterCategory.TELEMETRY.ordinal,
        ).also { check(it.size == CATEGORY_COUNT) }

        private val MODE_MASKS = IntArray(FilterMode.entries.size) { m ->
            categoryMask(FilterCategory.entries.filter { FilterMode.entries[m].includes(it) })
        }

        val NO_MATCH = FilterVerdict(false, DecisionReason.NO_MATCH)

        val EMPTY = FilterSnapshot(DomainMatcher.EMPTY, emptyList(), DomainMatcher.EMPTY, DomainMatcher.EMPTY, DomainMatcher.EMPTY, emptyMap())

        fun categoryMask(categories: Collection<FilterCategory>): Int =
            categories.fold(0) { acc, c -> acc or (1 shl c.ordinal) }
    }
}

package com.advault.intelligence.provider

import com.advault.core.common.AdFormat
import com.advault.core.common.FilterCategory
import com.advault.data.models.AdProvider
import com.advault.data.models.ProviderDomain

/**
 * A provider-database domain that may be used as a filter rule.
 *
 * [domain] is the bare host; when [subdomainsOnly] is false it matches the host and every subdomain,
 * when true only subdomains (the source pattern was "*.host"). This is the same syntax/semantics as
 * custom rules, so the FilterEngine can merge these with its blocklist suffix set under [category].
 */
data class ProviderBlockRule(
    val domain: String,
    val subdomainsOnly: Boolean,
    val category: FilterCategory,
    val providerId: String,
)

/** Domains that may be emitted as filter rules (shared-infrastructure domains are excluded). */
fun AdProvider.blockableDomains(): List<ProviderDomain> = domains.filter { it.blockable }

/** All blockable provider domains as filter rules. Shared-infrastructure domains are never included. */
fun List<AdProvider>.blockRules(): List<ProviderBlockRule> = flatMap { p ->
    p.blockableDomains().map { d ->
        val sub = d.pattern.startsWith("*.")
        ProviderBlockRule(if (sub) d.pattern.substring(2) else d.pattern, sub, d.category, p.id)
    }
}

private val AD_CATEGORIES = setOf(FilterCategory.ADVERTISING, FilterCategory.GAME_ADS)

/** True if any of the provider's domains are ad-serving (as opposed to tracking/analytics/telemetry only). */
fun AdProvider.isAdvertising(): Boolean = domains.any { it.category in AD_CATEGORIES }

/** True if the provider is known to serve a rewarded format on at least one of its domains. */
fun AdProvider.servesRewarded(): Boolean =
    domains.any { d -> d.formats.any { it == AdFormat.REWARDED || it == AdFormat.REWARDED_INTERSTITIAL } }

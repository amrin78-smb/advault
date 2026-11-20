package com.advault.core.common

/** Filtering categories. Each blocklist and provider domain belongs to exactly one category. */
enum class FilterCategory(val displayName: String, val description: String) {
    ADVERTISING("Advertising networks", "General ad-serving and ad-exchange domains."),
    GAME_ADS("Mobile game advertising", "Ad networks used by mobile games (interstitial, rewarded and banner ads)."),
    TRACKING("Tracking & attribution", "Install attribution, cross-app tracking and ad measurement."),
    ANALYTICS("Analytics", "Usage analytics. Blocking may affect some app features."),
    TELEMETRY("Telemetry", "Crash and diagnostics telemetry. Blocking may affect some app features."),
    MALICIOUS("Malicious advertising", "Known malvertising, scam and malware-distribution domains."),
}

/**
 * Filtering strength.
 * BALANCED blocks ADVERTISING, GAME_ADS and MALICIOUS.
 * AGGRESSIVE additionally blocks TRACKING, ANALYTICS and TELEMETRY.
 * In both cases a category must also be enabled globally in settings.
 */
enum class FilterMode(val displayName: String) {
    AGGRESSIVE("Aggressive"),
    BALANCED("Balanced");

    fun includes(category: FilterCategory): Boolean = when (this) {
        AGGRESSIVE -> true
        BALANCED -> category == FilterCategory.ADVERTISING ||
            category == FilterCategory.GAME_ADS ||
            category == FilterCategory.MALICIOUS
    }
}

/** Per-application protection setting. */
enum class AppProtection(val displayName: String) {
    /** Protected using the global default filter mode. */
    DEFAULT("Default"),
    AGGRESSIVE("Aggressive"),
    BALANCED("Balanced"),
    /** Excluded from the VPN entirely (VpnService.Builder.addDisallowedApplication). */
    EXCLUDED("Excluded");

    fun resolveMode(globalDefault: FilterMode): FilterMode? = when (this) {
        DEFAULT -> globalDefault
        AGGRESSIVE -> FilterMode.AGGRESSIVE
        BALANCED -> FilterMode.BALANCED
        EXCLUDED -> null
    }
}

/** Advertising format a provider domain is associated with. Classification only, never proof. */
enum class AdFormat(val displayName: String) {
    DISPLAY("Banner / display"),
    INTERSTITIAL("Interstitial"),
    REWARDED("Rewarded video"),
    REWARDED_INTERSTITIAL("Rewarded interstitial"),
    TRACKING("Tracking / measurement"),
    CONFIGURATION("SDK configuration"),
    UNKNOWN("Unclassified"),
}

enum class Decision { BLOCKED, ALLOWED }

/** Why the filter engine reached a decision. */
enum class DecisionReason(val displayName: String) {
    BLOCKLIST("Matched blocklist"),
    CUSTOM_BLOCK("Custom block rule"),
    CUSTOM_ALLOW("Custom allow rule"),
    CATEGORY_DISABLED("Category disabled"),
    MODE_EXCLUDES_CATEGORY("Filter mode excludes category"),
    NO_MATCH("No rule matched"),
    APP_EXCLUDED("Application excluded"),
    PROTECTION_PAUSED("Protection paused"),
    INVALID_QUERY("Malformed query"),
}

enum class RuleType { BLOCK, ALLOW }

enum class BlocklistFormat {
    /** "0.0.0.0 example.com" hosts file. */
    HOSTS,
    /** One domain per line, optionally "*.example.com". */
    DOMAINS,
    /** Adblock-style "||example.com^" (domain-anchored rules only; cosmetic/path rules are skipped). */
    ADBLOCK,
}

/** Response returned for blocked DNS queries. */
enum class BlockResponse(val displayName: String) {
    /** A -> 0.0.0.0, AAAA -> ::, other types -> NOERROR/empty. Fastest app failure, no retries. */
    NULL_IP("Null IP (0.0.0.0 / ::)"),
    NXDOMAIN("NXDOMAIN"),
    REFUSED("REFUSED"),
}

enum class UpdateFrequency(val displayName: String, val hours: Long) {
    MANUAL("Manual only", 0),
    DAILY("Daily", 24),
    EVERY_3_DAYS("Every 3 days", 72),
    WEEKLY("Weekly", 168),
}

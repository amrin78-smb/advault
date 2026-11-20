package com.advault.filtering.data

import com.advault.core.common.BlocklistFormat
import com.advault.core.common.FilterCategory
import com.advault.core.database.BlocklistEntity

/** A blocklist shipped with the app (snapshot in assets/blocklists, see SOURCES.md). */
data class BuiltInList(
    val id: String,
    val name: String,
    /** HTTPS update URL, or null for AdVault-curated lists that are updated with app releases. */
    val url: String?,
    val category: FilterCategory,
    val format: BlocklistFormat,
    /** File name inside assets/blocklists/. ".gz" files are decompressed transparently. */
    val asset: String,
    val attribution: String,
    val enabledByDefault: Boolean = true,
) {
    fun toEntity(enabled: Boolean = enabledByDefault) = BlocklistEntity(
        id = id, name = name, url = url, category = category, format = format,
        enabled = enabled, builtIn = true, attribution = attribution,
    )
}

object BuiltInLists {
    /** Date of the bundled snapshots (2026-11-20T00:00Z). Bump when assets are refreshed. */
    const val SNAPSHOT_EPOCH_MS = 1_795_132_800_000L

    const val ASSET_DIR = "blocklists"
    const val BUILTIN_ALLOW_ASSET = "builtin_allow.txt"

    /** Pseudo-source backed by [com.advault.data.repository.ProviderRepository] (no file, no URL). */
    const val PROVIDER_SOURCE_ID = "provider-intelligence"

    fun providerEntity(ruleCount: Int, updated: Long) = BlocklistEntity(
        id = PROVIDER_SOURCE_ID,
        name = "Provider intelligence",
        url = null,
        category = FilterCategory.GAME_ADS,
        format = BlocklistFormat.DOMAINS,
        enabled = true,
        builtIn = true,
        attribution = "AdVault advertising provider database (domains associated with known ad SDKs; per-domain categories).",
        ruleCount = ruleCount,
        lastUpdated = updated,
    )

    val all: List<BuiltInList> = listOf(
        BuiltInList(
            id = "stevenblack-unified",
            name = "StevenBlack Unified hosts",
            url = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
            category = FilterCategory.ADVERTISING,
            format = BlocklistFormat.HOSTS,
            asset = "stevenblack-unified.txt.gz",
            attribution = "Steven Black and contributors, https://github.com/StevenBlack/hosts — MIT License. " +
                "Aggregates AdAway (CC BY 3.0), add.2o7Net, add.Risk, add.Spam, Dan Pollock, URLHaus, " +
                "Peter Lowe and others under their respective terms.",
        ),
        BuiltInList(
            id = "adaway",
            name = "AdAway default hosts",
            url = "https://adaway.org/hosts.txt",
            category = FilterCategory.ADVERTISING,
            format = BlocklistFormat.HOSTS,
            asset = "adaway.txt.gz",
            attribution = "AdAway, https://github.com/AdAway/adaway.github.io — CC Attribution 3.0.",
        ),
        BuiltInList(
            id = "advault-mobile-ads",
            name = "AdVault mobile & game ad networks",
            url = null,
            category = FilterCategory.GAME_ADS,
            format = BlocklistFormat.DOMAINS,
            asset = "advault-mobile-ads.txt",
            attribution = "Curated by AdVault from public SDK documentation — CC0 1.0. Updated with app releases.",
        ),
        BuiltInList(
            id = "advault-attribution",
            name = "AdVault mobile attribution trackers",
            url = null,
            category = FilterCategory.TRACKING,
            format = BlocklistFormat.DOMAINS,
            asset = "advault-attribution.txt",
            attribution = "Curated by AdVault from public SDK documentation — CC0 1.0. Updated with app releases.",
        ),
        BuiltInList(
            id = "1hosts-lite",
            name = "1Hosts (Lite)",
            url = "https://raw.githubusercontent.com/badmojr/1Hosts/master/Lite/domains.txt",
            category = FilterCategory.TRACKING,
            format = BlocklistFormat.DOMAINS,
            asset = "1hosts-lite.txt.gz",
            attribution = "badmojr/1Hosts, https://github.com/badmojr/1Hosts — Mozilla Public License 2.0.",
        ),
        BuiltInList(
            id = "frogeye-firstparty",
            name = "First-party trackers (Frogeye)",
            url = "https://hostfiles.frogeye.fr/firstparty-trackers-hosts.txt",
            category = FilterCategory.TRACKING,
            format = BlocklistFormat.HOSTS,
            asset = "frogeye-firstparty.txt.gz",
            attribution = "Geoffrey 'Frogeye' Preud'homme, https://hostfiles.frogeye.fr — MIT License.",
        ),
        BuiltInList(
            id = "perflyst-android",
            name = "Android tracking (Perflyst)",
            url = "https://raw.githubusercontent.com/Perflyst/PiHoleBlocklist/master/android-tracking.txt",
            category = FilterCategory.TRACKING,
            format = BlocklistFormat.DOMAINS,
            asset = "perflyst-android.txt.gz",
            attribution = "Perflyst/PiHoleBlocklist, https://github.com/Perflyst/PiHoleBlocklist — MIT License.",
        ),
        BuiltInList(
            id = "advault-analytics",
            name = "AdVault analytics services",
            url = null,
            category = FilterCategory.ANALYTICS,
            format = BlocklistFormat.DOMAINS,
            asset = "advault-analytics.txt",
            attribution = "Curated by AdVault from public SDK documentation — CC0 1.0. Updated with app releases.",
        ),
        BuiltInList(
            id = "advault-telemetry",
            name = "AdVault crash & telemetry services",
            url = null,
            category = FilterCategory.TELEMETRY,
            format = BlocklistFormat.DOMAINS,
            asset = "advault-telemetry.txt",
            attribution = "Curated by AdVault from public SDK documentation — CC0 1.0. Updated with app releases.",
        ),
        BuiltInList(
            id = "urlhaus-filter",
            name = "Malicious hosts (URLhaus filter)",
            url = "https://malware-filter.gitlab.io/malware-filter/urlhaus-filter-hosts.txt",
            category = FilterCategory.MALICIOUS,
            format = BlocklistFormat.HOSTS,
            asset = "urlhaus-filter.txt.gz",
            attribution = "malware-filter/urlhaus-filter, https://gitlab.com/malware-filter/urlhaus-filter — " +
                "CC0 1.0 / MIT; URLhaus data by abuse.ch — CC0 1.0.",
        ),
        BuiltInList(
            id = "phishing-filter",
            name = "Phishing hosts (phishing filter)",
            url = "https://malware-filter.gitlab.io/malware-filter/phishing-filter-hosts.txt",
            category = FilterCategory.MALICIOUS,
            format = BlocklistFormat.HOSTS,
            asset = "phishing-filter.txt.gz",
            attribution = "malware-filter/phishing-filter, https://gitlab.com/malware-filter/phishing-filter — " +
                "filters CC BY-SA 4.0; sources OpenPhish, PhishTank (free of charge), IPThreat (CC BY-SA 4.0).",
        ),
    )

    fun byId(id: String): BuiltInList? = all.firstOrNull { it.id == id }
}

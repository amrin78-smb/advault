package com.advault.core.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.advault.core.common.AppProtection
import com.advault.core.common.BlocklistFormat
import com.advault.core.common.Decision
import com.advault.core.common.DecisionReason
import com.advault.core.common.FilterCategory
import com.advault.core.common.RuleType

/** A blocklist source. Compiled domains are stored on disk (filesDir/blocklists/<id>.txt), not in Room. */
@Entity(tableName = "blocklists")
data class BlocklistEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** HTTPS URL for downloadable lists; null for bundled-only or local lists. */
    val url: String?,
    val category: FilterCategory,
    val format: BlocklistFormat,
    val enabled: Boolean,
    val builtIn: Boolean,
    /** Human readable source attribution / licence. */
    val attribution: String,
    val ruleCount: Int = 0,
    val invalidRuleCount: Int = 0,
    val duplicateCount: Int = 0,
    /** Epoch millis of the last successful update (or bundle install), 0 if never. */
    val lastUpdated: Long = 0,
    val lastError: String? = null,
    val etag: String? = null,
    val lastModifiedHeader: String? = null,
)

@Entity(tableName = "custom_rules", indices = [Index(value = ["domain", "type"], unique = true)])
data class CustomRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Normalised domain. A leading "*." means "subdomains only". */
    val domain: String,
    val type: RuleType,
    val createdAt: Long,
)

@Entity(tableName = "app_policies")
data class AppPolicyEntity(
    @PrimaryKey val packageName: String,
    val protection: AppProtection,
    val updatedAt: Long,
)

/** One filtered DNS query. Only recorded when activity logging is enabled. */
@Entity(
    tableName = "activity_log",
    indices = [Index("timestamp"), Index("packageName"), Index("providerId")],
)
data class ActivityLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val uid: Int,
    /** Null when the owning app could not be determined. */
    val packageName: String?,
    val domain: String,
    /** DNS QTYPE (1 = A, 28 = AAAA, 65 = HTTPS ...). */
    val queryType: Int,
    val decision: Decision,
    val reason: DecisionReason,
    val category: FilterCategory?,
    val providerId: String?,
    val listId: String?,
    /** Upstream round-trip in ms for allowed queries; 0 for blocked/cached. */
    val latencyMs: Int,
    val cached: Boolean,
)

/** Aggregate counters per day per package. These are the source of all dashboard statistics. */
@Entity(tableName = "daily_stats", primaryKeys = ["day", "packageName"])
data class DailyStatsEntity(
    /** Days since epoch (UTC). */
    val day: Long,
    /** Empty string when the owning app is unknown. */
    val packageName: String,
    val totalQueries: Long = 0,
    val blockedQueries: Long = 0,
    /** Blocked queries in ADVERTISING / GAME_ADS / MALICIOUS. */
    val adsBlocked: Long = 0,
    /** Blocked queries in TRACKING / ANALYTICS / TELEMETRY. */
    val trackingBlocked: Long = 0,
    /** Bytes of DNS traffic (request + response) observed for this app. Measured, not estimated. */
    val dnsBytes: Long = 0,
)

package com.advault.intelligence.provider

import com.advault.core.common.AdFormat
import com.advault.core.common.DomainUtils
import com.advault.core.common.FilterCategory
import com.advault.data.models.AdProvider
import com.advault.data.models.ProviderDatabaseInfo
import com.advault.data.models.ProviderDomain
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Wire format of `assets/intelligence/providers.json` (and of downloaded updates).
 * Pure data: the file is parsed and validated, never executed.
 */
@Serializable
internal data class ProviderDatabaseDto(
    val schemaVersion: Int,
    val dataVersion: String,
    val updatedAt: String,
    val providers: List<ProviderDto>,
)

@Serializable
internal data class ProviderDto(
    val id: String,
    val name: String,
    val kind: String? = null,
    val description: String,
    val lastUpdated: String,
    val supportsServerSideVerification: Boolean = false,
    val domains: List<ProviderDomainDto>,
    val sdkSignatures: SdkSignaturesDto = SdkSignaturesDto(),
)

@Serializable
internal data class ProviderDomainDto(
    val pattern: String,
    val category: String,
    val formats: List<String> = listOf(AdFormat.UNKNOWN.name),
    val note: String? = null,
    val blockable: Boolean = true,
)

@Serializable
internal data class SdkSignaturesDto(
    @SerialName("componentPrefixes") val componentPrefixes: List<String> = emptyList(),
    @SerialName("metaDataKeys") val metaDataKeys: List<String> = emptyList(),
)

class ProviderValidationException(message: String) : Exception(message)

/** A parsed, fully validated provider database. Immutable. */
class ProviderDatabase(
    val schemaVersion: Int,
    val dataVersion: String,
    val updatedAt: String,
    val source: String,
    val providers: List<AdProvider>,
) {
    val domainCount: Int = providers.sumOf { it.domains.size }
    val info: ProviderDatabaseInfo
        get() = ProviderDatabaseInfo(schemaVersion, dataVersion, updatedAt, source, providers.size, domainCount)
}

object ProviderDatabaseParser {
    const val SUPPORTED_SCHEMA_VERSION = 1
    const val MAX_BYTES = 2 * 1024 * 1024
    const val MAX_PROVIDERS = 500
    const val MAX_DOMAINS_PER_PROVIDER = 500
    const val MAX_TOTAL_DOMAINS = 10_000
    const val MAX_SIGNATURES_PER_PROVIDER = 100
    private const val MAX_TEXT = 2_000

    private val ID = Regex("^[a-z0-9_]{2,48}$")
    private val DATA_VERSION = Regex("^\\d{4}\\.\\d{1,2}\\.\\d{1,2}\\.\\d{1,4}$")
    private val DATE = Regex("^\\d{4}-\\d{2}-\\d{2}$")
    private val SIGNATURE = Regex("^[A-Za-z0-9_.$:]{3,200}$")
    private val KINDS = setOf("ad_network", "mediation", "attribution", "analytics", "telemetry", "other")

    /**
     * Hosts that also serve essential non-advertising functionality. A *blockable* provider pattern
     * may never equal one of these or be a parent of one (e.g. "facebook.com" would cover
     * "graph.facebook.com"). More specific children such as "imasdk.googleapis.com" are allowed.
     * This protects against a bad downloaded update breaking unrelated apps.
     */
    val PROTECTED_HOSTS: Set<String> = setOf(
        "google.com", "www.google.com", "googleapis.com", "www.googleapis.com", "gstatic.com",
        "googleusercontent.com", "gvt1.com", "gvt2.com", "android.com", "play.google.com",
        "youtube.com", "firebaseio.com", "firebaseinstallations.googleapis.com",
        "facebook.com", "graph.facebook.com", "facebook.net", "connect.facebook.net", "fbcdn.net",
        "instagram.com", "whatsapp.net",
        "unity3d.com", "unity.com", "apple.com", "icloud.com", "amazon.com", "amazonaws.com",
        "cloudfront.net", "akamaihd.net", "akamaized.net", "akamai.net", "fastly.net",
        "cloudflare.com", "microsoft.com", "windows.net", "azureedge.net", "github.com",
        "githubusercontent.com", "app.link", "bnc.lt",
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = false }

    fun parse(text: String, source: String): ProviderDatabase {
        if (text.length > MAX_BYTES) throw ProviderValidationException("Provider database too large (${text.length} bytes)")
        val dto = try {
            json.decodeFromString(ProviderDatabaseDto.serializer(), text)
        } catch (e: Exception) {
            throw ProviderValidationException("Malformed provider database: ${e.message?.take(200)}")
        }
        return validate(dto, source)
    }

    internal fun validate(dto: ProviderDatabaseDto, source: String): ProviderDatabase {
        fun fail(msg: String): Nothing = throw ProviderValidationException(msg)
        if (dto.schemaVersion != SUPPORTED_SCHEMA_VERSION) fail("Unsupported schemaVersion ${dto.schemaVersion}")
        if (!DATA_VERSION.matches(dto.dataVersion)) fail("Invalid dataVersion '${dto.dataVersion}'")
        if (!DATE.matches(dto.updatedAt)) fail("Invalid updatedAt '${dto.updatedAt}'")
        if (dto.providers.isEmpty()) fail("No providers")
        if (dto.providers.size > MAX_PROVIDERS) fail("Too many providers (${dto.providers.size})")
        val total = dto.providers.sumOf { it.domains.size }
        if (total > MAX_TOTAL_DOMAINS) fail("Too many domains ($total)")

        val ids = HashSet<String>()
        val patterns = HashSet<String>()
        val result = dto.providers.map { p ->
            if (!ID.matches(p.id)) fail("Invalid provider id '${p.id}'")
            if (!ids.add(p.id)) fail("Duplicate provider id '${p.id}'")
            if (p.name.isBlank() || p.name.length > 200) fail("Invalid name for ${p.id}")
            if (p.description.length > MAX_TEXT) fail("Description too long for ${p.id}")
            if (!DATE.matches(p.lastUpdated)) fail("Invalid lastUpdated for ${p.id}")
            if (p.kind != null && p.kind !in KINDS) fail("Unknown kind '${p.kind}' for ${p.id}")
            if (p.domains.isEmpty()) fail("Provider ${p.id} has no domains")
            if (p.domains.size > MAX_DOMAINS_PER_PROVIDER) fail("Too many domains for ${p.id}")
            val sigs = p.sdkSignatures
            if (sigs.componentPrefixes.size + sigs.metaDataKeys.size > MAX_SIGNATURES_PER_PROVIDER) fail("Too many SDK signatures for ${p.id}")
            (sigs.componentPrefixes + sigs.metaDataKeys).forEach {
                if (!SIGNATURE.matches(it)) fail("Invalid SDK signature '$it' for ${p.id}")
            }
            val domains = p.domains.map { d ->
                val pattern = d.pattern.trim().lowercase()
                val host = pattern.removePrefix("*.")
                if (host.contains('*') || !DomainUtils.isValidDomain(host)) fail("Invalid domain '${d.pattern}' in ${p.id}")
                if (!patterns.add(pattern)) fail("Duplicate domain pattern '$pattern'")
                val category = FilterCategory.entries.firstOrNull { it.name == d.category }
                    ?: fail("Unknown category '${d.category}' for $pattern")
                if (d.formats.isEmpty()) fail("No formats for $pattern")
                val formats = d.formats.map { f ->
                    AdFormat.entries.firstOrNull { it.name == f } ?: fail("Unknown format '$f' for $pattern")
                }.distinct()
                if (d.note != null && d.note.length > MAX_TEXT) fail("Note too long for $pattern")
                if (d.blockable && coversProtectedHost(host)) fail("Blockable pattern '$pattern' covers protected shared infrastructure")
                ProviderDomain(pattern, category, formats, d.note, d.blockable)
            }
            AdProvider(
                id = p.id,
                name = p.name.trim(),
                description = p.description.trim(),
                lastUpdated = p.lastUpdated,
                domains = domains,
                sdkSignatures = sigs.componentPrefixes.distinct(),
                supportsServerSideVerification = p.supportsServerSideVerification,
                sdkMetaDataKeys = sigs.metaDataKeys.distinct(),
            )
        }
        return ProviderDatabase(dto.schemaVersion, dto.dataVersion, dto.updatedAt, source, result)
    }

    /** True if blocking [host] (and its subdomains) would block a protected host. */
    fun coversProtectedHost(host: String): Boolean =
        PROTECTED_HOSTS.any { it == host || it.endsWith(".$host") }

    /**
     * Compares "YYYY.M.D.N" data versions numerically. Returns <0, 0, >0. Invalid versions sort lowest.
     */
    fun compareDataVersions(a: String, b: String): Int {
        val pa = parts(a)
        val pb = parts(b)
        if (pa == null || pb == null) return (if (pa == null) 0 else 1) - (if (pb == null) 0 else 1)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val c = (pa.getOrElse(i) { 0 }).compareTo(pb.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    private fun parts(v: String): List<Long>? =
        if (!DATA_VERSION.matches(v)) null else v.split('.').map { it.toLong() }

    /** Picks the downloaded override only if it exists and is strictly newer than the bundled data. */
    fun choose(bundled: ProviderDatabase, override: ProviderDatabase?): ProviderDatabase =
        if (override != null && compareDataVersions(override.dataVersion, bundled.dataVersion) > 0) override else bundled
}

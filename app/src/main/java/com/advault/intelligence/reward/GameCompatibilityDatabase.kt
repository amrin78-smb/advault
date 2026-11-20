package com.advault.intelligence.reward

import com.advault.data.models.GameCompatibility
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Wire format of `assets/intelligence/games.json` and of the optional user/maintainer file
 * `filesDir/intelligence/games.json`. Every entry is a factual claim shown to the user, so entries
 * should only be added once verified; [GameEntryDto.lastVerified] and [GameEntryDto.source] record how.
 */
@Serializable
internal data class GameDatabaseDto(
    val schemaVersion: Int,
    val dataVersion: String,
    val updatedAt: String,
    val games: List<GameEntryDto> = emptyList(),
)

@Serializable
internal data class GameEntryDto(
    val packageName: String,
    val title: String,
    val notes: String = "",
    /** true / false when verified, null when unknown. */
    val rewardRequiresVerifiedAd: Boolean? = null,
    val alternatives: List<String> = emptyList(),
    val knownIssuesWhenBlocking: List<String> = emptyList(),
    val lastVerified: String? = null,
    val source: String? = null,
)

class GameDatabaseValidationException(message: String) : Exception(message)

class GameCompatibilityDatabase(val dataVersion: String, val entries: Map<String, GameCompatibility>) {
    operator fun get(packageName: String): GameCompatibility? = entries[packageName]

    /** Entries in [other] replace entries for the same package. */
    fun mergedWith(other: GameCompatibilityDatabase?): GameCompatibilityDatabase =
        if (other == null) this else GameCompatibilityDatabase(dataVersion, entries + other.entries)

    companion object {
        val EMPTY = GameCompatibilityDatabase("0", emptyMap())
        const val SUPPORTED_SCHEMA_VERSION = 1
        const val MAX_BYTES = 1024 * 1024
        const val MAX_GAMES = 5_000
        private const val MAX_TEXT = 2_000
        private const val MAX_LIST = 20
        private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
        private val DATE = Regex("^\\d{4}-\\d{2}-\\d{2}$")
        private val DATA_VERSION = Regex("^\\d{4}\\.\\d{1,2}\\.\\d{1,2}\\.\\d{1,4}$")
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): GameCompatibilityDatabase {
            fun fail(msg: String): Nothing = throw GameDatabaseValidationException(msg)
            if (text.length > MAX_BYTES) fail("Game database too large")
            val dto = try {
                json.decodeFromString(GameDatabaseDto.serializer(), text)
            } catch (e: Exception) {
                fail("Malformed game database: ${e.message?.take(200)}")
            }
            if (dto.schemaVersion != SUPPORTED_SCHEMA_VERSION) fail("Unsupported schemaVersion ${dto.schemaVersion}")
            if (!DATA_VERSION.matches(dto.dataVersion)) fail("Invalid dataVersion")
            if (!DATE.matches(dto.updatedAt)) fail("Invalid updatedAt")
            if (dto.games.size > MAX_GAMES) fail("Too many games")
            val out = LinkedHashMap<String, GameCompatibility>()
            for (g in dto.games) {
                if (g.packageName.length > 255 || !PACKAGE.matches(g.packageName)) fail("Invalid packageName '${g.packageName}'")
                if (g.packageName in out) fail("Duplicate packageName '${g.packageName}'")
                if (g.title.isBlank() || g.title.length > 200) fail("Invalid title for ${g.packageName}")
                if (g.notes.length > MAX_TEXT) fail("Notes too long for ${g.packageName}")
                for (list in listOf(g.alternatives, g.knownIssuesWhenBlocking)) {
                    if (list.size > MAX_LIST || list.any { it.isBlank() || it.length > 300 }) fail("Invalid list for ${g.packageName}")
                }
                if (g.lastVerified != null && !DATE.matches(g.lastVerified)) fail("Invalid lastVerified for ${g.packageName}")
                if (g.source != null && g.source.length > 500) fail("Source too long for ${g.packageName}")
                out[g.packageName] = GameCompatibility(
                    packageName = g.packageName,
                    title = g.title.trim(),
                    notes = g.notes.trim(),
                    rewardRequiresVerifiedAd = g.rewardRequiresVerifiedAd,
                    alternatives = g.alternatives.map(String::trim),
                    knownIssuesWhenBlocking = g.knownIssuesWhenBlocking.map(String::trim),
                    lastVerified = g.lastVerified,
                )
            }
            return GameCompatibilityDatabase(dto.dataVersion, out)
        }
    }
}

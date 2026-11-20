package com.advault.intelligence.apps

import android.content.pm.ApplicationInfo
import com.advault.data.models.AdProvider

/**
 * Offline SDK detection from an app's *declared* manifest components and meta-data keys.
 * A match shows the SDK is packaged in the app, not that it is active or showing ads.
 */
object SdkDetector {

    /** Returns ids of providers whose signatures match, in database order. */
    fun detect(componentNames: Collection<String>, metaDataKeys: Collection<String>, providers: List<AdProvider>): List<String> {
        if (componentNames.isEmpty() && metaDataKeys.isEmpty()) return emptyList()
        return providers.filter { p ->
            p.sdkSignatures.any { sig -> componentNames.any { matchesPrefix(it, sig) } } ||
                p.sdkMetaDataKeys.any { sig -> metaDataKeys.any { matchesPrefix(it, sig) } }
        }.map { it.id }
    }

    /**
     * [value] equals [prefix], or starts with it at a name boundary: the prefix ends in '.' or ':', or
     * the next character is '.', '$' or ':'. So "com.vungle" matches "com.vungle.ads.X" but not "com.vunglex.Y".
     */
    fun matchesPrefix(value: String, prefix: String): Boolean {
        if (prefix.isEmpty() || !value.startsWith(prefix)) return false
        if (value.length == prefix.length) return true
        val last = prefix.last()
        if (last == '.' || last == ':') return true
        val next = value[prefix.length]
        return next == '.' || next == '$' || next == ':'
    }
}

/** How an app was identified as a game. */
enum class GameSignal(val label: String, val heuristic: Boolean) {
    CATEGORY_GAME("Declared game category", false),
    LEGACY_GAME_FLAG("Declared as a game (legacy flag)", false),
    ENGINE_HEURISTIC("Game engine detected (heuristic)", true),
    PLAY_GAMES_HEURISTIC("Uses Google Play Games services (heuristic)", true),
}

data class GameDetection(val isGame: Boolean, val signal: GameSignal?, val engine: String? = null)

object GameDetector {
    const val CATEGORY_GAME = ApplicationInfo.CATEGORY_GAME
    @Suppress("DEPRECATION")
    const val FLAG_IS_GAME = ApplicationInfo.FLAG_IS_GAME

    /** Engine name → activity class prefixes declared by apps built with that engine. */
    val ENGINE_SIGNATURES: Map<String, List<String>> = linkedMapOf(
        "Unity" to listOf("com.unity3d.player."),
        "Unreal Engine" to listOf("com.epicgames.ue4.", "com.epicgames.unreal."),
        "Cocos2d-x" to listOf("org.cocos2dx.lib."),
        "Cocos Creator" to listOf("com.cocos.lib."),
        "Godot" to listOf("org.godotengine.godot.", "com.godot.game."),
        "GameMaker" to listOf("com.yoyogames.runner."),
        "Defold" to listOf("com.dynamo.android."),
    )
    const val PLAY_GAMES_APP_ID = "com.google.android.gms.games.APP_ID"

    /**
     * Declared category/flag are authoritative. Engine and Play Games signals are heuristics: e.g. some
     * non-game AR/3D apps are built with Unity. Results carry [GameSignal.heuristic] so UI can say so.
     */
    fun detect(category: Int, flags: Int, componentNames: Collection<String>, metaDataKeys: Collection<String>): GameDetection =
        detect(category, flags, detectEngine(componentNames), PLAY_GAMES_APP_ID in metaDataKeys)

    fun detect(category: Int, flags: Int, engine: String?, playGames: Boolean): GameDetection = when {
        category == CATEGORY_GAME -> GameDetection(true, GameSignal.CATEGORY_GAME)
        flags and FLAG_IS_GAME != 0 -> GameDetection(true, GameSignal.LEGACY_GAME_FLAG)
        engine != null -> GameDetection(true, GameSignal.ENGINE_HEURISTIC, engine)
        playGames -> GameDetection(true, GameSignal.PLAY_GAMES_HEURISTIC)
        else -> GameDetection(false, null)
    }

    fun detectEngine(componentNames: Collection<String>): String? =
        ENGINE_SIGNATURES.entries.firstOrNull { (_, prefixes) ->
            prefixes.any { p -> componentNames.any { SdkDetector.matchesPrefix(it, p) } }
        }?.key
}

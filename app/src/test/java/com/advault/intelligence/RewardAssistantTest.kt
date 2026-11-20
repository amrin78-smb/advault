package com.advault.intelligence

import com.advault.core.common.AdFormat
import com.advault.core.common.FilterCategory
import com.advault.data.models.GameAdBehaviour
import com.advault.data.models.GameCompatibility
import com.advault.data.models.ObservedProviderActivity
import com.advault.data.models.ProviderDomain
import com.advault.data.models.RewardSupport
import com.advault.intelligence.apps.GameDetection
import com.advault.intelligence.apps.GameSignal
import com.advault.intelligence.reward.GameCompatibilityDatabase
import com.advault.intelligence.reward.GameDatabaseValidationException
import com.advault.intelligence.reward.RewardAssistant
import com.advault.intelligence.reward.RewardInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RewardAssistantTest {
    private val providers = TestData.bundled().providers.associateBy { it.id }
    private val game = GameDetection(true, GameSignal.CATEGORY_GAME)
    private val noRewardAds = TestData.provider("plainads", listOf(ProviderDomain("plain.example", FilterCategory.ADVERTISING, listOf(AdFormat.DISPLAY))), name = "Plain Ads")
    private val analytics = providers.getValue("gameanalytics")

    private fun input(
        sdks: List<String> = emptyList(),
        observed: List<ObservedProviderActivity> = emptyList(),
        compat: GameCompatibility? = null,
        behaviour: GameAdBehaviour = GameAdBehaviour.DEFAULT,
        extra: List<com.advault.data.models.AdProvider> = emptyList(),
        detection: GameDetection = game,
    ): RewardInput {
        val byId = providers + extra.associateBy { it.id }
        return RewardInput("Test Game", detection, sdks.map { byId.getValue(it) }, observed, byId, compat, behaviour)
    }

    private fun compat(verified: Boolean? = null, alternatives: List<String> = emptyList()) =
        GameCompatibility("com.test.game", "Test Game", "", verified, alternatives, emptyList(), "2026-11-20")

    private fun obs(id: String, formats: Set<AdFormat> = emptySet()) =
        ObservedProviderActivity(id, providers[id]?.name ?: id, 10, 8, 1L, formats)

    @Test fun classification() {
        assertEquals(RewardSupport.NO_ADS_DETECTED, RewardAssistant.classify(input()))
        assertEquals(RewardSupport.NO_ADS_DETECTED, RewardAssistant.classify(input(sdks = listOf("gameanalytics", "appsflyer"))))
        assertEquals(RewardSupport.REWARDED_POSSIBLE, RewardAssistant.classify(input(sdks = listOf("unity_ads"))))
        assertEquals(RewardSupport.REWARDED_POSSIBLE, RewardAssistant.classify(input(observed = listOf(obs("google_admob")))))
        assertEquals(RewardSupport.ADS_NO_REWARDED_EVIDENCE, RewardAssistant.classify(input(sdks = listOf("plainads"), extra = listOf(noRewardAds))))
        assertEquals(RewardSupport.REQUIRES_VERIFIED_COMPLETION, RewardAssistant.classify(input(sdks = listOf("unity_ads"), compat = compat(verified = true))))
        assertEquals(RewardSupport.ALTERNATIVES_AVAILABLE, RewardAssistant.classify(input(sdks = listOf("unity_ads"), compat = compat(true, listOf("Remove-ads purchase")))))
        assertEquals(RewardSupport.REWARDED_POSSIBLE, RewardAssistant.classify(input(sdks = listOf("unity_ads"), compat = compat(verified = null))))
    }

    private val forbidden = listOf(
        Regex("(?i)advault\\s+(will|can|does|lets you|helps you)\\s+(give|grant|unlock|provide|earn|deliver|award|get)"),
        Regex("(?i)free rewards?"), Regex("(?i)bypass"), Regex("(?i)without watching"), Regex("(?i)skip(ping)? (the )?ads?"),
        Regex("(?i)guarantee"), Regex("(?i)unlock(s|ed)? (the )?rewards?"), Regex("(?i)rewards? (are|is) granted by advault"),
        Regex("(?i)fake|forge|spoof|simulat"),
    )

    private fun allInputs(): List<RewardInput> {
        val out = ArrayList<RewardInput>()
        val sdkSets = listOf(emptyList(), listOf("unity_ads"), listOf("google_admob", "applovin", "gameanalytics"), listOf("plainads"), listOf("gameanalytics"))
        val obsSets = listOf(emptyList(), listOf(obs("unity_ads", setOf(AdFormat.REWARDED))), listOf(obs("chartboost")))
        val compats = listOf(null, compat(true), compat(false), compat(null, listOf("Remove-ads purchase")))
        val detections = listOf(game, GameDetection(true, GameSignal.ENGINE_HEURISTIC, "Unity"), GameDetection(false, null))
        for (s in sdkSets) for (o in obsSets) for (c in compats) for (b in GameAdBehaviour.entries) for (d in detections)
            out += input(s, o, c, b, listOf(noRewardAds), d)
        return out
    }

    @Test fun explanationNeverImpliesAdVaultGrantsRewards() {
        for (inp in allInputs()) {
            val text = RewardAssistant.explain(inp)
            assertTrue(text.isNotBlank())
            forbidden.forEach { assertFalse("'${it.pattern}' in:\n$text", it.containsMatchIn(text)) }
        }
    }

    @Test fun explanationHonestyInvariants() {
        for (inp in allInputs()) {
            val support = RewardAssistant.classify(inp)
            val text = RewardAssistant.explain(inp, support)
            val hasAds = (inp.detectedSdks + inp.observed.mapNotNull { inp.providersById[it.providerId] })
                .any { p -> p.domains.any { it.category == FilterCategory.ADVERTISING || it.category == FilterCategory.GAME_ADS } }
            if (hasAds || inp.compatibility != null) {
                assertTrue(text, text.contains("controlled by the game's developer"))
                assertTrue(text, text.contains("AdVault cannot trigger, complete or verify a rewarded ad"))
            }
            if (support == RewardSupport.REWARDED_POSSIBLE) {
                assertTrue(text, text.contains("DNS filtering cannot reliably block one format while allowing another"))
            }
            if (inp.compatibility?.rewardRequiresVerifiedAd == true) assertTrue(text, text.contains("AdVault cannot manufacture that event"))
            if (inp.behaviour == GameAdBehaviour.ALLOW_REWARDED) assertTrue(text, text.contains("Interstitial and banner ads will also show"))
            if (inp.game.signal?.heuristic == true) assertTrue(text, text.contains("heuristic"))
            if (inp.observed.isNotEmpty()) assertTrue(text, text.contains("does not show which ad format was requested"))
            if (inp.compatibility == null && hasAds) {
                assertTrue(text, text.contains("AdVault cannot confirm whether this game offers any of them"))
            }
        }
    }

    @Test fun ssvProvidersExplained() {
        val text = RewardAssistant.explain(input(sdks = listOf("unity_ads")))
        assertTrue(text.contains("Unity Ads can confirm rewarded ad completions on the developer's servers"))
    }

    @Test fun gameDatabaseValidation() {
        val ok = """{"schemaVersion":1,"dataVersion":"2026.11.20.1","updatedAt":"2026-11-20","games":[
            {"packageName":"com.example.game","title":"Example","alternatives":["Paid remove-ads option"],"rewardRequiresVerifiedAd":true,"lastVerified":"2026-11-01"}]}"""
        val db = GameCompatibilityDatabase.parse(ok)
        assertEquals(true, db["com.example.game"]?.rewardRequiresVerifiedAd)
        for (bad in listOf(
            ok.replace("\"schemaVersion\":1", "\"schemaVersion\":9"),
            ok.replace("com.example.game", "not a package"),
            ok.replace("\"2026-11-01\"", "\"yesterday\""),
            ok.replace("\"Example\"", "\"\""),
            "[]",
        )) {
            try { GameCompatibilityDatabase.parse(bad); fail(bad) } catch (_: GameDatabaseValidationException) {}
        }
        val user = GameCompatibilityDatabase.parse(ok.replace("\"title\":\"Example\"", "\"title\":\"Override\""))
        assertEquals("Override", GameCompatibilityDatabase.EMPTY.mergedWith(db).mergedWith(user)["com.example.game"]?.title)
        assertFalse(analytics.domains.isEmpty())
    }
}

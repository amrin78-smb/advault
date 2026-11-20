package com.advault.intelligence.reward

import com.advault.core.common.AdFormat
import com.advault.data.models.AdProvider
import com.advault.data.models.GameAdBehaviour
import com.advault.data.models.GameCompatibility
import com.advault.data.models.ObservedProviderActivity
import com.advault.data.models.RewardSupport
import com.advault.intelligence.apps.GameDetection
import com.advault.intelligence.provider.isAdvertising
import com.advault.intelligence.provider.servesRewarded

/** Everything the Reward Assistant knows about one app. */
data class RewardInput(
    val label: String,
    val game: GameDetection,
    /** SDKs detected from declared components / meta-data. */
    val detectedSdks: List<AdProvider>,
    /** Observed DNS activity, already filtered to meaningful (non shared-infrastructure) matches. */
    val observed: List<ObservedProviderActivity>,
    /** Provider definitions for [observed] entries, by id. */
    val providersById: Map<String, AdProvider>,
    val compatibility: GameCompatibility?,
    val behaviour: GameAdBehaviour,
)

/**
 * Honest classification and explanation for the Reward Assistant.
 *
 * What AdVault can do: classify ad SDKs and ad-provider DNS traffic, and either block that traffic for a
 * game or exclude the game from filtering. What it cannot and does not do: invoke another app's ad SDK,
 * forge or verify ad completions, automate interaction, modify apps or intercept HTTPS. Rewards are decided
 * entirely by the game and its ad provider.
 */
object RewardAssistant {

    private fun adProviders(input: RewardInput): List<AdProvider> {
        val observed = input.observed.mapNotNull { input.providersById[it.providerId] }
        return (input.detectedSdks + observed).distinctBy { it.id }.filter { it.isAdvertising() }
    }

    private fun observedRewardedFormat(input: RewardInput): Boolean = input.observed.any { o ->
        o.associatedFormats.any { it == AdFormat.REWARDED || it == AdFormat.REWARDED_INTERSTITIAL }
    }

    fun classify(input: RewardInput): RewardSupport {
        val compat = input.compatibility
        if (compat != null && compat.alternatives.isNotEmpty()) return RewardSupport.ALTERNATIVES_AVAILABLE
        if (compat?.rewardRequiresVerifiedAd == true) return RewardSupport.REQUIRES_VERIFIED_COMPLETION
        val ads = adProviders(input)
        if (ads.isEmpty()) return RewardSupport.NO_ADS_DETECTED
        if (ads.any { it.servesRewarded() } || observedRewardedFormat(input)) return RewardSupport.REWARDED_POSSIBLE
        return RewardSupport.ADS_NO_REWARDED_EVIDENCE
    }

    fun explain(input: RewardInput, support: RewardSupport = classify(input)): String {
        val out = ArrayList<String>()
        val ads = adProviders(input)
        val detectedAds = input.detectedSdks.filter { it.isAdvertising() }
        val detectedOther = input.detectedSdks.filterNot { it.isAdvertising() }

        if (input.game.signal?.heuristic == true) {
            val why = input.game.engine?.let { "the $it engine was detected" } ?: input.game.signal.label.lowercase()
            out += "This app was identified as a game by a heuristic ($why), which may be wrong."
        }

        if (detectedAds.isNotEmpty()) {
            out += "Advertising SDKs packaged in this app: ${names(detectedAds)}. This is read from the app's declared " +
                "components and shows the SDK is included, not that it is currently showing ads."
        }
        if (detectedOther.isNotEmpty()) {
            out += "Tracking, analytics or crash-reporting SDKs packaged in this app: ${names(detectedOther)}."
        }
        if (input.observed.isNotEmpty()) {
            val parts = input.observed.sortedByDescending { it.hits }.take(6).joinToString(", ") {
                "${it.providerName} (${it.hits} ${if (it.hits == 1L) "request" else "requests"}, ${it.blocked} blocked)"
            }
            out += "DNS requests to known providers seen from this app: $parts. A DNS request only shows that the app " +
                "contacted the provider; it does not show which ad format was requested, or whether an ad was shown or completed."
        }

        if (ads.isNotEmpty() || input.compatibility != null) {
            out += "Whether a reward is offered or granted is controlled by the game's developer. AdVault cannot trigger, " +
                "complete or verify a rewarded ad on the game's behalf, and it cannot make the game grant a reward."
        }
        if (ads.isEmpty()) {
            out += "No advertising SDK from AdVault's provider database was found in this app's declared components, and no " +
                "ad-provider DNS traffic has been seen from it. It may still use advertising providers that are not in the database."
        } else {
            if (support == RewardSupport.REWARDED_POSSIBLE || ads.any { it.servesRewarded() }) {
                out += "Some of these providers can serve rewarded ads. They use the same domains for rewarded, interstitial " +
                    "and banner ads, so DNS filtering cannot reliably block one format while allowing another. With " +
                    "\"${GameAdBehaviour.BLOCK_ALL.displayName}\", rewarded ads will usually fail to load and rewards that " +
                    "depend on them will be unavailable."
            } else {
                out += "No rewarded ad format has been identified for these providers. If the game offers rewards for " +
                    "watching ads, blocking its ads will usually make those rewards unavailable."
            }
            val ssv = ads.filter { it.supportsServerSideVerification }
            if (ssv.isNotEmpty()) {
                out += "${names(ssv)} can confirm rewarded ad completions on the developer's servers. Where a game uses " +
                    "this, a reward is only granted after an ad has genuinely been watched to the end, and AdVault cannot " +
                    "manufacture that event."
            }
        }

        val compat = input.compatibility
        if (compat != null) {
            when (compat.rewardRequiresVerifiedAd) {
                true -> out += "Compatibility information for this game: its ad rewards require a verified ad completion. " +
                    "AdVault cannot manufacture that event."
                false -> out += "Compatibility information for this game indicates its rewards do not depend on a verified ad completion."
                null -> {}
            }
            if (compat.notes.isNotBlank()) out += "Notes: ${compat.notes}"
            if (compat.alternatives.isNotEmpty()) {
                out += "Ad-free options listed in the compatibility information (offered and controlled by the developer): " +
                    compat.alternatives.joinToString("; ") + "."
            }
            if (compat.knownIssuesWhenBlocking.isNotEmpty()) {
                out += "Known issues when ads are blocked: " + compat.knownIssuesWhenBlocking.joinToString("; ") + "."
            }
            compat.lastVerified?.let { out += "Compatibility information last verified: $it." }
        } else if (ads.isNotEmpty()) {
            out += "No verified compatibility information is available for this game. Some developers offer options such " +
                "as a paid \"remove ads\" purchase, a subscription, daily rewards or offline play. Check in the game itself; " +
                "AdVault cannot confirm whether this game offers any of them."
        }

        out += when (input.behaviour) {
            GameAdBehaviour.ALLOW_REWARDED -> "Current setting: this game is excluded from filtering, so you can choose to " +
                "watch rewarded ads. Interstitial and banner ads will also show, and its tracking traffic is not filtered."
            GameAdBehaviour.BLOCK_ALL -> "Current setting: identified ad and tracking domains are blocked for this game " +
                "(aggressive filtering). Rewarded ads will usually not load."
            GameAdBehaviour.DEFAULT -> "Current setting: this game follows its normal app protection."
        }
        return out.joinToString("\n\n")
    }

    private fun names(list: List<AdProvider>) = list.joinToString(", ") { it.name }
}

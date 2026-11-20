package com.advault.intelligence.apps

import com.advault.core.common.AppProtection
import com.advault.core.common.FilterMode
import com.advault.data.models.GameAdBehaviour

/** Minimal identity of an installed package, used for uid resolution. */
data class AppIdentity(val packageName: String, val uid: Int, val isSystem: Boolean, val launchable: Boolean)

/** Effective policy for one uid. [mode] null means the uid is not filtered (excluded from the VPN). */
data class UidPolicy(val packageName: String, val mode: FilterMode?)

/** Immutable resolution of every known uid. Read lock-free from the DNS hot path. */
class ProtectionSnapshot(
    val byUid: Map<Int, UidPolicy>,
    val defaultMode: FilterMode,
    /** Effective protection per package (after Reward Assistant behaviour). */
    val effective: Map<String, AppProtection>,
) {
    /** Unknown uids (e.g. apps installed after the last scan, or unattributed traffic) use the default mode. */
    fun modeForUid(uid: Int): FilterMode? {
        val p = byUid[uid] ?: return defaultMode
        return p.mode
    }

    fun packageForUid(uid: Int): String? = byUid[uid]?.packageName

    companion object {
        val EMPTY = ProtectionSnapshot(emptyMap(), FilterMode.AGGRESSIVE, emptyMap())
    }
}

object ProtectionResolver {

    /**
     * Reward Assistant behaviour overrides the stored app protection:
     *  - ALLOW_REWARDED → EXCLUDED (the game bypasses the VPN entirely, so rewarded ads can load).
     *  - BLOCK_ALL      → AGGRESSIVE.
     *  - DEFAULT / none → stored policy, or DEFAULT.
     */
    fun effectiveProtection(policy: AppProtection?, behaviour: GameAdBehaviour?): AppProtection = when (behaviour) {
        GameAdBehaviour.ALLOW_REWARDED -> AppProtection.EXCLUDED
        GameAdBehaviour.BLOCK_ALL -> AppProtection.AGGRESSIVE
        GameAdBehaviour.DEFAULT, null -> policy ?: AppProtection.DEFAULT
    }

    /**
     * Packages for VpnService.Builder.addDisallowedApplication: effectively EXCLUDED apps (including
     * ALLOW_REWARDED games) plus always [ownPackage]. When [installed] is known, packages that are no
     * longer installed are dropped (addDisallowedApplication throws for unknown packages).
     */
    fun excludedPackages(
        policies: Map<String, AppProtection>,
        behaviours: Map<String, GameAdBehaviour>,
        ownPackage: String,
        installed: ((String) -> Boolean)? = null,
    ): Set<String> {
        val out = sortedSetOf(ownPackage)
        for (pkg in policies.keys + behaviours.keys) {
            if (effectiveProtection(policies[pkg], behaviours[pkg]) == AppProtection.EXCLUDED &&
                (installed == null || installed(pkg))
            ) out += pkg
        }
        return out
    }

    /**
     * Shared uids: Android applies VPN app routing per uid, so if ANY package in a shared uid is
     * excluded the whole uid bypasses the VPN and resolves to null. Otherwise the most restrictive mode
     * among its packages wins (AGGRESSIVE over BALANCED). The reported package is the one with an explicit
     * setting, else a launchable user app, else the alphabetically first package.
     */
    fun resolve(
        apps: Collection<AppIdentity>,
        policies: Map<String, AppProtection>,
        behaviours: Map<String, GameAdBehaviour>,
        defaultMode: FilterMode,
        ownPackage: String,
    ): ProtectionSnapshot {
        val effective = HashMap<String, AppProtection>(apps.size * 2)
        val byUid = HashMap<Int, UidPolicy>(apps.size * 2)
        apps.groupBy { it.uid }.forEach { (uid, members) ->
            var excluded = false
            var mode: FilterMode? = null
            for (m in members) {
                val prot = if (m.packageName == ownPackage) AppProtection.EXCLUDED
                else effectiveProtection(policies[m.packageName], behaviours[m.packageName])
                effective[m.packageName] = prot
                when (val r = prot.resolveMode(defaultMode)) {
                    null -> excluded = true
                    FilterMode.AGGRESSIVE -> mode = FilterMode.AGGRESSIVE
                    FilterMode.BALANCED -> if (mode == null) mode = FilterMode.BALANCED
                }
            }
            val representative = members.sortedWith(
                compareByDescending<AppIdentity> { effective[it.packageName] != AppProtection.DEFAULT }
                    .thenByDescending { it.launchable && !it.isSystem }
                    .thenByDescending { it.launchable }
                    .thenBy { it.packageName },
            ).first()
            byUid[uid] = UidPolicy(representative.packageName, if (excluded) null else mode ?: defaultMode)
        }
        return ProtectionSnapshot(byUid, defaultMode, effective)
    }
}

package com.advault.intelligence

import com.advault.core.common.AppProtection
import com.advault.core.common.FilterMode
import com.advault.data.models.GameAdBehaviour
import com.advault.intelligence.apps.AppIdentity
import com.advault.intelligence.apps.ProtectionResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectionResolverTest {
    private val own = "com.advault.app"
    private val apps = listOf(
        AppIdentity(own, 10100, false, true),
        AppIdentity("com.a", 10001, false, true),
        AppIdentity("com.b", 10002, false, true),
        AppIdentity("com.game", 10003, false, true),
        AppIdentity("com.shared.one", 10004, false, true),
        AppIdentity("com.shared.two", 10004, true, false),
        AppIdentity("com.shared.x", 10005, true, false),
        AppIdentity("com.shared.y", 10005, false, true),
    )

    @Test fun uidModeResolution() {
        val s = ProtectionResolver.resolve(
            apps,
            policies = mapOf("com.b" to AppProtection.BALANCED, "com.shared.two" to AppProtection.EXCLUDED, "com.shared.x" to AppProtection.AGGRESSIVE),
            behaviours = mapOf("com.game" to GameAdBehaviour.BLOCK_ALL),
            defaultMode = FilterMode.BALANCED,
            ownPackage = own,
        )
        assertEquals(FilterMode.BALANCED, s.modeForUid(10001)) // default
        assertEquals(FilterMode.BALANCED, s.modeForUid(10002))
        assertEquals(FilterMode.AGGRESSIVE, s.modeForUid(10003)) // BLOCK_ALL → aggressive
        assertNull(s.modeForUid(10100)) // own app never filtered
        // Shared uid with an excluded member: Android routes per uid, so the uid is unfiltered.
        assertNull(s.modeForUid(10004))
        assertEquals("com.shared.two", s.packageForUid(10004))
        // Shared uid: most restrictive mode wins; explicitly configured package is reported.
        assertEquals(FilterMode.AGGRESSIVE, s.modeForUid(10005))
        assertEquals("com.shared.x", s.packageForUid(10005))
        // Unknown uid → global default, no package.
        assertEquals(FilterMode.BALANCED, s.modeForUid(99999))
        assertNull(s.packageForUid(99999))
        assertEquals("com.a", s.packageForUid(10001))
    }

    @Test fun allowRewardedExcludesGame() {
        val s = ProtectionResolver.resolve(apps, mapOf("com.game" to AppProtection.AGGRESSIVE),
            mapOf("com.game" to GameAdBehaviour.ALLOW_REWARDED), FilterMode.AGGRESSIVE, own)
        assertNull(s.modeForUid(10003))
        assertEquals(AppProtection.EXCLUDED, s.effective["com.game"])
    }

    @Test fun behaviourOverridesPolicy() {
        assertEquals(AppProtection.EXCLUDED, ProtectionResolver.effectiveProtection(AppProtection.AGGRESSIVE, GameAdBehaviour.ALLOW_REWARDED))
        assertEquals(AppProtection.AGGRESSIVE, ProtectionResolver.effectiveProtection(AppProtection.EXCLUDED, GameAdBehaviour.BLOCK_ALL))
        assertEquals(AppProtection.BALANCED, ProtectionResolver.effectiveProtection(AppProtection.BALANCED, GameAdBehaviour.DEFAULT))
        assertEquals(AppProtection.DEFAULT, ProtectionResolver.effectiveProtection(null, null))
    }

    @Test fun excludedPackagesAlwaysContainsOwnPackage() {
        assertEquals(setOf(own), ProtectionResolver.excludedPackages(emptyMap(), emptyMap(), own))
        val ex = ProtectionResolver.excludedPackages(
            policies = mapOf("com.a" to AppProtection.EXCLUDED, "com.b" to AppProtection.BALANCED, "com.gone" to AppProtection.EXCLUDED),
            behaviours = mapOf("com.game" to GameAdBehaviour.ALLOW_REWARDED, "com.a" to GameAdBehaviour.BLOCK_ALL),
            ownPackage = own,
            installed = { it != "com.gone" },
        )
        assertEquals(setOf(own, "com.game"), ex) // com.a overridden by BLOCK_ALL, com.gone uninstalled
        assertTrue(own in ProtectionResolver.excludedPackages(emptyMap(), emptyMap(), own, installed = { false }))
    }
}

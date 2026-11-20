package com.advault.filtering

import com.advault.core.common.FilterCategory
import com.advault.core.common.FilterMode
import com.advault.filtering.engine.FilterSnapshot
import com.advault.filtering.engine.FilterSnapshotBuilder
import com.advault.intelligence.provider.ProviderDatabaseParser
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Cross-module: the real bundled provider database feeding the real filter snapshot builder. */
class ProviderIntegrationTest {
    private val db = ProviderDatabaseParser.parse(
        File("src/main/assets/intelligence/providers.json").readText(), "bundled",
    )
    private val snap = FilterSnapshotBuilder().apply { addProviders(db.providers, "provider-intelligence") }.build()
    private val all = FilterSnapshot.categoryMask(FilterCategory.entries)

    @Test fun nonBlockableSharedInfrastructureIsNeverBlocked() {
        val shared = db.providers.flatMap { it.domains }.filter { !it.blockable }
        assertTrue("expected some classification-only domains", shared.isNotEmpty())
        for (d in shared) {
            val host = d.pattern.removePrefix("*.").let { if (d.pattern.startsWith("*.")) "x.$it" else it }
            assertFalse("$host must not be blocked", snap.evaluate(host, FilterMode.AGGRESSIVE, all).blocked)
        }
        assertFalse(snap.evaluate("graph.facebook.com", FilterMode.AGGRESSIVE, all).blocked)
    }

    @Test fun blockableProviderDomainsAreBlockedInAggressive() {
        val blockable = db.providers.flatMap { it.domains }.filter { it.blockable }
        assertTrue(blockable.size > 20)
        for (d in blockable) {
            val host = if (d.pattern.startsWith("*.")) "x." + d.pattern.removePrefix("*.") else d.pattern
            assertTrue("$host should be blocked", snap.evaluate(host, FilterMode.AGGRESSIVE, all).blocked)
        }
    }
}

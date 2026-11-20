package com.advault.intelligence

import com.advault.core.common.AdFormat
import com.advault.core.common.FilterCategory
import com.advault.data.models.AdProvider
import com.advault.data.models.ProviderDomain
import com.advault.intelligence.provider.ProviderDatabase
import com.advault.intelligence.provider.ProviderDatabaseParser
import java.io.File

object TestData {
    fun bundledProvidersText(): String = asset("providers.json")
    fun bundledGamesText(): String = asset("games.json")

    private fun asset(name: String): String {
        val candidates = listOf("src/main/assets/intelligence/$name", "app/src/main/assets/intelligence/$name")
        return candidates.map(::File).first { it.isFile }.readText()
    }

    fun bundled(): ProviderDatabase = ProviderDatabaseParser.parse(bundledProvidersText(), "bundled")

    fun provider(
        id: String,
        domains: List<ProviderDomain> = listOf(ProviderDomain("$id.example", FilterCategory.GAME_ADS, listOf(AdFormat.UNKNOWN))),
        signatures: List<String> = emptyList(),
        meta: List<String> = emptyList(),
        ssv: Boolean = false,
        name: String = id,
    ) = AdProvider(id, name, "", "2026-11-20", domains, signatures, ssv, meta)

    fun dbJson(
        schemaVersion: Int = 1,
        dataVersion: String = "2026.11.20.1",
        domainsJson: String = """[{"pattern":"ads.example.com","category":"ADVERTISING","formats":["DISPLAY"]}]""",
        id: String = "example",
    ) = """{"schemaVersion":$schemaVersion,"dataVersion":"$dataVersion","updatedAt":"2026-11-20","providers":[
        {"id":"$id","name":"Example","description":"d","lastUpdated":"2026-11-20","domains":$domainsJson,
         "sdkSignatures":{"componentPrefixes":["com.example.ads."],"metaDataKeys":["example.key"]}}]}"""
}

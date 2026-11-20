@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.advault.feature.rewards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.advault.R
import com.advault.feature.common.AdVaultCard
import com.advault.feature.common.AdVaultTopBar
import com.advault.feature.common.EmptyState
import com.advault.feature.common.KeyValueRow
import com.advault.feature.common.MonoStyle
import com.advault.feature.common.SectionHeader
import com.advault.feature.common.StatusTone
import com.advault.feature.common.Tag

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProviderDetailRoute(onBack: () -> Unit, viewModel: ProviderDetailViewModel = hiltViewModel()) {
    val provider by viewModel.provider.collectAsStateWithLifecycle()
    Scaffold(topBar = { AdVaultTopBar(provider?.name ?: stringResource(R.string.provider_title), onBack = onBack) }) { padding ->
        val p = provider
        if (p == null) {
            EmptyState(Icons.Outlined.Hub, stringResource(R.string.provider_missing_title), viewModel.providerId, Modifier.padding(padding))
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Text(p.description, style = MaterialTheme.typography.bodyMedium)
                    SectionHeader(stringResource(R.string.provider_section_info))
                    AdVaultCard(Modifier.fillMaxWidth()) {
                        KeyValueRow(stringResource(R.string.provider_id), p.id, mono = true)
                        KeyValueRow(stringResource(R.string.provider_last_updated), p.lastUpdated)
                        KeyValueRow(
                            stringResource(R.string.provider_ssv),
                            stringResource(if (p.supportsServerSideVerification) R.string.common_yes else R.string.common_no),
                        )
                    }
                    if (p.supportsServerSideVerification) {
                        Text(
                            stringResource(R.string.provider_ssv_explain),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    if (p.sdkSignatures.isNotEmpty()) {
                        SectionHeader(stringResource(R.string.provider_section_sdk))
                        p.sdkSignatures.forEach { Text(it, style = MonoStyle) }
                    }
                    SectionHeader(stringResource(R.string.provider_section_domains, p.domains.size))
                }
            }
            items(p.domains, key = { it.pattern }) { d ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(d.pattern, style = MonoStyle)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Tag(d.category.displayName, tone = StatusTone.Info)
                        d.formats.forEach { Tag(it.displayName, tone = StatusTone.Warning) }
                    }
                    d.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
            }
            item {
                Text(
                    stringResource(R.string.provider_formats_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

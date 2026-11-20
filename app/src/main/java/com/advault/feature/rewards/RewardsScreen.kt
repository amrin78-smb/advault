package com.advault.feature.rewards

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.advault.R
import com.advault.data.models.AdProvider
import com.advault.data.models.GameAdBehaviour
import com.advault.data.models.GameRewardProfile
import com.advault.data.models.RewardSupport
import com.advault.feature.common.AdVaultCard
import com.advault.feature.common.AdVaultTheme
import com.advault.feature.common.AdVaultTopBar
import com.advault.feature.common.AppIcon
import com.advault.feature.common.CenteredLoading
import com.advault.feature.common.EmptyState
import com.advault.feature.common.Format
import com.advault.feature.common.KeyValueRow
import com.advault.feature.common.SectionHeader
import com.advault.feature.common.SettingsRow
import com.advault.feature.common.StatusPill
import com.advault.feature.common.StatusTone
import com.advault.feature.common.Tag
import com.advault.feature.common.resolve

fun RewardSupport.tone(): StatusTone = when (this) {
    RewardSupport.NO_ADS_DETECTED -> StatusTone.Inactive
    RewardSupport.ADS_NO_REWARDED_EVIDENCE -> StatusTone.Info
    RewardSupport.REWARDED_POSSIBLE -> StatusTone.Warning
    RewardSupport.REQUIRES_VERIFIED_COMPLETION -> StatusTone.Error
    RewardSupport.ALTERNATIVES_AVAILABLE -> StatusTone.Active
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RewardsRoute(onOpenProvider: (String) -> Unit, viewModel: RewardsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    LaunchedEffect(viewModel) { viewModel.messageFlow.collect { snackbar.showSnackbar(it.resolve(context)) } }
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    var expanded by rememberSaveable { mutableStateOf(emptyList<String>()) }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = { AdVaultTopBar(stringResource(R.string.rewards_title), scrollBehavior = scroll) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
            item { HonestyCard(Modifier.padding(horizontal = 16.dp)) }
            item { SectionHeader(stringResource(R.string.rewards_section_games), Modifier.padding(horizontal = 16.dp)) }
            when {
                state.loading -> item { CenteredLoading(Modifier.padding(32.dp)) }
                state.games.isEmpty() -> item {
                    EmptyState(Icons.Outlined.SportsEsports, stringResource(R.string.rewards_no_games_title), stringResource(R.string.rewards_no_games_body))
                }
                else -> items(state.games, key = { it.packageName }) { game ->
                    GameCard(
                        game = game,
                        expanded = game.packageName in expanded,
                        onExpand = {
                            expanded = if (game.packageName in expanded) expanded - game.packageName else expanded + game.packageName
                        },
                        onBehaviour = { viewModel.setBehaviour(game, it) },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
            }
            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    SectionHeader(stringResource(R.string.rewards_section_providers))
                    AdVaultCard(Modifier.fillMaxWidth()) {
                        val info = state.info
                        if (info == null) {
                            Text(stringResource(R.string.rewards_db_not_loaded), style = MaterialTheme.typography.bodyMedium)
                        } else {
                            KeyValueRow(stringResource(R.string.rewards_db_version), info.dataVersion)
                            KeyValueRow(stringResource(R.string.rewards_db_updated_at), info.updatedAt)
                            KeyValueRow(stringResource(R.string.rewards_db_source), info.source)
                            KeyValueRow(stringResource(R.string.rewards_db_counts), stringResource(R.string.rewards_db_counts_value, info.providerCount, info.domainCount))
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = viewModel::updateDatabase, enabled = !state.updatingDatabase, modifier = Modifier.fillMaxWidth()) {
                            if (state.updatingDatabase) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Outlined.CloudDownload, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.rewards_db_update))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
            if (state.providers.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.rewards_no_providers),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            } else {
                items(state.providers, key = { "p_" + it.id }) { p ->
                    ProviderRow(p) { onOpenProvider(p.id) }
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun HonestyCard(modifier: Modifier = Modifier) {
    AdVaultCard(
        modifier.fillMaxWidth(),
        color = AdVaultTheme.status.infoContainer,
        border = BorderStroke(1.dp, AdVaultTheme.status.info.copy(alpha = 0.4f)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.EmojiEvents, null, tint = AdVaultTheme.status.info)
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.rewards_honesty_title), style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(8.dp))
        listOf(
            R.string.rewards_honesty_1,
            R.string.rewards_honesty_2,
            R.string.rewards_honesty_3,
            R.string.rewards_honesty_4,
        ).forEach {
            Row(Modifier.padding(vertical = 3.dp)) {
                Text("•", modifier = Modifier.width(16.dp), color = AdVaultTheme.status.info)
                Text(stringResource(it), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GameCard(
    game: GameRewardProfile,
    expanded: Boolean,
    onExpand: () -> Unit,
    onBehaviour: (GameAdBehaviour) -> Unit,
    modifier: Modifier = Modifier,
) {
    AdVaultCard(modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onExpand).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(game.packageName, game.icon, game.label)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(game.label, style = MaterialTheme.typography.titleSmall)
                StatusPill(game.support.displayName, game.support.tone())
                Text(
                    stringResource(R.string.rewards_current_behaviour, game.behaviour.displayName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = stringResource(if (expanded) R.string.rewards_collapse else R.string.rewards_expand),
            )
        }
        AnimatedVisibility(expanded) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(game.explanation, style = MaterialTheme.typography.bodyMedium)

                Label(stringResource(R.string.rewards_detected_sdks))
                if (game.detectedSdks.isEmpty()) Muted(stringResource(R.string.rewards_none_detected))
                else FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    game.detectedSdks.forEach { Tag(it.name, tone = StatusTone.Warning) }
                }

                Label(stringResource(R.string.rewards_observed))
                if (game.observed.isEmpty()) Muted(stringResource(R.string.rewards_none_observed))
                game.observed.forEach { o ->
                    Column(Modifier.padding(vertical = 2.dp)) {
                        Text(o.providerName, style = MaterialTheme.typography.bodyMedium)
                        Muted(
                            stringResource(
                                R.string.rewards_observed_line,
                                Format.count(o.hits), Format.count(o.blocked),
                                Format.relativeTime(o.lastSeen) ?: stringResource(R.string.common_never),
                            ),
                        )
                        if (o.associatedFormats.isNotEmpty()) {
                            Muted(stringResource(R.string.rewards_associated_with, o.associatedFormats.joinToString { it.displayName }))
                        }
                    }
                }

                game.compatibility?.let { c ->
                    Label(stringResource(R.string.rewards_compatibility))
                    if (c.notes.isNotBlank()) Text(c.notes, style = MaterialTheme.typography.bodyMedium)
                    Muted(
                        stringResource(
                            R.string.rewards_requires_verified,
                            stringResource(
                                when (c.rewardRequiresVerifiedAd) {
                                    true -> R.string.common_yes
                                    false -> R.string.common_no
                                    null -> R.string.common_unknown
                                },
                            ),
                        ),
                    )
                    if (c.rewardRequiresVerifiedAd == true) {
                        Text(stringResource(R.string.rewards_cannot_manufacture), style = MaterialTheme.typography.bodySmall, color = AdVaultTheme.status.warning)
                    }
                    if (c.alternatives.isNotEmpty()) {
                        Label(stringResource(R.string.rewards_alternatives))
                        c.alternatives.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                    }
                    if (c.knownIssuesWhenBlocking.isNotEmpty()) {
                        Label(stringResource(R.string.rewards_known_issues))
                        c.knownIssuesWhenBlocking.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                    }
                    c.lastVerified?.let { Muted(stringResource(R.string.rewards_last_verified, it)) }
                }

                Label(stringResource(R.string.rewards_behaviour))
                Column(Modifier.selectableGroup()) {
                    GameAdBehaviour.entries.forEach { b ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.small)
                                .selectable(b == game.behaviour, role = Role.RadioButton, onClick = { onBehaviour(b) })
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            RadioButton(selected = b == game.behaviour, onClick = null)
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(b.displayName, style = MaterialTheme.typography.bodyLarge)
                                Muted(b.description)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun Muted(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ProviderRow(p: AdProvider, onClick: () -> Unit) {
    SettingsRow(
        title = p.name,
        subtitle = stringResource(R.string.rewards_provider_summary, p.domains.size, p.lastUpdated) +
            if (p.supportsServerSideVerification) " · " + stringResource(R.string.rewards_provider_ssv) else "",
        onClick = onClick,
    )
}

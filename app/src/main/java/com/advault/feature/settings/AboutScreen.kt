@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.advault.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.advault.BuildConfig
import com.advault.R
import com.advault.feature.common.AdVaultCard
import com.advault.feature.common.AdVaultTopBar
import com.advault.feature.common.SectionHeader
import com.advault.feature.common.SystemIntents

const val GITHUB_URL = "https://github.com/amrin78-smb/advault"

@Composable
fun AboutRoute(onBack: () -> Unit, viewModel: AboutViewModel = hiltViewModel()) {
    val lists by viewModel.lists.collectAsStateWithLifecycle()
    val context = LocalContext.current
    Scaffold(topBar = { AdVaultTopBar(stringResource(R.string.about_title), onBack = onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                Icon(Icons.Filled.Shield, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(44.dp))
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
                    Text(
                        stringResource(R.string.about_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(stringResource(R.string.about_tagline), style = MaterialTheme.typography.bodyMedium)

            SectionHeader(stringResource(R.string.about_section_privacy))
            Bullets(
                listOf(
                    R.string.about_privacy_1, R.string.about_privacy_2, R.string.about_privacy_3,
                    R.string.about_privacy_4, R.string.about_privacy_5,
                ).map { stringResource(it) },
            )

            SectionHeader(stringResource(R.string.about_section_limitations))
            Bullets(
                listOf(
                    R.string.about_limit_1, R.string.about_limit_2, R.string.about_limit_3,
                    R.string.about_limit_4, R.string.about_limit_5, R.string.about_limit_6,
                ).map { stringResource(it) },
            )

            SectionHeader(stringResource(R.string.about_section_attributions))
            AdVaultCard(Modifier.fillMaxWidth()) {
                if (lists.isEmpty()) Text(stringResource(R.string.about_no_lists), style = MaterialTheme.typography.bodyMedium)
                lists.forEach { l ->
                    Column(Modifier.padding(vertical = 6.dp)) {
                        Text(l.name, style = MaterialTheme.typography.titleSmall)
                        Text(
                            l.attribution.ifBlank { l.url ?: "" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            SectionHeader(stringResource(R.string.about_section_source))
            OutlinedButton(onClick = { SystemIntents.openUrl(context, GITHUB_URL) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.about_github))
            }
            Text(GITHUB_URL, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun Bullets(items: List<String>) {
    AdVaultCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items.forEach {
                Row {
                    Text("•", color = MaterialTheme.colorScheme.secondary, modifier = Modifier.width(16.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

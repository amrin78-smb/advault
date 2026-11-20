package com.advault.feature.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.VpnLock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.advault.R
import com.advault.feature.common.AdVaultTheme
import kotlinx.coroutines.launch

private data class Page(val icon: ImageVector, val title: Int, val body: List<Int>)

private val pages = listOf(
    Page(Icons.Outlined.Shield, R.string.onboarding_1_title, listOf(R.string.onboarding_1_body, R.string.onboarding_1_body2)),
    Page(Icons.Outlined.VpnLock, R.string.onboarding_2_title, listOf(R.string.onboarding_2_body, R.string.onboarding_2_body2)),
    Page(Icons.Outlined.Lightbulb, R.string.onboarding_3_title, listOf(R.string.onboarding_3_body, R.string.onboarding_3_body2, R.string.onboarding_3_body3)),
)

@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
    val pager = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    val last = pager.currentPage == pages.lastIndex

    Column(
        Modifier.fillMaxSize().background(AdVaultTheme.status.heroGradient).safeDrawingPadding(),
    ) {
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.End) {
            if (!last) TextButton(onClick = onFinish) { Text(stringResource(R.string.onboarding_skip)) }
            else Spacer(Modifier.height(48.dp))
        }
        HorizontalPager(pager, Modifier.weight(1f)) { index ->
            val page = pages[index]
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    Modifier.size(112.dp).clip(CircleShape).background(AdVaultTheme.status.infoContainer),
                    contentAlignment = Alignment.Center,
                ) { Icon(page.icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp)) }
                Spacer(Modifier.height(28.dp))
                Text(stringResource(page.title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                page.body.forEach {
                    Text(
                        stringResource(it),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center) {
            repeat(pages.size) { i ->
                Box(
                    Modifier
                        .padding(4.dp)
                        .size(if (i == pager.currentPage) 10.dp else 8.dp)
                        .clip(CircleShape)
                        .background(if (i == pager.currentPage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
                )
            }
        }
        Button(
            onClick = { if (last) onFinish() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp).height(52.dp),
        ) {
            Text(stringResource(if (last) R.string.onboarding_done else R.string.onboarding_next))
        }
        Spacer(Modifier.width(1.dp))
    }
}

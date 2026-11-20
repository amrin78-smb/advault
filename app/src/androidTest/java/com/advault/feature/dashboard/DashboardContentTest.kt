package com.advault.feature.dashboard

import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.advault.core.preferences.AppSettings
import com.advault.data.models.DashboardStats
import com.advault.data.models.ProtectionState
import com.advault.data.models.VpnDiagnostics
import com.advault.feature.common.AdVaultTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class DashboardContentTest {
    @get:Rule val rule = createComposeRule()

    private fun render(state: ProtectionState, stats: DashboardStats = DashboardStats(), onToggle: (Boolean) -> Unit = {}) {
        val ui = DashboardMapper.map(state, stats, VpnDiagnostics(), emptyList(), AppSettings(), null, Locale.US)
        rule.setContent {
            AdVaultTheme {
                DashboardContent(ui, onToggle, {}, {}, {}, {}, {}, {})
            }
        }
    }

    @Test fun stopped() {
        render(ProtectionState.Stopped)
        rule.onNodeWithTag("status_title").assertTextContains("Protection is off")
        rule.onNodeWithTag("protection_switch").assertIsOff()
    }

    @Test fun starting() {
        render(ProtectionState.Starting)
        rule.onNodeWithTag("status_title").assertTextContains("Starting", substring = true)
        rule.onNodeWithTag("protection_switch").assertIsOn()
    }

    @Test fun running() {
        render(ProtectionState.Running(System.currentTimeMillis(), "Quad9 (DNS-over-TLS)", 12, 2, true))
        rule.onNodeWithTag("status_title").assertTextContains("Protection active")
        rule.onNodeWithTag("status_detail").assertTextContains("Quad9", substring = true)
        rule.onNodeWithTag("status_detail").assertTextContains("12 apps protected · 2 excluded", substring = true)
        rule.onNodeWithTag("protection_switch").assertIsOn()
    }

    @Test fun waitingForNetwork() {
        render(ProtectionState.WaitingForNetwork(0))
        rule.onNodeWithTag("status_title").assertTextContains("Waiting for network")
    }

    @Test fun error() {
        render(ProtectionState.Error("Could not establish TUN", recoverable = true))
        rule.onNodeWithTag("status_title").assertTextContains("Protection error")
        rule.onNodeWithTag("status_detail").assertTextContains("Could not establish TUN")
        rule.onNodeWithTag("protection_switch").assertIsOff()
    }

    @Test fun revoked() {
        render(ProtectionState.Revoked("Another VPN connected"))
        rule.onNodeWithTag("status_title").assertTextContains("VPN connection taken over")
        rule.onNodeWithText("Another VPN connected", substring = true).assertExists()
    }

    @Test fun estimateIsLabelled() {
        render(ProtectionState.Stopped, DashboardStats(totalQueries = 10, estimatedBytesSaved = 38_500_000))
        rule.onNodeWithText("≈ 38.5 MB").performScrollTo().assertExists()
        rule.onNodeWithText("ESTIMATE", substring = true).assertExists()
    }

    @Test fun toggleCallsBack() {
        var toggled: Boolean? = null
        render(ProtectionState.Stopped) { toggled = it }
        rule.onNodeWithTag("protection_switch").performClick()
        assertEquals(true, toggled)
    }
}

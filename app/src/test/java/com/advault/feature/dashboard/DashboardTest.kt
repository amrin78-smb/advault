@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.advault.feature.dashboard

import android.content.Intent
import com.advault.R
import com.advault.core.preferences.AppSettings
import com.advault.data.models.DailyPoint
import com.advault.data.models.DashboardStats
import com.advault.data.models.ProtectionState
import com.advault.data.models.VpnDiagnostics
import com.advault.feature.FakeProtectionController
import com.advault.feature.FakeSettingsSource
import com.advault.feature.FakeStatsRepository
import com.advault.feature.MainDispatcherRule
import com.advault.feature.common.StatusTone
import com.advault.feature.common.UiText
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.Locale

class DashboardMapperTest {
    private fun map(state: ProtectionState, stats: DashboardStats = DashboardStats(), diag: VpnDiagnostics = VpnDiagnostics()) =
        DashboardMapper.map(state, stats, diag, emptyList(), AppSettings(), null, Locale.US)

    @Test fun stoppedIsOffAndInactive() {
        val p = map(ProtectionState.Stopped).protection
        assertEquals(StatusKind.Stopped, p.kind)
        assertFalse(p.toggleOn)
        assertEquals(StatusTone.Inactive, p.tone)
    }

    @Test fun startingIsOnAndInfo() {
        val p = map(ProtectionState.Starting).protection
        assertEquals(StatusKind.Starting, p.kind)
        assertTrue(p.toggleOn)
    }

    @Test fun runningCarriesUpstreamAndCounts() {
        val p = map(ProtectionState.Running(1000, "Cloudflare DoH", 42, 3, ipv6 = true)).protection
        assertEquals(StatusKind.Running, p.kind)
        assertTrue(p.toggleOn)
        assertEquals(StatusTone.Active, p.tone)
        assertEquals("Cloudflare DoH", p.upstream)
        assertEquals(42, p.protectedApps)
        assertEquals(3, p.excludedApps)
        assertEquals(1000L, p.since)
        assertTrue(p.ipv6)
    }

    @Test fun waitingForNetworkKeepsToggleOnWithWarning() {
        val p = map(ProtectionState.WaitingForNetwork(5)).protection
        assertEquals(StatusKind.WaitingForNetwork, p.kind)
        assertTrue(p.toggleOn)
        assertEquals(StatusTone.Warning, p.tone)
    }

    @Test fun errorShowsMessageAndToggleOff() {
        val p = map(ProtectionState.Error("TUN failed", recoverable = false)).protection
        assertEquals(StatusKind.Error, p.kind)
        assertFalse(p.toggleOn)
        assertEquals(StatusTone.Error, p.tone)
        assertEquals("TUN failed", p.message)
        assertFalse(p.recoverable)
    }

    @Test fun revokedShowsMessage() {
        val p = map(ProtectionState.Revoked("Other VPN connected")).protection
        assertEquals(StatusKind.Revoked, p.kind)
        assertFalse(p.toggleOn)
        assertEquals("Other VPN connected", p.message)
    }

    @Test fun estimatedDataSavedIsAlwaysLabelledAsEstimate() {
        val ui = map(ProtectionState.Stopped, DashboardStats(estimatedBytesSaved = 38_500_000))
        assertTrue(ui.estimatedDataSaved.isEstimate)
        assertEquals("≈ 38.5 MB", ui.estimatedDataSaved.value)
        // Measured values are never flagged as estimates.
        assertFalse(ui.adsBlocked.isEstimate)
        assertFalse(ui.trackingBlocked.isEstimate)
        assertFalse(ui.protectedApps.isEstimate)
        assertFalse(ui.totalQueries.isEstimate)
    }

    @Test fun zeroStatsShowZerosNotInventedValues() {
        val ui = map(ProtectionState.Stopped)
        assertEquals("0", ui.adsBlocked.value)
        assertEquals("0", ui.trackingBlocked.value)
        assertEquals("0", ui.protectedApps.value)
        assertEquals("≈ 0 B", ui.estimatedDataSaved.value)
        assertTrue(ui.estimatedDataSaved.isEstimate)
        assertEquals(0L, ui.lastBlocklistUpdate)
        assertFalse(ui.hasTraffic)
        assertTrue(ui.daily.isEmpty())
    }

    @Test fun countsAreFormattedWithGrouping() {
        val ui = map(ProtectionState.Stopped, DashboardStats(totalQueries = 5000, adsBlocked = 1284, trackingBlocked = 426, protectedApps = 12, lastBlocklistUpdate = 77))
        assertEquals("1,284", ui.adsBlocked.value)
        assertEquals("426", ui.trackingBlocked.value)
        assertEquals("12", ui.protectedApps.value)
        assertEquals("5,000", ui.totalQueries.value)
        assertEquals(77L, ui.lastBlocklistUpdate)
        assertTrue(ui.hasTraffic)
    }

    @Test fun dailyPointsSortedAndNotPadded() {
        val ui = DashboardMapper.map(
            ProtectionState.Stopped, DashboardStats(), VpnDiagnostics(),
            listOf(DailyPoint(3, 10, 2), DailyPoint(1, 5, 1)), null, null, Locale.US,
        )
        assertEquals(listOf(1L, 3L), ui.daily.map { it.day })
    }

    @Test fun privateDnsSurfaced() {
        val ui = map(ProtectionState.Stopped, diag = VpnDiagnostics(privateDnsActive = true, privateDnsServerName = "dns.google"))
        assertTrue(ui.privateDnsActive)
        assertEquals("dns.google", ui.privateDnsServer)
    }
}

class StartFlowTest {
    private val intent = Intent()

    @Test fun otherVpnRequiresConfirmationFirst() {
        val step = StartFlow.next(VpnDiagnostics(anotherVpnActive = true), otherVpnConfirmed = false, notificationPermissionNeeded = true) { intent }
        assertEquals(StartStep.ConfirmOtherVpn, step)
    }

    @Test fun confirmedOtherVpnProceeds() {
        val step = StartFlow.next(VpnDiagnostics(anotherVpnActive = true), otherVpnConfirmed = true, notificationPermissionNeeded = false) { null }
        assertEquals(StartStep.Start, step)
    }

    @Test fun notificationPermissionBeforeConsent() {
        val step = StartFlow.next(VpnDiagnostics(), false, notificationPermissionNeeded = true) { intent }
        assertEquals(StartStep.RequestNotificationPermission, step)
    }

    @Test fun consentIntentNeeded() {
        val step = StartFlow.next(VpnDiagnostics(), false, false) { intent }
        assertTrue(step is StartStep.RequestVpnConsent)
        assertTrue((step as StartStep.RequestVpnConsent).intent === intent)
    }

    @Test fun noConsentNeededStartsDirectly() {
        assertEquals(StartStep.Start, StartFlow.next(VpnDiagnostics(), false, false) { null })
    }

    @Test fun consentIntentNotQueriedWhileConfirmationPending() {
        var queried = false
        StartFlow.next(VpnDiagnostics(anotherVpnActive = true), false, false) { queried = true; null }
        assertFalse(queried)
    }
}

class DashboardViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val controller = FakeProtectionController()
    private val stats = FakeStatsRepository()
    private fun vm() = DashboardViewModel(controller, stats, FakeSettingsSource())

    @Test fun otherVpnShowsDialogAndDoesNotStart() = runTest {
        controller.diagnostics.value = VpnDiagnostics(anotherVpnActive = true, alwaysOnVpnOtherApp = true)
        val vm = vm()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        vm.onToggle(true, notificationPermissionNeeded = false)
        assertEquals(1, controller.refreshCalls)
        assertEquals(DashboardDialog.ConfirmOtherVpn(alwaysOnOther = true), vm.state.value.dialog)
        assertEquals(0, controller.startCalls)

        vm.onOtherVpnConfirmed()
        assertNull(vm.state.value.dialog)
        assertEquals(1, controller.startCalls)
    }

    @Test fun dismissingOtherVpnDialogNeverStarts() = runTest {
        controller.diagnostics.value = VpnDiagnostics(anotherVpnActive = true)
        val vm = vm()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        vm.onToggle(true, false)
        vm.dismissDialog()
        assertNull(vm.state.value.dialog)
        assertEquals(0, controller.startCalls)
    }

    @Test fun consentIntentIsLaunchedThenStartOnGrant() = runTest {
        val intent = Intent()
        controller.consentIntent = intent
        val vm = vm()
        vm.onToggle(true, false)
        val effect = vm.effectFlow.first()
        assertTrue(effect is DashboardEffect.LaunchVpnConsent && effect.intent === intent)
        assertEquals(0, controller.startCalls)
        vm.onVpnConsentResult(true)
        assertEquals(1, controller.startCalls)
    }

    @Test fun consentDeniedShowsMessage() = runTest {
        controller.consentIntent = Intent()
        val vm = vm()
        vm.onToggle(true, false)
        vm.effectFlow.first()
        vm.onVpnConsentResult(false)
        assertEquals(DashboardEffect.ShowMessage(UiText.Res(R.string.dashboard_consent_denied)), vm.effectFlow.first())
        assertEquals(0, controller.startCalls)
    }

    @Test fun notificationPermissionRequestedThenFlowContinuesEvenIfDenied() = runTest {
        val vm = vm()
        vm.onToggle(true, notificationPermissionNeeded = true)
        assertEquals(DashboardEffect.RequestNotificationPermission, vm.effectFlow.first())
        assertEquals(0, controller.startCalls)
        vm.onNotificationPermissionResult(false)
        assertEquals(DashboardEffect.ShowMessage(UiText.Res(R.string.dashboard_notifications_denied)), vm.effectFlow.first())
        assertEquals(1, controller.startCalls)
    }

    @Test fun toggleOffStops() {
        val vm = vm()
        vm.onToggle(false, false)
        assertEquals(1, controller.stopCalls)
        assertEquals(0, controller.startCalls)
    }

    @Test fun stateReflectsControllerAndStats() = runTest {
        val vm = vm()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        controller.state.value = ProtectionState.Running(1, "Quad9", 5, 1, false)
        stats.dashboard.value = DashboardStats(adsBlocked = 7, estimatedBytesSaved = 2_000)
        val ui = vm.state.value
        assertEquals(StatusKind.Running, ui.protection.kind)
        assertEquals("7", ui.adsBlocked.value.filter { it.isDigit() })
        assertTrue(ui.estimatedDataSaved.isEstimate)
        assertTrue(ui.estimatedDataSaved.value.startsWith("≈"))
    }
}

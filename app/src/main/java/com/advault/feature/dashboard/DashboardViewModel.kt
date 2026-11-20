package com.advault.feature.dashboard

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.advault.R
import com.advault.core.preferences.AppSettings
import com.advault.data.models.DailyPoint
import com.advault.data.models.DashboardStats
import com.advault.data.models.ProtectionState
import com.advault.data.models.VpnDiagnostics
import com.advault.data.repository.ProtectionController
import com.advault.data.repository.StatsRepository
import com.advault.feature.common.Format
import com.advault.feature.common.SettingsSource
import com.advault.feature.common.StatusTone
import com.advault.feature.common.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

enum class StatusKind { Stopped, Starting, Running, WaitingForNetwork, Error, Revoked }

data class ProtectionUi(
    val kind: StatusKind,
    /** Whether the big toggle is in the "on" position. */
    val toggleOn: Boolean,
    val tone: StatusTone,
    val upstream: String? = null,
    val protectedApps: Int? = null,
    val excludedApps: Int? = null,
    val since: Long? = null,
    val ipv6: Boolean = false,
    val message: String? = null,
    val recoverable: Boolean = true,
)

/** A displayed statistic. [isEstimate] values must be visibly labelled as estimates. */
data class StatValue(val value: String, val isEstimate: Boolean = false)

data class DashboardUiState(
    val protection: ProtectionUi = DashboardMapper.protection(ProtectionState.Stopped),
    val adsBlocked: StatValue = StatValue("0"),
    val trackingBlocked: StatValue = StatValue("0"),
    val estimatedDataSaved: StatValue = StatValue(Format.estimateBytes(0), isEstimate = true),
    val protectedApps: StatValue = StatValue("0"),
    val totalQueries: StatValue = StatValue("0"),
    val blockedQueries: StatValue = StatValue("0"),
    /** Epoch millis; 0 means never. Rendered relatively in the UI. */
    val lastBlocklistUpdate: Long = 0,
    val hasTraffic: Boolean = false,
    val daily: List<DailyPoint> = emptyList(),
    val privateDnsActive: Boolean = false,
    val privateDnsServer: String? = null,
    val bytesPerBlockedRequest: Int = AppSettings().estimatedBytesPerBlockedRequest,
    val dialog: DashboardDialog? = null,
)

sealed interface DashboardDialog {
    /** Another VPN is connected; starting AdVault will disconnect it. */
    data class ConfirmOtherVpn(val alwaysOnOther: Boolean) : DashboardDialog
    data object EstimateExplanation : DashboardDialog
}

sealed interface DashboardEffect {
    data class LaunchVpnConsent(val intent: Intent) : DashboardEffect
    data object RequestNotificationPermission : DashboardEffect
    data class ShowMessage(val text: UiText) : DashboardEffect
}

/** Next step of the protection start flow. Pure decision logic, see [StartFlow.next]. */
sealed interface StartStep {
    data object ConfirmOtherVpn : StartStep
    data object RequestNotificationPermission : StartStep
    data class RequestVpnConsent(val intent: Intent) : StartStep
    data object Start : StartStep
}

object StartFlow {
    /**
     * Order: (1) never silently replace another VPN — confirm first; (2) ask for notification permission
     * once (API 33+; protection still works if denied); (3) obtain VPN consent if needed; (4) start.
     */
    fun next(
        diagnostics: VpnDiagnostics,
        otherVpnConfirmed: Boolean,
        notificationPermissionNeeded: Boolean,
        consentIntent: () -> Intent?,
    ): StartStep {
        if (diagnostics.anotherVpnActive && !otherVpnConfirmed) return StartStep.ConfirmOtherVpn
        if (notificationPermissionNeeded) return StartStep.RequestNotificationPermission
        val intent = consentIntent()
        return if (intent != null) StartStep.RequestVpnConsent(intent) else StartStep.Start
    }
}

object DashboardMapper {
    fun protection(state: ProtectionState): ProtectionUi = when (state) {
        ProtectionState.Stopped -> ProtectionUi(StatusKind.Stopped, toggleOn = false, tone = StatusTone.Inactive)
        ProtectionState.Starting -> ProtectionUi(StatusKind.Starting, toggleOn = true, tone = StatusTone.Info)
        is ProtectionState.Running -> ProtectionUi(
            StatusKind.Running, toggleOn = true, tone = StatusTone.Active,
            upstream = state.upstreamDescription,
            protectedApps = state.protectedAppCount,
            excludedApps = state.excludedAppCount,
            since = state.since,
            ipv6 = state.ipv6,
        )
        is ProtectionState.WaitingForNetwork -> ProtectionUi(StatusKind.WaitingForNetwork, toggleOn = true, tone = StatusTone.Warning, since = state.since)
        is ProtectionState.Error -> ProtectionUi(StatusKind.Error, toggleOn = false, tone = StatusTone.Error, message = state.message, recoverable = state.recoverable)
        is ProtectionState.Revoked -> ProtectionUi(StatusKind.Revoked, toggleOn = false, tone = StatusTone.Warning, message = state.message)
    }

    fun map(
        state: ProtectionState,
        stats: DashboardStats,
        diagnostics: VpnDiagnostics,
        daily: List<DailyPoint>,
        settings: AppSettings?,
        dialog: DashboardDialog?,
        locale: Locale = Locale.getDefault(),
    ): DashboardUiState = DashboardUiState(
        protection = protection(state),
        adsBlocked = StatValue(Format.count(stats.adsBlocked, locale)),
        trackingBlocked = StatValue(Format.count(stats.trackingBlocked, locale)),
        estimatedDataSaved = StatValue(Format.estimateBytes(stats.estimatedBytesSaved.coerceAtLeast(0), locale), isEstimate = true),
        protectedApps = StatValue(Format.count(stats.protectedApps, locale)),
        totalQueries = StatValue(Format.count(stats.totalQueries, locale)),
        blockedQueries = StatValue(Format.count(stats.blockedQueries, locale)),
        lastBlocklistUpdate = stats.lastBlocklistUpdate.coerceAtLeast(0),
        hasTraffic = stats.totalQueries > 0,
        // Only real observed days; never pad with invented values.
        daily = daily.sortedBy { it.day },
        privateDnsActive = diagnostics.privateDnsActive,
        privateDnsServer = diagnostics.privateDnsServerName,
        bytesPerBlockedRequest = settings?.estimatedBytesPerBlockedRequest ?: AppSettings().estimatedBytesPerBlockedRequest,
        dialog = dialog,
    )
}

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val controller: ProtectionController,
    stats: StatsRepository,
    settingsSource: SettingsSource,
) : ViewModel() {

    private val dialog = MutableStateFlow<DashboardDialog?>(null)
    private val effects = Channel<DashboardEffect>(Channel.BUFFERED)
    val effectFlow: Flow<DashboardEffect> = effects.receiveAsFlow()

    private var otherVpnConfirmed = false
    private var notificationPermissionNeeded = false

    private val settingsFlow: Flow<AppSettings?> = settingsSource.settings().onStart<AppSettings?> { emit(null) }

    val state: StateFlow<DashboardUiState> = combine(
        combine(controller.state, controller.diagnostics, ::Pair),
        stats.observeDashboard().onStart { emit(DashboardStats()) },
        stats.observeDaily(7).onStart { emit(emptyList()) },
        settingsFlow,
        dialog,
    ) { (state, diag), s, daily, settings, dlg ->
        DashboardMapper.map(state, s, diag, daily, settings, dlg)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())

    fun refreshDiagnostics() = controller.refreshDiagnostics()

    /**
     * Big toggle. [notificationPermissionNeeded] is computed by the UI (API 33+ and not yet granted, and we
     * have not asked this session).
     */
    fun onToggle(enable: Boolean, notificationPermissionNeeded: Boolean) {
        if (!enable) {
            controller.stop()
            return
        }
        otherVpnConfirmed = false
        this.notificationPermissionNeeded = notificationPermissionNeeded
        controller.refreshDiagnostics()
        advance()
    }

    private fun advance() {
        val diag = controller.diagnostics.value
        when (val step = StartFlow.next(diag, otherVpnConfirmed, notificationPermissionNeeded, controller::prepareIntent)) {
            StartStep.ConfirmOtherVpn -> dialog.value = DashboardDialog.ConfirmOtherVpn(diag.alwaysOnVpnOtherApp)
            StartStep.RequestNotificationPermission -> {
                notificationPermissionNeeded = false
                send(DashboardEffect.RequestNotificationPermission)
            }
            is StartStep.RequestVpnConsent -> send(DashboardEffect.LaunchVpnConsent(step.intent))
            StartStep.Start -> controller.start()
        }
    }

    fun onOtherVpnConfirmed() {
        dialog.value = null
        otherVpnConfirmed = true
        advance()
    }

    fun dismissDialog() {
        dialog.value = null
    }

    fun showEstimateExplanation() {
        dialog.value = DashboardDialog.EstimateExplanation
    }

    fun onNotificationPermissionResult(granted: Boolean) {
        if (!granted) send(DashboardEffect.ShowMessage(UiText.Res(R.string.dashboard_notifications_denied)))
        advance()
    }

    fun onVpnConsentResult(granted: Boolean) {
        if (granted) controller.start()
        else send(DashboardEffect.ShowMessage(UiText.Res(R.string.dashboard_consent_denied)))
    }

    private fun send(effect: DashboardEffect) {
        viewModelScope.launch { effects.send(effect) }
    }
}

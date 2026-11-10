package com.dronits.wotvpn.ui

import androidx.annotation.StringRes
import com.dronits.wotvpn.R
import com.dronits.wotvpn.config.ConfigInputError
import com.dronits.wotvpn.vpn.VpnState

internal enum class ConnectionStep {
    Idle,
    RequestNotifications,
    AwaitingNotifications,
    RequestVpn,
    AwaitingVpn,
    ReadyToStart,
}

internal enum class UiActionError(@StringRes val messageRes: Int) {
    VpnConsentDenied(R.string.error_vpn_consent_denied),
    PermissionDialogFailed(R.string.error_permission_dialog),
    ServiceStartFailed(R.string.error_vpn_start),
    ServiceStopFailed(R.string.error_vpn_stop),
}

internal data class MainUiState(
    val configuration: String = "",
    val configurationError: ConfigInputError? = null,
    val gameInstalled: Boolean = false,
    val notificationsEnabled: Boolean = true,
    val step: ConnectionStep = ConnectionStep.Idle,
    val vpnState: VpnState = VpnState.Disconnected,
    val actionError: UiActionError? = null,
) {
    val permissionRequestInProgress: Boolean
        get() = step != ConnectionStep.Idle

    val vpnActive: Boolean
        get() = vpnState == VpnState.Preparing || vpnState == VpnState.Connected || vpnState == VpnState.Stopping

    val configurationEditable: Boolean
        get() = !permissionRequestInProgress && !vpnActive
}

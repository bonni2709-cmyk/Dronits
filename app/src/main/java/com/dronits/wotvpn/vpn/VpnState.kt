package com.dronits.wotvpn.vpn

sealed interface VpnState {
    data object Disconnected : VpnState
    data object Preparing : VpnState
    data object Connected : VpnState
    data object Stopping : VpnState
    data class Error(val message: String) : VpnState
}

package com.dronits.wotvpn.vpn

import android.content.Context
import kotlinx.coroutines.flow.StateFlow

internal interface VpnClient {
    val state: StateFlow<VpnState>
    fun connect(configuration: String)
    fun disconnect()
}

internal class AndroidVpnClient(context: Context) : VpnClient {
    private val applicationContext = context.applicationContext

    override val state: StateFlow<VpnState> = WotVpnService.state

    override fun connect(configuration: String) {
        WotVpnService.requestConnect(applicationContext, configuration)
    }

    override fun disconnect() {
        WotVpnService.requestDisconnect(applicationContext)
    }
}

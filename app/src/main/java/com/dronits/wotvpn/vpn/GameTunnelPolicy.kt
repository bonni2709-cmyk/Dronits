package com.dronits.wotvpn.vpn

import android.content.pm.PackageManager
import android.net.VpnService

internal object GameTunnelPolicy {
    @Throws(PackageManager.NameNotFoundException::class)
    fun configure(builder: VpnService.Builder, sessionName: String): VpnService.Builder {
        // Never fall back to a device-wide VPN if this package cannot be resolved.
        builder.addAllowedApplication(WotGame.PACKAGE_NAME)

        return builder
            .setSession(sessionName)
            .setMtu(TunnelSettings.MTU)
            .setBlocking(false)
            .addAddress(TunnelSettings.IPV4, 30)
            .addAddress(TunnelSettings.IPV6, 126)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            .addDnsServer("1.1.1.1")
            .addDnsServer("2606:4700:4700::1111")
    }
}

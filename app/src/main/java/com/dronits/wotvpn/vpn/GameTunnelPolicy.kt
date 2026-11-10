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
            .setMtu(1400)
            .addAddress("10.77.0.1", 30)
            .addAddress("fd42:776f:7400::1", 126)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            .addDnsServer("1.1.1.1")
            .addDnsServer("2606:4700:4700::1111")
    }
}

package com.dronits.wotvpn.vpn

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

internal object WotGame {
    const val PACKAGE_NAME = "com.wargaming.wot.blitz"

    @Suppress("DEPRECATION")
    fun isInstalled(context: Context): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getApplicationInfo(
                PACKAGE_NAME,
                PackageManager.ApplicationInfoFlags.of(0L),
            )
        } else {
            context.packageManager.getApplicationInfo(PACKAGE_NAME, 0)
        }
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}

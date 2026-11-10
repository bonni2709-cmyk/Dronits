package com.dronits.wotvpn.core

import android.net.VpnService

internal object NativeXray {
    init { System.loadLibrary("xray") }

    external fun start(configuration: ByteArray, service: VpnService): Int
    external fun stop()
    external fun isRunning(): Boolean
}

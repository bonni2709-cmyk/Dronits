package com.dronits.wotvpn.core

internal object NativeTunnel {
    init { System.loadLibrary("hev-socks5-tunnel") }

    /** The adapter duplicates fd and waits for actual lwIP initialization, not just pthread creation. */
    external fun start(configuration: ByteArray, fd: Int): Boolean
    external fun stop()
    external fun isRunning(): Boolean
}

package com.dronits.wotvpn.core

import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/** Authenticates locally and verifies RFC 1928 UDP ASSOCIATE before any TUN is established. */
internal object Socks5Probe {
    fun verifyUdp(endpoint: SocksEndpoint) {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", endpoint.port), 3_000)
            socket.soTimeout = 3_000
            val input = DataInputStream(socket.getInputStream())
            val output = socket.getOutputStream()
            output.write(byteArrayOf(5, 1, 2))
            output.flush()
            checkReply(input.readUnsignedByte() == 5 && input.readUnsignedByte() == 2)
            val user = endpoint.username.toByteArray(Charsets.UTF_8)
            val pass = endpoint.password.toByteArray(Charsets.UTF_8)
            checkReply(user.size in 1..255 && pass.size in 1..255)
            output.write(byteArrayOf(1, user.size.toByte()))
            output.write(user)
            output.write(pass.size)
            output.write(pass)
            output.flush()
            checkReply(input.readUnsignedByte() == 1 && input.readUnsignedByte() == 0)
            output.write(byteArrayOf(5, 3, 0, 1, 127, 0, 0, 1, 0, 0))
            output.flush()
            checkReply(input.readUnsignedByte() == 5 && input.readUnsignedByte() == 0)
            checkReply(input.readUnsignedByte() == 0)
            val addressLength = when (input.readUnsignedByte()) {
                1 -> 4
                4 -> 16
                3 -> input.readUnsignedByte()
                else -> throw IOException("Invalid SOCKS reply")
            }
            checkReply(addressLength > 0)
            input.readFully(ByteArray(addressLength))
            checkReply(input.readUnsignedShort() > 0)
        }
    }

    private fun checkReply(condition: Boolean) {
        if (!condition) throw IOException("SOCKS UDP association unavailable")
    }
}

package com.tube.byedpi

import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/** Минимальный SOCKS5-клиент (CONNECT по имени домена) — для проверки стратегий без лишних библиотек. */
object Socks5 {

    fun connect(proxyPort: Int, host: String, port: Int, timeoutMs: Int): Socket {
        val socket = Socket()
        try {
            socket.soTimeout = timeoutMs
            socket.connect(InetSocketAddress("127.0.0.1", proxyPort), timeoutMs)
            val out = socket.getOutputStream()
            val input = DataInputStream(socket.getInputStream())
            out.write(byteArrayOf(5, 1, 0))
            out.flush()
            if (input.readByte().toInt() != 5 || input.readByte().toInt() != 0) throw IOException("socks auth")
            val name = host.toByteArray(Charsets.US_ASCII)
            out.write(byteArrayOf(5, 1, 0, 3, name.size.toByte()) + name + byteArrayOf((port shr 8).toByte(), port.toByte()))
            out.flush()
            if (input.readByte().toInt() != 5) throw IOException("socks version")
            val reply = input.readByte().toInt()
            if (reply != 0) throw IOException("socks reply $reply")
            input.readByte()
            when (input.readByte().toInt()) {
                1 -> input.skipBytes(4)
                3 -> input.skipBytes(input.readUnsignedByte())
                4 -> input.skipBytes(16)
                else -> throw IOException("socks atyp")
            }
            input.skipBytes(2)
            return socket
        } catch (e: Exception) {
            socket.close()
            throw e
        }
    }
}

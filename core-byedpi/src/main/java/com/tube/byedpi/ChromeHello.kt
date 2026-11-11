package com.tube.byedpi

import java.io.ByteArrayOutputStream
import kotlin.random.Random

/**
 * ClientHello «как у Chrome»: шаблон — настоящий ClientHello Chrome с Kyber и GREASE ECH (~1.8 КБ, из zapret),
 * в который подставляется нужное имя сайта.
 *
 * Зачем: приложение YouTube (Cronet) шлёт именно такой большой ClientHello, где SNI стоит после
 * переставленных расширений и часто попадает во второй TCP-сегмент. Короткий ClientHello Java (~500 байт)
 * DPI разбирает иначе, поэтому часть стратегий проходила проверку, но не пропускала видео в самом YouTube.
 */
object ChromeHello {

    /** Новый ClientHello: SNI = [host], случайные random и session id; длины пересчитываются. */
    fun build(template: ByteArray, host: String, random: Random = Random.Default): ByteArray {
        require(template.size > 50 && template[0] == 0x16.toByte() && template[5] == 0x01.toByte()) { "not a ClientHello" }
        var p = 5 + 4 + 2 // заголовок записи, заголовок handshake, версия
        val randomAt = p
        p += 32
        val sessionLen = template[p].toInt() and 0xFF
        val sessionAt = p + 1
        p += 1 + sessionLen
        p += 2 + u16(template, p) // cipher suites
        p += 1 + (template[p].toInt() and 0xFF) // compression
        val extTotalAt = p
        val extStart = p + 2
        val extEnd = extStart + u16(template, p)

        val head = template.copyOfRange(0, extTotalAt).also {
            random.nextBytes(it, randomAt, randomAt + 32)
            random.nextBytes(it, sessionAt, sessionAt + sessionLen)
        }
        val extensions = ByteArrayOutputStream()
        var e = extStart
        var replaced = false
        while (e + 4 <= extEnd) {
            val type = u16(template, e)
            val len = u16(template, e + 2)
            if (type == 0x0000) {
                extensions.write(serverName(host))
                replaced = true
            } else {
                extensions.write(template, e, 4 + len)
            }
            e += 4 + len
        }
        if (!replaced) extensions.write(serverName(host))
        val ext = extensions.toByteArray()

        val out = ByteArrayOutputStream()
        out.write(head)
        out.write(ext.size shr 8)
        out.write(ext.size and 0xFF)
        out.write(ext)
        val bytes = out.toByteArray()
        val handshakeLen = bytes.size - 9
        bytes[6] = (handshakeLen shr 16).toByte()
        bytes[7] = (handshakeLen shr 8).toByte()
        bytes[8] = handshakeLen.toByte()
        val recordLen = bytes.size - 5
        bytes[3] = (recordLen shr 8).toByte()
        bytes[4] = recordLen.toByte()
        return bytes
    }

    /** Имя сайта из ClientHello (для тестов). */
    fun sni(hello: ByteArray): String? {
        var p = 5 + 4 + 2 + 32
        p += 1 + (hello[p].toInt() and 0xFF)
        p += 2 + u16(hello, p)
        p += 1 + (hello[p].toInt() and 0xFF)
        val end = p + 2 + u16(hello, p)
        p += 2
        while (p + 4 <= end) {
            val type = u16(hello, p)
            val len = u16(hello, p + 2)
            if (type == 0) {
                val nameLen = u16(hello, p + 7)
                return String(hello, p + 9, nameLen, Charsets.US_ASCII)
            }
            p += 4 + len
        }
        return null
    }

    private fun serverName(host: String): ByteArray {
        val name = host.toByteArray(Charsets.US_ASCII)
        val listLen = name.size + 3
        val extLen = listLen + 2
        return byteArrayOf(0, 0, (extLen shr 8).toByte(), extLen.toByte(), (listLen shr 8).toByte(), listLen.toByte(), 0, (name.size shr 8).toByte(), name.size.toByte()) + name
    }

    private fun u16(b: ByteArray, at: Int): Int = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)
}

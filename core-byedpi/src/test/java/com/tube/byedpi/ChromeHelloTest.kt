package com.tube.byedpi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.File
import kotlin.random.Random

class ChromeHelloTest {
    private val template = File("src/main/assets/fake/tls_clienthello_vk_com_kyber.bin").readBytes()

    @Test
    fun replacesSniAndFixesLengths() {
        val hello = ChromeHello.build(template, "rr4---sn-gxuo03g-3c2z.googlevideo.com", Random(1))
        assertEquals("vk.com", ChromeHello.sni(template))
        assertEquals("rr4---sn-gxuo03g-3c2z.googlevideo.com", ChromeHello.sni(hello))
        val recordLen = ((hello[3].toInt() and 0xFF) shl 8) or (hello[4].toInt() and 0xFF)
        assertEquals(hello.size - 5, recordLen)
        val handshakeLen = ((hello[6].toInt() and 0xFF) shl 16) or ((hello[7].toInt() and 0xFF) shl 8) or (hello[8].toInt() and 0xFF)
        assertEquals(hello.size - 9, handshakeLen)
        // Больше одного TCP-сегмента, как у настоящего Chrome.
        assert(hello.size > 1500)
    }

    @Test
    fun randomizesClientRandom() {
        val a = ChromeHello.build(template, "www.youtube.com", Random(1))
        val b = ChromeHello.build(template, "www.youtube.com", Random(2))
        assertNotEquals(a.copyOfRange(11, 43).toList(), b.copyOfRange(11, 43).toList())
        assertEquals(a.size, b.size)
    }
}

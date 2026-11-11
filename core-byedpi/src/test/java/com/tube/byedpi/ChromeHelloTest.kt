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
    fun permutesExtensionsLikeChrome() {
        val host = "rr4---sn-gxuo03g-3c2z.googlevideo.com"
        val first = ChromeHello.build(template, host, Random(3), ChromeHello.Order.SniFirst)
        val last = ChromeHello.build(template, host, Random(3), ChromeHello.Order.SniLast)
        val random = ChromeHello.build(template, host, Random(4), ChromeHello.Order.Random)
        listOf(first, last, random).forEach { hello ->
            assertEquals(host, ChromeHello.sni(hello))
            assertEquals(hello.size - 5, ((hello[3].toInt() and 0xFF) shl 8) or (hello[4].toInt() and 0xFF))
        }
        assertEquals(first.size, last.size)
        // SNI в начале — в первом TCP-сегменте, в конце — во втором (как часто у Chrome с Kyber и ECH).
        assert(ChromeHello.sniOffset(first) < 200) { ChromeHello.sniOffset(first) }
        assert(ChromeHello.sniOffset(last) > 1460) { ChromeHello.sniOffset(last) }
        // GREASE остаётся первым и последним расширением.
        val extStart = 5 + 4 + 2 + 32 + 1 + (first[43].toInt() and 0xFF)
        val cs = ((first[extStart].toInt() and 0xFF) shl 8) or (first[extStart + 1].toInt() and 0xFF)
        val ext = extStart + 2 + cs + 1 + (first[extStart + 2 + cs].toInt() and 0xFF) + 2
        assertEquals(0x9a.toByte(), last[ext])
        assertEquals(0x0a.toByte(), last[last.size - 5])
    }

    @Test
    fun randomizesClientRandom() {
        val a = ChromeHello.build(template, "www.youtube.com", Random(1))
        val b = ChromeHello.build(template, "www.youtube.com", Random(2))
        assertNotEquals(a.copyOfRange(11, 43).toList(), b.copyOfRange(11, 43).toList())
        assertEquals(a.size, b.size)
    }
}

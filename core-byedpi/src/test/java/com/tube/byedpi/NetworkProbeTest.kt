package com.tube.byedpi

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Проверка сетевой части тестера через настоящий интернет и простой SOCKS5-ретранслятор (без byedpi).
 * Запуск вручную: gradlew :core-byedpi:testDebugUnitTest -Pnetwork
 */
class NetworkProbeTest {

    private fun relay(): Int {
        val server = ServerSocket(0)
        thread(isDaemon = true) {
            while (true) {
                val client = server.accept()
                thread(isDaemon = true) {
                    runCatching {
                        val input = DataInputStream(client.getInputStream())
                        val out = client.getOutputStream()
                        input.readByte(); val n = input.readUnsignedByte(); input.skipBytes(n)
                        out.write(byteArrayOf(5, 0))
                        input.readByte(); input.readByte(); input.readByte()
                        val host = when (input.readUnsignedByte()) {
                            3 -> ByteArray(input.readUnsignedByte()).also { input.readFully(it) }.toString(Charsets.US_ASCII)
                            else -> error("atyp")
                        }
                        val port = input.readUnsignedShort()
                        val upstream = Socket().apply { connect(InetSocketAddress(host, port), 5000) }
                        out.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
                        thread(isDaemon = true) { runCatching { upstream.getInputStream().copyTo(out) }; client.close() }
                        runCatching { input.copyTo(upstream.getOutputStream()) }
                        upstream.close()
                    }
                    client.close()
                }
            }
        }
        return server.localPort
    }

    @Test
    fun localDnsResolvesOverDoh() {
        assumeTrue(System.getProperty("network") == "true")
        val name = "www.youtube.com".split('.').flatMap { listOf(it.length.toByte()) + it.toByteArray().toList() }
        val query = byteArrayOf(0x12, 0x34, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0) + name.toByteArray() + byteArrayOf(0, 0, 1, 0, 1)
        val socket = java.net.DatagramSocket().apply { soTimeout = 8000 }
        val dns = LocalDns.shared()
        socket.send(java.net.DatagramPacket(query, query.size, java.net.InetAddress.getByName("127.0.0.1"), dns.port))
        val buffer = ByteArray(1500)
        val packet = java.net.DatagramPacket(buffer, buffer.size)
        socket.receive(packet)
        val answers = ((buffer[6].toInt() and 0xFF) shl 8) or (buffer[7].toInt() and 0xFF)
        println("LocalDns: ${packet.length} B, $answers answers")
        assertTrue(buffer[0] == 0x12.toByte() && buffer[1] == 0x34.toByte())
        assertTrue(answers > 0)
    }

    @Test
    fun probesPassThroughPlainProxy() {
        assumeTrue(System.getProperty("network") == "true")
        val port = relay()
        val results = StrategyTester.defaultProbes().map { probe ->
            val started = System.currentTimeMillis()
            val bytes = StrategyTester.httpsGet(port, probe.host, probe.path, maxOf(probe.minBytes, 1), 8000)
            println("${probe.host}${probe.path}: ${bytes.size} B, ${System.currentTimeMillis() - started} ms, ${String(bytes).lineSequence().first()}")
            probe to bytes.size
        }
        results.forEach { (probe, size) -> assertTrue(probe.host, size >= maxOf(probe.minBytes, 1)) }
    }
}

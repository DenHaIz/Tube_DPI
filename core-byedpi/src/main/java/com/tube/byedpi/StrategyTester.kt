package com.tube.byedpi

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.Socket
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.coroutines.coroutineContext

/** Что проверяем: TLS до узла и, если [minBytes] > 0, что ответ не «замирает» после первых килобайт. */
data class Probe(val host: String, val path: String, val minBytes: Int)

data class ProbeResult(val probe: Probe, val ok: Boolean, val millis: Long, val bytes: Int, val error: String?)

data class StrategyScore(val strategy: Strategy, val results: List<ProbeResult>, val startError: String? = null) {
    val passed: Int get() = results.count { it.ok }
    val total: Int get() = results.size
    val avgMillis: Long get() = results.filter { it.ok }.map { it.millis }.average().takeIf { !it.isNaN() }?.toLong() ?: Long.MAX_VALUE
    val works: Boolean get() = total > 0 && passed == total
}

sealed interface TestEvent {
    data class Progress(val done: Int, val total: Int, val score: StrategyScore) : TestEvent
    data class Finished(val ranking: List<StrategyScore>) : TestEvent
}

/**
 * Автоподбор стратегии, как перебор general*.bat у zapret, но автоматически:
 * для каждой стратегии поднимается свой byedpi на отдельном порту (несколько параллельно),
 * через него открываются TLS-соединения к узлам YouTube и замеряется скачивание.
 */
class StrategyTester(
    private val context: Context,
    private val basePort: Int = 11080,
    private val parallelism: Int = 6,
    private val timeoutMs: Int = 7000,
    private val fakeSni: String = Strategies.FakeSniOptions.first(),
) {

    fun run(strategies: List<Strategy>, probes: List<Probe>, blockUdp: Boolean = true): Flow<TestEvent> = channelFlow {
        val gate = Semaphore(parallelism)
        val ports = ArrayDeque((0 until parallelism).map { basePort + it })
        var done = 0
        val scores = coroutineScope {
            strategies.map { strategy ->
                async {
                    gate.withPermit {
                        val port = synchronized(ports) { ports.removeFirst() }
                        try {
                            testOne(strategy, port, probes, blockUdp)
                        } finally {
                            synchronized(ports) { ports.addLast(port) }
                        }
                    }.also { score ->
                        synchronized(this@StrategyTester) { done++ }
                        send(TestEvent.Progress(done, strategies.size, score))
                    }
                }
            }.awaitAll()
        }
        send(TestEvent.Finished(rank(scores)))
    }.flowOn(Dispatchers.IO)

    private suspend fun testOne(strategy: Strategy, port: Int, probes: List<Probe>, blockUdp: Boolean): StrategyScore {
        val argv = when (val parsed = StrategyArgs.parse(strategy.args)) {
            is StrategyArgs.Result.Ok -> parsed.argv
            is StrategyArgs.Result.Error -> return StrategyScore(strategy, emptyList(), parsed.message)
        }
        val proxy = try {
            ByeDpiProcess.start(context, port, argv, blockUdp, fakeSni)
        } catch (e: Exception) {
            return StrategyScore(strategy, emptyList(), e.message)
        }
        try {
            val results = coroutineScope {
                probes.map { probe -> async { probe(port, probe) } }.awaitAll()
            }
            return StrategyScore(strategy, results)
        } finally {
            proxy.stop()
        }
    }

    private suspend fun probe(port: Int?, probe: Probe): ProbeResult {
        coroutineContext.ensureActive()
        val started = System.nanoTime()
        val target = maxOf(probe.minBytes, 1)
        return try {
            val bytes = httpsGet(port, probe.host, probe.path, target, timeoutMs).size
            val ok = bytes >= target
            ProbeResult(probe, ok, (System.nanoTime() - started) / 1_000_000, bytes, if (ok) null else "short read $bytes")
        } catch (e: Exception) {
            ProbeResult(probe, false, (System.nanoTime() - started) / 1_000_000, 0, e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""))
        }
    }

    /**
     * Те же проверки без прокси: видно, блокирует ли сеть YouTube вообще
     * (приложение не входит в собственный VPN, поэтому соединения идут напрямую).
     */
    /** Отдаёт ли DNS провайдера адрес youtube.com (с февраля 2026 многие — нет; byedpi тогда ходит через DoH). */
    fun systemDnsResolves(host: String = "www.youtube.com"): Boolean = runCatching {
        java.net.InetAddress.getAllByName(host).any { !it.isAnyLocalAddress && !it.isLoopbackAddress }
    }.getOrDefault(false)

    suspend fun baseline(probes: List<Probe>): StrategyScore = coroutineScope {
        val results = probes.map { probe -> async(Dispatchers.IO) { probe(null, probe) } }.awaitAll()
        StrategyScore(Strategy(BASELINE_ID, "DIRECT", "", "Без обхода", "No bypass"), results)
    }

    companion object {
        const val BASELINE_ID = "direct"

        /**
         * Узлы, которые нужны приложению YouTube: страница, превью и видеосервер.
         * redirector отвечает с SNI *.googlevideo.com — по нему ТСПУ и режет видео.
         * Страница качается целиком (> 64 КБ): так видно замедление, при котором поток «замирает» после ~16 КБ.
         */
        fun defaultProbes(): List<Probe> = listOf(
            Probe("www.youtube.com", "/", minBytes = 64 * 1024),
            Probe("i.ytimg.com", "/vi/jNQXAC9IVRw/hqdefault.jpg", minBytes = 8 * 1024),
            Probe("redirector.googlevideo.com", "/report_mapping?di=no", minBytes = 16),
        )

        /** Сначала все пройденные проверки, затем скорость. */
        fun rank(scores: List<StrategyScore>): List<StrategyScore> =
            scores.sortedWith(compareByDescending<StrategyScore> { it.passed }.thenBy { it.avgMillis })

        private fun httpRequest(host: String, path: String): String = listOf(
            "GET $path HTTP/1.1",
            "Host: $host",
            // Десктопный браузер: с мобильным UA youtube.com сразу уводит на m.youtube.com коротким 302.
            "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
            "Accept: */*",
            "Accept-Language: ru-RU,ru;q=0.9,en;q=0.8",
            "Connection: close",
            "",
            "",
        ).joinToString(CRLF)

        private const val CRLF = "\r\n"
        private const val MAX_REDIRECTS = 3

        /**
         * HTTPS GET через SOCKS5 (или напрямую, если [proxyPort] = null) с переходом по редиректам;
         * читает не больше [maxBytes] (ответ вместе с заголовками). Если поток оборвался или замер
         * после первых байт, возвращает то, что успело прийти, — так видно замедление.
         */
        fun httpsGet(proxyPort: Int?, host: String, path: String, maxBytes: Int, timeoutMs: Int): ByteArray {
            var currentHost = host
            var currentPath = path
            repeat(MAX_REDIRECTS + 1) {
                val bytes = fetchOnce(proxyPort, currentHost, currentPath, maxBytes, timeoutMs)
                val location = redirectTarget(bytes) ?: return bytes
                val (nextHost, nextPath) = resolveLocation(location, currentHost) ?: return bytes
                currentHost = nextHost
                currentPath = nextPath
            }
            throw java.io.IOException("too many redirects")
        }

        /** Location из ответа 301/302/303/307/308 или null. */
        internal fun redirectTarget(response: ByteArray): String? {
            val head = String(response, 0, minOf(response.size, 8192), Charsets.ISO_8859_1)
            val headers = head.substringBefore(CRLF + CRLF)
            val status = headers.substringBefore(CRLF).split(' ').getOrNull(1)?.toIntOrNull() ?: return null
            if (status !in setOf(301, 302, 303, 307, 308)) return null
            return headers.lineSequence()
                .firstOrNull { it.startsWith("location:", ignoreCase = true) }
                ?.substringAfter(':')?.trim()
        }

        /** https://host/path или /path относительно текущего узла; http:// и чужие схемы не идём. */
        internal fun resolveLocation(location: String, currentHost: String): Pair<String, String>? = when {
            location.startsWith("https://") -> {
                val rest = location.removePrefix("https://")
                val hostPart = rest.substringBefore('/').substringBefore('?')
                val pathPart = rest.removePrefix(hostPart).ifEmpty { "/" }
                hostPart.substringBefore(':') to (if (pathPart.startsWith("/")) pathPart else "/$pathPart")
            }
            location.startsWith("/") -> currentHost to location
            else -> null
        }

        private fun fetchOnce(proxyPort: Int?, host: String, path: String, maxBytes: Int, timeoutMs: Int): ByteArray {
            val raw = if (proxyPort != null) {
                Socks5.connect(proxyPort, host, 443, timeoutMs)
            } else {
                Socket().apply {
                    soTimeout = timeoutMs
                    connect(java.net.InetSocketAddress(host, 443), timeoutMs)
                }
            }
            raw.use {
                val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, host, 443, true) as SSLSocket
                tls.use {
                    tls.sslParameters = tls.sslParameters.apply { serverNames = listOf(SNIHostName(host)) }
                    tls.soTimeout = timeoutMs
                    tls.startHandshake()
                    tls.outputStream.apply {
                        write(httpRequest(host, path).toByteArray())
                        flush()
                    }
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(16 * 1024)
                    val input = tls.inputStream
                    while (out.size() < maxBytes) {
                        val n = runCatching { input.read(buffer) }.getOrElse { if (out.size() > 0) -1 else throw it }
                        if (n < 0) break
                        out.write(buffer, 0, n)
                    }
                    return out.toByteArray()
                }
            }
        }
    }
}

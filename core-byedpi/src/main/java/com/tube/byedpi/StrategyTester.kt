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
import kotlinx.coroutines.withContext
import java.net.Socket
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.coroutines.coroutineContext

/** Как проверяем узел. */
enum class ProbeKind {
    /** Полный TLS (Java) и HTTP GET: видно замедление, при котором поток замирает после ~16 КБ. */
    Https,

    /** ClientHello как у Chrome/YouTube (~1.8 КБ, Kyber): прошёл ли он DPI и ответил ли сервер. */
    ChromeHello,
}

/** Что проверяем: узел, путь, сколько байт должно прийти; [group] — для экрана (сайт, превью, видео). */
data class Probe(
    val host: String,
    val path: String,
    val minBytes: Int,
    val kind: ProbeKind = ProbeKind.Https,
    val group: ProbeGroup = ProbeGroup.of(host),
)

enum class ProbeGroup {
    Site, Thumbs, Video;

    companion object {
        fun of(host: String): ProbeGroup = when {
            "googlevideo" in host -> Video
            "ytimg" in host -> Thumbs
            else -> Site
        }
    }
}

data class ProbeResult(val probe: Probe, val ok: Boolean, val millis: Long, val bytes: Int, val error: String?)

data class StrategyScore(val strategy: Strategy, val results: List<ProbeResult>, val startError: String? = null) {
    val passed: Int get() = results.count { it.ok }
    val total: Int get() = results.size
    val avgMillis: Long get() = results.filter { it.ok }.map { it.millis }.average().takeIf { !it.isNaN() }?.toLong() ?: Long.MAX_VALUE
    val works: Boolean get() = total > 0 && passed == total
    val partial: Boolean get() = passed in 1 until total
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

    /** Одна стратегия на своём экземпляре byedpi (порт [port] должен быть свободен). */
    suspend fun testOne(strategy: Strategy, port: Int, probes: List<Probe>, blockUdp: Boolean): StrategyScore {
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

    /** Проверки через уже работающий прокси (сторож проверяет текущую стратегию, не останавливая VPN). */
    suspend fun checkThrough(port: Int, probes: List<Probe>): List<ProbeResult> = coroutineScope {
        probes.map { probe -> async(Dispatchers.IO) { probe(port, probe) } }.awaitAll()
    }

    private val chromeTemplate: ByteArray by lazy {
        context.assets.open(CHROME_TEMPLATE).use { it.readBytes() }
    }

    private suspend fun probe(port: Int?, probe: Probe): ProbeResult {
        coroutineContext.ensureActive()
        val started = System.nanoTime()
        val target = maxOf(probe.minBytes, 1)
        return try {
            val bytes = when (probe.kind) {
                ProbeKind.Https -> httpsGet(port, probe.host, probe.path, target, timeoutMs).size
                ProbeKind.ChromeHello -> chromeHandshake(port, probe.host, ChromeHello.build(chromeTemplate, probe.host), target, timeoutMs)
            }
            val ok = bytes >= target
            ProbeResult(probe, ok, (System.nanoTime() - started) / 1_000_000, bytes, if (ok) null else "short read $bytes")
        } catch (e: Exception) {
            ProbeResult(probe, false, (System.nanoTime() - started) / 1_000_000, 0, e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""))
        }
    }

    /**
     * Настоящий видеоузел этой сети (rrN---sn-….googlevideo.com) — именно с него YouTube качает видео.
     * Ссылка есть на странице просмотра; страница читается через первую сработавшую стратегию из [candidates].
     */
    suspend fun discoverVideoHost(candidates: List<Strategy>, blockUdp: Boolean): String? = withContext(Dispatchers.IO) {
        for (strategy in candidates.take(DISCOVERY_ATTEMPTS)) {
            ensureActive()
            val argv = (StrategyArgs.parse(strategy.args) as? StrategyArgs.Result.Ok)?.argv ?: continue
            val proxy = runCatching { ByeDpiProcess.start(context, DISCOVERY_PORT, argv, blockUdp, fakeSni) }.getOrNull() ?: continue
            try {
                runCatching { findVideoHost(DISCOVERY_PORT, timeoutMs * 2) }.getOrNull()?.let { return@withContext it }
            } finally {
                proxy.stop()
            }
        }
        null
    }

    /** Отдаёт ли DNS провайдера адрес youtube.com (с февраля 2026 многие — нет; byedpi тогда ходит через DoH). */
    fun systemDnsResolves(host: String = "www.youtube.com"): Boolean = runCatching {
        java.net.InetAddress.getAllByName(host).any { !it.isAnyLocalAddress && !it.isLoopbackAddress }
    }.getOrDefault(false)

    /**
     * Те же проверки без прокси: видно, блокирует ли сеть YouTube вообще
     * (приложение не входит в собственный VPN, поэтому соединения идут напрямую).
     */
    suspend fun baseline(probes: List<Probe>): StrategyScore = coroutineScope {
        val results = probes.map { probe -> async(Dispatchers.IO) { probe(null, probe) } }.awaitAll()
        StrategyScore(Strategy(BASELINE_ID, "DIRECT", "", "Без обхода", "No bypass"), results)
    }

    companion object {
        const val BASELINE_ID = "direct"

        private const val CHROME_TEMPLATE = "fake/tls_clienthello_vk_com_kyber.bin"
        private const val DISCOVERY_PORT = 11090
        private const val DISCOVERY_ATTEMPTS = 8
        private const val FALLBACK_VIDEO_HOST = "redirector.googlevideo.com"

        /** Ответ сервера на ClientHello: ServerHello + сертификаты — несколько КБ. Меньше — DPI оборвал. */
        private const val CHROME_MIN_BYTES = 2000

        /**
         * Проверки «как у приложения YouTube»: страница, превью и видеоузел, каждый — и полным TLS
         * с загрузкой (замедление), и ClientHello как у Chrome (именно его видит DPI в приложении).
         * [videoHost] — настоящий узел сети; без него — redirector (тот же SNI *.googlevideo.com).
         */
        fun defaultProbes(videoHost: String? = null): List<Probe> {
            val video = videoHost ?: FALLBACK_VIDEO_HOST
            return listOf(
                Probe("www.youtube.com", "/", minBytes = 64 * 1024),
                Probe("www.youtube.com", "", minBytes = CHROME_MIN_BYTES, kind = ProbeKind.ChromeHello),
                Probe("i.ytimg.com", "/vi/jNQXAC9IVRw/hqdefault.jpg", minBytes = 8 * 1024),
                Probe("i.ytimg.com", "", minBytes = CHROME_MIN_BYTES, kind = ProbeKind.ChromeHello),
                Probe(video, if (videoHost != null) "/generate_204" else "/report_mapping?di=no", minBytes = 12),
                Probe(video, "", minBytes = CHROME_MIN_BYTES, kind = ProbeKind.ChromeHello),
            )
        }

        /**
         * Шлёт ClientHello как у Chrome и считает, сколько пришло в ответ. Ответ должен начинаться с записи
         * Handshake (0x16); тревога (0x15) или тишина — значит, DPI или сервер оборвали соединение.
         */
        fun chromeHandshake(proxyPort: Int?, host: String, hello: ByteArray, minBytes: Int, timeoutMs: Int): Int {
            val socket = if (proxyPort != null) {
                Socks5.connect(proxyPort, host, 443, timeoutMs)
            } else {
                Socket().apply {
                    soTimeout = timeoutMs
                    connect(java.net.InetSocketAddress(host, 443), timeoutMs)
                }
            }
            socket.use {
                it.soTimeout = timeoutMs
                it.getOutputStream().apply {
                    write(hello)
                    flush()
                }
                val input = it.getInputStream()
                val buffer = ByteArray(16 * 1024)
                var total = 0
                var first = -1
                while (total < minBytes) {
                    val n = runCatching { input.read(buffer) }.getOrElse { e -> if (total > 0) -1 else throw e }
                    if (n < 0) break
                    if (first < 0 && n > 0) first = buffer[0].toInt() and 0xFF
                    total += n
                }
                if (first == 0x15) throw java.io.IOException("TLS alert")
                if (first != 0x16 && total > 0) throw java.io.IOException("not TLS")
                return total
            }
        }

        private val VideoHostPattern = Regex("""rr\d{1,2}---sn-[a-z0-9-]+\.googlevideo\.com""")

        /** Читает страницу просмотра потоком, пока не встретится ссылка на видеоузел. */
        fun findVideoHost(proxyPort: Int?, timeoutMs: Int): String? {
            val raw = if (proxyPort != null) Socks5.connect(proxyPort, "www.youtube.com", 443, timeoutMs) else Socket("www.youtube.com", 443)
            raw.use {
                val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, "www.youtube.com", 443, true) as SSLSocket
                tls.use {
                    tls.soTimeout = timeoutMs
                    tls.startHandshake()
                    tls.outputStream.apply {
                        write(httpRequest("www.youtube.com", WATCH_PATH).toByteArray())
                        flush()
                    }
                    val input = tls.inputStream
                    val buffer = ByteArray(32 * 1024)
                    var tail = ""
                    var read = 0
                    while (read < WATCH_MAX_BYTES) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        read += n
                        val chunk = tail + String(buffer, 0, n, Charsets.ISO_8859_1)
                        VideoHostPattern.find(chunk)?.let { m -> return m.value }
                        tail = chunk.takeLast(80)
                    }
                    return null
                }
            }
        }

        private const val WATCH_PATH = "/watch?v=jNQXAC9IVRw"
        private const val WATCH_MAX_BYTES = 2_000_000

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

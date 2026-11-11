package com.tube.byedpi

import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection
import kotlin.concurrent.thread

/**
 * DNS-сервер на 127.0.0.1 для процессов byedpi: принимает обычные DNS-запросы и пересылает их
 * через DNS-over-HTTPS. С февраля 2026 youtube.com не резолвится через национальную систему DNS,
 * которой пользуется большинство провайдеров, а DoH по IP-адресу (без SNI) ТСПУ не видит.
 * byedpi обращается сюда через подменённый getaddrinfo (tube_dns.c, переменная TUBE_DNS).
 */
class LocalDns private constructor(private val socket: DatagramSocket) {

    val port: Int get() = socket.localPort

    /** Значение переменной окружения TUBE_DNS для byedpi. */
    val address: String get() = "127.0.0.1:$port"

    private val workers = Executors.newFixedThreadPool(8) { r -> Thread(r, "tube-dns").apply { isDaemon = true } }
    private class Entry(val freshUntil: Long, val staleUntil: Long, val body: ByteArray)

    private val cache = ConcurrentHashMap<String, Entry>()
    private val refreshing = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var preferred = 0

    init {
        thread(name = "tube-dns-listen", isDaemon = true) {
            val buffer = ByteArray(1500)
            while (!socket.isClosed) {
                val packet = DatagramPacket(buffer, buffer.size)
                runCatching { socket.receive(packet) }.onFailure { if (socket.isClosed) return@thread }
                val query = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                val from = packet.socketAddress
                workers.execute {
                    val response = runCatching { resolve(query) }.getOrNull() ?: return@execute
                    runCatching { socket.send(DatagramPacket(response, response.size, from)) }
                }
            }
        }
    }

    /**
     * Ответ на DNS-запрос (wire format) с тем же id; null — ни один DoH-сервер не ответил.
     *
     * byedpi резолвит имена синхронно, в своём единственном потоке: пока ждём DoH, стоят все его соединения.
     * Поэтому устаревший ответ отдаётся сразу и обновляется в фоне, а адреса YouTube запрашиваются заранее ([prefetch]).
     */
    fun resolve(query: ByteArray): ByteArray? {
        if (query.size < 12) return null
        val key = questionKey(query)
        val now = System.currentTimeMillis()
        val entry = cache[key]
        val body = when {
            entry != null && entry.freshUntil > now -> entry.body
            entry != null && entry.staleUntil > now -> {
                refreshLater(key, query)
                entry.body
            }
            else -> fetchAndStore(key, query)
        } ?: return null
        return body.copyOf().also {
            it[0] = query[0]
            it[1] = query[1]
        }
    }

    /** Заранее узнать адреса (A) — при старте VPN, чтобы первые соединения YouTube не ждали DoH. */
    fun prefetch(hosts: Collection<String>) {
        hosts.forEach { host ->
            val query = runCatching { aQuery(host) }.getOrNull() ?: return@forEach
            val entry = cache[questionKey(query)]
            if (entry == null || entry.freshUntil <= System.currentTimeMillis()) refreshLater(questionKey(query), query)
        }
    }

    private fun refreshLater(key: String, query: ByteArray) {
        if (!refreshing.add(key)) return
        workers.execute {
            try {
                fetchAndStore(key, query)
            } finally {
                refreshing.remove(key)
            }
        }
    }

    private fun fetchAndStore(key: String, query: ByteArray): ByteArray? =
        fetch(query.copyOf().also { it[0] = 0; it[1] = 0 })?.also { response ->
            // Кэшируем только успешные ответы (RCODE = 0).
            if (response.size >= 12 && response[3].toInt() and 0x0F == 0) {
                val now = System.currentTimeMillis()
                cache[key] = Entry(now + FRESH_MS, now + STALE_MS, response)
            }
        }

    private fun fetch(query: ByteArray): ByteArray? {
        val start = preferred
        for (i in Upstreams.indices) {
            val index = (start + i) % Upstreams.size
            val response = runCatching { post(Upstreams[index], query) }.getOrNull()
            if (response != null) {
                preferred = index
                return response
            }
        }
        return null
    }

    private fun post(url: String, query: ByteArray): ByteArray {
        val connection = URL(url).openConnection() as HttpsURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/dns-message")
            connection.setRequestProperty("Accept", "application/dns-message")
            connection.outputStream.use { it.write(query) }
            if (connection.responseCode != 200) error("HTTP ${connection.responseCode}")
            // Поток дочитан и закрыт без disconnect(): соединение остаётся в пуле keep-alive,
            // и следующий запрос не платит за новое TCP+TLS-рукопожатие.
            return connection.inputStream.use { input ->
                ByteArrayOutputStream().also { input.copyTo(it) }.toByteArray()
            }
        } catch (e: Exception) {
            connection.disconnect()
            throw e
        }
    }

    companion object {
        /** DoH по IP: сертификаты Google и Cloudflare содержат эти адреса, SNI в ClientHello нет. */
        val Upstreams = listOf(
            "https://8.8.8.8/dns-query",
            "https://1.1.1.1/dns-query",
            "https://8.8.4.4/dns-query",
            "https://1.0.0.1/dns-query",
        )
        private const val TIMEOUT_MS = 3500
        private const val FRESH_MS = 10 * 60_000L
        private const val STALE_MS = 24 * 60 * 60_000L

        /** Узлы, к которым YouTube подключается первым делом. */
        val YouTubeHosts = listOf(
            "www.youtube.com", "m.youtube.com", "youtube.com", "youtubei.googleapis.com", "youtube.googleapis.com",
            "www.googleapis.com", "i.ytimg.com", "i9.ytimg.com", "s.ytimg.com", "yt3.ggpht.com", "yt4.ggpht.com",
            "yt3.googleusercontent.com", "redirector.googlevideo.com", "play.googleapis.com", "jnn-pa.googleapis.com",
            "suggestqueries-clients6.youtube.com", "www.google.com", "accounts.google.com",
        )

        /** Узлы того же кластера, что и [videoHost] (rr1…rr16---sn-xxx): по ним идёт видео. */
        fun videoCluster(videoHost: String?): List<String> {
            val suffix = videoHost?.substringAfter("---", "")?.takeIf { it.isNotEmpty() } ?: return emptyList()
            return (1..16).map { "rr$it---$suffix" }
        }

        /** DNS-запрос A для [host] (id = 0, RD). */
        internal fun aQuery(host: String): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(byteArrayOf(0, 0, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0))
            host.trimEnd('.').split('.').forEach { label ->
                val bytes = label.toByteArray(Charsets.US_ASCII)
                require(bytes.size in 1..63)
                out.write(bytes.size)
                out.write(bytes)
            }
            out.write(byteArrayOf(0, 0, 1, 0, 1))
            return out.toByteArray()
        }

        @Volatile private var instance: LocalDns? = null

        /** Один сервер на процесс: им пользуются и VPN, и тестер стратегий. */
        fun shared(): LocalDns = instance ?: synchronized(this) {
            instance ?: LocalDns(DatagramSocket(0, InetAddress.getByName("127.0.0.1"))).also { instance = it }
        }

        /** Секция вопроса (имя + тип + класс) без id и флагов — ключ кэша. */
        internal fun questionKey(query: ByteArray): String =
            query.copyOfRange(12, query.size).joinToString("") { "%02x".format(it) }
    }
}

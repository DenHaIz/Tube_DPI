package com.tube.byedpi

import android.content.Context
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.ArrayDeque
import kotlin.concurrent.thread

/**
 * Один экземпляр SOCKS5-прокси byedpi в отдельном процессе (`libbyedpi.so` из nativeLibraryDir).
 * Процесс приложения не входит в VPN, поэтому исходящие соединения прокси идут напрямую.
 */
class ByeDpiProcess private constructor(
    val port: Int,
    private val process: Process,
) {
    private val log = ArrayDeque<String>()

    init {
        thread(name = "byedpi-$port-log", isDaemon = true) {
            runCatching {
                process.inputStream.bufferedReader().forEachLine { line ->
                    synchronized(log) {
                        log.addLast(line)
                        while (log.size > LOG_LINES) log.removeFirst()
                    }
                }
            }
        }
    }

    val isAlive: Boolean get() = process.isAlive

    /** Последние строки stderr/stdout прокси — для экрана диагностики. */
    fun logTail(): List<String> = synchronized(log) { log.toList() }

    /** SIGTERM → byedpi закрывает слушающий сокет и выходит; если завис — SIGKILL. */
    fun stop() {
        if (!process.isAlive) return
        process.destroy()
        val deadline = System.currentTimeMillis() + 1500
        while (process.isAlive && System.currentTimeMillis() < deadline) Thread.sleep(20)
        if (process.isAlive) process.destroyForcibly()
    }

    companion object {
        private const val LOG_LINES = 200

        fun executable(context: Context): File = File(context.applicationInfo.nativeLibraryDir, "libbyedpi.so")

        /**
         * Запускает прокси и ждёт, пока он начнёт принимать соединения.
         * Бросает [IllegalStateException] с выводом byedpi, если процесс сразу завершился (неверные аргументы).
         */
        fun start(
            context: Context,
            port: Int,
            strategy: List<String>,
            blockUdp: Boolean,
            fakeSni: String = Strategies.FakeSniOptions.first(),
            timeoutMs: Long = 3000,
        ): ByeDpiProcess {
            val args = resolveFakeFiles(context, strategy).map { it.replace(Strategies.SNI, fakeSni) }
            val command = listOf(executable(context).absolutePath) + StrategyArgs.commandLine(port, args, blockUdp)
            val builder = ProcessBuilder(command).redirectErrorStream(true)
            // Имена сайтов byedpi резолвит через DoH (см. LocalDns и tube_dns.c), а не через DNS провайдера.
            builder.environment()["TUBE_DNS"] = LocalDns.shared().address
            val process = builder.start()
            val proxy = ByeDpiProcess(port, process)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (!process.isAlive) {
                    Thread.sleep(50) // дать потоку логов дочитать вывод
                    throw IllegalStateException(proxy.logTail().joinToString("\n").ifBlank { "exit ${process.exitValue()}" })
                }
                if (isListening(port)) return proxy
                Thread.sleep(30)
            }
            proxy.stop()
            throw IllegalStateException("byedpi did not start listening on $port")
        }

        /** Префикс встроенных фейковых пакетов в аргументах: `-l @fake/tls_clienthello_www_google_com.bin`. */
        const val FAKE_PREFIX = "@fake/"

        /**
         * Подставляет вместо `@fake/<имя>` путь к файлу из assets/fake (фейковые ClientHello/QUIC из zapret):
         * byedpi читает фейк из файла, поэтому ресурс один раз копируется в filesDir.
         */
        private fun resolveFakeFiles(context: Context, argv: List<String>): List<String> = argv.map { arg ->
            val at = arg.indexOf(FAKE_PREFIX)
            if (at < 0) return@map arg
            val name = arg.substring(at + FAKE_PREFIX.length)
            val file = File(context.filesDir, "fake/$name")
            synchronized(ByeDpiProcess) {
                if (!file.exists()) {
                    file.parentFile?.mkdirs()
                    context.assets.open("fake/$name").use { input -> file.outputStream().use { input.copyTo(it) } }
                }
            }
            arg.substring(0, at) + file.absolutePath
        }

        private fun isListening(port: Int): Boolean = runCatching {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 100) }
        }.isSuccess
    }
}

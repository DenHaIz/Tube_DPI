package com.tube.dpi.strategy

import android.content.Context
import com.tube.byedpi.Strategies
import com.tube.byedpi.Strategy
import com.tube.byedpi.StrategyScore
import com.tube.byedpi.StrategyTester
import com.tube.byedpi.TestEvent
import com.tube.dpi.data.NetworkMonitor
import com.tube.dpi.data.ResultsStore
import com.tube.dpi.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Ход подбора (общий для экрана и сторожа). */
data class TestProgress(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    /** Подбор запустил сторож, а не пользователь. */
    val background: Boolean = false,
)

/**
 * Подбор стратегии для текущей сети. Результаты сразу пишутся в [ResultsStore] по ключу сети,
 * поэтому экран показывает их и после перезапуска, а сторож берёт из них замену.
 */
class AutoPick(
    private val context: Context,
    private val settings: SettingsRepository,
    private val results: ResultsStore,
    private val network: NetworkMonitor,
    private val scope: CoroutineScope,
) {
    private val _progress = MutableStateFlow(TestProgress())
    val progress: StateFlow<TestProgress> = _progress.asStateFlow()

    /** Строки журнала последнего подбора: итог по каждому узлу. */
    val log = MutableStateFlow<List<String>>(emptyList())

    private var job: Job? = null

    val running: Boolean get() = _progress.value.running

    fun start(background: Boolean = false): Job? {
        if (running) return job
        return scope.launch { runFull(background) }.also { job = it }
    }

    fun cancel() {
        job?.cancel()
        _progress.value = _progress.value.copy(running = false)
    }

    /** Полный перебор: все встроенные, набор ByeByeDPI и своя стратегия. Возвращает рейтинг. */
    suspend fun runFull(background: Boolean = false): List<StrategyScore> {
        network.refresh()
        val key = network.current.value?.key ?: return emptyList()
        val custom = settings.customArgs.first().takeIf { it.isNotBlank() }
        val candidates = Strategies.all + listOfNotNull(custom?.let { Strategy(Strategies.CUSTOM_ID, "CUSTOM", it, "Своя стратегия", "Custom strategy") })
        val tester = tester()
        val blockUdp = settings.blockQuic.first()
        _progress.value = TestProgress(running = true, total = candidates.size, background = background)
        try {
            val systemDns = withContext(Dispatchers.IO) { tester.systemDnsResolves() }
            // Настоящий видеоузел сети: страница просмотра читается через текущую и ранее рабочие стратегии.
            val saved = results.of(key)
            val current = settings.strategy.first()
            val discoveryOrder = (listOf(current) + saved?.working?.map { it.strategy }.orEmpty() + Strategies.builtIn).distinctBy { it.id }
            val videoHost = tester.discoverVideoHost(discoveryOrder, blockUdp) ?: saved?.videoHost
            val probes = StrategyTester.defaultProbes(videoHost)
            val baseline = tester.baseline(probes)
            // Новый подбор заменяет старые результаты этой сети, выбор стратегии сохраняется.
            results.update(key) { it.copy(testedAt = System.currentTimeMillis(), systemDns = systemDns, baseline = baseline, scores = emptyMap(), videoHost = videoHost) }
            log.value = listOf("network: $key", "system DNS resolves www.youtube.com: $systemDns", "video host: ${videoHost ?: "not found"}", describe(baseline))
            var ranking = emptyList<StrategyScore>()
            tester.run(candidates, probes, blockUdp).collect { event ->
                when (event) {
                    is TestEvent.Progress -> {
                        results.update(key) { it.copy(scores = it.scores + (event.score.strategy.id to event.score)) }
                        log.value = log.value + describe(event.score)
                        _progress.value = _progress.value.copy(done = event.done)
                    }
                    is TestEvent.Finished -> ranking = event.ranking
                }
            }
            return ranking
        } finally {
            _progress.value = _progress.value.copy(running = false)
        }
    }

    /** Проверка одной стратегии на свободном порту; результат тоже сохраняется. */
    suspend fun testOne(strategy: Strategy): StrategyScore {
        val videoHost = results.of(network.current.value?.key)?.videoHost
        val score = tester().testOne(strategy, SINGLE_PORT, StrategyTester.defaultProbes(videoHost), settings.blockQuic.first())
        network.current.value?.key?.let { key -> results.update(key) { it.copy(scores = it.scores + (strategy.id to score)) } }
        return score
    }

    private suspend fun tester() = StrategyTester(context, fakeSni = settings.fakeSni.first())

    companion object {
        private const val SINGLE_PORT = 11100

        /** Строка журнала: стратегия и итог по каждому узлу — видно, что именно режет провайдер. */
        fun describe(score: StrategyScore): String = buildString {
            append(score.strategy.name).append(": ")
            if (score.startError != null) {
                append("start error: ").append(score.startError)
                return@buildString
            }
            score.results.joinTo(this, "; ") { r ->
                val kind = if (r.probe.kind == com.tube.byedpi.ProbeKind.ChromeHello) " [chrome]" else ""
                r.probe.host + kind + " " + if (r.ok) "ok ${r.millis}ms" else "FAIL ${r.bytes}B ${r.error.orEmpty()}"
            }
        }
    }
}

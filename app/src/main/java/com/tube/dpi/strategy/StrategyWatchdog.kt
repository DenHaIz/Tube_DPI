package com.tube.dpi.strategy

import android.content.Context
import com.tube.byedpi.Strategy
import com.tube.byedpi.StrategyTester
import com.tube.dpi.AppContainer
import com.tube.dpi.vpn.TunnelState
import com.tube.dpi.vpn.WatchdogStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Почему сторож сменил стратегию. */
enum class SwitchReason { Failed, NetworkChanged }

/**
 * Сторож работающего обхода:
 * - раз в [CHECK_INTERVAL_MS] проверяет узлы YouTube через работающий прокси (VPN не останавливается);
 * - два провала подряд при живом интернете → ищет замену среди рабочих в этой сети, а если таких нет —
 *   запускает подбор в фоне (не чаще раза в час на сеть);
 * - при смене сети сразу подставляет стратегию, запомненную для новой сети.
 * Смена — через [onSwitch] (сервис перезапускает туннель, этот цикл при этом завершается).
 */
class StrategyWatchdog(
    private val context: Context,
    private val container: AppContainer,
    private val proxyPort: Int,
    private val currentId: String,
    private val onSwitch: suspend (Strategy, SwitchReason) -> Unit,
) {
    suspend fun run() = coroutineScope {
        val network = container.network
        val networkChanged = Channel<Unit>(Channel.CONFLATED)
        launch { network.current.drop(1).collect { networkChanged.trySend(Unit) } }
        var lastKey = network.current.value?.key
        var failures = 0
        var interval = FIRST_CHECK_MS
        while (isActive) {
            withTimeoutOrNull(interval) { networkChanged.receive() }
            interval = CHECK_INTERVAL_MS
            if (!container.settings.watchdog.first()) continue

            val key = network.current.value?.key ?: continue
            if (key != lastKey) {
                lastKey = key
                failures = 0
                val chosen = container.results.of(key)?.chosen
                val strategy = chosen?.let { container.resolveStrategy(it) }
                if (strategy != null && strategy.id != currentId) {
                    onSwitch(strategy, SwitchReason.NetworkChanged)
                    return@coroutineScope
                }
            }

            val ok = checkCurrent(container.results.of(key)?.videoHost)
            TunnelState.setWatchdog(WatchdogStatus(System.currentTimeMillis(), ok))
            if (ok) {
                failures = 0
                // Рабочая стратегия запоминается для сети, если там ещё ничего не выбрано.
                if (container.results.of(key)?.chosen == null) container.results.update(key) { it.copy(chosen = currentId) }
                continue
            }
            if (!internetAvailable()) continue
            failures++
            if (failures < 2) {
                interval = RECHECK_MS
                continue
            }
            val replacement = findReplacement(key) ?: continue
            onSwitch(replacement, SwitchReason.Failed)
            return@coroutineScope
        }
    }

    private suspend fun checkCurrent(videoHost: String?): Boolean {
        val tester = StrategyTester(context)
        return tester.checkThrough(proxyPort, StrategyTester.defaultProbes(videoHost)).all { it.ok }
    }

    /** Есть ли интернет вообще: без него менять стратегию бессмысленно. */
    private suspend fun internetAvailable(): Boolean = coroutineScope {
        ControlProbes.map { (host, path) ->
            async(Dispatchers.IO) { runCatching { StrategyTester.httpsGet(null, host, path, 1, 5000).isNotEmpty() }.getOrDefault(false) }
        }.awaitAll().any { it }
    }

    private suspend fun findReplacement(key: String): Strategy? {
        val autoPick = container.autoPick
        val saved = container.results.of(key)
        saved?.working?.map { it.strategy }?.filter { it.id != currentId }?.take(MAX_CANDIDATES)?.forEach { candidate ->
            if (autoPick.testOne(candidate).works) return candidate
        }
        val stale = saved == null || System.currentTimeMillis() - saved.testedAt > FULL_RETEST_MS
        if (!stale || autoPick.running) return null
        return autoPick.runFull(background = true).firstOrNull { it.works && it.strategy.id != currentId }?.strategy
    }

    companion object {
        private const val FIRST_CHECK_MS = 60_000L
        private const val CHECK_INTERVAL_MS = 10 * 60_000L
        private const val RECHECK_MS = 30_000L
        private const val FULL_RETEST_MS = 60 * 60_000L
        private const val MAX_CANDIDATES = 6
        private val ControlProbes = listOf("ya.ru" to "/", "www.gstatic.com" to "/generate_204")
    }
}

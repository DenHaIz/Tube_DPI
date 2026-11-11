package com.tube.dpi.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tube.byedpi.Strategies
import com.tube.byedpi.Strategy
import com.tube.byedpi.StrategyArgs
import com.tube.byedpi.StrategyScore
import com.tube.byedpi.StrategyTester
import com.tube.byedpi.TestEvent
import com.tube.dpi.TubeApp
import com.tube.dpi.apps.InstalledApp
import com.tube.dpi.apps.TargetApps
import com.tube.dpi.autostart.AutoStartService
import com.tube.dpi.data.ThemeMode
import com.tube.dpi.vpn.TunnelState
import com.tube.dpi.vpn.VpnController
import com.tube.ui.i18n.AppLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Ход автоподбора стратегии. */
data class TestProgress(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    /** Отдаёт ли DNS провайдера адрес youtube.com; null — ещё не проверено. */
    val systemDns: Boolean? = null,
    /** Те же проверки без обхода: блокирует ли сеть YouTube вообще. */
    val baseline: StrategyScore? = null,
    val ranking: List<StrategyScore> = emptyList(),
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as TubeApp).container
    private val settings = container.settings
    private val context get() = getApplication<Application>()

    val status = TunnelState.status
    val traffic = TunnelState.traffic
    val log = TunnelState.log

    val strategy: StateFlow<Strategy> = settings.strategy.stateIn(viewModelScope, SharingStarted.Eagerly, Strategies.default)
    val strategyId: StateFlow<String> = settings.strategyId.stateIn(viewModelScope, SharingStarted.Eagerly, Strategies.default.id)
    val customArgs: StateFlow<String> = settings.customArgs.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val blockQuic: StateFlow<Boolean> = settings.blockQuic.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val fakeSni: StateFlow<String> = settings.fakeSni.stateIn(viewModelScope, SharingStarted.Eagerly, Strategies.FakeSniOptions.first())
    val stopDelay: StateFlow<Int> = settings.stopDelayMinutes.stateIn(viewModelScope, SharingStarted.Eagerly, 5)
    val themeMode: StateFlow<ThemeMode> = settings.themeMode.stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.System)
    val dynamicColors: StateFlow<Boolean> = settings.dynamicColors.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val language: StateFlow<AppLanguage> = settings.language.stateIn(viewModelScope, SharingStarted.Eagerly, AppLanguage.Russian)
    val testResults: StateFlow<Map<String, StrategyScore>> = container.testResults
    val testLog: StateFlow<List<String>> = container.testLog

    private val _installed = MutableStateFlow<List<InstalledApp>?>(null)
    /** Приложения с доступом в интернет; null — список ещё загружается. */
    val installed: StateFlow<List<InstalledApp>?> = _installed.asStateFlow()

    private val _defaults = MutableStateFlow<Set<String>>(emptySet())

    /** Выбранные пакеты (по умолчанию — найденные клиенты YouTube). */
    val targets: StateFlow<Set<String>> = combine(settings.targets, _defaults) { saved, defaults -> saved ?: defaults }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    private val _clients = MutableStateFlow<List<InstalledApp>>(emptyList())
    /** Клиенты YouTube среди выбранных, которые можно открыть кнопкой. */
    val clients: StateFlow<List<InstalledApp>> = _clients.asStateFlow()

    val lastClient: StateFlow<String?> = settings.lastClient.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _autostartEnabled = MutableStateFlow(false)
    val autostartEnabled: StateFlow<Boolean> = _autostartEnabled.asStateFlow()

    private val _test = MutableStateFlow(TestProgress())
    val test: StateFlow<TestProgress> = _test.asStateFlow()
    private var testJob: Job? = null

    init {
        viewModelScope.launch {
            targets.map { it }.collect { refreshClients(it) }
        }
    }

    /** При каждом возвращении на экран: службу могли включить в настройках, приложения — поставить или удалить. */
    fun refresh() {
        _autostartEnabled.value = AutoStartService.isEnabled(context)
        viewModelScope.launch {
            val (defaults, apps) = withContext(Dispatchers.IO) { TargetApps.defaultTargets(context) to TargetApps.installed(context) }
            _defaults.value = defaults
            _installed.value = apps
            refreshClients(targets.value)
        }
    }

    private suspend fun refreshClients(targets: Set<String>) {
        _clients.value = withContext(Dispatchers.IO) { TargetApps.launchableClients(context, targets) }
    }

    fun toggleTunnel() = VpnController.toggle(context)

    fun startTunnel() = VpnController.start(context)

    fun selectStrategy(id: String) = viewModelScope.launch {
        settings.setStrategy(id)
        VpnController.restartIfRunning(context)
    }

    /** null — строка принята; иначе — опция, на которой споткнулся разбор. */
    fun saveCustomArgs(args: String): String? {
        val parsed = StrategyArgs.parse(args)
        if (parsed is StrategyArgs.Result.Error) return parsed.message
        viewModelScope.launch {
            settings.setCustomArgs(args.trim())
            VpnController.restartIfRunning(context)
        }
        return null
    }

    fun setTarget(packageName: String, selected: Boolean) = viewModelScope.launch {
        val next = if (selected) targets.value + packageName else targets.value - packageName
        settings.setTargets(next)
        VpnController.restartIfRunning(context)
    }

    fun setBlockQuic(enabled: Boolean) = viewModelScope.launch {
        settings.setBlockQuic(enabled)
        VpnController.restartIfRunning(context)
    }

    fun setFakeSni(sni: String) = viewModelScope.launch {
        settings.setFakeSni(sni)
        container.testResults.value = emptyMap()
        VpnController.restartIfRunning(context)
    }

    fun setStopDelay(minutes: Int) = viewModelScope.launch { settings.setStopDelayMinutes(minutes) }

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { settings.setThemeMode(mode) }

    fun setDynamicColors(enabled: Boolean) = viewModelScope.launch { settings.setDynamicColors(enabled) }

    fun setLanguage(language: AppLanguage) = viewModelScope.launch { settings.setLanguage(language) }

    fun rememberClient(packageName: String) = viewModelScope.launch { settings.setLastClient(packageName) }

    /** Перебор всех встроенных стратегий (и своей, если задана) на реальных узлах YouTube. */
    fun runTest() {
        if (_test.value.running) return
        val candidates = Strategies.all + listOfNotNull(
            customArgs.value.takeIf { it.isNotBlank() }?.let { Strategy(Strategies.CUSTOM_ID, "CUSTOM", it, "", "") },
        )
        val tester = StrategyTester(context, fakeSni = fakeSni.value)
        _test.value = TestProgress(running = true, total = candidates.size)
        testJob = viewModelScope.launch {
            val probes = StrategyTester.defaultProbes()
            val systemDns = withContext(Dispatchers.IO) { tester.systemDnsResolves() }
            val baseline = tester.baseline(probes)
            _test.value = _test.value.copy(baseline = baseline, systemDns = systemDns)
            container.testLog.value = listOf("system DNS resolves www.youtube.com: $systemDns", "fake SNI: ${fakeSni.value}", describe(baseline))
            val blockUdp = blockQuic.value
            tester.run(candidates, probes, blockUdp).collect { event ->
                when (event) {
                    is TestEvent.Progress -> {
                        container.testResults.value = container.testResults.value + (event.score.strategy.id to event.score)
                        container.testLog.value = container.testLog.value + describe(event.score)
                        _test.value = _test.value.copy(
                            done = event.done,
                            ranking = StrategyTester.rank(_test.value.ranking + event.score),
                        )
                    }
                    is TestEvent.Finished -> _test.value = _test.value.copy(running = false, ranking = event.ranking)
                }
            }
        }
    }

    /** Строка журнала: стратегия и итог по каждому узлу — видно, что именно режет провайдер. */
    private fun describe(score: StrategyScore): String = buildString {
        append(score.strategy.name).append(": ")
        if (score.startError != null) {
            append("start error: ").append(score.startError)
            return@buildString
        }
        score.results.joinTo(this, "; ") { r ->
            r.probe.host + " " + if (r.ok) "ok ${r.millis}ms" else "FAIL ${r.bytes}B ${r.error.orEmpty()}"
        }
    }

    fun cancelTest() {
        testJob?.cancel()
        _test.value = _test.value.copy(running = false)
    }
}

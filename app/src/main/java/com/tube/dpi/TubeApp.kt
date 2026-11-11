package com.tube.dpi

import android.app.Application
import android.content.Context
import com.tube.byedpi.StrategyScore
import com.tube.dpi.data.SettingsRepository
import com.tube.dpi.data.ThemeMode
import com.tube.dpi.tile.TubeTileService
import com.tube.dpi.vpn.TunnelState
import com.tube.ui.i18n.AppLanguage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TubeApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        // Плитка в шторке перерисовывается при каждом изменении состояния туннеля.
        container.scope.launch { TunnelState.status.drop(1).collect { TubeTileService.refresh(this@TubeApp) } }
    }
}

/** Ручной DI: все зависимости создаются лениво, при первом обращении. */
class AppContainer(context: Context) {
    val settings: SettingsRepository by lazy { SettingsRepository(context) }

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Язык, тема и цвета — горячие потоки на весь процесс: экран получает их сразу, без пустого кадра.
     * null — ещё не прочитаны.
     */
    val language: StateFlow<AppLanguage?> by lazy { settings.language.stateIn(scope, SharingStarted.Eagerly, null) }
    val themeMode: StateFlow<ThemeMode?> by lazy { settings.themeMode.stateIn(scope, SharingStarted.Eagerly, null) }
    val dynamicColors: StateFlow<Boolean?> by lazy { settings.dynamicColors.stateIn(scope, SharingStarted.Eagerly, null) }

    /** Результаты последнего автоподбора по id стратегии — до перезапуска процесса. */
    val testResults = MutableStateFlow<Map<String, StrategyScore>>(emptyMap())

    /** Подробности последнего автоподбора (ошибки по каждому узлу) — для журнала. */
    val testLog = MutableStateFlow<List<String>>(emptyList())
}

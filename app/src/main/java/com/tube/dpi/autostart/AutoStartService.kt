package com.tube.dpi.autostart

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import com.tube.dpi.TubeApp
import com.tube.dpi.apps.TargetApps
import com.tube.dpi.vpn.TunnelState
import com.tube.dpi.vpn.TunnelStatus
import com.tube.dpi.vpn.VpnController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Автозапуск обхода вместе с YouTube. Служба получает только события смены активного окна
 * (имя пакета); содержимое экрана не запрашивается (canRetrieveWindowContent=false).
 */
class AutoStartService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var policy: AutoStartPolicy
    private lateinit var targets: StateFlow<Set<String>>
    private lateinit var stopDelayMs: StateFlow<Long?>
    private var transient: Set<String> = emptySet()

    override fun onServiceConnected() {
        super.onServiceConnected()
        val settings = (application as TubeApp).container.settings
        policy = AutoStartPolicy(packageName)
        targets = settings.targets.map { it ?: TargetApps.defaultTargets(this) }
            .stateIn(scope, SharingStarted.Eagerly, emptySet())
        stopDelayMs = settings.stopDelayMinutes.map { minutes -> if (minutes <= 0) null else minutes * 60_000L }
            .stateIn(scope, SharingStarted.Eagerly, 5 * 60_000L)
        transient = inputMethods()
        scope.launch {
            var lastRx = TunnelState.traffic.value.rxBytes
            while (isActive) {
                delay(TICK_MS)
                // Средняя скорость за интервал: фоновое аудио качается порциями, мгновенная скорость часто нулевая.
                val rx = TunnelState.traffic.value.rxBytes
                val avgRate = (rx - lastRx).coerceAtLeast(0) * 1000 / TICK_MS
                lastRx = rx
                val decision = policy.onTick(System.currentTimeMillis(), TunnelState.isActive, avgRate, stopDelayMs.value)
                if (decision == AutoStartPolicy.Decision.Stop) VpnController.stop(this@AutoStartService)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || !::policy.isInitialized) return
        val pkg = event.packageName?.toString() ?: return
        val status = TunnelState.status.value
        val decision = policy.onForeground(
            packageName = pkg,
            targets = targets.value,
            transient = transient,
            tunnelActive = TunnelState.isActive,
            startedByAutostart = status is TunnelStatus.Running && status.byAutostart || status is TunnelStatus.Starting,
            stopDelayMs = stopDelayMs.value,
            now = System.currentTimeMillis(),
        )
        if (decision == AutoStartPolicy.Decision.Start) VpnController.start(this, byAutostart = true)
    }

    private fun inputMethods(): Set<String> = runCatching {
        getSystemService(InputMethodManager::class.java).enabledInputMethodList.map { it.packageName }.toSet()
    }.getOrDefault(emptySet())

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TICK_MS = 15_000L

        /** Включена ли служба в настройках спецвозможностей. */
        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            val me = ComponentName(context, AutoStartService::class.java)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}

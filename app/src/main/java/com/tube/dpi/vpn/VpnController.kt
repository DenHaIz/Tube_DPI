package com.tube.dpi.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat

/** Единая точка включения и выключения обхода: экран, плитка, ярлык и автозапуск. */
object VpnController {

    /** Пользователь уже разрешил приложению создавать VPN (системный диалог показывается один раз). */
    fun hasConsent(context: Context): Boolean = VpnService.prepare(context) == null

    /** Intent системного диалога согласия или null, если согласие уже есть. */
    fun consentIntent(context: Context): Intent? = VpnService.prepare(context)

    fun start(context: Context, byAutostart: Boolean = false): Boolean {
        if (!hasConsent(context)) {
            TunnelState.set(TunnelStatus.Failed(FailReason.NoConsent, null))
            if (byAutostart) Notifications.showConsentNeeded(context)
            return false
        }
        val intent = Intent(context, TubeVpnService::class.java)
            .setAction(TubeVpnService.ACTION_START)
            .putExtra(TubeVpnService.EXTRA_BY_AUTOSTART, byAutostart)
        return runCatching { ContextCompat.startForegroundService(context, intent) }.isSuccess
    }

    fun stop(context: Context) {
        if (!TunnelState.isActive) {
            if (TunnelState.status.value is TunnelStatus.Failed) TunnelState.set(TunnelStatus.Stopped)
            return
        }
        val intent = Intent(context, TubeVpnService::class.java).setAction(TubeVpnService.ACTION_STOP)
        runCatching { context.startService(intent) }
    }

    fun toggle(context: Context) {
        if (TunnelState.isActive) stop(context) else start(context)
    }

    /** Применить изменённые настройки (стратегия, приложения, QUIC) к работающему туннелю. */
    fun restartIfRunning(context: Context) {
        val status = TunnelState.status.value
        if (status !is TunnelStatus.Running) return
        val intent = Intent(context, TubeVpnService::class.java)
            .setAction(TubeVpnService.ACTION_RESTART)
            .putExtra(TubeVpnService.EXTRA_BY_AUTOSTART, status.byAutostart)
        runCatching { context.startService(intent) }
    }
}

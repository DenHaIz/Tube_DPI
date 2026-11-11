package com.tube.dpi.vpn

import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.ServiceCompat
import com.tube.byedpi.ByeDpiProcess
import com.tube.byedpi.StrategyArgs
import com.tube.dpi.TubeApp
import com.tube.dpi.apps.TargetApps
import com.tube.tunnel.TProxyService
import com.tube.tunnel.TunnelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Локальный VPN только для выбранных приложений:
 * приложение → TUN → hev-socks5-tunnel → SOCKS5 127.0.0.1 → byedpi (desync) → интернет.
 * Сам Tube в туннель не входит, поэтому соединения byedpi идут напрямую, без protect().
 */
class TubeVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.tube.dpi.START"
        const val ACTION_STOP = "com.tube.dpi.STOP"
        const val ACTION_RESTART = "com.tube.dpi.RESTART"
        const val EXTRA_BY_AUTOSTART = "by_autostart"

        private const val PROXY_PORT = 10800
        private const val MONITOR_INTERVAL_MS = 1000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var proxy: ByeDpiProcess? = null
    private var tun: ParcelFileDescriptor? = null
    private var monitor: Job? = null

    private val container get() = (application as TubeApp).container

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val byAutostart = intent?.getBooleanExtra(EXTRA_BY_AUTOSTART, false) ?: false
        when (intent?.action) {
            ACTION_STOP -> scope.launch { lock.withLock { shutdown(TunnelStatus.Stopped) } }
            ACTION_RESTART -> {
                promoteToForeground("…", 0)
                scope.launch { lock.withLock { teardown(); startTunnel(byAutostart) } }
            }
            // null — система перезапустила сервис (always-on VPN или после нехватки памяти).
            else -> {
                promoteToForeground("…", 0)
                scope.launch { lock.withLock { if (proxy == null) startTunnel(byAutostart) } }
            }
        }
        return START_NOT_STICKY
    }

    /** Android требует startForeground в первые секунды после startForegroundService. */
    private fun promoteToForeground(strategyName: String, apps: Int) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, Notifications.ID_TUNNEL, Notifications.tunnel(this, strategyName, apps), type)
    }

    private suspend fun startTunnel(byAutostart: Boolean) {
        TunnelState.set(TunnelStatus.Starting)
        val settings = container.settings.tunnelSettings { TargetApps.defaultTargets(this) }
        val installed = settings.targets.filter { TargetApps.isInstalled(this, it) }
        if (installed.isEmpty()) return fail(FailReason.NoApps, null)

        val argv = when (val parsed = StrategyArgs.parse(settings.strategy.args)) {
            is StrategyArgs.Result.Ok -> parsed.argv
            is StrategyArgs.Result.Error -> return fail(FailReason.ProxyFailed, parsed.message)
        }
        proxy = try {
            ByeDpiProcess.start(this, PROXY_PORT, argv, blockUdp = settings.blockQuic, fakeSni = settings.fakeSni)
        } catch (e: Exception) {
            return fail(FailReason.ProxyFailed, e.message)
        }

        val builder = Builder()
            .setSession("Tube")
            .setMtu(TunnelConfig.MTU)
            .addAddress(TunnelConfig.TUN_IPV4, 32)
            .addAddress(TunnelConfig.TUN_IPV6, 128)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            .addDnsServer(TunnelConfig.MAPPED_DNS)
            .setBlocking(false)
            .setConfigureIntent(
                android.app.PendingIntent.getActivity(
                    this, 0,
                    Intent(this, com.tube.dpi.MainActivity::class.java),
                    android.app.PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)
        var allowed = 0
        installed.forEach { pkg ->
            try {
                builder.addAllowedApplication(pkg)
                allowed++
            } catch (_: PackageManager.NameNotFoundException) {
            }
        }
        if (allowed == 0) return fail(FailReason.NoApps, null)

        val fd = try {
            builder.establish()
        } catch (e: Exception) {
            return fail(FailReason.TunnelFailed, e.message)
        } ?: return fail(FailReason.NoConsent, null)
        tun = fd

        val config = File(cacheDir, "tunnel.yml").apply { writeText(TunnelConfig(socksPort = PROXY_PORT).toYaml()) }
        if (!TProxyService.TProxyStartService(config.absolutePath, fd.fd)) {
            return fail(FailReason.TunnelFailed, "hev-socks5-tunnel")
        }

        promoteToForeground(settings.strategy.name, allowed)
        Notifications.cancelAlerts(this)
        TunnelState.set(TunnelStatus.Running(System.currentTimeMillis(), settings.strategy.name, byAutostart))
        monitor = scope.launch { monitorLoop() }
    }

    /** Следит, что byedpi жив, и считает трафик для экрана и автозапуска. */
    private suspend fun monitorLoop() {
        var lastRx = 0L
        var lastTx = 0L
        while (scope.isActive) {
            delay(MONITOR_INTERVAL_MS)
            val current = proxy ?: return
            TunnelState.setLog(current.logTail())
            if (!current.isAlive) {
                lock.withLock { fail(FailReason.ProxyFailed, current.logTail().takeLast(3).joinToString("\n")) }
                return
            }
            val stats = runCatching { TProxyService.TProxyGetStats() }.getOrNull() ?: continue
            val tx = stats[1]
            val rx = stats[3]
            TunnelState.setTraffic(Traffic(rx, tx, (rx - lastRx).coerceAtLeast(0), (tx - lastTx).coerceAtLeast(0)))
            lastRx = rx
            lastTx = tx
        }
    }

    private fun fail(reason: FailReason, details: String?) {
        teardown()
        TunnelState.set(TunnelStatus.Failed(reason, details))
        if (reason != FailReason.NoConsent) Notifications.showFailed(this, listOfNotNull(reason.name, details).joinToString(": "))
        else Notifications.showConsentNeeded(this)
        stopSelfCompletely()
    }

    private fun shutdown(status: TunnelStatus) {
        teardown()
        TunnelState.set(status)
        stopSelfCompletely()
    }

    private fun teardown() {
        monitor?.cancel()
        monitor = null
        runCatching { TProxyService.TProxyStopService() }
        runCatching { tun?.close() }
        tun = null
        proxy?.let { TunnelState.setLog(it.logTail()) }
        proxy?.stop()
        proxy = null
    }

    private fun stopSelfCompletely() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Другое VPN-приложение заняло место или пользователь отключил VPN в системных настройках. */
    override fun onRevoke() {
        scope.launch { lock.withLock { shutdown(TunnelStatus.Failed(FailReason.Revoked, null)) } }
    }

    override fun onDestroy() {
        teardown()
        if (TunnelState.isActive) TunnelState.set(TunnelStatus.Stopped)
        scope.cancel()
        super.onDestroy()
    }
}

package com.tube.dpi.tile

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.tube.dpi.MainActivity
import com.tube.dpi.vpn.TunnelState
import com.tube.dpi.vpn.TunnelStatus
import com.tube.dpi.vpn.VpnController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Плитка в шторке: включить или выключить обход одним касанием. */
class TubeTileService : TileService() {
    private var watch: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        watch = CoroutineScope(Dispatchers.Main.immediate).launch {
            TunnelState.status.collect { render(it) }
        }
    }

    override fun onStopListening() {
        watch?.cancel()
        watch = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        if (!TunnelState.isActive && !VpnController.hasConsent(this)) {
            openApp()
            return
        }
        VpnController.toggle(this)
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun render(status: TunnelStatus) {
        val tile = qsTile ?: return
        tile.state = when (status) {
            is TunnelStatus.Running, TunnelStatus.Starting -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = (status as? TunnelStatus.Running)?.strategyName
        }
        tile.updateTile()
    }

    companion object {
        fun refresh(context: Context) {
            runCatching { requestListeningState(context, ComponentName(context, TubeTileService::class.java)) }
        }
    }
}

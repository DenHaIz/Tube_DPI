package com.tube.dpi

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.tube.dpi.apps.TargetApps
import com.tube.dpi.vpn.TunnelState
import com.tube.dpi.vpn.TunnelStatus
import com.tube.dpi.vpn.VpnController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Ярлык «YouTube через Tube»: включает обход, дожидается туннеля и открывает клиент
 * ([EXTRA_PACKAGE] или последний открытый / первый найденный). Без согласия на VPN — открывает Tube.
 */
@android.annotation.SuppressLint("CustomSplashScreen")
class LaunchActivity : ComponentActivity() {

    companion object {
        const val EXTRA_PACKAGE = "package"
        private const val TUNNEL_WAIT_MS = 4000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!VpnController.hasConsent(this)) {
            openTube()
            return
        }
        val settings = (application as TubeApp).container.settings
        lifecycleScope.launch {
            val client = withContext(Dispatchers.IO) {
                val targets = settings.targets.first() ?: TargetApps.defaultTargets(this@LaunchActivity)
                val clients = TargetApps.launchableClients(this@LaunchActivity, targets).map { it.packageName }
                val wanted = intent.getStringExtra(EXTRA_PACKAGE) ?: settings.lastClient.first()
                wanted?.takeIf { it in clients } ?: clients.firstOrNull()
            }
            if (client == null) {
                openTube()
                return@launch
            }
            settings.setLastClient(client)
            if (!TunnelState.isActive) VpnController.start(this@LaunchActivity)
            // Первые запросы клиента должны уже пойти через туннель.
            withTimeoutOrNull(TUNNEL_WAIT_MS) {
                TunnelState.status.first { it is TunnelStatus.Running || it is TunnelStatus.Failed }
            }
            TargetApps.launch(this@LaunchActivity, client)
            finish()
        }
    }

    private fun openTube() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}

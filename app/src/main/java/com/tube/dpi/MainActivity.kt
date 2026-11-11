package com.tube.dpi

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tube.dpi.data.ThemeMode
import com.tube.dpi.system.OemPower
import com.tube.dpi.ui.MainViewModel
import com.tube.dpi.ui.ShellActions
import com.tube.dpi.ui.TubeAppShell
import com.tube.dpi.vpn.Notifications
import com.tube.dpi.vpn.VpnController
import com.tube.ui.i18n.LocalLanguage
import com.tube.ui.theme.TubeTheme

class MainActivity : ComponentActivity() {

    private val container get() = (application as TubeApp).container
    private val vm: MainViewModel by viewModels()

    private val consent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) VpnController.start(this)
    }

    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Ответ не важен: без уведомлений обход работает, просто без значка в шторке.
        startWithConsent()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Notifications.ensureChannels(this)
        val actions = ShellActions(
            start = ::start,
            openAccessibility = {
                runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            },
            openClient = { pkg ->
                vm.rememberClient(pkg)
                if (VpnController.hasConsent(this)) {
                    startActivity(Intent(this, LaunchActivity::class.java).putExtra(LaunchActivity.EXTRA_PACKAGE, pkg))
                } else {
                    start()
                }
            },
            openAutostartOem = { OemPower.openAutostart(this) },
            openBattery = { OemPower.openBatterySettings(this) },
        )
        setContent {
            val language by container.language.collectAsStateWithLifecycle()
            val savedMode by container.themeMode.collectAsStateWithLifecycle()
            val dynamicColors by container.dynamicColors.collectAsStateWithLifecycle()
            // Пока настройки читаются (миллисекунды), виден фон окна — без мелькания чужого языка и темы.
            val mode = savedMode ?: return@setContent
            val dynamic = dynamicColors ?: return@setContent
            val dark = when (mode) {
                ThemeMode.System -> isSystemInDarkTheme()
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
            }
            CompositionLocalProvider(LocalLanguage provides (language ?: return@setContent)) {
                TubeTheme(darkTheme = dark, dynamic = dynamic) {
                    TubeAppShell(vm, actions)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.refresh()
    }

    /** Включение вручную: сначала разрешение на уведомления (Android 13+), затем согласие на VPN. */
    private fun start() {
        val needsNotifications = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsNotifications) notifications.launch(Manifest.permission.POST_NOTIFICATIONS) else startWithConsent()
    }

    private fun startWithConsent() {
        val intent = VpnController.consentIntent(this)
        if (intent == null) VpnController.start(this) else consent.launch(intent)
    }
}

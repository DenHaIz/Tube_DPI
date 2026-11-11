package com.tube.dpi.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tube.byedpi.Strategies
import com.tube.dpi.BuildConfig
import com.tube.dpi.data.ThemeMode
import com.tube.dpi.system.OemPower
import com.tube.dpi.vpn.TunnelStatus
import com.tube.dpi.vpn.VpnController
import com.tube.ui.components.LiquidSegmented
import com.tube.ui.components.PermissionRow
import com.tube.ui.components.PillButton
import com.tube.ui.components.SettingsCard
import com.tube.ui.components.SheetTitle
import com.tube.ui.components.ToggleRow
import com.tube.ui.i18n.AppLanguage
import com.tube.ui.i18n.LocalLanguage
import com.tube.ui.i18n.tr
import com.tube.ui.theme.Shapes
import com.tube.ui.theme.TubeIcons
import com.tube.ui.theme.TubeTheme
import com.tube.ui.theme.dynamicColorsSupported

@Composable
fun ColumnScope.SettingsSheet(vm: MainViewModel, actions: ShellActions, onAutostart: () -> Unit, onShowLog: () -> Unit) {
    val lang = LocalLanguage.current
    val context = LocalContext.current
    val blockQuic by vm.blockQuic.collectAsStateWithLifecycle()
    val fakeSni by vm.fakeSni.collectAsStateWithLifecycle()
    val autostart by vm.autostartEnabled.collectAsStateWithLifecycle()
    val stopDelay by vm.stopDelay.collectAsStateWithLifecycle()
    val theme by vm.themeMode.collectAsStateWithLifecycle()
    val dynamic by vm.dynamicColors.collectAsStateWithLifecycle()
    val language by vm.language.collectAsStateWithLifecycle()

    SheetTitle(tr("Настройки", "Settings"))
    Spacer(Modifier.height(12.dp))
    Column(
        Modifier
            .weight(1f, fill = false)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SettingsCard(tr("Обход", "Unblocking")) {
            ToggleRow(
                title = tr("Блокировать QUIC", "Block QUIC"),
                status = if (blockQuic) {
                    tr("YouTube переходит на TCP, где обход работает надёжнее (рекомендуется)", "YouTube falls back to TCP where unblocking is more reliable (recommended)")
                } else {
                    tr("QUIC идёт через byedpi с поддельными UDP-пакетами — для стратегии QUIC FAKE", "QUIC goes through byedpi with fake UDP packets — for the QUIC FAKE strategy")
                },
                checked = blockQuic,
                onChange = vm::setBlockQuic,
            )
            Spacer(Modifier.height(14.dp))
            Text(tr("Сайт в фейковых пакетах", "Site in fake packets"), style = MaterialTheme.typography.labelMedium, color = TubeTheme.colors.textSecondary)
            Spacer(Modifier.height(8.dp))
            Segmented(Strategies.FakeSniOptions.map { it.removePrefix("fonts.").removeSuffix(".google.com").removeSuffix(".com") }, Strategies.FakeSniOptions.indexOf(fakeSni).coerceAtLeast(0)) {
                vm.setFakeSni(Strategies.FakeSniOptions[it])
            }
            Spacer(Modifier.height(6.dp))
            Text(
                tr("Если подбор ничего не нашёл — смените сайт и проверьте заново: ТСПУ научился узнавать популярные подделки.", "If auto-pick found nothing, switch the site and test again: DPI has learned to spot popular fakes."),
                style = MaterialTheme.typography.labelSmall,
                color = TubeTheme.colors.textTertiary,
            )
        }

        SettingsCard(tr("Автозапуск", "Autostart")) {
            PermissionRow(
                title = tr("Вместе с YouTube", "Together with YouTube"),
                status = if (autostart) tr("Служба включена", "Service is on") else tr("Служба спецвозможностей выключена", "Accessibility service is off"),
                ok = autostart,
                action = tr("Настроить", "Set up"),
                onAction = onAutostart,
            )
            Spacer(Modifier.height(14.dp))
            Text(tr("Выключать после ухода из YouTube через", "Turn off after leaving YouTube in"), style = MaterialTheme.typography.labelMedium, color = TubeTheme.colors.textSecondary)
            Spacer(Modifier.height(8.dp))
            Segmented(StopDelayOptions.map { lang.stopDelay(it) }, StopDelayOptions.indexOf(stopDelay).coerceAtLeast(0)) { vm.setStopDelay(StopDelayOptions[it]) }
            Spacer(Modifier.height(6.dp))
            Text(
                tr("Пока через обход идёт фоновое воспроизведение, он не выключается.", "While background playback is streaming, it stays on."),
                style = MaterialTheme.typography.labelSmall,
                color = TubeTheme.colors.textTertiary,
            )
        }

        SettingsCard(tr("Фоновая работа", "Background")) {
            var refresh by remember { mutableIntStateOf(0) }
            val unrestricted = remember(refresh) { OemPower.isBatteryUnrestricted(context) }
            PermissionRow(
                title = tr("Без ограничений батареи", "Unrestricted battery"),
                status = if (unrestricted) tr("Система не выгрузит обход во время просмотра", "The system won't kill unblocking while watching") else tr("Обход может выключаться при экономии заряда", "Battery saver may stop unblocking"),
                ok = unrestricted,
                action = tr("Разрешить", "Allow"),
                onAction = {
                    actions.openBattery()
                    refresh++
                },
            )
            if (OemPower.needsAutostart) {
                Spacer(Modifier.height(12.dp))
                val allowed = OemPower.autostartAllowed(context)
                PermissionRow(
                    title = tr("Автозапуск прошивки", "OEM autostart"),
                    status = tr("На этом телефоне без него служба выгружается вместе с приложением", "Without it, this phone unloads the service with the app"),
                    ok = allowed == true,
                    optional = allowed == null,
                    action = tr("Открыть", "Open"),
                    onAction = actions.openAutostartOem,
                )
            }
        }

        SettingsCard(tr("Оформление", "Appearance")) {
            Segmented(
                listOf(tr("Системная", "System"), tr("Светлая", "Light"), tr("Тёмная", "Dark")),
                ThemeMode.entries.indexOf(theme),
            ) { vm.setThemeMode(ThemeMode.entries[it]) }
            if (dynamicColorsSupported) {
                Spacer(Modifier.height(14.dp))
                ToggleRow(
                    title = tr("Цвета обоев", "Wallpaper colors"),
                    status = tr("Material You вместо фирменного зелёного", "Material You instead of brand green"),
                    checked = dynamic,
                    onChange = vm::setDynamicColors,
                )
            }
            Spacer(Modifier.height(14.dp))
            Segmented(AppLanguage.entries.map { it.title }, AppLanguage.entries.indexOf(language)) { vm.setLanguage(AppLanguage.entries[it]) }
        }

        SettingsCard(tr("Диагностика", "Diagnostics")) {
            PillButton(tr("Журнал byedpi", "byedpi log"), onClick = onShowLog, icon = TubeIcons.Terminal, compact = true)
        }

        SettingsCard(tr("О приложении", "About")) {
            Text("Tube ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium, color = TubeTheme.colors.textPrimary)
            Spacer(Modifier.height(4.dp))
            Text(
                tr(
                    "Обход DPI на устройстве, без внешних серверов: трафик не шифруется и никуда не пересылается. " +
                        "Работает на основе byedpi (hufrea, MIT) и hev-socks5-tunnel (heiher, MIT). Лицензия GPL-3.0.",
                    "On-device DPI circumvention without external servers: traffic is neither encrypted nor relayed anywhere. " +
                        "Built on byedpi (hufrea, MIT) and hev-socks5-tunnel (heiher, MIT). Licensed under GPL-3.0.",
                ),
                style = MaterialTheme.typography.labelMedium,
                color = TubeTheme.colors.textSecondary,
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val colors = TubeTheme.colors
    LiquidSegmented(
        count = options.size,
        selectedIndex = selected,
        onSelect = onSelect,
        pillColor = colors.accent.copy(alpha = 0.22f),
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(Shapes.Pill)
            .background(colors.chip),
    ) { index, isSelected ->
        Text(
            options[index],
            style = MaterialTheme.typography.labelLarge,
            color = if (isSelected) colors.textPrimary else colors.textSecondary,
            maxLines = 1,
        )
    }
}

/**
 * Включение автозапуска. Явно объясняем, зачем служба спецвозможностей и что она не делает, —
 * этого требуют и здравый смысл, и правила Google для таких служб.
 */
@Composable
fun ColumnScope.AutostartSheet(vm: MainViewModel, actions: ShellActions) {
    val colors = TubeTheme.colors
    val autostart by vm.autostartEnabled.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val context = LocalContext.current

    SheetTitle(
        tr("Автозапуск с YouTube", "Start with YouTube"),
        tr(
            "Android не сообщает приложениям, что открылось другое приложение. Узнать это можно только через службу спецвозможностей.",
            "Android doesn't tell apps when another app opens. The only way to know is an accessibility service.",
        ),
    )
    Column(
        Modifier
            .weight(1f, fill = false)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Disclosure(
            tr("Что делает служба", "What the service does"),
            tr(
                "Получает только имя приложения на экране. Когда это YouTube, ReVanced или Morphe — включает обход, а после ухода из них выключает.",
                "Receives only the name of the app on screen. When it's YouTube, ReVanced or Morphe — turns unblocking on, and off after you leave.",
            ),
        )
        Disclosure(
            tr("Чего служба не делает", "What it doesn't do"),
            tr(
                "Не читает содержимое экрана и нажатия, ничего не отправляет в интернет и не хранит историю приложений.",
                "Doesn't read screen content or taps, sends nothing to the internet and keeps no app history.",
            ),
        )
        Disclosure(
            tr("Как включить", "How to turn on"),
            tr(
                "1. «Открыть настройки» → «Установленные приложения» (или «Скачанные») → Tube → включить.\n" +
                    "2. Если переключатель серый (Android 13+): «Сведения о приложении» → ⋮ → «Разрешить ограниченные настройки», затем шаг 1.",
                "1. “Open settings” → “Installed apps” (or “Downloaded”) → Tube → turn on.\n" +
                    "2. If the switch is greyed out (Android 13+): “App info” → ⋮ → “Allow restricted settings”, then step 1.",
            ),
        )
        PermissionRow(
            title = tr("Служба автозапуска", "Autostart service"),
            status = if (autostart) tr("Включена", "On") else tr("Выключена", "Off"),
            ok = autostart,
            action = tr("Открыть настройки", "Open settings"),
            onAction = actions.openAccessibility,
        )
        val consent = VpnController.hasConsent(context)
        PermissionRow(
            title = tr("Разрешение VPN", "VPN permission"),
            status = if (consent) tr("Выдано", "Granted") else tr("Нужно включить обход вручную один раз", "Turn unblocking on manually once"),
            ok = consent || status is TunnelStatus.Running,
            action = tr("Выдать", "Grant"),
            onAction = actions.start,
        )
        if (!autostart) {
            PillButton(
                tr("Сведения о приложении", "App info"),
                onClick = {
                    runCatching {
                        context.startActivity(
                            android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                },
                compact = true,
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun Disclosure(title: String, text: String) {
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium, color = TubeTheme.colors.textPrimary)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = TubeTheme.colors.textSecondary)
    }
}

@Composable
fun ColumnScope.LogSheet(vm: MainViewModel) {
    val colors = TubeTheme.colors
    val context = LocalContext.current
    val log by vm.log.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val strategy by vm.strategy.collectAsStateWithLifecycle()
    val testLog by vm.testLog.collectAsStateWithLifecycle()
    val lines = buildList {
        add("Tube ${BuildConfig.VERSION_NAME}, Android ${android.os.Build.VERSION.RELEASE}, ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        add("strategy: ${strategy.name} ${strategy.args}")
        add("status: $status")
        addAll(log)
        if (testLog.isNotEmpty()) {
            add("")
            add("--- auto-pick ---")
            addAll(testLog)
        }
    }
    SheetTitle(tr("Журнал byedpi", "byedpi log"), tr("Последние строки вывода прокси", "Latest proxy output"))
    Spacer(Modifier.height(12.dp))
    Box(
        Modifier
            .weight(1f, fill = false)
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(Shapes.Chip)
            .background(colors.chip)
            .verticalScroll(rememberScrollState())
            .horizontalScroll(rememberScrollState())
            .padding(12.dp),
    ) {
        Text(
            lines.joinToString("\n"),
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = colors.textSecondary,
        )
    }
    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        PillButton(
            tr("Скопировать", "Copy"),
            onClick = {
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("byedpi", lines.joinToString("\n")))
            },
            compact = true,
        )
        Spacer(Modifier.size(8.dp))
    }
}

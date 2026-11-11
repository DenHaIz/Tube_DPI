package com.tube.dpi.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tube.dpi.R
import com.tube.dpi.vpn.FailReason
import com.tube.dpi.vpn.TunnelStatus
import com.tube.ui.components.AmbientBackground
import com.tube.ui.components.FitText
import com.tube.ui.components.GlassSheet
import com.tube.ui.components.PillButton
import com.tube.ui.components.bouncyClickable
import com.tube.ui.glass.FrostedCard
import com.tube.ui.glass.LocalHazeState
import com.tube.ui.glass.liquidGlass
import com.tube.ui.i18n.LocalLanguage
import com.tube.ui.i18n.tr
import com.tube.ui.theme.Motion
import com.tube.ui.theme.Shapes
import com.tube.ui.theme.TubeIcons
import com.tube.ui.theme.TubeTheme
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class Sheet { None, Strategy, Apps, Settings, Autostart, Log }

/** Действия, которые требуют активности (системные диалоги и экраны настроек). */
class ShellActions(
    /** Включить обход: при необходимости — системные диалоги согласия на VPN и уведомлений. */
    val start: () -> Unit,
    val openAccessibility: () -> Unit,
    val openClient: (String) -> Unit,
    val openAutostartOem: () -> Unit,
    val openBattery: () -> Unit,
)

@Composable
fun TubeAppShell(vm: MainViewModel, actions: ShellActions) {
    val hazeState = rememberHazeState()
    var sheet by rememberSaveable { mutableStateOf(Sheet.None) }
    val status by vm.status.collectAsStateWithLifecycle()
    CompositionLocalProvider(LocalHazeState provides hazeState) {
        Box(Modifier.fillMaxSize()) {
            AmbientBackground(
                colors = emptyList(),
                seed = if (status is TunnelStatus.Running) 7 else 3,
                modifier = Modifier
                    .fillMaxSize()
                    .hazeSource(hazeState, zIndex = 0f),
            )
            HomeScreen(vm, actions, onOpen = { sheet = it })
            val close = { sheet = Sheet.None }
            GlassSheet(sheet == Sheet.Strategy, close) { StrategySheet(vm, onShowLog = { sheet = Sheet.Log }) }
            GlassSheet(sheet == Sheet.Apps, close) { AppsSheet(vm) }
            GlassSheet(sheet == Sheet.Settings, close) {
                SettingsSheet(vm, actions, onAutostart = { sheet = Sheet.Autostart }, onShowLog = { sheet = Sheet.Log })
            }
            GlassSheet(sheet == Sheet.Autostart, close) { AutostartSheet(vm, actions) }
            GlassSheet(sheet == Sheet.Log, close) { LogSheet(vm) }
        }
    }
}

@Composable
private fun HomeScreen(vm: MainViewModel, actions: ShellActions, onOpen: (Sheet) -> Unit) {
    val colors = TubeTheme.colors
    val lang = LocalLanguage.current
    val status by vm.status.collectAsStateWithLifecycle()
    val traffic by vm.traffic.collectAsStateWithLifecycle()
    val strategy by vm.strategy.collectAsStateWithLifecycle()
    val targets by vm.targets.collectAsStateWithLifecycle()
    val installed by vm.installed.collectAsStateWithLifecycle()
    val clients by vm.clients.collectAsStateWithLifecycle()
    val autostart by vm.autostartEnabled.collectAsStateWithLifecycle()
    val stopDelay by vm.stopDelay.collectAsStateWithLifecycle()
    val testResults by vm.testResults.collectAsStateWithLifecycle()

    val orb = when (status) {
        is TunnelStatus.Running -> OrbState.On
        TunnelStatus.Starting -> OrbState.Starting
        is TunnelStatus.Failed -> OrbState.Failed
        TunnelStatus.Stopped -> OrbState.Off
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.tube_mark), contentDescription = null, modifier = Modifier.size(44.dp))
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Tube", style = MaterialTheme.typography.headlineSmall, color = colors.textPrimary)
                Text(tr("Обход блокировок YouTube", "YouTube unblocking"), style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
            }
            GlassIconButton(TubeIcons.Sliders, tr("Настройки", "Settings")) { onOpen(Sheet.Settings) }
        }

        Spacer(Modifier.height(20.dp))
        PowerOrb(orb, tr("Включить или выключить обход", "Toggle unblocking"), onClick = {
            if (status is TunnelStatus.Running || status is TunnelStatus.Starting) vm.toggleTunnel() else actions.start()
        })

        AnimatedContent(status, transitionSpec = { fadeIn(Motion.snappy()) togetherWith fadeOut(Motion.snappy()) }, label = "status") { st ->
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                val title = when (st) {
                    is TunnelStatus.Running -> tr("Обход включён", "Unblocking is on")
                    TunnelStatus.Starting -> tr("Запуск…", "Starting…")
                    is TunnelStatus.Failed -> lang.failMessage(st.reason)
                    TunnelStatus.Stopped -> tr("Обход выключен", "Unblocking is off")
                }
                FitText(title, style = MaterialTheme.typography.titleLarge, color = if (st is TunnelStatus.Failed) colors.danger else colors.textPrimary)
                val subtitle = when (st) {
                    is TunnelStatus.Running -> "↓ ${lang.bytes(traffic.rxBytes)} · ${lang.rate(traffic.rxRate)}   ↑ ${lang.bytes(traffic.txBytes)}"
                    TunnelStatus.Stopped -> tr("Нажмите, чтобы включить", "Tap to turn on")
                    is TunnelStatus.Failed -> st.details?.lineSequence()?.firstOrNull()?.take(120)
                    TunnelStatus.Starting -> null
                }
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.textSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (st is TunnelStatus.Failed) {
                    Spacer(Modifier.height(10.dp))
                    when (st.reason) {
                        FailReason.NoConsent, FailReason.Revoked, FailReason.TunnelFailed ->
                            PillButton(tr("Включить снова", "Turn on again"), onClick = actions.start, compact = true)
                        FailReason.NoApps -> PillButton(tr("Выбрать приложения", "Choose apps"), onClick = { onOpen(Sheet.Apps) }, compact = true)
                        FailReason.ProxyFailed -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PillButton(tr("Сменить стратегию", "Change strategy"), onClick = { onOpen(Sheet.Strategy) }, compact = true)
                            PillButton(tr("Журнал", "Log"), onClick = { onOpen(Sheet.Log) }, compact = true)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(22.dp))

        AnimatedVisibility(clients.isNotEmpty()) {
            Column(Modifier.fillMaxWidth()) {
                SectionLabel(tr("Открыть через обход", "Open with unblocking"))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    clients.forEach { client ->
                        ClientChip(client.packageName, client.label) { actions.openClient(client.packageName) }
                    }
                }
                Spacer(Modifier.height(18.dp))
            }
        }

        val score = testResults[strategy.id]
        InfoCard(
            icon = TubeIcons.Bolt,
            title = tr("Стратегия", "Strategy"),
            value = strategy.name + (score?.let { " · ${it.passed}/${it.total}" } ?: ""),
            hint = if (testResults.isEmpty()) tr("Подберите лучшую для вашего провайдера", "Find the best one for your ISP") else lang.pick(strategy.descriptionRu, strategy.descriptionEn),
        ) { onOpen(Sheet.Strategy) }
        Spacer(Modifier.height(10.dp))

        val selectedNames = installed.orEmpty().filter { it.packageName in targets }.map { it.label }
        InfoCard(
            icon = TubeIcons.Apps,
            title = tr("Приложения", "Apps"),
            value = when {
                installed == null -> "…"
                selectedNames.isEmpty() -> tr("Не выбрано", "None selected")
                else -> lang.plural(selectedNames.size, "${selectedNames.size} приложение", "${selectedNames.size} приложения", "${selectedNames.size} приложений", "${selectedNames.size} app", "${selectedNames.size} apps")
            },
            hint = selectedNames.take(4).joinToString(", ").ifEmpty { tr("Трафик остальных приложений идёт напрямую", "Other apps bypass the tunnel") },
            warning = installed != null && selectedNames.isEmpty(),
        ) { onOpen(Sheet.Apps) }
        Spacer(Modifier.height(10.dp))

        InfoCard(
            icon = TubeIcons.Eye,
            title = tr("Автозапуск с YouTube", "Start with YouTube"),
            value = if (autostart) tr("Включён", "On") else tr("Выключен", "Off"),
            hint = if (autostart) {
                if (stopDelay == 0) tr("Включается при открытии YouTube и не выключается сам", "Turns on when YouTube opens and stays on")
                else tr("Включается при открытии YouTube, выключается через ${lang.stopDelay(stopDelay)} после ухода", "Turns on when YouTube opens, off ${lang.stopDelay(stopDelay)} after you leave")
            } else {
                tr("Нажмите, чтобы обход включался сам вместе с YouTube", "Tap to make it start together with YouTube")
            },
            warning = !autostart,
        ) { onOpen(Sheet.Autostart) }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = TubeTheme.colors.textSecondary,
        modifier = modifier.padding(start = 4.dp, bottom = 8.dp),
    )
}

@Composable
fun GlassIconButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .bouncyClickable(glowColor = TubeTheme.colors.accent, glowShape = Shapes.Pill, onClick = onClick)
            .liquidGlass(Shapes.Pill),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = TubeTheme.colors.textPrimary, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun InfoCard(
    icon: ImageVector,
    title: String,
    value: String,
    hint: String,
    warning: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = TubeTheme.colors
    val iconColor = if (warning) colors.warning else colors.accent
    FrostedCard(
        Modifier
            .fillMaxWidth()
            .bouncyClickable(pressedScale = 0.97f, onClick = onClick),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(Shapes.Chip)
                    .background(iconColor.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.size(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.labelMedium, color = colors.textSecondary, modifier = Modifier.weight(1f, fill = false))
                }
                FitText(value, style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
                Text(hint, style = MaterialTheme.typography.labelMedium, color = colors.textTertiary, maxLines = 2)
            }
            Icon(TubeIcons.ChevronRight, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun ClientChip(packageName: String, label: String, onClick: () -> Unit) {
    val colors = TubeTheme.colors
    Row(
        Modifier
            .bouncyClickable(glowColor = colors.accent, glowShape = Shapes.Pill, onClick = onClick)
            .liquidGlass(Shapes.Pill)
            .padding(start = 6.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(packageName, Modifier.size(32.dp))
        Spacer(Modifier.size(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = colors.textPrimary, maxLines = 1)
        Spacer(Modifier.size(6.dp))
        Icon(TubeIcons.Play, contentDescription = null, tint = colors.accent, modifier = Modifier.size(16.dp))
    }
}

/** Значок установленного приложения; грузится в фоне, пока — пустое место. */
@Composable
fun AppIcon(packageName: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(null, packageName) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.packageManager.getApplicationIcon(packageName).toBitmap(96, 96).asImageBitmap() }.getOrNull()
        }
    }
    Box(modifier) {
        icon?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize()) }
    }
}

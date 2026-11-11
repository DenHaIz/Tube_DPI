package com.tube.dpi.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tube.byedpi.Strategies
import com.tube.byedpi.Strategy
import com.tube.byedpi.StrategyScore
import com.tube.ui.components.PillButton
import com.tube.ui.components.SheetTitle
import com.tube.ui.components.bouncyClickable
import com.tube.ui.i18n.LocalLanguage
import com.tube.ui.i18n.tr
import com.tube.ui.theme.Motion
import com.tube.ui.theme.Shapes
import com.tube.ui.theme.TubeIcons
import com.tube.ui.theme.TubeTheme

@Composable
fun ColumnScope.StrategySheet(vm: MainViewModel, onShowLog: () -> Unit) {
    val colors = TubeTheme.colors
    val lang = LocalLanguage.current
    val selectedId by vm.strategyId.collectAsStateWithLifecycle()
    val customArgs by vm.customArgs.collectAsStateWithLifecycle()
    val results by vm.testResults.collectAsStateWithLifecycle()
    val test by vm.test.collectAsStateWithLifecycle()
    val fakeSni by vm.fakeSni.collectAsStateWithLifecycle()

    SheetTitle(
        tr("Стратегия обхода", "Bypass strategy"),
        tr(
            "Как byedpi запутывает DPI провайдера. Автоподбор проверит все варианты на серверах YouTube — около минуты.",
            "How byedpi confuses your ISP's DPI. Auto-pick tests every option against YouTube servers — about a minute.",
        ),
    )
    Spacer(Modifier.height(12.dp))

    Column(Modifier.padding(horizontal = 20.dp)) {
        if (test.systemDns == false) {
            Text(
                tr(
                    "DNS провайдера не отдаёт youtube.com — Tube резолвит имена через DoH (Google, Cloudflare).",
                    "Your ISP's DNS doesn't resolve youtube.com — Tube resolves names over DoH (Google, Cloudflare).",
                ),
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary,
            )
            Spacer(Modifier.height(6.dp))
        }
        test.baseline?.let { baseline ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (baseline.works) tr("Без обхода YouTube открывается", "YouTube works without bypass") else tr("Без обхода:", "Without bypass:"),
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(end = 8.dp),
                )
                ProbeDots(baseline)
            }
            Spacer(Modifier.height(10.dp))
        }
        if (test.running) {
            val progress by animateFloatAsState(if (test.total == 0) 0f else test.done / test.total.toFloat(), Motion.soft(), label = "progress")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    tr("Проверено ${test.done} из ${test.total}", "Tested ${test.done} of ${test.total}"),
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                PillButton(tr("Стоп", "Stop"), onClick = vm::cancelTest, compact = true)
            }
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(Shapes.Pill)
                    .background(colors.chip),
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(progress)
                        .clip(Shapes.Pill)
                        .background(colors.accent),
                )
            }
        } else {
            val best = test.ranking.firstOrNull()?.takeIf { it.works }
            if (best != null && best.strategy.id != selectedId) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("Лучшая для вашей сети", "Best for your network"), style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
                        Text("${best.strategy.name} · ${best.avgMillis} ${lang.pick("мс", "ms")}", style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
                    }
                    PillButton(tr("Применить", "Apply"), onClick = { vm.selectStrategy(best.strategy.id) }, filled = true, compact = true)
                }
            } else if (test.ranking.isNotEmpty() && best == null) {
                Text(
                    tr(
                        "Ни одна стратегия не прошла все проверки. Попробуйте разрешить QUIC в настройках, другую сеть или свою стратегию.",
                        "No strategy passed every check. Try allowing QUIC in settings, another network, or a custom strategy.",
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.warning,
                )
                Spacer(Modifier.height(8.dp))
            }
            Spacer(Modifier.height(8.dp))
            PillButton(
                if (results.isEmpty()) tr("Подобрать автоматически", "Auto-pick") else tr("Проверить заново", "Test again"),
                onClick = vm::runTest,
                icon = TubeIcons.Bolt,
                filled = results.isEmpty(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    Spacer(Modifier.height(12.dp))

    androidx.compose.runtime.CompositionLocalProvider(LocalFakeSni provides fakeSni) { LazyColumn(
        Modifier
            .weight(1f, fill = false)
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Из набора ByeByeDPI показываем только сработавшие при подборе (и выбранную).
        val extra = Strategies.extended.filter { it.id == selectedId || results[it.id]?.works == true }
            .sortedBy { results[it.id]?.avgMillis ?: Long.MAX_VALUE }
        items(Strategies.builtIn + extra, key = { it.id }) { strategy ->
            StrategyRow(strategy, strategy.id == selectedId, results[strategy.id]) { vm.selectStrategy(strategy.id) }
        }
        item(key = Strategies.CUSTOM_ID) {
            CustomStrategy(
                args = customArgs,
                selected = selectedId == Strategies.CUSTOM_ID,
                score = results[Strategies.CUSTOM_ID],
                onSave = vm::saveCustomArgs,
            )
        }
        item {
            Text(
                tr("Журнал byedpi", "byedpi log"),
                style = MaterialTheme.typography.labelLarge,
                color = colors.accent,
                modifier = Modifier
                    .padding(8.dp)
                    .bouncyClickable(onClick = onShowLog)
                    .padding(8.dp),
            )
            Spacer(Modifier.height(12.dp))
        }
    } }
}

/** Текущий фейковый SNI — подставляется в показанные аргументы вместо {sni}. */
val LocalFakeSni = androidx.compose.runtime.staticCompositionLocalOf { Strategies.FakeSniOptions.first() }

@Composable
private fun StrategyRow(strategy: Strategy, selected: Boolean, score: StrategyScore?, onClick: () -> Unit) {
    val colors = TubeTheme.colors
    val lang = LocalLanguage.current
    Row(
        Modifier
            .fillMaxWidth()
            .bouncyClickable(pressedScale = 0.98f, onClick = onClick)
            .clip(Shapes.Chip)
            .background(if (selected) colors.accent.copy(alpha = 0.14f) else colors.chip.copy(alpha = colors.chip.alpha * 0.6f))
            .then(if (selected) Modifier.border(1.5.dp, colors.accent.copy(alpha = 0.6f), Shapes.Chip) else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(strategy.name, style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
                if (score != null) {
                    Spacer(Modifier.size(8.dp))
                    ScoreBadge(score)
                }
            }
            Text(lang.pick(strategy.descriptionRu, strategy.descriptionEn), style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
            if (score != null && !score.works && score.results.isNotEmpty()) {
                ProbeDots(score, Modifier.padding(vertical = 4.dp))
            }
            Text(strategy.args.replace(Strategies.SNI, LocalFakeSni.current), style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace), color = colors.textTertiary, maxLines = 2)
        }
        if (selected) {
            Icon(TubeIcons.Check, contentDescription = null, tint = colors.accent, modifier = Modifier.padding(start = 8.dp).size(22.dp))
        }
    }
}

/** Итог по каждому узлу: страница, превью, видео — видно, что именно режет провайдер. */
@Composable
private fun ProbeDots(score: StrategyScore, modifier: Modifier = Modifier) {
    val colors = TubeTheme.colors
    val lang = LocalLanguage.current
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        score.results.forEach { result ->
            val label = when {
                "googlevideo" in result.probe.host -> lang.pick("видео", "video")
                "ytimg" in result.probe.host -> lang.pick("превью", "thumbs")
                else -> "youtube.com"
            }
            val color = if (result.ok) colors.success else colors.danger
            Text(
                (if (result.ok) "✓ " else "✕ ") + label,
                style = MaterialTheme.typography.labelSmall,
                color = color,
                modifier = Modifier
                    .clip(Shapes.Pill)
                    .background(color.copy(alpha = 0.12f))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun ScoreBadge(score: StrategyScore) {
    val colors = TubeTheme.colors
    val lang = LocalLanguage.current
    val color = when {
        score.works -> colors.success
        score.passed > 0 -> colors.warning
        else -> colors.danger
    }
    val text = when {
        score.startError != null -> lang.pick("ошибка", "error")
        score.works -> "${score.passed}/${score.total} · ${score.avgMillis} ${lang.pick("мс", "ms")}"
        else -> "${score.passed}/${score.total}"
    }
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .clip(Shapes.Pill)
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun CustomStrategy(args: String, selected: Boolean, score: StrategyScore?, onSave: (String) -> String?) {
    val colors = TubeTheme.colors
    var text by remember { mutableStateOf(args) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(args) { if (text.isEmpty()) text = args }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(Shapes.Chip)
            .background(if (selected) colors.accent.copy(alpha = 0.14f) else colors.chip.copy(alpha = colors.chip.alpha * 0.6f))
            .then(if (selected) Modifier.border(1.5.dp, colors.accent.copy(alpha = 0.6f), Shapes.Chip) else Modifier)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(TubeIcons.Edit, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text(tr("Своя стратегия", "Custom strategy"), style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
            if (score != null) {
                Spacer(Modifier.size(8.dp))
                ScoreBadge(score)
            }
        }
        Text(
            tr("Аргументы byedpi, как в ByeByeDPI. Адрес и порт задаёт Tube.", "byedpi arguments, as in ByeByeDPI. Address and port are set by Tube."),
            style = MaterialTheme.typography.labelMedium,
            color = colors.textSecondary,
        )
        Spacer(Modifier.height(8.dp))
        BasicTextField(
            value = text,
            onValueChange = {
                text = it
                error = null
            },
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, color = colors.textPrimary),
            cursorBrush = SolidColor(colors.accent),
            modifier = Modifier
                .fillMaxWidth()
                .clip(Shapes.Chip)
                .background(colors.chip)
                .padding(12.dp),
            decorationBox = { inner ->
                if (text.isEmpty()) Text("-d1 -s1+s -r1+s", style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), color = colors.textTertiary)
                inner()
            },
        )
        AnimatedVisibility(error != null) {
            Text(
                tr("Не понимаю «${error.orEmpty()}»", "Can't parse “${error.orEmpty()}”"),
                style = MaterialTheme.typography.labelMedium,
                color = colors.danger,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        PillButton(
            if (selected && text.trim() == args) tr("Используется", "In use") else tr("Сохранить и использовать", "Save and use"),
            onClick = { error = onSave(text) },
            enabled = text.isNotBlank() && !(selected && text.trim() == args),
            compact = true,
            modifier = Modifier.align(Alignment.End),
        )
    }
}

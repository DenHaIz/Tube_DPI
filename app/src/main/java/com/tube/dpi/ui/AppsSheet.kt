package com.tube.dpi.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tube.dpi.apps.AppKind
import com.tube.dpi.apps.InstalledApp
import com.tube.ui.components.SheetTitle
import com.tube.ui.components.bouncyClickable
import com.tube.ui.i18n.tr
import com.tube.ui.theme.Motion
import com.tube.ui.theme.Shapes
import com.tube.ui.theme.TubeIcons
import com.tube.ui.theme.TubeTheme

@Composable
fun ColumnScope.AppsSheet(vm: MainViewModel) {
    val colors = TubeTheme.colors
    val installed by vm.installed.collectAsStateWithLifecycle()
    val targets by vm.targets.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }

    SheetTitle(
        tr("Приложения", "Apps"),
        tr(
            "Через обход идёт трафик только отмеченных приложений — остальные работают как обычно.",
            "Only checked apps go through the unblocking — everything else works as usual.",
        ),
    )
    Spacer(Modifier.height(12.dp))

    Row(
        Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .clip(Shapes.Pill)
            .background(colors.chip)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(TubeIcons.Search, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(10.dp))
        BasicTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.textPrimary),
            cursorBrush = SolidColor(colors.accent),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                if (query.isEmpty()) Text(tr("Поиск", "Search"), style = MaterialTheme.typography.bodyLarge, color = colors.textTertiary)
                inner()
            },
        )
    }
    Spacer(Modifier.height(12.dp))

    val apps = installed
    if (apps == null) {
        Text(tr("Загрузка…", "Loading…"), color = colors.textSecondary, modifier = Modifier.padding(20.dp))
        return
    }
    val q = query.trim().lowercase()
    val filtered = apps.filter { q.isEmpty() || q in it.label.lowercase() || q in it.packageName.lowercase() }
    val youtube = filtered.filter { it.kind != null }
    val others = filtered.filter { it.kind == null && (!it.system || it.packageName in targets || q.isNotEmpty()) }

    LazyColumn(
        Modifier
            .weight(1f, fill = false)
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (youtube.isNotEmpty()) {
            item { SectionLabel(tr("YouTube и сервисы", "YouTube and services"), Modifier.padding(start = 8.dp, top = 4.dp)) }
            items(youtube, key = { it.packageName }) { app -> AppRow(app, app.packageName in targets) { vm.setTarget(app.packageName, it) } }
        }
        if (others.isNotEmpty()) {
            item { SectionLabel(tr("Другие приложения", "Other apps"), Modifier.padding(start = 8.dp, top = 12.dp)) }
            items(others, key = { it.packageName }) { app -> AppRow(app, app.packageName in targets) { vm.setTarget(app.packageName, it) } }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun AppRow(app: InstalledApp, checked: Boolean, onChange: (Boolean) -> Unit) {
    val colors = TubeTheme.colors
    val box by animateColorAsState(if (checked) colors.accent else colors.chip, Motion.snappy(), label = "check")
    Row(
        Modifier
            .fillMaxWidth()
            .bouncyClickable(pressedScale = 0.98f) { onChange(!checked) }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(app.packageName, Modifier.size(40.dp))
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(app.label, style = MaterialTheme.typography.titleMedium, color = colors.textPrimary, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                app.kind?.let { kind ->
                    Spacer(Modifier.size(6.dp))
                    Text(
                        when (kind) {
                            AppKind.YouTube -> "YouTube"
                            AppKind.Music -> "Music"
                            AppKind.Services -> tr("вход", "sign-in")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.accent,
                        modifier = Modifier
                            .clip(Shapes.Pill)
                            .background(colors.accent.copy(alpha = 0.14f))
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            }
            Text(app.packageName, style = MaterialTheme.typography.labelSmall, color = colors.textTertiary, maxLines = 1)
        }
        Box(
            Modifier
                .padding(start = 8.dp)
                .size(24.dp)
                .clip(Shapes.Chip)
                .background(box)
                .then(if (!checked) Modifier.border(1.5.dp, colors.textTertiary.copy(alpha = 0.5f), Shapes.Chip) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) Icon(TubeIcons.Check, contentDescription = null, tint = colors.onAccent, modifier = Modifier.size(16.dp))
        }
    }
}

package com.tube.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.tube.ui.glass.FrostedCard
import com.tube.ui.theme.Motion
import com.tube.ui.theme.Shapes
import com.tube.ui.theme.TubeTheme
import kotlin.math.abs
import kotlin.math.roundToInt

/** Матовая карточка с заголовком-капсом — как группы в настройках Woody. */
@Composable
fun SettingsCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    FrostedCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                title.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = TubeTheme.colors.textSecondary,
                maxLines = 1,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            content()
        }
    }
}

/** Строка с переключателем: капля-бегунок перетекает пружиной. */
@Composable
fun ToggleRow(title: String, status: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val colors = TubeTheme.colors
    val track by animateColorAsState(if (checked) colors.success else colors.chip, Motion.snappy(), label = "track")
    val knob by animateFloatAsState(if (checked) 1f else 0f, Motion.bouncy(), label = "knob")
    Row(
        Modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = if (enabled) 1f else 0.5f }
            .bouncyClickable(pressedScale = 0.98f, enabled = enabled) { onChange(!checked) }
            .semantics(mergeDescendants = true) {
                role = Role.Switch
                toggleableState = ToggleableState(checked)
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(if (checked) colors.success else colors.textTertiary)
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            FitText(title, style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
            Text(status, style = MaterialTheme.typography.labelMedium, color = colors.textSecondary, maxLines = 4)
        }
        Box(
            Modifier
                .padding(start = 12.dp)
                .size(width = 52.dp, height = 32.dp)
                .clip(Shapes.Pill)
                .background(track)
                .padding(3.dp),
        ) {
            Box(
                Modifier
                    .offset { IntOffset((20.dp.toPx() * knob).roundToInt(), 0) }
                    .size(26.dp)
                    .graphicsLayer {
                        // Бегунок слегка растягивается в движении — как капля.
                        scaleX = 1f + 0.25f * (1f - abs(knob * 2f - 1f))
                    }
                    .background(Color.White, CircleShape),
            )
        }
    }
}

/** Строка состояния разрешения: точка-индикатор и кнопка действия, пока не выполнено. */
@Composable
fun PermissionRow(
    title: String,
    status: String,
    ok: Boolean,
    action: String,
    onAction: () -> Unit,
    optional: Boolean = false,
) {
    val colors = TubeTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusDot(
            when {
                ok -> colors.success
                optional -> colors.textTertiary
                else -> colors.warning
            },
        )
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            FitText(title, style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
            Text(status, style = MaterialTheme.typography.labelMedium, color = colors.textSecondary, maxLines = 4)
        }
        if (!ok) {
            PillButton(action, onClick = onAction, modifier = Modifier.padding(start = 8.dp), compact = true)
        }
    }
}

@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(10.dp)
            .background(color, Shapes.Pill),
    )
}

/** Капсула-кнопка в цвете акцента: заливка [filled] или мягкий тон. */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    filled: Boolean = false,
    compact: Boolean = false,
    enabled: Boolean = true,
    color: Color = TubeTheme.colors.accent,
) {
    val content = if (filled) TubeTheme.colors.onAccent else color
    Row(
        modifier
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f }
            .heightIn(min = if (compact) 36.dp else 50.dp)
            .bouncyClickable(enabled = enabled, glowColor = color, glowShape = Shapes.Pill, onClick = onClick)
            .clip(Shapes.Pill)
            .background(if (filled) color else color.copy(alpha = 0.14f))
            .padding(horizontal = if (compact) 14.dp else 18.dp, vertical = if (compact) 8.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = content, maxLines = 1)
    }
}

/** Заголовок листа: крупный текст и необязательное пояснение. */
@Composable
fun SheetTitle(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, color = TubeTheme.colors.textPrimary)
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = TubeTheme.colors.textSecondary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

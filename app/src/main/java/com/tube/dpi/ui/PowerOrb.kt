package com.tube.dpi.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tube.ui.components.bouncyClickable
import com.tube.ui.glass.liquidGlass
import com.tube.ui.theme.Motion
import com.tube.ui.theme.Shapes
import com.tube.ui.theme.TubeIcons
import com.tube.ui.theme.TubeTheme

enum class OrbState { Off, Starting, On, Failed }

/**
 * Большая стеклянная кнопка включения. Включено — зелёное свечение и полное кольцо,
 * запуск — бегущая дуга (единственная бесконечная анимация, только пока идёт запуск).
 */
@Composable
fun PowerOrb(state: OrbState, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, diameter: Dp = 236.dp) {
    val colors = TubeTheme.colors
    val on = state == OrbState.On
    val glow by animateFloatAsState(if (on) 1f else 0f, Motion.soft(), label = "glow")
    val ring by animateFloatAsState(if (on) 1f else 0f, Motion.soft(), label = "ring")
    val tint by animateColorAsState(
        when (state) {
            OrbState.On, OrbState.Starting -> colors.accent
            OrbState.Failed -> colors.danger
            OrbState.Off -> colors.textSecondary
        },
        Motion.snappy(),
        label = "tint",
    )
    val spin = if (state == OrbState.Starting) {
        rememberInfiniteTransition(label = "spin").animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing)),
            label = "angle",
        ).value
    } else {
        null
    }
    val accent = colors.accent
    val track = colors.chip
    Box(
        modifier
            .size(diameter)
            .drawBehind {
                if (glow > 0.01f) {
                    drawCircle(
                        Brush.radialGradient(
                            listOf(accent.copy(alpha = 0.42f * glow), accent.copy(alpha = 0.12f * glow), Color.Transparent),
                            center = center,
                            radius = size.minDimension / 2,
                        ),
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(diameter * 0.78f)
                .semantics { contentDescription = label }
                .bouncyClickable(pressedScale = 0.93f, glowColor = accent, glowShape = Shapes.Pill, onClick = onClick)
                .liquidGlass(Shapes.Pill)
                .drawWithContent {
                    drawContent()
                    val stroke = (diameter * 0.03f).toPx()
                    val inset = (diameter * 0.06f).toPx()
                    val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
                    val topLeft = Offset(inset, inset)
                    drawArc(track, 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
                    when {
                        spin != null -> drawArc(tint, spin - 90f, 90f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                        ring > 0.01f -> drawArc(tint, -90f, 360f * ring, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                TubeIcons.Power,
                contentDescription = null,
                tint = tint,
                modifier = Modifier
                    .size(diameter * 0.29f)
                    .graphicsLayer {
                        val s = 1f + 0.06f * glow
                        scaleX = s
                        scaleY = s
                    },
            )
        }
    }
}

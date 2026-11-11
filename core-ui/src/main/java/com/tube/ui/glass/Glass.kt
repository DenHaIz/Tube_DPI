package com.tube.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tube.ui.theme.TubeTheme
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.glass.GlassStyle
import dev.chrisbanes.haze.glass.hazeGlass

/** Общее состояние Haze: контент экрана — источник, стеклянные панели — потребители. */
val LocalHazeState = staticCompositionLocalOf<HazeState?> { null }

/**
 * Жидкое стекло: размытие + преломление по краям + блики (Haze Glass).
 * Без «засвета» под пальцем: отклик на нажатие рисуют сами кнопки (мягкий цветной отсвет).
 * На Android 13+ работает на AGSL-шейдере, на Android 12 Haze автоматически
 * переключается на упрощённый вариант с размытием.
 */
@Composable
fun Modifier.liquidGlass(
    shape: RoundedCornerShape,
    tint: Color = TubeTheme.colors.glassTint,
    stroke: Boolean = true,
): Modifier {
    val state = LocalHazeState.current
    val strokeColor = TubeTheme.colors.glassStroke
    val dark = TubeTheme.colors.isDark
    val style = remember(shape, tint, dark) {
        val glassShape = shape
        val glassTint = tint
        GlassStyle.regular.then {
            shape(glassShape)
            tint(glassTint)
            // Тема приложения может отличаться от системной — задаём отклик стекла явно.
            if (dark) {
                specularIntensity(0.38f)
                edgeShadow(Color.Black.copy(alpha = 0.32f))
                ambientResponse(0.08f)
                contrast(0.08f)
                whitePoint(-0.22f)
                chromaMultiplier(1.1f)
            } else {
                specularIntensity(0.48f)
                edgeShadow(Color.Black.copy(alpha = 0.14f))
                ambientResponse(0.15f)
                contrast(0f)
                whitePoint(0.55f)
                chromaMultiplier(1.5f)
            }
        }
    }
    val glass = if (state != null) {
        hazeGlass(
            input = HazeInput.Sources(state),
            style = style,
        )
    } else {
        background(tint.copy(alpha = 0.9f), shape)
    }
    return glass
        .then(if (stroke) Modifier.glassRim(shape, strokeColor) else Modifier)
        .clip(shape)
}

/** Тонкая светлая кромка: ярче сверху, тает книзу — подчёркивает объём стекла. */
fun Modifier.glassRim(shape: RoundedCornerShape, color: Color, width: Dp = 1.dp): Modifier =
    drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val brush = Brush.linearGradient(
            colors = listOf(color, color.copy(alpha = color.alpha * 0.15f), color.copy(alpha = color.alpha * 0.45f)),
            start = Offset.Zero,
            end = Offset(size.width * 0.4f, size.height),
        )
        val strokeWidth = width.toPx()
        onDrawWithContent {
            drawContent()
            drawOutline(outline, brush, style = Stroke(strokeWidth))
        }
    }

/** Матовая карточка для элементов списка: дешевле настоящего стекла и хорошо смотрится поверх фона. */
@Composable
fun FrostedCard(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(24.dp),
    color: Color = TubeTheme.colors.card,
    content: @Composable BoxScope.() -> Unit,
) {
    val border = TubeTheme.colors.cardBorder
    Box(
        modifier = modifier
            .clip(shape)
            .background(color)
            .border(1.dp, Brush.verticalGradient(listOf(border, border.copy(alpha = border.alpha * 0.2f))), shape),
        content = content,
    )
}

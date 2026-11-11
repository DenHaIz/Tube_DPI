package com.tube.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tube.ui.theme.Motion
import kotlin.math.max
import kotlin.math.min

/**
 * Рисует «жидкую каплю» под выбранным элементом ряда из [count] равных ячеек.
 * Передний край догоняет цель быстрой пружиной, задний — медленной, поэтому капля
 * растягивается в движении и стягивается на месте.
 */
fun Modifier.liquidIndicator(
    count: Int,
    leading: () -> Float,
    lagging: () -> Float,
    visibility: () -> Float = { 1f },
    color: Color,
    inset: Dp = 4.dp,
    verticalInset: Dp = inset,
): Modifier = drawBehind {
    val alpha = visibility()
    if (alpha <= 0.01f || count == 0) return@drawBehind
    val cell = size.width / count
    val a = leading()
    val b = lagging()
    val left = min(a, b) * cell + inset.toPx()
    val right = (max(a, b) + 1f) * cell - inset.toPx()
    val top = verticalInset.toPx()
    val height = size.height - top * 2
    // Растянутая капля чуть «худеет» по высоте — как жидкость.
    val stretch = ((right - left) / (cell - inset.toPx() * 2) - 1f).coerceIn(0f, 2f)
    val squeeze = height * 0.08f * stretch
    drawRoundRect(
        color = color.copy(alpha = color.alpha * alpha),
        topLeft = Offset(left, top + squeeze / 2),
        size = Size(right - left, height - squeeze),
        cornerRadius = CornerRadius((height - squeeze) / 2),
    )
}

@Composable
fun LiquidSegmented(
    count: Int,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    pillColor: Color,
    modifier: Modifier = Modifier,
    inset: Dp = 4.dp,
    item: @Composable RowScope.(index: Int, selected: Boolean) -> Unit,
) {
    val lead = animateFloatAsState(selectedIndex.toFloat(), Motion.leading(), label = "lead")
    val lag = animateFloatAsState(selectedIndex.toFloat(), Motion.lagging(), label = "lag")
    val haptics = LocalHapticFeedback.current
    Row(
        modifier
            .liquidIndicator(count, { lead.value }, { lag.value }, color = pillColor, inset = inset),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { index ->
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable(interactionSource = null, indication = null, role = Role.Tab) {
                        if (index != selectedIndex) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        onSelect(index)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) { item(index, index == selectedIndex) }
            }
        }
    }
}

package com.tube.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring

/**
 * Единые пружины приложения. Все анимации — физические (spring), без жёстких длительностей:
 * так движение естественно подхватывает прерывания жестом.
 */
object Motion {
    /** Плавные перемещения панелей и контента. */
    fun <T> soft(): SpringSpec<T> = spring(dampingRatio = 0.86f, stiffness = 320f)

    /** Упругий отклик на касание: чекбоксы, кнопки, капля-индикатор. */
    fun <T> bouncy(): SpringSpec<T> = spring(dampingRatio = 0.58f, stiffness = 520f)

    /** Быстрые мелкие изменения: цвет, прозрачность. */
    fun <T> snappy(): SpringSpec<T> = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 900f)

    /** «Отстающий» край жидкого индикатора — даёт эффект растяжения капли. */
    fun <T> lagging(): SpringSpec<T> = spring(dampingRatio = 0.8f, stiffness = 180f)

    /** «Ведущий» край жидкого индикатора. */
    fun <T> leading(): SpringSpec<T> = spring(dampingRatio = 0.75f, stiffness = 700f)
}

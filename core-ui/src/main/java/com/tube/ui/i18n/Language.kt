package com.tube.ui.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import java.util.Locale

/** Язык интерфейса и уведомлений. */
enum class AppLanguage(val title: String, val locale: Locale) {
    Russian("Русский", Locale.forLanguageTag("ru")),
    English("English", Locale.ENGLISH);

    fun pick(ru: String, en: String): String = if (this == English) en else ru

    /** Слово после числа: русские формы для 1, 2–4 и 5+, английские — ед./мн. */
    fun plural(n: Int, one: String, few: String, many: String, enOne: String, enMany: String): String {
        if (this == English) return if (n == 1) enOne else enMany
        val mod100 = n % 100
        val mod10 = n % 10
        return when {
            mod100 in 11..14 -> many
            mod10 == 1 -> one
            mod10 in 2..4 -> few
            else -> many
        }
    }
}

/** Язык интерфейса; меняется в настройках и сразу перерисовывает весь экран. */
val LocalLanguage = staticCompositionLocalOf { AppLanguage.Russian }

/** Строка на языке приложения: русский и английский варианты стоят рядом, прямо в месте использования. */
@Composable
@ReadOnlyComposable
fun tr(ru: String, en: String): String = LocalLanguage.current.pick(ru, en)

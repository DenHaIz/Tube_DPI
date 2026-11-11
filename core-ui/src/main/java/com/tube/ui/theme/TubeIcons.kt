package com.tube.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Собственный минималистичный набор линейных иконок (скруглённые концы, толщина 2) — как у Woody. */
object TubeIcons {
    val Power by lazy { icon("Power", "M12 3v8M7.05 6.05a7 7 0 1 0 9.9 0") }
    val Play by lazy { icon("Play", "M8 5.5v13l10.5-6.5z") }
    val Apps by lazy {
        icon(
            "Apps",
            "M6 4h3a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z" +
                "M15 4h3a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2h-3a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z" +
                "M6 13h3a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2v-3a2 2 0 0 1 2-2z" +
                "M15 13h3a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2h-3a2 2 0 0 1-2-2v-3a2 2 0 0 1 2-2z",
        )
    }
    val Sliders by lazy { icon("Sliders", "M4 7h9M17 7h3M4 17h3M11 17h9M15 5v4M9 15v4") }
    val Bolt by lazy { icon("Bolt", "M13 3L5 13.5h6L10 21l8-10.5h-6z") }
    val Search by lazy { icon("Search", "M11 4a7 7 0 1 0 0 14a7 7 0 1 0 0-14zM16.5 16.5L20 20") }
    val Check by lazy { icon("Check", "M5 12.5l4.5 4.5L19 7.5") }
    val Close by lazy { icon("Close", "M6 6l12 12M18 6L6 18") }
    val ChevronRight by lazy { icon("ChevronRight", "M9 6l6 6-6 6") }
    val ChevronDown by lazy { icon("ChevronDown", "M6 9l6 6 6-6") }
    val Eye by lazy { icon("Eye", "M2.5 12s3.5-6.5 9.5-6.5S21.5 12 21.5 12s-3.5 6.5-9.5 6.5S2.5 12 2.5 12zM12 9.5a2.5 2.5 0 1 0 0 5a2.5 2.5 0 1 0 0-5z") }
    val Shield by lazy { icon("Shield", "M12 3l7 3v5.5c0 4.5-3 8-7 9.5c-4-1.5-7-5-7-9.5V6z") }
    val Battery by lazy { icon("Battery", "M5 7h12a2 2 0 0 1 2 2v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V9a2 2 0 0 1 2-2zM21 10.5v3") }
    val Edit by lazy { icon("Edit", "M4 20h4L19 9l-4-4L4 16zM13.5 6.5l4 4") }
    val Info by lazy { icon("Info", "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18zM12 11v5M12 8h.01") }
    val Terminal by lazy { icon("Terminal", "M5 5h14a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2zM7 10l3 2-3 2M12 15h5") }

    private fun icon(name: String, pathData: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).addPath(
            pathData = addPathNodes(pathData),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ).build()
}

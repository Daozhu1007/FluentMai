package dev.fluentmai.android.feature.scores

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import dev.fluentmai.android.core.model.Difficulty

/** Shared difficulty tokens for B50 cards, chart cards, plate rows and details. */
internal fun Difficulty.accentColor(): Color = when (this) {
    Difficulty.BASIC -> Color(0xFF2F9E44)
    Difficulty.ADVANCED -> Color(0xFFD9480F)
    Difficulty.EXPERT -> Color(0xFFE03131)
    Difficulty.MASTER -> Color(0xFF7B2CBF)
    Difficulty.RE_MASTER -> Color(0xFFC5A6E0)
}

// Keep white labels legible on the pale Re:MASTER background.
internal fun difficultyLabelShadow(color: Color): Shadow =
    if (color == Difficulty.RE_MASTER.accentColor()) {
        Shadow(color = Color(0xFF604079), blurRadius = 3f)
    } else Shadow.None

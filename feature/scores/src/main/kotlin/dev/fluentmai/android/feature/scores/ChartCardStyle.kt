package dev.fluentmai.android.feature.scores

import androidx.compose.foundation.border
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

// Read the resolved app theme, including a user's override of the system theme.
@Composable
private fun isLightChartTheme() = MaterialTheme.colorScheme.background.luminance() > 0.5f

@Composable
internal fun Modifier.chartCardOutline(): Modifier = if (isLightChartTheme()) {
    border(1.dp, Color.White, CardDefaults.elevatedShape)
} else this

@Composable
internal fun chartCardElevation() = if (isLightChartTheme()) {
    CardDefaults.elevatedCardElevation(
        defaultElevation = 0.dp,
        pressedElevation = 0.dp,
        focusedElevation = 0.dp,
        hoveredElevation = 0.dp,
        draggedElevation = 0.dp,
        disabledElevation = 0.dp,
    )
} else CardDefaults.elevatedCardElevation()

@Composable
fun chartCardContainerColor(darkContainer: Color = MaterialTheme.colorScheme.surface): Color =
    if (isLightChartTheme()) Color(0xFFF3F6F8) else darkContainer

@Composable
internal fun chartCardColors(darkContainer: Color = Color.Unspecified) =
    CardDefaults.elevatedCardColors(
        containerColor = chartCardContainerColor(darkContainer),
    )

@Composable
internal fun plateRecordCardColors(completed: Boolean) = CardDefaults.elevatedCardColors(
    containerColor = plateRecordBackgroundColor(completed, isLightChartTheme()),
    contentColor = MaterialTheme.colorScheme.onSurface,
)

// Use the resolved app theme, not the device theme, for both manual and system modes.
internal fun plateRecordBackgroundColor(completed: Boolean, lightTheme: Boolean): Color = when {
    lightTheme && completed -> Color(0xFFE1F2E7)
    lightTheme -> Color(0xFFFBE5E5)
    completed -> Color(0xFF193C30)
    else -> Color(0xFF45282D)
}

package dev.fluentmai.android.feature.scores

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class FavoriteFilter(val label: String) {
    All("全部谱面"), Favorites("仅收藏"), Unfavorites("仅未收藏");
    fun next() = entries[(ordinal + 1) % entries.size]
    fun matches(favorite: Boolean) = this == All || (this == Favorites) == favorite
}

internal class ChartFavoriteStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("chart_favorites", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(prefs.getStringSet("identities", emptySet()).orEmpty().toSet())
    val values = state.asStateFlow()
    fun toggle(key: String) {
        val updated = if (key in state.value) state.value - key else state.value + key
        prefs.edit().putStringSet("identities", updated).apply()
        state.value = updated
    }
}

@Composable internal fun rememberFavoriteStore(): ChartFavoriteStore {
    val context = LocalContext.current.applicationContext
    return remember(context) { ChartFavoriteStore(context) }
}

@Composable internal fun FavoriteIcon(active: Boolean, excluded: Boolean, label: String) {
    Box(Modifier.size(24.dp)) {
        Icon(if (active) Icons.Filled.Star else Icons.Outlined.StarBorder, label,
            tint = if (active) Color(0xFFFFC107) else MaterialTheme.colorScheme.onSurfaceVariant)
        if (excluded) Canvas(Modifier.size(24.dp)) {
            drawLine(Color(0xFFE54848), Offset(2.dp.toPx(), 22.dp.toPx()), Offset(22.dp.toPx(), 2.dp.toPx()), 2.dp.toPx())
        }
    }
}

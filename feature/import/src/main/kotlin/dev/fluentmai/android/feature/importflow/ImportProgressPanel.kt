package dev.fluentmai.android.feature.importflow

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.fluentmai.android.core.model.*

@Composable
internal fun ImportProgressPanel(progress: ImportProgress, running: Boolean) {
    val track = if (MaterialTheme.colorScheme.background.luminance() > .5f) Color(0xFFE0E3E5) else Color(0xFF343B40)
    val green = MaterialTheme.colorScheme.primary
    val animatedFraction = key(progress.stage) { animateFloatAsState(progress.fraction ?: 0f, tween(220), label = "importPages").value }
    val fraction = if (running) animatedFraction else progress.fraction ?: 0f
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("加载进度 · ${progress.stage.label}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium)
            Text("${progress.stage.ordinal + 1}/${ImportStage.entries.size} 阶段", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val bar = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).testTag("import-stage-progress")
        if (progress.fraction == null && running) {
            LinearProgressIndicator(modifier = bar, color = green, trackColor = track)
        } else {
            LinearProgressIndicator(progress = { fraction }, modifier = bar, color = green, trackColor = track)
        }
        if (progress.totalPages != null || progress.processedPages > 0) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(progress.countLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                progress.percentageLabel?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = green) }
            }
        }
        progress.pageName?.let { name ->
            Text("当前页面：$name · ${if (!running && progress.pageState in listOf(ImportPageState.Loading, ImportPageState.Parsing)) "已停止" else progress.pageState.label}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (running && progress.pageState in listOf(ImportPageState.Loading, ImportPageState.Parsing)) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).testTag("import-current-page-progress"),
                    color = green, trackColor = track)
            }
        }
        if (progress.failedPages > 0) Text("其中 ${progress.failedPages} 页未完整读取", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error)
        Text(progress.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

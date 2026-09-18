package dev.fluentmai.android.feature.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.fluentmai.android.core.model.PlayRecord
import java.time.*

internal fun playCountsByDay(records: List<PlayRecord>): Map<LocalDate, Int> = records.distinctBy { it.id }
    .groupingBy { Instant.ofEpochMilli(it.playedAt).atZone(ZoneId.of("Asia/Shanghai")).toLocalDate() }.eachCount()

@Composable
internal fun PlayActivityHeatmap(records: List<PlayRecord>) {
    val counts = remember(records) { playCountsByDay(records) }
    var monthOffset by rememberSaveable { mutableIntStateOf(0) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val month = YearMonth.now(ZoneId.of("Asia/Shanghai")).plusMonths(monthOffset.toLong())
    val maximum = counts.filterKeys { YearMonth.from(it) == month }.values.maxOrNull()?.coerceAtLeast(1) ?: 1
    val isLight = MaterialTheme.colorScheme.surface.luminance() > .5f
    val pale = if (isLight) Color(0xFFC8E6C9) else Color(0xFF91BB9A)
    val deep = if (isLight) Color(0xFF1B5E20) else Color(0xFF16522C)
    OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("游玩热力图", style = MaterialTheme.typography.titleLarge)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { monthOffset--; selected = null }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "上个月") }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Text("${month.year} 年 ${month.monthValue} 月") }
                IconButton(onClick = { monthOffset++; selected = null }, enabled = monthOffset < 0) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "下个月") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                listOf("一", "二", "三", "四", "五", "六", "日").forEach { label ->
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Text(label, style = MaterialTheme.typography.labelSmall) }
                }
            }
            val offset = month.atDay(1).dayOfWeek.value - 1
            val rows = (offset + month.lengthOfMonth() + 6) / 7
            repeat(rows) { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    repeat(7) { column ->
                        val day = row * 7 + column - offset + 1
                        if (day !in 1..month.lengthOfMonth()) Spacer(Modifier.weight(1f).aspectRatio(1f))
                        else {
                            val date = month.atDay(day)
                            val count = counts[date]
                            val color = if (count == null) MaterialTheme.colorScheme.surfaceVariant else
                                lerp(pale, deep, if (maximum <= 1) 1f else (count - 1f) / (maximum - 1f))
                            val label = "$date · ${count?.let { "已记录 $it 次游玩" } ?: "未采集到记录"}"
                            Box(Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(5.dp)).background(color)
                                .clickable { selected = date.toString() }.semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
                                Text(day.toString(), style = MaterialTheme.typography.labelMedium,
                                    color = if (color.luminance() > .45f) Color(0xFF163A23) else Color.White)
                            }
                        }
                    }
                }
            }
            selected?.let { date -> Text("$date · ${counts[LocalDate.parse(date)]?.let { "已记录 $it 次游玩" } ?: "未采集到记录"}") }
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("少", style = MaterialTheme.typography.labelSmall)
                repeat(5) { Box(Modifier.size(14.dp).background(lerp(pale, deep, it / 4f), RoundedCornerShape(2.dp))) }
                Text("多", style = MaterialTheme.typography.labelSmall)
            }
            Text("按实际游玩日期统计已同步记录。灰色表示未采集到记录，不一定是当天未游玩。每次导入会补充官方仍保留的最近记录。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

package dev.fluentmai.android.feature.scores

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.fluentmai.android.core.model.*

enum class ComponentEditor { Thumbnail, Details }

@Composable
fun ComponentEditorDialog(editor: ComponentEditor, charts: List<ChartRecord> = emptyList(), onDismiss: () -> Unit) {
    val store = LocalComponentSettingsState.current
    var draft by remember(editor) { mutableStateOf(store.value) }
    var selectedSlot by remember { mutableStateOf<Int?>(null) }
    val chart = remember { previewChart() }
    val valueScale = remember(charts) { ThumbnailValueScale.fromCharts(charts) }
    val score = remember { ScoreRecord("component-preview", chart.songId, chart.title, chart.songType, chart.difficulty,
        chart.level, chart.levelIndex, 100.5678, 2500, "fc", "fs", "preview", 0L, playCount = 12) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 700.dp).fillMaxWidth(.96f).then(
            if (editor == ComponentEditor.Details) Modifier.fillMaxHeight(.9f)
            else Modifier.heightIn(max = (LocalConfiguration.current.screenHeightDp * .9f).dp)), shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(vertical = 12.dp)) {
                Text(if (editor == ComponentEditor.Thumbnail) "修改谱面缩略框" else "修改谱面详情页",
                    Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(if (editor == ComponentEditor.Thumbnail) "点击下方四个属性位置更换内容，不可重复。" else "点击属性标记红叉，再点一次恢复。红叉属性将不再显示。",
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                CompositionLocalProvider(LocalComponentSettings provides draft) {
                    if (editor == ComponentEditor.Thumbnail) {
                        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            ChartCard(chart, score, onClick = {}, fields = draft.thumbnailFields, onEditSlot = { selectedSlot = it }, valueScale = valueScale)
                            Text("模板数据仅作示例；保存后应用于所有谱面搜索结果。",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        CompositionLocalProvider(LocalDetailEditor provides { field: String ->
                            draft = draft.copy(hiddenDetails = if (field in draft.hiddenDetails) draft.hiddenDetails - field else draft.hiddenDetails + field)
                        }) {
                            ChartDetailScreen(ChartIdentity.from(chart), listOf(chart), listOf(score),
                                aliasStatus = AliasDataStatus("水鱼别名库", 0L, "示例版本", 1000, 2000, 0),
                                onBack = {}, onChartSelected = {}, modifier = Modifier.weight(1f))
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { draft = if (editor == ComponentEditor.Thumbnail)
                        draft.copy(thumbnailFields = ComponentSettings.DefaultThumbnailFields) else draft.copy(hiddenDetails = emptySet()) }) { Text("默认") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Button(onClick = { store.save(draft); onDismiss() }) { Text("保存") }
                }
            }
        }
    }
    selectedSlot?.let { slot ->
        AlertDialog(onDismissRequest = { selectedSlot = null }, title = { Text("选择显示属性") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ThumbnailField.entries.forEach { field ->
                    val current = draft.thumbnailFields[slot] == field
                    val used = field in draft.thumbnailFields && !current
                    TextButton(enabled = !used, onClick = { draft = draft.replaceSlot(slot, field); selectedSlot = null }, modifier = Modifier.fillMaxWidth()) {
                        Text(field.label, Modifier.weight(1f))
                        Text(if (current) "当前" else if (used) "已使用" else "")
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { selectedSlot = null }) { Text("取消") } })
    }
}

internal fun previewChart() = ChartRecord(
    songId = 1, title = "谱面样式预览", artist = "示例曲师", genre = "原创乐曲", bpm = 180,
    songVersion = 26000, songVersionName = "DX2026", chartVersion = 26000, chartVersionName = "DX2026",
    songType = SongType.DX, difficulty = Difficulty.MASTER, levelIndex = 3, level = "14", levelValue = 14.3,
    noteDesigner = "示例谱师", notes = ChartNotes(1000, 500, 100, 200, 150, 50),
    fittedConstant = 14.42, fittedUpdatedAt = 0L,
)

@Composable
fun rememberResetChartFilters(): () -> Unit {
    val model: ChartQueryViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val playerModel: PlayerRecordsViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    return { model.resetFilters(); model.saveScroll(0, 0); playerModel.resetSettings() }
}

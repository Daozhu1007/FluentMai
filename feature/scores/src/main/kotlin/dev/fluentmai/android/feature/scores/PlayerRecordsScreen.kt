package dev.fluentmai.android.feature.scores

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Pending
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.fluentmai.android.core.model.ChartIdentity
import dev.fluentmai.android.core.model.ChartRecord
import dev.fluentmai.android.core.model.Difficulty
import dev.fluentmai.android.core.model.MaimaiMajorVersion
import dev.fluentmai.android.core.model.PlateBlocker
import dev.fluentmai.android.core.model.PlateKind
import dev.fluentmai.android.core.model.PlateProgress
import dev.fluentmai.android.core.model.maimaiPlateVersionFor
import dev.fluentmai.android.core.model.PlayerChartRecord
import dev.fluentmai.android.core.model.ScoreRecord
import dev.fluentmai.android.core.model.resolveCurrentMaimaiVersion
import java.util.Locale

private const val PLATE_SCROLL_TO_TOP_ANIMATION_START_INDEX = 8

enum class PlayerProgressDestination { PLATES, RECOMMENDATIONS }

@Composable
fun PlayerProgressScreen(
    destination: PlayerProgressDestination,
    scores: List<ScoreRecord>,
    charts: List<ChartRecord>,
    majorVersions: List<MaimaiMajorVersion>,
    onDestinationChanged: (PlayerProgressDestination) -> Unit,
    onBack: () -> Unit,
    onChartSelected: (ChartIdentity) -> Unit = {},
    scrollToTopRequestId: Int = 0,
    modifier: Modifier = Modifier,
) {
    val recordsViewModel: PlayerRecordsViewModel = viewModel()
    val state by recordsViewModel.uiState.collectAsState()
    val operatingVersion = remember(majorVersions, charts) {
        resolveCurrentMaimaiVersion(majorVersions, charts)
    }
    SideEffect {
        recordsViewModel.submitCatalog(charts, scores, majorVersions, operatingVersion)
    }

    when (destination) {
        PlayerProgressDestination.PLATES -> PlateContent(
            state = state,
            viewModel = recordsViewModel,
            onChartSelected = onChartSelected,
            onDestinationChanged = onDestinationChanged,
            onBack = onBack,
            scrollToTopRequestId = scrollToTopRequestId,
            modifier = modifier,
        )
        PlayerProgressDestination.RECOMMENDATIONS -> RatingRecommendationsContent(
            state = state,
            viewModel = recordsViewModel,
            onChartSelected = onChartSelected,
            destination = destination,
            onDestinationChanged = onDestinationChanged,
            onBack = onBack,
            scrollToTopRequestId = scrollToTopRequestId,
            modifier = modifier,
        )
    }
}

@Composable
private fun ChoiceRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
private fun <T> FilterMenu(
    label: String,
    values: List<Pair<T, String>>,
    onSelected: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Filled.ExpandMore, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            values.forEach { (value, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        expanded = false
                        onSelected(value)
                    },
                )
            }
        }
    }
}

@Composable
private fun PlateContent(
    state: PlayerRecordsUiState,
    viewModel: PlayerRecordsViewModel,
    onChartSelected: (ChartIdentity) -> Unit,
    onDestinationChanged: (PlayerProgressDestination) -> Unit,
    onBack: () -> Unit,
    scrollToTopRequestId: Int,
    modifier: Modifier,
) {
    val progress = state.plateProgress
    val blockers = remember(progress) { progress?.blockers?.associateBy { it.record.identity }.orEmpty() }
    val displayed = remember(
        progress,
        blockers,
        state.selectedPlateDifficulty,
        state.plateIncompleteOnly,
        state.plateSort,
    ) {
        progress?.eligibleRecords.orEmpty()
            .asSequence()
            .filter { state.selectedPlateDifficulty == null || it.chart.difficulty == state.selectedPlateDifficulty }
            .filter { !state.plateIncompleteOnly || it.identity in blockers }
            .sortedWith(state.plateSort.comparator())
            .toList()
    }
    val grouped = remember(displayed, state.plateSort) {
        if (state.plateSort == PlateListSort.LEVEL_DESC) displayed.groupBy { it.chart.level }
        else linkedMapOf("谱面" to displayed)
    }
    val listState = rememberLazyListState()
    var handledScrollToTopRequestId by remember { mutableStateOf(scrollToTopRequestId) }
    LaunchedEffect(scrollToTopRequestId) {
        if (scrollToTopRequestId != handledScrollToTopRequestId) {
            handledScrollToTopRequestId = scrollToTopRequestId
            listState.animatePlateScrollToTop()
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            PlateHeader(
                destination = PlayerProgressDestination.PLATES,
                onDestinationChanged = onDestinationChanged,
                onBack = onBack,
            )
        }
        item { PlateControls(state, viewModel) }
        item { progress?.let { PlateSummary(it) } ?: EmptyRecords("正在计算牌子进度") }
        if (displayed.isEmpty() && progress != null) {
            item {
                EmptyRecords(if (progress.isComplete) "当前条件已全部完成" else "当前查看条件没有谱面")
            }
        }
        grouped.forEach { (group, records) ->
            if (records.isNotEmpty()) {
                item(key = "group-$group") {
                    Text(group, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                items(records, key = { it.identity.saveableKey() }) { record ->
                    PlateRecordCard(
                        record = record,
                        blocker = blockers[record.identity],
                        onClick = { onChartSelected(record.identity) },
                    )
                }
            }
        }
    }
}

private suspend fun LazyListState.animatePlateScrollToTop() {
    if (firstVisibleItemIndex > PLATE_SCROLL_TO_TOP_ANIMATION_START_INDEX) {
        scrollToItem(PLATE_SCROLL_TO_TOP_ANIMATION_START_INDEX)
    }
    animateScrollToItem(0)
}

@Composable
private fun PlateHeader(
    destination: PlayerProgressDestination,
    onDestinationChanged: (PlayerProgressDestination) -> Unit,
    onBack: () -> Unit,
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回首页")
                }
                Text("牌子进度", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
            Text(
                "规则来自 SEGA 官方公告；缺少可核验数据时不会宣布完成。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PlayerProgressSwitch(destination, onDestinationChanged)
        }
    }
}

@Composable
internal fun PlayerProgressSwitch(
    destination: PlayerProgressDestination,
    onChange: (PlayerProgressDestination) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            FilterChip(
                selected = destination == PlayerProgressDestination.PLATES,
                onClick = { onChange(PlayerProgressDestination.PLATES) },
                label = { Text("牌子进度") },
            )
        }
        item {
            FilterChip(
                selected = destination == PlayerProgressDestination.RECOMMENDATIONS,
                onClick = { onChange(PlayerProgressDestination.RECOMMENDATIONS) },
                label = { Text("推分建议") },
            )
        }
    }
}

@Composable
private fun PlateControls(state: PlayerRecordsUiState, viewModel: PlayerRecordsViewModel) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("牌子类型", style = MaterialTheme.typography.labelLarge)
            ChoiceRow {
                PlateKind.entries.forEach { kind ->
                    FilterChip(
                        selected = state.selectedPlate == kind,
                        onClick = { viewModel.updatePlateKind(kind) },
                        label = { Text(kind.displayName) },
                    )
                }
            }
            if (state.selectedPlate != PlateKind.CONQUEROR) {
                Text("版本分类", style = MaterialTheme.typography.labelLarge)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.availableVersions, key = { it.id }) { version ->
                        val prefixes = maimaiPlateVersionFor(version.id)?.prefixes.orEmpty()
                        FilterChip(
                            selected = state.selectedPlateVersionId == version.id,
                            onClick = { viewModel.updatePlateVersion(version.id) },
                            label = {
                                Text(
                                    buildString {
                                        if (prefixes.isNotEmpty()) append(prefixes.joinToString(" / ")).append(" · ")
                                        append(version.name)
                                    },
                                )
                            },
                        )
                    }
                }
            }
            Text("查看条件", style = MaterialTheme.typography.labelLarge)
            ChoiceRow {
                FilterChip(
                    selected = state.plateIncompleteOnly,
                    onClick = { viewModel.updatePlateIncompleteOnly(!state.plateIncompleteOnly) },
                    label = { Text(if (state.plateIncompleteOnly) "只看未完成" else "显示全部") },
                )
                FilterChip(
                    selected = state.selectedPlateDifficulty == null,
                    onClick = { viewModel.updatePlateDifficulty(null) },
                    label = { Text("全部难度") },
                )
                Difficulty.entries.forEach { difficulty ->
                    FilterChip(
                        selected = state.selectedPlateDifficulty == difficulty,
                        onClick = { viewModel.updatePlateDifficulty(difficulty) },
                        label = { Text(difficulty.shortName()) },
                    )
                }
                FilterMenu(
                    label = state.plateSort.displayName(),
                    values = PlateListSort.entries.map { it to it.displayName() },
                    onSelected = viewModel::updatePlateSort,
                )
            }
        }
    }
}

@Composable
private fun PlateSummary(progress: PlateProgress) {
    val accent = if (progress.isComplete) Color(0xFF2B8A3E) else MaterialTheme.colorScheme.primary
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = when {
                        !progress.dataSufficient -> Icons.Filled.Warning
                        progress.isComplete -> Icons.Filled.CheckCircle
                        else -> Icons.Filled.Pending
                    },
                    contentDescription = null,
                    tint = if (progress.dataSufficient) accent else MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (progress.kind == PlateKind.CONQUEROR) "霸者 · 全标准谱面" else progress.plateName,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
            if (!progress.dataSufficient) {
                Text("数据不足：${progress.dataMessage}", color = MaterialTheme.colorScheme.error)
            } else {
                LinearProgressIndicator(
                    progress = { progress.completionFraction.toFloat() },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                    color = accent,
                    trackColor = ProgressIndicatorDefaults.linearTrackColor,
                )
                Text(
                    "${progress.completedCount} / ${progress.requiredCount} · " +
                        "剩余 ${progress.remainingCount} · " +
                        "%.1f%%".format(Locale.US, progress.completionFraction * 100),
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    if (progress.isComplete) "已满足当前曲库可核验的全部条件" else "下方列出阻塞完成的具体谱面与差距",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PlateRecordCard(
    record: PlayerChartRecord,
    blocker: PlateBlocker?,
    onClick: () -> Unit,
) {
    ElevatedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(48.dp),
                shape = RoundedCornerShape(12.dp),
                color = record.chart.difficulty.accentColor().copy(alpha = 0.16f),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(record.chart.level, fontWeight = FontWeight.Bold, color = record.chart.difficulty.accentColor())
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(record.chart.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${record.chart.difficulty.shortName()} · ${record.chart.songType.divingFishName} · ID ${record.chart.songId}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    blocker?.let { "${it.currentValue} · ${it.requirementGap}" } ?: "已完成",
                    color = if (blocker == null) Color(0xFF2B8A3E) else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun EmptyRecords(text: String) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text,
            modifier = Modifier.padding(24.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun PlateListSort.displayName(): String = when (this) {
    PlateListSort.LEVEL_DESC -> "按等级分组"
    PlateListSort.TITLE_ASC -> "按曲名"
    PlateListSort.SONG_ID_ASC -> "按歌曲 ID"
}

private fun PlateListSort.comparator(): Comparator<PlayerChartRecord> = when (this) {
    PlateListSort.LEVEL_DESC -> compareByDescending<PlayerChartRecord> { it.chart.levelValue ?: -1.0 }
        .thenBy { it.chart.title }
    PlateListSort.TITLE_ASC -> compareBy<PlayerChartRecord> { it.chart.title }.thenByDescending { it.chart.levelValue ?: -1.0 }
    PlateListSort.SONG_ID_ASC -> compareBy<PlayerChartRecord> { it.chart.songId }.thenBy { it.chart.levelIndex }
}

private fun Difficulty.shortName(): String = when (this) {
    Difficulty.BASIC -> "BASIC"
    Difficulty.ADVANCED -> "ADV"
    Difficulty.EXPERT -> "EXP"
    Difficulty.MASTER -> "MAS"
    Difficulty.RE_MASTER -> "Re:MAS"
}

private fun ChartIdentity.saveableKey(): String = "$songId-${songType.name}-${difficulty.name}"

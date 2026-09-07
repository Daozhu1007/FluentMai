package dev.fluentmai.android.feature.scores

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import dev.fluentmai.android.core.model.ChartRecord
import dev.fluentmai.android.core.model.ChartIdentity
import dev.fluentmai.android.core.model.AchievementRank
import dev.fluentmai.android.core.model.Difficulty
import dev.fluentmai.android.core.model.FullComboStatus
import dev.fluentmai.android.core.model.FullSyncStatus
import dev.fluentmai.android.core.model.MaimaiMajorVersion
import dev.fluentmai.android.core.model.MaimaiRatedScore
import dev.fluentmai.android.core.model.ScoreRecord
import dev.fluentmai.android.core.model.SongAliasCatalog
import dev.fluentmai.android.core.model.SongType
import dev.fluentmai.android.core.model.buildMaimaiBestSet
import dev.fluentmai.android.core.model.calculateDxRating
import dev.fluentmai.android.core.model.matchChartsForScores
import dev.fluentmai.android.core.model.maimaiVersionNameFor
import dev.fluentmai.android.core.model.resolveCurrentMaimaiVersion
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScoresScreen(
    scores: List<ScoreRecord>,
    charts: List<ChartRecord>,
    majorVersions: List<MaimaiMajorVersion>,
    onOpenPlayedCharts: () -> Unit = {},
    onOpenPlates: () -> Unit = {},
    onOpenRecommendations: () -> Unit = {},
    onChartSelected: (ChartIdentity) -> Unit = {},
    scrollToTopRequestId: Int = 0,
    modifier: Modifier = Modifier,
) {
    val enrichedScores = remember(scores, charts) { enrichScores(scores, charts) }
    val currentVersion = remember(majorVersions, charts) {
        resolveCurrentMaimaiVersion(majorVersions, charts)
    }
    val bestSet = remember(enrichedScores, currentVersion) {
        buildMaimaiBestSet(enrichedScores, currentVersion)
    }
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    var handledScrollToTopRequestId by remember { mutableStateOf(scrollToTopRequestId) }
    LaunchedEffect(scrollToTopRequestId) {
        if (scrollToTopRequestId != handledScrollToTopRequestId) {
            handledScrollToTopRequestId = scrollToTopRequestId
            gridState.animateScrollToItem(0)
        }
    }

    LazyVerticalGrid(
        modifier = modifier.fillMaxSize(),
        state = gridState,
        columns = GridCells.Adaptive(320.dp),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            ScoreSummaryHeader(
                scoreCount = scores.size,
                chartCount = charts.size,
                bestRating = bestSet.rating,
            )
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            HomeActions(
                oldBestCount = bestSet.oldBest.size,
                newBestCount = bestSet.newBest.size,
                onJumpToOldBest = { scope.launch { gridState.animateScrollToItem(HOME_OLD_BEST_HEADER_INDEX) } },
                onJumpToNewBest = {
                    scope.launch { gridState.animateScrollToItem(HOME_NEW_BEST_HEADER_BASE_INDEX + bestSet.oldBest.size) }
                },
                onOpenPlayedCharts = onOpenPlayedCharts,
                onOpenPlates = onOpenPlates,
                onOpenRecommendations = onOpenRecommendations,
            )
        }

        if (scores.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                EmptyState(text = "还没有导入成绩")
            }
        } else {
            item(span = { GridItemSpan(maxLineSpan) }) {
                SectionTitle("旧版本 Best 35", bestSet.oldBest.size)
            }
            items(bestSet.oldBest, key = { "old-${it.score.id}" }) { item ->
                ScoreCard(item = item, rank = bestSet.oldBest.indexOf(item) + 1, onChartSelected = onChartSelected)
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                SectionTitle("当前版本 Best 15", bestSet.newBest.size)
            }
            items(bestSet.newBest, key = { "new-${it.score.id}" }) { item ->
                ScoreCard(item = item, rank = bestSet.newBest.indexOf(item) + 1, onChartSelected = onChartSelected)
            }
        }
    }
}

@Composable
fun ChartQueryPrewarmer(
    charts: List<ChartRecord>,
    scores: List<ScoreRecord>,
    majorVersions: List<MaimaiMajorVersion>,
    aliases: SongAliasCatalog = SongAliasCatalog.Empty,
    playedPresetActive: Boolean = false,
) {
    val queryViewModel: ChartQueryViewModel = viewModel()
    val currentVersion = remember(majorVersions, charts) {
        resolveCurrentMaimaiVersion(majorVersions, charts)?.majorVersion?.id ?: 0
    }
    SideEffect {
        queryViewModel.submitCatalog(charts, scores, currentVersion, aliases)
    }
    LaunchedEffect(playedPresetActive) {
        if (playedPresetActive) queryViewModel.enterPlayedPreset() else queryViewModel.exitPreset()
    }
}

@Composable
fun ChartQueryScreen(
    charts: List<ChartRecord>,
    scores: List<ScoreRecord>,
    majorVersions: List<MaimaiMajorVersion>,
    aliases: SongAliasCatalog = SongAliasCatalog.Empty,
    isLoading: Boolean,
    onRefresh: () -> Unit,
    playedPresetActive: Boolean = false,
    onDismissPlayedPreset: () -> Unit = {},
    scrollToTopRequestId: Int = 0,
    onChartSelected: (ChartIdentity) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val queryViewModel: ChartQueryViewModel = viewModel()
    val uiState by queryViewModel.uiState.collectAsState()
    val filters = uiState.filters
    val currentVersion = remember(majorVersions, charts) {
        resolveCurrentMaimaiVersion(majorVersions, charts)?.majorVersion?.id ?: 0
    }
    SideEffect {
        queryViewModel.submitCatalog(charts, scores, currentVersion, aliases)
    }
    val gridState = rememberLazyGridState(
        initialFirstVisibleItemIndex = queryViewModel.restoredScrollIndex,
        initialFirstVisibleItemScrollOffset = queryViewModel.restoredScrollOffset,
    )
    var handledScrollToTopRequestId by remember { mutableStateOf(scrollToTopRequestId) }
    LaunchedEffect(scrollToTopRequestId) {
        if (scrollToTopRequestId != handledScrollToTopRequestId) {
            handledScrollToTopRequestId = scrollToTopRequestId
            gridState.animateScrollToItem(0)
        }
    }
    LaunchedEffect(queryViewModel, gridState) {
        snapshotFlow { gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .collect { (index, offset) -> queryViewModel.saveScroll(index, offset) }
    }

    LazyVerticalGrid(
        modifier = modifier.fillMaxSize(),
        state = gridState,
        columns = GridCells.Adaptive(340.dp),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            ChartHeader(
                chartCount = charts.size,
                visibleCount = uiState.result.matchingCount,
                isLoading = isLoading,
                isFiltering = uiState.isIndexing || uiState.isFiltering,
                onRefresh = onRefresh,
            )
        }
        if (playedPresetActive) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                PlayedPresetBanner(
                    onReset = {
                        queryViewModel.resetFilters()
                        onDismissPlayedPreset()
                    },
                )
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            ChartFilters(
                searchQuery = filters.searchQuery,
                onSearchQueryChanged = queryViewModel::updateSearchQuery,
                levelQuery = filters.levelQuery,
                onLevelQueryChanged = queryViewModel::updateLevelQuery,
                selectedDifficulty = filters.difficulty,
                onDifficultyChanged = queryViewModel::updateDifficulty,
                genreFilter = filters.genre,
                onGenreFilterChanged = queryViewModel::updateGenre,
                versionFilter = filters.version,
                onVersionFilterChanged = queryViewModel::updateVersion,
                statusFilter = filters.status,
                onStatusFilterChanged = queryViewModel::updateStatus,
                songType = filters.songType,
                onSongTypeChanged = queryViewModel::updateSongType,
                constantMin = filters.constantMin,
                constantMax = filters.constantMax,
                onConstantRangeChanged = queryViewModel::updateConstantRange,
                achievementMin = filters.achievementMin,
                achievementMax = filters.achievementMax,
                onAchievementRangeChanged = queryViewModel::updateAchievementRange,
                rankFilter = filters.rank,
                onRankChanged = queryViewModel::updateRank,
                fullCombo = filters.fullCombo,
                onFullComboChanged = queryViewModel::updateFullCombo,
                fullSync = filters.fullSync,
                onFullSyncChanged = queryViewModel::updateFullSync,
                sortMode = filters.sort,
                onSortModeChanged = queryViewModel::updateSort,
                hasActiveFilters = filters != ChartQueryFilters(),
                activeAdvancedFilters = filters.activeAdvancedLabels(),
                onReset = {
                    queryViewModel.resetFilters()
                    if (playedPresetActive) onDismissPlayedPreset()
                },
            )
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            ChartStatsSummary(stats = uiState.result.stats, isFiltering = uiState.isIndexing || uiState.isFiltering)
        }

        if (charts.isEmpty() && !isLoading) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                EmptyState(text = "还没有曲库数据")
            }
        } else if (uiState.result.items.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                EmptyState(
                    text = if (isLoading || uiState.isIndexing || uiState.isFiltering) {
                        "正在准备谱面结果"
                    } else {
                        "没有匹配的谱面"
                    },
                )
            }
        } else {
            items(
                uiState.result.items,
                key = { "${it.chart.songId}-${it.chart.songType}-${it.chart.levelIndex}" },
            ) { item ->
                ChartCard(
                    chart = item.chart,
                    score = item.score,
                    onClick = { onChartSelected(ChartIdentity.from(item.chart)) },
                )
            }
        }
    }
}

@Composable
private fun PlayedPresetBanner(onReset: () -> Unit) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("首页临时视图 · 已游玩", fontWeight = FontWeight.SemiBold)
                Text(
                    "离开谱面后恢复之前的筛选条件。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onReset) { Text("回到默认") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChartStatsSummary(stats: ChartQueryStats, isFiltering: Boolean) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${stats.totalCharts} 谱面 · ${stats.playedCharts} 已游玩 · " +
                        "${stats.unplayedCharts} 未游玩 · ${stats.rankCounts[AchievementRank.SSS_PLUS] ?: 0} SSS+",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "收起" else if (isFiltering) "更新中" else "详情")
                }
            }
            if (expanded) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AchievementRank.entries.forEach { rank ->
                        MetricChip(rank.displayName, (stats.rankCounts[rank] ?: 0).toString())
                    }
                    FullComboStatus.entries.filter { it != FullComboStatus.UNKNOWN }.forEach { status ->
                        MetricChip(status.displayName, (stats.fullComboCounts[status] ?: 0).toString())
                    }
                    FullSyncStatus.entries.filter { it != FullSyncStatus.UNKNOWN }.forEach { status ->
                        MetricChip(status.displayName, (stats.fullSyncCounts[status] ?: 0).toString())
                    }
                }
            }
        }
    }
}

@Composable
private fun ScoreSummaryHeader(
    scoreCount: Int,
    chartCount: Int,
    bestRating: Int,
) {
    OutlinedCard(
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(text = "成绩", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                RatingPlate(rating = bestRating)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricChip(label = "本地成绩", value = scoreCount.toString())
                MetricChip(label = "曲库谱面", value = if (chartCount == 0) "未同步" else chartCount.toString())
            }
        }
    }
}

@Composable
private fun ChartHeader(
    chartCount: Int,
    visibleCount: Int,
    isLoading: Boolean,
    isFiltering: Boolean,
    onRefresh: () -> Unit,
) {
    OutlinedCard(
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = "谱面查询", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MetricChip(label = "曲库", value = if (isLoading) "同步中" else chartCount.toString())
                    MetricChip(label = "结果", value = if (isFiltering) "筛选中" else visibleCount.toString())
                }
            }
            IconButton(onClick = onRefresh, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.Refresh, contentDescription = "刷新曲库")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomeActions(
    oldBestCount: Int,
    newBestCount: Int,
    onJumpToOldBest: () -> Unit,
    onJumpToNewBest: () -> Unit,
    onOpenPlayedCharts: () -> Unit,
    onOpenPlates: () -> Unit,
    onOpenRecommendations: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("B50 快速跳转", style = MaterialTheme.typography.labelLarge)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = false,
                onClick = onJumpToOldBest,
                label = { Text("旧版本 B35 · $oldBestCount 张") },
            )
            FilterChip(
                selected = false,
                onClick = onJumpToNewBest,
                label = { Text("当前版本 B15 · $newBestCount 张") },
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TextButton(onClick = onOpenPlayedCharts) { Text("查看已游玩谱面") }
            TextButton(onClick = onOpenPlates) { Text("牌子进度") }
            TextButton(onClick = onOpenRecommendations) { Text("推分建议") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChartFilters(
    searchQuery: String,
    onSearchQueryChanged: (String) -> Unit,
    levelQuery: String,
    onLevelQueryChanged: (String) -> Unit,
    selectedDifficulty: Difficulty?,
    onDifficultyChanged: (Difficulty?) -> Unit,
    genreFilter: ChartGenreFilter,
    onGenreFilterChanged: (ChartGenreFilter) -> Unit,
    versionFilter: ChartVersionFilter,
    onVersionFilterChanged: (ChartVersionFilter) -> Unit,
    statusFilter: ChartStatusFilter,
    onStatusFilterChanged: (ChartStatusFilter) -> Unit,
    songType: SongType?,
    onSongTypeChanged: (SongType?) -> Unit,
    constantMin: Double?,
    constantMax: Double?,
    onConstantRangeChanged: (Double?, Double?) -> Unit,
    achievementMin: Double?,
    achievementMax: Double?,
    onAchievementRangeChanged: (Double?, Double?) -> Unit,
    rankFilter: AchievementRank?,
    onRankChanged: (AchievementRank?) -> Unit,
    fullCombo: FullComboStatus?,
    onFullComboChanged: (FullComboStatus?) -> Unit,
    fullSync: FullSyncStatus?,
    onFullSyncChanged: (FullSyncStatus?) -> Unit,
    sortMode: ChartSort,
    onSortModeChanged: (ChartSort) -> Unit,
    hasActiveFilters: Boolean,
    activeAdvancedFilters: List<String>,
    onReset: () -> Unit,
) {
    var advancedFiltersRequested by rememberSaveable { mutableStateOf(false) }
    var showAdvancedFilters by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(advancedFiltersRequested) {
        if (advancedFiltersRequested && !showAdvancedFilters) {
            // Commit the chip's selected state before mounting the heavier modal content.
            withFrameNanos { }
            showAdvancedFilters = true
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (hasActiveFilters) "筛选条件 · 已启用" else "筛选条件",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            TextButton(onClick = onReset, enabled = hasActiveFilters) {
                Icon(Icons.Filled.Clear, contentDescription = null)
                Text("重置")
            }
        }
        SearchField(
            value = searchQuery,
            onValueChanged = onSearchQueryChanged,
            label = "曲名 / 别名 / ID / BPM / 曲师 / 谱师",
        )
        OutlinedTextField(
            value = levelQuery,
            onValueChange = onLevelQueryChanged,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            isError = !isValidLevelQuery(levelQuery),
            label = { Text("等级或内部定数：13、13+、13.3") },
            supportingText = if (!isValidLevelQuery(levelQuery)) {
                { Text("请输入 1–15、1+–14+，或一位小数定数") }
            } else {
                null
            },
            trailingIcon = {
                if (levelQuery.isNotBlank()) {
                    IconButton(onClick = { onLevelQueryChanged("") }) {
                        Icon(Icons.Filled.Clear, contentDescription = "清除定数")
                    }
                }
            },
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            QuickFilterMenu(
                label = selectedDifficulty?.displayName() ?: "全部难度",
                active = selectedDifficulty != null,
                values = listOf(null to "全部难度") + Difficulty.entries.map { it to it.displayName() },
                onSelected = onDifficultyChanged,
            )
            QuickFilterMenu(
                label = versionFilter.label,
                active = versionFilter != ChartVersionFilter.All,
                values = ChartVersionFilter.entries.map { it to it.label },
                onSelected = onVersionFilterChanged,
            )
            QuickFilterMenu(
                label = genreFilter.label,
                active = genreFilter != ChartGenreFilter.All,
                values = ChartGenreFilter.entries.map { it to it.label },
                onSelected = onGenreFilterChanged,
            )
            QuickFilterMenu(
                label = statusFilter.label,
                active = statusFilter != ChartStatusFilter.All,
                values = ChartStatusFilter.entries.map { it to it.label },
                onSelected = onStatusFilterChanged,
            )
            QuickFilterMenu(
                label = sortMode.label,
                active = sortMode != ChartSort.ConstantDesc,
                values = ChartSort.entries.map { it to it.label },
                onSelected = onSortModeChanged,
            )
            FilterChip(
                selected = advancedFiltersRequested || showAdvancedFilters,
                onClick = { advancedFiltersRequested = true },
                label = {
                    Text(if (activeAdvancedFilters.isEmpty()) "更多筛选" else "更多筛选 · ${activeAdvancedFilters.size}")
                },
            )
        }
        if (activeAdvancedFilters.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                activeAdvancedFilters.forEach { label ->
                    AssistChip(onClick = { advancedFiltersRequested = true }, label = { Text(label) })
                }
            }
        }
    }
    if (showAdvancedFilters) {
        AdvancedChartFiltersSheet(
            constantMin = constantMin,
            constantMax = constantMax,
            onConstantRangeChanged = onConstantRangeChanged,
            songType = songType,
            onSongTypeChanged = onSongTypeChanged,
            achievementMin = achievementMin,
            achievementMax = achievementMax,
            onAchievementRangeChanged = onAchievementRangeChanged,
            rankFilter = rankFilter,
            onRankChanged = onRankChanged,
            fullCombo = fullCombo,
            onFullComboChanged = onFullComboChanged,
            fullSync = fullSync,
            onFullSyncChanged = onFullSyncChanged,
            onReset = onReset,
            onDismiss = {
                showAdvancedFilters = false
                advancedFiltersRequested = false
            },
        )
    }
}

@Composable
private fun <T> QuickFilterMenu(
    label: String,
    active: Boolean,
    values: List<Pair<T, String>>,
    onSelected: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = active,
            onClick = { expanded = true },
            label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            trailingIcon = { Icon(Icons.Filled.ExpandMore, contentDescription = null) },
        )
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

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun AdvancedChartFiltersSheet(
    constantMin: Double?,
    constantMax: Double?,
    onConstantRangeChanged: (Double?, Double?) -> Unit,
    songType: SongType?,
    onSongTypeChanged: (SongType?) -> Unit,
    achievementMin: Double?,
    achievementMax: Double?,
    onAchievementRangeChanged: (Double?, Double?) -> Unit,
    rankFilter: AchievementRank?,
    onRankChanged: (AchievementRank?) -> Unit,
    fullCombo: FullComboStatus?,
    onFullComboChanged: (FullComboStatus?) -> Unit,
    fullSync: FullSyncStatus?,
    onFullSyncChanged: (FullSyncStatus?) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("更多筛选", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(
                            "范围与成绩条件会使用同一套谱面结果。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = onReset) { Text("全部重置") }
                }
            }
            item {
                ConstantRangeFilter(
                    minimum = constantMin,
                    maximum = constantMax,
                    onRangeChanged = onConstantRangeChanged,
                )
            }
            item {
                FilterGroup(title = "谱面类型") {
                    listOf<SongType?>(null, SongType.STANDARD, SongType.DX).forEach { type ->
                        FilterChip(
                            selected = songType == type,
                            onClick = { onSongTypeChanged(type) },
                            label = { Text(type?.displayName() ?: "SD / DX") },
                        )
                    }
                }
            }
            item {
                AchievementRangeFilter(
                    minimum = achievementMin,
                    maximum = achievementMax,
                    onRangeChanged = onAchievementRangeChanged,
                )
            }
            item {
                FilterGroup(title = "成绩等级") {
                    FilterChip(
                        selected = rankFilter == null,
                        onClick = { onRankChanged(null) },
                        label = { Text("全部等级") },
                    )
                    AchievementRank.entries.forEach { rank ->
                        FilterChip(
                            selected = rankFilter == rank,
                            onClick = { onRankChanged(rank) },
                            label = { Text(rank.displayName) },
                        )
                    }
                }
            }
            item {
                FilterGroup(title = "FC 状态") {
                    FilterChip(
                        selected = fullCombo == null,
                        onClick = { onFullComboChanged(null) },
                        label = { Text("全部 FC") },
                    )
                    FullComboStatus.entries.filter { it != FullComboStatus.UNKNOWN }.forEach { status ->
                        FilterChip(
                            selected = fullCombo == status,
                            onClick = { onFullComboChanged(status) },
                            label = { Text(status.displayName) },
                        )
                    }
                }
            }
            item {
                FilterGroup(title = "SYNC / FS 状态") {
                    FilterChip(
                        selected = fullSync == null,
                        onClick = { onFullSyncChanged(null) },
                        label = { Text("全部 FS") },
                    )
                    FullSyncStatus.entries.filter { it != FullSyncStatus.UNKNOWN }.forEach { status ->
                        FilterChip(
                            selected = fullSync == status,
                            onClick = { onFullSyncChanged(status) },
                            label = { Text(status.displayName) },
                        )
                    }
                }
            }
            item {
                TextButton(modifier = Modifier.fillMaxWidth(), onClick = onDismiss) { Text("查看筛选结果") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterGroup(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun ConstantRangeFilter(
    minimum: Double?,
    maximum: Double?,
    onRangeChanged: (Double?, Double?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("定数范围", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DecimalFilterField(
                value = minimum,
                label = "最低",
                modifier = Modifier.weight(1f),
                allowedRange = MIN_CHART_CONSTANT..MAX_CHART_CONSTANT,
                onValueChanged = { value ->
                    onRangeChanged(value, if (value != null && maximum != null && value > maximum) value else maximum)
                },
            )
            DecimalFilterField(
                value = maximum,
                label = "最高",
                modifier = Modifier.weight(1f),
                allowedRange = MIN_CHART_CONSTANT..MAX_CHART_CONSTANT,
                onValueChanged = { value ->
                    onRangeChanged(if (value != null && minimum != null && value < minimum) value else minimum, value)
                },
            )
        }
        RangeSlider(
            value = (minimum ?: MIN_CHART_CONSTANT).toFloat()..(maximum ?: MAX_CHART_CONSTANT).toFloat(),
            onValueChange = { range ->
                onRangeChanged(range.start.roundToTenth(), range.endInclusive.roundToTenth())
            },
            valueRange = MIN_CHART_CONSTANT.toFloat()..MAX_CHART_CONSTANT.toFloat(),
            steps = 139,
        )
    }
}

@Composable
private fun AchievementRangeFilter(
    minimum: Double?,
    maximum: Double?,
    onRangeChanged: (Double?, Double?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("达成率范围", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DecimalFilterField(
                value = minimum,
                label = "最低 %",
                modifier = Modifier.weight(1f),
                allowedRange = 0.0..101.0,
                onValueChanged = { value ->
                    onRangeChanged(value, if (value != null && maximum != null && value > maximum) value else maximum)
                },
            )
            DecimalFilterField(
                value = maximum,
                label = "最高 %",
                modifier = Modifier.weight(1f),
                allowedRange = 0.0..101.0,
                onValueChanged = { value ->
                    onRangeChanged(if (value != null && minimum != null && value < minimum) value else minimum, value)
                },
            )
        }
    }
}

@Composable
private fun DecimalFilterField(
    value: Double?,
    label: String,
    modifier: Modifier = Modifier,
    allowedRange: ClosedFloatingPointRange<Double>? = null,
    onValueChanged: (Double?) -> Unit,
) {
    var localText by remember { mutableStateOf(value?.let { String.format(Locale.US, "%.1f", it) }.orEmpty()) }
    LaunchedEffect(value) {
        val localValue = localText.replace(',', '.').toDoubleOrNull()
        if (localValue != value) {
            localText = value?.let { String.format(Locale.US, "%.1f", it) }.orEmpty()
        }
    }
    OutlinedTextField(
        value = localText,
        onValueChange = { raw ->
            val normalized = raw.trim().replace(',', '.')
            if (normalized.matches(Regex("\\d{0,3}(\\.\\d?)?"))) {
                localText = normalized
                if (normalized.isEmpty()) {
                    onValueChanged(null)
                } else {
                    normalized.toDoubleOrNull()
                        ?.takeIf { allowedRange == null || it in allowedRange }
                        ?.let(onValueChanged)
                }
            }
        },
        modifier = modifier,
        singleLine = true,
        isError = localText.isNotBlank() && localText.toDoubleOrNull()?.let {
            allowedRange != null && it !in allowedRange
        } != false,
        label = { Text(label) },
        supportingText = if (localText.isNotBlank() && localText.toDoubleOrNull()?.let {
                allowedRange != null && it !in allowedRange
            } != false
        ) {
            { Text("超出可用范围") }
        } else {
            null
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    )
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun SearchField(
    value: String,
    onValueChanged: (String) -> Unit,
    label: String,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChanged,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text(label) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (value.isNotBlank()) {
                IconButton(onClick = { onValueChanged("") }) {
                    Icon(Icons.Filled.Clear, contentDescription = "清除搜索")
                }
            }
        },
    )
}

@Composable
private fun SectionTitle(title: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(text = "$count 张", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ScoreCard(
    item: MaimaiRatedScore,
    rank: Int,
    onChartSelected: (ChartIdentity) -> Unit,
) {
    val difficultyColor = item.score.difficulty.accentColor()
    val openDetail = {
        item.chart?.let { onChartSelected(ChartIdentity.from(it)) }
        Unit
    }
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = item.chart != null, onClick = openDetail),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                JacketArt(
                    songId = item.chart?.songId ?: item.score.songId,
                    title = item.score.title,
                    modifier = Modifier.size(86.dp),
                )
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        RankLabel(rank = rank)
                        CopyableText(
                            text = item.score.title,
                            copyLabel = "曲名",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            onClick = openDetail,
                        )
                    }
                    val scoreSubtitle = listOfNotNull(
                        item.chart?.artist?.takeIf { it.isNotBlank() },
                        item.chart?.displayVersionName()?.takeIf { it != "--" },
                    ).joinToString(" · ").ifBlank { item.score.songType.displayName() }
                    CopyableText(
                        text = scoreSubtitle,
                        copyText = item.chart?.artist?.takeIf { it.isNotBlank() },
                        copyLabel = "曲师",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    DifficultyPill(
                        text = "${item.score.difficulty.displayName()} ${item.score.level}",
                        color = difficultyColor,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = String.format(Locale.US, "%.4f%%", item.score.achievement),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = item.score.rankName(),
                        style = MaterialTheme.typography.labelMedium,
                        color = item.score.rankColor(),
                        fontWeight = FontWeight.Bold,
                    )
                }
                InfoStack(label = "定数", value = item.chart?.levelValue?.formatConst() ?: item.score.level)
                InfoStack(label = "Rating", value = item.rating?.toString() ?: "--")
                InfoStack(label = "DX", value = item.score.dxScore?.toString() ?: "--")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item.score.fc?.takeIf { it.isNotBlank() }?.let { SmallTag(it.uppercase(), Color(0xFF2F9E44)) }
                item.score.fs?.takeIf { it.isNotBlank() }?.let { SmallTag(it.uppercase(), Color(0xFF1C7ED6)) }
                SmallTag(item.score.songType.displayName(), MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ChartCard(
    chart: ChartRecord,
    score: ScoreRecord?,
    onClick: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (score == null) {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                JacketArt(
                    songId = chart.songId,
                    title = chart.title,
                    modifier = Modifier.size(82.dp),
                )
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    CopyableText(
                        text = chart.title,
                        copyLabel = "曲名",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        onClick = onClick,
                    )
                    CopyableText(
                        text = chart.artist.ifBlank { chart.genre },
                        copyLabel = "曲师",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    DifficultyPill(
                        text = "${chart.difficulty.displayName()} ${chart.level}",
                        color = chart.difficulty.accentColor(),
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                InfoStack(label = "定数", value = chart.levelValue?.formatConst() ?: chart.level)
                InfoStack(label = "BPM", value = chart.bpm?.toString() ?: "--")
                InfoStack(label = "版本", value = chart.displayVersionName())
                InfoStack(label = "物量", value = chart.notes?.total?.toString() ?: "--")
            }
            CopyableText(
                text = "谱师 ${chart.noteDesigner.ifBlank { "--" }}",
                copyText = chart.noteDesigner.takeIf { it.isNotBlank() },
                copyLabel = "谱师",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                SmallTag(chart.songType.displayName(), MaterialTheme.colorScheme.onSurfaceVariant)
                if (score == null) {
                    SmallTag("未游玩", Color(0xFFB42318))
                } else {
                    SmallTag(String.format(Locale.US, "%.4f%%", score.achievement), score.rankColor())
                    score.fc?.takeIf { it.isNotBlank() }?.let { SmallTag(it.uppercase(), Color(0xFF2F9E44)) }
                    score.fs?.takeIf { it.isNotBlank() }?.let { SmallTag(it.uppercase(), Color(0xFF1C7ED6)) }
                }
            }
        }
    }
}

@Composable
private fun JacketArt(
    songId: Int?,
    title: String,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (songId != null) {
            val context = LocalContext.current
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(jacketUrl(songId))
                    .size(320)
                    .crossfade(150)
                    .build(),
                contentDescription = "$title 曲绘",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text(
                text = "无曲绘",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CopyableText(
    text: String,
    copyLabel: String,
    modifier: Modifier = Modifier,
    copyText: String? = text,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    onClick: () -> Unit = {},
) {
    val context = LocalContext.current
    Text(
        text = text,
        modifier = modifier.combinedClickable(
            onClick = onClick,
            onLongClick = {
                copyText?.takeIf { it.isNotBlank() }?.let { value ->
                    copyToClipboard(context, copyLabel, value)
                }
            },
        ),
        style = style,
        color = color,
        fontWeight = fontWeight,
        maxLines = maxLines,
        overflow = overflow,
    )
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, "已复制$label", Toast.LENGTH_SHORT).show()
}

@Composable
private fun DifficultyPill(text: String, color: Color) {
    Surface(
        color = color,
        contentColor = Color.White,
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

@Composable
private fun InfoStack(label: String, value: String) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun RatingPlate(rating: Int) {
    val palette = ratingFramePalette(rating)
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(Brush.horizontalGradient(palette.frameColors))
            .padding(2.dp),
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF121318))
                .padding(3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(5.dp))
                    .background(Brush.verticalGradient(listOf(Color(0xFFFFFFFF), Color(0xFFBFEFFF), Color(0xFF2F8CFF))))
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "DX",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFFFA726),
                        fontWeight = FontWeight.Black,
                    )
                    Text(
                        text = "RATING",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF0B56A8),
                        fontWeight = FontWeight.Black,
                    )
                }
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(5.dp))
                    .background(Brush.verticalGradient(listOf(Color(0xFF2B2D33), Color(0xFF050506))))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = rating.takeIf { it > 0 }?.toString() ?: "-----",
                    style = MaterialTheme.typography.titleMedium,
                    color = palette.textColor,
                    fontWeight = FontWeight.Black,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun RankLabel(rank: Int) {
    Text(
        text = "#$rank",
        modifier = Modifier.padding(top = 3.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
        fontWeight = FontWeight.Bold,
        maxLines = 1,
    )
}

private data class RatingFramePalette(
    val frameColors: List<Color>,
    val textColor: Color,
)

private fun ratingFramePalette(rating: Int): RatingFramePalette =
    when {
        rating >= 16000 -> RatingFramePalette(
            frameColors = listOf(Color(0xFFFFD166), Color(0xFFFF7A59), Color(0xFF1DD3B0), Color(0xFF4CC9F0), Color(0xFFFFF7B8)),
            textColor = Color(0xFFFFF3C4),
        )
        rating >= 15000 -> RatingFramePalette(
            frameColors = listOf(Color(0xFFFF4D6D), Color(0xFFFFD166), Color(0xFF06D6A0), Color(0xFF4D96FF), Color(0xFFB15CFF), Color(0xFFFFFFFF)),
            textColor = Color(0xFFFFFFFF),
        )
        rating >= 14000 -> RatingFramePalette(
            frameColors = listOf(Color(0xFFFFFFFF), Color(0xFFF6E7A8), Color(0xFFC9A64C), Color(0xFFFFFFFF)),
            textColor = Color(0xFFFFD84D),
        )
        rating >= 13000 -> RatingFramePalette(
            frameColors = listOf(Color(0xFFD7B35C), Color(0xFFFFE8A3), Color(0xFFA06A22)),
            textColor = Color(0xFFFFE8A3),
        )
        else -> RatingFramePalette(
            frameColors = listOf(Color(0xFF64748B), Color(0xFFCBD5E1), Color(0xFF64748B)),
            textColor = Color(0xFFE5E7EB),
        )
    }

@Composable
private fun MetricChip(label: String, value: String) {
    AssistChip(
        onClick = {},
        label = {
            Text("$label $value", style = MaterialTheme.typography.labelLarge)
        },
    )
}

@Composable
private fun SmallTag(text: String, color: Color) {
    Surface(
        color = color.copy(alpha = 0.12f),
        contentColor = color,
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

@Composable
private fun EmptyState(text: String) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(20.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun enrichScores(scores: List<ScoreRecord>, charts: List<ChartRecord>): List<MaimaiRatedScore> {
    val chartMap = matchChartsForScores(charts, scores)
    return scores.map { score ->
        val chart = chartMap[score.id]
        MaimaiRatedScore(
            score = score,
            chart = chart,
            rating = chart?.levelValue?.let { calculateDxRating(it, score.achievement, score.fc) },
        )
    }
}

private fun ChartQueryFilters.activeAdvancedLabels(): List<String> = buildList {
    if (constantMin != null || constantMax != null) {
        add("定数 ${constantMin?.formatConst() ?: "--"}–${constantMax?.formatConst() ?: "--"}")
    }
    songType?.let { add(it.displayName()) }
    if (achievementMin != null || achievementMax != null) {
        add("达成率 ${achievementMin?.formatConst() ?: "--"}–${achievementMax?.formatConst() ?: "--"}%")
    }
    rank?.let { add(it.displayName) }
    fullCombo?.let { add(it.displayName) }
    fullSync?.let { add(it.displayName) }
}

private fun ScoreRecord.rankName(): String =
    when {
        achievement >= 100.5 -> "SSS+"
        achievement >= 100.0 -> "SSS"
        achievement >= 99.5 -> "SS+"
        achievement >= 99.0 -> "SS"
        achievement >= 98.0 -> "S+"
        achievement >= 97.0 -> "S"
        achievement >= 94.0 -> "AAA"
        achievement >= 90.0 -> "AA"
        achievement >= 80.0 -> "A"
        achievement >= 75.0 -> "BBB"
        achievement >= 70.0 -> "BB"
        achievement >= 60.0 -> "B"
        achievement >= 50.0 -> "C"
        else -> "D"
    }

private fun ScoreRecord.rankColor(): Color =
    when {
        achievement >= 100.5 -> Color(0xFFE03131)
        achievement >= 100.0 -> Color(0xFF1971C2)
        achievement >= 97.0 -> Color(0xFF5F3DC4)
        else -> Color(0xFF475569)
    }

private fun Difficulty.displayName(): String =
    when (this) {
        Difficulty.BASIC -> "Basic"
        Difficulty.ADVANCED -> "Advanced"
        Difficulty.EXPERT -> "Expert"
        Difficulty.MASTER -> "Master"
        Difficulty.RE_MASTER -> "Re:MASTER"
    }

private fun SongType.displayName(): String =
    when (this) {
        SongType.STANDARD -> "SD"
        SongType.DX -> "DX"
    }

private fun ChartRecord.displayVersionName(): String =
    chartVersionName
        ?: songVersionName
        ?: maimaiVersionNameFor(chartVersion)
        ?: maimaiVersionNameFor(songVersion)
        ?: "--"

private fun Double.formatConst(): String =
    String.format(Locale.US, "%.1f", this)

private fun jacketUrl(songId: Int): String =
    "https://assets2.lxns.net/maimai/jacket/$songId.png"

internal fun normalizeQuery(value: String): String =
    normalizeSearchKey(value).let { normalized ->
        if (normalized.any(Char::isHanCharacter)) {
            SIMPLIFIED_QUERY_CACHE.computeIfAbsent(normalized, ChineseSearchNormalizer::toSimplified)
        } else {
            normalized
        }
    }

internal fun normalizeSimplifiedSearchKey(value: String): String =
    normalizeSearchKey(value).let { normalized ->
        if (normalized.any(Char::isHanCharacter)) ChineseSearchNormalizer.toSimplified(normalized) else normalized
    }

internal fun normalizeSearchKey(value: String): String =
    Normalizer.normalize(value.trim(), Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
        .replace(Regex("[\\s._·・:：!！?？'\"“”‘’()（）\\[\\]【】/\\\\-]+"), "")

private fun Char.isHanCharacter(): Boolean =
    code in 0x3400..0x4DBF || code in 0x4E00..0x9FFF || code in 0xF900..0xFAFF

private val SIMPLIFIED_QUERY_CACHE = ConcurrentHashMap<String, String>()

private const val MIN_CHART_CONSTANT = 1.0
private const val MAX_CHART_CONSTANT = 15.0
private const val HOME_OLD_BEST_HEADER_INDEX = 2
private const val HOME_NEW_BEST_HEADER_BASE_INDEX = 3

private fun Float.roundToTenth(): Double = (this * 10f).roundToInt() / 10.0

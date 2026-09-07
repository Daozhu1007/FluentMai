package dev.fluentmai.android.feature.scores

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.fluentmai.android.core.model.ChartIdentity
import dev.fluentmai.android.core.model.RatingRecommendation
import dev.fluentmai.android.core.model.RatingRecommendationAvailability
import dev.fluentmai.android.core.model.RatingRecommendationReason
import dev.fluentmai.android.core.model.VersionAgeFilter
import java.util.Locale

@Composable
internal fun RatingRecommendationsContent(
    state: PlayerRecordsUiState,
    viewModel: PlayerRecordsViewModel,
    onChartSelected: (ChartIdentity) -> Unit,
    destination: PlayerProgressDestination,
    onDestinationChanged: (PlayerProgressDestination) -> Unit,
    onBack: () -> Unit,
    scrollToTopRequestId: Int,
    modifier: Modifier,
) {
    val result = state.recommendationResult
    val gridState = rememberLazyGridState()
    var handledScrollToTopRequestId by remember { mutableStateOf(scrollToTopRequestId) }
    LaunchedEffect(scrollToTopRequestId) {
        if (scrollToTopRequestId != handledScrollToTopRequestId) {
            handledScrollToTopRequestId = scrollToTopRequestId
            gridState.animateScrollToItem(0)
        }
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(340.dp),
        modifier = modifier.fillMaxSize(),
        state = gridState,
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            RecommendationHeader(
                state = state,
                destination = destination,
                onDestinationChanged = onDestinationChanged,
                onBack = onBack,
            )
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            RecommendationFilters(state, viewModel)
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            RecommendationSummary(state)
        }
        when {
            state.recommendationInputError != null -> Unit
            state.isRecommendationWorking && result == null -> Unit
            result == null -> item(span = { GridItemSpan(maxLineSpan) }) {
                RecommendationEmpty("正在建立可解释建议")
            }
            result.availability == RatingRecommendationAvailability.CURRENT_VERSION_UNAVAILABLE ->
                item(span = { GridItemSpan(maxLineSpan) }) {
                    RecommendationEmpty("当前运营大版本无法由可靠元数据确定，推荐已安全停用。")
                }
            result.availability == RatingRecommendationAvailability.NO_ELIGIBLE_SCORES ->
                item(span = { GridItemSpan(maxLineSpan) }) {
                    RecommendationEmpty("没有同时具备本地成绩、谱面定数与 B35/B15 版本归属的可用谱面。")
                }
            result.recommendations.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
                RecommendationEmpty(
                    if (state.recommendationFilters.onlyB50Gain) {
                        "当前条件没有能实际提升 B50 的单谱面目标；可关闭“只看实际提分”检查储备候选。"
                    } else {
                        "当前条件没有匹配的建议。若目标总 Rating 较远，可能无法靠单张谱面一次达到。"
                    },
                )
            }
            else -> items(result.recommendations, key = { it.identity.stableKey() }) { recommendation ->
                RecommendationCard(
                    recommendation = recommendation,
                    onOpen = { onChartSelected(recommendation.identity) },
                    onExclude = { viewModel.excludeRecommendation(recommendation.identity) },
                )
            }
        }
    }
}

@Composable
private fun RecommendationHeader(
    state: PlayerRecordsUiState,
    destination: PlayerProgressDestination,
    onDestinationChanged: (PlayerProgressDestination) -> Unit,
    onBack: () -> Unit,
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回首页")
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("推分建议", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "只模拟真实成绩、谱面定数和当前 B35/B15 尾部，不评估技术风格，也不声称“最适合”。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.isRecommendationWorking) {
                    Text("计算中", color = MaterialTheme.colorScheme.primary)
                }
            }
            PlayerProgressSwitch(destination, onDestinationChanged)
        }
    }
}

@Composable
private fun RecommendationFilters(state: PlayerRecordsUiState, viewModel: PlayerRecordsViewModel) {
    val filters = state.recommendationFilters
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("目标与范围", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "目标总 Rating 会按“只提升这一张谱面”反推最低达成率；留空时使用下一个 97/98/99/99.5/100/100.5 里程碑。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RecommendationIntegerField(
                    value = state.recommendationTargetTotalText,
                    onValueChange = viewModel::updateRecommendationTargetTotal,
                    label = "目标总 Rating",
                    modifier = Modifier.weight(1f),
                )
                RecommendationDecimalField(
                    value = state.recommendationTargetAchievementText,
                    onValueChange = viewModel::updateRecommendationTargetAchievement,
                    label = "目标达成率 %",
                    modifier = Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RecommendationDecimalField(
                    value = state.recommendationConstantMinText,
                    onValueChange = viewModel::updateRecommendationConstantMin,
                    label = "最低定数",
                    modifier = Modifier.weight(1f),
                )
                RecommendationDecimalField(
                    value = state.recommendationConstantMaxText,
                    onValueChange = viewModel::updateRecommendationConstantMax,
                    label = "最高定数",
                    modifier = Modifier.weight(1f),
                )
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilterChip(
                        selected = filters.versionAge == VersionAgeFilter.ALL,
                        onClick = { viewModel.updateRecommendationVersionAge(VersionAgeFilter.ALL) },
                        label = { Text("全部版本") },
                    )
                }
                item {
                    FilterChip(
                        selected = filters.versionAge == VersionAgeFilter.CURRENT,
                        onClick = { viewModel.updateRecommendationVersionAge(VersionAgeFilter.CURRENT) },
                        label = { Text("当前版本 B15") },
                    )
                }
                item {
                    FilterChip(
                        selected = filters.versionAge == VersionAgeFilter.OLD,
                        onClick = { viewModel.updateRecommendationVersionAge(VersionAgeFilter.OLD) },
                        label = { Text("旧版本 B35") },
                    )
                }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilterChip(
                        selected = filters.excludeSssPlus,
                        onClick = { viewModel.updateRecommendationExcludeSssPlus(!filters.excludeSssPlus) },
                        label = { Text("排除已鸟加") },
                    )
                }
                item {
                    FilterChip(
                        selected = filters.onlyB50Gain,
                        onClick = { viewModel.updateRecommendationOnlyB50Gain(!filters.onlyB50Gain) },
                        label = { Text("只看实际提升 B50") },
                    )
                }
            }
            state.recommendationInputError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = viewModel::resetRecommendationFilters) { Text("重置条件") }
                if (filters.excludedIdentities.isNotEmpty()) {
                    OutlinedButton(onClick = viewModel::clearRecommendationExclusions) {
                        Text("恢复不想练 ${filters.excludedIdentities.size} 张")
                    }
                }
            }
        }
    }
}

@Composable
private fun RecommendationSummary(state: PlayerRecordsUiState) {
    val result = state.recommendationResult
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("计算口径", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (result != null && result.availability == RatingRecommendationAvailability.AVAILABLE) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { RecommendationMetric("当前 B50", result.currentTotalRating.toString()) }
                    item { RecommendationMetric("建议", result.recommendations.size.toString()) }
                    item { RecommendationMetric("可计算成绩", result.eligiblePlayedCharts.toString()) }
                    item { RecommendationMetric("B35 尾部", result.oldBestCutoff?.toString() ?: "未满") }
                    item { RecommendationMetric("B15 尾部", result.currentBestCutoff?.toString() ?: "未满") }
                }
            } else {
                Text("等待成绩、曲库和当前版本数据。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                "每张卡同时给出单曲理论增量与重算 B35/B15 后的实际 B50 增量。没有可靠拟合难度来源，因此不显示或猜测拟合难度。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RecommendationMetric(label: String, value: String) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(value, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun RecommendationCard(
    recommendation: RatingRecommendation,
    onOpen: () -> Unit,
    onExclude: () -> Unit,
) {
    ElevatedCard(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        recommendation.chart.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${recommendation.chart.difficulty.displayLabel()} · ${recommendation.chart.songType.divingFishName} · " +
                            "定数 ${recommendation.chart.levelValue?.formatRecommendation(1)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Text(
                        recommendation.bucket.bestLabel,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RecommendationValue(
                    label = "当前",
                    achievement = recommendation.currentAchievement,
                    rating = recommendation.currentSingleRating,
                    modifier = Modifier.weight(1f),
                )
                RecommendationValue(
                    label = if (recommendation.isCompleted) "目标已完成" else "建议目标",
                    achievement = recommendation.targetAchievement,
                    rating = recommendation.targetSingleRating,
                    modifier = Modifier.weight(1f),
                )
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { RecommendationMetric("单曲理论", "+${recommendation.theoreticalSingleGain}") }
                item { RecommendationMetric("实际 B50", "+${recommendation.actualB50Gain}") }
                item { RecommendationMetric("目标总分", recommendation.projectedTotalRating.toString()) }
                item {
                    RecommendationMetric(
                        recommendation.bucket.bestLabel,
                        if (recommendation.willEnterBestSet) "会进入" else "未进入",
                    )
                }
            }
            Text(
                recommendation.explanation(),
                color = if (recommendation.actualB50Gain > 0) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onExclude) { Text("不想练") }
                TextButton(onClick = onOpen) { Text("查看详情") }
            }
        }
    }
}

@Composable
private fun RecommendationValue(
    label: String,
    achievement: Double,
    rating: Int,
    modifier: Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${achievement.formatRecommendation(4)}%", fontWeight = FontWeight.Bold)
            Text("单曲 Rating $rating")
        }
    }
}

@Composable
private fun RecommendationEmpty(message: String) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Text(message, modifier = Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RecommendationIntegerField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { next -> if (next.all(Char::isDigit)) onValueChange(next) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

@Composable
private fun RecommendationDecimalField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

private fun RatingRecommendation.explanation(): String = when (reason) {
    RatingRecommendationReason.TARGET_COMPLETED ->
        "当前成绩已经满足所选目标；保留此项用于核对，未计算虚假增量。"
    RatingRecommendationReason.ALREADY_IN_BEST_SET ->
        "已在 ${bucket.bestLabel}；单曲 +$theoreticalSingleGain 会让当前 B50 实际 +$actualB50Gain。"
    RatingRecommendationReason.ENTERS_BEST_SET ->
        "目标单曲 Rating $targetSingleRating 高于 ${bucket.bestLabel} 尾部 $bucketCutoffRating，重算后实际 +$actualB50Gain。"
    RatingRecommendationReason.TIES_BEST_SET_CUTOFF ->
        "目标后可进入 ${bucket.bestLabel}，但与尾部 $bucketCutoffRating 同分，当前 B50 不变。"
    RatingRecommendationReason.BELOW_BEST_SET_CUTOFF ->
        "目标单曲 Rating $targetSingleRating 尚未超过 ${bucket.bestLabel} 尾部 $bucketCutoffRating，属于储备候选。"
}

private fun dev.fluentmai.android.core.model.Difficulty.displayLabel(): String = when (this) {
    dev.fluentmai.android.core.model.Difficulty.BASIC -> "Basic"
    dev.fluentmai.android.core.model.Difficulty.ADVANCED -> "Advanced"
    dev.fluentmai.android.core.model.Difficulty.EXPERT -> "Expert"
    dev.fluentmai.android.core.model.Difficulty.MASTER -> "Master"
    dev.fluentmai.android.core.model.Difficulty.RE_MASTER -> "Re:Master"
}

private fun Double.formatRecommendation(decimals: Int): String =
    String.format(Locale.US, "%.${decimals}f", this)

package dev.fluentmai.android.feature.scores

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import dev.fluentmai.android.core.model.ChartAvailability
import dev.fluentmai.android.core.model.ChartIdentity
import dev.fluentmai.android.core.model.ChartRecord
import dev.fluentmai.android.core.model.maimaiVersionNameFor
import dev.fluentmai.android.core.model.Difficulty
import dev.fluentmai.android.core.model.ScoreRecord
import dev.fluentmai.android.core.model.SongAliasCatalog
import dev.fluentmai.android.core.model.SongType
import dev.fluentmai.android.core.model.availability
import dev.fluentmai.android.core.model.buildPlayerRecordCatalog
import java.text.DateFormat
import java.util.Date
import java.util.Locale

data class AliasDataStatus(
    val sourceLabel: String,
    val fetchedAtEpochMillis: Long,
    val contentVersion: String,
    val songCount: Int,
    val aliasCount: Int,
    val unmappedSongCount: Int,
)

@Composable
fun ChartDetailScreen(
    identity: ChartIdentity,
    charts: List<ChartRecord>,
    scores: List<ScoreRecord>,
    aliases: SongAliasCatalog = SongAliasCatalog.Empty,
    aliasStatus: AliasDataStatus? = null,
    currentVersionId: Int? = null,
    onBack: () -> Unit,
    onChartSelected: (ChartIdentity) -> Unit,
    scrollToTopRequestId: Int = 0,
    modifier: Modifier = Modifier,
) {
    val chart = remember(identity, charts) {
        charts.firstOrNull { ChartIdentity.from(it) == identity }
    }
    if (chart == null) {
        MissingChartDetail(onBack = onBack, modifier = modifier)
        return
    }
    val sameSongCharts = remember(chart.songId, charts) {
        charts.asSequence()
            .filter { it.songId == chart.songId }
            .distinctBy(ChartIdentity::from)
            .sortedWith(compareBy<ChartRecord> { it.songType.ordinal }.thenBy { it.levelIndex })
            .toList()
    }
    val playerRecord = remember(charts, scores, identity) {
        buildPlayerRecordCatalog(charts, scores).records.firstOrNull { it.identity == identity }
    }
    val songAliases = remember(aliases, chart.songId) { aliases.aliasesFor(chart.songId) }
    val gridState = rememberLazyGridState()
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
        columns = GridCells.Adaptive(340.dp),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            DetailHeader(chart = chart, onBack = onBack)
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            DifficultySwitcher(
                selected = identity,
                charts = sameSongCharts,
                onChartSelected = onChartSelected,
            )
        }
        item {
            DetailSection(title = "歌曲") {
                DetailValue("Song ID", chart.songId.toString())
                DetailValue("谱面身份", "${chart.songId} · ${chart.songType.detailName()} · ${chart.difficulty.detailName()}")
                DetailValue("曲师", chart.artist.ifBlank { "--" })
                DetailValue("类别", chart.genre.ifBlank { "--" })
                DetailValue("BPM", chart.bpm?.toString() ?: "--")
                DetailValue("歌曲版本", chart.songVersionName ?: chart.songVersion.detailVersionName())
                DetailValue("谱面版本", chart.chartVersionName ?: chart.chartVersion.detailVersionName())
                DetailValue("上线状态", chart.availability(currentVersionId).displayName())
            }
        }
        item {
            DetailSection(title = "谱面") {
                DetailValue("类型", chart.songType.detailName())
                DetailValue("难度", "${chart.difficulty.detailName()} ${chart.level}")
                DetailValue("定数", chart.levelValue?.let { String.format(Locale.US, "%.1f", it) } ?: "--")
                DetailValue("谱师", chart.noteDesigner.ifBlank { "--" })
                DetailValue("总 Note", chart.notes?.total?.toString() ?: "--")
                chart.notes?.let { notes ->
                    DetailValue(
                        "Note 明细",
                        listOfNotNull(
                            notes.tap?.let { "Tap $it" },
                            notes.hold?.let { "Hold $it" },
                            notes.slide?.let { "Slide $it" },
                            notes.touch?.let { "Touch $it" },
                            notes.breakCount?.let { "Break $it" },
                        ).joinToString(" · ").ifBlank { "--" },
                    )
                }
            }
        }
        item {
            DetailSection(title = "玩家最佳") {
                val score = playerRecord?.score
                DetailValue("达成率", score?.let { String.format(Locale.US, "%.4f%%", it.achievement) } ?: "未游玩")
                DetailValue("Rating 贡献", playerRecord?.rating?.toString() ?: "--")
                DetailValue("FC", score?.fc?.uppercase(Locale.ROOT) ?: "--")
                DetailValue("FS", score?.fs?.uppercase(Locale.ROOT) ?: "--")
                DetailValue("DX Score", score?.dxScore?.toString() ?: "--")
                DetailValue("容错 / 失分", "数据不足，暂不估算")
            }
        }
        item {
            DetailSection(title = "别名与数据来源") {
                DetailValue("别名", songAliases.takeIf { it.isNotEmpty() }?.joinToString("、") ?: "暂无已映射别名")
                if (aliasStatus == null) {
                    DetailValue("别名数据", "尚无本地缓存；基本字段搜索仍可用")
                } else {
                    DetailValue("来源", aliasStatus.sourceLabel)
                    DetailValue("更新时间", aliasStatus.fetchedAtEpochMillis.asLocalTime())
                    DetailValue("数据版本", aliasStatus.contentVersion.take(19))
                    DetailValue("覆盖", "${aliasStatus.songCount} 首 / ${aliasStatus.aliasCount} 个别名")
                    DetailValue("未映射", "${aliasStatus.unmappedSongCount} 首")
                }
            }
        }
    }
}

@Composable
private fun DetailHeader(chart: ChartRecord, onBack: () -> Unit) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            DetailJacket(chart, Modifier.size(116.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    chart.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "Song ${chart.songId} · ${chart.songType.detailName()} · ${chart.difficulty.detailName()}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = chart.difficulty.accentColor().copy(alpha = 0.14f),
                    contentColor = chart.difficulty.accentColor(),
                ) {
                    Text(
                        "${chart.level}  ${chart.levelValue?.let { String.format(Locale.US, "%.1f", it) } ?: "定数未知"}",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
private fun DifficultySwitcher(
    selected: ChartIdentity,
    charts: List<ChartRecord>,
    onChartSelected: (ChartIdentity) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("切换谱面", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(charts, key = { ChartIdentity.from(it).stableKey() }) { chart ->
                val identity = ChartIdentity.from(chart)
                FilterChip(
                    selected = identity == selected,
                    onClick = { onChartSelected(identity) },
                    label = { Text("${chart.songType.detailName()} ${chart.difficulty.detailName()} ${chart.level}") },
                )
            }
        }
    }
}

@Composable
private fun DetailSection(title: String, content: @Composable () -> Unit) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun DetailValue(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun DetailJacket(chart: ChartRecord, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data("https://assets2.lxns.net/maimai/jacket/${chart.songId}.png")
                .size(400)
                .crossfade(150)
                .build(),
            contentDescription = "${chart.title} 曲绘",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
    }
}

@Composable
private fun MissingChartDetail(onBack: () -> Unit, modifier: Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        OutlinedCard {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("当前曲库中找不到该谱面", style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            }
        }
    }
}

private fun SongType.detailName(): String = if (this == SongType.DX) "DX" else "SD"

private fun Difficulty.detailName(): String =
    when (this) {
        Difficulty.BASIC -> "Basic"
        Difficulty.ADVANCED -> "Advanced"
        Difficulty.EXPERT -> "Expert"
        Difficulty.MASTER -> "Master"
        Difficulty.RE_MASTER -> "Re:MASTER"
    }

private fun ChartAvailability.displayName(): String =
    when (this) {
        ChartAvailability.AVAILABLE -> "可用"
        ChartAvailability.LOCKED -> "锁定"
        ChartAvailability.DISABLED -> "已停用"
        ChartAvailability.UPCOMING -> "即将上线"
        ChartAvailability.UNKNOWN -> "未知"
    }

private fun Long.asLocalTime(): String =
    takeIf { it > 0L }?.let { DateFormat.getDateTimeInstance().format(Date(it)) } ?: "未知"

private fun Int.detailVersionName(): String = maimaiVersionNameFor(this) ?: toString()

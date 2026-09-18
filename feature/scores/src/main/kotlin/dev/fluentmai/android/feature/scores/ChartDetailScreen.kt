package dev.fluentmai.android.feature.scores

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
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
import dev.fluentmai.android.core.model.sssPlusTapGreatTolerance
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
    playCounts: List<dev.fluentmai.android.core.model.ChartPlayCount> = emptyList(),
) {
    val componentSettings = LocalComponentSettings.current
    val editing = LocalDetailEditor.current != null
    fun showSection(title: String) = editing || DetailAttributeGroups[title].orEmpty().any { it !in componentSettings.hiddenDetails }
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
    val sssPlusTolerance = remember(chart) { chart.sssPlusTapGreatTolerance() }
    val gridState = rememberLazyGridState()
    var handledScrollToTopRequestId by remember { mutableStateOf(scrollToTopRequestId) }
    LaunchedEffect(scrollToTopRequestId) {
        if (scrollToTopRequestId != handledScrollToTopRequestId) {
            handledScrollToTopRequestId = scrollToTopRequestId
            gridState.animateScrollToItem(0)
        }
    }

    LazyVerticalGrid(
        modifier = modifier.fillMaxSize().testTag("chart-detail-grid"),
        state = gridState,
        columns = GridCells.Adaptive(340.dp),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            if (showSection("标题")) DetailHeader(chart = chart, onBack = onBack)
            else IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            DifficultySwitcher(
                selected = identity,
                charts = sameSongCharts,
                onChartSelected = onChartSelected,
            )
        }
        if (showSection("歌曲")) item {
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
        if (showSection("谱面")) item {
            DetailSection(title = "谱面") {
                DetailValue("类型", chart.songType.detailName())
                DetailValue("难度", "${chart.difficulty.detailName()} ${chart.level}")
                DetailValue("定数", chart.levelValue?.let { String.format(Locale.US, "%.1f", it) } ?: "--")
                DetailValue("水鱼拟合", chart.fittedConstant?.let { String.format(Locale.US, "%.4f", it) } ?: "暂无数据")
                DetailValue("谱师", chart.noteDesigner.ifBlank { "--" })
                DetailValue("总 Note", chart.notes?.total?.toString() ?: "--")
                DetailValue(
                    "Note 明细",
                    chart.notes?.let { notes ->
                        listOfNotNull(
                            notes.tap?.let { "Tap $it" },
                            notes.hold?.let { "Hold $it" },
                            notes.slide?.let { "Slide $it" },
                            notes.touch?.let { "Touch $it" },
                            notes.breakCount?.let { "Break $it" },
                        ).joinToString(" · ").ifBlank { "--" }
                    } ?: "--",
                )
                DetailValue("SSS+容错", sssPlusTolerance?.toString() ?: "--")
            }
        }
        if (showSection("玩家最佳")) item {
            DetailSection(title = "玩家最佳") {
                val score = playerRecord?.score
                DetailValue("达成率", score?.let { String.format(Locale.US, "%.4f%%", it.achievement) } ?: "未游玩")
                DetailValue("Rating 贡献", playerRecord?.rating?.toString() ?: "--")
                DetailValue("FC", score?.fc?.uppercase(Locale.ROOT) ?: "--")
                DetailValue("FS", score?.fs?.uppercase(Locale.ROOT) ?: "--")
                DetailValue("DX Score", score?.dxScore?.toString() ?: "--")
                val pc = playCounts.firstOrNull { it.title == chart.title && it.songType == chart.songType && it.difficulty == chart.difficulty }
                DetailValue("PC", pc?.displayText() ?: score?.playCount?.toString() ?: score?.playCountUpperBound?.let { "≤$it" } ?: "未知")
                if ("PC" !in componentSettings.hiddenDetails && pc == null && score?.playCount == null && score?.playCountUpperBound == null && (score?.observedPlayCount ?: 0) > 0) {
                    Text("已同步 ${score?.observedPlayCount} 次游玩记录，累计 PC 尚未获取", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (showSection("别名与数据来源")) item {
            DetailSection(title = "别名与数据来源") {
                if (editing || chart.fittedUpdatedAt != null) DetailValue("水鱼拟合更新", chart.fittedUpdatedAt?.asLocalTime() ?: "示例更新时间")
                DetailValue("别名", songAliases.takeIf { it.isNotEmpty() }?.joinToString("、") ?: "暂无已映射别名")
                if (aliasStatus == null || editing) {
                    DetailValue("别名数据", "尚无本地缓存；基本字段搜索仍可用")
                }
                if (aliasStatus != null) {
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
            DetailAttribute("封面") { DetailJacket(chart, Modifier.size(116.dp)) }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                DetailAttribute("曲名") { Text(
                    chart.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                ) }
                DetailAttribute("标题谱面身份") { Text(
                    "Song ${chart.songId} · ${chart.songType.detailName()} · ${chart.difficulty.detailName()}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                ) }
                DetailAttribute("标题定数") { Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = chart.difficulty.accentColor(),
                    contentColor = Color.White,
                ) {
                    Text(
                        chart.levelValue?.let { String.format(Locale.US, "%.1f", it) } ?: "--",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium.copy(
                            shadow = difficultyLabelShadow(chart.difficulty.accentColor()),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        ),
                        fontWeight = FontWeight.Bold,
                    )
                }
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
    DetailAttribute(label, Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(end = if (LocalDetailEditor.current != null) 32.dp else 0.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun DetailAttribute(label: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val hidden = label in LocalComponentSettings.current.hiddenDetails
    val edit = LocalDetailEditor.current
    if (hidden && edit == null) return
    Box(modifier.then(if (edit != null) Modifier.clip(RoundedCornerShape(6.dp))
        .background(if (hidden) Color.Red.copy(alpha = .08f) else MaterialTheme.colorScheme.primary.copy(alpha = .05f))
        .semantics { contentDescription = "$label，${if (hidden) "已隐藏，点击恢复" else "已显示，点击隐藏"}" }
        .clickable { edit(label) }.padding(4.dp) else Modifier)) {
        content()
        if (hidden && edit != null) Icon(Icons.Default.Close, "已隐藏", Modifier.align(Alignment.CenterEnd).size(28.dp), tint = Color.Red)
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

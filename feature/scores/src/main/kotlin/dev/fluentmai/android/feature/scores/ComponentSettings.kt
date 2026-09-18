package dev.fluentmai.android.feature.scores

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import dev.fluentmai.android.core.model.*
import java.util.Locale

enum class ThumbnailField(val label: String) {
    Constant("定数"), Bpm("BPM"), Version("版本"), Notes("物量"),
    PlayCount("PC"), Fitted("水鱼拟合"), FitGap("拟合分差"), Tolerance("SSS+容错");

    fun value(chart: ChartRecord, score: ScoreRecord?, pc: ChartPlayCount? = null): String = when (this) {
        Constant -> chart.levelValue?.let { String.format(Locale.US, "%.1f", it) } ?: chart.level
        Bpm -> chart.bpm?.toString() ?: "--"
        Version -> (chart.chartVersionName ?: chart.songVersionName ?: maimaiVersionNameFor(chart.chartVersion)
            ?: maimaiVersionNameFor(chart.songVersion) ?: "--").compactThumbnailVersion()
        Notes -> chart.notes?.total?.toString() ?: "--"
        PlayCount -> pc?.displayText() ?: score.pcText()
        Fitted -> chart.fittedConstant?.let { String.format(Locale.US, "%.4f", it) } ?: "--"
        FitGap -> chart.fittedConstant?.let { fitted -> chart.levelValue?.let { official ->
            String.format(Locale.US, "%.4f", official - fitted)
        } } ?: "--"
        Tolerance -> chart.sssPlusTapGreatTolerance()?.toString() ?: "--"
    }
}

private val ThumbnailVersionPrefix = Regex("^舞萌\\s*[DＤ][XＸ]\\s*(20[0-9]{2})", RegexOption.IGNORE_CASE)
internal fun String.compactThumbnailVersion(): String = replaceFirst(ThumbnailVersionPrefix, "DX$1")

fun ScoreRecord?.pcText(): String = this?.playCount?.toString() ?: this?.playCountUpperBound?.let { "≤$it" } ?: "--"

data class ComponentSettings(
    val thumbnailFields: List<ThumbnailField> = DefaultThumbnailFields,
    val hiddenDetails: Set<String> = emptySet(),
) {
    fun replaceSlot(index: Int, field: ThumbnailField): ComponentSettings {
        if (index !in thumbnailFields.indices || (field in thumbnailFields && thumbnailFields[index] != field)) return this
        return copy(thumbnailFields = thumbnailFields.toMutableList().also { it[index] = field })
    }

    companion object {
        val DefaultThumbnailFields = listOf(ThumbnailField.Constant, ThumbnailField.Bpm, ThumbnailField.Version, ThumbnailField.Notes)
        fun decode(fields: String?, hidden: Set<String>): ComponentSettings {
            val parsed = fields?.split(",")?.mapNotNull { name -> ThumbnailField.entries.find { it.name == name } }
            return ComponentSettings(parsed?.takeIf { it.size == 4 && it.distinct().size == 4 } ?: DefaultThumbnailFields,
                hidden.intersect(DetailAttributeGroups.values.flatten().toSet()))
        }
    }
}

// Stable persisted attribute keys. Keep duplicate header attributes independent of the detail rows.
internal val DetailAttributeGroups = linkedMapOf(
    "标题" to listOf("封面", "曲名", "标题谱面身份", "标题定数"),
    "歌曲" to listOf("Song ID", "谱面身份", "曲师", "类别", "BPM", "歌曲版本", "谱面版本", "上线状态"),
    "谱面" to listOf("类型", "难度", "定数", "水鱼拟合", "谱师", "总 Note", "Note 明细", "SSS+容错"),
    "玩家最佳" to listOf("达成率", "Rating 贡献", "FC", "FS", "DX Score", "PC"),
    "别名与数据来源" to listOf("水鱼拟合更新", "别名", "别名数据", "来源", "更新时间", "数据版本", "覆盖", "未映射"),
)

class ComponentSettingsState(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("component_settings", Context.MODE_PRIVATE)
    var value by mutableStateOf(ComponentSettings.decode(prefs.getString("thumbnail", null), prefs.getStringSet("hidden", emptySet()).orEmpty()))
        private set
    fun save(settings: ComponentSettings) {
        value = ComponentSettings.decode(settings.thumbnailFields.joinToString(",") { it.name }, settings.hiddenDetails)
        prefs.edit().putString("thumbnail", value.thumbnailFields.joinToString(",") { it.name })
            .putStringSet("hidden", value.hiddenDetails.toSet()).apply()
    }
    fun reset() = save(ComponentSettings())
}

val LocalComponentSettings = staticCompositionLocalOf { ComponentSettings() }
val LocalComponentSettingsState = staticCompositionLocalOf<ComponentSettingsState> { error("Missing component settings provider") }
internal val LocalDetailEditor = staticCompositionLocalOf<((String) -> Unit)?> { null }
val LocalChartPlayCounts = staticCompositionLocalOf<List<ChartPlayCount>> { emptyList() }

@Composable
fun ProvideComponentSettings(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val state = remember(context) { ComponentSettingsState(context) }
    CompositionLocalProvider(LocalComponentSettingsState provides state, LocalComponentSettings provides state.value, content = content)
}

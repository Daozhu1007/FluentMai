package dev.fluentmai.android.feature.scores

import dev.fluentmai.android.core.model.*

data class B50PlayerProfile(
    val name: String,
    val iconId: Int?,
    val plateId: Int?,
    val frameId: Int?,
    val trophy: String?,
    val trophyColor: String?,
    val courseRank: Int?,
    val classRank: Int?,
    val iconUrl: String? = null,
    val plateUrl: String? = null,
    val frameUrl: String? = null,
)

internal val B50PlayerProfile.iconArtwork get() = iconUrl ?: iconId?.let(B50Assets::icon)
internal val B50PlayerProfile.plateArtwork get() = plateUrl ?: plateId?.let(B50Assets::plate)
internal val B50PlayerProfile.frameArtwork get() = frameUrl ?: frameId?.let(B50Assets::frame)

data class B50ProfileResult(val player: B50PlayerProfile? = null, val message: String? = null)

internal data class B50DisplayOptions(
    val trophy: Boolean = true, val nameplate: Boolean = true, val course: Boolean = true,
    val rank: Boolean = true, val background: Boolean = true,
)

internal fun posterAssets(best: MaimaiBestSet, player: B50PlayerProfile?, options: B50DisplayOptions): Map<String, String> = buildMap {
    best.all.forEach { item ->
        (item.chart?.songId ?: item.score.songId)?.let { put(B50Assets.jacket(it), "谱面封面：${item.score.title}") }
        B50Assets.status(item.score.fc)?.let { put(it, "FC/AP 状态：${item.score.fc}") }
        B50Assets.status(item.score.fs)?.let { put(it, "FS 状态：${item.score.fs}") }
    }
    player?.iconArtwork?.let { put(it, "玩家头像") }
    if (options.nameplate) player?.plateArtwork?.let { put(it, "玩家姓名框") }
    // Keep the original dimensions even when its appearance is hidden.
    player?.frameArtwork?.let { put(it, "玩家收藏品背景") }
    if (options.course) player?.courseRank?.let { put(B50Assets.course(it), "段位图标") }
    if (options.rank) player?.classRank?.let { put(B50Assets.rank(it), "友人对战等级") }
}

internal fun posterBestSet(scores: List<ScoreRecord>, charts: List<ChartRecord>, versions: List<MaimaiMajorVersion>): MaimaiBestSet {
    val matched = matchChartsForScores(charts, scores)
    return buildMaimaiBestSet(scores.map { score ->
        val chart = matched[score.id]
        MaimaiRatedScore(score, chart, chart?.levelValue?.let { calculateDxRating(it, score.achievement, score.fc) })
    }, resolveCurrentMaimaiVersion(versions, charts))
}

internal fun posterDxStars(dxScore: Int?, totalNotes: Int?): Int? {
    if (dxScore == null || totalNotes == null || totalNotes <= 0 || dxScore < 0) return null
    val value = dxScore.toLong() * 100
    val maximum = totalNotes.toLong() * 3
    if (dxScore > maximum) return null
    return when {
        value >= maximum * 97 -> 5
        value >= maximum * 95 -> 4
        value >= maximum * 93 -> 3
        value >= maximum * 90 -> 2
        value >= maximum * 85 -> 1
        else -> 0
    }
}

internal fun posterRatingColor(rating: Int): String = when {
    rating >= 15000 -> "rainbow"
    rating >= 14500 -> "platinum"
    rating >= 14000 -> "gold"
    rating >= 13000 -> "silver"
    rating >= 12000 -> "bronze"
    rating >= 10000 -> "purple"
    rating >= 7000 -> "red"
    rating >= 4000 -> "orange"
    rating >= 2000 -> "green"
    rating >= 1000 -> "blue"
    else -> "normal"
}

internal object B50Assets {
    const val COLLECTIONS = "https://assets2.lxns.net/maimai"
    const val UI = "https://maimai.lxns.net/assets/maimai"
    fun jacket(id: Int) = "$COLLECTIONS/jacket/$id.png"
    fun icon(id: Int) = "$COLLECTIONS/icon/$id.png"
    fun plate(id: Int) = "$COLLECTIONS/plate/$id.png"
    fun frame(id: Int) = "$COLLECTIONS/frame/$id.png"
    fun course(id: Int) = "$UI/course_rank/$id.webp"
    fun rank(id: Int) = "$UI/class_rank/$id.webp"
    // Local keys, never passed to the network image loader.
    fun rating(value: Int) = "bundled:rating/${posterRatingColor(value)}"
    fun trophy(color: String?) = "bundled:trophy/${trophyColor(color)}"
    fun trophyColor(color: String?) = color?.lowercase()?.takeIf { it in setOf("normal", "bronze", "silver", "gold", "rainbow") } ?: "normal"
    fun status(raw: String?): String? {
        val code = when (raw?.lowercase()) { "fc+" -> "fcp"; "ap+" -> "app"; "fs+" -> "fsp"; "fsd+", "fsdx+" -> "fsdp"; "fsdx" -> "fsd"; else -> raw?.lowercase() }
        return code?.takeIf { it in setOf("fc", "fcp", "ap", "app", "fs", "fsp", "fsd", "fsdp", "sync") }?.let { "$UI/music_icon/$it.webp" }
    }
}

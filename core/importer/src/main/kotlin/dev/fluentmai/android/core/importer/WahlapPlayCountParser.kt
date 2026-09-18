package dev.fluentmai.android.core.importer

import dev.fluentmai.android.core.model.ChartPlayCount
import dev.fluentmai.android.core.model.Difficulty
import dev.fluentmai.android.core.model.SongType
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.Normalizer

/** The Mybest page is a ranking, not a complete source of PC. Each musicDetail
 * page contains the counts for all played difficulties of one song/type. */
data class WahlapMusicDetailTarget(
    val title: String,
    val songType: SongType,
    val url: String,
    val difficulties: Set<Difficulty>,
    val sourceDifficulty: Difficulty = difficulties.first(),
)

class WahlapPlayCountIndex {
    private val targets = linkedMapOf<Pair<String, SongType>, WahlapMusicDetailTarget>()
    var missingLinks: Int = 0
        private set

    fun addPage(html: String, difficulty: Difficulty, catalog: MaimaiSongCatalog) {
        val result = WahlapPlayCountParser.targets(html, difficulty, catalog)
        missingLinks += result.missingLinks
        for (target in result.targets) {
            val key = target.title to target.songType
            val previous = targets[key]
            targets[key] = if (previous == null) target else
                previous.copy(difficulties = previous.difficulties + target.difficulties)
        }
    }

    fun targets(): List<WahlapMusicDetailTarget> = targets.values.toList()
}

object WahlapPlayCountParser {
    const val DETAIL_URL = "https://maimai.wahlap.com/maimai-mobile/record/musicDetail/"
    private val tokens = listOf("basic", "advanced", "expert", "master", "remaster")
    private val countPattern = Regex(
        "(?:游戏次数|遊戲次數|游玩次数|遊玩次數|プレイ回数|PLAY\\s*COUNT|\\bPC\\b)\\s*[:：]?\\s*([0-9][0-9,]*)",
        RegexOption.IGNORE_CASE,
    )

    data class TargetList(val targets: List<WahlapMusicDetailTarget>, val missingLinks: Int)

    fun targets(html: String, difficulty: Difficulty, catalog: MaimaiSongCatalog): TargetList {
        val doc = Jsoup.parse(html, WahlapScorePageUrls.scorePageUrl(difficulty))
        var missing = 0
        val targets = doc.select("form[action*=musicDetail]").mapNotNull { form ->
            // Ignore unplayed cards if the server includes them despite playCheck=on.
            if (form.select(".music_score_block").none { '%' in it.text() }) return@mapNotNull null
            // U+3000 is the actual title of song 1422, not an empty/malformed link.
            val title = form.selectFirst(".music_name_block")?.text()?.trim { it <= ' ' }.orEmpty()
            val action = runCatching { URI(form.absUrl("action")) }.getOrNull()
            val idx = form.selectFirst("input[name=idx]")?.attr("value").orEmpty()
            val url = safeDetailUrl("$DETAIL_URL?idx=${URLEncoder.encode(idx, "UTF-8")}")
            if (title.isEmpty() || action?.scheme != "https" || action.host != "maimai.wahlap.com" ||
                action.port != -1 || action.userInfo != null || action.rawPath != URI(DETAIL_URL).path ||
                form.attr("method").let { it.isNotEmpty() && !it.equals("get", true) } || url == null) {
                missing++
                return@mapNotNull null
            }
            // The chart kind badge is outside the form in the official list.
            val card = form.parents().takeWhile { it.select("form[action*=musicDetail]").size == 1 }
                .firstOrNull { it.hasClass("w_450") } ?: form
            val type = typeFrom(card) ?: catalog.resolveSongType(
                title, difficulty.levelIndex, SongType.STANDARD, form.select(".music_lv_block").text(),
            )
            WahlapMusicDetailTarget(title, type, url, setOf(difficulty))
        }
        return TargetList(targets, missing)
    }

    /** Missing counts remain unknown; never substitute 0 or a recent-record count. */
    fun counts(html: String, target: WahlapMusicDetailTarget): List<ChartPlayCount> {
        val doc = Jsoup.parse(html)
        val title = doc.selectFirst(".m_5.f_15.break")?.text()?.trim { it <= ' ' }
        if (title == null || normalized(title) != normalized(target.title) ||
            doc.select(".music_detail_table").isEmpty() || WahlapActivityParser.hasErrorPage(html)) return emptyList()
        return tokens.mapIndexedNotNull { index, token ->
            val section = doc.selectFirst("#$token.music_${token}_score_back") ?: return@mapIndexedNotNull null
            val type = typeFrom(section) ?: typeFrom(doc.selectFirst(".music_detail_table")?.parent())
                ?: return@mapIndexedNotNull null
            if (type != target.songType) return@mapIndexedNotNull null
            val count = countPattern.find(section.text())?.groupValues?.get(1)
                ?.replace(",", "")?.toIntOrNull() ?: return@mapIndexedNotNull null
            ChartPlayCount(target.title, type, requireNotNull(Difficulty.fromLevelIndex(index)), count)
        }
    }

    /** Only same-origin, read-only detail requests, with the opaque idx encoded once. */
    fun safeDetailUrl(url: String): String? = runCatching {
        val uri = URI(url)
        if (uri.scheme != "https" || uri.host != "maimai.wahlap.com" || uri.port != -1 || uri.userInfo != null ||
            uri.rawPath != URI(DETAIL_URL).path) return null
        val query = uri.rawQuery ?: return null
        if (!query.startsWith("idx=") || '&' in query) return null
        val idx = URLDecoder.decode(query.substring(4), "UTF-8")
        if (!Regex("[A-Za-z0-9+/=_-]{1,2048}").matches(idx)) return null
        "$DETAIL_URL?idx=${URLEncoder.encode(idx, "UTF-8")}" // Strip scroll anchors.
    }.getOrNull()

    private fun normalized(title: String) = Normalizer.normalize(title, Normalizer.Form.NFKC).trim()

    private fun typeFrom(element: Element?): SongType? {
        val images = element?.select("img[src*=music_dx], img[src*=music_standard]").orEmpty()
        val dx = images.any { "music_dx" in it.attr("src") }
        val standard = images.any { "music_standard" in it.attr("src") }
        return when {
            dx && !standard -> SongType.DX
            standard && !dx -> SongType.STANDARD
            else -> null
        }
    }
}

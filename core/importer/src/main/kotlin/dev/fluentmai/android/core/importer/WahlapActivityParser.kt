package dev.fluentmai.android.core.importer

import dev.fluentmai.android.core.model.*
import org.jsoup.Jsoup
import java.net.URI
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object WahlapActivityParser {
    const val RECENT_URL = "https://maimai.wahlap.com/maimai-mobile/record/"
    private val datePattern = Regex("[0-9]{4}/[0-9]{1,2}/[0-9]{1,2}\\s+[0-9]{1,2}:[0-9]{2}")
    private val countPattern = Regex("(?:游戏次数|遊戲次數|游玩次数|遊玩次數|プレイ回数|PLAY\\s*COUNT|\\bPC\\b)\\s*[:：]?\\s*([0-9][0-9,]*)", RegexOption.IGNORE_CASE)

    fun playCountUrl(difficulty: Difficulty) =
        "https://maimai.wahlap.com/maimai-mobile/record/musicMybest/search/?diff=${difficulty.levelIndex}"

    fun hasErrorPage(html: String) = Jsoup.parse(html).select(".title_error, img[src*=title_error]").isNotEmpty()

    // Numeric diagnostic only: never include response text, user data or session links.
    fun errorCode(html: String): String? = Regex("(?:错误码|錯誤碼|エラーコード|error\\s*code)\\s*[:：]?\\s*([0-9]{1,12})(?![0-9])", RegexOption.IGNORE_CASE)
        .find(Jsoup.parse(html).text())?.groupValues?.get(1)

    fun records(html: String, catalog: MaimaiSongCatalog): List<PlayRecord> =
        Jsoup.parse(html).select(".playlog_top_container").mapNotNull { top ->
            val main = top.parent()?.children()?.firstOrNull { "playlog_" in it.className() && it != top } ?: return@mapNotNull null
            val diff = difficulty(main.className()) ?: return@mapNotNull null
            val rawTime = datePattern.find(top.text())?.value ?: return@mapNotNull null
            val time = runCatching { LocalDateTime.parse(rawTime.replace(Regex("\\s+"), " "), DateTimeFormatter.ofPattern("uuuu/M/d H:mm").withResolverStyle(java.time.format.ResolverStyle.STRICT))
                .atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli() }.getOrNull() ?: return@mapNotNull null
            val titleNode = main.selectFirst(".basic_block.break, .music_name_block, .basic_block")?.clone() ?: return@mapNotNull null
            titleNode.select(".music_lv_back, .music_lv_block").remove()
            var title = titleNode.text().trim()
            val kind = main.select("img.playlog_music_kind_icon, img[src*=music_standard], img[src*=music_dx]").joinToString { it.attr("src") }
            if (kind.isBlank()) return@mapNotNull null
            val type = if ("standard" in kind) SongType.STANDARD else SongType.DX
            val achievement = Regex("([0-9]{1,3}(?:\\.[0-9]{1,4})?)\\s*%").find(main.select(".playlog_achievement_txt").text())
                ?.groupValues?.get(1)?.toDoubleOrNull()?.takeIf { it in 0.0..101.0 }
            val pair = Regex("([0-9,]+)\\s*/\\s*([0-9,]+)").find(main.select(".playlog_score_block").text())
            val dx = pair?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull()
            val maximum = pair?.groupValues?.get(2)?.replace(",", "")?.toIntOrNull()
            if (title == "Link" && maximum != null) {
                val matches = catalog.charts().filter { it.title in setOf("Link", "Link(CoF)") && it.songType == type && it.difficulty == diff && it.notes?.total?.times(3) == maximum }
                if (matches.size == 1) title = matches.single().title
            }
            val flags = main.select("img[src]").mapNotNull { Regex("(?:music_icon_|playlog/)([a-z]+)\\.").find(it.attr("src"))?.groupValues?.get(1) }
                .map { when (it) { "fcplus" -> "fcp"; "applus" -> "app"; "fsplus" -> "fsp"; "fsdplus", "fdxp" -> "fsdp"; "fdx" -> "fsd"; else -> it } }
            val fc = flags.firstOrNull { it in setOf("fc", "fcp", "ap", "app") }
            val fs = flags.firstOrNull { it in setOf("fs", "fsp", "fsd", "fsdp", "sync") }
            val track = Regex("(?:TRACK|曲目)\\s*([0-9]+)", RegexOption.IGNORE_CASE).find(top.text())?.groupValues?.get(1).orEmpty()
            // The rotating page idx and import timestamp must never identify a play.
            val key = listOf(title, type.name, diff.name, time, track, achievement, dx).joinToString("|")
            PlayRecord(Hashing.sha256(key), catalog.idForTitle(title), title, type, diff, time, achievement, dx, fc, fs)
        }.distinctBy { it.id }

    fun playCounts(html: String, difficulty: Difficulty, catalog: MaimaiSongCatalog): List<ChartPlayCount> =
        Jsoup.parse(html).select(".music_name_block").mapNotNull { name ->
            // In the real page the DX badge can be outside the form. Stay within one card.
            val ancestors = name.parents().takeWhile { it.select(".music_name_block").size == 1 }
            val card = ancestors.firstOrNull { it.hasClass("w_450") && countPattern.containsMatchIn(it.text()) }
                ?: ancestors.firstOrNull { countPattern.containsMatchIn(it.text()) } ?: return@mapNotNull null
            val count = countPattern.find(card.text())?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull() ?: return@mapNotNull null
            val title = name.text().trim { it <= ' ' }.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val kind = card.select("img[src*=music_dx], img[src*=music_standard]").joinToString { it.attr("src") }
            val detected = if ("music_dx" in kind) SongType.DX else SongType.STANDARD
            val type = catalog.resolveSongType(title, difficulty.levelIndex, detected, card.select(".music_lv_block").text())
            ChartPlayCount(title, type, difficulty, count)
        }.distinctBy { Triple(it.title, it.songType, it.difficulty) }

    fun playCountCardCount(html: String) = Jsoup.parse(html).select(".music_name_block").size

    fun isPlayCountPage(html: String, counts: List<ChartPlayCount>): Boolean {
        if (counts.isNotEmpty()) return true
        val doc = Jsoup.parse(html)
        return doc.select(".music_name_block").isEmpty() && html.contains("</html>", true) &&
            doc.select("[href*=musicMybest], [action*=musicMybest], [src*=title_music]").isNotEmpty()
    }

    fun isRecordPage(html: String): Boolean {
        val doc = Jsoup.parse(html)
        return doc.select(".playlog_top_container").isNotEmpty() ||
            (html.contains("</html>", true) && doc.select("[src*=title_playlog], [src*=title_record]").isNotEmpty() &&
                Regex("暂无|没有|不存在|ありません|no\\s+(?:play|record)", RegexOption.IGNORE_CASE).containsMatchIn(doc.text()))
    }

    fun safeActivityUrl(url: String): String? = safeRecordUrl(url) ?: WahlapPlayCountParser.safeDetailUrl(url) ?: runCatching {
        val uri = URI(url)
        if (uri.scheme != "https" || uri.host != "maimai.wahlap.com" || uri.port != -1 || uri.userInfo != null ||
            uri.path != "/maimai-mobile/record/musicMybest/search/" || !Regex("diff=[0-4]").matches(uri.rawQuery.orEmpty())) return null
        uri.toString()
    }.getOrNull()

    fun pagination(html: String): List<String> = Jsoup.parse(html, RECENT_URL).select("a[href]")
        .mapNotNull { safeRecordUrl(it.absUrl("href")) }.distinct()

    fun safeRecordUrl(url: String): String? = runCatching {
        val uri = URI(url)
        if (uri.scheme != "https" || uri.host != "maimai.wahlap.com" || uri.port != -1 || uri.userInfo != null ||
            uri.path !in setOf("/maimai-mobile/record/", "/maimai-mobile/record/index/")) return null
        val query = uri.rawQuery
        if (query != null && !Regex("(?:page|index)=[0-9]+(?:&(?:page|index)=[0-9]+)*").matches(query)) return null
        URI(uri.scheme, uri.authority, uri.path, query, null).toString()
    }.getOrNull()

    private fun difficulty(signal: String) = when {
        "remaster" in signal -> Difficulty.RE_MASTER
        "master" in signal -> Difficulty.MASTER
        "expert" in signal -> Difficulty.EXPERT
        "advanced" in signal -> Difficulty.ADVANCED
        "basic" in signal -> Difficulty.BASIC
        else -> null
    }
}

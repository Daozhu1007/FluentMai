package dev.fluentmai.android.core.importer

import dev.fluentmai.android.core.model.Difficulty
import dev.fluentmai.android.core.model.SongType
import org.junit.Assert.*
import org.junit.Test

class WahlapPlayCountParserTest {
    private val catalog = MaimaiSongCatalog.Empty

    @Test fun fullScoreListHasNoRankingCutoffAndDeduplicatesAcrossDifficulties() {
        val html = (1..180).joinToString("") { card("Song $it", "idx$it") }
        val index = WahlapPlayCountIndex()
        index.addPage(html, Difficulty.EXPERT, catalog)
        index.addPage(html, Difficulty.MASTER, catalog)
        assertEquals(180, index.targets().size)
        assertEquals(0, index.missingLinks)
        val last = index.targets().last()
        assertEquals(setOf(Difficulty.EXPERT, Difficulty.MASTER), last.difficulties)
        // A low-PC song beyond the former ranking is still parsed and stored.
        assertEquals(1, WahlapPlayCountParser.counts(detail("Song 180", "dx", "master" to "1"), last).single().count)
    }

    @Test fun collectsExternalDxBadgeAndKeepsStandardSeparate() {
        val index = WahlapPlayCountIndex()
        index.addPage(card("Same title", "dx") + card("Same title", "std", "standard"), Difficulty.MASTER, catalog)
        assertEquals(setOf(SongType.DX, SongType.STANDARD), index.targets().map { it.songType }.toSet())
    }

    @Test fun ideographicSpaceSongTitleIsNotTreatedAsAMissingDetailLink() {
        val result = WahlapPlayCountParser.targets(card("　", "blank-title"), Difficulty.EXPERT, catalog)
        assertEquals(0, result.missingLinks)
        assertEquals("　", result.targets.single().title)
        assertEquals(1, WahlapPlayCountParser.counts(detail("　", "dx", "expert" to "1"), result.targets.single()).single().count)
    }

    @Test fun opaqueIdxIsEncodedOnceAndOnlyReadOnlySameOriginUrlsAreAccepted() {
        val target = WahlapPlayCountParser.targets(card("Test", "abc+/def=="), Difficulty.MASTER, catalog).targets.single()
        assertEquals("${WahlapPlayCountParser.DETAIL_URL}?idx=abc%2B%2Fdef%3D%3D", target.url)
        assertEquals(target.url, WahlapActivityParser.safeActivityUrl(target.url + "#master"))
        listOf(
            target.url.replace("maimai.wahlap.com", "example.com"),
            target.url.replace("https:", "http:"),
            target.url.replace("musicDetail/", "../home/"),
            target.url.replace("maimai.wahlap.com", "user@maimai.wahlap.com"),
            target.url.replace("maimai.wahlap.com", "maimai.wahlap.com:443"),
            "${target.url}&action=delete", "${WahlapPlayCountParser.DETAIL_URL}?idx=%252F",
            "${WahlapPlayCountParser.DETAIL_URL}?idx=",
        ).forEach { assertNull(it, WahlapActivityParser.safeActivityUrl(it)) }
        val hostile = card("Test", "1").replace(WahlapPlayCountParser.DETAIL_URL, "https://example.com/musicDetail/")
        assertTrue(WahlapPlayCountParser.targets(hostile, Difficulty.MASTER, catalog).targets.isEmpty())
    }

    @Test fun missingLinksAreReportedButUnplayedCardsAreNotRequested() {
        val html = card("Bad", "") + card("Unplayed", "2").replace("<div class=\"music_score_block\">100.0000%</div>", "")
        val result = WahlapPlayCountParser.targets(html, Difficulty.MASTER, catalog)
        assertEquals(1, result.missingLinks)
        assertTrue(result.targets.isEmpty())
    }

    @Test fun readsIndependentCountsForAllFiveDifficultiesWithoutUsingLastPlayedDateOrDxScore() {
        val target = target("Example & Test")
        val html = detail("Example &amp; Test", "dx", "basic" to "1", "advanced" to "2", "expert" to "3", "master" to "71", "remaster" to "1,234")
        val result = WahlapPlayCountParser.counts(html, target)
        assertEquals(Difficulty.entries, result.map { it.difficulty })
        assertEquals(listOf(1, 2, 3, 71, 1234), result.map { it.count })
        assertTrue(result.all { it.title == target.title && it.songType == SongType.DX })
    }

    @Test fun supportsOfficialEnglishAndJapaneseLabelsAndExplicitZeroOnly() {
        for (label in listOf("PLAY COUNT", "プレイ回数", "游戏次数", "遊玩次數")) {
            val html = detail("Test", "dx", "master" to "0").replace("游戏次数", label)
            assertEquals(0, WahlapPlayCountParser.counts(html, target("Test")).single().count)
            assertTrue(WahlapPlayCountParser.counts(html.replace("<td>0</td>", "<td>—</td>"), target("Test")).isEmpty())
        }
    }

    @Test fun wrongSongWrongTypeMissingOrErrorPagesCannotOverwriteKnownPc() {
        val valid = detail("Test", "dx", "master" to "2")
        assertTrue(WahlapPlayCountParser.counts(valid, target("Other")).isEmpty())
        assertTrue(WahlapPlayCountParser.counts(valid, target("Test").copy(songType = SongType.STANDARD)).isEmpty())
        assertTrue(WahlapPlayCountParser.counts("<html>请重新登录</html>", target("Test")).isEmpty())
        assertTrue(WahlapPlayCountParser.counts(valid.replace("<body>", "<body><img src='title_error.png'>"), target("Test")).isEmpty())
        assertTrue(WahlapPlayCountParser.counts(valid.replace("master", "expert"), target("Test")).none { it.difficulty == Difficulty.MASTER })
    }

    private fun target(title: String) = WahlapMusicDetailTarget(title, SongType.DX, "${WahlapPlayCountParser.DETAIL_URL}?idx=1", setOf(Difficulty.MASTER))

    private fun card(title: String, idx: String, type: String = "dx") = """
        <div class="w_450 m_15 p_r f_0"><div class="music_master_score_back">
          <form action="${WahlapPlayCountParser.DETAIL_URL}" method="get">
            <div class="music_name_block">$title</div><div class="music_lv_block">14+</div>
            <div class="music_score_block">100.0000%</div><input name="idx" value="$idx">
          </form></div><img src="music_$type.png"></div>
    """.trimIndent()

    // Reduced structural fixture checked against official-page samples in
    // minty99/maistats: crates/maimai-parsers/examples/maimai/music_detail/example{1,2}.html.
    // No player identifiers, cookies or captured encrypted idx are retained.
    private fun detail(title: String, type: String, vararg counts: Pair<String, String>) = """
        <html><body><div><div class="m_5 f_15 break">$title</div><table class="music_detail_table"></table></div>
        ${counts.joinToString("") { (diff, count) -> """
          <div id="$diff" class="music_${diff}_score_back w_450"><img src="music_$type.png">
            <div class="black_block"><table><tr><td>最后游玩时间：</td><td>2026/09/15 21:42</td></tr>
            <tr><td>游戏次数：</td><td>$count</td></tr></table></div>
            <div class="music_score_block">100.5000%</div><div class="music_score_block">2,410 / 2,700</div>
          </div>""" }}
        </body></html>
    """.trimIndent()
}

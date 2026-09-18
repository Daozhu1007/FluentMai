package dev.fluentmai.android.core.importer

import dev.fluentmai.android.core.model.*
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class WahlapActivityParserTest {
    private val catalog = MaimaiSongCatalog.Empty
    private fun record(track: Int = 1, time: String = "2026/09/16 00:03", idx: Int = 1) = """
        <div class="t_l v_b p_10 f_0">
          <div class="playlog_top_container"><div class="sub_title"><span>TRACK $track</span><span>$time</span></div></div>
          <div class="playlog_remaster_container">
            <img class="playlog_music_kind_icon" src="music_standard.png">
            <div class="basic_block break">Test Song</div>
            <div class="playlog_achievement_txt">100.5000<span>%</span></div>
            <div class="playlog_score_block">2,990 / 3,000</div>
            <img src="music_icon_app.png"><img src="music_icon_fsdp.png">
            <a href="/maimai-mobile/record/playlogDetail/?idx=$idx">详情</a>
          </div>
        </div>
    """.trimIndent()

    @Test fun parsesActualDateScoreTypeAndStatuses() {
        val item = WahlapActivityParser.records(record(), catalog).single()
        assertEquals(Difficulty.RE_MASTER, item.difficulty)
        assertEquals(SongType.STANDARD, item.songType)
        assertEquals(Instant.parse("2026-09-15T16:03:00Z").toEpochMilli(), item.playedAt)
        assertEquals(100.5, item.achievement!!, .00001)
        assertEquals(2990, item.dxScore)
        assertEquals("app", item.fc)
        assertEquals("fsdp", item.fs)
    }

    @Test fun rotatingIndexesDoNotDuplicateButDifferentTracksRemainSeparate() {
        val old = WahlapActivityParser.records(record(idx = 20), catalog).single()
        val new = WahlapActivityParser.records(record(idx = 2), catalog).single()
        assertEquals(old.id, new.id)
        assertEquals(2, WahlapActivityParser.records(record() + record() + record(track = 2), catalog).size)
    }

    @Test fun missingDatesOrLoginPagesCannotCreateFakeHistory() {
        assertTrue(WahlapActivityParser.records(record(time = "未知"), catalog).isEmpty())
        assertTrue(WahlapActivityParser.records(record(time = "2026/02/31 12:00"), catalog).isEmpty())
        assertTrue(WahlapActivityParser.records("<html>请登录</html>", catalog).isEmpty())
    }

    @Test fun followsOnlyReadOnlyRecentListLinksOnOfficialHost() {
        val allowed = WahlapActivityParser.RECENT_URL + "?page=2"
        assertEquals(listOf(allowed), WahlapActivityParser.pagination("""
            <a href="?page=2">2</a><a href="?page=2">next</a>
            <a href="https://attacker.example/maimai-mobile/record/">external</a>
            <a href="/maimai-mobile/logout/">logout</a><a href="?action=delete">action</a>
        """))
        assertNull(WahlapActivityParser.safeRecordUrl("https://maimai.wahlap.com:443/maimai-mobile/record/"))
        assertNull(WahlapActivityParser.safeRecordUrl("https://x@maimai.wahlap.com/maimai-mobile/record/"))
    }

    @Test fun pcMustBeAnExplicitPerChartCountNotInferredFromRecentRecords() {
        val fixture = javaClass.getResource("/wahlap_valid_fixture.html")!!.readText()
        assertTrue(WahlapActivityParser.playCounts(fixture, Difficulty.EXPERT, catalog).isEmpty())
        val doc = org.jsoup.Jsoup.parse(fixture)
        doc.select("form[action*=musicDetail]").first()!!.append("<div>PC: 1,234</div>")
        val counts = WahlapActivityParser.playCounts(doc.outerHtml(), Difficulty.EXPERT, catalog)
        assertEquals(1234, counts.single().count)
        assertEquals("PANDORA PARADOXXX", counts.single().title)
    }

    @Test fun officialMybestCountsNeedNoAchievementAndDxBadgeCanBeOutsideForm() {
        fun card(title: String, count: Int, kind: String) = """
            <div class="w_450 m_15 p_r f_0">
              <div class="music_master_score_back"><form action="/maimai-mobile/record/musicDetail/">
                <div class="music_lv_block">14+</div><div class="music_name_block">$title</div>
                <div class="music_score_block"><span>游戏次数：</span>$count</div>
                <input name="idx" value="opaque-song-id">
              </form></div>
              <img class="music_kind_icon" src="music_$kind.png">
            </div>
        """
        val html = card("ViRTUS", 71, "dx") + card("TEmPTaTiON", 41, "standard") + card("TEmPTaTiON", 9, "dx")
        val counts = WahlapActivityParser.playCounts(html, Difficulty.MASTER, catalog)
        assertEquals(listOf(71, 41, 9), counts.map { it.count })
        assertEquals(listOf(SongType.DX, SongType.STANDARD, SongType.DX), counts.map { it.songType })
        assertEquals("https://maimai.wahlap.com/maimai-mobile/record/musicMybest/search/?diff=3", WahlapActivityParser.playCountUrl(Difficulty.MASTER))
        assertEquals(WahlapActivityParser.playCountUrl(Difficulty.MASTER), WahlapActivityParser.safeActivityUrl(WahlapActivityParser.playCountUrl(Difficulty.MASTER)))
        assertNull(WahlapActivityParser.safeActivityUrl("https://maimai.wahlap.com/maimai-mobile/record/musicMybest/search/?diff=3&action=delete"))
    }

    @Test fun recentTitleExcludesNestedLevelAndUnderstandsPlaylogStatusAssets() {
        val html = record().replace("Test Song</div>", "<div class='music_lv_back'>14+</div>Test Song</div>")
            .replace("music_icon_app.png", "playlog/applus.png").replace("music_icon_fsdp.png", "playlog/fsdplus.png")
        val play = WahlapActivityParser.records(html, catalog).single()
        assertEquals("Test Song", play.title)
        assertEquals("app", play.fc)
        assertEquals("fsdp", play.fs)
        assertTrue(WahlapActivityParser.isRecordPage(html))
        assertFalse(WahlapActivityParser.isRecordPage("<html><div>login</div></html>"))
    }

    @Test fun chineseTrackNumbersProduceDistinctIdentitiesAndShanghaiDates() {
        val official = record(track = 3, time = "2026/09/15 21:42").replace("TRACK", "曲目")
            .replace("Test Song", "Panopticon").replace("100.5000", "100.6208")
        val play = WahlapActivityParser.records(official, catalog).single()
        assertEquals("Panopticon", play.title)
        assertEquals(Instant.parse("2026-09-15T13:42:00Z").toEpochMilli(), play.playedAt)
        val other = WahlapActivityParser.records(official.replace("曲目 3", "曲目 2"), catalog).single()
        assertNotEquals(play.id, other.id)
    }
}

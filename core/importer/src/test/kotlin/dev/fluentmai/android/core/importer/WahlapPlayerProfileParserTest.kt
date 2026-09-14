package dev.fluentmai.android.core.importer

import org.junit.Assert.*
import org.junit.Test

class WahlapPlayerProfileParserTest {
    private val home = """
        <div class="name_block f_l f_16">测试 &amp; 玩家</div>
        <div class="rating_block">99999</div>
        <input name="token" value="must-not-be-retained">
        <div class="trophy_block trophy_Rainbow"><div class="trophy_inner_block"><span>真·舞萌</span></div></div>
        <img src="/maimai-mobile/img/Icon/4e6e74e816900636.png?token=secret">
        <img src="/maimai-mobile/img/course/course_rank_23T7GHJvGe.png">
        <img src="/maimai-mobile/img/class/class_rank_s_25ZqZmdpb8.png">
    """.trimIndent()

    @Test fun extractsOfficialDisplayDataWithoutRatingOrCredentials() {
        val player = requireNotNull(WahlapPlayerProfileParser.parse(home))
        assertEquals("测试 & 玩家", player.name)
        assertEquals("真·舞萌", player.trophy)
        assertEquals("rainbow", player.trophyColor)
        assertEquals(23, player.courseRank)
        assertEquals(25, player.classRank)
        assertEquals("https://maimai.wahlap.com/maimai-mobile/img/Icon/4e6e74e816900636.png", player.iconUrl)
        assertFalse(player.toString().contains("99999"))
        assertFalse(player.toString().contains("secret"))
        assertFalse(player.toString().contains("must-not-be-retained"))
    }

    @Test fun preservesHashedPlateAndFrameUrlsIncludingCssBackground() {
        val player = requireNotNull(WahlapPlayerProfileParser.parse(home + """
            <img src="https://maimai.wahlap.com/maimai-mobile/img/NamePlate/abc123.png">
            <div style="background-image: url('/maimai-mobile/img/Frame/def456.png')"></div>
        """))
        assertTrue(player.plateUrl!!.endsWith("/NamePlate/abc123.png"))
        assertTrue(player.frameUrl!!.endsWith("/Frame/def456.png"))
    }

    @Test fun missingOrUnauthenticatedProfileNeverInventsPlayer() {
        assertNull(WahlapPlayerProfileParser.parse("<title>登录失败</title>"))
        assertNull(WahlapPlayerProfileParser.parse("<div class='music_name_block'>Song</div>"))
        val player = requireNotNull(WahlapPlayerProfileParser.parse("<div class='name_block'>New account</div>"))
        assertNull(player.iconUrl)
        assertNull(player.plateUrl)
        assertNull(player.courseRank)
    }

    @Test fun rejectsArbitraryAndAuthenticatedAssetEndpoints() {
        listOf("https://evil.example/a.png", "file:///tmp/a.png", "https://maimai.wahlap.com/home/?token=secret",
            "https://secret@maimai.wahlap.com/maimai-mobile/img/Icon/a.png").forEach {
            assertNull(WahlapPlayerProfileParser.safeArtworkUrl(it))
        }
    }

    @Test fun collectsOnlyExplicitlyEquippedArtworkAndReadOnlyLinks() {
        val collection = """
            <a href="/maimai-mobile/collection/nameplate/">牌子</a>
            <a href="/maimai-mobile/collection/frame/">背景</a>
            <a href="/maimai-mobile/collection/nameplate/update/?idx=1">不可访问</a>
            <div><img src="/maimai-mobile/img/NamePlate/not-equipped.png"></div>
            <div><img src="/maimai-mobile/img/NamePlate/equipped.png"><span>設定中</span></div>
        """
        assertEquals(2, WahlapPlayerProfileParser.collectionLinks(collection).size)
        val result = WahlapPlayerProfileParser.parse(WahlapPlayerProfileParser.addEquippedArtwork(home, collection))!!
        assertTrue(result.plateUrl!!.endsWith("/equipped.png"))
        val noSelection = collection.replace("設定中", "未获得")
        assertNull(WahlapPlayerProfileParser.parse(WahlapPlayerProfileParser.addEquippedArtwork(home, noSelection))!!.plateUrl)
    }
}

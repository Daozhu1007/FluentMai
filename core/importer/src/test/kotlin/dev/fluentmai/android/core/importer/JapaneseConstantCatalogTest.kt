package dev.fluentmai.android.core.importer

import dev.fluentmai.android.core.model.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class JapaneseConstantCatalogTest {
    private fun chart(type: SongType = SongType.DX, index: Int = 3) = ChartRecord(
        1, "Test", "", "", null, 1, null, 1, null, type, Difficulty.fromLevelIndex(index)!!,
        index, "13+", 13.7, "", null,
    )

    @Test fun separatesTypeAndDifficultyWithoutReplacingChineseConstant() {
        val catalog = JapaneseConstantCatalog.parse("""[{"title":"Test","lev_mas":"13+","lev_mas_i":"13.8",
            "dx_lev_mas":"14","dx_lev_mas_i":"14.2","dx_lev_remas":"14+","dx_lev_remas_i":"14.7",
            "date_added":"20260917"}]""")
        assertEquals(13.8, catalog.find(chart(SongType.STANDARD))!!, 0.0)
        assertEquals(14.2, catalog.find(chart())!!, 0.0)
        assertEquals(14.7, catalog.find(chart(index = 4))!!, 0.0)
        assertNull(catalog.find(chart(index = 0)))
        assertEquals(13.7, chart().levelValue!!, 0.0)
        assertEquals("20260917", catalog.latestSongDate)
    }

    @Test fun neverUsesLevelOrInternationalConstantsForMissingJapaneseValue() {
        val catalog = JapaneseConstantCatalog.parse("""[{"title":"Test","dx_lev_bas":"5","dx_lev_bas_i":"5.0",
            "dx_lev_mas":"14+","dx_lev_mas_i":"","dx_lev_mas_i_intl":"14.9",
            "dx_lev_exp":"12","dx_lev_exp_i":"NaN"}]""")
        assertNull(catalog.find(chart()))
        assertEquals(1, catalog.values.size)
    }

    @Test fun excludesAmbiguousAndRenewedCharts() {
        val catalog = JapaneseConstantCatalog.parse("""[
            {"title":"Test","dx_lev_mas":"14","dx_lev_mas_i":"14.1"},
            {"title":"Test","dx_lev_mas":"14","dx_lev_mas_i":"14.1"},
            {"title":"Other","dx_lev_mas":"14","dx_lev_mas_i":"14.2","dx_lev_mas_notes":"100"}]
        """)
        assertNull(catalog.find(chart()))
        assertNull(catalog.find(chart().copy(title = "Other", notes = ChartNotes(101, null, null, null, null, null))))
        assertEquals(14.2, catalog.find(chart().copy(title = "Other", notes = ChartNotes(100, null, null, null, null, null)))!!, 0.0)
    }

    @Test fun optionalLiveSnapshotParsesAndReportsCoverage() {
        val path = System.getenv("FLUENTMAI_JP_LIVE_FIXTURE")
        assumeTrue(path != null)
        val catalog = JapaneseConstantCatalog.parse(File(path!!).readText())
        assertTrue(catalog.values.size > 6000)
        println("JP live snapshot: ${catalog.values.size} constants; latest song date=${catalog.latestSongDate}")
        val cn = javaClass.getResourceAsStream("/lxns_song_list_fallback.json")!!.bufferedReader().use {
            MaimaiSongCatalog.fromLxnsSongListJson(it.readText()).charts()
        }
        println("CN bundled catalog: ${cn.count { catalog.find(it) != null }}/${cn.size} safely matched")
    }
}

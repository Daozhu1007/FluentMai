package dev.fluentmai.android.feature.scores

import dev.fluentmai.android.core.model.*
import org.junit.Assert.*
import org.junit.Test

class FavoriteAndTheoryTest {
    @Test fun favoriteFilterCyclesAndAppliesBeforeResultLimit() {
        assertEquals(FavoriteFilter.All, FavoriteFilter.All.next().next().next())
        val charts = (1..60).map(B50PosterTest::chart)
        val keys = charts.takeLast(3).map { ChartIdentity.from(it).stableKey() }.toSet()
        val engine = ChartQueryEngine.create(charts, emptyList())
        assertEquals(3, engine.query(ChartQueryFilters(favorite = FavoriteFilter.Favorites), 25500, limit = 1, favorites = keys).matchingCount)
        assertEquals(57, engine.query(ChartQueryFilters(favorite = FavoriteFilter.Unfavorites), 25500, favorites = keys).matchingCount)
        assertEquals(60, engine.query(ChartQueryFilters(), 25500, favorites = keys).matchingCount)
    }
    @Test fun theoryIsUniqueCurrentVersionB35PlusB15AndExcludesDisabledCharts() {
        val charts = (1..60).map(B50PosterTest::chart)
        val versions = listOf(MaimaiMajorVersion(25500, "2026"))
        val expected = theoreticalBestSet(charts, versions)
        assertEquals(35, expected.oldBest.size)
        assertEquals(15, expected.newBest.size)
        assertEquals(expected.rating, theoreticalBestSet(charts + charts, versions).rating)
        assertEquals(50 * calculateDxRating(14.9, 100.5), expected.rating)
        val disabled = charts.map { it.copy(isDisabled = true) }
        assertEquals(0, theoreticalBestSet(disabled, versions).rating)
        assertEquals(0, theoreticalBestSet(emptyList(), versions).rating)
    }
    @Test fun posterDisplayOptionsDoNotRequestHiddenCollections() {
        val best = posterBestSet(emptyList(), emptyList(), emptyList())
        val profile = B50PlayerProfile("Local", 1, 6101, 1, "Trophy", "gold", 23, 25)
        val hidden = B50DisplayOptions(false, false, false, false, false)
        val requested = posterAssets(best, profile, hidden)
        assertEquals(setOf(B50Assets.icon(1)), requested.keys)
        val visible = posterAssets(best, profile, B50DisplayOptions())
        assertEquals("玩家收藏品背景", visible[B50Assets.frame(1)])
        assertFalse(visible.keys.any { "maimaidx.jp" in it || it.startsWith("bundled:") })
    }

    @Test fun theoryIncludesWholeAnnualReleaseAcrossMinorUpdatesAndNextYear() {
        for ((launch, year) in listOf(25500 to 2026, 26000 to 2027)) {
            val charts = (1..60).map { id -> B50PosterTest.chart(id).copy(
                chartVersion = if (id <= 40) launch - 1 else launch + (id % 3) * 100,
                chartVersionName = null,
            ) }
            val versions = listOf(MaimaiMajorVersion(launch, "舞萌DX $year"),
                MaimaiMajorVersion(launch + 200, "舞萌ＤＸ ${year} 第三次更新"))
            val theory = theoreticalBestSet(charts + charts, versions)
            assertEquals(launch, theory.currentVersion?.majorVersion?.id)
            assertEquals(35, theory.oldBest.size)
            assertEquals(15, theory.newBest.size)
            assertTrue(theory.newBest.any { it.chart!!.chartVersion == launch })
            assertTrue(theory.newBest.any { it.chart!!.chartVersion == launch + 200 })
            assertTrue(theory.oldBest.all { it.chart!!.chartVersion < launch })
            assertEquals(50 * calculateDxRating(14.9, 100.5), theory.rating)
        }
    }
}

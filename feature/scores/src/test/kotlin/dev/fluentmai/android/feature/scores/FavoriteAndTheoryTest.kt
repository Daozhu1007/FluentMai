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

    @Test fun theorySelectsHighestConstantsIncludingLockedChartsAt101Percent() {
        val charts = (1..60).map { id -> B50PosterTest.chart(id).copy(levelValue = 10.0 + id / 10.0) }
        val lockedOld = charts.first().copy(levelValue = 15.0, isLocked = true)
        val lockedNew = charts[40].copy(levelValue = 15.0, isLocked = true)
        val available = charts.filter { it.songId !in setOf(1, 41) } + lockedOld + lockedNew
        val disabled = lockedOld.copy(songId = 999, levelValue = 20.0, isDisabled = true)
        val future = lockedNew.copy(songId = 998, chartVersion = 26000, levelValue = 20.0)
        val best = theoreticalBestSet(available + available + disabled + future,
            listOf(MaimaiMajorVersion(25500, "舞萌DX 2026")))
        val expectedOld = available.filter { it.chartVersion < 25500 }.sortedByDescending { it.levelValue }.take(35)
        val expectedNew = available.filter { it.chartVersion == 25500 }.sortedByDescending { it.levelValue }.take(15)
        assertEquals(expectedOld.map { it.levelValue }, best.oldBest.map { it.chart!!.levelValue })
        assertEquals(expectedNew.map { it.levelValue }, best.newBest.map { it.chart!!.levelValue })
        assertTrue(best.oldBest.any { it.chart!!.songId == 1 })
        assertTrue(best.newBest.any { it.chart!!.songId == 41 })
        assertTrue(best.all.all { it.score.achievement == 101.0 && it.score.fc == "app" })
        assertEquals((expectedOld + expectedNew).sumOf { calculateDxRating(it.levelValue!!, 101.0) }, best.rating)
        assertEquals(50, best.all.size)
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

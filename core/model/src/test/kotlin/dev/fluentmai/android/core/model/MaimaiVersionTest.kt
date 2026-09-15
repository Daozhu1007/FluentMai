package dev.fluentmai.android.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MaimaiVersionTest {
    @Test
    fun explicitMajorVersionTableWinsOverFutureContentBatch() {
        val current = resolveCurrentMaimaiVersion(
            majorVersions = listOf(
                MaimaiMajorVersion(25000, "舞萌DX 2025"),
                MaimaiMajorVersion(25500, "舞萌DX 2026"),
            ),
            charts = listOf(chart(chartVersion = 25501, chartVersionName = null)),
        )

        assertEquals(25500, current?.majorVersion?.id)
        assertEquals(MaimaiCurrentVersionSource.CATALOG_VERSION_TABLE, current?.source)
    }

    @Test
    fun namedMetadataIsSafeFallbackButRawMaximumIsNot() {
        val current = resolveCurrentMaimaiVersion(
            majorVersions = emptyList(),
            charts = listOf(
                chart(chartVersion = 25500, chartVersionName = "舞萌DX 2026"),
                chart(chartVersion = 25501, chartVersionName = null),
            ),
        )

        assertEquals(25500, current?.majorVersion?.id)
        assertEquals(MaimaiCurrentVersionSource.NAMED_CHART_METADATA, current?.source)
    }

    @Test
    fun unresolvedMetadataDoesNotGuessFromRawMaximum() {
        val current = resolveCurrentMaimaiVersion(
            majorVersions = emptyList(),
            charts = listOf(chart(chartVersion = 25501, chartVersionName = null)),
        )

        assertNull(current)
    }

    @Test
    fun versionNameNormalizationHandlesWidthCaseAndSpacing() {
        assertTrue(sameMaimaiVersionName("舞萌ＤＸ ２０２６", "舞萌DX2026"))
        assertTrue(sameMaimaiVersionName("MAIMAI DX - 2026", "maimai_dx 2026"))
    }

    @Test
    fun annualBoundarySurvivesFutureYearsAndMinorUpdates() {
        val current = resolveCurrentMaimaiVersion(listOf(
            MaimaiMajorVersion(25500, "舞萌DX 2026"),
            MaimaiMajorVersion(26000, "舞萌DX 2027"),
            MaimaiMajorVersion(26500, "舞萌DX 2027 PLUS"),
        ), emptyList())!!
        assertEquals(26000, current.majorVersion.id)
        assertEquals("舞萌DX 2027", current.majorVersion.name)
        assertEquals(MaimaiRatingBucket.CURRENT, chart(26000, null).ratingBucket(current))
        assertEquals(MaimaiRatingBucket.CURRENT, chart(26501, null).ratingBucket(current))
        assertEquals(MaimaiRatingBucket.OLD, chart(25999, null).ratingBucket(current))
        assertEquals(MaimaiRatingBucket.INELIGIBLE, chart(27000, null).ratingBucket(current))
        assertEquals(MaimaiRatingBucket.INELIGIBLE, chart(26600, "舞萌DX 2028").ratingBucket(current))
    }

    @Test
    fun metadataOnlyMinorUpdatesStillUseKnownAnnualLaunch() {
        val current = resolveCurrentMaimaiVersion(listOf(MaimaiMajorVersion(25507, "舞萌DX 2026 更新")), emptyList())!!
        assertEquals(25500, current.majorVersion.id)
        assertEquals(MaimaiRatingBucket.CURRENT, chart(25500, null).ratingBucket(current))
        assertEquals(MaimaiRatingBucket.CURRENT, chart(25507, null).ratingBucket(current))
        assertEquals(MaimaiRatingBucket.OLD, chart(25008, null).ratingBucket(current))
    }

    private fun chart(
        chartVersion: Int,
        chartVersionName: String?,
    ): ChartRecord =
        ChartRecord(
            songId = chartVersion,
            title = "Version $chartVersion",
            artist = "Artist",
            genre = "maimai",
            bpm = 180,
            songVersion = chartVersion,
            songVersionName = chartVersionName,
            chartVersion = chartVersion,
            chartVersionName = chartVersionName,
            songType = SongType.DX,
            difficulty = Difficulty.MASTER,
            levelIndex = Difficulty.MASTER.levelIndex,
            level = "13",
            levelValue = 13.0,
            noteDesigner = "Designer",
            notes = null,
        )
}

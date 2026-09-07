package dev.fluentmai.android.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MaimaiToolsTest {
    @Test
    fun singleSongRatingUsesVerifiedCapAndRank() {
        val result = calculateSingleSongRating(13.5, 100.6217)

        assertEquals(303, result.rating)
        assertEquals(100.5, result.cappedAchievement, 0.0)
        assertEquals(22.4, result.coefficient, 0.0)
        assertEquals(AchievementRank.SSS_PLUS, result.rank)
    }

    @Test
    fun singleSongRatingKeepsThresholdPrecision() {
        assertEquals(332, calculateSingleSongRating(14.9, 100.4999).rating)
        assertEquals(323, calculateSingleSongRating(14.9, 100.4567).rating)
        assertEquals(318, calculateSingleSongRating(14.9, 99.9999).rating)
    }

    @Test
    fun singleSongRatingRejectsInvalidInputs() {
        assertFailsWith<IllegalArgumentException> { calculateSingleSongRating(0.0, 100.0) }
        assertFailsWith<IllegalArgumentException> { calculateSingleSongRating(13.0, 101.0001) }
        assertFailsWith<IllegalArgumentException> { calculateSingleSongRating(Double.NaN, 100.0) }
    }

    @Test
    fun noteWeightsAndBreakBonusMatchVerifiedFormula() {
        val notes = MaimaiNoteCounts(tap = 100, hold = 10, slide = 10, touch = 10, breakCount = 10)
        val tapGreat = calculateMaimaiAchievement(
            notes,
            MaimaiNoteKind.TAP,
            MaimaiJudgement.GREAT,
            occurrences = 1,
            targetAchievement = 100.5,
        )
        val breakHighPerfect = calculateMaimaiAchievement(
            notes,
            MaimaiNoteKind.BREAK,
            MaimaiJudgement.PERFECT_HIGH,
            occurrences = 1,
            targetAchievement = 100.5,
        )
        val breakLowPerfect = calculateMaimaiAchievement(
            notes,
            MaimaiNoteKind.BREAK,
            MaimaiJudgement.PERFECT,
            occurrences = 1,
            targetAchievement = 100.5,
        )

        assertEquals(210, notes.weightedCount)
        assertEquals(101.0, tapGreat.maximumAchievement, 0.0)
        assertEquals(0.0952380952, tapGreat.lossPerJudgement, 1e-9)
        assertEquals(5, tapGreat.toleratedOccurrences)
        assertEquals(0.025, breakHighPerfect.lossPerJudgement, 1e-9)
        assertEquals(0.05, breakLowPerfect.lossPerJudgement, 1e-9)
    }

    @Test
    fun noteCalculatorRejectsImpossibleCountsAndTargets() {
        assertFailsWith<IllegalArgumentException> {
            MaimaiNoteCounts(0, 0, 0, 0, 0)
        }
        val notes = MaimaiNoteCounts(1, 0, 0, 0, 0)
        assertFailsWith<IllegalArgumentException> {
            calculateMaimaiAchievement(notes, MaimaiNoteKind.TAP, MaimaiJudgement.MISS, 2, 80.0)
        }
    }

    @Test
    fun sssPlusToleranceUsesTapGreatFormulaAndCompleteChartNotes() {
        val chart = chart(
            ChartNotes(total = 180, tap = 100, hold = 10, slide = 10, touch = 10, breakCount = 10),
        )

        assertEquals(5, chart.sssPlusTapGreatTolerance())
        assertEquals(100, chart.toMaimaiNoteCountsOrNull()?.tap)
    }

    @Test
    fun sssPlusToleranceIsUnavailableWithoutCompleteNotesOrBreakBonus() {
        assertEquals(
            null,
            chart(ChartNotes(total = 180, tap = 100, hold = null, slide = 10, touch = 10, breakCount = 10))
                .sssPlusTapGreatTolerance(),
        )
        assertEquals(
            null,
            chart(ChartNotes(total = 170, tap = 100, hold = 10, slide = 10, touch = 10, breakCount = 0))
                .sssPlusTapGreatTolerance(),
        )
    }

    @Test
    fun versionLookupUsesMaintainableBoundaries() {
        assertEquals("MURASAKi PLUS", maimaiVersionNameFor(18500))
        assertEquals("舞萌DX 2025", maimaiVersionNameFor(25007))
        assertEquals(MaimaiGeneration.DELUXE, maimaiVersionReferenceFor(25501)?.generation)
        assertEquals(listOf("雪"), maimaiPlateVersionFor(19500)?.prefixes)
        assertEquals(listOf("輝"), maimaiPlateVersionFor(19900)?.prefixes)
        assertEquals(20000, maimaiPlateVersionFor(19900)?.chartVersionEndExclusive)
    }

    @Test
    fun catalogNamesDoNotDiscardCanonicalPlateSemantics() {
        val references = buildMaimaiVersionReferences(
            listOf(
                MaimaiMajorVersion(19900, "FiNALE"),
                MaimaiMajorVersion(25500, "舞萌DX 2026"),
            ),
        )

        assertEquals("輝", references.single { it.versionId == 19900 }.plate?.prefixes?.single())
        assertEquals("当前曲库批次", references.single { it.versionId == 25500 }.relatedNames.single())
        assertEquals("MiLK PLUS", references.single { it.versionId == 19500 }.officialName)
    }

    private fun chart(notes: ChartNotes) = ChartRecord(
        songId = 1,
        title = "Test",
        artist = "Artist",
        genre = "maimai",
        bpm = 180,
        songVersion = 25_500,
        songVersionName = "舞萌DX 2026",
        chartVersion = 25_500,
        chartVersionName = "舞萌DX 2026",
        songType = SongType.DX,
        difficulty = Difficulty.MASTER,
        levelIndex = Difficulty.MASTER.levelIndex,
        level = "13+",
        levelValue = 13.7,
        noteDesigner = "Designer",
        notes = notes,
    )
}

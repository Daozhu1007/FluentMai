package dev.fluentmai.android.feature.tools

import dev.fluentmai.android.core.model.ChartNotes
import dev.fluentmai.android.core.model.ChartRecord
import dev.fluentmai.android.core.model.Difficulty
import dev.fluentmai.android.core.model.SongType
import dev.fluentmai.android.core.model.toMaimaiNoteCountsOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToolboxNotesTest {
    @Test
    fun chartSelectionAutofillsEveryNoteKind() {
        val counts = chart(
            ChartNotes(total = 1000, tap = 800, hold = 50, slide = 100, touch = 20, breakCount = 30),
        ).toMaimaiNoteCountsOrNull()

        requireNotNull(counts)
        assertEquals(800, counts.tap)
        assertEquals(50, counts.hold)
        assertEquals(100, counts.slide)
        assertEquals(20, counts.touch)
        assertEquals(30, counts.breakCount)
    }

    @Test
    fun chartSelectionRejectsIncompleteBreakdown() {
        assertNull(
            chart(ChartNotes(total = 1000, tap = 800, hold = null, slide = 100, touch = 20, breakCount = 30))
                .toMaimaiNoteCountsOrNull(),
        )
    }

    private fun chart(notes: ChartNotes) = ChartRecord(
        songId = 834,
        title = "PANDORA PARADOXXX",
        artist = "削除",
        genre = "maimai",
        bpm = 150,
        songVersion = 19998,
        songVersionName = "FiNALE",
        chartVersion = 19998,
        chartVersionName = "FiNALE",
        songType = SongType.STANDARD,
        difficulty = Difficulty.MASTER,
        levelIndex = Difficulty.MASTER.levelIndex,
        level = "14+",
        levelValue = 14.9,
        noteDesigner = "PANDORA PARADOXXX",
        notes = notes,
    )
}

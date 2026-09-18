package dev.fluentmai.android.feature.tools

import dev.fluentmai.android.core.model.*
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class PlayActivityHeatmapTest {
    @Test fun groupsActualPlayDatesInChinaAndIgnoresDuplicateIds() {
        fun play(id: String, time: String) = PlayRecord(id, 1, "Song", SongType.DX, Difficulty.MASTER,
            Instant.parse(time).toEpochMilli(), 100.5, null, null, null)
        val first = play("1", "2026-09-15T15:59:00Z")
        val second = play("2", "2026-09-15T16:00:00Z")
        val third = play("3", "2026-09-16T10:00:00Z")
        val counts = playCountsByDay(listOf(first, second, first, third))
        assertEquals(1, counts[LocalDate.parse("2026-09-15")])
        assertEquals(2, counts[LocalDate.parse("2026-09-16")])
        assertNull(counts[LocalDate.parse("2026-09-14")])
    }
}

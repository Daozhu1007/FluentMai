package dev.fluentmai.android.feature.scores

import dev.fluentmai.android.core.model.ChartNotes
import dev.fluentmai.android.core.model.ChartRecord
import dev.fluentmai.android.core.model.Difficulty
import dev.fluentmai.android.core.model.SongAliasCatalog
import dev.fluentmai.android.core.model.SongAliasEntry
import dev.fluentmai.android.core.model.SongType
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import org.junit.Assert.assertEquals
import org.junit.Test

class CrossPlatformSearchFixtureTest {
    @Test
    fun sharedSearchNormalizationFixtureIsConsumedByAndroid() {
        rows("search/normalization.tsv").forEachIndexed { index, row ->
            val corpus = row.getValue("corpus").split('|')
            val songId = index + 1
            val chart = ChartRecord(
                songId = songId,
                title = corpus.first(),
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
                levelValue = 13.5,
                noteDesigner = "Designer",
                notes = ChartNotes(500, 300, 50, 50, 50, 50),
            )
            val aliases = SongAliasCatalog.from(
                listOf(SongAliasEntry(songId, corpus.drop(1))),
            )
            val result = ChartQueryEngine.create(listOf(chart), emptyList(), aliases)
                .query(ChartQueryFilters(searchQuery = row.getValue("query")), 25_500)

            assertEquals(row.getValue("query"), row.getValue("expected_match").toBoolean(), result.matchingCount > 0)
        }
    }

    private fun rows(relative: String): List<Map<String, String>> {
        val lines = Files.readAllLines(fixtureRoot().resolve(relative), Charsets.UTF_8).filter(String::isNotBlank)
        val headers = lines.first().split('\t')
        return lines.drop(1).map { line ->
            val values = line.split('\t', ignoreCase = false, limit = headers.size)
            headers.mapIndexed { index, header -> header to values.getOrElse(index) { "" } }.toMap()
        }
    }

    private fun fixtureRoot(): Path {
        var current: Path? = Path.of("").toAbsolutePath()
        while (current != null) {
            val candidate = current.resolve("test-fixtures")
            if (candidate.isDirectory()) return candidate
            current = current.parent
        }
        error("test-fixtures not found")
    }
}

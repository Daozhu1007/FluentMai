package dev.fluentmai.android.feature.scores

import androidx.lifecycle.SavedStateHandle
import dev.fluentmai.android.core.model.AchievementRank
import dev.fluentmai.android.core.model.Difficulty
import dev.fluentmai.android.core.model.FullComboStatus
import dev.fluentmai.android.core.model.FullSyncStatus
import dev.fluentmai.android.core.model.SongType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChartQueryViewModelTest {
    @Test fun multipleDifficultiesToggleResetAndRestore() {
        val handle = SavedStateHandle(mapOf("charts.difficulty" to "EXPERT", "charts.version" to "Current"))
        val model = ChartQueryViewModel(handle)
        assertEquals(setOf(Difficulty.EXPERT), model.uiState.value.filters.difficulties)
        assertEquals(ChartVersionFilter.All, model.uiState.value.filters.version)
        model.updateDifficulty(Difficulty.MASTER)
        assertEquals(setOf(Difficulty.EXPERT, Difficulty.MASTER), ChartQueryViewModel(handle).uiState.value.filters.difficulties)
        model.updateDifficulty(Difficulty.EXPERT)
        assertEquals(setOf(Difficulty.MASTER), model.uiState.value.filters.difficulties)
        model.updateDifficulty(null)
        assertEquals(emptySet<Difficulty>(), ChartQueryViewModel(handle).uiState.value.filters.difficulties)
        Difficulty.entries.forEach(model::updateDifficulty)
        assertEquals(emptySet<Difficulty>(), model.uiState.value.filters.difficulties)
        model.updateDifficulty(Difficulty.BASIC)
        model.updateDifficulty(Difficulty.BASIC)
        assertEquals(emptySet<Difficulty>(), model.uiState.value.filters.difficulties)
    }

    @Test
    fun filtersSortAndScrollRestoreFromSavedState() {
        val handle = SavedStateHandle()
        val original = ChartQueryViewModel(handle)

        original.updateSearchQuery("PANDORA")
        original.updateLevelQuery("13+")
        original.updateConstantRange(13.2, 14.7)
        original.updateDifficulty(Difficulty.MASTER)
        original.updateGenre(ChartGenreFilter.Maimai)
        original.updateVersion(ChartVersionFilter.Dx2026)
        original.updateStatus(ChartStatusFilter.Played)
        original.updateSongType(SongType.DX)
        original.updateAchievementRange(99.5, 100.5)
        original.updateRank(AchievementRank.SSS)
        original.updateFullCombo(FullComboStatus.AP)
        original.updateFullSync(FullSyncStatus.FSD)
        original.updateSort(ChartSort.AchievementDesc)
        original.saveScroll(index = 17, offset = 96)

        val restored = ChartQueryViewModel(handle)
        assertEquals("PANDORA", restored.uiState.value.filters.searchQuery)
        assertEquals("", restored.uiState.value.filters.levelQuery)
        assertEquals(13.2, restored.uiState.value.filters.constantMin)
        assertEquals(14.7, restored.uiState.value.filters.constantMax)
        assertEquals(setOf(Difficulty.MASTER), restored.uiState.value.filters.difficulties)
        assertEquals(ChartGenreFilter.Maimai, restored.uiState.value.filters.genre)
        assertEquals(ChartVersionFilter.Dx2026, restored.uiState.value.filters.version)
        assertEquals(ChartStatusFilter.Played, restored.uiState.value.filters.status)
        assertEquals(SongType.DX, restored.uiState.value.filters.songType)
        assertEquals(99.5, restored.uiState.value.filters.achievementMin)
        assertEquals(100.5, restored.uiState.value.filters.achievementMax)
        assertEquals(AchievementRank.SSS, restored.uiState.value.filters.rank)
        assertEquals(FullComboStatus.AP, restored.uiState.value.filters.fullCombo)
        assertEquals(FullSyncStatus.FSD, restored.uiState.value.filters.fullSync)
        assertEquals(ChartSort.AchievementDesc, restored.uiState.value.filters.sort)
        assertEquals(17, restored.restoredScrollIndex)
        assertEquals(96, restored.restoredScrollOffset)
    }

    @Test
    fun playedPresetIsTemporaryAndResetCanDismissIt() {
        val viewModel = ChartQueryViewModel(SavedStateHandle())
        viewModel.updateSearchQuery("PANDORA")

        viewModel.enterPlayedPreset()
        assertEquals("", viewModel.uiState.value.filters.searchQuery)
        assertEquals(ChartStatusFilter.Played, viewModel.uiState.value.filters.status)

        viewModel.exitPreset()
        assertEquals("PANDORA", viewModel.uiState.value.filters.searchQuery)
        assertEquals(ChartStatusFilter.All, viewModel.uiState.value.filters.status)

        viewModel.enterPlayedPreset()
        viewModel.resetFilters()
        viewModel.exitPreset()
        assertEquals(ChartQueryFilters(), viewModel.uiState.value.filters)
    }

    @Test
    fun exactAndRangeConstantModesReplaceEachOtherAndRejectInvalidRanges() {
        val viewModel = ChartQueryViewModel(SavedStateHandle())
        viewModel.updateConstantRange(13.0, 13.7)
        assertEquals("", viewModel.uiState.value.filters.levelQuery)
        assertEquals(13.0, viewModel.uiState.value.filters.constantMin)

        viewModel.updateLevelQuery("13+")
        assertEquals("13+", viewModel.uiState.value.filters.levelQuery)
        assertNull(viewModel.uiState.value.filters.constantMin)
        assertNull(viewModel.uiState.value.filters.constantMax)

        viewModel.updateConstantRange(14.0, 13.0)
        assertEquals("13+", viewModel.uiState.value.filters.levelQuery)
        viewModel.updateConstantRange(0.9, 13.0)
        assertEquals("13+", viewModel.uiState.value.filters.levelQuery)
    }

    @Test
    fun invalidSavedEnumValuesFailClosedToDefaults() {
        val restored = ChartQueryViewModel(
            SavedStateHandle(
                mapOf(
                    "charts.difficulty" to "UNKNOWN",
                    "charts.genre" to "UNKNOWN",
                    "charts.version" to "UNKNOWN",
                    "charts.status" to "UNKNOWN",
                    "charts.sort" to "UNKNOWN",
                ),
            ),
        )

        assertEquals(emptySet<Difficulty>(), restored.uiState.value.filters.difficulties)
        assertEquals(ChartGenreFilter.All, restored.uiState.value.filters.genre)
        assertEquals(ChartVersionFilter.All, restored.uiState.value.filters.version)
        assertEquals(ChartStatusFilter.All, restored.uiState.value.filters.status)
        assertEquals(ChartSort.ConstantDesc, restored.uiState.value.filters.sort)
    }
}

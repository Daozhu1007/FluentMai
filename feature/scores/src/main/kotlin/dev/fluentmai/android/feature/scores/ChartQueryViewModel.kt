package dev.fluentmai.android.feature.scores

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.fluentmai.android.core.model.AchievementRank
import dev.fluentmai.android.core.model.ChartRecord
import dev.fluentmai.android.core.model.Difficulty
import dev.fluentmai.android.core.model.FullComboStatus
import dev.fluentmai.android.core.model.FullSyncStatus
import dev.fluentmai.android.core.model.ScoreRecord
import dev.fluentmai.android.core.model.SongAliasCatalog
import dev.fluentmai.android.core.model.SongType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class ChartQueryUiState(
    val filters: ChartQueryFilters,
    val result: ChartQueryResult = ChartQueryResult(),
    val isIndexing: Boolean = false,
    val isFiltering: Boolean = false,
)

internal class ChartQueryViewModel(
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        ChartQueryUiState(filters = restoreFilters(savedStateHandle)),
    )
    val uiState: StateFlow<ChartQueryUiState> = _uiState.asStateFlow()

    val restoredScrollIndex: Int
        get() = savedStateHandle[KEY_SCROLL_INDEX] ?: 0
    val restoredScrollOffset: Int
        get() = savedStateHandle[KEY_SCROLL_OFFSET] ?: 0

    private var favorites: Set<String> = emptySet()
    fun updateFavorites(value: Set<String>) {
        if (favorites == value) return
        favorites = value.toSet()
        scheduleQuery(0)
    }
    fun cycleFavoriteFilter() = updateFilters { it.copy(favorite = it.favorite.next()) }

    private var engine: ChartQueryEngine? = null
    private var indexedCharts: List<ChartRecord>? = null
    private var indexedScores: List<ScoreRecord>? = null
    private var indexedCurrentVersion: Int = 0
    private var indexedAliases: SongAliasCatalog? = null
    private var inputGeneration: Long = 0
    private var indexJob: Job? = null
    private var queryJob: Job? = null
    private var filtersBeforePreset: ChartQueryFilters? = null

    fun submitCatalog(
        charts: List<ChartRecord>,
        scores: List<ScoreRecord>,
        currentVersion: Int,
        aliases: SongAliasCatalog = SongAliasCatalog.Empty,
    ) {
        if (
            charts == indexedCharts &&
            scores == indexedScores &&
            currentVersion == indexedCurrentVersion &&
            aliases.entries == indexedAliases?.entries
        ) return
        indexedCharts = charts
        indexedScores = scores
        indexedCurrentVersion = currentVersion
        indexedAliases = aliases
        val generation = ++inputGeneration
        indexJob?.cancel()
        queryJob?.cancel()
        indexJob = viewModelScope.launch {
            _uiState.update { it.copy(isIndexing = true, isFiltering = false) }
            delay(INDEX_INPUT_DEBOUNCE_MILLIS)
            if (generation != inputGeneration) return@launch
            if (charts.isEmpty()) {
                engine = null
                _uiState.update {
                    it.copy(
                        result = ChartQueryResult(),
                        isIndexing = false,
                        isFiltering = false,
                    )
                }
                return@launch
            }
            val startedAt = SystemClock.elapsedRealtime()
            val built = withContext(Dispatchers.Default) {
                ChartQueryEngine.create(charts, scores, aliases)
            }
            if (generation != inputGeneration) return@launch
            engine = built
            Log.i(
                TAG,
                "Chart index ready in ${SystemClock.elapsedRealtime() - startedAt}ms: " +
                    "chartCount=${charts.size} scoreCount=${scores.size} " +
                    "stages=${built.buildTimings}",
            )
            _uiState.update { it.copy(isIndexing = false) }
            scheduleQuery(debounceMillis = 0L)
        }
    }

    fun updateSearchQuery(value: String) =
        updateFilters(debounceMillis = SEARCH_DEBOUNCE_MILLIS) { it.copy(searchQuery = value) }

    fun updateLevelQuery(value: String) =
        updateFilters(debounceMillis = SEARCH_DEBOUNCE_MILLIS) {
            val useExactLevel = value.isNotBlank() && isValidLevelQuery(value)
            it.copy(
                levelQuery = value,
                constantMin = if (useExactLevel) null else it.constantMin,
                constantMax = if (useExactLevel) null else it.constantMax,
            )
        }

    fun updateConstantRange(minimum: Double?, maximum: Double?) {
        if (minimum != null && (!minimum.isFinite() || minimum !in 1.0..15.0)) return
        if (maximum != null && (!maximum.isFinite() || maximum !in 1.0..15.0)) return
        if (minimum != null && maximum != null && minimum > maximum) return
        updateFilters(debounceMillis = SEARCH_DEBOUNCE_MILLIS) {
            it.copy(
                levelQuery = if (minimum != null || maximum != null) "" else it.levelQuery,
                constantMin = minimum,
                constantMax = maximum,
            )
        }
    }

    fun updateDifficulty(value: Difficulty?) =
        updateFilters { it.copy(difficulty = value) }

    fun updateGenre(value: ChartGenreFilter) =
        updateFilters { it.copy(genre = value) }

    fun updateVersion(value: ChartVersionFilter) =
        updateFilters { it.copy(version = value) }

    fun updateStatus(value: ChartStatusFilter) =
        updateFilters { it.copy(status = value) }

    fun updateSongType(value: SongType?) =
        updateFilters { it.copy(songType = value) }

    fun updateAchievementRange(minimum: Double?, maximum: Double?) {
        if (minimum != null && (!minimum.isFinite() || minimum !in 0.0..101.0)) return
        if (maximum != null && (!maximum.isFinite() || maximum !in 0.0..101.0)) return
        if (minimum != null && maximum != null && minimum > maximum) return
        updateFilters(debounceMillis = SEARCH_DEBOUNCE_MILLIS) {
            it.copy(achievementMin = minimum, achievementMax = maximum)
        }
    }

    fun updateRank(value: AchievementRank?) =
        updateFilters { it.copy(rank = value) }

    fun updateFullCombo(value: FullComboStatus?) =
        updateFilters { it.copy(fullCombo = value) }

    fun updateFullSync(value: FullSyncStatus?) =
        updateFilters { it.copy(fullSync = value) }

    fun updateSort(value: ChartSort) =
        updateFilters { it.copy(sort = value) }

    fun resetFilters() {
        filtersBeforePreset = null
        replaceFilters(ChartQueryFilters())
    }

    fun enterPlayedPreset() {
        if (filtersBeforePreset != null) return
        filtersBeforePreset = _uiState.value.filters
        replaceFilters(ChartQueryFilters(status = ChartStatusFilter.Played))
    }

    fun exitPreset() {
        val restored = filtersBeforePreset ?: return
        filtersBeforePreset = null
        replaceFilters(restored)
    }

    fun saveScroll(index: Int, offset: Int) {
        savedStateHandle[KEY_SCROLL_INDEX] = index.coerceAtLeast(0)
        savedStateHandle[KEY_SCROLL_OFFSET] = offset.coerceAtLeast(0)
    }

    private fun updateFilters(
        debounceMillis: Long = 0L,
        transform: (ChartQueryFilters) -> ChartQueryFilters,
    ) {
        val filters = transform(_uiState.value.filters)
        if (filters == _uiState.value.filters) return
        replaceFilters(filters, debounceMillis)
    }

    private fun replaceFilters(filters: ChartQueryFilters, debounceMillis: Long = 0L) {
        if (filters == _uiState.value.filters) return
        persistFilters(filters)
        _uiState.update { it.copy(filters = filters) }
        scheduleQuery(debounceMillis)
    }

    private fun scheduleQuery(debounceMillis: Long) {
        val queryEngine = engine ?: return
        val filters = _uiState.value.filters
        if (!isValidLevelQuery(filters.levelQuery)) {
            queryJob?.cancel()
            _uiState.update { it.copy(isFiltering = false) }
            return
        }
        val currentVersion = indexedCurrentVersion
        val favoriteSnapshot = favorites
        queryJob?.cancel()
        _uiState.update { it.copy(isFiltering = true) }
        queryJob = viewModelScope.launch {
            if (debounceMillis > 0L) delay(debounceMillis)
            val startedAt = SystemClock.elapsedRealtime()
            val result = withContext(Dispatchers.Default) {
                queryEngine.query(filters, currentVersion, favorites = favoriteSnapshot)
            }
            if (engine !== queryEngine || _uiState.value.filters != filters || favorites != favoriteSnapshot) return@launch
            Log.i(
                TAG,
                "Chart query ready in ${SystemClock.elapsedRealtime() - startedAt}ms: " +
                    "matches=${result.matchingCount} visible=${result.items.size}",
            )
            _uiState.update { it.copy(result = result, isFiltering = false) }
        }
    }

    private fun persistFilters(filters: ChartQueryFilters) {
        savedStateHandle[KEY_SEARCH] = filters.searchQuery
        savedStateHandle[KEY_LEVEL] = filters.levelQuery
        savedStateHandle[KEY_CONSTANT_MIN] = filters.constantMin
        savedStateHandle[KEY_CONSTANT_MAX] = filters.constantMax
        savedStateHandle[KEY_DIFFICULTY] = filters.difficulty?.name
        savedStateHandle[KEY_GENRE] = filters.genre.name
        savedStateHandle[KEY_VERSION] = filters.version.name
        savedStateHandle[KEY_STATUS] = filters.status.name
        savedStateHandle[KEY_SONG_TYPE] = filters.songType?.name
        savedStateHandle[KEY_ACHIEVEMENT_MIN] = filters.achievementMin
        savedStateHandle[KEY_ACHIEVEMENT_MAX] = filters.achievementMax
        savedStateHandle[KEY_RANK] = filters.rank?.name
        savedStateHandle[KEY_FULL_COMBO] = filters.fullCombo?.name
        savedStateHandle[KEY_FULL_SYNC] = filters.fullSync?.name
        savedStateHandle[KEY_SORT] = filters.sort.name
        savedStateHandle["charts.favorite"] = filters.favorite.name
    }

    private companion object {
        private const val TAG = "FluentMaiCharts"
        private const val INDEX_INPUT_DEBOUNCE_MILLIS = 80L
        private const val SEARCH_DEBOUNCE_MILLIS = 180L
        private const val KEY_SEARCH = "charts.search"
        private const val KEY_LEVEL = "charts.level"
        private const val KEY_CONSTANT_MIN = "charts.constant.min"
        private const val KEY_CONSTANT_MAX = "charts.constant.max"
        private const val KEY_DIFFICULTY = "charts.difficulty"
        private const val KEY_GENRE = "charts.genre"
        private const val KEY_VERSION = "charts.version"
        private const val KEY_STATUS = "charts.status"
        private const val KEY_SONG_TYPE = "charts.song.type"
        private const val KEY_ACHIEVEMENT_MIN = "charts.achievement.min"
        private const val KEY_ACHIEVEMENT_MAX = "charts.achievement.max"
        private const val KEY_RANK = "charts.rank"
        private const val KEY_FULL_COMBO = "charts.full.combo"
        private const val KEY_FULL_SYNC = "charts.full.sync"
        private const val KEY_SORT = "charts.sort"
        private const val KEY_SCROLL_INDEX = "charts.scroll.index"
        private const val KEY_SCROLL_OFFSET = "charts.scroll.offset"

        private fun restoreFilters(handle: SavedStateHandle): ChartQueryFilters =
            ChartQueryFilters(
                searchQuery = handle[KEY_SEARCH] ?: "",
                levelQuery = handle[KEY_LEVEL] ?: "",
                constantMin = handle[KEY_CONSTANT_MIN],
                constantMax = handle[KEY_CONSTANT_MAX],
                difficulty = enumValueOrNull<Difficulty>(handle[KEY_DIFFICULTY]),
                genre = enumValueOrDefault(handle[KEY_GENRE], ChartGenreFilter.All),
                version = enumValueOrDefault(handle[KEY_VERSION], ChartVersionFilter.All),
                status = enumValueOrDefault(handle[KEY_STATUS], ChartStatusFilter.All),
                songType = enumValueOrNull<SongType>(handle[KEY_SONG_TYPE]),
                achievementMin = handle[KEY_ACHIEVEMENT_MIN],
                achievementMax = handle[KEY_ACHIEVEMENT_MAX],
                rank = enumValueOrNull<AchievementRank>(handle[KEY_RANK]),
                fullCombo = enumValueOrNull<FullComboStatus>(handle[KEY_FULL_COMBO]),
                fullSync = enumValueOrNull<FullSyncStatus>(handle[KEY_FULL_SYNC]),
                sort = enumValueOrDefault(handle[KEY_SORT], ChartSort.ConstantDesc),
                favorite = enumValueOrDefault(handle["charts.favorite"], FavoriteFilter.All),
            )

        private inline fun <reified T : Enum<T>> enumValueOrNull(name: String?): T? =
            name?.let { value -> enumValues<T>().firstOrNull { it.name == value } }

        private inline fun <reified T : Enum<T>> enumValueOrDefault(name: String?, default: T): T =
            enumValueOrNull<T>(name) ?: default
    }
}

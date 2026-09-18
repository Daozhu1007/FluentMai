package dev.fluentmai.android.feature.scores

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.fluentmai.android.core.model.ChartIdentity
import dev.fluentmai.android.core.model.ChartRecord
import dev.fluentmai.android.core.model.Difficulty
import dev.fluentmai.android.core.model.MaimaiCurrentVersion
import dev.fluentmai.android.core.model.MaimaiMajorVersion
import dev.fluentmai.android.core.model.PlateKind
import dev.fluentmai.android.core.model.PlateProgress
import dev.fluentmai.android.core.model.PlayerRecordCatalog
import dev.fluentmai.android.core.model.RatingRecommendationFilters
import dev.fluentmai.android.core.model.RatingRecommendationResult
import dev.fluentmai.android.core.model.ScoreRecord
import dev.fluentmai.android.core.model.VersionAgeFilter
import dev.fluentmai.android.core.model.buildPlayerRecordCatalog
import dev.fluentmai.android.core.model.buildRatingRecommendations
import dev.fluentmai.android.core.model.calculatePlateProgress
import dev.fluentmai.android.core.model.maimaiPlateVersionFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class PlateListSort { LEVEL_DESC, TITLE_ASC, SONG_ID_ASC }

internal data class PlayerRecordsUiState(
    val availableVersions: List<MaimaiMajorVersion> = emptyList(),
    val selectedPlate: PlateKind = PlateKind.GENERAL,
    val selectedPlateVersionId: Int? = null,
    val selectedPlateDifficulty: Difficulty? = null,
    val plateIncompleteOnly: Boolean = true,
    val plateSort: PlateListSort = PlateListSort.LEVEL_DESC,
    val plateProgress: PlateProgress? = null,
    val recommendationFilters: RatingRecommendationFilters = RatingRecommendationFilters(),
    val recommendationTargetTotalText: String = "",
    val recommendationTargetAchievementText: String = "",
    val recommendationConstantMinText: String = "",
    val recommendationConstantMaxText: String = "",
    val recommendationResult: RatingRecommendationResult? = null,
    val recommendationInputError: String? = null,
    val isRecommendationWorking: Boolean = false,
    val isWorking: Boolean = false,
)

internal class PlayerRecordsViewModel(
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val _uiState = MutableStateFlow(restoreState(savedStateHandle))
    val uiState: StateFlow<PlayerRecordsUiState> = _uiState.asStateFlow()

    private var catalog: PlayerRecordCatalog? = null
    private var currentVersion: MaimaiCurrentVersion? = null
    private var indexedCharts: List<ChartRecord>? = null
    private var indexedScores: List<ScoreRecord>? = null
    private var indexedMajorVersions: List<MaimaiMajorVersion>? = null
    private var plateVersions: List<MaimaiMajorVersion> = emptyList()
    private var generation = 0L
    private var indexJob: Job? = null
    private var recommendationJob: Job? = null
    private var recommendationGeneration = 0L

    fun submitCatalog(
        charts: List<ChartRecord>,
        scores: List<ScoreRecord>,
        majorVersions: List<MaimaiMajorVersion>,
        operatingVersion: MaimaiCurrentVersion?,
    ) {
        if (
            charts === indexedCharts &&
            scores === indexedScores &&
            majorVersions === indexedMajorVersions &&
            operatingVersion == currentVersion
        ) return
        indexedCharts = charts
        indexedScores = scores
        indexedMajorVersions = majorVersions
        currentVersion = operatingVersion
        val operatingVersionId = operatingVersion?.majorVersion?.id
        val requestGeneration = ++generation
        indexJob?.cancel()
        recommendationJob?.cancel()
        indexJob = viewModelScope.launch {
            _uiState.update { it.copy(isWorking = true) }
            val result = withContext(Dispatchers.Default) {
                val builtCatalog = buildPlayerRecordCatalog(charts, scores)
                val versions = (
                    majorVersions + charts.mapNotNull { chart ->
                        chart.chartVersionName
                            ?.takeIf { chart.chartVersion > 0 && it.isNotBlank() }
                            ?.let { MaimaiMajorVersion(chart.chartVersion, it.trim()) }
                    }
                    ).distinctBy { it.id }.sortedByDescending { it.id }
                builtCatalog to versions
            }
            if (requestGeneration != generation) return@launch
            catalog = result.first
            plateVersions = result.second
            val availableVersions = plateVersionsFor(_uiState.value.selectedPlate)
            val requestedVersion = _uiState.value.selectedPlateVersionId ?: operatingVersionId
            val selectedVersion = requestedVersion
                ?.takeIf { requested -> availableVersions.any { it.id == requested } }
                ?: availableVersions.firstOrNull()?.id
            _uiState.update {
                it.copy(
                    availableVersions = availableVersions,
                    selectedPlateVersionId = selectedVersion,
                    isWorking = false,
                )
            }
            persist(KEY_PLATE_VERSION, selectedVersion)
            updatePlateProgress()
            runRecommendations()
        }
    }

    fun updatePlateKind(value: PlateKind) {
        persist(KEY_PLATE_KIND, value.name)
        val versions = plateVersionsFor(value)
        val selectedVersion = _uiState.value.selectedPlateVersionId
            ?.takeIf { selected -> versions.any { it.id == selected } }
            ?: versions.firstOrNull()?.id
        persist(KEY_PLATE_VERSION, selectedVersion)
        _uiState.update {
            it.copy(
                selectedPlate = value,
                availableVersions = versions,
                selectedPlateVersionId = selectedVersion,
            )
        }
        updatePlateProgress()
    }

    fun updatePlateVersion(value: Int) {
        persist(KEY_PLATE_VERSION, value)
        _uiState.update { it.copy(selectedPlateVersionId = value) }
        updatePlateProgress()
    }

    fun updatePlateDifficulty(value: Difficulty?) {
        persist(KEY_PLATE_DIFFICULTY, value?.name)
        _uiState.update { it.copy(selectedPlateDifficulty = value) }
    }

    fun updatePlateIncompleteOnly(value: Boolean) {
        persist(KEY_PLATE_INCOMPLETE, value)
        _uiState.update { it.copy(plateIncompleteOnly = value) }
    }

    fun updatePlateSort(value: PlateListSort) {
        persist(KEY_PLATE_SORT, value.name)
        _uiState.update { it.copy(plateSort = value) }
    }

    fun updateRecommendationTargetTotal(value: String) {
        persist(KEY_RECOMMENDATION_TARGET_TOTAL, value)
        _uiState.update { it.copy(recommendationTargetTotalText = value) }
        runRecommendations(180L)
    }

    fun updateRecommendationTargetAchievement(value: String) {
        persist(KEY_RECOMMENDATION_TARGET_ACHIEVEMENT, value)
        _uiState.update { it.copy(recommendationTargetAchievementText = value) }
        runRecommendations(180L)
    }

    fun updateRecommendationConstantMin(value: String) {
        persist(KEY_RECOMMENDATION_CONSTANT_MIN, value)
        _uiState.update { it.copy(recommendationConstantMinText = value) }
        runRecommendations(180L)
    }

    fun updateRecommendationConstantMax(value: String) {
        persist(KEY_RECOMMENDATION_CONSTANT_MAX, value)
        _uiState.update { it.copy(recommendationConstantMaxText = value) }
        runRecommendations(180L)
    }

    fun updateRecommendationVersionAge(value: VersionAgeFilter) = updateRecommendationFilters {
        it.copy(versionAge = value)
    }

    fun updateRecommendationExcludeSssPlus(value: Boolean) = updateRecommendationFilters {
        it.copy(excludeSssPlus = value)
    }

    fun updateRecommendationOnlyB50Gain(value: Boolean) = updateRecommendationFilters {
        it.copy(onlyB50Gain = value)
    }

    fun excludeRecommendation(identity: ChartIdentity) = updateRecommendationFilters { filters ->
        filters.copy(excludedIdentities = filters.excludedIdentities + identity)
    }

    fun clearRecommendationExclusions() = updateRecommendationFilters {
        it.copy(excludedIdentities = emptySet())
    }

    fun resetRecommendationFilters() {
        val reset = RatingRecommendationFilters()
        persist(KEY_RECOMMENDATION_TARGET_TOTAL, "")
        persist(KEY_RECOMMENDATION_TARGET_ACHIEVEMENT, "")
        persist(KEY_RECOMMENDATION_CONSTANT_MIN, "")
        persist(KEY_RECOMMENDATION_CONSTANT_MAX, "")
        persistRecommendationFilters(reset)
        _uiState.update {
            it.copy(
                recommendationFilters = reset,
                recommendationTargetTotalText = "",
                recommendationTargetAchievementText = "",
                recommendationConstantMinText = "",
                recommendationConstantMaxText = "",
                recommendationInputError = null,
            )
        }
        runRecommendations()
    }

    fun resetSettings() {
        updatePlateKind(PlateKind.GENERAL)
        val versions = _uiState.value.availableVersions
        val selected = currentVersion?.majorVersion?.id?.takeIf { id -> versions.any { it.id == id } } ?: versions.firstOrNull()?.id
        persist(KEY_PLATE_VERSION, selected)
        _uiState.update { it.copy(selectedPlateVersionId = selected) }
        updatePlateProgress()
        updatePlateDifficulty(null)
        updatePlateIncompleteOnly(true)
        updatePlateSort(PlateListSort.LEVEL_DESC)
        resetRecommendationFilters()
    }

    private fun updatePlateProgress() {
        val playerCatalog = catalog ?: return
        val state = _uiState.value
        val versionId = state.selectedPlateVersionId
        val versionName = state.availableVersions.firstOrNull { it.id == versionId }?.name
        val progress = calculatePlateProgress(playerCatalog.records, state.selectedPlate, versionId, versionName)
        _uiState.update { it.copy(plateProgress = progress) }
    }

    private fun plateVersionsFor(kind: PlateKind): List<MaimaiMajorVersion> =
        plateVersions.filter { version ->
            kind == PlateKind.CONQUEROR || maimaiPlateVersionFor(version.id)?.supports(kind) == true
        }

    private fun updateRecommendationFilters(
        transform: (RatingRecommendationFilters) -> RatingRecommendationFilters,
    ) {
        val next = transform(_uiState.value.recommendationFilters)
        if (next == _uiState.value.recommendationFilters) return
        persistRecommendationFilters(next)
        _uiState.update { it.copy(recommendationFilters = next) }
        runRecommendations()
    }

    private fun runRecommendations(debounceMillis: Long = 0) {
        val playerCatalog = catalog ?: return
        val operatingVersion = currentVersion
        val requestGeneration = ++recommendationGeneration
        recommendationJob?.cancel()
        recommendationJob = viewModelScope.launch {
            _uiState.update { it.copy(isRecommendationWorking = true, recommendationInputError = null) }
            if (debounceMillis > 0) delay(debounceMillis)
            val parsed = parseRecommendationFilters(_uiState.value)
            if (parsed.error != null) {
                if (requestGeneration == recommendationGeneration) {
                    _uiState.update {
                        it.copy(
                            recommendationResult = null,
                            recommendationInputError = parsed.error,
                            isRecommendationWorking = false,
                        )
                    }
                }
                return@launch
            }
            val startedAt = SystemClock.elapsedRealtime()
            val result = withContext(Dispatchers.Default) {
                buildRatingRecommendations(
                    records = playerCatalog.records,
                    currentVersion = operatingVersion,
                    filters = requireNotNull(parsed.filters),
                )
            }
            if (requestGeneration != recommendationGeneration || catalog !== playerCatalog) return@launch
            Log.i(
                TAG,
                "Rating recommendations ready in ${SystemClock.elapsedRealtime() - startedAt}ms: " +
                    "eligible=${result.eligiblePlayedCharts} results=${result.recommendations.size} " +
                    "availability=${result.availability}",
            )
            _uiState.update {
                it.copy(
                    recommendationFilters = requireNotNull(parsed.filters),
                    recommendationResult = result,
                    recommendationInputError = null,
                    isRecommendationWorking = false,
                )
            }
        }
    }

    private fun persistRecommendationFilters(filters: RatingRecommendationFilters) {
        persist(KEY_RECOMMENDATION_VERSION_AGE, filters.versionAge.name)
        persist(KEY_RECOMMENDATION_EXCLUDE_SSS_PLUS, filters.excludeSssPlus)
        persist(KEY_RECOMMENDATION_ONLY_B50, filters.onlyB50Gain)
        persist(
            KEY_RECOMMENDATION_EXCLUDED,
            ArrayList(filters.excludedIdentities.map(ChartIdentity::stableKey).sorted()),
        )
    }

    private fun <T> persist(key: String, value: T?) {
        savedStateHandle[key] = value
    }

    private companion object {
        private const val TAG = "PlayerRecords"
        private const val KEY_PLATE_KIND = "plates.kind"
        private const val KEY_PLATE_VERSION = "plates.version"
        private const val KEY_PLATE_DIFFICULTY = "plates.difficulty"
        private const val KEY_PLATE_INCOMPLETE = "plates.incomplete"
        private const val KEY_PLATE_SORT = "plates.sort"
        private const val KEY_RECOMMENDATION_TARGET_TOTAL = "recommendations.target.total"
        private const val KEY_RECOMMENDATION_TARGET_ACHIEVEMENT = "recommendations.target.achievement"
        private const val KEY_RECOMMENDATION_CONSTANT_MIN = "recommendations.constant.min"
        private const val KEY_RECOMMENDATION_CONSTANT_MAX = "recommendations.constant.max"
        private const val KEY_RECOMMENDATION_VERSION_AGE = "recommendations.version.age"
        private const val KEY_RECOMMENDATION_EXCLUDE_SSS_PLUS = "recommendations.exclude.sssplus"
        private const val KEY_RECOMMENDATION_ONLY_B50 = "recommendations.only.b50"
        private const val KEY_RECOMMENDATION_EXCLUDED = "recommendations.excluded"

        private fun restoreState(handle: SavedStateHandle): PlayerRecordsUiState =
            PlayerRecordsUiState(
                selectedPlate = enumOrDefault<PlateKind>(handle[KEY_PLATE_KIND], PlateKind.GENERAL),
                selectedPlateVersionId = handle[KEY_PLATE_VERSION],
                selectedPlateDifficulty = enumOrNull<Difficulty>(handle[KEY_PLATE_DIFFICULTY]),
                plateIncompleteOnly = handle[KEY_PLATE_INCOMPLETE] ?: true,
                plateSort = enumOrDefault<PlateListSort>(handle[KEY_PLATE_SORT], PlateListSort.LEVEL_DESC),
                recommendationFilters = RatingRecommendationFilters(
                    versionAge = enumOrDefault<VersionAgeFilter>(
                        handle[KEY_RECOMMENDATION_VERSION_AGE],
                        VersionAgeFilter.ALL,
                    ),
                    excludeSssPlus = handle[KEY_RECOMMENDATION_EXCLUDE_SSS_PLUS] ?: true,
                    excludedIdentities = (handle.get<ArrayList<String>>(KEY_RECOMMENDATION_EXCLUDED) ?: arrayListOf())
                        .mapNotNull(ChartIdentity::parseStableKey)
                        .toSet(),
                    onlyB50Gain = handle[KEY_RECOMMENDATION_ONLY_B50] ?: true,
                ),
                recommendationTargetTotalText = handle[KEY_RECOMMENDATION_TARGET_TOTAL] ?: "",
                recommendationTargetAchievementText = handle[KEY_RECOMMENDATION_TARGET_ACHIEVEMENT] ?: "",
                recommendationConstantMinText = handle[KEY_RECOMMENDATION_CONSTANT_MIN] ?: "",
                recommendationConstantMaxText = handle[KEY_RECOMMENDATION_CONSTANT_MAX] ?: "",
            )

        private inline fun <reified T : Enum<T>> enumOrNull(name: String?): T? =
            name?.let { candidate -> enumValues<T>().firstOrNull { it.name == candidate } }

        private inline fun <reified T : Enum<T>> enumOrDefault(name: String?, default: T): T =
            enumOrNull<T>(name) ?: default
    }
}

private data class ParsedRecommendationFilters(
    val filters: RatingRecommendationFilters? = null,
    val error: String? = null,
)

private fun parseRecommendationFilters(state: PlayerRecordsUiState): ParsedRecommendationFilters {
    val targetTotal = state.recommendationTargetTotalText.trim().let { value ->
        if (value.isEmpty()) null else value.toIntOrNull()?.takeIf { it in 0..30_000 }
            ?: return ParsedRecommendationFilters(error = "目标总 Rating 必须是 0 到 30000 的整数")
    }
    val targetAchievement = state.recommendationTargetAchievementText.trim().let { value ->
        if (value.isEmpty()) null else value.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..101.0 }
            ?: return ParsedRecommendationFilters(error = "目标达成率必须在 0.0% 到 101.0% 之间")
    }
    val constantMin = state.recommendationConstantMinText.trim().let { value ->
        if (value.isEmpty()) null else value.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.1..20.0 }
            ?: return ParsedRecommendationFilters(error = "最低定数必须在 0.1 到 20.0 之间")
    }
    val constantMax = state.recommendationConstantMaxText.trim().let { value ->
        if (value.isEmpty()) null else value.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.1..20.0 }
            ?: return ParsedRecommendationFilters(error = "最高定数必须在 0.1 到 20.0 之间")
    }
    if (constantMin != null && constantMax != null && constantMin > constantMax) {
        return ParsedRecommendationFilters(error = "最低定数不能高于最高定数")
    }
    return ParsedRecommendationFilters(
        filters = state.recommendationFilters.copy(
            targetTotalRating = targetTotal,
            targetAchievement = targetAchievement,
            constantMin = constantMin,
            constantMax = constantMax,
        ),
    )
}

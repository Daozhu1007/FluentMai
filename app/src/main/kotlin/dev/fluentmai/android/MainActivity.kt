package dev.fluentmai.android

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import dev.fluentmai.android.core.model.PlayRecord
import dev.fluentmai.android.core.model.fittedChartKey
import dev.fluentmai.android.core.importer.WahlapPlayCountIndex
import dev.fluentmai.android.core.database.FluentMaiDatabase
import dev.fluentmai.android.core.database.FluentMaiRepository
import dev.fluentmai.android.core.database.RoomImportPersistence
import dev.fluentmai.android.core.importer.MaimaiSongCatalog
import dev.fluentmai.android.core.importer.RealWahlapImportAdapter
import dev.fluentmai.android.core.importer.RealWahlapImportResult
import dev.fluentmai.android.core.importer.WahlapScorePageProvider
import dev.fluentmai.android.core.importer.WahlapFixtureParser
import dev.fluentmai.android.core.importer.WahlapSupplementalPageProvider
import dev.fluentmai.android.core.model.ChartRecord
import dev.fluentmai.android.core.model.ChartIdentity
import dev.fluentmai.android.core.model.Difficulty
import dev.fluentmai.android.core.model.ImportBatch
import dev.fluentmai.android.core.model.ImportResult
import dev.fluentmai.android.core.model.ImportProgress
import dev.fluentmai.android.core.model.ImportStage
import dev.fluentmai.android.core.model.ImportPageState
import dev.fluentmai.android.core.model.MaimaiMajorVersion
import dev.fluentmai.android.core.model.MaimaiRatedScore
import dev.fluentmai.android.core.model.QuarantineRecord
import dev.fluentmai.android.core.model.RatingHistoryEntry
import dev.fluentmai.android.core.model.ScoreRecord
import dev.fluentmai.android.core.model.SongAliasCatalog
import dev.fluentmai.android.core.model.buildMaimaiBestSet
import dev.fluentmai.android.core.model.buildPlayerRecordCatalog
import dev.fluentmai.android.core.model.resolveCurrentMaimaiVersion
import dev.fluentmai.android.core.privacy.PrivacyRedactor
import dev.fluentmai.android.core.upload.MaimaiScoreUploader
import dev.fluentmai.android.core.upload.MaimaiUploadProgress
import dev.fluentmai.android.core.upload.MaimaiUploadResult
import dev.fluentmai.android.feature.importflow.ImportScreen
import dev.fluentmai.android.feature.scores.ChartQueryScreen
import dev.fluentmai.android.feature.scores.ChartQueryPrewarmer
import dev.fluentmai.android.feature.scores.ChartDetailScreen
import dev.fluentmai.android.feature.scores.AliasDataStatus
import dev.fluentmai.android.feature.scores.PlayerProgressDestination
import dev.fluentmai.android.feature.scores.PlayerProgressScreen
import dev.fluentmai.android.feature.scores.ScoresScreen
import dev.fluentmai.android.feature.scores.B50PosterScreen
import dev.fluentmai.android.feature.scores.ComponentEditor
import dev.fluentmai.android.feature.scores.ComponentEditorDialog
import dev.fluentmai.android.feature.scores.LocalChartPlayCounts
import dev.fluentmai.android.feature.scores.LocalComponentSettingsState
import dev.fluentmai.android.feature.scores.ProvideComponentSettings
import dev.fluentmai.android.feature.scores.rememberResetChartFilters
import dev.fluentmai.android.feature.scores.chartCardContainerColor
import dev.fluentmai.android.feature.settings.SettingsScreen
import dev.fluentmai.android.feature.settings.ThemeMode
import dev.fluentmai.android.feature.tools.ToolboxScreen
import dev.fluentmai.android.vpn.core.LocalVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.Normalizer
import kotlinx.coroutines.withContext

private const val TAG = "FluentMaiImport"
internal const val APP_VERSION = "0.2.8-beta"

class MainActivity : ComponentActivity() {
    private val database by lazy { FluentMaiDatabase.create(this) }
    private val repository by lazy { FluentMaiRepository(database) }
    private val persistence by lazy { RoomImportPersistence(database) }
    private val privacyRedactor by lazy { PrivacyRedactor() }
    private val uploadTokenStore by lazy { UploadTokenStore(this) }
    private val themePreferences by lazy { ThemePreferences(this) }
    private val scoreUploader by lazy {
        MaimaiScoreUploader(transport = AndroidNetworkMaimaiUploadTransport(this))
    }
    private val songCatalogClient by lazy { LxnsMaimaiSongCatalogClient(privacyRedactor) }
    private val songCatalogStore by lazy {
        SongCatalogStore(
            context = this,
            client = songCatalogClient,
            redactor = privacyRedactor,
        )
    }
    private val songAliasStore by lazy {
        SongAliasStore(
            context = this,
            client = songCatalogClient,
            redactor = privacyRedactor,
        )
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var themeMode by remember { mutableStateOf(themePreferences.mode) }
            var experimentalFeatures by remember { mutableStateOf(themePreferences.experimentalFeatures) }
            var automaticUpdates by remember { mutableStateOf(themePreferences.automaticUpdates) }
            var efficientPc by remember { mutableStateOf(themePreferences.efficientPc) }
            FluentMaiTheme(themeMode) {
                ProvideComponentSettings {
                FluentMaiApp(
                    onResetSettings = {
                        themePreferences.reset()
                        themeMode = themePreferences.mode
                        automaticUpdates = themePreferences.automaticUpdates
                        efficientPc = themePreferences.efficientPc
                        experimentalFeatures = themePreferences.experimentalFeatures
                    },
                    themeMode = themeMode,
                    automaticUpdates = automaticUpdates,
                    efficientPc = efficientPc,
                    onEfficientPcChanged = { efficientPc = it; themePreferences.efficientPc = it },
                    onAutomaticUpdatesChanged = { automaticUpdates = it; themePreferences.automaticUpdates = it },
                    experimentalFeatures = experimentalFeatures,
                    onExperimentalFeaturesChanged = { enabled ->
                        experimentalFeatures = enabled
                        themePreferences.experimentalFeatures = enabled
                    },
                    onThemeModeChanged = { mode ->
                        themeMode = mode
                        themePreferences.mode = mode
                    },
                    repository = repository,
                    runRealImport = { authUrl, afterLoginAttempt, onProgress ->
                        runRealImport(authUrl, afterLoginAttempt, onProgress)
                    },
                    runCookieImport = { cookieInput, onProgress -> runCookieImport(cookieInput, onProgress) },
                    loadLocalChartCatalog = { songCatalogStore.loadLocalCatalog() },
                    refreshChartCatalog = { songCatalogStore.refreshFromNetwork() },
                    loadLocalAliasCatalog = { knownSongIds -> songAliasStore.loadLocalCatalog(knownSongIds) },
                    refreshAliasCatalog = { knownSongIds -> songAliasStore.refreshFromNetwork(knownSongIds) },
                    uploadToDivingFish = { token, onProgress -> uploadToDivingFish(token, onProgress) },
                    rebuildDivingFish = { token, onProgress -> rebuildDivingFish(token, onProgress) },
                    uploadToLxns = { token, onProgress -> uploadToLxns(token, onProgress) },
                    uploadTokenStore = uploadTokenStore,
                    redactMessage = privacyRedactor::redact,
                )
                }
            }
        }
    }

    private suspend fun runRealImport(
        authUrl: String,
        afterLoginAttempt: () -> Unit = {},
        onProgress: (ImportProgress) -> Unit = {},
    ): RealWahlapImportResult = withImportDiagnostics("微信捕获") { diagnostics ->
        val efficientPc = themePreferences.efficientPc
        val pageProgress = ImportPageReporter(onProgress)
        onProgress(ImportProgress(ImportStage.Preparing, "正在登录 Wahlap"))
        val client = WahlapHttpScorePageClient(redactor = privacyRedactor, onPlayerHome = B50PlayerStore(this)::capture, onDiagnostic = diagnostics::record)
        try {
            client.login(authUrl)
        } finally {
            afterLoginAttempt()
        }
        onProgress(ImportProgress(ImportStage.Preparing, "正在加载曲库"))
        val catalog = fetchSongCatalogOrEmpty()
        val activity = captureWahlapActivity(catalog, repository, diagnostics::record, onProgress) { client.fetchActivityPage(it) }
        val pcIndex = WahlapPlayCountIndex()
        val realImportAdapter = RealWahlapImportAdapter(
            parser = WahlapFixtureParser(songCatalog = catalog),
            sanitizeFailure = privacyRedactor::redact,
        )
        val result = realImportAdapter.importFetchedPages(
            source = "wahlap:real-device",
            pageProvider = WahlapScorePageProvider { difficulty ->
                pageProgress.page(ImportStage.Scores, difficulty.ordinal, Difficulty.entries.size, "${difficulty.name} 成绩页") {
                    client.fetchScorePage(difficulty).also { html ->
                        pcIndex.addPage(html, difficulty, catalog)
                    }
                }
            },
            supplementalPageProvider = WahlapSupplementalPageProvider {
                pageProgress.page(ImportStage.Supplemental, 0, WahlapSupplementalPages.pages.size, "Rating 对象补充页",
                    complete = { it.size == WahlapSupplementalPages.pages.size }) { client.fetchSupplementalScorePages() }
            },
            persistence = persistence,
        )
        diagnostics.record("PC数爬取规则：${if (efficientPc) "效率优先" else "全部爬取"}")
        val pc = if (efficientPc) captureEfficientPc(catalog, pcIndex.targets(), client::fetchActivityPage,
            repository::savePlayCounts, {}, diagnostics::record, onPageProgress = onProgress)
        else captureFullPlayCounts(pcIndex, result, client::fetchMusicDetail, client::fetchScorePage, catalog, onProgress, diagnostics::record)
        result.copy(fetchedPlayRecordCount = activity.recordCount, failedPlayPageCount = activity.failedPages, activityCaptureAttempted = true,
            fetchedPlayCountCharts = pc.chartCount, activityWarnings = activity.warnings + pc.warnings)
    }

    private suspend fun runCookieImport(cookieInput: String, onProgress: (ImportProgress) -> Unit = {}): RealWahlapImportResult = withImportDiagnostics("手动 Cookie") { diagnostics ->
        val efficientPc = themePreferences.efficientPc
        val pageProgress = ImportPageReporter(onProgress)
        onProgress(ImportProgress(ImportStage.Preparing, "正在验证凭据并加载曲库"))
        val credentials = WahlapCookieImportCredentials.parse(cookieInput)
        val catalog = fetchSongCatalogOrEmpty()
        val realImportAdapter = RealWahlapImportAdapter(
            parser = WahlapFixtureParser(songCatalog = catalog),
            sanitizeFailure = privacyRedactor::redact,
        )
        val client = WahlapManualCookieScorePageClient(
            credentials = credentials,
            redactor = privacyRedactor,
            onPlayerHome = B50PlayerStore(this)::capture,
            onDiagnostic = diagnostics::record,
        )
        try {
            client.validateLogin()
            val activity = captureWahlapActivity(catalog, repository, diagnostics::record, onProgress) { client.fetchActivityPage(it) }
            val pcIndex = WahlapPlayCountIndex()
            val result = realImportAdapter.importFetchedPages(
                source = "wahlap:manual-cookie",
                pageProvider = WahlapScorePageProvider { difficulty ->
                    pageProgress.page(ImportStage.Scores, difficulty.ordinal, Difficulty.entries.size, "${difficulty.name} 成绩页") {
                        client.fetchScorePage(difficulty).also { html ->
                            pcIndex.addPage(html, difficulty, catalog)
                        }
                    }
                },
                supplementalPageProvider = WahlapSupplementalPageProvider {
                    pageProgress.page(ImportStage.Supplemental, 0, WahlapSupplementalPages.pages.size, "Rating 对象补充页",
                        complete = { it.size == WahlapSupplementalPages.pages.size }) { client.fetchSupplementalScorePages() }
                },
                persistence = persistence,
            )
            diagnostics.record("PC数爬取规则：${if (efficientPc) "效率优先" else "全部爬取"}")
            val pc = if (efficientPc) captureEfficientPc(catalog, pcIndex.targets(), client::fetchActivityPage,
                repository::savePlayCounts, {}, diagnostics::record, onPageProgress = onProgress)
            else captureFullPlayCounts(pcIndex, result, client::fetchMusicDetail, client::fetchScorePage, catalog, onProgress, diagnostics::record)
            result.copy(fetchedPlayRecordCount = activity.recordCount, failedPlayPageCount = activity.failedPages, activityCaptureAttempted = true,
                fetchedPlayCountCharts = pc.chartCount, activityWarnings = activity.warnings + pc.warnings)
        } finally {
            client.close()
        }
    }

    private suspend fun captureFullPlayCounts(
        index: WahlapPlayCountIndex,
        result: RealWahlapImportResult,
        fetch: suspend (dev.fluentmai.android.core.importer.WahlapMusicDetailTarget) -> String,
        refreshPage: suspend (Difficulty) -> String,
        catalog: MaimaiSongCatalog,
        onProgress: (ImportProgress) -> Unit,
        onDiagnostic: (String) -> Unit,
    ): PlayCountCaptureResult {
        val known = repository.scores().filter { it.playCount != null }
            .map { Triple(it.title, it.songType, it.difficulty) }.toSet()
        // Fill missing/low-PC charts first; still refresh known counts on every import.
        val targets = index.targets().sortedBy { target ->
            target.difficulties.all { Triple(target.title, target.songType, it) in known }
        }
        val pc = captureWahlapPlayCounts(targets, fetch, repository::savePlayCounts, onDiagnostic = onDiagnostic, onPageProgress = onProgress,
            refreshTarget = { target ->
                val html = refreshPage(target.sourceDifficulty)
                dev.fluentmai.android.core.importer.WahlapPlayCountParser.targets(html, target.sourceDifficulty, catalog).targets
                    .firstOrNull { it.title == target.title && it.songType == target.songType }
                    ?.copy(difficulties = target.difficulties)
            })
        val warnings = buildList {
            addAll(pc.warnings)
            if (index.missingLinks > 0) add("${index.missingLinks} 张已游玩谱面的详情链接未能解析，PC 未完整同步。")
            if (result.failedDifficultyCount > 0) add("部分难度的成绩列表读取失败，PC 未完整同步。")
            if (targets.isEmpty() && result.parsedRecordCount > 0) add("未取得单曲详情链接，PC 未同步；已保留原有 PC。")
        }
        return pc.copy(warnings = warnings.distinct())
    }

    private suspend fun uploadToDivingFish(
        token: String,
        onProgress: (MaimaiUploadProgress) -> Unit,
    ): MaimaiUploadResult {
        onProgress(MaimaiUploadProgress(0, 1, "正在读取本地成绩"))
        val currentScores = repository.scores()
        return scoreUploader.uploadToDivingFish(
            importToken = token,
            scores = currentScores,
            onProgress = onProgress,
        )
    }

    private suspend fun rebuildDivingFish(
        token: String,
        onProgress: (MaimaiUploadProgress) -> Unit,
    ): MaimaiUploadResult {
        onProgress(MaimaiUploadProgress(0, 1, "正在读取本地成绩"))
        val currentScores = repository.scores()
        return scoreUploader.rebuildDivingFishRecords(
            importToken = token,
            freshScores = currentScores,
            recordsToRemove = emptyList(),
            onProgress = onProgress,
        )
    }

    private suspend fun uploadToLxns(
        token: String,
        onProgress: (MaimaiUploadProgress) -> Unit,
    ): MaimaiUploadResult {
        val catalog = fetchSongCatalogOrEmpty()
        val currentScores = repository.scores().withCatalogSongIds(catalog)
        return scoreUploader.uploadToLxns(
            userToken = token,
            scores = currentScores,
            onProgress = onProgress,
        )
    }

    private fun fetchSongCatalogOrEmpty(): MaimaiSongCatalog =
        runCatching { songCatalogClient.fetchCatalog() }
            .getOrElse { error ->
                Log.w(TAG, "LXNS song catalog unavailable: ${privacyRedactor.redact(error.message ?: error::class.java.simpleName)}")
                MaimaiSongCatalog.Empty
            }

    private fun List<ScoreRecord>.withCatalogSongIds(catalog: MaimaiSongCatalog): List<ScoreRecord> =
        map { score ->
            if (score.songId != null) {
                score
            } else {
                score.copy(songId = catalog.idForTitle(score.title))
            }
        }

}

@Composable
private fun FluentMaiApp(
    onResetSettings: () -> Unit,
    themeMode: ThemeMode,
    onThemeModeChanged: (ThemeMode) -> Unit,
    automaticUpdates: Boolean,
    efficientPc: Boolean,
    onEfficientPcChanged: (Boolean) -> Unit,
    onAutomaticUpdatesChanged: (Boolean) -> Unit,
    experimentalFeatures: Boolean,
    onExperimentalFeaturesChanged: (Boolean) -> Unit,
    repository: FluentMaiRepository,
    runRealImport: suspend (String, () -> Unit, (ImportProgress) -> Unit) -> RealWahlapImportResult,
    runCookieImport: suspend (String, (ImportProgress) -> Unit) -> RealWahlapImportResult,
    loadLocalChartCatalog: suspend () -> SongCatalogSnapshot?,
    refreshChartCatalog: suspend () -> SongCatalogSnapshot,
    loadLocalAliasCatalog: suspend (Set<Int>) -> SongAliasSnapshot?,
    refreshAliasCatalog: suspend (Set<Int>) -> SongAliasSnapshot,
    uploadToDivingFish: suspend (String, (MaimaiUploadProgress) -> Unit) -> MaimaiUploadResult,
    rebuildDivingFish: suspend (String, (MaimaiUploadProgress) -> Unit) -> MaimaiUploadResult,
    uploadToLxns: suspend (String, (MaimaiUploadProgress) -> Unit) -> MaimaiUploadResult,
    uploadTokenStore: UploadTokenStore,
    redactMessage: (String) -> String,
) {
    val context = LocalContext.current
    val componentSettings = LocalComponentSettingsState.current
    var componentEditor by remember { mutableStateOf<ComponentEditor?>(null) }
    val resetChartFilters = rememberResetChartFilters()
    AppUpdatePrompt(automaticUpdates)
    val startupStartedAtMs = remember { SystemClock.elapsedRealtime() }
    val authUrlRedactor = remember { PrivacyRedactor() }
    val hookStatus by WahlapHookBridge.status.collectAsState()
    val isHookRunning by WahlapHookBridge.vpnRunning.collectAsState()
    var selectedTab by rememberSaveable { mutableStateOf(AppTab.Home) }
    var homeSelectedChartKey by rememberSaveable { mutableStateOf<String?>(null) }
    var chartsSelectedChartKey by rememberSaveable { mutableStateOf<String?>(null) }
    var playerProgressDestination by rememberSaveable { mutableStateOf<PlayerProgressDestination?>(null) }
    var showB50Poster by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(experimentalFeatures) { if (!experimentalFeatures) showB50Poster = false }
    var playedPresetActive by rememberSaveable { mutableStateOf(false) }
    var scrollToTopRequestId by remember { mutableStateOf(0) }
    var scoreCount by remember { mutableStateOf(0) }
    var scores by remember { mutableStateOf<List<ScoreRecord>>(emptyList()) }
    var playCounts by remember { mutableStateOf<List<dev.fluentmai.android.core.model.ChartPlayCount>>(emptyList()) }
    var rawChartRecords by remember { mutableStateOf<List<ChartRecord>>(emptyList()) }
    var fittedSnapshot by remember { mutableStateOf<FittedSnapshot?>(null) }
    var fittedRefreshRequest by remember { mutableStateOf(0) }
    val fittedStore = remember { DivingFishFitStore(context.applicationContext) }
    val chartRecords = remember(rawChartRecords, fittedSnapshot) {
        rawChartRecords.map { chart -> chart.copy(
            fittedConstant = fittedSnapshot?.values?.get(fittedChartKey(chart.songId, chart.songType, chart.levelIndex)),
            fittedUpdatedAt = fittedSnapshot?.updatedAt,
        ) }
    }
    componentEditor?.let { editor ->
        ComponentEditorDialog(editor, charts = chartRecords) { componentEditor = null }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, fittedRefreshRequest) {
        withContext(Dispatchers.IO) { fittedStore.cached() }?.let { fittedSnapshot = it }
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                try { fittedSnapshot = withContext(Dispatchers.IO) { fittedStore.refresh() } }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { Log.w(TAG, "Fitted constants refresh unavailable; retaining cache") }
                delay(5 * 60 * 1000L)
            }
        }
    }
    var chartMajorVersions by remember { mutableStateOf<List<MaimaiMajorVersion>>(emptyList()) }
    var songAliases by remember { mutableStateOf(SongAliasCatalog.Empty) }
    var songAliasSnapshot by remember { mutableStateOf<SongAliasSnapshot?>(null) }
    var isChartCatalogLoading by remember { mutableStateOf(false) }
    var isScoreStateLoaded by remember { mutableStateOf(false) }
    var isRatingReadyLogged by remember { mutableStateOf(false) }
    var quarantineCount by remember { mutableStateOf(0) }
    var quarantineRecords by remember { mutableStateOf<List<QuarantineRecord>>(emptyList()) }
    var ratingHistory by remember { mutableStateOf<List<RatingHistoryEntry>>(emptyList()) }
    var playRecords by remember { mutableStateOf<List<PlayRecord>>(emptyList()) }
    var lastImport by remember { mutableStateOf<ImportBatch?>(null) }
    var lastRealResult by remember { mutableStateOf<RealWahlapImportResult?>(null) }
    var lastImportError by remember { mutableStateOf<String?>(null) }
    var importDiagnosticDetails by remember { mutableStateOf<String?>(null) }
    var importStatus by remember { mutableStateOf(ImportRunStatus.Idle) }
    var importProgress by remember { mutableStateOf<ImportProgress?>(null) }
    var importPageFailed by remember { mutableStateOf(false) }
    var uploadStatus by remember { mutableStateOf(UploadRunStatus.Idle) }
    var divingFishToken by remember { mutableStateOf(uploadTokenStore.divingFishToken) }
    var lxnsToken by remember { mutableStateOf(uploadTokenStore.lxnsToken) }
    var lastUploadResult by remember { mutableStateOf<MaimaiUploadResult?>(null) }
    var lastUploadError by remember { mutableStateOf<String?>(null) }
    var uploadProgressText by remember { mutableStateOf<String?>(null) }
    var uploadProgressFraction by remember { mutableStateOf<Float?>(null) }
    var hookLink by remember { mutableStateOf(WahlapHookHttpService.HOOK_URL) }
    var wahlapCookieInput by remember { mutableStateOf("") }
    var isPreparingHookLink by remember { mutableStateOf(false) }
    var isImporting by remember { mutableStateOf(false) }
    var isUploading by remember { mutableStateOf(false) }
    var isSettingsOpen by rememberSaveable { mutableStateOf(false) }
    var automaticRatingRequestId by remember { mutableStateOf(0) }
    var processedAutomaticRatingRequestId by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    var settingsResetRevision by rememberSaveable { mutableStateOf(0) }
    val screenStateHolder = key(settingsResetRevision) { rememberSaveableStateHolder() }
    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startVpnService(context)
        } else {
            WahlapHookBridge.setStatus("没有获得 VPN 权限，无法从微信捕获授权请求。")
        }
    }

    suspend fun refreshState() {
        val startedAt = SystemClock.elapsedRealtime()
        scoreCount = repository.scoreCount()
        scores = repository.scores()
        playCounts = repository.playCounts()
        quarantineCount = repository.quarantineCount()
        quarantineRecords = repository.quarantineRecords()
        ratingHistory = repository.ratingHistory()
        playRecords = repository.playRecords()
        lastImport = repository.latestImportBatch()
        isScoreStateLoaded = true
        Log.i(
            TAG,
            "Scores state loaded in ${SystemClock.elapsedRealtime() - startedAt}ms: " +
                "scoreCount=$scoreCount quarantineCount=$quarantineCount",
        )
    }

    fun updateUploadProgress(progress: MaimaiUploadProgress) {
        uploadProgressText = progress.message
        uploadProgressFraction = if (progress.completedSteps <= 0) {
            null
        } else {
            progress.fraction.coerceIn(0f, 1f)
        }
    }

    fun stopCaptureBeforeUpload() {
        if (isHookRunning) {
            stopVpnService(context)
            WahlapHookBridge.setStatus("上传前已停止 Hook 捕获，避免本地 VPN 影响外网上传。")
        }
    }

    fun refreshChartRecords() {
        fittedRefreshRequest += 1
        scope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            isChartCatalogLoading = true
            val localSnapshot = withContext(Dispatchers.IO) { loadLocalChartCatalog() }
            if (localSnapshot != null) {
                rawChartRecords = localSnapshot.catalog.charts()
                chartMajorVersions = localSnapshot.catalog.majorVersions()
                Log.i(
                    TAG,
                    "Local song catalog ready in ${SystemClock.elapsedRealtime() - startedAt}ms: " +
                        "source=${localSnapshot.source.logName} songCount=${localSnapshot.songCount} " +
                        "chartCount=${localSnapshot.chartCount} jsonBytes=${localSnapshot.jsonBytes}",
                )
            } else {
                Log.w(TAG, "No local song catalog cache or bundled fallback available")
            }
            val localAliasSnapshot = withContext(Dispatchers.IO) {
                loadLocalAliasCatalog(rawChartRecords.mapTo(mutableSetOf()) { it.songId })
            }
            if (localAliasSnapshot != null) {
                songAliases = localAliasSnapshot.catalog
                songAliasSnapshot = localAliasSnapshot
                Log.i(
                    TAG,
                    "Local alias catalog ready: songCount=${localAliasSnapshot.songCount} " +
                        "aliasCount=${localAliasSnapshot.aliasCount} unmapped=${localAliasSnapshot.unmappedSongIds.size}",
                )
            }
            try {
                val networkStartedAt = SystemClock.elapsedRealtime()
                Log.i(TAG, "LXNS song catalog background refresh started")
                val networkSnapshot = withContext(Dispatchers.IO) { refreshChartCatalog() }
                rawChartRecords = networkSnapshot.catalog.charts()
                chartMajorVersions = networkSnapshot.catalog.majorVersions()
                Log.i(
                    TAG,
                    "LXNS song catalog background refresh completed in " +
                        "${SystemClock.elapsedRealtime() - networkStartedAt}ms: " +
                        "songCount=${networkSnapshot.songCount} chartCount=${networkSnapshot.chartCount} " +
                        "jsonBytes=${networkSnapshot.jsonBytes}",
                )
            } catch (error: Exception) {
                val safeMessage = redactMessage(error.message ?: error::class.java.simpleName)
                Log.w(
                    TAG,
                    "LXNS song catalog background refresh failed after " +
                        "${SystemClock.elapsedRealtime() - startedAt}ms: $safeMessage; " +
                        "usingChartCount=${rawChartRecords.size}",
                )
            }
            try {
                val aliasSnapshot = withContext(Dispatchers.IO) {
                    refreshAliasCatalog(rawChartRecords.mapTo(mutableSetOf()) { it.songId })
                }
                songAliases = aliasSnapshot.catalog
                songAliasSnapshot = aliasSnapshot
                Log.i(
                    TAG,
                    "Community alias refresh completed: songCount=${aliasSnapshot.songCount} " +
                        "aliasCount=${aliasSnapshot.aliasCount} mapped=${aliasSnapshot.mappedSongCount} " +
                        "unmapped=${aliasSnapshot.unmappedSongIds.size}",
                )
            } catch (error: Exception) {
                val safeMessage = redactMessage(error.message ?: error::class.java.simpleName)
                Log.w(
                    TAG,
                    "Community alias refresh failed: $safeMessage; usingAliasCount=${songAliases.aliasCount}",
                )
            } finally {
                isChartCatalogLoading = false
            }
        }
    }

    fun startDivingFishUpload() {
        val capturedToken = divingFishToken.trim()
        if (capturedToken.isBlank()) {
            uploadStatus = UploadRunStatus.Failed
            lastUploadError = "请先填写水鱼 Import Token。"
            return
        }
        if (scoreCount <= 0) {
            uploadStatus = UploadRunStatus.Failed
            lastUploadError = "请先导入成绩，再上传。"
            return
        }
        scope.launch {
            isUploading = true
            uploadStatus = UploadRunStatus.Uploading
            lastUploadError = null
            lastUploadResult = null
            stopCaptureBeforeUpload()
            updateUploadProgress(MaimaiUploadProgress(0, 1, "准备上传到水鱼"))
            try {
                val result = withContext(Dispatchers.IO) {
                    uploadToDivingFish(capturedToken) { progress ->
                        scope.launch { updateUploadProgress(progress) }
                    }
                }
                lastUploadResult = result
                uploadStatus = result.toUploadRunStatus()
                uploadProgressText = result.message
                uploadProgressFraction = if (result.success || result.hasCloudLocalDiff) 1f else uploadProgressFraction
                Log.i(TAG, "Diving Fish upload completed: ${result.safeSummary()}")
            } catch (error: Exception) {
                val safeMessage = redactMessage(error.message ?: error::class.java.simpleName)
                lastUploadError = safeMessage
                uploadStatus = UploadRunStatus.Failed
                uploadProgressText = "上传失败：$safeMessage"
                Log.e(TAG, "Diving Fish upload failed: $safeMessage")
            } finally {
                isUploading = false
            }
        }
    }

    fun startDivingFishRebuild() {
        val capturedToken = divingFishToken.trim()
        if (capturedToken.isBlank()) {
            uploadStatus = UploadRunStatus.Failed
            lastUploadError = "请先填写水鱼 Import Token。"
            return
        }
        if (scoreCount <= 0) {
            uploadStatus = UploadRunStatus.Failed
            lastUploadError = "请先导入成绩，再重建水鱼数据。"
            return
        }
        scope.launch {
            isUploading = true
            uploadStatus = UploadRunStatus.Uploading
            lastUploadError = null
            lastUploadResult = null
            stopCaptureBeforeUpload()
            updateUploadProgress(MaimaiUploadProgress(0, 1, "准备重建水鱼数据"))
            try {
                val result = withContext(Dispatchers.IO) {
                    rebuildDivingFish(capturedToken) { progress ->
                        scope.launch { updateUploadProgress(progress) }
                    }
                }
                lastUploadResult = result
                uploadStatus = result.toUploadRunStatus()
                uploadProgressText = result.message
                uploadProgressFraction = if (result.success || result.hasCloudLocalDiff) 1f else uploadProgressFraction
                Log.i(TAG, "Diving Fish rebuild completed: ${result.safeSummary()}")
            } catch (error: Exception) {
                val safeMessage = redactMessage(error.message ?: error::class.java.simpleName)
                lastUploadError = safeMessage
                uploadStatus = UploadRunStatus.Failed
                uploadProgressText = "重建失败：$safeMessage"
                Log.e(TAG, "Diving Fish rebuild failed: $safeMessage")
            } finally {
                isUploading = false
            }
        }
    }

    fun startLxnsUpload() {
        val capturedToken = lxnsToken.trim()
        if (capturedToken.isBlank()) {
            uploadStatus = UploadRunStatus.Failed
            lastUploadError = "请先填写落雪 LXNS User Token。"
            return
        }
        if (scoreCount <= 0) {
            uploadStatus = UploadRunStatus.Failed
            lastUploadError = "请先导入成绩，再上传。"
            return
        }
        scope.launch {
            isUploading = true
            uploadStatus = UploadRunStatus.Uploading
            lastUploadError = null
            lastUploadResult = null
            stopCaptureBeforeUpload()
            updateUploadProgress(MaimaiUploadProgress(0, 1, "准备上传到 LXNS"))
            try {
                val result = withContext(Dispatchers.IO) {
                    uploadToLxns(capturedToken) { progress ->
                        scope.launch { updateUploadProgress(progress) }
                    }
                }
                lastUploadResult = result
                uploadStatus = if (result.success) UploadRunStatus.Success else UploadRunStatus.Failed
                uploadProgressText = result.message
                uploadProgressFraction = if (result.success) 1f else uploadProgressFraction
                Log.i(TAG, "LXNS upload completed: ${result.safeSummary()}")
            } catch (error: Exception) {
                val safeMessage = redactMessage(error.message ?: error::class.java.simpleName)
                lastUploadError = safeMessage
                uploadStatus = UploadRunStatus.Failed
                uploadProgressText = "上传失败：$safeMessage"
                Log.e(TAG, "LXNS upload failed: $safeMessage")
            } finally {
                isUploading = false
            }
        }
    }

    suspend fun finishImportProgress(result: RealWahlapImportResult) {
        val fetchedProgress = importProgress
        importProgress = ImportProgress(ImportStage.Saving, "正在刷新本地成绩、PC 和游玩记录")
        refreshState()
        importProgress = if (result.isCompleteSuccess && result.activityWarnings.isEmpty() && !importPageFailed) {
            ImportProgress(ImportStage.Saving, "导入完成", processedPages = 1, totalPages = 1, pageState = ImportPageState.Complete)
        } else fetchedProgress?.copy(detail = "本次爬取已结束，未完整读取的页面请查看下方导入结果")
    }

    fun startCapturedRealImport(capturedAuthUrl: String) {
        scope.launch {
            isImporting = true
            importStatus = ImportRunStatus.Importing
            importProgress = ImportProgress(ImportStage.Preparing, "正在登录并准备同步")
            importPageFailed = false
            lastImportError = null
            lastRealResult = null
            importDiagnosticDetails = null
            val captureStopped = java.util.concurrent.atomic.AtomicBoolean(false)
            fun stopCaptureAfterLogin() {
                if (captureStopped.compareAndSet(false, true)) {
                    Log.i(TAG, "Stopping capture services after Wahlap login attempt")
                    stopVpnService(context)
                    WahlapHookHttpService.stop(context)
                }
            }
            try {
                WahlapHookBridge.setStatus("已捕获回跳授权，正在关闭捕获并登录 Wahlap。")
                Log.i(TAG, "Starting real Wahlap import from captured auth URL")
                val result = withContext(Dispatchers.IO) {
                    runRealImport(capturedAuthUrl, ::stopCaptureAfterLogin) { progress ->
                        scope.launch { if (isImporting) {
                            importProgress = progress
                            if (progress.failedPages > 0) importPageFailed = true
                        } }
                    }
                }
                lastRealResult = result
                importDiagnosticDetails = result.diagnosticDetails.takeIf { importPageFailed || result.failedDifficultyCount > 0 || result.activityWarnings.isNotEmpty() || result.supplementalFailures.isNotEmpty() }
                val importSucceeded = result.failedDifficultyCount == 0 && result.fetchedDifficultyCount > 0
                importStatus = if (importSucceeded) {
                    if (result.activityWarnings.isEmpty() && result.supplementalFailures.isEmpty() && !importPageFailed) ImportRunStatus.Success else ImportRunStatus.PartialSuccess
                } else {
                    ImportRunStatus.Failed
                }
                lastImportError = result.takeIf { it.failedDifficultyCount > 0 }
                    ?.failures
                    ?.joinToString("; ") { "${it.difficulty.name}: ${it.message}" }
                finishImportProgress(result)
                if (importSucceeded) automaticRatingRequestId += 1
                Log.i(TAG, "real Wahlap import completed: ${result.safeSummary()}")
            } catch (error: Exception) {
                val safeMessage = redactMessage(error.message ?: error::class.java.simpleName)
                lastImportError = safeMessage
                importDiagnosticDetails = (error as? ImportDiagnosticException)?.details ?: diagnosticException(error)
                importStatus = ImportRunStatus.Failed
                Log.e(TAG, "real Wahlap import failed: $safeMessage")
                importProgress = importProgress?.copy(pageState = ImportPageState.Failed, detail = "导入已停止，请查看下方错误信息")
            } finally {
                stopCaptureAfterLogin()
                isImporting = false
                WahlapHookBridge.finishImport()
            }
        }
    }

    fun startManualCookieImport() {
        val capturedInput = wahlapCookieInput.trim()
        if (capturedInput.isBlank()) {
            importProgress = null
            importStatus = ImportRunStatus.Failed
            lastImportError = "请先粘贴 Wahlap Cookie 或 Reqable 请求头。"
            importDiagnosticDetails = lastImportError
            return
        }
        scope.launch {
            isImporting = true
            importStatus = ImportRunStatus.Importing
            importProgress = ImportProgress(ImportStage.Preparing, "正在登录并准备同步")
            importPageFailed = false
            lastImportError = null
            lastRealResult = null
            importDiagnosticDetails = null
            try {
                stopVpnService(context)
                WahlapHookHttpService.stop(context)
                WahlapHookBridge.finishImport()
                WahlapHookBridge.setStatus("正在使用 Wahlap Cookie 导入本地成绩。")
                val result = withContext(Dispatchers.IO) {
                    runCookieImport(capturedInput) { progress ->
                        scope.launch { if (isImporting) {
                            importProgress = progress
                            if (progress.failedPages > 0) importPageFailed = true
                        } }
                    }
                }
                lastRealResult = result
                importDiagnosticDetails = result.diagnosticDetails.takeIf { importPageFailed || result.failedDifficultyCount > 0 || result.activityWarnings.isNotEmpty() || result.supplementalFailures.isNotEmpty() }
                val importSucceeded = result.failedDifficultyCount == 0 && result.fetchedDifficultyCount > 0
                importStatus = if (importSucceeded) {
                    if (result.activityWarnings.isEmpty() && result.supplementalFailures.isEmpty() && !importPageFailed) ImportRunStatus.Success else ImportRunStatus.PartialSuccess
                } else {
                    ImportRunStatus.Failed
                }
                lastImportError = result.takeIf { it.failedDifficultyCount > 0 }
                    ?.failures
                    ?.joinToString("; ") { "${it.difficulty.name}: ${it.message}" }
                finishImportProgress(result)
                if (importSucceeded) automaticRatingRequestId += 1
                Log.i(TAG, "manual Wahlap import completed: ${result.safeSummary()}")
            } catch (error: Exception) {
                val safeMessage = redactMessage(error.message ?: error::class.java.simpleName)
                lastImportError = safeMessage
                importDiagnosticDetails = (error as? ImportDiagnosticException)?.details ?: diagnosticException(error)
                importStatus = ImportRunStatus.Failed
                Log.e(TAG, "manual Wahlap import failed: $safeMessage")
                importProgress = importProgress?.copy(pageState = ImportPageState.Failed, detail = "导入已停止，请查看下方错误信息")
            } finally {
                isImporting = false
            }
        }
    }

    fun startHookCapture() {
        WahlapHookHttpService.start(context)
        val vpnPrepareIntent = VpnService.prepare(context)
        if (vpnPrepareIntent != null) {
            vpnPermissionLauncher.launch(vpnPrepareIntent)
        } else {
            startVpnService(context)
        }
    }

    fun stopHookCapture() {
        stopVpnService(context)
        WahlapHookHttpService.stop(context)
    }

    fun copyHookUrl() {
        scope.launch {
            isPreparingHookLink = true
            try {
                val authUrl = withContext(Dispatchers.IO) {
                    WahlapWechatAuthUrlClient(authUrlRedactor).maimaiDxAuthUrl()
                }
                hookLink = authUrl
                copyTextToClipboard(context, "FluentMai 微信授权链接", authUrl)
                WahlapHookBridge.setStatus("微信授权链接已复制。请发到微信并点开，VPN 会捕获回跳授权。")
            } catch (error: Exception) {
                val safeMessage = redactMessage(error.message ?: error::class.java.simpleName)
                hookLink = WahlapHookHttpService.HOOK_URL
                copyTextToClipboard(context, "FluentMai 备用 Hook 链接", WahlapHookHttpService.HOOK_URL)
                WahlapHookBridge.setStatus("生成微信授权链接失败，已复制备用本地链接：$safeMessage")
            } finally {
                isPreparingHookLink = false
            }
        }
    }

    LaunchedEffect(Unit) {
        refreshState()
        refreshChartRecords()
    }

    LaunchedEffect(Unit) {
        WahlapHookBridge.capturedAuthUrls.collect { capturedAuthUrl ->
            startCapturedRealImport(capturedAuthUrl)
        }
    }

    LaunchedEffect(isScoreStateLoaded, scores, chartRecords) {
        if (!isScoreStateLoaded) return@LaunchedEffect
        val unmatchedScoreCount = unmatchedScoreCount(scores, chartRecords)
        val ratingReady = scores.isNotEmpty() && chartRecords.isNotEmpty() && unmatchedScoreCount < scores.size
        Log.i(
            TAG,
            "Scores startup rating state: scoreCount=${scores.size} " +
                "cachedChartCount=${chartRecords.size} unmatchedScoreCount=$unmatchedScoreCount " +
                "ratingReady=$ratingReady elapsedMs=${SystemClock.elapsedRealtime() - startupStartedAtMs}",
        )
        if (ratingReady && !isRatingReadyLogged) {
            isRatingReadyLogged = true
            Log.i(
                TAG,
                "Scores startup rating ready in ${SystemClock.elapsedRealtime() - startupStartedAtMs}ms: " +
                    "scoreCount=${scores.size} chartCount=${chartRecords.size} unmatchedScoreCount=$unmatchedScoreCount",
            )
        }
    }

    LaunchedEffect(automaticRatingRequestId, scores, chartRecords, chartMajorVersions) {
        if (automaticRatingRequestId <= 0 || automaticRatingRequestId == processedAutomaticRatingRequestId) {
            return@LaunchedEffect
        }
        if (scores.isEmpty() || chartRecords.isEmpty()) return@LaunchedEffect
        val rating = withContext(Dispatchers.Default) {
            val currentVersion = resolveCurrentMaimaiVersion(chartMajorVersions, chartRecords)
                ?: return@withContext null
            val catalog = buildPlayerRecordCatalog(chartRecords, scores)
            val ratedScores = catalog.records.mapNotNull { record ->
                record.score?.let { score ->
                    MaimaiRatedScore(score = score, chart = record.chart, rating = record.rating)
                }
            }
            buildMaimaiBestSet(ratedScores, currentVersion).rating.takeIf { it > 0 }
        } ?: return@LaunchedEffect
        val inserted = withContext(Dispatchers.IO) {
            repository.recordAutomaticRating(
                recordedAtEpochMillis = System.currentTimeMillis(),
                rating = rating,
            )
        }
        ratingHistory = withContext(Dispatchers.IO) { repository.ratingHistory() }
        processedAutomaticRatingRequestId = automaticRatingRequestId
        Log.i(TAG, "Rating history automatic snapshot completed: inserted=$inserted rating=$rating")
    }

    val selectedChartKey = when (selectedTab) {
        AppTab.Home -> homeSelectedChartKey
        AppTab.Charts -> chartsSelectedChartKey
        AppTab.Import, AppTab.Tools -> null
    }
    val selectedChartIdentity = ChartIdentity.parseStableKey(selectedChartKey)
    val currentVersionId = remember(chartMajorVersions, chartRecords) {
        resolveCurrentMaimaiVersion(chartMajorVersions, chartRecords)?.majorVersion?.id
    }
    val clearSelectedChart: () -> Unit = {
        when (selectedTab) {
            AppTab.Home -> homeSelectedChartKey = null
            AppTab.Charts -> chartsSelectedChartKey = null
            AppTab.Import, AppTab.Tools -> Unit
        }
    }
    val selectChart: (ChartIdentity) -> Unit = { identity ->
        when (selectedTab) {
            AppTab.Home -> homeSelectedChartKey = identity.stableKey()
            AppTab.Charts -> chartsSelectedChartKey = identity.stableKey()
            AppTab.Import, AppTab.Tools -> Unit
        }
    }
    BackHandler(enabled = selectedChartIdentity != null, onBack = clearSelectedChart)
    BackHandler(
        enabled = selectedChartIdentity == null && selectedTab == AppTab.Home && playerProgressDestination != null,
    ) {
        playerProgressDestination = null
    }
    BackHandler(
        enabled = selectedChartIdentity == null && selectedTab == AppTab.Tools && isSettingsOpen,
    ) {
        isSettingsOpen = false
    }

    val onTabSelected: (AppTab) -> Unit = { tab ->
        if (tab != AppTab.Charts) playedPresetActive = false
        selectedTab = tab
    }
    ChartQueryPrewarmer(
        charts = chartRecords,
        scores = scores,
        majorVersions = chartMajorVersions,
        aliases = songAliases,
        playedPresetActive = playedPresetActive,
    )
    // Give every tab destination its own saveable-state registry so switching tabs does not reset its UI.
    val screenStateKey = when {
        selectedChartKey != null -> "${selectedTab.name}:chart:$selectedChartKey"
        selectedTab == AppTab.Home && showB50Poster && experimentalFeatures -> "Home:b50-poster"
        selectedTab == AppTab.Home && playerProgressDestination != null ->
            "${AppTab.Home.name}:progress:${requireNotNull(playerProgressDestination).name}"
        selectedTab == AppTab.Tools && isSettingsOpen -> "${AppTab.Tools.name}:settings"
        else -> "${selectedTab.name}:root"
    }
    AdaptiveNavigationScaffold(
        selectedTab = selectedTab,
        onTabSelected = onTabSelected,
        onSelectedTabReselected = { scrollToTopRequestId += 1 },
    ) { innerPadding ->
        val modifier = Modifier.padding(innerPadding)
        screenStateHolder.SaveableStateProvider(screenStateKey) {
            if (selectedChartIdentity != null) {
                ChartDetailScreen(
                    playCounts = playCounts,
                    identity = selectedChartIdentity,
                    charts = chartRecords,
                    scores = scores,
                    aliases = songAliases,
                    aliasStatus = songAliasSnapshot?.let { snapshot ->
                        AliasDataStatus(
                            sourceLabel = when (snapshot.source) {
                                SongAliasSource.Network -> "社区别名在线刷新"
                                SongAliasSource.FileCache -> "社区别名本地缓存"
                            },
                            fetchedAtEpochMillis = snapshot.fetchedAtEpochMillis,
                            contentVersion = snapshot.contentVersion,
                            songCount = snapshot.songCount,
                            aliasCount = snapshot.aliasCount,
                            unmappedSongCount = snapshot.unmappedSongIds.size,
                        )
                    },
                    currentVersionId = currentVersionId,
                    scrollToTopRequestId = scrollToTopRequestId,
                    onBack = clearSelectedChart,
                    onChartSelected = selectChart,
                    modifier = modifier,
                )
            } else if (selectedTab == AppTab.Home && showB50Poster && experimentalFeatures) {
                B50PosterScreen(
                    scores = scores, charts = chartRecords, majorVersions = chartMajorVersions,
                    loadProfile = { B50PlayerStore(context).load() },
                    profileRevision = B50PlayerStore(context).revision,
                    onBack = { showB50Poster = false },
                    scrollToTopRequestId = scrollToTopRequestId, modifier = modifier,
                )
            } else if (selectedTab == AppTab.Home && playerProgressDestination != null) {
                PlayerProgressScreen(
                    destination = requireNotNull(playerProgressDestination),
                    scores = scores,
                    charts = chartRecords,
                    majorVersions = chartMajorVersions,
                    onDestinationChanged = { playerProgressDestination = it },
                    onBack = { playerProgressDestination = null },
                    onChartSelected = selectChart,
                    scrollToTopRequestId = scrollToTopRequestId,
                    modifier = modifier,
                )
            } else when (selectedTab) {
                AppTab.Home -> ScoresScreen(
                    onOpenB50Poster = if (experimentalFeatures) ({ showB50Poster = true }) else null,
                    scores = scores,
                    charts = chartRecords,
                    majorVersions = chartMajorVersions,
                    onOpenPlayedCharts = {
                        playedPresetActive = true
                        chartsSelectedChartKey = null
                        selectedTab = AppTab.Charts
                    },
                    onOpenPlates = {
                        playerProgressDestination = PlayerProgressDestination.PLATES
                    },
                    onOpenRecommendations = {
                        playerProgressDestination = PlayerProgressDestination.RECOMMENDATIONS
                    },
                    onChartSelected = selectChart,
                    scrollToTopRequestId = scrollToTopRequestId,
                    modifier = modifier,
                )

                AppTab.Import -> ImportScreen(
                    moduleBackgroundColor = chartCardContainerColor(),
                    realImportSummary = if (isImporting) importProgress?.detail else lastRealResult?.summaryText(),
                    importProgress = importProgress,
                    importStatus = importStatus.label,
                    errorMessage = lastImportError,
                    onCopyImportError = importDiagnosticDetails?.takeIf { !isImporting && it.isNotBlank() }?.let { details ->
                        {
                            copyTextToClipboard(context, "FluentMai 导入详细报错", sanitizeImportDiagnostic(details))
                            Toast.makeText(context, "已复制详细报错（已移除敏感信息）", Toast.LENGTH_SHORT).show()
                        }
                    },
                    hookUrl = hookLink,
                    hookStatus = hookStatus,
                    isHookRunning = isHookRunning,
                    divingFishToken = divingFishToken,
                    lxnsToken = lxnsToken,
                    uploadStatus = uploadStatus.label,
                    uploadSummary = lastUploadResult?.summaryText(),
                    uploadErrorMessage = lastUploadError,
                    uploadProgressText = uploadProgressText,
                    uploadProgressFraction = uploadProgressFraction,
                    scoreCount = scoreCount,
                    isImporting = isImporting,
                    isUploading = isUploading,
                    isPreparingHookLink = isPreparingHookLink,
                    wahlapCookieInput = wahlapCookieInput,
                    onStartHookCapture = ::startHookCapture,
                    onStopHookCapture = ::stopHookCapture,
                    onCopyHookUrl = ::copyHookUrl,
                    onWahlapCookieInputChanged = { value -> wahlapCookieInput = value },
                    onImportWahlapCookie = ::startManualCookieImport,
                    onDivingFishTokenChanged = { token ->
                        divingFishToken = token
                        runCatching { uploadTokenStore.divingFishToken = token }
                            .onFailure {
                                Toast.makeText(context, "水鱼 Token 保存失败，请重新输入或粘贴后重试。", Toast.LENGTH_LONG).show()
                            }
                    },
                    onLxnsTokenChanged = { token ->
                        lxnsToken = token
                        runCatching { uploadTokenStore.lxnsToken = token }
                            .onFailure {
                                Toast.makeText(context, "落雪 Token 保存失败，请重新输入或粘贴后重试。", Toast.LENGTH_LONG).show()
                            }
                    },
                    onUploadDivingFish = ::startDivingFishUpload,
                    onRebuildDivingFish = ::startDivingFishRebuild,
                    onUploadLxns = ::startLxnsUpload,
                    scrollToTopRequestId = scrollToTopRequestId,
                    modifier = modifier,
                )

                AppTab.Charts -> CompositionLocalProvider(LocalChartPlayCounts provides playCounts) { ChartQueryScreen(
                    charts = chartRecords,
                    scores = scores,
                    majorVersions = chartMajorVersions,
                    aliases = songAliases,
                    isLoading = isChartCatalogLoading,
                    onRefresh = ::refreshChartRecords,
                    playedPresetActive = playedPresetActive,
                    onDismissPlayedPreset = { playedPresetActive = false },
                    scrollToTopRequestId = scrollToTopRequestId,
                    onChartSelected = selectChart,
                    modifier = modifier,
                ) }

                AppTab.Tools -> if (isSettingsOpen) {
                    SettingsScreen(
                        onEditThumbnail = { componentEditor = ComponentEditor.Thumbnail },
                        onEditDetails = { componentEditor = ComponentEditor.Details },
                        onResetSettings = {
                            onResetSettings()
                            componentSettings.reset()
                            resetChartFilters()
                            playedPresetActive = false
                            settingsResetRevision += 1
                            Toast.makeText(context, "已恢复默认设置", Toast.LENGTH_SHORT).show()
                        },
                        efficientPc = efficientPc,
                        onEfficientPcChanged = onEfficientPcChanged,
                        themeMode = themeMode,
                        experimentalFeatures = experimentalFeatures,
                        onExperimentalFeaturesChanged = onExperimentalFeaturesChanged,
                        onThemeModeChanged = onThemeModeChanged,
                        automaticUpdates = automaticUpdates,
                        onAutomaticUpdatesChanged = onAutomaticUpdatesChanged,
                        appVersion = APP_VERSION,
                        quarantineCount = quarantineCount,
                        records = quarantineRecords,
                        onBack = { isSettingsOpen = false },
                        scrollToTopRequestId = scrollToTopRequestId,
                        modifier = modifier,
                    )
                } else {
                    ToolboxScreen(
                        charts = chartRecords,
                        majorVersions = chartMajorVersions,
                        ratingHistory = ratingHistory,
                        playRecords = playRecords,
                        onAddManualRating = { recordedAt, rating, note ->
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    repository.addManualRating(recordedAt, rating, note)
                                }
                                ratingHistory = withContext(Dispatchers.IO) { repository.ratingHistory() }
                            }
                        },
                        onUpdateManualRating = { id, recordedAt, rating, note ->
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    repository.updateManualRating(id, recordedAt, rating, note)
                                }
                                ratingHistory = withContext(Dispatchers.IO) { repository.ratingHistory() }
                            }
                        },
                        onDeleteManualRating = { id ->
                            scope.launch {
                                withContext(Dispatchers.IO) { repository.deleteManualRating(id) }
                                ratingHistory = withContext(Dispatchers.IO) { repository.ratingHistory() }
                            }
                        },
                        onOpenSettings = { isSettingsOpen = true },
                        scrollToTopRequestId = scrollToTopRequestId,
                        modifier = modifier,
                    )
                }
            }
        }
    }
}

private fun unmatchedScoreCount(
    scores: List<ScoreRecord>,
    charts: List<ChartRecord>,
): Int {
    if (scores.isEmpty()) return 0
    if (charts.isEmpty()) return scores.size
    val chartTitleKeys = charts
        .map { chart -> StartupTitleKey(normalizeStartupTitle(chart.title), chart.songType, chart.levelIndex) }
        .toSet()
    val chartSongIdKeys = charts
        .map { chart -> StartupSongIdKey(chart.songId, chart.songType, chart.levelIndex) }
        .toSet()
    return scores.count { score ->
        val titleMatched = StartupTitleKey(normalizeStartupTitle(score.title), score.songType, score.levelIndex) in chartTitleKeys
        val songIdMatched = score.songId?.let { StartupSongIdKey(it, score.songType, score.levelIndex) in chartSongIdKeys } == true
        !titleMatched && !songIdMatched
    }
}

private data class StartupTitleKey(
    val title: String,
    val songType: dev.fluentmai.android.core.model.SongType,
    val levelIndex: Int,
)

private data class StartupSongIdKey(
    val songId: Int,
    val songType: dev.fluentmai.android.core.model.SongType,
    val levelIndex: Int,
)

private fun normalizeStartupTitle(title: String): String =
    Normalizer.normalize(title.trim(), Normalizer.Form.NFKC).lowercase()

@Composable
private fun FluentMaiTheme(themeMode: ThemeMode, content: @Composable () -> Unit) {
    val darkTheme = themeMode.isDark(isSystemInDarkTheme())
    val colorScheme = if (darkTheme) {
        darkColorScheme(
            primary = Color(0xFF7DD8C2),
            secondary = Color(0xFFF1C15E),
            tertiary = Color(0xFFFFB0CB),
            background = Color(0xFF101418),
            surface = Color(0xFF151A20),
            surfaceVariant = Color(0xFF27313A),
            onSurface = Color(0xFFE7ECEF),
            onSurfaceVariant = Color(0xFFC1CBD3),
            outline = Color(0xFF8A949D),
            outlineVariant = Color(0xFF414B54),
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF246B5A),
            secondary = Color(0xFF735C0F),
            tertiary = Color(0xFF7A405A),
            background = Color(0xFFFBFCF8),
            surface = Color(0xFFFFFFFF),
            surfaceVariant = Color(0xFFF0F4F7),
            onSurface = Color(0xFF172027),
            onSurfaceVariant = Color(0xFF52616C),
            outline = Color(0xFF707C86),
            outlineVariant = Color(0xFFD7E0E7),
        )
    }
    val activity = LocalContext.current as? Activity
    SideEffect {
        activity?.window?.let { window ->
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }
    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}

internal enum class AppTab(
    val label: String,
    val icon: ImageVector,
) {
    Home("首页", Icons.Filled.Home),
    Import("导入", Icons.Filled.PlayArrow),
    Charts("谱面", Icons.Filled.Search),
    Tools("工具", Icons.Filled.Build),
}

private enum class ImportRunStatus(val label: String) {
    Idle("未开始"),
    Importing("导入中"),
    Success("成功"),
    PartialSuccess("成绩已导入，PC 或游玩记录未完整同步"),
    Failed("失败"),
}

private enum class UploadRunStatus(val label: String) {
    Idle("未开始"),
    Uploading("上传中"),
    Success("成功"),
    CloudMismatch("已上传，校验不一致"),
    Failed("失败"),
}

private fun ImportResult.safeSummary(): String =
    "inserted=$inserted duplicate=$skippedDuplicate quarantined=$quarantined rejected=$rejected"

private fun RealWahlapImportResult.safeSummary(): String =
    "${importResult.safeSummary()} parsed=$parsedRecordCount " +
        "fetchedDifficulties=$fetchedDifficultyCount failedDifficulties=$failedDifficultyCount " +
        "supplementalPages=$fetchedSupplementalPageCount supplementalParsed=$parsedSupplementalRecordCount"

private fun RealWahlapImportResult.summaryText(): String =
    "新增 ${importResult.inserted} 条，跳过重复 ${importResult.skippedDuplicate} 条，" +
        "隔离 ${importResult.quarantined} 条，拒绝 ${importResult.rejected} 条，" +
        "失败难度 $failedDifficultyCount 个，解析 $parsedRecordCount 条，" +
        "补充页 $fetchedSupplementalPageCount 个，补充解析 $parsedSupplementalRecordCount 条" +
        (if (activityCaptureAttempted) "\n本次读取 $fetchedPlayRecordCount 条最近游玩记录（重复记录不会重复保存），同步 $fetchedPlayCountCharts 张谱面的 PC" else "") +
        (if (activityWarnings.isNotEmpty()) "\n部分记录未同步，原有数据已保留：\n${activityWarnings.joinToString("\n")}" else "")

private fun MaimaiUploadResult.safeSummary(): String =
    "platform=${platform.name} success=$success status=$statusCode uploaded=$uploadedScoreCount " +
        "updated=$updatedCount created=$createdCount " +
        "cloudOnly=${syncDiff?.cloudOnly?.size ?: 0} localOnly=${syncDiff?.localOnly?.size ?: 0} " +
        "mismatched=${syncDiff?.valueMismatches?.size ?: 0}"

private fun MaimaiUploadResult.summaryText(): String =
    "${platform.displayName}: ${displayStatusText()}，" +
        "$uploadedScoreCount 条成绩，HTTP $statusCode，$message"

private fun MaimaiUploadResult.toUploadRunStatus(): UploadRunStatus =
    when {
        success -> UploadRunStatus.Success
        hasCloudLocalDiff -> UploadRunStatus.CloudMismatch
        else -> UploadRunStatus.Failed
    }

private fun MaimaiUploadResult.displayStatusText(): String =
    when {
        success -> "成功"
        hasCloudLocalDiff -> "已上传，校验不一致"
        else -> "失败"
    }

private fun startVpnService(context: Context) {
    val intent = Intent(context, LocalVpnService::class.java)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
    } else {
        context.startService(intent)
    }
}

private fun stopVpnService(context: Context) {
    context.startService(Intent(context, LocalVpnService::class.java).apply {
        action = LocalVpnService.DISCONNECT_INTENT
    })
    WahlapHookBridge.setVpnRunning(false)
}

private fun copyTextToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}

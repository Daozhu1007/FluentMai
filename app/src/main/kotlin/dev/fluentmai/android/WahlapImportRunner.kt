package dev.fluentmai.android

import android.content.Context
import android.util.Log
import dev.fluentmai.android.core.database.FluentMaiDatabase
import dev.fluentmai.android.core.database.FluentMaiRepository
import dev.fluentmai.android.core.database.RoomImportPersistence
import dev.fluentmai.android.core.importer.*
import dev.fluentmai.android.core.model.*
import dev.fluentmai.android.core.privacy.PrivacyRedactor

/** No Activity references: the service owns this runner and its database connection. */
internal class WahlapImportRunner(context: Context, private val observation: ImportDiagnosticCollector? = null) : AutoCloseable {
    private val observer: ImportTimingObserver = observation ?: ImportTimingObserver.None
    private val context = context.applicationContext
    private val database = FluentMaiDatabase.create(this.context)
    private val repository = FluentMaiRepository(database, observer)
    private val persistence = DiagnosticImportPersistence(RoomImportPersistence(database), observer)
    private val privacyRedactor = PrivacyRedactor()
    private val themePreferences = ThemePreferences(this.context)
    private val songCatalogClient = LxnsMaimaiSongCatalogClient(privacyRedactor)
    override fun close() = database.close()
    suspend fun runRealImport(
        authUrl: String,
        afterLoginAttempt: () -> Unit = {},
        onProgress: (ImportProgress) -> Unit = {},
        onAuthenticatedHome: () -> Unit = {},
    ): RealWahlapImportResult = withImportDiagnostics("微信捕获") { diagnostics ->
        diagnostics.record(importBackgroundDiagnostic(context))
        val efficientPc = themePreferences.efficientPc
        val pageProgress = ImportPageReporter(onProgress)
        onProgress(ImportProgress(ImportStage.Preparing, "正在登录 Wahlap"))
        val client = WahlapHttpScorePageClient(
            redactor = privacyRedactor,
            onPlayerHome = B50PlayerStore(context)::capture,
            onDiagnostic = diagnostics::record,
            onRequestAttempt = { category, label, log -> observation?.attempt(category, label, log) },
            attempt = WahlapAuthCaptureStore.consumeAttempt(authUrl),
            observer = observer,
            fetcher = WahlapResilientFetcher(nowMs = { android.os.SystemClock.elapsedRealtimeNanos() / 1_000_000 }),
        )
        try {
            try {
                observer.measure(DiagnosticStage.LOGIN_HOME) { client.login(authUrl) }
                onAuthenticatedHome()
            } finally {
                afterLoginAttempt()
            }
            onProgress(ImportProgress(ImportStage.Preparing, "正在加载曲库"))
            val catalog = fetchSongCatalogOrEmpty()
            val activity = observer.measure(DiagnosticStage.RECENT_RECORDS) {
                captureWahlapActivity(catalog, repository, diagnostics::record, onProgress, observer) { client.fetchActivityPage(it) }
            }
            val pcIndex = WahlapPlayCountIndex()
            val realImportAdapter = RealWahlapImportAdapter(
                parser = WahlapFixtureParser(songCatalog = catalog),
                sanitizeFailure = privacyRedactor::redact,
                observer = observer,
            )
            val result = realImportAdapter.importFetchedPages(
                source = "wahlap:real-device",
                pageProvider = WahlapScorePageProvider { difficulty ->
                    pageProgress.page(ImportStage.Scores, difficulty.ordinal, Difficulty.entries.size, "${difficulty.name} 成绩页") {
                        observer.measure(DiagnosticStage.valueOf(difficulty.name)) { client.fetchScorePage(difficulty).also { html ->
                            observer.measure(DiagnosticStage.PARSING) { pcIndex.addPage(html, difficulty, catalog) }
                        } }
                    }
                },
                supplementalPageProvider = WahlapSupplementalPageProvider {
                    pageProgress.page(ImportStage.Supplemental, 0, WahlapSupplementalPages.pages.size, "Rating 对象补充页",
                        complete = { it.failures.isEmpty() && it.pages.size == WahlapSupplementalPages.pages.size }) { observer.measure(DiagnosticStage.SUPPLEMENTAL) { client.fetchSupplementalScorePages() } }
                },
                persistence = persistence,
            )
            diagnostics.record("PC数爬取规则：${if (efficientPc) "效率优先" else "全部爬取"}")
            observation?.result(result.copy(fetchedPlayRecordCount = activity.recordCount, failedPlayPageCount = activity.failedPages, activityCaptureAttempted = true))
            val pc = observer.measure(DiagnosticStage.PC_CAPTURE) { if (efficientPc) captureEfficientPc(catalog, pcIndex.targets(), client::fetchActivityPage,
                repository::savePlayCounts, {}, diagnostics::record, onPageProgress = onProgress, observer = observer)
            else captureFullPlayCounts(pcIndex, result, client::fetchMusicDetail, client::fetchScorePage, catalog, onProgress, diagnostics::record) }
            if (pc.warnings.isNotEmpty()) observation?.stageFailure(DiagnosticStage.PC_CAPTURE, DiagnosticFailure.PC_CAPTURE)
            result.copy(fetchedPlayRecordCount = activity.recordCount, failedPlayPageCount = activity.failedPages, activityCaptureAttempted = true,
                fetchedPlayCountCharts = pc.chartCount, activityWarnings = activity.warnings + pc.warnings).also { observation?.result(it) }
        } finally {
            // Close the complete OAuth attempt even when login or its shutdown callback failed.
            client.close()
        }
    }

    suspend fun runCookieImport(cookieInput: String, onProgress: (ImportProgress) -> Unit = {}): RealWahlapImportResult = withImportDiagnostics("手动 Cookie") { diagnostics ->
        diagnostics.record(importBackgroundDiagnostic(context))
        val efficientPc = themePreferences.efficientPc
        val pageProgress = ImportPageReporter(onProgress)
        onProgress(ImportProgress(ImportStage.Preparing, "正在验证凭据并加载曲库"))
        val credentials = WahlapCookieImportCredentials.parse(cookieInput)
        val catalog = fetchSongCatalogOrEmpty()
        val realImportAdapter = RealWahlapImportAdapter(
            parser = WahlapFixtureParser(songCatalog = catalog),
            sanitizeFailure = privacyRedactor::redact,
            observer = observer,
        )
        val client = WahlapManualCookieScorePageClient(
            credentials = credentials,
            fetcher = WahlapResilientFetcher(nowMs = { android.os.SystemClock.elapsedRealtimeNanos() / 1_000_000 }),
            redactor = privacyRedactor,
            onPlayerHome = B50PlayerStore(context)::capture,
            onDiagnostic = diagnostics::record,
            onRequestAttempt = { category, label, log -> observation?.attempt(category, label, log) },
        )
        try {
            observer.measure(DiagnosticStage.LOGIN_HOME) {
                try { client.validateLogin() }
                catch (error: Exception) {
                    if (generateSequence<Throwable>(error) { it.cause }.take(20).any { it is WahlapAuthFailurePageException })
                        observation?.failure(DiagnosticFailure.AUTHORIZATION_REJECTED)
                    throw error
                }
            }
            val activity = observer.measure(DiagnosticStage.RECENT_RECORDS) {
                captureWahlapActivity(catalog, repository, diagnostics::record, onProgress, observer) { client.fetchActivityPage(it) }
            }
            val pcIndex = WahlapPlayCountIndex()
            val result = realImportAdapter.importFetchedPages(
                source = "wahlap:manual-cookie",
                pageProvider = WahlapScorePageProvider { difficulty ->
                    pageProgress.page(ImportStage.Scores, difficulty.ordinal, Difficulty.entries.size, "${difficulty.name} 成绩页") {
                        observer.measure(DiagnosticStage.valueOf(difficulty.name)) { client.fetchScorePage(difficulty).also { html ->
                            observer.measure(DiagnosticStage.PARSING) { pcIndex.addPage(html, difficulty, catalog) }
                        } }
                    }
                },
                supplementalPageProvider = WahlapSupplementalPageProvider {
                    pageProgress.page(ImportStage.Supplemental, 0, WahlapSupplementalPages.pages.size, "Rating 对象补充页",
                        complete = { it.failures.isEmpty() && it.pages.size == WahlapSupplementalPages.pages.size }) { observer.measure(DiagnosticStage.SUPPLEMENTAL) { client.fetchSupplementalScorePages() } }
                },
                persistence = persistence,
            )
            diagnostics.record("PC数爬取规则：${if (efficientPc) "效率优先" else "全部爬取"}")
            observation?.result(result.copy(fetchedPlayRecordCount = activity.recordCount, failedPlayPageCount = activity.failedPages, activityCaptureAttempted = true))
            val pc = observer.measure(DiagnosticStage.PC_CAPTURE) { if (efficientPc) captureEfficientPc(catalog, pcIndex.targets(), client::fetchActivityPage,
                repository::savePlayCounts, {}, diagnostics::record, onPageProgress = onProgress, observer = observer)
            else captureFullPlayCounts(pcIndex, result, client::fetchMusicDetail, client::fetchScorePage, catalog, onProgress, diagnostics::record) }
            if (pc.warnings.isNotEmpty()) observation?.stageFailure(DiagnosticStage.PC_CAPTURE, DiagnosticFailure.PC_CAPTURE)
            result.copy(fetchedPlayRecordCount = activity.recordCount, failedPlayPageCount = activity.failedPages, activityCaptureAttempted = true,
                fetchedPlayCountCharts = pc.chartCount, activityWarnings = activity.warnings + pc.warnings).also { observation?.result(it) }
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
        val pc = captureWahlapPlayCounts(targets, fetch, repository::savePlayCounts, onDiagnostic = onDiagnostic, onPageProgress = onProgress, observer = observer,
            refreshTarget = { target ->
                val html = refreshPage(target.sourceDifficulty)
                observer.measure(DiagnosticStage.PARSING) { dev.fluentmai.android.core.importer.WahlapPlayCountParser.targets(html, target.sourceDifficulty, catalog) }.targets
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

    private fun fetchSongCatalogOrEmpty(): MaimaiSongCatalog =
        runCatching { observer.measure(DiagnosticStage.SONG_CATALOG) { songCatalogClient.fetchCatalog() } }
            .getOrElse { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                Log.w(TAG, "LXNS song catalog unavailable: ${privacyRedactor.redact(error.message ?: error::class.java.simpleName)}")
                MaimaiSongCatalog.Empty
            }

    private companion object { const val TAG = "FluentMaiImport" }
}

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
internal class WahlapImportRunner(context: Context) : AutoCloseable {
    private val context = context.applicationContext
    private val database = FluentMaiDatabase.create(this.context)
    private val repository = FluentMaiRepository(database)
    private val persistence = RoomImportPersistence(database)
    private val privacyRedactor = PrivacyRedactor()
    private val themePreferences = ThemePreferences(this.context)
    private val songCatalogClient = LxnsMaimaiSongCatalogClient(privacyRedactor)
    override fun close() = database.close()
    suspend fun runRealImport(
        authUrl: String,
        afterLoginAttempt: () -> Unit = {},
        onProgress: (ImportProgress) -> Unit = {},
    ): RealWahlapImportResult = withImportDiagnostics("微信捕获") { diagnostics ->
        diagnostics.record(importBackgroundDiagnostic(context))
        val efficientPc = themePreferences.efficientPc
        val pageProgress = ImportPageReporter(onProgress)
        onProgress(ImportProgress(ImportStage.Preparing, "正在登录 Wahlap"))
        val client = WahlapHttpScorePageClient(redactor = privacyRedactor, onPlayerHome = B50PlayerStore(context)::capture, onDiagnostic = diagnostics::record)
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
        )
        val client = WahlapManualCookieScorePageClient(
            credentials = credentials,
            redactor = privacyRedactor,
            onPlayerHome = B50PlayerStore(context)::capture,
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

    private fun fetchSongCatalogOrEmpty(): MaimaiSongCatalog =
        runCatching { songCatalogClient.fetchCatalog() }
            .getOrElse { error ->
                Log.w(TAG, "LXNS song catalog unavailable: ${privacyRedactor.redact(error.message ?: error::class.java.simpleName)}")
                MaimaiSongCatalog.Empty
            }

    private companion object { const val TAG = "FluentMaiImport" }
}

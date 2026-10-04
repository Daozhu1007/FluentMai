package dev.fluentmai.android

import dev.fluentmai.android.core.importer.RealWahlapImportResult
import dev.fluentmai.android.core.importer.WahlapImportOutcome
import dev.fluentmai.android.core.model.ImportProgress
import kotlinx.coroutines.flow.MutableStateFlow

internal enum class ImportTaskPhase { Idle, Waiting, Running, AuthRetryAvailable, Finished }
internal enum class ImportFlowState { AUTHORIZING, IMPORTING, AUTH_RETRY_AVAILABLE, COMPLETE, PARTIAL, FAILED }
internal const val MAX_FRESH_AUTH_ATTEMPTS = 3

/** Only progress/results, never login credentials. Survives Activity recreation, not process death. */
internal data class ImportTaskState(
    val id: Long = 0,
    val phase: ImportTaskPhase = ImportTaskPhase.Idle,
    val progress: ImportProgress? = null,
    val pageFailed: Boolean = false,
    val result: RealWahlapImportResult? = null,
    val error: String? = null,
    val diagnostics: String? = null,
    val executionId: Long = 0,
    val authAttempt: Int = 0,
    val maxAuthAttempts: Int = MAX_FRESH_AUTH_ATTEMPTS,
    val authenticated: Boolean = false,
    val failureCategory: ImportFailureCategory? = null,
) {
    val busy: Boolean get() = phase == ImportTaskPhase.Waiting || phase == ImportTaskPhase.Running
    val flowState: ImportFlowState? get() = when (phase) {
        ImportTaskPhase.Idle -> null
        ImportTaskPhase.Waiting -> ImportFlowState.AUTHORIZING
        ImportTaskPhase.Running -> if (authenticated || authAttempt == 0) ImportFlowState.IMPORTING else ImportFlowState.AUTHORIZING
        ImportTaskPhase.AuthRetryAvailable -> ImportFlowState.AUTH_RETRY_AVAILABLE
        ImportTaskPhase.Finished -> when {
            complete -> ImportFlowState.COMPLETE
            succeeded -> ImportFlowState.PARTIAL
            else -> ImportFlowState.FAILED
        }
    }

    /**
     * A partial import persisted the difficulties it did fetch (failed ones keep their previous
     * local records), so it counts as succeeded — the UI reports it as PartialSuccess.
     * FAILED means nothing was persisted.
     */
    val succeeded: Boolean get() = result?.let {
        (it.fetchedDifficultyCount > 0 || it.parsedRecordCount > 0) && it.outcome != WahlapImportOutcome.FAILED
    } == true
    val complete: Boolean get() = succeeded && result?.outcome == WahlapImportOutcome.COMPLETE && !pageFailed &&
        result?.let { it.activityWarnings.isEmpty() && it.failedPlayPageCount == 0 } == true
    val completionTitle: String get() = when {
        phase == ImportTaskPhase.AuthRetryAvailable -> "华立登录未成功"
        complete -> "导入完成"
        succeeded -> "成绩已导入，部分数据未完整同步"
        else -> "导入未完成"
    }
}

internal object ImportTaskStore {
    val state = MutableStateFlow(ImportTaskState())
}

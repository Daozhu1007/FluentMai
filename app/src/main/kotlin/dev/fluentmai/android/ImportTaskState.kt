package dev.fluentmai.android

import dev.fluentmai.android.core.importer.RealWahlapImportResult
import dev.fluentmai.android.core.importer.WahlapImportOutcome
import dev.fluentmai.android.core.model.ImportProgress
import kotlinx.coroutines.flow.MutableStateFlow

internal enum class ImportTaskPhase { Idle, Waiting, Running, Finished }

/** Only progress/results, never login credentials. Survives Activity recreation, not process death. */
internal data class ImportTaskState(
    val id: Long = 0,
    val phase: ImportTaskPhase = ImportTaskPhase.Idle,
    val progress: ImportProgress? = null,
    val pageFailed: Boolean = false,
    val result: RealWahlapImportResult? = null,
    val error: String? = null,
    val diagnostics: String? = null,
) {
    val busy: Boolean get() = phase == ImportTaskPhase.Waiting || phase == ImportTaskPhase.Running

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
        complete -> "导入完成"
        succeeded -> "成绩已导入，部分数据未完整同步"
        else -> "导入未完成"
    }
}

internal object ImportTaskStore {
    val state = MutableStateFlow(ImportTaskState())
}

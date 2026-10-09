package dev.fluentmai.android.core.importer

import dev.fluentmai.android.core.model.DiagnosticStage
import kotlinx.coroutines.CancellationException

/** Narrow synchronous hooks around existing operations. No scheduling or import decisions. */
interface ImportTimingObserver {
    fun started(stage: DiagnosticStage) {}
    fun finished(stage: DiagnosticStage, failed: Boolean, interrupted: Boolean = false) {}
    /** Existing outer recovery loops, separate from categorized HTTP retries. */
    fun recoveryRetry(stage: DiagnosticStage) {}
    companion object { val None = object : ImportTimingObserver {} }
}

inline fun <T> ImportTimingObserver.measure(stage: DiagnosticStage, block: () -> T): T {
    started(stage)
    var failed = false
    var interrupted = false
    try { return block() }
    catch (error: Throwable) {
        failed = true
        interrupted = error is CancellationException
        throw error
    } finally { finished(stage, failed, interrupted) }
}

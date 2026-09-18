package dev.fluentmai.android

import dev.fluentmai.android.core.model.ImportPageState
import dev.fluentmai.android.core.model.ImportProgress
import dev.fluentmai.android.core.model.ImportStage
import kotlinx.coroutines.CancellationException

/** Reports existing sequential requests only; never schedules requests or changes retries. */
internal class ImportPageReporter(private val emit: (ImportProgress) -> Unit) {
    private val failures = mutableMapOf<ImportStage, Int>()
    suspend fun <T> page(stage: ImportStage, index: Int, total: Int, name: String,
        complete: (T) -> Boolean = { true }, block: suspend () -> T): T {
        val started = ImportProgress(stage, "正在读取$name", index, total, failures[stage] ?: 0, name)
        emit(started)
        try {
            val result = block()
            val success = complete(result)
            if (!success) failures[stage] = (failures[stage] ?: 0) + 1
            emit(started.copy(processedPages = index + 1, failedPages = failures[stage] ?: 0,
                pageState = if (success) ImportPageState.Complete else ImportPageState.Failed,
                detail = if (success) "页面已读取" else "页面未完整读取，详情见导入结果"))
            return result
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            failures[stage] = (failures[stage] ?: 0) + 1
            emit(started.copy(processedPages = index + 1, failedPages = failures.getValue(stage),
                pageState = ImportPageState.Failed, detail = "页面读取失败，详情见导入结果"))
            throw error
        }
    }
}

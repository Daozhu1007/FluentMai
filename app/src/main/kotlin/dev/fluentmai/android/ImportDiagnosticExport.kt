package dev.fluentmai.android

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import dev.fluentmai.android.core.model.ImportDiagnosticReport
import kotlinx.coroutines.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal object ImportDiagnosticExport {
    fun filename(report: ImportDiagnosticReport): String = "FluentMai-import-diagnostic-" +
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(report.createdAtEpochMs)) + ".json"
    fun write(context: Context, uri: Uri, report: ImportDiagnosticReport) {
        val bytes = ImportDiagnosticJson.encode(report).toByteArray(Charsets.UTF_8)
        require(bytes.isNotEmpty() && bytes.size <= ImportDiagnosticJson.MAX_BYTES)
        requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { it.write(bytes) }
    }
}

/** The picker callback reads the pinned app-private snapshot, not ephemeral Activity state. */
@Composable
internal fun rememberExportImportDiagnostic(context: Context, store: ImportDiagnosticStore): () -> Unit {
    val app = context.applicationContext
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        scope.launch {
            val message = withContext(NonCancellable + Dispatchers.IO) {
                try {
                    if (uri == null) null else {
                        val report = store.pendingExport() ?: return@withContext "待导出的诊断已不可用，请重新导出"
                        ImportDiagnosticExport.write(app, uri, report)
                        "诊断报告已导出"
                    }
                } catch (_: Exception) { "诊断导出失败，请重新选择保存位置" }
                finally { store.clearExport() }
            }
            message?.let { Toast.makeText(app, it, Toast.LENGTH_LONG).show() }
        }
    }
    return {
        scope.launch {
            val report = withContext(Dispatchers.IO) { store.prepareExport() }
            if (report == null) Toast.makeText(app, "暂无可导出的诊断报告", Toast.LENGTH_SHORT).show()
            else try { launcher.launch(ImportDiagnosticExport.filename(report)) }
            catch (_: Exception) {
                store.clearExport()
                Toast.makeText(app, "无法打开系统保存窗口", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

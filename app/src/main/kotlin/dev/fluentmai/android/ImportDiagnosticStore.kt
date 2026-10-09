package dev.fluentmai.android

import android.content.Context
import dev.fluentmai.android.core.model.*
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.flow.MutableStateFlow

/** Bounded app-private snapshots, atomically replaced. No Room tables or credentials. */
internal class ImportDiagnosticStore(directory: File) {
    private val latestFile = File(directory, "import-diagnostic.json")
    private val activeFile = File(directory, "import-diagnostic-active.json")
    private val exportFile = File(directory, "import-diagnostic-export.json")
    val latest = MutableStateFlow<ImportDiagnosticReport?>(null)
    val storageFailed = MutableStateFlow(false)
    private var owner: Long? = null

    init {
        directory.mkdirs()
        listOf(latestFile, activeFile, exportFile).forEach { File(it.path + ".new").delete() }
        latest.value = read(latestFile)
        read(activeFile)?.let { interrupted ->
            // This is an interruption marker, never reconstructed successful progress or elapsed time.
            latest.value = interrupted
            if (persist(latestFile, interrupted)) activeFile.delete()
        }
    }
    @Synchronized fun begin(executionId: Long, mode: DiagnosticImportMode) {
        owner = executionId
        persist(activeFile, ImportDiagnosticReport(appVersion = APP_VERSION, createdAtEpochMs = System.currentTimeMillis(),
            mode = mode, outcome = null, termination = DiagnosticTermination.INTERRUPTED, totalDurationMs = null,
            executionDurationMs = null, authorizationWaitDurationMs = null, unattributedDurationMs = null,
            stages = DiagnosticStage.entries.map { DiagnosticStageTiming(it) }, requests = emptyList(),
            failures = setOf(DiagnosticFailure.PROCESS_INTERRUPTED)))
    }
    @Synchronized fun finish(executionId: Long, report: ImportDiagnosticReport): Boolean {
        if (owner != executionId) return false
        // Normalize through the allowlist even for in-process callers.
        val safe = ImportDiagnosticJson.decode(ImportDiagnosticJson.encode(report))
        val persisted = persist(latestFile, safe)
        latest.value = safe
        if (persisted) activeFile.delete()
        owner = null
        return true
    }
    /** Pins the chosen report while the system picker is open, including across recreation. */
    @Synchronized fun prepareExport(): ImportDiagnosticReport? = latest.value?.takeIf { persist(exportFile, it) }
    @Synchronized fun pendingExport(): ImportDiagnosticReport? = read(exportFile)
    @Synchronized fun clearExport() = exportFile.delete()
    private fun read(file: File): ImportDiagnosticReport? = try {
        file.inputStream().use { input ->
            val bytes = input.readBytesBounded(ImportDiagnosticJson.MAX_BYTES)
            ImportDiagnosticJson.decode(bytes.toString(Charsets.UTF_8))
        }
    } catch (_: Exception) { null }
    private fun persist(file: File, report: ImportDiagnosticReport): Boolean {
        val pending = File(file.path + ".new")
        return try {
            val bytes = ImportDiagnosticJson.encode(report).toByteArray(Charsets.UTF_8)
            pending.outputStream().use { stream -> stream.write(bytes); stream.fd.sync() }
            // Same-directory atomic replacement (Android API 26+); never degrade to a torn write.
            Files.move(pending.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            storageFailed.value = false
            true
        } catch (_: Exception) {
            pending.delete()
            storageFailed.value = true
            false
        }
    }
}

private fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(4096)
    while (true) {
        val size = read(buffer)
        if (size < 0) break
        require(output.size() + size <= limit)
        output.write(buffer, 0, size)
    }
    return output.toByteArray()
}

internal object ImportDiagnosticRuntime {
    private var instance: ImportDiagnosticStore? = null
    @Synchronized fun get(context: Context): ImportDiagnosticStore = instance ?: ImportDiagnosticStore(
        File(context.applicationContext.noBackupFilesDir, "import-diagnostics")).also { instance = it }
    internal fun resetForTest() { instance = null }
}

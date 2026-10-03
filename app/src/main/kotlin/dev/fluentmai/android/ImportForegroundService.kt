package dev.fluentmai.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import dev.fluentmai.android.core.model.ImportPageState
import dev.fluentmai.android.core.model.ImportProgress
import dev.fluentmai.android.core.model.ImportStage
import dev.fluentmai.android.vpn.core.LocalVpnService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.update

/** Owns the entire import, including waiting for WeChat. Never captures an Activity or UI scope. */
open class ImportForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var importJob: Job? = null
    private var waitTimeout: Job? = null
    private var lastNotificationAt = 0L
    private var taskId = 0L
    private val notifications get() = getSystemService(NotificationManager::class.java)

    override fun onCreate() {
        super.onCreate()
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "成绩导入", NotificationManager.IMPORTANCE_LOW))
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            WahlapHookBridge.capturedAuthUrls.collect { url ->
                if (ImportTaskStore.state.value.phase == ImportTaskPhase.Waiting) beginImport(url, cookie = false)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CANCEL_WAIT) {
            if (ImportTaskStore.state.value.phase == ImportTaskPhase.Waiting) {
                stopCapture()
                ImportTaskStore.state.value = ImportTaskState()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            return START_NOT_STICKY
        }
        if (ImportTaskStore.state.value.busy) return START_NOT_STICKY
        val cookie = intent?.action == COOKIE
        if (intent?.action != WAIT && !cookie) { stopSelf(); return START_NOT_STICKY }
        taskId = System.nanoTime()
        ImportTaskStore.state.value = ImportTaskState(id = taskId, phase = ImportTaskPhase.Waiting,
            progress = ImportProgress(ImportStage.Preparing, if (cookie) "准备导入" else "等待微信授权，请在微信打开授权链接"))
        try {
            startForeground(NOTIFICATION_ID, notification(ImportTaskStore.state.value))
            if (cookie) {
                val input = intent.getStringExtra(INPUT).orEmpty()
                intent.removeExtra(INPUT)
                if (input.isBlank()) throw IllegalArgumentException("请先输入 Cookie")
                beginImport(input, cookie = true)
            } else {
                WahlapHookBridge.finishImport()
                WahlapHookHttpService.start(this)
                startForegroundService(Intent(this, LocalVpnService::class.java))
                waitTimeout = scope.launch {
                    delay(10 * 60 * 1000L)
                    if (ImportTaskStore.state.value.phase == ImportTaskPhase.Waiting) {
                        fail("等待微信授权超时，请重新启动捕获")
                        finishService()
                    }
                }
            }
        } catch (error: Exception) {
            fail(sanitizeImportDiagnostic(error.message ?: "无法启动后台导入"))
            finishService()
        }
        // Never replay an expiring authorization or Cookie after a process restart.
        return START_NOT_STICKY
    }

    private fun beginImport(input: String, cookie: Boolean) {
        if (importJob?.isActive == true) return
        waitTimeout?.cancel()
        ImportTaskStore.state.update { it.copy(phase = ImportTaskPhase.Running,
            progress = ImportProgress(ImportStage.Preparing, "正在登录并准备同步")) }
        postProgress(force = true)
        importJob = scope.launch {
            val wakeLock = getSystemService(PowerManager::class.java).newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "FluentMai:Import")
            try {
                // Bounded to prevent an abandoned import holding the CPU indefinitely.
                wakeLock.acquire(2 * 60 * 60 * 1000L)
                val result = withContext(Dispatchers.IO) {
                    val progress: (ImportProgress) -> Unit = { value ->
                        ImportTaskStore.state.update {
                            if (it.id == taskId && it.phase == ImportTaskPhase.Running)
                                it.copy(progress = value, pageFailed = it.pageFailed || value.failedPages > 0)
                            else it
                        }
                        scope.launch { postProgress() }
                    }
                    executeImport(input, cookie, progress)
                }
                ImportTaskStore.state.update { state ->
                    if (state.id != taskId) return@update state
                    val completed = state.copy(phase = ImportTaskPhase.Finished, result = result,
                        error = result.failures.takeIf { it.isNotEmpty() }?.joinToString("; ") { "${it.difficulty}: ${it.message}" })
                    completed.copy(
                        progress = if (completed.complete) ImportProgress(ImportStage.Saving, "导入完成", 1, 1, pageState = ImportPageState.Complete)
                            else state.progress?.copy(detail = "爬取已结束，部分数据未完整同步", pageState = ImportPageState.Failed),
                        diagnostics = result.diagnosticDetails.takeIf { !completed.complete },
                    )
                }
            } catch (cancelled: CancellationException) {
                fail("导入服务已停止；已保存的数据保留，请重新导入补齐")
                throw cancelled
            } catch (error: Exception) {
                fail(sanitizeImportDiagnostic(error.message ?: "导入失败"),
                    (error as? ImportDiagnosticException)?.details ?: diagnosticException(error))
            } finally {
                if (wakeLock.isHeld) wakeLock.release()
                finishService()
            }
        }
    }

    protected open suspend fun executeImport(input: String, cookie: Boolean, progress: (ImportProgress) -> Unit): dev.fluentmai.android.core.importer.RealWahlapImportResult =
        WahlapImportRunner(applicationContext).use { runner ->
            if (cookie) {
                stopCapture()
                runner.runCookieImport(input, progress)
            } else runner.runRealImport(input, ::stopCapture, progress)
        }

    private fun fail(message: String, details: String? = null) {
        ImportTaskStore.state.update {
            if (it.id != taskId) it else it.copy(phase = ImportTaskPhase.Finished, error = message,
                diagnostics = details ?: message, progress = it.progress?.copy(detail = message, pageState = ImportPageState.Failed))
        }
    }

    private fun stopCapture() {
        WahlapAuthCaptureStore.discardPendingAttempt()
        // A VPN can remain system-bound after stopService; explicitly close its tunnel.
        startService(Intent(this, LocalVpnService::class.java).setAction(LocalVpnService.DISCONNECT_INTENT))
        WahlapHookHttpService.stop(this)
    }

    private fun finishService() {
        if (ImportTaskStore.state.value.id != taskId) return
        stopCapture()
        WahlapHookBridge.finishImport()
        stopForeground(STOP_FOREGROUND_REMOVE)
        runCatching { notifications.notify(NOTIFICATION_ID, notification(ImportTaskStore.state.value)) }
        stopSelf()
    }

    private fun postProgress(force: Boolean = false) {
        if (ImportTaskStore.state.value.id != taskId) return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastNotificationAt < 500) return
        lastNotificationAt = now
        runCatching { notifications.notify(NOTIFICATION_ID, notification(ImportTaskStore.state.value)) }
    }

    private fun notification(state: ImportTaskState): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .setAction("dev.fluentmai.android.OPEN_IMPORT")
            .putExtra(OPEN_IMPORT, true).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val progress = state.progress
        val title = when (state.phase) {
            ImportTaskPhase.Waiting -> "等待微信授权"
            ImportTaskPhase.Running -> "正在导入 · ${progress?.stage?.label.orEmpty()}"
            else -> state.completionTitle
        }
        val detail = if (state.phase == ImportTaskPhase.Running) {
            listOfNotNull(progress?.pageName, progress?.countLabel, progress?.percentageLabel).joinToString(" · ")
        } else if (state.phase == ImportTaskPhase.Waiting) "在微信打开授权链接，随后会自动继续导入"
        else "点击查看导入结果"
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title).setContentText(detail)
            .setStyle(Notification.BigTextStyle().bigText(detail))
            .setContentIntent(open).setOnlyAlertOnce(true).setOngoing(state.busy)
            // Progress contains no credentials; show it without unlocking the phone.
            // Keep the existing channel so the user's notification preferences are respected.
            .setAutoCancel(!state.busy).setVisibility(Notification.VISIBILITY_PUBLIC)
            .apply {
                if (android.os.Build.VERSION.SDK_INT >= 31) setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                if (state.busy) setProgress(1000, ((progress?.fraction ?: 0f) * 1000).toInt(), progress?.fraction == null)
                if (state.phase == ImportTaskPhase.Waiting) addAction(Notification.Action.Builder(null, "取消等待",
                    PendingIntent.getService(this@ImportForegroundService, 1,
                        Intent(this@ImportForegroundService, ImportForegroundService::class.java).setAction(CANCEL_WAIT),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build())
            }.build()
    }

    override fun onDestroy() {
        waitTimeout?.cancel()
        if (ImportTaskStore.state.value.id == taskId && ImportTaskStore.state.value.busy) {
            fail("导入服务已停止；已保存的数据保留，请重新导入补齐")
            stopCapture()
            WahlapHookBridge.finishImport()
        }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val OPEN_IMPORT = "open_import"
        private const val CHANNEL = IMPORT_NOTIFICATION_CHANNEL
        private const val NOTIFICATION_ID = 8290
        private const val WAIT = "wait_for_wechat"
        private const val COOKIE = "import_cookie"
        private const val CANCEL_WAIT = "cancel_import_wait"
        private const val INPUT = "input"
        fun start(context: Context, cookie: String? = null) {
            context.startForegroundService(Intent(context, ImportForegroundService::class.java)
                .setAction(if (cookie == null) WAIT else COOKIE).apply { if (cookie != null) putExtra(INPUT, cookie) })
        }
        fun cancelWaiting(context: Context) {
            if (ImportTaskStore.state.value.phase == ImportTaskPhase.Waiting)
                context.startService(Intent(context, ImportForegroundService::class.java).setAction(CANCEL_WAIT))
        }
    }
}

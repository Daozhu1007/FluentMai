package dev.fluentmai.android

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.os.Build
import android.os.PersistableBundle
import android.util.Log
import dev.fluentmai.android.core.privacy.PrivacyRedactor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Ordinary PackageManager launcher only; no private component, chat or message intent. */
internal object WechatLauncher {
    fun launch(activity: Activity): Boolean = try {
        val intent = if (activity.isFinishing || activity.isDestroyed) null else
            activity.packageManager.getLaunchIntentForPackage("com.tencent.mm")
        if (intent == null) false else {
            activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }
    } catch (_: Exception) { false }
}

internal object QuickAuthClipboard {
    fun copy(context: Context, url: String) {
        val clip = ClipData.newPlainText("FluentMai 微信授权链接", url)
        if (Build.VERSION.SDK_INT >= 33) clip.description.extras = PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
    }
}

internal object QuickAuthRuntime {
    var coordinator: QuickAuthCoordinator? = null
        internal set

    fun get(context: Context): QuickAuthCoordinator {
        coordinator?.let { return it }
        val app = context.applicationContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val ports = object : QuickAuthPorts {
            override fun currentTask() = ImportTaskStore.state.value
            override fun readiness() = WahlapHookBridge.captureReadiness.value
            override fun startCapture(requestId: Long, retry: ImportTaskState?, quick: Boolean) =
                ImportForegroundService.startHandoff(app, requestId, retry, quick)
            override fun cancelCapture(executionId: Long) {
                if (currentTask().executionId == executionId) ImportForegroundService.cancelWaiting(app)
            }
            override suspend fun generateAuthorize(executionId: Long): String = withContext(Dispatchers.IO) {
                WahlapWechatAuthUrlClient(PrivacyRedactor(), canAuthorize = {
                    currentTask().let { it.executionId == executionId && it.phase == ImportTaskPhase.Waiting } &&
                        readiness().readyFor(executionId)
                }).maimaiDxAuthUrl()
            }
            override fun copyToClipboard(url: String) {
                QuickAuthClipboard.copy(app, url)
            }
            override fun captureFailed(executionId: Long, category: ImportFailureCategory) = WahlapHookBridge.captureFailed(category, executionId)
        }
        return QuickAuthCoordinator(scope, ports, SystemClock::elapsedRealtime) { Log.i("FluentMaiQuickAuth", it) }.also { owner ->
            coordinator = owner
            scope.launch {
                combine(ImportTaskStore.state, WahlapHookBridge.captureReadiness) { task, _ -> task }
                    .collect { owner.taskChanged(it) }
            }
        }
    }
}

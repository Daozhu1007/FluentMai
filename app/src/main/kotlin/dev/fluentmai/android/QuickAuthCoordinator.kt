package dev.fluentmai.android

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import dev.fluentmai.android.core.model.DiagnosticAuthEvent
import dev.fluentmai.android.core.model.DiagnosticAuthMilestone

internal enum class QuickAuthPhase {
    Idle, RequestingVpnPermission, StartingCapture, WaitingCaptureReady,
    GeneratingAuthorize, CopyingClipboard, LaunchingWechat, AwaitingCallback,
    ManualWaiting, Importing, AuthRetryAvailable, Terminal,
}

internal data class QuickAuthState(
    val requestId: Long = 0,
    val executionId: Long = 0,
    val quick: Boolean = false,
    val phase: QuickAuthPhase = QuickAuthPhase.Idle,
    val message: String? = null,
) {
    val active: Boolean get() = phase !in setOf(QuickAuthPhase.Idle, QuickAuthPhase.Terminal, QuickAuthPhase.AuthRetryAvailable)
}

internal data class CaptureReadiness(val executionId: Long = 0, val vpn: Boolean = false, val http: Boolean = false) {
    fun readyFor(execution: Long) = execution != 0L && executionId == execution && vpn && http
}

internal enum class QuickAuthEvent(val label: String) {
    Requested("quick_auth_requested"), CaptureReady("capture_ready"), AuthorizeGenerated("authorize_generated"),
    ClipboardReady("clipboard_ready"), LaunchDispatched("wechat_launch_dispatched"),
    CallbackCaptured("callback_captured"), AuthenticatedHome("authenticated_home"), AuthRejected("auth_rejected"),
}

/** Accepts only fixed events and numeric durations, never credential-bearing text. */
internal class QuickAuthTiming(private val now: () -> Long, private val log: (String) -> Unit) {
    private val times = mutableMapOf<QuickAuthEvent, Long>()
    fun snapshot(): List<DiagnosticAuthMilestone> = times[QuickAuthEvent.Requested]?.let { requested ->
        times.map { (event, at) -> DiagnosticAuthMilestone(when (event) {
            QuickAuthEvent.Requested -> DiagnosticAuthEvent.REQUESTED
            QuickAuthEvent.CaptureReady -> DiagnosticAuthEvent.CAPTURE_READY
            QuickAuthEvent.AuthorizeGenerated -> DiagnosticAuthEvent.AUTHORIZE_GENERATED
            QuickAuthEvent.ClipboardReady -> DiagnosticAuthEvent.CLIPBOARD_READY
            QuickAuthEvent.LaunchDispatched -> DiagnosticAuthEvent.WECHAT_LAUNCH_DISPATCHED
            QuickAuthEvent.CallbackCaptured -> DiagnosticAuthEvent.CALLBACK_CAPTURED
            QuickAuthEvent.AuthenticatedHome -> DiagnosticAuthEvent.AUTHENTICATED_HOME
            QuickAuthEvent.AuthRejected -> DiagnosticAuthEvent.AUTH_REJECTED
        }, (at - requested).coerceAtLeast(0)) }
    } ?: emptyList()
    fun mark(event: QuickAuthEvent) {
        if (event in times) return
        val at = now()
        times[event] = at
        val metrics = mutableListOf("elapsed_ms=${at - times.getValue(QuickAuthEvent.Requested)}")
        fun duration(name: String, from: QuickAuthEvent) {
            times[from]?.let { metrics += "$name=${at - it}" }
        }
        when (event) {
            QuickAuthEvent.CaptureReady -> duration("request_to_capture_ready_ms", QuickAuthEvent.Requested)
            QuickAuthEvent.AuthorizeGenerated -> duration("capture_ready_to_authorize_generated_ms", QuickAuthEvent.CaptureReady)
            QuickAuthEvent.ClipboardReady -> duration("authorize_generated_to_clipboard_ms", QuickAuthEvent.AuthorizeGenerated)
            QuickAuthEvent.LaunchDispatched -> {
                duration("clipboard_to_wechat_launch_dispatch_ms", QuickAuthEvent.ClipboardReady)
                duration("authorize_generated_to_wechat_launch_dispatch_ms", QuickAuthEvent.AuthorizeGenerated)
            }
            QuickAuthEvent.CallbackCaptured -> duration("authorize_generated_to_callback_ms", QuickAuthEvent.AuthorizeGenerated)
            else -> Unit
        }
        log("QUICK_AUTH ${event.label} ${metrics.joinToString(" ")}")
    }
}

internal interface QuickAuthPorts {
    fun currentTask(): ImportTaskState
    fun readiness(): CaptureReadiness
    fun startCapture(requestId: Long, retry: ImportTaskState?, quick: Boolean)
    fun cancelCapture(executionId: Long)
    suspend fun generateAuthorize(executionId: Long): String
    fun copyToClipboard(url: String)
    fun captureFailed(executionId: Long, category: ImportFailureCategory)
}

/** Main-thread, process-scoped command owner. No Activity, URL or command is persisted to disk.
 * A resumed host is needed BEFORE generating; handoff is consumed before clipboard/launch.
 * Recreation can attach a new host but cannot replay a consumed handoff. */
internal class QuickAuthCoordinator(
    private val scope: CoroutineScope,
    private val ports: QuickAuthPorts,
    private val now: () -> Long,
    private val log: (String) -> Unit,
) {
    val state = MutableStateFlow(QuickAuthState())
    private var nextRequestId = 0L
    private var retry: ImportTaskState? = null
    private var generation: Job? = null
    private var timing: QuickAuthTiming? = null
    private var host: (() -> Boolean)? = null
    private var hostOwner: Any? = null
    fun diagnosticMilestones(executionId: Long): List<DiagnosticAuthMilestone> =
        if (state.value.executionId == executionId) timing?.snapshot().orEmpty() else emptyList()

    fun attachHost(owner: Any = this, launch: () -> Boolean) {
        hostOwner = owner
        host = launch
        taskChanged(ports.currentTask())
    }
    fun detachHost(owner: Any = this) { if (hostOwner === owner) { host = null; hostOwner = null } }

    fun request(quick: Boolean, permissionRequired: Boolean): Long? {
        val task = ports.currentTask()
        if (state.value.active || task.busy || (task.phase == ImportTaskPhase.AuthRetryAvailable && task.authAttempt >= task.maxAuthAttempts)) return null
        retry = task.takeIf { it.phase == ImportTaskPhase.AuthRetryAvailable }
        val id = ++nextRequestId
        timing = if (quick) QuickAuthTiming(now, log).also { it.mark(QuickAuthEvent.Requested) } else null
        state.value = QuickAuthState(id, quick = quick,
            phase = if (permissionRequired) QuickAuthPhase.RequestingVpnPermission else QuickAuthPhase.StartingCapture,
            message = "正在准备授权捕获…")
        if (!permissionRequired) startCapture(id)
        return id
    }

    fun permissionResult(requestId: Long, granted: Boolean) {
        if (state.value.requestId != requestId || state.value.phase != QuickAuthPhase.RequestingVpnPermission) return
        if (granted) startCapture(requestId)
        else terminal("没有获得 VPN 权限，无法捕获微信授权请求")
    }

    fun isStartRequested(requestId: Long): Boolean = state.value.let {
        it.requestId == requestId && it.executionId == 0L && it.phase == QuickAuthPhase.WaitingCaptureReady
    }

    private fun startCapture(requestId: Long) {
        state.value = state.value.copy(phase = QuickAuthPhase.WaitingCaptureReady)
        try { ports.startCapture(requestId, retry, state.value.quick) }
        catch (_: Exception) { terminal("无法启动授权捕获，请重新开始") }
    }

    fun taskChanged(task: ImportTaskState) {
        val current = ports.currentTask()
        if (task.executionId != current.executionId || task.phase != current.phase) return
        val s = state.value
        if (!s.active) return
        if (task.handoffRequestId != s.requestId || (s.executionId != 0L && s.executionId != task.executionId)) {
            if (s.executionId != 0L) terminal(null)
            return
        }
        state.value = s.copy(executionId = task.executionId)
        when (task.phase) {
            ImportTaskPhase.Waiting -> {
                if (s.phase != QuickAuthPhase.WaitingCaptureReady || !ports.readiness().readyFor(task.executionId)) return
                if (!s.quick) {
                    state.value = state.value.copy(phase = QuickAuthPhase.ManualWaiting, message = null)
                    return
                }
                if (host == null) return
                timing?.mark(QuickAuthEvent.CaptureReady)
                state.value = state.value.copy(phase = QuickAuthPhase.GeneratingAuthorize, message = "捕获已就绪，正在生成新授权…")
                generate(s.requestId, task.executionId)
            }
            ImportTaskPhase.Running -> callbackCaptured(task.executionId)
            ImportTaskPhase.AuthRetryAvailable -> {
                generation?.cancel()
                state.value = state.value.copy(phase = QuickAuthPhase.AuthRetryAvailable, message = null)
            }
            ImportTaskPhase.Finished -> terminal(null)
            else -> Unit
        }
    }

    private fun stillWaiting(requestId: Long, executionId: Long): Boolean = state.value.let {
        it.requestId == requestId && it.executionId == executionId && it.phase == QuickAuthPhase.GeneratingAuthorize
    } && ports.currentTask().let {
        it.executionId == executionId && it.handoffRequestId == requestId && it.phase == ImportTaskPhase.Waiting
    } && ports.readiness().readyFor(executionId)

    private fun generate(requestId: Long, executionId: Long) {
        generation = scope.launch {
            val url = try { ports.generateAuthorize(executionId) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (stillWaiting(requestId, executionId)) {
                    terminal("生成微信授权链接失败，请重新开始")
                    ports.captureFailed(executionId, ImportFailureCategory.AUTHORIZE_GENERATION)
                }
                return@launch
            }
            if (!stillWaiting(requestId, executionId)) return@launch
            timing?.mark(QuickAuthEvent.AuthorizeGenerated)
            // This transition claims the one-shot handoff before any external side effect.
            state.value = state.value.copy(phase = QuickAuthPhase.CopyingClipboard, message = "授权链接已生成，正在复制…")
            try { ports.copyToClipboard(url) }
            catch (_: Exception) {
                state.value = state.value.copy(phase = QuickAuthPhase.ManualWaiting, message = "复制失败，请使用“复制授权”后立即打开微信。")
                return@launch
            }
            timing?.mark(QuickAuthEvent.ClipboardReady)
            state.value = state.value.copy(phase = QuickAuthPhase.LaunchingWechat, message = "授权链接已复制，正在打开微信…")
            val launched = try { host?.invoke() == true } catch (_: Exception) { false }
            if (launched) timing?.mark(QuickAuthEvent.LaunchDispatched)
            state.value = state.value.copy(phase = QuickAuthPhase.AwaitingCallback, message = if (launched)
                "请立即粘贴发送并点开授权链接" else "授权链接已复制，请立即打开微信，粘贴发送并点开。")
        }
    }

    fun callbackCaptured(executionId: Long) {
        if (state.value.executionId != executionId || !state.value.active) return
        timing?.mark(QuickAuthEvent.CallbackCaptured)
        state.value = state.value.copy(phase = QuickAuthPhase.Importing, message = null)
    }
    fun authenticatedHome(executionId: Long) {
        if (state.value.executionId == executionId) timing?.mark(QuickAuthEvent.AuthenticatedHome)
    }
    fun authRejected(executionId: Long) {
        if (state.value.executionId == executionId) timing?.mark(QuickAuthEvent.AuthRejected)
    }
    fun cancel() {
        val s = state.value
        if (!s.active || s.phase == QuickAuthPhase.Importing) return
        terminal("已取消微信授权")
        val task = ports.currentTask()
        val execution = if (task.handoffRequestId == s.requestId) task.executionId else s.executionId
        if (execution != 0L) ports.cancelCapture(execution)
    }
    private fun terminal(message: String?) {
        generation?.cancel()
        state.value = state.value.copy(phase = QuickAuthPhase.Terminal, message = message)
        retry = null
    }
}

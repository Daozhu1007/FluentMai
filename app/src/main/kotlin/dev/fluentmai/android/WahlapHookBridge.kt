package dev.fluentmai.android

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

internal data class CaptureFailure(val executionId: Long, val category: ImportFailureCategory)

object WahlapHookBridge {
    val capturedAuthUrls = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val status = MutableStateFlow("Capture not started")
    val vpnRunning = MutableStateFlow(false)
    internal val captureReadiness = MutableStateFlow(CaptureReadiness())
    @Synchronized
    internal fun prepareCapture(executionId: Long) {
        captureReadiness.value = CaptureReadiness(executionId)
        vpnRunning.value = false
    }
    @Synchronized
    internal fun setHttpReady(executionId: Long, ready: Boolean) {
        if (captureReadiness.value.executionId != executionId) return
        captureReadiness.value = captureReadiness.value.copy(http = ready)
    }
    internal val captureFailures = MutableSharedFlow<CaptureFailure>(extraBufferCapacity = 4)
    internal fun captureFailed(category: ImportFailureCategory, executionId: Long = ImportTaskStore.state.value.executionId) {
        captureFailures.tryEmit(CaptureFailure(executionId, category))
    }

    private val importRunning = AtomicBoolean(false)

    @JvmStatic
    fun onAuthUrlCaptured(rawUrl: String) {
        onAuthRequestCaptured(rawUrl, "")
    }

    @JvmStatic
    @Synchronized
    fun onAuthRequestCaptured(rawUrl: String, rawRequestHeaders: String) {
        val capturedUri = runCatching { URI(rawUrl.trim()) }.getOrNull()
        val host = capturedUri?.host.orEmpty()
        val path = capturedUri?.path.orEmpty()
        val query = capturedUri?.rawQuery.orEmpty()
        if (!host.equals("tgk-wcaime.wahlap.com", ignoreCase = true) ||
            !path.contains("/wc_auth/oauth/callback/maimai-dx", ignoreCase = true) ||
            !query.contains("code=", ignoreCase = true)
        ) {
            if (rawUrl.contains("open.weixin.qq.com", ignoreCase = true)) {
                status.value = "Wechat authorize entry seen; waiting for Wahlap callback."
            }
            return
        }
        if (!importRunning.compareAndSet(false, true)) {
            status.value = "Auth request already captured; importing."
            return
        }

        val authUrl = rawUrl.trim()
        val replayHeaderCount = WahlapAuthCaptureStore.captureCallback(authUrl, rawRequestHeaders)
        if (replayHeaderCount == null) {
            importRunning.set(false)
            status.value = "No active OAuth attempt; open the hook link again."
            return
        }
        Log.i(TAG, "Emitting captured Wahlap auth URL immediately replayHeaderCount=$replayHeaderCount")
        status.value = "Captured Wahlap auth request; importing."
        if (!capturedAuthUrls.tryEmit(authUrl)) {
            WahlapAuthCaptureStore.discardPendingAttempt()
            importRunning.set(false)
            status.value = "Captured Wahlap auth request, but import queue was not ready."
            Log.w(TAG, "Captured Wahlap auth URL could not be emitted")
        }
    }

    @JvmStatic
    fun isImporting(): Boolean = importRunning.get()

    fun finishImport() {
        WahlapAuthCaptureStore.discardPendingAttempt()
        importRunning.set(false)
    }

    @JvmStatic
    @JvmOverloads
    @Synchronized
    fun setVpnRunning(running: Boolean, executionId: Long = ImportTaskStore.state.value.executionId) {
        if (captureReadiness.value.executionId != executionId) return
        if (!running && ImportTaskStore.state.value.executionId == executionId &&
            ImportTaskStore.state.value.phase == ImportTaskPhase.Waiting) {
            captureFailed(ImportFailureCategory.CAPTURE_VPN, executionId)
        }
        captureReadiness.value = captureReadiness.value.copy(vpn = running)
        vpnRunning.value = running
        status.value = if (running) {
            "Capture started. Open the hook link in WeChat."
        } else {
            "Capture stopped."
        }
    }

    @JvmStatic
    fun setStatus(message: String) {
        status.value = message
    }

    private const val TAG = "WahlapHookBridge"
}

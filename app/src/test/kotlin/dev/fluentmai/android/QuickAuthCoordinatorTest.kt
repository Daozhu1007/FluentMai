package dev.fluentmai.android

import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.CoroutineContext

/** Explicit queue/deferred completions: no device, sleeps, external HTTP or real OAuth. */
class QuickAuthCoordinatorTest {
    private class QueueDispatcher : CoroutineDispatcher() {
        val queue = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queue.addLast(block) }
        fun drain() { var n = 0; while (queue.isNotEmpty()) { check(n++ < 100); queue.removeFirst().run() } }
    }
    private val dispatcher = QueueDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val events = mutableListOf<String>()
    private val logs = mutableListOf<String>()
    private var task = ImportTaskState()
    private var ready = CaptureReadiness()
    private var requestedId = 0L
    private var startFailure = false
    private var authorizeFailure = false
    private var clipboardFailure = false
    private var launchFailure = false
    private var deferred: CompletableDeferred<Unit>? = null
    private var ignoresCancellation = false
    private var clock = 0L
    private var cancelCount = 0
    private val handoff = WahlapAuthCaptureHandoff()
    private val attempts = mutableListOf<WahlapOAuthAttempt>()
    private val ports = object : QuickAuthPorts {
        override fun currentTask() = task
        override fun readiness() = ready
        override fun startCapture(requestId: Long, retry: ImportTaskState?, quick: Boolean) {
            events += "start"
            requestedId = requestId
            if (startFailure) error("secret-start-url")
        }
        override fun cancelCapture(executionId: Long) { cancelCount++; handoff.discardPendingAttempt() }
        override suspend fun generateAuthorize(executionId: Long): String {
            events += "authorize"
            if (ignoresCancellation) withContext(NonCancellable) { deferred?.await() } else deferred?.await()
            if (authorizeFailure) error("secret-code-cookie")
            clock += 10
            return handoff.authorize(canAuthorize = { task.executionId == executionId && task.phase == ImportTaskPhase.Waiting }) {
                attempts += it
                "http://fixture/authorize?secret=fixture-${attempts.size}"
            }
        }
        override fun copyToClipboard(url: String) { if (clipboardFailure) error("secret-clipboard"); clock++; events += "clipboard" }
        override fun captureFailed(executionId: Long, category: ImportFailureCategory) {
            events += "failure:$category"
            handoff.discardPendingAttempt()
            task = task.copy(phase = ImportTaskPhase.Finished, failureCategory = category)
        }
    }
    private val coordinator = QuickAuthCoordinator(scope, ports, { clock }, logs::add)
    private fun attach(owner: Any = coordinator) = coordinator.attachHost(owner) {
        events += "launch"
        if (launchFailure) error("secret-launch-url")
        clock++
        true
    }
    private fun request(permission: Boolean = false): Long {
        attach()
        return coordinator.request(true, permission)!!
    }
    private fun startExecution(execution: Long = 100, allReady: Boolean = false) {
        task = ImportTaskState(id = if (task.phase == ImportTaskPhase.AuthRetryAvailable) task.id else 50,
            executionId = execution, handoffRequestId = requestedId, quickAuth = true,
            phase = ImportTaskPhase.Waiting, authAttempt = task.authAttempt + 1)
        ready = CaptureReadiness(execution, allReady, allReady)
        coordinator.taskChanged(task)
        dispatcher.drain()
    }
    private fun captureReady() {
        clock += 5
        ready = CaptureReadiness(task.executionId, true, true)
        coordinator.taskChanged(task)
        dispatcher.drain()
    }
    @After fun cleanup() { scope.cancel(); handoff.discardPendingAttempt(); attempts.forEach { it.close() } }

    @Test fun waitsForBothListenersAndTunnelOfMatchingExecution() {
        request(); startExecution()
        assertEquals(listOf("start"), events)
        for (readiness in listOf(CaptureReadiness(99, true, true), CaptureReadiness(100, true, false), CaptureReadiness(100, false, true))) {
            ready = readiness; coordinator.taskChanged(task); dispatcher.drain()
            assertEquals(listOf("start"), events)
        }
        captureReady()
        assertEquals(listOf("start", "authorize", "clipboard", "launch"), events)
    }
    @Test fun permissionRequiredDoesNotStartOrAuthorizeBeforeGrant() {
        request(true); dispatcher.drain()
        assertTrue(events.isEmpty()); assertTrue(attempts.isEmpty())
        assertEquals(QuickAuthPhase.RequestingVpnPermission, coordinator.state.value.phase)
    }
    @Test fun grantAutomaticallyStartsThenOneGenerationCopyAndLaunch() {
        val id = request(true)
        coordinator.permissionResult(id, true); coordinator.permissionResult(id, true)
        startExecution(allReady = true)
        assertEquals(listOf("start", "authorize", "clipboard", "launch"), events)
        assertEquals(1, attempts.size)
    }
    @Test fun denialAndStalePermissionDeliveryGenerateNothing() {
        val id = request(true)
        coordinator.permissionResult(id + 1, true); coordinator.permissionResult(id, false)
        coordinator.permissionResult(id, true); dispatcher.drain()
        assertTrue(events.isEmpty()); assertTrue(attempts.isEmpty())
        assertEquals(QuickAuthPhase.Terminal, coordinator.state.value.phase)
    }
    @Test fun rapidDoubleTapReservesExactlyOneExecutionBeforeServiceResponds() {
        request()
        assertNull(coordinator.request(true, false)); assertNull(coordinator.request(false, false))
        startExecution(allReady = true)
        assertEquals(1, events.count { it == "start" }); assertEquals(1, attempts.size)
    }
    @Test fun recreationStateRedeliveryAndReturnNeverRepeatHandoff() {
        request(); startExecution(allReady = true)
        coordinator.detachHost()
        repeat(4) { attach(); coordinator.taskChanged(task); dispatcher.drain() }
        assertEquals(1, events.count { it == "launch" }); assertEquals(1, attempts.size)
    }
    @Test fun backgroundBeforeReadinessDefersGenerationUntilResumedHost() {
        request(); coordinator.detachHost(); startExecution(allReady = true)
        assertTrue(attempts.isEmpty())
        attach(); dispatcher.drain(); assertEquals(1, attempts.size)
    }
    @Test fun backgroundDuringGenerationCopiesOnceAndOffersManualLaunchFallback() {
        deferred = CompletableDeferred(); request(); startExecution(allReady = true)
        coordinator.detachHost(); deferred!!.complete(Unit); dispatcher.drain()
        assertEquals(listOf("start", "authorize", "clipboard"), events)
        assertTrue(coordinator.state.value.message!!.startsWith("授权链接已复制，请立即打开微信"))
        attach(); dispatcher.drain(); assertEquals(0, events.count { it == "launch" })
    }
    @Test fun priorHostPauseCannotDetachReplacementActivity() {
        val old = Any(); val replacement = Any()
        coordinator.request(true, false); attach(old); attach(replacement); coordinator.detachHost(old)
        startExecution(allReady = true)
        assertEquals(1, events.count { it == "launch" })
    }
    @Test fun retryKeepsLogicalTaskButCreatesDistinctExecutionAttemptAndHandoff() {
        request(); startExecution(allReady = true)
        val first = task
        handoff.discardPendingAttempt()
        coordinator.authRejected(first.executionId)
        task = first.copy(phase = ImportTaskPhase.AuthRetryAvailable)
        coordinator.taskChanged(task)
        coordinator.request(true, false)
        startExecution(200, true)
        assertEquals(first.id, task.id); assertNotEquals(first.executionId, task.executionId)
        assertEquals(2, task.authAttempt); assertEquals(2, attempts.size)
        assertNotSame(attempts[0], attempts[1]); assertNotSame(attempts[0].httpClient, attempts[1].httpClient)
        assertEquals(2, events.count { it == "launch" })
    }
    @Test fun stalePriorExecutionReadinessAndTaskDeliveryCannotHandoffNewExecution() {
        request(); startExecution(); val old = task
        coordinator.cancel(); task = old.copy(phase = ImportTaskPhase.Finished)
        coordinator.request(true, false); startExecution(200)
        ready = CaptureReadiness(old.executionId, true, true)
        coordinator.taskChanged(old); coordinator.taskChanged(task); dispatcher.drain()
        assertTrue(attempts.isEmpty())
        captureReady(); assertEquals(1, attempts.size)
    }
    @Test fun startupThrowAndTerminalCaptureFailureHaveZeroAuthorizeAndLaunch() {
        startFailure = true; request(); dispatcher.drain()
        assertEquals(QuickAuthPhase.Terminal, coordinator.state.value.phase); assertTrue(attempts.isEmpty())
        startFailure = false; coordinator.request(true, false); startExecution()
        task = task.copy(phase = ImportTaskPhase.Finished, failureCategory = ImportFailureCategory.CAPTURE_VPN)
        coordinator.taskChanged(task); captureReady()
        assertTrue(attempts.isEmpty()); assertFalse("launch" in events)
    }
    @Test fun generationFailureDoesNotCopyOrLaunchStaleAuthorization() {
        authorizeFailure = true; request(); startExecution(allReady = true)
        assertFalse("clipboard" in events); assertFalse("launch" in events)
        assertEquals(ImportFailureCategory.AUTHORIZE_GENERATION, task.failureCategory)
        assertFalse(logs.any { "secret" in it })
    }
    @Test fun launchThrowKeepsAttemptPendingAndDoesNotRegenerate() {
        launchFailure = true; request(); startExecution(allReady = true)
        val attempt = attempts.single()
        assertFalse(attempt.isClosed); assertTrue(handoff.isCurrent(attempt))
        assertEquals(ImportTaskPhase.Waiting, task.phase)
        assertEquals(QuickAuthPhase.AwaitingCallback, coordinator.state.value.phase)
        assertEquals("授权链接已复制，请立即打开微信，粘贴发送并点开。", coordinator.state.value.message)
        repeat(3) { coordinator.taskChanged(task); attach() }; dispatcher.drain()
        assertEquals(1, attempts.size); assertEquals(1, events.count { it == "launch" })
    }
    @Test fun clipboardFailureRetainsAttemptAndEnablesManualCopyWithoutLaunch() {
        clipboardFailure = true; request(); startExecution(allReady = true)
        assertTrue(handoff.isCurrent(attempts.single())); assertFalse("launch" in events)
        assertEquals(QuickAuthPhase.ManualWaiting, coordinator.state.value.phase)
    }
    @Test fun cancelBeforeGrantOrBeforeServiceAcknowledgesCannotStartLateCommand() {
        val id = request(true); coordinator.cancel(); coordinator.permissionResult(id, true)
        assertTrue(events.isEmpty())
        val next = coordinator.request(true, false)!!; coordinator.cancel()
        assertFalse(coordinator.isStartRequested(next)); assertTrue(attempts.isEmpty())
    }
    @Test fun cancelAfterServiceStartBeforeStateDeliveryCancelsMatchingExecution() {
        request()
        task = ImportTaskState(executionId = 100, handoffRequestId = requestedId, phase = ImportTaskPhase.Waiting)
        coordinator.cancel(); assertEquals(1, cancelCount)
        ready = CaptureReadiness(100, true, true); coordinator.taskChanged(task); dispatcher.drain()
        assertTrue(attempts.isEmpty())
    }
    @Test fun cancelDuringPreparationOrGenerationHasNoLateClipboardOrLaunch() {
        request(); startExecution(); coordinator.cancel(); captureReady()
        assertTrue(attempts.isEmpty())
        task = task.copy(phase = ImportTaskPhase.Finished)
        coordinator.request(true, false); deferred = CompletableDeferred(); startExecution(200, true)
        coordinator.cancel(); deferred!!.complete(Unit); dispatcher.drain()
        assertTrue(attempts.isEmpty()); assertFalse("clipboard" in events); assertFalse("launch" in events)
    }
    @Test fun terminalTaskDuringGenerationSuppressesCompletionAndCleansUp() {
        deferred = CompletableDeferred(); request(); startExecution(allReady = true)
        task = task.copy(phase = ImportTaskPhase.Finished)
        coordinator.taskChanged(task); deferred!!.complete(Unit); dispatcher.drain()
        assertEquals(QuickAuthPhase.Terminal, coordinator.state.value.phase)
        assertFalse("clipboard" in events); assertFalse("launch" in events)
    }
    @Test fun uncancellablePriorGenerationCannotMutateOrHandoffNextAttempt() {
        ignoresCancellation = true
        val oldCompletion = CompletableDeferred<Unit>()
        deferred = oldCompletion
        request(); startExecution(allReady = true)
        coordinator.cancel()
        task = task.copy(phase = ImportTaskPhase.Finished)
        deferred = null
        coordinator.request(true, false); startExecution(200, true)
        val currentAttempt = attempts.single()
        oldCompletion.complete(Unit); dispatcher.drain()
        assertTrue(handoff.isCurrent(currentAttempt)); assertFalse(currentAttempt.isClosed)
        assertEquals(1, attempts.size); assertEquals(1, events.count { it == "clipboard" }); assertEquals(1, events.count { it == "launch" })
    }
    @Test fun authoritativeReplacementExecutionTerminatesAbandonedCommand() {
        deferred = CompletableDeferred(); request(); startExecution(allReady = true)
        task = task.copy(executionId = 200, handoffRequestId = requestedId + 1)
        coordinator.taskChanged(task); dispatcher.drain()
        assertEquals(QuickAuthPhase.Terminal, coordinator.state.value.phase)
        assertFalse("clipboard" in events); assertFalse("launch" in events)
    }
    @Test fun callbackAndAuthenticationEmitDurationsOnceAndKeepImportOutOfCancellation() {
        request(); startExecution(allReady = true); clock += 100
        coordinator.callbackCaptured(task.executionId); coordinator.callbackCaptured(task.executionId)
        coordinator.authenticatedHome(task.executionId); coordinator.cancel()
        assertEquals(QuickAuthPhase.Importing, coordinator.state.value.phase); assertEquals(0, cancelCount)
        assertEquals(1, logs.count { "callback_captured" in it })
        assertTrue(logs.any { "authorize_generated_to_callback_ms=102" in it })
        assertTrue(logs.any { "authenticated_home" in it })
    }
    @Test fun thirdFreshRetryIsTheLastAndLogsContainOnlyFixedNamesAndDurations() {
        request(); startExecution(allReady = true)
        for (number in 1..3) {
            assertEquals(number, task.authAttempt)
            coordinator.callbackCaptured(task.executionId); coordinator.authRejected(task.executionId)
            handoff.discardPendingAttempt()
            task = task.copy(phase = if (number < 3) ImportTaskPhase.AuthRetryAvailable else ImportTaskPhase.Finished)
            coordinator.taskChanged(task)
            if (number < 3) { coordinator.request(true, false); startExecution((number + 1) * 100L, true) }
        }
        assertEquals(3, attempts.size)
        task = task.copy(phase = ImportTaskPhase.AuthRetryAvailable)
        assertNull(coordinator.request(true, false))
        assertTrue(logs.all { it.matches(Regex("QUICK_AUTH [a-z_]+( [a-z_]+=[0-9]+)+")) })
        for (secret in listOf("http", "secret", "fixture", "state=", "code=", "Cookie", "r=", "t=")) assertFalse(logs.any { secret in it })
    }
    @Test fun legacyCaptureWaitsForReadinessAndCannotRaceQuickExecution() {
        attach(); coordinator.request(false, false); startExecution()
        assertNull(coordinator.request(true, false)); captureReady()
        assertEquals(QuickAuthPhase.ManualWaiting, coordinator.state.value.phase)
        assertTrue(attempts.isEmpty()); assertFalse("clipboard" in events); assertFalse("launch" in events)
    }
}

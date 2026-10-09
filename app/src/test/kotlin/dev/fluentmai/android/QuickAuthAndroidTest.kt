package dev.fluentmai.android

import android.app.Activity
import android.content.Intent
import android.content.ClipboardManager
import android.content.ClipDescription
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class QuickAuthAndroidTest {
    @Test fun clipboardPlumbingWritesExactSentinelAndMarksPreviewSensitive() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        QuickAuthClipboard.copy(activity, "harmless-fixture-sentinel")
        val clip = activity.getSystemService(ClipboardManager::class.java).primaryClip!!
        assertEquals("harmless-fixture-sentinel", clip.getItemAt(0).text)
        assertTrue(clip.description.extras.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE))
    }
    @Test fun ordinaryLauncherIsResolvedDynamicallyWithoutChatExtrasOrTaskClearing() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage("com.tencent.mm")
        val info = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = "com.tencent.mm"
                name = "fixture.PublicLauncher"
                applicationInfo = ApplicationInfo().apply { packageName = "com.tencent.mm" }
            }
        }
        shadowOf(activity.packageManager).addResolveInfoForIntent(launcher, info)
        assertTrue(WechatLauncher.launch(activity))
        val dispatched = shadowOf(activity).nextStartedActivity
        assertEquals("com.tencent.mm", dispatched.component?.packageName)
        assertEquals("fixture.PublicLauncher", dispatched.component?.className)
        assertEquals(Intent.ACTION_MAIN, dispatched.action)
        assertTrue(dispatched.hasCategory(Intent.CATEGORY_LAUNCHER))
        assertEquals(0, dispatched.flags and (Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        assertNull(dispatched.getStringExtra(Intent.EXTRA_TEXT)); assertNull(dispatched.data)
    }
    @Test fun missingWechatReturnsFallbackWithoutStartingAnotherApp() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        assertFalse(WechatLauncher.launch(activity)); assertNull(shadowOf(activity).nextStartedActivity)
    }
    @Test fun readinessAndTeardownEventsAreScopedToCurrentExecution() {
        ImportTaskStore.state.value = ImportTaskState(executionId = 20, phase = ImportTaskPhase.Waiting)
        WahlapHookBridge.prepareCapture(20)
        WahlapHookBridge.setVpnRunning(true, 10); WahlapHookBridge.setHttpReady(10, true)
        assertFalse(WahlapHookBridge.captureReadiness.value.readyFor(20))
        WahlapHookBridge.setVpnRunning(true, 20)
        assertFalse(WahlapHookBridge.captureReadiness.value.readyFor(20))
        WahlapHookBridge.setHttpReady(20, true)
        assertTrue(WahlapHookBridge.captureReadiness.value.readyFor(20))
        WahlapHookBridge.setVpnRunning(false, 10); WahlapHookBridge.setHttpReady(10, false)
        assertTrue(WahlapHookBridge.captureReadiness.value.readyFor(20))
        WahlapHookBridge.setVpnRunning(false, 20)
        assertFalse(WahlapHookBridge.captureReadiness.value.readyFor(20))
        ImportTaskStore.state.value = ImportTaskState()
        WahlapHookBridge.prepareCapture(0)
    }
}

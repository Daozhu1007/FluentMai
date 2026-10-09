package dev.fluentmai.android

import android.os.PowerManager
import android.app.ActivityManager
import android.net.ConnectivityManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ImportBackgroundAccessTest {
    @Test fun notificationGrantIsRecheckedAndNeverRequestedWhileGranted() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(needsImportNotificationPermission(context))
        shadowOf(context).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        repeat(3) { assertFalse(needsImportNotificationPermission(context)) }
        shadowOf(context).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(needsImportNotificationPermission(context))
    }

    @Test @Config(sdk = [32]) fun olderAndroidDoesNotRequestRuntimeNotifications() {
        assertFalse(needsImportNotificationPermission(RuntimeEnvironment.getApplication()))
    }

    @Test fun backgroundDiagnosticsRemainReadOnlyWithAndWithoutBatteryExemption() {
        val context = RuntimeEnvironment.getApplication()
        val power = context.getSystemService(PowerManager::class.java)
        val activity = context.getSystemService(ActivityManager::class.java)
        val network = context.getSystemService(ConnectivityManager::class.java)
        shadowOf(power).setIsPowerSaveMode(true)
        shadowOf(activity).setBackgroundRestricted(true)
        for (exempt in listOf(false, true, false)) {
            shadowOf(power).setIgnoringBatteryOptimizations(context.packageName, exempt)
            val backgroundStatus = network.restrictBackgroundStatus
            repeat(3) {
                val diagnostic = importBackgroundDiagnostic(context)
                assertTrue(diagnostic.contains("省电豁免=$exempt"))
                assertTrue(diagnostic.contains("省电模式=true"))
                assertTrue(diagnostic.contains("后台受限=true"))
                assertEquals(exempt, power.isIgnoringBatteryOptimizations(context.packageName))
                assertTrue(power.isPowerSaveMode)
                assertTrue(activity.isBackgroundRestricted)
                assertEquals(backgroundStatus, network.restrictBackgroundStatus)
                assertNull(shadowOf(context).nextStartedActivity)
                assertNull(shadowOf(context).nextStartedService)
            }
        }
    }
}

package dev.fluentmai.android

import android.os.PowerManager
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

    @Test fun asksForExemptionOnlyUntilSystemGrantsIt() {
        val context = RuntimeEnvironment.getApplication()
        val power = context.getSystemService(PowerManager::class.java)
        shadowOf(power).setIgnoringBatteryOptimizations(context.packageName, false)
        assertTrue(needsImportBatteryExemption(context))
        shadowOf(power).setIgnoringBatteryOptimizations(context.packageName, true)
        assertFalse(needsImportBatteryExemption(context))
        assertTrue(importBackgroundDiagnostic(context).contains("省电豁免=true"))
    }
}

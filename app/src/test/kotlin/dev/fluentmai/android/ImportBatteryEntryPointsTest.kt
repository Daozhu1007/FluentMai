package dev.fluentmai.android

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Guard the actual application wiring, including Cookie import and the shared fresh retry. */
class ImportBatteryEntryPointsTest {
    private val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }
    private fun source(path: String) = File(root, path).readText()

    @Test fun onlyTheOptionalOnboardingBoundaryContainsBatterySettingsIntents() {
        val sourceRoot = File(root, "app/src/main/kotlin")
        val intentCallers = sourceRoot.walkTopDown().filter { it.extension == "kt" }
            .filter { it.readText().contains("Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS") ||
                it.readText().contains("Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS") }.toList()
        assertEquals(listOf("ImportBatteryOnboarding.kt"), intentCallers.map { it.name })
        assertFalse(sourceRoot.walkTopDown().filter { it.extension == "kt" }
            .any { it.readText().contains("rememberRequestImportBackgroundAccess") })
    }

    @Test fun quickRetryAndCookieEntriesRetainTheirOperationalPermissions() {
        val activity = source("app/src/main/kotlin/dev/fluentmai/android/MainActivity.kt")
        val quick = activity.substringAfter("val requestWechatAuthorization:").substringBefore("suspend fun refreshState")
        assertTrue(quick.contains("batteryAccess.runImport"))
        assertTrue(quick.contains("requestNotifications()"))
        assertTrue(quick.contains("requestAuthorization(true)"))
        assertTrue(activity.contains("onQuickAuthorization = requestWechatAuthorization"))
        assertTrue(activity.contains("VpnService.prepare(this)"))
        assertTrue(activity.contains("quickAuth.permissionResult(requestId, result.resultCode == Activity.RESULT_OK)"))
        val cookie = activity.substringAfter("fun startManualCookieImport()").substringBefore("LaunchedEffect(Unit)")
        assertTrue(cookie.contains("if (!batteryAccess.importAllowed) return"))
        assertTrue(cookie.contains("requestNotifications()"))
        assertFalse(cookie.contains("openSettings"))
        assertFalse(cookie.contains("requestAuthorization"))
    }

    @Test fun manifestKeepsBatteryVpnNotificationAndForegroundServicePermissions() {
        val manifest = source("app/src/main/AndroidManifest.xml")
        listOf("REQUEST_IGNORE_BATTERY_OPTIMIZATIONS", "POST_NOTIFICATIONS", "BIND_VPN_SERVICE",
            "FOREGROUND_SERVICE_DATA_SYNC", "FOREGROUND_SERVICE_SPECIAL_USE", "WAKE_LOCK")
            .forEach { assertTrue(it, manifest.contains("android.permission.$it")) }
        assertFalse(manifest.contains("com.miui"))
    }
}

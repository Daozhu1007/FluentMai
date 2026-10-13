package dev.fluentmai.android

import android.app.Activity
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.os.Looper
import android.Manifest
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.fluentmai.android.feature.settings.SettingsScreen
import dev.fluentmai.android.feature.settings.ThemeMode
import org.junit.Assert.*
import org.junit.Rule
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
// Exercise the app's target API, in a separate SDK sandbox from the existing
// API-33 bare-Activity diagnostic tests and their cached window recomposer.
@Config(sdk = [34])
class ImportBatteryOnboardingUiTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()
    // Register only in Robolectric, so both Debug and Release tests have a host
    // without adding a test Activity to either application APK's Manifest.
    @get:Rule val rules = RuleChain.outerRule(object : ExternalResource() {
        override fun before() {
            val context = RuntimeEnvironment.getApplication()
            val component = ComponentName(context.packageName, ComponentActivity::class.java.name)
            val packageManager = shadowOf(context.packageManager)
            packageManager.addActivityIfNotPresent(component).apply { enabled = true; exported = true }
            packageManager.addIntentFilterForActivity(component, IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            })
        }
    }).around(compose)
    private val visible = mutableStateOf(true)
    private val busy = mutableStateOf(false)
    private lateinit var access: ImportBatteryAccess
    private var imports = 0

    private fun content() {
        compose.setContent {
            MaterialTheme {
                access = rememberImportBatteryOnboarding(visible.value, busy.value)
                Column {
                    TextButton(onClick = { access.runImport { imports++ } }) { Text("fixture import") }
                    TextButton(onClick = access.openSettings) { Text("fixture settings") }
                }
            }
        }
        compose.waitUntil(10_000) { access.introductionChecked }
    }
    private fun settle() = compose.waitUntil(10_000) {
        shadowOf(Looper.getMainLooper()).idle()
        !access.savingChoice
    }
    private fun exempt(value: Boolean) = shadowOf(compose.activity.getSystemService(PowerManager::class.java))
        .setIgnoringBatteryOptimizations(compose.activity.packageName, value)
    private fun decision() = compose.activity.getSharedPreferences(BATTERY_PREFERENCES, Context.MODE_PRIVATE)
        .getString(BATTERY_DECISION, null)

    @Test fun firstIntroductionHasBothChoicesAndDoesNotStartOAuth() {
        content()
        compose.onNodeWithText(BATTERY_INTRODUCTION).assertIsDisplayed()
        compose.onNodeWithText("前往设置").assertIsEnabled()
        compose.onNodeWithText("暂不设置").assertIsEnabled()
        compose.runOnIdle { access.runImport { imports++ } }
        assertEquals(0, imports)
        assertNull(shadowOf(compose.activity).nextStartedActivityForResult)
    }

    @Test fun declineAllowsImportsAndThreeRevisitsNeverOpenSettings() {
        exempt(false)
        content()
        compose.onNodeWithText("暂不设置").performClick()
        settle()
        assertEquals("declined", decision())
        repeat(3) {
            compose.runOnIdle { visible.value = false }
            compose.waitForIdle()
            compose.runOnIdle { visible.value = true }
            compose.waitForIdle()
            compose.onNodeWithText(BATTERY_INTRODUCTION).assertDoesNotExist()
            compose.onNodeWithText("fixture import").performClick()
        }
        assertEquals(3, imports)
        assertEquals(false, importBatteryExemption(compose.activity))
        assertNull(shadowOf(compose.activity).nextStartedActivityForResult)
    }

    @Test fun settingsChoiceIsDurableBeforeLaunchAndDoubleTapIsIgnored() {
        exempt(false)
        content()
        compose.onNodeWithText("前往设置").performClick()
        settle()
        val launched = shadowOf(compose.activity).nextStartedActivityForResult
        assertNotNull(launched)
        assertEquals("settings", decision())
        assertTrue(access.settingsPending)
        compose.runOnIdle { access.openSettings(); access.openSettings(); access.runImport { imports++ } }
        compose.waitForIdle()
        assertNull(shadowOf(compose.activity).nextStartedActivityForResult)
        assertEquals(0, imports)
    }

    @Test fun returnWithoutGrantDoesNotResumeImportOrReopenSettings() = returnFromSettings(false)
    @Test fun returnWithGrantAllowsFreshUserActionOnly() = returnFromSettings(true)
    private fun returnFromSettings(grant: Boolean) {
        exempt(false)
        content()
        compose.onNodeWithText("前往设置").performClick()
        settle()
        val launched = shadowOf(compose.activity).nextStartedActivityForResult!!
        exempt(grant)
        compose.runOnIdle {
            compose.activity.activityResultRegistry.dispatchResult(launched.requestCode, Activity.RESULT_OK, Intent())
        }
        compose.waitForIdle()
        assertTrue(access.importAllowed)
        assertEquals(grant, access.exemption)
        assertEquals(0, imports)
        compose.onNodeWithText("fixture import").performClick()
        assertEquals(1, imports)
        assertNull(shadowOf(compose.activity).nextStartedActivityForResult)
        compose.onNodeWithText(BATTERY_INTRODUCTION).assertDoesNotExist()
    }

    @Test fun compositionRecreationBeforeChoiceDoesNotRepeatIntroduction() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MaterialTheme { access = rememberImportBatteryOnboarding(true, false) } }
        compose.waitUntil(10_000) { access.introductionChecked }
        compose.onNodeWithText(BATTERY_INTRODUCTION).assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.waitUntil(10_000) { access.introductionChecked }
        compose.onNodeWithText(BATTERY_INTRODUCTION).assertDoesNotExist()
        assertTrue(access.importAllowed)
    }

    @Test fun recreationWhileSettingsPendingWaitsForReturnWithoutRepeatingIntroduction() {
        exempt(false)
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MaterialTheme { access = rememberImportBatteryOnboarding(true, false) } }
        compose.waitUntil(10_000) { access.introductionChecked }
        compose.onNodeWithText("前往设置").performClick()
        settle()
        val launched = shadowOf(compose.activity).nextStartedActivityForResult!!
        restoration.emulateSavedInstanceStateRestore()
        compose.waitUntil(10_000) { access.introductionChecked }
        assertFalse(access.importAllowed)
        compose.onNodeWithText(BATTERY_INTRODUCTION).assertDoesNotExist()
        compose.runOnIdle {
            compose.activity.activityResultRegistry.dispatchResult(launched.requestCode, Activity.RESULT_CANCELED, Intent())
        }
        assertTrue(access.importAllowed)
        assertEquals(0, imports)
    }

    @Test fun homeThenResumeWithoutActivityResultReleasesGateWithoutStartingImport() {
        content()
        compose.onNodeWithText("前往设置").performClick()
        settle()
        assertTrue(access.settingsPending)
        compose.runOnIdle {
            val lifecycle = compose.activity.lifecycle as LifecycleRegistry
            lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
        assertTrue(access.importAllowed)
        assertEquals(0, imports)
        compose.onNodeWithText(BATTERY_INTRODUCTION).assertDoesNotExist()
    }

    @Test fun explicitAppSettingOpensSettingsAfterDeclineWithoutReintroducing() {
        ImportBatteryOnboardingStore(compose.activity).acknowledge(false)
        exempt(false)
        compose.setContent {
            MaterialTheme {
                access = rememberImportBatteryOnboarding(false, false)
                SettingsScreen(themeMode = ThemeMode.SYSTEM, onThemeModeChanged = {}, appVersion = "fixture",
                    quarantineCount = 0, records = emptyList(), importBatteryStatus = access.status,
                    onOpenImportBatterySettings = access.openSettings)
            }
        }
        compose.onNodeWithText("未获电池优化豁免（可正常导入）").assertExists()
        compose.onNodeWithText("前往系统设置").performScrollTo().performClick()
        settle()
        assertNotNull(shadowOf(compose.activity).nextStartedActivityForResult)
        compose.onNodeWithText(BATTERY_INTRODUCTION).assertDoesNotExist()
    }

    @Test fun missingSettingsActivitiesGiveNonblockingExplanation() {
        // Robolectric's Activity shadow can enforce PackageManager resolution, like Android.
        shadowOf(RuntimeEnvironment.getApplication()).checkActivities(true)
        exempt(false)
        content()
        compose.onNodeWithText("前往设置").performClick()
        settle()
        assertTrue(access.importAllowed)
        assertEquals("无法打开系统后台设置，可继续导入", ShadowToast.getTextOfLatestToast())
        compose.onNodeWithText("fixture import").performClick()
        assertEquals(1, imports)
    }

    @Test fun activeImportDefersIntroductionAndPreventsSettingsLaunch() {
        busy.value = true
        compose.setContent { MaterialTheme {
            access = rememberImportBatteryOnboarding(true, busy.value)
            Text("fixture active import")
        } }
        compose.waitForIdle()
        compose.onNodeWithText(BATTERY_INTRODUCTION).assertDoesNotExist()
        compose.runOnIdle { access.openSettings() }
        assertNull(shadowOf(compose.activity).nextStartedActivityForResult)
        compose.runOnIdle { busy.value = false }
        compose.waitForIdle()
        compose.waitUntil(10_000) { access.introductionChecked }
        compose.onNodeWithText(BATTERY_INTRODUCTION).assertIsDisplayed()
    }

    @Test fun rapidSettingsTapsBeforePreferenceCommitLaunchOnlyOnce() {
        ImportBatteryOnboardingStore(compose.activity).acknowledge(false)
        content()
        compose.runOnIdle { access.openSettings(); access.openSettings(); access.openSettings() }
        settle()
        assertNotNull(shadowOf(compose.activity).nextStartedActivityForResult)
        assertNull(shadowOf(compose.activity).nextStartedActivityForResult)
    }

    @Test fun notificationRuntimeRequestStillWorksAfterBatteryDecline() {
        ImportBatteryOnboardingStore(compose.activity).acknowledge(false)
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        compose.setContent {
            MaterialTheme {
                access = rememberImportBatteryOnboarding(true, false)
                val notifications = rememberRequestImportNotifications()
                TextButton(onClick = { access.runImport(notifications) }) { Text("fixture notifications") }
            }
        }
        compose.waitUntil(10_000) { access.introductionChecked }
        compose.onNodeWithText("fixture notifications").performClick()
        val request = shadowOf(compose.activity).lastRequestedPermission
        assertNotNull(request)
        assertArrayEquals(arrayOf(Manifest.permission.POST_NOTIFICATIONS), request.requestedPermissions)
        val permissionActivity = shadowOf(compose.activity).nextStartedActivityForResult
        assertNotNull(permissionActivity)
        assertEquals("android.content.pm.action.REQUEST_PERMISSIONS", permissionActivity.intent.action)
        generateSequence { shadowOf(compose.activity).nextStartedActivityForResult }
            .forEach { assertEquals("android.content.pm.action.REQUEST_PERMISSIONS", it.intent.action) }
    }
}

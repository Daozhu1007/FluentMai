package dev.fluentmai.android

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ImportBatteryOnboardingTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun exempt(value: Boolean) = shadowOf(context.getSystemService(PowerManager::class.java))
        .setIgnoringBatteryOptimizations(context.packageName, value)
    private fun persistedDecision(): String? = context.getSharedPreferences(BATTERY_PREFERENCES, Context.MODE_PRIVATE)
        .getString(BATTERY_DECISION, null)

    @Test fun introductionIsConsumedBeforeDisplayEvenWithoutAChoice() {
        assertTrue(ImportBatteryOnboardingStore(context).claimIntroduction())
        assertEquals("introduced", persistedDecision())
        repeat(3) { assertFalse(ImportBatteryOnboardingStore(context).claimIntroduction()) }
    }

    @Test fun declineDoesNotGrantExemptionOrRepeatTheIntroduction() {
        exempt(false)
        val store = ImportBatteryOnboardingStore(context)
        assertTrue(store.claimIntroduction())
        store.acknowledge(false)
        assertEquals("declined", persistedDecision())
        assertEquals(false, importBatteryExemption(context))
        repeat(3) { assertFalse(store.claimIntroduction()) }
        assertNull(shadowOf(context).nextStartedActivity)
    }

    @Test fun activityRecreationUsesTheSameAcknowledgment() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        ImportBatteryOnboardingStore(controller.get()).acknowledge(false)
        controller.recreate()
        assertFalse(ImportBatteryOnboardingStore(controller.get()).claimIntroduction())
        controller.pause().stop().destroy()
    }

    @Test fun acknowledgmentSurvivesReloadFromDiskWithoutTheProcessPreferencesCache() {
        val store = ImportBatteryOnboardingStore(context)
        store.claimIntroduction()
        store.acknowledge(true)
        val xml = File(context.applicationInfo.dataDir, "shared_prefs/$BATTERY_PREFERENCES.xml")
        assertTrue(xml.readText().contains(">settings<"))
        // Model a new process: discard Android's in-process SharedPreferencesImpl cache.
        ReflectionHelpers.setStaticField(Class.forName("android.app.ContextImpl"), "sSharedPrefsCache", null)
        assertFalse(ImportBatteryOnboardingStore(context).claimIntroduction())
        assertEquals("settings", persistedDecision())
    }

    @Test fun grantingThenRevokingDoesNotResetOnboarding() {
        val store = ImportBatteryOnboardingStore(context)
        store.claimIntroduction()
        store.acknowledge(true)
        for (grant in listOf(false, true, false)) {
            exempt(grant)
            assertEquals(grant, importBatteryExemption(context))
            assertFalse(ImportBatteryOnboardingStore(context).claimIntroduction())
        }
    }

    @Test fun explicitSettingsRequestUsesPackageUriWhenNotExempt() {
        exempt(false)
        val intents = mutableListOf<Intent>()
        assertTrue(openImportBatterySettings(context, intents::add))
        assertEquals(1, intents.size)
        assertEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, intents.single().action)
        assertEquals("package:${context.packageName}", intents.single().data.toString())
    }

    @Test fun alreadyExemptUserCanExplicitlyOpenTheSystemList() {
        exempt(true)
        val intents = mutableListOf<Intent>()
        assertTrue(openImportBatterySettings(context, intents::add))
        assertEquals(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, intents.single().action)
    }

    @Test fun unsupportedRequestFallsBackToPublicBatterySettings() {
        exempt(false)
        val intents = mutableListOf<Intent>()
        assertTrue(openImportBatterySettings(context) {
            intents += it
            if (it.action == Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS) throw ActivityNotFoundException()
        })
        assertEquals(listOf(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), intents.map { it.action })
    }

    @Test fun noResolvableSettingsReturnsWithoutStartingImportOrReactivatingOnboarding() {
        ImportBatteryOnboardingStore(context).acknowledge(true)
        assertFalse(openImportBatterySettings(context) { throw ActivityNotFoundException() })
        assertFalse(ImportBatteryOnboardingStore(context).claimIntroduction())
    }

    @Test fun absentPowerServiceHasAnHonestReadOnlyStatusAndPublicSettingsFallback() {
        val noPower = object : ContextWrapper(context) {
            override fun getSystemService(name: String): Any? = if (name == Context.POWER_SERVICE) null else super.getSystemService(name)
        }
        assertNull(importBatteryExemption(noPower))
        assertTrue(importBackgroundDiagnostic(noPower).contains("省电豁免=null"))
        assertEquals(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, importBatterySettingsIntents(noPower).single().action)
    }

    @Test fun importEntryCannotStartOAuthWhileIntroductionOrSettingsArePending() {
        val access = ImportBatteryAccess()
        var starts = 0
        access.runImport { starts++ }
        access.introductionChecked = true
        access.showingIntroduction = true
        access.runImport { starts++ }
        access.showingIntroduction = false
        access.savingChoice = true
        access.runImport { starts++ }
        access.savingChoice = false
        access.settingsPending = true
        access.runImport { starts++ }
        assertEquals(0, starts)
        access.settingsPending = false
        assertEquals(0, starts) // Returning never resumes an authorization.
        access.runImport { starts++ }
        assertEquals(1, starts)
    }

    @Test fun threeImportsAndRetryAfterDeclineDoNotLaunchBatterySettings() {
        ImportBatteryOnboardingStore(context).acknowledge(false)
        exempt(false)
        val access = ImportBatteryAccess().apply { introductionChecked = true }
        var imports = 0
        repeat(4) { access.runImport { imports++ } }
        assertEquals(4, imports)
        assertNull(shadowOf(context).nextStartedActivity)
        assertFalse(ImportBatteryOnboardingStore(context).claimIntroduction())
    }
}

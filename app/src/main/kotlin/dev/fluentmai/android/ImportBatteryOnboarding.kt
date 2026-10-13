package dev.fluentmai.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val BATTERY_INTRODUCTION = "部分手机可能会限制熄屏后的成绩导入。可以选择调整后台运行设置，或直接继续使用。"
internal const val BATTERY_PREFERENCES = "import_battery_onboarding"
internal const val BATTERY_DECISION = "decision"

/** Separate from general settings reset and Room. Call writes on Dispatchers.IO. */
internal class ImportBatteryOnboardingStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(BATTERY_PREFERENCES, Context.MODE_PRIVATE)

    fun claimIntroduction(): Boolean = synchronized(preferences) {
        if (preferences.contains(BATTERY_DECISION)) false
        else {
            // Consume before display: even destruction before a choice must not show it twice.
            check(preferences.edit().putString(BATTERY_DECISION, "introduced").commit())
            true
        }
    }

    fun acknowledge(openSettings: Boolean): Unit = synchronized(preferences) {
        check(preferences.edit().putString(BATTERY_DECISION, if (openSettings) "settings" else "declined").commit())
    }
}

/** Try public platform Activities; launching handles devices without a resolver. */
internal fun importBatterySettingsIntents(context: Context): List<Intent> = buildList {
    if (importBatteryExemption(context) == false) {
        add(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")))
    }
    add(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
}

internal fun openImportBatterySettings(context: Context, launch: (Intent) -> Unit): Boolean =
    importBatterySettingsIntents(context).any { intent -> runCatching { launch(intent) }.isSuccess }

internal class ImportBatteryAccess {
    var introductionChecked by mutableStateOf(false)
    var showingIntroduction by mutableStateOf(false)
    var savingChoice by mutableStateOf(false)
    var settingsPending by mutableStateOf(false)
    var exemption by mutableStateOf<Boolean?>(null)
    var openSettings: () -> Unit = {}

    val importAllowed: Boolean get() = introductionChecked && !showingIntroduction && !savingChoice && !settingsPending
    fun runImport(action: () -> Unit) { if (importAllowed) action() }
    val status: String get() = when (exemption) {
        true -> "已获电池优化豁免"
        false -> "未获电池优化豁免（可正常导入）"
        null -> "无法读取电池优化状态（可正常导入）"
    }
}

/** Onboarding never stores or resumes an import/OAuth continuation. */
@Composable
internal fun rememberImportBatteryOnboarding(importScreenVisible: Boolean, importBusy: Boolean): ImportBatteryAccess {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val store = remember(context) { ImportBatteryOnboardingStore(context) }
    val access = remember { ImportBatteryAccess() }
    val scope = rememberCoroutineScope()
    var settingsPending by rememberSaveable { mutableStateOf(false) }
    var settingsWasPaused by rememberSaveable { mutableStateOf(false) }
    access.settingsPending = settingsPending
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        settingsPending = false
        settingsWasPaused = false
        access.settingsPending = false
        access.exemption = importBatteryExemption(context)
    }
    DisposableEffect(lifecycle, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE && settingsPending) settingsWasPaused = true
            if (event == Lifecycle.Event.ON_RESUME) {
                access.exemption = importBatteryExemption(context)
                // Also handle Home -> app returns where the settings Activity has not finished.
                if (settingsWasPaused) {
                    settingsPending = false
                    settingsWasPaused = false
                    access.settingsPending = false
                }
            }
        }
        lifecycle.addObserver(observer)
        access.exemption = importBatteryExemption(context)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(importScreenVisible, importBusy) {
        if (importScreenVisible && !importBusy && !access.introductionChecked) {
            try {
                access.showingIntroduction = withContext(Dispatchers.IO) { store.claimIntroduction() }
            } catch (_: IllegalStateException) {
                Toast.makeText(context, "后台设置介绍暂不可用，可继续导入", Toast.LENGTH_SHORT).show()
            } finally {
                access.introductionChecked = true
            }
        }
    }

    fun choose(openSettings: Boolean) {
        if (access.savingChoice || access.settingsPending || importBusy) return
        access.savingChoice = true // Guard rapid taps before the coroutine runs.
        scope.launch(Dispatchers.Main.immediate) {
            val saved = withContext(Dispatchers.IO) { runCatching { store.acknowledge(openSettings) }.isSuccess }
            access.showingIntroduction = false
            access.savingChoice = false
            if (!saved) {
                Toast.makeText(context, "后台设置选择未能保存，可继续导入", Toast.LENGTH_SHORT).show()
            } else if (openSettings) {
                settingsPending = true
                settingsWasPaused = false
                access.settingsPending = true
                if (!openImportBatterySettings(context, launcher::launch)) {
                    settingsPending = false
                    access.settingsPending = false
                    Toast.makeText(context, "无法打开系统后台设置，可继续导入", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    access.openSettings = { choose(true) }
    if (access.showingIntroduction) {
        AlertDialog(
            onDismissRequest = { choose(false) },
            title = { Text("后台导入保护") },
            text = { Text(BATTERY_INTRODUCTION) },
            confirmButton = { TextButton(onClick = { choose(true) }, enabled = !access.savingChoice) { Text("前往设置") } },
            dismissButton = { TextButton(onClick = { choose(false) }, enabled = !access.savingChoice) { Text("暂不设置") } },
        )
    }
    return access
}

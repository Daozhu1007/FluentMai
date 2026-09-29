package dev.fluentmai.android

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext

internal fun needsImportBatteryExemption(context: Context): Boolean =
    !context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

internal fun importBackgroundDiagnostic(context: Context): String {
    val power = context.getSystemService(PowerManager::class.java)
    val activity = context.getSystemService(ActivityManager::class.java)
    val network = context.getSystemService(ConnectivityManager::class.java)
    return "后台环境：Android ${Build.VERSION.SDK_INT}；${Build.MANUFACTURER} ${Build.MODEL}；" +
        "省电豁免=${power.isIgnoringBatteryOptimizations(context.packageName)}；省电模式=${power.isPowerSaveMode}；" +
        "休眠=${power.isDeviceIdleMode}；后台受限=${if (Build.VERSION.SDK_INT >= 28) activity.isBackgroundRestricted else false}；" +
        "流量节省状态=${network.restrictBackgroundStatus}"
}

/** Use the platform request on every brand, and always trust the current system grant. */
@Composable
internal fun rememberRequestImportBackgroundAccess(afterRequest: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val after by rememberUpdatedState(afterRequest)
    var requesting by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        requesting = false
        after()
    }
    return {
        if (!requesting) {
            if (needsImportBatteryExemption(context)) {
                requesting = true
                // No vendor-specific screens or extra explanation dialogs. If unsupported,
                // continue importing rather than sending the user through unrelated settings.
                runCatching {
                    launcher.launch(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:${context.packageName}")))
                }.onFailure {
                    requesting = false
                    after()
                }
            } else after()
        }
    }
}

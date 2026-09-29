package dev.fluentmai.android

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext

internal const val IMPORT_NOTIFICATION_CHANNEL = "fluentmai_import"

internal fun needsImportNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT >= 33 &&
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

/** Lock-screen visibility is a system setting, not a runtime permission. Never prompt for it here. */
@Composable
internal fun rememberRequestImportNotifications(): () -> Unit {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(NotificationManager::class.java) }
    var requesting by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        requesting = false
    }
    return {
        manager.createNotificationChannel(NotificationChannel(IMPORT_NOTIFICATION_CHANNEL, "成绩导入", NotificationManager.IMPORTANCE_LOW))
        if (!requesting && needsImportNotificationPermission(context)) {
            requesting = true
            runCatching { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
                .onFailure { requesting = false }
        }
    }
}

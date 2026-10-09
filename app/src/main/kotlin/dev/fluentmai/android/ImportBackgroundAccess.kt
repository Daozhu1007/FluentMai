package dev.fluentmai.android

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.PowerManager

/** Battery exemption is optional; inspect it without requesting settings or gating imports. */
internal fun importBackgroundDiagnostic(context: Context): String {
    val power = context.getSystemService(PowerManager::class.java)
    val activity = context.getSystemService(ActivityManager::class.java)
    val network = context.getSystemService(ConnectivityManager::class.java)
    return "后台环境：Android ${Build.VERSION.SDK_INT}；${Build.MANUFACTURER} ${Build.MODEL}；" +
        "省电豁免=${power.isIgnoringBatteryOptimizations(context.packageName)}；省电模式=${power.isPowerSaveMode}；" +
        "休眠=${power.isDeviceIdleMode}；后台受限=${if (Build.VERSION.SDK_INT >= 28) activity.isBackgroundRestricted else false}；" +
        "流量节省状态=${network.restrictBackgroundStatus}"
}

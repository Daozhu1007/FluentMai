package dev.fluentmai.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.File
import kotlinx.coroutines.*

internal class AppUpdateViewModel : ViewModel() {
    var release by mutableStateOf<AppRelease?>(null)
    var busy by mutableStateOf(false)
    var progress by mutableFloatStateOf(0f)
    var error by mutableStateOf<String?>(null)
    var ready by mutableStateOf<File?>(null)
    private var checked = false
    private var checkJob: Job? = null
    private var job: Job? = null

    fun checkOnce(context: Context, enabled: Boolean) {
        if (!enabled) { checkJob?.cancel(); return }
        if (checked) return
        checked = true
        checkJob = viewModelScope.launch {
            val latest = AppUpdateClient.newest() ?: return@launch
            @Suppress("DEPRECATION")
            val current = AppVersion.parse(context.packageManager.getPackageInfo(context.packageName, 0).versionName) ?: return@launch
            if (latest.version > current) release = latest
        }
    }
    fun download(context: Context) {
        val candidate = release ?: return
        if (busy) return
        busy = true
        error = null
        job = viewModelScope.launch {
            try { ready = AppUpdateClient.download(context, candidate) { progress = it } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "下载或安全校验失败，请检查网络后重试。官方线路不可用时已尝试备用加速线路。" }
            finally { busy = false }
        }
    }
    fun dismiss() {
        job?.cancel()
        ready?.delete()
        ready = null
        release = null
    }
}

@Composable internal fun AppUpdatePrompt(enabled: Boolean) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val state: AppUpdateViewModel = viewModel()
    var askPermission by remember { mutableStateOf(false) }
    LaunchedEffect(enabled) { state.checkOnce(appContext, enabled) }
    fun install() {
        val file = state.ready ?: return
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            // Keep the file until the package installer has finished reading it.
            state.ready = null
            state.release = null
        } catch (_: Exception) { state.error = "无法打开安装器，请检查系统安装权限后重试。" }
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        askPermission = false
        if (context.packageManager.canRequestPackageInstalls()) install()
        else state.error = "未获得安装权限。允许 FluentMai 安装未知来源应用后可重试安装。"
    }
    LaunchedEffect(state.ready) {
        if (state.ready != null) {
            if (context.packageManager.canRequestPackageInstalls()) install() else askPermission = true
        }
    }
    state.release?.let { release ->
        AlertDialog(onDismissRequest = { if (!state.busy) state.dismiss() },
            title = { Text("发现新版本 · ${release.name}") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("是否下载并安装最新 FluentMai？包含 Beta 等预发布版本。")
                Text("GitHub 连接失败时会自动尝试公共加速线路；只传递公开下载地址，安装前校验软件签名。",
                    style = MaterialTheme.typography.bodySmall)
                if (state.busy) {
                    LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                    Text("正在下载 ${(state.progress * 100).toInt()}%")
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } },
            confirmButton = { TextButton(enabled = !state.busy, onClick = {
                if (state.ready != null) {
                    if (context.packageManager.canRequestPackageInstalls()) install() else askPermission = true
                } else state.download(appContext)
            }) { Text(if (state.ready != null) "安装" else "更新") } },
            dismissButton = { TextButton(onClick = { state.dismiss() }) { Text(if (state.busy) "取消下载" else "暂不更新") } })
    }
    if (askPermission && state.ready != null) AlertDialog(onDismissRequest = { askPermission = false },
        title = { Text("允许安装更新") }, text = { Text("安装包已通过校验。请在系统设置中允许 FluentMai 安装未知来源应用，返回后继续安装。") },
        confirmButton = { TextButton(onClick = {
            runCatching { permissions.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))) }
                .onFailure { askPermission = false; state.error = "系统不支持打开此权限页面，请在系统设置中允许安装未知来源应用。" }
        }) { Text("打开系统设置") } },
        dismissButton = { TextButton(onClick = { askPermission = false }) { Text("取消") } })
}

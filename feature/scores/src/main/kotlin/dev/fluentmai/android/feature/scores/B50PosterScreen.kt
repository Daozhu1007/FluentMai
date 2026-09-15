package dev.fluentmai.android.feature.scores

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.imageLoader
import coil.request.ImageRequest
import dev.fluentmai.android.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal class B50PosterViewModel : ViewModel() {
    var bitmap by mutableStateOf<Bitmap?>(null)
    var lastKey: Any? = null
    var status by mutableStateOf<String?>(null)
    var loading by mutableStateOf(false)
    var refresh by mutableIntStateOf(0)
    var images: Map<String, Bitmap> = emptyMap()
    var missing by mutableStateOf<List<Pair<String, String>>>(emptyList())
}

@Composable
fun B50PosterScreen(
    scores: List<ScoreRecord>, charts: List<ChartRecord>, majorVersions: List<MaimaiMajorVersion>,
    loadProfile: suspend () -> B50ProfileResult, profileRevision: Int, onBack: () -> Unit,
    scrollToTopRequestId: Int = 0, modifier: Modifier = Modifier,
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val state: B50PosterViewModel = viewModel(key = "experimental-b50-poster")
    val best = remember(scores, charts, majorVersions) { posterBestSet(scores, charts, majorVersions) }
    val loadCurrentProfile by rememberUpdatedState(loadProfile)
    val preferences = remember(context) { context.getSharedPreferences("b50_poster", android.content.Context.MODE_PRIVATE) }
    var night by rememberSaveable { mutableStateOf(preferences.getBoolean("night_background", false)) }
    var options by remember { mutableStateOf(B50DisplayOptions(
        preferences.getBoolean("show_trophy", true), preferences.getBoolean("show_nameplate", true),
        preferences.getBoolean("show_course", true), preferences.getBoolean("show_rank", true),
        preferences.getBoolean("show_background", true),
    )) }
    fun updateOptions(value: B50DisplayOptions) {
        options = value
        preferences.edit().putBoolean("show_trophy", value.trophy).putBoolean("show_nameplate", value.nameplate)
            .putBoolean("show_course", value.course).putBoolean("show_rank", value.rank)
            .putBoolean("show_background", value.background).apply()
    }
    var chooseBackground by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showDetails by rememberSaveable { mutableStateOf(false) }
    var confirmDownload by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var exportBitmap by remember { mutableStateOf<Bitmap?>(null) }
    fun saveConfirmed() {
        val bitmap = exportBitmap ?: return
        saving = true
        scope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { saveB50ToGallery(context, bitmap) } }
            saving = false
            exportBitmap = null
            Toast.makeText(context, if (result.isSuccess) "B50 大图已保存到相册 / FluentMai" else "保存失败，请检查相册权限和存储空间后重试。", Toast.LENGTH_LONG).show()
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) saveConfirmed() else {
            exportBitmap = null
            Toast.makeText(context, "未获得存储权限，未保存图片。", Toast.LENGTH_LONG).show()
        }
    }
    val scroll = rememberScrollState()
    var handledScroll by remember { mutableIntStateOf(scrollToTopRequestId) }
    LaunchedEffect(scrollToTopRequestId) {
        if (handledScroll != scrollToTopRequestId) { handledScroll = scrollToTopRequestId; scroll.animateScrollTo(0) }
    }
    LaunchedEffect(best, profileRevision, state.refresh, night, options) {
        val key = listOf(best, profileRevision, state.refresh, night, options)
        if (state.lastKey == key && state.bitmap != null) return@LaunchedEffect
        state.loading = true
        try {
            val profile = loadCurrentProfile()
            val requested = posterAssets(best, profile.player, options)
            val limiter = Semaphore(4)
            val images = coroutineScope {
                requested.keys.map { url -> async {
                    limiter.withPermit {
                        val bitmap = state.images[url] ?: withTimeoutOrNull(12_000) {
                            val path = url.lowercase()
                            val size = when {
                                "/jacket/" in path -> 160
                                "/icon/" in path -> 256
                                "/plate/" in path || "/nameplate/" in path || "/frame/" in path -> 1440
                                "/music_icon/" in path -> 96
                                else -> 480
                            }
                            context.imageLoader.execute(ImageRequest.Builder(context).data(url).allowHardware(false).size(size).build()).drawable?.toBitmap()
                        }
                        url to bitmap
                    }
                } }.awaitAll().mapNotNull { (url, bitmap) -> bitmap?.let { url to it } }.toMap()
            }
            state.images = images
            state.missing = requested.filterKeys { it !in images }.map { (url, label) -> label to url }
            state.bitmap = withContext(Dispatchers.Default) {
                val background = BitmapFactory.decodeResource(context.resources,
                    if (night) R.drawable.b50_background_night else R.drawable.b50_background_day)
                val bundled = B50BundledArtwork.load(context.resources, best.rating, profile.player, options)
                try { B50PosterRenderer().render(best, profile.player, background, images + bundled, options = options) }
                finally { background.recycle(); bundled.values.forEach { it.recycle() } }
            }
            state.lastKey = key
            state.status = listOfNotNull(
                if (profile.player == null) "请重新导入一次官方成绩，以补齐玩家装扮信息。" else null,
                if (best.all.size < 50) "当前有 ${best.oldBest.size} 张 B35、${best.newBest.size} 张 B15，未满的位置留空。" else null,
            ).joinToString("\n").takeIf { it.isNotBlank() }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { state.status = "大图生成失败，请点击刷新重试。" }
        finally { state.loading = false }
    }
    BackHandler(onBack = onBack)
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回首页") }
            Text("B50 大图", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            IconButton(onClick = { state.images = emptyMap(); state.refresh++ }, enabled = !state.loading) { Icon(Icons.Default.Refresh, "刷新大图") }
            IconButton(onClick = { confirmDownload = true }, enabled = state.bitmap != null && !state.loading && !saving) {
                Icon(Icons.Default.Download, "下载完整 B50 大图到相册")
            }
        }
        if (state.loading || saving) LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { chooseBackground = true }, enabled = !state.loading) { Text(if (night) "背景 · 经典夜晚" else "背景 · 经典白天") }
            OutlinedButton(onClick = { showSettings = true }) { Text("显示设置") }
        }
        Column(Modifier.weight(1f).verticalScroll(scroll).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (state.missing.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                Text("有 ${state.missing.size} 张云端图片暂未加载", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { showDetails = true }) { Text("查看详情", color = MaterialTheme.colorScheme.primary) }
            }
            state.bitmap?.let { bitmap ->
                Image(bitmap.asImageBitmap(), "玩家 B50 成绩大图", Modifier.fillMaxWidth().aspectRatio(bitmap.width.toFloat() / bitmap.height), contentScale = ContentScale.Fit)
            }
            if (state.bitmap == null && state.loading) Text("正在准备封面与玩家装扮…", Modifier.padding(24.dp))
        }
    }
    if (confirmDownload) AlertDialog(onDismissRequest = { confirmDownload = false }, title = { Text("下载 B50 大图") },
        text = { Text("是否将完整 B50 长图保存到相册？") },
        confirmButton = { TextButton(onClick = {
            confirmDownload = false
            exportBitmap = state.bitmap
            if (Build.VERSION.SDK_INT < 29 && ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
                permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            else saveConfirmed()
        }) { Text("下载") } },
        dismissButton = { TextButton(onClick = { confirmDownload = false }) { Text("取消") } })
    if (showSettings) AlertDialog(onDismissRequest = { showSettings = false }, title = { Text("显示设置") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                listOf("称号" to options.trophy, "姓名框" to options.nameplate, "段位" to options.course,
                    "友人对战等级" to options.rank, "背景" to options.background).forEachIndexed { index, (label, enabled) ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(label, Modifier.weight(1f))
                        Switch(enabled, { value -> updateOptions(when (index) {
                            0 -> options.copy(trophy = value); 1 -> options.copy(nameplate = value)
                            2 -> options.copy(course = value); 3 -> options.copy(rank = value)
                            else -> options.copy(background = value)
                        }) })
                    }
                }
                Text("背景开关控制玩家信息区的游戏收藏品背景，不影响经典白天／经典夜晚底图。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }, confirmButton = { TextButton(onClick = { showSettings = false }) { Text("完成") } })
    if (showDetails) AlertDialog(onDismissRequest = { showDetails = false }, title = { Text("未加载图片") },
        text = { Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            state.missing.forEach { (label, url) -> Column {
                Text(label, style = MaterialTheme.typography.titleSmall)
                Text(url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } }
        } }, confirmButton = { TextButton(onClick = { showDetails = false }) { Text("关闭") } })
    if (chooseBackground) AlertDialog(onDismissRequest = { chooseBackground = false }, title = { Text("选择背景") },
        text = { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(false to "经典白天", true to "经典夜晚").forEach { (value, label) ->
                Surface(onClick = { night = value; preferences.edit().putBoolean("night_background", value).apply(); chooseBackground = false },
                    modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(if (night == value) 2.dp else 1.dp, if (night == value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Image(painterResource(if (value) R.drawable.b50_background_night else R.drawable.b50_background_day), label,
                            Modifier.fillMaxWidth().aspectRatio(941f / 1672), contentScale = ContentScale.Fit)
                        Text(label, Modifier.padding(vertical = 10.dp), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        } }, confirmButton = { TextButton(onClick = { chooseBackground = false }) { Text("关闭") } })
}

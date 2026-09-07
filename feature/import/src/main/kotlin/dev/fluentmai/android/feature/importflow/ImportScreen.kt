package dev.fluentmai.android.feature.importflow

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

const val DIVING_FISH_REBUILD_CONFIRMATION_PHRASE = "我确认清空云端"

fun isDivingFishRebuildConfirmationAccepted(input: String): Boolean =
    input.trim() == DIVING_FISH_REBUILD_CONFIRMATION_PHRASE

@Composable
fun ImportScreen(
    realImportSummary: String?,
    importStatus: String,
    errorMessage: String?,
    hookUrl: String,
    hookStatus: String,
    isHookRunning: Boolean,
    divingFishToken: String,
    lxnsToken: String,
    uploadStatus: String,
    uploadSummary: String?,
    uploadErrorMessage: String?,
    uploadProgressText: String?,
    uploadProgressFraction: Float?,
    scoreCount: Int,
    isImporting: Boolean,
    isUploading: Boolean,
    isPreparingHookLink: Boolean,
    wahlapCookieInput: String,
    onStartHookCapture: () -> Unit,
    onStopHookCapture: () -> Unit,
    onCopyHookUrl: () -> Unit,
    onWahlapCookieInputChanged: (String) -> Unit,
    onImportWahlapCookie: () -> Unit,
    onDivingFishTokenChanged: (String) -> Unit,
    onLxnsTokenChanged: (String) -> Unit,
    onUploadDivingFish: () -> Unit,
    onRebuildDivingFish: () -> Unit,
    onUploadLxns: () -> Unit,
    scrollToTopRequestId: Int = 0,
    modifier: Modifier = Modifier,
) {
    val isBusy = isImporting || isUploading
    var showRebuildConfirmation by remember { mutableStateOf(false) }
    var rebuildConfirmationInput by remember { mutableStateOf("") }
    val scrollState = rememberScrollState()
    var handledScrollToTopRequestId by remember { mutableStateOf(scrollToTopRequestId) }
    LaunchedEffect(scrollToTopRequestId) {
        if (scrollToTopRequestId != handledScrollToTopRequestId) {
            handledScrollToTopRequestId = scrollToTopRequestId
            scrollState.animateScrollTo(0)
        }
    }

    if (showRebuildConfirmation) {
        AlertDialog(
            onDismissRequest = {
                showRebuildConfirmation = false
                rebuildConfirmationInput = ""
            },
            title = { Text(text = "重建水鱼数据") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "此操作将删除水鱼云端你的全部 maimai 成绩，并用本地 $scoreCount 条重建。此操作不可撤销。仅用于清理云端脏数据，日常同步请用“上传到水鱼”按钮。",
                    )
                    OutlinedTextField(
                        value = rebuildConfirmationInput,
                        onValueChange = { rebuildConfirmationInput = it },
                        label = { Text(text = "输入：$DIVING_FISH_REBUILD_CONFIRMATION_PHRASE") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRebuildConfirmation = false
                        rebuildConfirmationInput = ""
                        onRebuildDivingFish()
                    },
                    enabled = isDivingFishRebuildConfirmationAccepted(rebuildConfirmationInput),
                ) {
                    Text(text = "清空并重建")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showRebuildConfirmation = false
                        rebuildConfirmationInput = ""
                    },
                ) {
                    Text(text = "取消")
                }
            },
        )
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 1000.dp)
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
        Text(text = "导入与上传", style = MaterialTheme.typography.headlineSmall)

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small,
            tonalElevation = 1.dp,
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(text = "微信 Hook 导入", style = MaterialTheme.typography.titleMedium)
                Text(text = hookStatus)
                Text(text = hookUrl, style = MaterialTheme.typography.bodySmall)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = onStartHookCapture,
                        enabled = !isBusy && !isHookRunning,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(imageVector = Icons.Filled.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = "启动捕获")
                    }
                    OutlinedButton(
                        onClick = onCopyHookUrl,
                        enabled = !isBusy && !isPreparingHookLink,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(imageVector = Icons.Filled.ContentCopy, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = if (isPreparingHookLink) "生成中" else "复制授权")
                    }
                }
                OutlinedButton(
                    onClick = onStopHookCapture,
                    enabled = isHookRunning,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(imageVector = Icons.Filled.Stop, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "停止捕获")
                }
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small,
            tonalElevation = 1.dp,
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(text = "Wahlap Cookie 导入", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = wahlapCookieInput,
                    onValueChange = onWahlapCookieInputChanged,
                    enabled = !isBusy,
                    label = { Text(text = "Cookie / Reqable 请求头") },
                    visualTransformation = PasswordVisualTransformation(),
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = onImportWahlapCookie,
                    enabled = !isBusy && wahlapCookieInput.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(imageVector = Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = if (isImporting) "导入中" else "使用 Cookie 导入本地成绩")
                }
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small,
            tonalElevation = 1.dp,
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(text = "导入状态", style = MaterialTheme.typography.titleMedium)
                Text(text = importStatus)
                Text(text = realImportSummary ?: "启动捕获后，复制授权链接发到微信并点开。捕获到授权请求后会自动导入本地成绩。")
                errorMessage?.let { Text(text = it, color = MaterialTheme.colorScheme.error) }
            }
        }

        Text(text = "上传", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = divingFishToken,
            onValueChange = onDivingFishTokenChanged,
            enabled = !isBusy,
            label = { Text(text = "水鱼 Import Token") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = onUploadDivingFish,
            enabled = !isBusy && scoreCount > 0 && divingFishToken.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(imageVector = Icons.Filled.CloudUpload, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = if (isUploading) "上传中" else "上传到水鱼")
        }
        OutlinedButton(
            onClick = {
                rebuildConfirmationInput = ""
                showRebuildConfirmation = true
            },
            enabled = !isBusy && scoreCount > 0 && divingFishToken.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(imageVector = Icons.Filled.CloudUpload, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = "重建水鱼数据（清理异常）")
        }
        OutlinedTextField(
            value = lxnsToken,
            onValueChange = onLxnsTokenChanged,
            enabled = !isBusy,
            label = { Text(text = "落雪 LXNS User Token") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = onUploadLxns,
            enabled = !isBusy && scoreCount > 0 && lxnsToken.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(imageVector = Icons.Filled.CloudUpload, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = if (isUploading) "上传中" else "上传到落雪")
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small,
            tonalElevation = 1.dp,
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(text = "上传状态", style = MaterialTheme.typography.titleMedium)
                Text(text = uploadStatus)
                if (isUploading || uploadProgressText != null) {
                    val progress = uploadProgressFraction?.coerceIn(0f, 1f)
                    if (progress == null) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    uploadProgressText?.let { text ->
                        val prefix = progress?.let { "${(it * 100).toInt()}% · " }.orEmpty()
                        Text(text = prefix + text, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text(text = uploadSummary ?: "本地已有 $scoreCount 条成绩可上传。")
                    uploadErrorMessage?.let { Text(text = it, color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}

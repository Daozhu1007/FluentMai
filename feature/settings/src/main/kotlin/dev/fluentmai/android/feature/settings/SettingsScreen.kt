package dev.fluentmai.android.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.fluentmai.android.core.model.QuarantineRecord

@Composable
fun SettingsScreen(
    appVersion: String,
    quarantineCount: Int,
    records: List<QuarantineRecord>,
    onBack: (() -> Unit)? = null,
    scrollToTopRequestId: Int = 0,
    modifier: Modifier = Modifier,
) {
    var showQuarantine by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    var handledScrollToTopRequestId by remember { mutableStateOf(scrollToTopRequestId) }
    LaunchedEffect(scrollToTopRequestId) {
        if (scrollToTopRequestId != handledScrollToTopRequestId) {
            handledScrollToTopRequestId = scrollToTopRequestId
            listState.animateScrollToItem(0)
        }
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        LazyColumn(
            modifier = Modifier.widthIn(max = 1000.dp).fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                onBack?.let { callback ->
                    IconButton(onClick = callback) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回工具箱")
                    }
                }
                Text(text = "设置", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
        }
        item {
            SettingSection(
                title = "外观",
                primary = "跟随安卓系统深色模式",
                secondary = "应用会自动读取系统浅色/深色设置，成绩卡片、谱面卡片和底栏一起切换。",
            ) {
                AssistChip(onClick = {}, label = { Text("系统控制") })
            }
        }
        item {
            SettingSection(
                title = "上传",
                primary = "上传前自动停止 Hook/VPN",
                secondary = "向水鱼或 LXNS 上传前会先停止本地抓包服务，并优先尝试非 VPN 网络出口。",
            ) {
                AssistChip(onClick = {}, label = { Text("已启用") })
            }
        }
        item {
            DiagnosticSection(
                quarantineCount = quarantineCount,
                records = records,
                showQuarantine = showQuarantine,
                onToggleQuarantine = { showQuarantine = !showQuarantine },
            )
        }
        item {
            SettingSection(
                title = "隐私",
                primary = "Token 与导入页面仅在当前会话处理",
                secondary = "Token 不写入本地设置，新的原始 HTML 不写入文件或成绩库；日志和状态消息会隐藏 Cookie、完整授权 URL 与输入内容。",
            )
        }
            item {
                AboutSection(appVersion = appVersion)
            }
        }
    }
}

@Composable
private fun SettingSection(
    title: String,
    primary: String,
    secondary: String,
    trailing: @Composable (() -> Unit)? = null,
) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(text = title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(text = primary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(text = secondary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            trailing?.invoke()
        }
    }
}

@Composable
private fun DiagnosticSection(
    quarantineCount: Int,
    records: List<QuarantineRecord>,
    showQuarantine: Boolean,
    onToggleQuarantine: () -> Unit,
) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(text = "诊断", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(text = "隔离记录", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        text = if (quarantineCount == 0) "当前没有解析失败或未知格式记录。" else "$quarantineCount 条记录被保留用于排查导入问题。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FilterChip(
                    selected = showQuarantine,
                    onClick = onToggleQuarantine,
                    label = { Text(if (showQuarantine) "收起" else "查看") },
                    enabled = quarantineCount > 0,
                )
            }
            if (showQuarantine) {
                if (records.isEmpty()) {
                    Text(
                        text = "没有隔离记录。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    records.take(20).forEach { record ->
                        QuarantineRow(record)
                    }
                    if (records.size > 20) {
                        Text(
                            text = "仅显示前 20 条，完整记录仍保存在本地数据库。",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QuarantineRow(record: QuarantineRecord) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = record.reason,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "难度 ${record.difficulty?.name ?: "未知"} · 指纹 ${record.rawFingerprint.take(16)}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun AboutSection(appVersion: String) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(text = "关于", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(text = "FluentMai", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                text = "v$appVersion · 本地优先的舞萌 DX 成绩导入、查询与上传工具",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AboutLine(label = "开发者", value = "Limitime")
            AboutLine(label = "邮箱", value = "Daozhu1007@outlook.com")
            AboutLine(label = "项目", value = "Daozhu1007 / FluentMai-Android")
            Text(
                text = "FluentMai 是独立的社区工具，与 SEGA、华立、Diving Fish、LXNS 官方均无从属关系。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AboutLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

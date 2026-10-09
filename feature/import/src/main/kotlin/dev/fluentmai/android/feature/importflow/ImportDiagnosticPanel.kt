package dev.fluentmai.android.feature.importflow

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fluentmai.android.core.model.*

fun diagnosticStageLabel(stage: DiagnosticStage): String = when (stage) {
    DiagnosticStage.LOGIN_HOME -> "登录 / Home"
    DiagnosticStage.CALLBACK_PROCESSING -> "回调处理"
    DiagnosticStage.SONG_CATALOG -> "曲库准备"
    DiagnosticStage.RECENT_RECORDS -> "最近游玩记录"
    DiagnosticStage.SUPPLEMENTAL -> "Rating 补充"
    DiagnosticStage.PC_CAPTURE -> "PC 捕获"
    DiagnosticStage.PARSING -> "解析"
    DiagnosticStage.DATABASE_PERSISTENCE -> "数据库保存"
    else -> stage.name
}
private fun duration(ms: Long?): String = ms?.let { "$it ms" } ?: "未观测"
fun diagnosticSummary(report: ImportDiagnosticReport): String = buildString {
    append("FluentMai 导入诊断 · ")
    append(report.outcome?.name ?: when (report.termination) {
        DiagnosticTermination.AUTH_REJECTED -> "授权被拒绝（成绩导入未开始）"
        DiagnosticTermination.CANCELLED -> "已取消"
        DiagnosticTermination.INTERRUPTED -> "进程中断"
        else -> "未观测"
    })
    append(" · 导入耗时 ").append(duration(report.totalDurationMs))
    if (report.requests.isEmpty()) append(" · 请求未观测") else {
        append(" · 请求重试 ").append(report.requests.sumOf { it.retries })
        append(" / 失败 ").append(report.requests.sumOf { it.failedRequests })
    }
    append(" · 解析 ").append(report.counters.parsed ?: "未观测")
    append(" / 保存 ").append(report.counters.inserted?.let { it + (report.counters.updated ?: 0) } ?: "未观测")
    append(" / 隔离 ").append(report.counters.quarantined ?: "未观测")
}

@Composable
fun ImportDiagnosticPanel(report: ImportDiagnosticReport?, currentProgress: String?, storageFailed: Boolean,
    onExport: () -> Unit, onCopy: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("最近一次导入诊断", style = MaterialTheme.typography.titleMedium)
            currentProgress?.let { Text("当前进度：$it", color = MaterialTheme.colorScheme.primary) }
            if (report == null) Text("暂无已结束的导入诊断。导入结束后可导出报告。")
            else {
                if (currentProgress != null) Text("以下为上一次已结束的诊断，与当前导入分开显示。", style = MaterialTheme.typography.bodySmall)
                Text(diagnosticSummary(report))
                Text("导入方式：${if (report.mode == DiagnosticImportMode.WECHAT_OAUTH) "微信授权" else "手动 Cookie"}")
                Text("执行耗时：${duration(report.executionDurationMs)}")
                if (report.mode == DiagnosticImportMode.WECHAT_OAUTH) Text("授权等待：${duration(report.authorizationWaitDurationMs)}（单独计时）")
                Text("阶段耗时（含子操作，不可直接相加）", style = MaterialTheme.typography.labelMedium)
                report.stages.filter { it.durationMs != null }.maxByOrNull { it.durationMs!! }?.let {
                    Text("耗时最长：${diagnosticStageLabel(it.stage)} · ${duration(it.durationMs)}", style = MaterialTheme.typography.bodySmall)
                }
                Text(report.stages.joinToString("\n") { timing ->
                    "${diagnosticStageLabel(timing.stage)}：${duration(timing.durationMs)}" + when (timing.observation) {
                        DiagnosticObservation.FAILED -> " · 有失败"
                        DiagnosticObservation.INCOMPLETE -> " · 未结束"
                        else -> ""
                    } + if (timing.recoveryRetries > 0) " · 恢复重试 ${timing.recoveryRetries}" else ""
                }, style = MaterialTheme.typography.bodySmall)
                Text("未归属开销：${duration(report.unattributedDurationMs)}", style = MaterialTheme.typography.bodySmall)
                val affected = report.requests.filter { it.retries > 0 || it.failedRequests > 0 }
                if (affected.isNotEmpty()) Text(affected.joinToString("\n") {
                    "${diagnosticStageLabel(it.stage)} / ${it.label.name}：重试 ${it.retries} · 失败 ${it.failedRequests}"
                }, style = MaterialTheme.typography.bodySmall)
                if (report.failures.isNotEmpty()) Text("失败类别：${report.failures.joinToString { it.name }}", style = MaterialTheme.typography.bodySmall)
                if (report.metricsBounded) Text("诊断统计达到上限，部分指标已截断。")
            }
            if (storageFailed) Text("诊断本地保存失败；返回应用后的恢复可能不可用。", color = MaterialTheme.colorScheme.error)
            Button(onClick = onExport, enabled = report != null, modifier = Modifier.fillMaxWidth()) { Text("导出诊断报告") }
            TextButton(onClick = onCopy, enabled = report != null) { Text("复制诊断摘要") }
        }
    }
}

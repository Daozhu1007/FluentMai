package dev.fluentmai.android.feature.importflow

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.fluentmai.android.core.model.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ImportDiagnosticPanelTest {
    @get:Rule val compose = createComposeRule()
    private fun report() = ImportDiagnosticReport(appVersion = "0.3.0-beta", createdAtEpochMs = 0,
        mode = DiagnosticImportMode.MANUAL_COOKIE, outcome = DiagnosticOutcome.COMPLETE,
        termination = DiagnosticTermination.FINISHED, totalDurationMs = 100, executionDurationMs = 100,
        authorizationWaitDurationMs = null, unattributedDurationMs = 100,
        stages = DiagnosticStage.entries.map { DiagnosticStageTiming(it) }, requests = emptyList())
    @Test fun absentReportExplainsUnavailableAndDisablesExport() {
        compose.setContent { MaterialTheme { ImportDiagnosticPanel(null, null, false, {}, {}) } }
        compose.onNodeWithText("导出诊断报告").assertIsNotEnabled()
        compose.onNodeWithText("暂无已结束的导入诊断。导入结束后可导出报告。").assertExists()
    }
    @Test fun activeProgressAndPreviousResultRemainSeparatedAndExportIsExplicit() {
        var exports = 0
        var copies = 0
        val progress = mutableStateOf<String?>("MASTER")
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            ImportDiagnosticPanel(report(), progress.value, false, { exports++ }, { copies++ })
        } } }
        compose.onNodeWithText("当前进度：MASTER").assertExists()
        compose.onNodeWithText("以下为上一次已结束的诊断，与当前导入分开显示。").assertExists()
        compose.onNodeWithText("导出诊断报告").assertIsEnabled().performScrollTo().performClick()
        compose.onNodeWithText("复制诊断摘要").performScrollTo().performClick()
        assertEquals(1, exports); assertEquals(1, copies)
        compose.runOnIdle { progress.value = null }
        compose.onNodeWithText("当前进度：MASTER").assertDoesNotExist()
    }
    @Test fun authorizationRejectionAndInterruptionNeverRenderComplete() {
        compose.setContent { MaterialTheme { ImportDiagnosticPanel(report().copy(outcome = null,
            termination = DiagnosticTermination.AUTH_REJECTED), null, false, {}, {}) } }
        compose.onNodeWithText("授权被拒绝（成绩导入未开始）", substring = true).assertExists()
        compose.onNodeWithText("COMPLETE", substring = true).assertDoesNotExist()
    }
}

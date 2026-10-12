package dev.fluentmai.android.feature.importflow

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
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
        compose.setContent { MaterialTheme { ImportDiagnosticPanel(null, false, {}, {}) } }
        compose.onNodeWithText("暂无已结束的导入诊断。导入结束后可导出报告。").assertDoesNotExist()
        compose.onNodeWithText("导出诊断报告").assertDoesNotExist()
        compose.onNodeWithText("最近一次导入诊断").performClick()
        compose.onNodeWithText("导出诊断报告").assertIsNotEnabled()
        compose.onNodeWithText("复制诊断摘要").assertIsNotEnabled()
        compose.onNodeWithText("暂无已结束的导入诊断。导入结束后可导出报告。").assertExists()
    }
    @Test fun detailsDefaultToCollapsedAndBothActionsShareTheBottomRow() {
        var exports = 0
        var copies = 0
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            ImportDiagnosticPanel(report(), false, { exports++ }, { copies++ })
        } } }
        compose.onNodeWithText("导入方式：手动 Cookie").assertDoesNotExist()
        compose.onNodeWithText("导出诊断报告").assertDoesNotExist()
        compose.onNodeWithText("复制诊断摘要").assertDoesNotExist()
        compose.onNodeWithText("最近一次导入诊断").performClick()
        compose.onNodeWithText("导入方式：手动 Cookie").assertExists()
        compose.onNodeWithText("当前进度：", substring = true).assertDoesNotExist()
        compose.onNodeWithText("以下为上一次", substring = true).assertDoesNotExist()
        compose.onNodeWithText("导出诊断报告").assertIsEnabled().performScrollTo().performClick()
        compose.onNodeWithText("复制诊断摘要").performScrollTo().performClick()
        assertEquals(1, exports); assertEquals(1, copies)
        val exportBounds = compose.onNodeWithText("导出诊断报告").fetchSemanticsNode().boundsInRoot
        val copyBounds = compose.onNodeWithText("复制诊断摘要").fetchSemanticsNode().boundsInRoot
        assertEquals(exportBounds.top, copyBounds.top, 1f)
        assertEquals(exportBounds.height, copyBounds.height, 1f)
        compose.onNodeWithText("最近一次导入诊断").performScrollTo().performClick()
        compose.onNodeWithText("导入方式：手动 Cookie").assertDoesNotExist()
        compose.onNodeWithText("导出诊断报告").assertDoesNotExist()
    }
    @Test fun authorizationRejectionAndInterruptionNeverRenderComplete() {
        compose.setContent { MaterialTheme { ImportDiagnosticPanel(report().copy(outcome = null,
            termination = DiagnosticTermination.AUTH_REJECTED), false, {}, {}) } }
        compose.onNodeWithText("最近一次导入诊断").performClick()
        compose.onNodeWithText("授权被拒绝（成绩导入未开始）", substring = true).assertExists()
        compose.onNodeWithText("COMPLETE", substring = true).assertDoesNotExist()
    }
}

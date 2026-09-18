package dev.fluentmai.android.feature.importflow

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import dev.fluentmai.android.core.model.*
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w393dp-h852dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImportProgressUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var root: android.view.View

    @Test fun loadingShowsRealPageCountAndActivePageInLightTheme() {
        val progress = ImportProgress(ImportStage.PlayCounts, "全部爬取：请保持软件在前台", 32, 500, pageName = "ViRTUS · DX")
        compose.setContent { root = LocalView.current; MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF246B4A))) {
            Surface { Column(Modifier.padding(16.dp)) { Text("导入状态"); ImportProgressPanel(progress, true) } }
        } }
        compose.onNodeWithText("已处理 32/500 页").assertExists()
        compose.onNodeWithText("当前页面：ViRTUS · DX · 正在读取").assertExists()
        assertEquals(.064f, stageFraction(), .00001f)
        compose.onNodeWithTag("import-current-page-progress").assertExists()
        screenshot("import-progress-light")
    }

    @Test fun failureStopsAnimationAndNeverPretendsToReachOneHundredPercent() {
        val progress = ImportProgress(ImportStage.PlayCounts, "本次爬取已结束，请查看详细报错", 3, 500, 3,
            "ViRTUS · DX", ImportPageState.Failed)
        compose.setContent { root = LocalView.current; MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF93D5AF))) {
            Surface { Column(Modifier.padding(16.dp)) { Text("导入状态"); ImportProgressPanel(progress, false) } }
        } }
        assertEquals(.006f, stageFraction(), .00001f)
        compose.onNodeWithTag("import-current-page-progress").assertDoesNotExist()
        compose.onNodeWithText("其中 3 页未完整读取").assertExists()
        compose.onNodeWithText("100%").assertDoesNotExist()
        screenshot("import-progress-dark-failure")
    }

    @Test fun unknownPageTotalDoesNotShowAnInventedPercentage() {
        compose.setContent { MaterialTheme { ImportProgressPanel(ImportProgress(ImportStage.Recent, "继续发现记录分页", 2,
            pageName = "游戏记录第 3 页"), true) } }
        compose.onNodeWithText("已处理 2 页").assertExists()
        compose.onNodeWithText("%", substring = true).assertDoesNotExist()
    }

    @Test fun aNewStageResetsTheBarAndCompletionStopsThePageAnimation() {
        var progress by mutableStateOf(ImportProgress(ImportStage.Scores, "读取完成", 5, 5, pageState = ImportPageState.Complete))
        var running by mutableStateOf(true)
        compose.setContent { MaterialTheme { ImportProgressPanel(progress, running) } }
        compose.runOnIdle { progress = ImportProgress(ImportStage.PlayCounts, "正在读取", 0, 500, pageName = "曲目详情") }
        assertEquals(0f, stageFraction(), .00001f)
        compose.runOnIdle { progress = ImportProgress(ImportStage.Saving, "导入完成", 1, 1, pageState = ImportPageState.Complete); running = false }
        assertEquals(1f, stageFraction(), .00001f)
        compose.onNodeWithText("100%").assertExists()
        compose.onNodeWithText("6/6 阶段").assertExists()
        compose.onNodeWithTag("import-current-page-progress").assertDoesNotExist()
    }

    private fun stageFraction() = compose.onNodeWithTag("import-stage-progress").fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current

    private fun screenshot(name: String) {
        val repository = generateSequence(File(System.getProperty("user.dir")!!)) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
        val folder = File(repository, "build/test-artifacts/import-progress-preview").apply { mkdirs() }
        compose.runOnIdle {
            val view = root.rootView
            val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            File(folder, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}

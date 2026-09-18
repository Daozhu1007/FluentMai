package dev.fluentmai.android.feature.scores

import androidx.compose.material3.*
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.fluentmai.android.core.model.ChartIdentity
import dev.fluentmai.android.core.model.AchievementRank
import dev.fluentmai.android.core.model.FullComboStatus
import dev.fluentmai.android.core.model.FullSyncStatus
import dev.fluentmai.android.core.model.ChartNotes
import dev.fluentmai.android.feature.settings.SettingsScreen
import dev.fluentmai.android.feature.settings.ThemeMode
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w393dp-h852dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComponentEditorUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var rootView: android.view.View

    @Test fun chosenNumericFieldsUseThemeAwareGradientsWithoutChangingOtherValues() {
        val chart = previewChart()
        val scale = ThumbnailValueScale.fromCharts(listOf(chart))
        var light by mutableStateOf(true)
        compose.setContent { MaterialTheme(colorScheme = if (light) lightColorScheme() else darkColorScheme()) {
            ChartCard(chart, null, onClick = {}, valueScale = scale,
                fields = listOf(ThumbnailField.FitGap, ThumbnailField.Tolerance, ThumbnailField.Constant, ThumbnailField.Version))
        } }
        for (theme in listOf(true, false)) {
            compose.runOnIdle { light = theme }
            for (field in listOf(ThumbnailField.FitGap, ThumbnailField.Tolerance)) {
                val layouts = mutableListOf<TextLayoutResult>()
                compose.onNodeWithText(field.value(chart, null), useUnmergedTree = true)
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                assertEquals(thumbnailValueColor(scale.position(field, chart), theme, Color.Unspecified), layouts.single().layoutInput.style.color)
            }
            compose.onNodeWithText("14.3", useUnmergedTree = true).assertExists()
        }
    }

    @Test @Config(qualifiers = "w393dp-h1300dp-mdpi")
    fun valueGradientGalleryForBothThemes() {
        val charts = listOf(
            previewChart().copy(title = "负分差 · 低容错", fittedConstant = 15.1, notes = ChartNotes(110, 100, 0, 0, 0, 10)),
            previewChart().copy(title = "略低于中间值", fittedConstant = 14.35, notes = ChartNotes(560, 550, 0, 0, 0, 10)),
            previewChart().copy(title = "零分差 · 中间容错", fittedConstant = 14.3, notes = ChartNotes(610, 600, 0, 0, 0, 10)),
            previewChart().copy(title = "略高于中间值", fittedConstant = 14.25, notes = ChartNotes(660, 650, 0, 0, 0, 10)),
            previewChart().copy(title = "正分差 · 高容错", fittedConstant = 13.5, notes = ChartNotes(1110, 1100, 0, 0, 0, 10)),
        )
        val scale = ThumbnailValueScale.fromCharts(charts)
        var light by mutableStateOf(true)
        compose.setContent { rootView = LocalView.current; MaterialTheme(colorScheme = if (light) lightColorScheme() else darkColorScheme()) {
            Surface { Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
                charts.forEach { chart -> ChartCard(chart, null, onClick = {}, valueScale = scale,
                    fields = listOf(ThumbnailField.FitGap, ThumbnailField.Tolerance, ThumbnailField.Constant, ThumbnailField.Version)) }
            } }
        } }
        screenshot("value-gradients-light", dialog = false)
        compose.runOnIdle { light = false }
        screenshot("value-gradients-dark", dialog = false)
    }

    @Test fun thumbnailPreviewHasOnlyFourInteractiveSlots() {
        var opened = 0
        var favorited = 0
        var selectedSlot = -1
        val chart = previewChart()
        compose.setContent { MaterialTheme {
            ChartCard(chart, null, onClick = { opened++ }, onFavorite = { favorited++ }, onEditSlot = { selectedSlot = it })
        } }
        compose.onAllNodes(hasClickAction()).assertCountEquals(4)
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick)).assertCountEquals(0)
        compose.onNodeWithText(chart.title).performTouchInput { click(); longClick() }
        compose.onNodeWithContentDescription("收藏预览").performTouchInput { click() }
        assertEquals(0, opened)
        assertEquals(0, favorited)
        compose.onNodeWithText("版本").performClick()
        assertEquals(2, selectedSlot)
    }

    @Test fun realChartCardStillOpensAndFavorites() {
        var opened = 0
        var favorited = 0
        compose.setContent { MaterialTheme { ChartCard(previewChart(), null, onClick = { opened++ }, onFavorite = { favorited++ }) } }
        compose.onNodeWithText("谱面样式预览").performClick()
        compose.onNodeWithContentDescription("收藏谱面").performClick()
        assertEquals(1, opened)
        assertEquals(1, favorited)
    }

    @Test fun completionStatsAreGroupedTextNotClickableChips() {
        val stats = ChartQueryStats(totalCharts = 120, playedCharts = 100,
            rankCounts = mapOf(AchievementRank.SSS_PLUS to 37, AchievementRank.SSS to 63),
            fullComboCounts = mapOf(FullComboStatus.AP_PLUS to 9), fullSyncCounts = mapOf(FullSyncStatus.FSD_PLUS to 4))
        compose.setContent { rootView = LocalView.current; MaterialTheme {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) { ChartStatsSummary(stats, false) }
        } }
        compose.onNodeWithText("详情").performClick()
        for (title in listOf("达成等级", "FC / AP", "同步状态")) compose.onNodeWithText(title).assertExists()
        for (count in listOf("37", "63", "9", "4")) compose.onNodeWithText(count).assertExists()
        compose.onAllNodes(hasClickAction()).assertCountEquals(1)
        screenshot("completion-stats-simple", dialog = false)
        compose.onNodeWithText("收起").performClick()
        compose.onNodeWithText("达成等级").assertDoesNotExist()
    }

    @Test fun thumbnailEditorPreventsDuplicateAndSavesSelection() {
        val store = ComponentSettingsState(RuntimeEnvironment.getApplication()).also { it.reset() }
        compose.setContent { MaterialTheme { CompositionLocalProvider(LocalComponentSettingsState provides store) {
            ComponentEditorDialog(ComponentEditor.Thumbnail) {}
        } } }
        compose.onNodeWithText("定数").performClick()
        compose.onAllNodesWithText("BPM").onLast().assertIsNotEnabled()
        compose.onAllNodesWithText("PC").onLast().performClick()
        compose.onNodeWithText("PC").assertExists()
        compose.onNodeWithText("拟合分差 =", substring = true).assertDoesNotExist()
        screenshot("thumbnail-editor")
        compose.onNodeWithText("保存").performClick()
        assertEquals(ThumbnailField.PlayCount, store.value.thumbnailFields.first())
    }

    @Test fun detailEditorTogglesCrossWithoutHidingThePreview() {
        val store = ComponentSettingsState(RuntimeEnvironment.getApplication()).also { it.reset() }
        compose.setContent { MaterialTheme(colorScheme = darkColorScheme()) { CompositionLocalProvider(LocalComponentSettingsState provides store) {
            ComponentEditorDialog(ComponentEditor.Details) {}
        } } }
        compose.onNodeWithContentDescription("曲名，已显示，点击隐藏").performClick()
        compose.onNodeWithContentDescription("曲名，已隐藏，点击恢复").assertExists()
        compose.onNodeWithText("谱面样式预览").assertExists()
        screenshot("detail-editor-dark")
        compose.onNodeWithTag("chart-detail-grid").performScrollToNode(hasContentDescription("PC，已显示，点击隐藏"))
        compose.onNodeWithContentDescription("PC，已显示，点击隐藏").performClick()
        compose.onNodeWithContentDescription("PC，已隐藏，点击恢复").assertExists()
        compose.onNodeWithText("保存").performClick()
        assertTrue("曲名" in store.value.hiddenDetails)
        assertTrue("PC" in store.value.hiddenDetails)
    }

    @Test fun hiddenPropertiesDisappearOnRealDetailScreen() {
        val chart = previewChart()
        compose.setContent { MaterialTheme { CompositionLocalProvider(LocalComponentSettings provides ComponentSettings(hiddenDetails = setOf("曲名", "标题定数"))) {
            ChartDetailScreen(ChartIdentity.from(chart), listOf(chart), emptyList(), onBack = {}, onChartSelected = {})
        } } }
        compose.onNodeWithText("谱面样式预览").assertDoesNotExist()
        compose.onNodeWithContentDescription("已隐藏").assertDoesNotExist()
    }

    @Test fun settingsRestorationRequiresConfirmation() {
        var resets = 0
        compose.setContent { rootView = LocalView.current; MaterialTheme { SettingsScreen(ThemeMode.SYSTEM, {}, appVersion = "test", quarantineCount = 0,
            records = emptyList(), onResetSettings = { resets++ }) } }
        screenshot("settings-light", dialog = false)
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("还原"))
        compose.onNodeWithText("还原").performClick()
        compose.onNodeWithText("取消").performClick()
        assertEquals(0, resets)
        compose.onNodeWithText("还原").performClick()
        compose.onNodeWithText("恢复默认").performClick()
        assertEquals(1, resets)
    }

    @Test @Config(qualifiers = "w320dp-h640dp-mdpi")
    fun compactPhoneCanSelectSignedFitGapAndCancelWithoutSaving() {
        val store = ComponentSettingsState(RuntimeEnvironment.getApplication()).also { it.reset() }
        compose.setContent { MaterialTheme { CompositionLocalProvider(LocalComponentSettingsState provides store) {
            ComponentEditorDialog(ComponentEditor.Thumbnail) {}
        } } }
        compose.onNodeWithText("物量").performClick()
        compose.onNodeWithText("拟合分差").performClick()
        compose.onNodeWithText("-0.1200").assertIsDisplayed()
        compose.onNodeWithText("保存").assertIsDisplayed()
        screenshot("thumbnail-small-phone")
        compose.onNodeWithText("取消").performClick()
        assertEquals(ComponentSettings(), store.value)
    }

    @Test @Config(qualifiers = "w320dp-h640dp-mdpi")
    fun everyFieldFitsEverySlotWithoutEllipsisWithLargeFonts() {
        val chart = previewChart().copy(chartVersionName = "舞萌DX 2026 PLUS 第三次更新")
        var fields by mutableStateOf(ComponentSettings.DefaultThumbnailFields)
        compose.setContent {
            rootView = LocalView.current
            MaterialTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 1.5f)) {
                    ChartCard(chart, null, fields = fields, onClick = {})
                }
            }
        }
        for (field in ThumbnailField.entries) for (slot in 0..3) {
            compose.runOnIdle {
                fields = ThumbnailField.entries.filter { it != field }.take(3).toMutableList().also { it.add(slot, field) }
            }
            for (text in listOf(field.label, field.value(chart, null))) {
                // A missing PC intentionally displays '--'; that still must not be clipped.
                val layouts = mutableListOf<TextLayoutResult>()
                compose.onAllNodesWithText(text, useUnmergedTree = true).onLast().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                val result = layouts.single()
                // A paragraph can retain its available width while Text wraps to its narrower
                // intrinsic width. Check actual glyph/line bounds instead of paragraph width.
                for (line in 0 until result.lineCount) {
                    assertTrue("$field slot $slot horizontal clipping: $text", result.getLineRight(line) <= result.size.width + 1f)
                    assertTrue("$field slot $slot vertical clipping: $text", result.getLineBottom(line) <= result.size.height + 1f)
                }
                assertEquals(text.length, result.getLineEnd(result.lineCount - 1))
                for (line in 0 until result.lineCount) assertFalse(result.isLineEllipsized(line))
            }
        }
        compose.runOnIdle { fields = listOf(ThumbnailField.PlayCount, ThumbnailField.Fitted, ThumbnailField.Version, ThumbnailField.Tolerance) }
        screenshot("thumbnail-complete-long-version", dialog = false)
    }

    @Test fun homeNoLongerShowsRedundantCountChips() {
        compose.setContent { rootView = LocalView.current; MaterialTheme {
            ScoresScreen(emptyList(), emptyList(), emptyList())
        } }
        compose.onNodeWithText("成绩").assertIsDisplayed()
        compose.onNodeWithText("本地成绩").assertDoesNotExist()
        compose.onNodeWithText("曲库谱面").assertDoesNotExist()
        screenshot("home-compact-header", dialog = false)
    }

    @Test fun chartQueryKeepsTitleAndRefreshWithoutCountChips() {
        var refreshes = 0
        compose.setContent { rootView = LocalView.current; MaterialTheme {
            ChartQueryScreen(emptyList(), emptyList(), emptyList(), isLoading = false, onRefresh = { refreshes++ })
        } }
        compose.onNodeWithText("谱面查询").assertIsDisplayed()
        compose.onNodeWithText("曲库").assertDoesNotExist()
        compose.onNodeWithText("结果").assertDoesNotExist()
        compose.onNodeWithContentDescription("刷新曲库").performClick()
        assertEquals(1, refreshes)
        screenshot("query-compact-header", dialog = false)
    }

    private fun screenshot(name: String, dialog: Boolean = true) {
        val root = generateSequence(File(System.getProperty("user.dir")!!)) { it.parentFile }
            .first { File(it, "settings.gradle.kts").exists() }
        val folder = File(root, "build/test-artifacts/component-preview").apply { mkdirs() }
        compose.runOnIdle {
            val view = if (dialog) org.robolectric.shadows.ShadowDialog.getShownDialogs().last { it.isShowing }.window!!.decorView else rootView.rootView
            val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            File(folder, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}

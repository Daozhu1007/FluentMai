package dev.fluentmai.android.feature.importflow

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ImportAuthorizationUiTest {
    @get:Rule val compose = createComposeRule()

    private fun showScreen(
        authorizing: Boolean = false,
        retryAvailable: Boolean = false,
        onAuthorize: () -> Unit = {},
        onStop: () -> Unit = {},
    ) {
        compose.setContent {
            MaterialTheme {
                ImportScreen(
                    realImportSummary = null,
                    importStatus = "等待导入",
                    errorMessage = null,
                    hookStatus = "等待微信授权",
                    isHookRunning = authorizing,
                    divingFishToken = "",
                    lxnsToken = "",
                    uploadStatus = "",
                    uploadSummary = null,
                    uploadErrorMessage = null,
                    uploadProgressText = null,
                    uploadProgressFraction = null,
                    scoreCount = 0,
                    isImporting = false,
                    isUploading = false,
                    wahlapCookieInput = "",
                    onStopHookCapture = onStop,
                    onWahlapCookieInputChanged = {},
                    onImportWahlapCookie = {},
                    onDivingFishTokenChanged = {},
                    onLxnsTokenChanged = {},
                    onUploadDivingFish = {},
                    onRebuildDivingFish = {},
                    onUploadLxns = {},
                    isAuthorizing = authorizing,
                    quickAuthActive = authorizing,
                    authRetryAvailable = retryAvailable,
                    onQuickAuthorization = onAuthorize,
                )
            }
        }
    }

    @Test fun oneAuthorizationEntryReplacesTheConflictingManualControls() {
        var starts = 0
        showScreen(onAuthorize = { starts++ })
        compose.onAllNodesWithText("立即微信授权").assertCountEquals(1)
        compose.onNodeWithText("启动捕获").assertDoesNotExist()
        compose.onNodeWithText("复制授权").assertDoesNotExist()
        compose.onNodeWithText("手动重新启动捕获").assertDoesNotExist()
        compose.onNodeWithText("立即微信授权").performScrollTo().performClick()
        assertEquals(1, starts)
    }

    @Test fun activeAuthorizationCannotStartAnotherAttemptButCanStillBeStopped() {
        var stops = 0
        showScreen(authorizing = true, onStop = { stops++ })
        compose.onNodeWithText("立即微信授权").assertIsNotEnabled()
        compose.onNodeWithText("停止捕获").assertIsEnabled().performScrollTo().performClick()
        assertEquals(1, stops)
    }

    @Test fun rejectedAuthorizationRetriesThroughTheSameQuickEntry() {
        var retries = 0
        showScreen(retryAvailable = true, onAuthorize = { retries++ })
        compose.onAllNodesWithText("授权尝试", substring = true).assertCountEquals(0)
        compose.onNodeWithText("立即微信授权").assertDoesNotExist()
        compose.onNodeWithText("手动重新启动捕获").assertDoesNotExist()
        compose.onNodeWithText("重新授权并打开微信").assertIsEnabled().performScrollTo().performClick()
        assertEquals(1, retries)
    }

    @Test fun fiveUserClicksAcrossFailuresKeepRetryEnabledWithoutAutomaticCommands() {
        val retryAvailable = androidx.compose.runtime.mutableStateOf(true)
        val active = androidx.compose.runtime.mutableStateOf(false)
        var retries = 0
        compose.setContent {
            MaterialTheme {
                ImportScreen(realImportSummary = null, importStatus = "等待导入", errorMessage = null,
                    hookStatus = "本次授权已失效", isHookRunning = active.value,
                    divingFishToken = "", lxnsToken = "", uploadStatus = "", uploadSummary = null,
                    uploadErrorMessage = null, uploadProgressText = null, uploadProgressFraction = null,
                    scoreCount = 0, isImporting = false, isUploading = false, wahlapCookieInput = "",
                    onStopHookCapture = {}, onWahlapCookieInputChanged = {}, onImportWahlapCookie = {},
                    onDivingFishTokenChanged = {}, onLxnsTokenChanged = {}, onUploadDivingFish = {},
                    onRebuildDivingFish = {}, onUploadLxns = {}, isAuthorizing = active.value,
                    quickAuthActive = active.value, authRetryAvailable = retryAvailable.value,
                    onQuickAuthorization = { retries++; active.value = true })
            }
        }
        for (number in 1..5) {
            compose.waitForIdle()
            assertEquals(number - 1, retries)
            compose.onNodeWithText("重新授权并打开微信").assertIsEnabled().performScrollTo().performClick()
            compose.waitForIdle()
            assertEquals(number, retries)
            compose.onNodeWithText("重新授权并打开微信").assertIsNotEnabled()
            compose.runOnIdle { active.value = false }
            compose.waitForIdle()
            compose.onNodeWithText("重新授权并打开微信").assertIsEnabled()
            compose.onAllNodesWithText("授权尝试", substring = true).assertCountEquals(0)
        }
    }
}

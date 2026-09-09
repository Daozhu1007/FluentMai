package dev.fluentmai.android.core.importer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WahlapScorePageValidationTest {
    private val emptyPage = """
        <html><body><form action="/maimai-mobile/record/musicSort/search/">
        <input name="diff" value="0"><input name="sort" value="1">
        </form><div>没有符合条件的乐曲。</div></body></html>
    """.trimIndent()

    @Test fun unplayedDifficultyIsAValidEmptyList() {
        assertTrue(WahlapScorePageValidation.isScorePage(emptyPage))
    }

    @Test fun authenticationAndServerErrorsAreNotEmptyScores() {
        assertFalse(WahlapScorePageValidation.isScorePage("<html>登录失败</html>"))
        assertFalse(WahlapScorePageValidation.isScorePage("<html>服务繁忙</html>"))
        assertFalse(WahlapScorePageValidation.isScorePage(emptyPage.replace("</body>", "<div class='title_error'>错误码</div></body>")))
        assertFalse(WahlapScorePageValidation.isScorePage("<html>暂无成绩</html>"))
    }

    @Test fun truncatedPagesAndIncompleteCardsStillFail() {
        assertFalse(WahlapScorePageValidation.isScorePage(emptyPage.substringBefore("</html>")))
        assertFalse(WahlapScorePageValidation.isScorePage(emptyPage.replace("</body>", "<div class='music_name_block'>Song</div></body>")))
    }
}

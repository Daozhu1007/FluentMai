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

    @Test fun sharedErrorScriptDoesNotMakeAValidListAnErrorPage() {
        assertTrue(WahlapScorePageValidation.isScorePage(emptyPage.replace("</body>",
            "<script>const message = '登录失败 错误码 title_error';</script></body>")))
        assertFalse(WahlapScorePageValidation.isScorePage(emptyPage.replace("</body>",
            "<img src='title_error.png'></body>")))
    }

    @Test fun authenticationAndServerErrorsAreNotEmptyScores() {
        assertFalse(WahlapScorePageValidation.isScorePage("<html>登录失败</html>"))
        assertFalse(WahlapScorePageValidation.isScorePage("<html>服务繁忙</html>"))
        assertFalse(WahlapScorePageValidation.isScorePage(emptyPage.replace("</body>", "<div class='title_error'>错误码</div></body>")))
        assertFalse(WahlapScorePageValidation.isScorePage("<html>暂无成绩</html>"))
    }

    @Test fun truncatedPagesAndIncompleteCardsStillFail() {
        assertFalse(WahlapScorePageValidation.isScorePage(emptyPage.substringBefore("</html>")))
        val completeCard = "<html><form action='musicDetail'><div class='music_name_block'>Song</div>" +
            "<div class='music_score_block'>99.0000%</div></form>"
        assertFalse(WahlapScorePageValidation.isScorePage(completeCard))
        assertTrue(WahlapScorePageValidation.isScorePage("$completeCard</html>"))
        assertFalse(WahlapScorePageValidation.isScorePage(emptyPage.replace("</body>", "<div class='music_name_block'>Song</div></body>")))
    }
}

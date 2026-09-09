package dev.fluentmai.android.core.importer

import org.jsoup.Jsoup

/** Empty, successfully rendered score lists are valid for unplayed difficulties. */
object WahlapScorePageValidation {
    fun isScorePage(html: String): Boolean {
        val document = Jsoup.parse(html)
        if (document.select(".title_error").isNotEmpty() ||
            Regex("登录失败|错误码|请在微信客户端|please open in wechat|oauth[/2]", RegexOption.IGNORE_CASE)
                .containsMatchIn(html)) return false

        val detailLinks = document.select("[action*=musicDetail], a[href*=musicDetail]")
        val names = document.select(".music_name_block")
        val scores = document.select(".music_score_block")
        if (detailLinks.isNotEmpty() && names.isNotEmpty() && scores.isNotEmpty()) return true
        // An incomplete score card is not an empty list.
        if (detailLinks.isNotEmpty() || names.isNotEmpty() || scores.isNotEmpty()) return false
        if (!html.contains("</html>", ignoreCase = true)) return false

        val searchForms = document.select("form[action*=record/musicSort], form[action*=record/musicGenre]")
        val completeSearchForm = searchForms.any { form ->
            form.select("[name=diff]").isNotEmpty() &&
                form.select("[name=sort], [name=genre], [name=search]").isNotEmpty()
        }
        val emptyMessage = Regex(
            "(?:没有|暂无|无|未找到)[^。！\\n]{0,40}(?:曲|成绩|记录)|(?:曲|成绩|记录)[^。！\\n]{0,30}(?:不存在|未找到)|該当する楽曲.*(?:ありません|見つかりません)",
        ).containsMatchIn(document.text())
        val scorePageChrome = document.select(
            "[src*=title_music], [src*=title_record], a[href*=record/musicSort], a[href*=record/musicGenre]",
        ).isNotEmpty()
        return completeSearchForm || (emptyMessage && (searchForms.isNotEmpty() || scorePageChrome))
    }
}

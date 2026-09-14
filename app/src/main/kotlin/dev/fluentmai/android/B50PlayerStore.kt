package dev.fluentmai.android

import android.content.Context
import dev.fluentmai.android.core.importer.WahlapPlayerProfileParser
import dev.fluentmai.android.feature.scores.B50PlayerProfile
import dev.fluentmai.android.feature.scores.B50ProfileResult
import org.json.JSONObject

/** Local display-only snapshot captured during FluentMai's own authenticated import. */
internal class B50PlayerStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("wahlap_player_display", Context.MODE_PRIVATE)
    val revision: Int get() = preferences.getString("profile", null).hashCode()

    fun capture(homeHtml: String) {
        val player = runCatching { WahlapPlayerProfileParser.parse(homeHtml) }.getOrNull()
        // Replace instead of merging: switching accounts must not retain somebody else's artwork.
        val data = player?.let {
            JSONObject().put("name", it.name).put("icon", it.iconUrl).put("plate", it.plateUrl)
                .put("frame", it.frameUrl).put("trophy", it.trophy).put("trophyColor", it.trophyColor)
                .put("course", it.courseRank).put("class", it.classRank).toString()
        }
        preferences.edit().putString("profile", data).apply()
    }

    fun load(): B50ProfileResult {
        val data = preferences.getString("profile", null)?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: return B50ProfileResult(message = "大图使用本地成绩，不需要落雪 Token。请重新导入一次官方成绩，以补齐旧版本未保存的玩家装扮。")
        fun string(key: String) = data.optString(key).takeIf { it.isNotBlank() && it != "null" }
        fun image(key: String) = string(key)?.let(WahlapPlayerProfileParser::safeArtworkUrl)
        val player = B50PlayerProfile(
            name = string("name") ?: "本地玩家", iconId = null, plateId = null, frameId = null,
            trophy = string("trophy"), trophyColor = string("trophyColor"),
            courseRank = if (data.has("course")) data.optInt("course") else null,
            classRank = if (data.has("class")) data.optInt("class") else null,
            iconUrl = image("icon"), plateUrl = image("plate"), frameUrl = image("frame"),
        )
        return B50ProfileResult(player, if (player.plateUrl == null) "本次官方页面未提供姓名框图片，暂时留空；不会借用落雪账号的装扮。" else null)
    }
}

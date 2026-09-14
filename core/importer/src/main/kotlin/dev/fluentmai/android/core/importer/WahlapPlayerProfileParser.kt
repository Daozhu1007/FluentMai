package dev.fluentmai.android.core.importer

import java.net.URI
import org.jsoup.Jsoup

/** Only public display fields are retained, never cookies, friend codes or form tokens. */
data class WahlapPlayerProfile(
    val name: String,
    val iconUrl: String?,
    val plateUrl: String?,
    val frameUrl: String?,
    val trophy: String?,
    val trophyColor: String?,
    val courseRank: Int?,
    val classRank: Int?,
)

object WahlapPlayerProfileParser {
    fun parse(html: String): WahlapPlayerProfile? {
        val doc = Jsoup.parse(html, "https://maimai.wahlap.com/maimai-mobile/home/")
        val name = doc.selectFirst(".name_block")?.text()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val urls = buildList {
            doc.select("img[src]").forEach { add(it.absUrl("src")) }
            doc.select("[style]").forEach { element ->
                Regex("url\\(['\"]?([^)'\"]+)['\"]?\\)", RegexOption.IGNORE_CASE)
                    .findAll(element.attr("style")).forEach { match ->
                        runCatching { URI(doc.baseUri()).resolve(match.groupValues[1]).toString() }.getOrNull()?.let(::add)
                    }
            }
        }.mapNotNull(::safeArtworkUrl)
        fun image(vararg folders: String) = urls.firstOrNull { url -> folders.any { "/$it/" in url.lowercase() } }
        fun rank(pattern: String, range: IntRange) = urls.firstNotNullOfOrNull {
            Regex(pattern, RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { value -> value in range }
        }
        val trophy = doc.selectFirst(".trophy_inner_block")?.text()?.takeIf { it.isNotBlank() }
        val color = doc.selectFirst(".trophy_block")?.classNames()?.firstOrNull {
            it.lowercase() in setOf("trophy_normal", "trophy_bronze", "trophy_silver", "trophy_gold", "trophy_rainbow")
        }?.substringAfter('_')?.lowercase()
        return WahlapPlayerProfile(name, image("icon"), image("plate", "nameplate", "name_plate"), image("frame"),
            trophy, color, rank("course_rank_(\\d{2})", 0..23), rank("class_rank_(?:s_)?(\\d{2})", 0..25))
    }

    /** Follow only displayed read-only collection links, never a change/equip action. */
    fun collectionLinks(html: String): List<String> = Jsoup.parse(html, "https://maimai.wahlap.com/maimai-mobile/")
        .select("a[href]").mapNotNull { element ->
            runCatching {
                val uri = URI(element.absUrl("href"))
                if (uri.host != "maimai.wahlap.com" || uri.rawQuery != null || uri.userInfo != null || uri.port != -1 ||
                    !Regex("/maimai-mobile/collection/(?:index/|nameplate/|frame/)?").matches(uri.path)) null
                else "https://maimai.wahlap.com${uri.path}"
            }.getOrNull()
        }.distinct()

    fun addEquippedArtwork(homeHtml: String, collectionHtml: String): String {
        val home = Jsoup.parse(homeHtml)
        val collection = Jsoup.parse(collectionHtml, "https://maimai.wahlap.com/maimai-mobile/")
        val markers = collection.select("img[src]").filter {
            Regex("(?:collection|nameplate|frame)_(?:on|setting|selected)\\.", RegexOption.IGNORE_CASE).containsMatchIn(it.attr("src"))
        } + collection.allElements.filter {
            it.ownText().trim() in setOf("設定中", "设置中", "使用中", "已设置", "装備中", "当前使用", "已装备")
        }
        markers.forEach { marker ->
            // Stop at the smallest card containing artwork. Never choose the first unlocked item.
            val card = generateSequence(marker.parent()) { it.parent() }.take(4).firstOrNull { parent ->
                parent.select("img[src]").any { image ->
                    Regex("/(?:plate|nameplate|name_plate|frame)/", RegexOption.IGNORE_CASE).containsMatchIn(image.attr("src"))
                }
            }
            card?.select("img[src]")?.mapNotNull { safeArtworkUrl(it.absUrl("src")) }
                ?.filter { Regex("/(?:plate|nameplate|name_plate|frame)/", RegexOption.IGNORE_CASE).containsMatchIn(it) }
                ?.takeIf { it.size == 1 }?.forEach { url -> home.body().appendElement("img").attr("src", url) }
        }
        return home.outerHtml()
    }

    fun safeArtworkUrl(raw: String): String? = runCatching {
        val uri = URI(raw)
        if (uri.host?.lowercase() !in setOf("maimai.wahlap.com", "maimaidx.jp") ||
            uri.scheme !in setOf("https", "http") || uri.userInfo != null || uri.port != -1 ||
            !uri.path.startsWith("/maimai-mobile/img/") ||
            !uri.path.lowercase().endsWith(".png")) return null
        // Drop query parameters: an image request must never carry any captured credentials.
        URI("https", uri.host, uri.path, null).toASCIIString()
    }.getOrNull()
}

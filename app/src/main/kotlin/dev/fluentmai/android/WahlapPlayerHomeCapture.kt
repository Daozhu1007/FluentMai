package dev.fluentmai.android

import dev.fluentmai.android.core.importer.WahlapPlayerProfileParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/** Optional display metadata may fail without preventing score import. */
internal suspend fun enrichWahlapPlayerHome(home: String, fetch: suspend (String) -> String?): String {
    val profile = WahlapPlayerProfileParser.parse(home) ?: return home
    if (profile.plateUrl != null && profile.frameUrl != null) return home
    var result = home
    val queue = ArrayDeque(WahlapPlayerProfileParser.collectionLinks(home))
    val visited = mutableSetOf<String>()
    while (queue.isNotEmpty() && visited.size < 3) {
        val url = queue.removeFirst()
        if (!visited.add(url)) continue
        val page = try { withTimeoutOrNull(7_000) { fetch(url) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        if (page != null) {
            result = WahlapPlayerProfileParser.addEquippedArtwork(result, page)
            queue.addAll(WahlapPlayerProfileParser.collectionLinks(page).filter { it !in visited })
        }
    }
    return result
}

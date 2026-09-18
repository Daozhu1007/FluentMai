package dev.fluentmai.android

/** Only the confirmed CN endpoint. Do not probe guessed/legacy routes: a failed
 * optional request was followed by errors even on previously working score lists
 * in the 2026-09-17 device diagnostic, before the first PC request. */
internal object WahlapSupplementalPages {
    data class Page(val label: String, val url: String)
    val pages = listOf(Page("rating-target-music", "https://maimai.wahlap.com/maimai-mobile/home/ratingTargetMusic/"))
}

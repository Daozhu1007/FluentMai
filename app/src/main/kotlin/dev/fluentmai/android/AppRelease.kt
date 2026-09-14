package dev.fluentmai.android

import java.net.URI
import org.json.JSONArray

internal data class AppVersion(val major: Int, val minor: Int, val patch: Int, val stage: Int, val revision: Int) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion) = compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch }, { it.stage }, { it.revision })
    companion object {
        fun parse(raw: String?): AppVersion? {
            val match = Regex("(\\d+)\\.(\\d+)\\.(\\d+)(.*)", RegexOption.IGNORE_CASE).find(raw.orEmpty()) ?: return null
            val suffix = match.groupValues[4].lowercase().removeSuffix(".apk")
            val stage = when { "alpha" in suffix -> 0; "beta" in suffix -> 1; "rc" in suffix -> 2; else -> 3 }
            val revision = Regex("(?:alpha|beta|rc)[. _-]*(\\d+)").find(suffix)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            return AppVersion(match.groupValues[1].toIntOrNull() ?: return null, match.groupValues[2].toIntOrNull() ?: return null,
                match.groupValues[3].toIntOrNull() ?: return null, stage, revision)
        }
    }
}

internal data class AppRelease(val name: String, val version: AppVersion, val assetUrl: String, val size: Long, val sha256: String?)

internal fun isOfficialApkUrl(url: String): Boolean = runCatching {
    val uri = URI(url)
    uri.scheme == "https" && uri.host == "github.com" && uri.userInfo == null && uri.port == -1 &&
        uri.path.startsWith("/Daozhu1007/FluentMai/releases/download/") && uri.path.endsWith(".apk", true) &&
        uri.normalize().path == uri.path && uri.rawQuery == null
}.getOrDefault(false)

internal fun parseAppReleases(json: String): List<AppRelease> {
    val releases = JSONArray(json)
    return (0 until releases.length()).mapNotNull { index ->
        val release = releases.optJSONObject(index) ?: return@mapNotNull null
        if (release.optBoolean("draft")) return@mapNotNull null
        val assets = release.optJSONArray("assets") ?: return@mapNotNull null
        val apk = (0 until assets.length()).mapNotNull(assets::optJSONObject)
            .firstOrNull { isOfficialApkUrl(it.optString("browser_download_url")) } ?: return@mapNotNull null
        val version = AppVersion.parse(apk.optString("name")) ?: AppVersion.parse(release.optString("tag_name")) ?: return@mapNotNull null
        AppRelease(release.optString("name").ifBlank { release.optString("tag_name") }, version,
            apk.getString("browser_download_url"), apk.optLong("size"),
            apk.optString("digest").removePrefix("sha256:").takeIf { it.matches(Regex("[a-fA-F0-9]{64}")) })
    }
}

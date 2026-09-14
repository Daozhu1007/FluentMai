package dev.fluentmai.android

import org.junit.Assert.*
import org.junit.Test

class AppReleaseTest {
    @Test fun comparesStableAndPrereleaseVersionsNumerically() {
        fun version(s: String) = requireNotNull(AppVersion.parse(s))
        assertTrue(version("v0.2.6 Beta") > version("0.2.5-android-beta.3"))
        assertTrue(version("v0.2.6-beta.10") > version("0.2.6-beta.9"))
        assertTrue(version("v0.2.6") > version("0.2.6-rc.9"))
        assertEquals(version("0.2.6-beta"), version("FluentMai-v0.2.6-beta.apk"))
        assertNull(AppVersion.parse("unknown"))
    }
    @Test fun acceptsOnlyThisRepositoriesHttpsApks() {
        assertTrue(isOfficialApkUrl("https://github.com/Daozhu1007/FluentMai/releases/download/v0.2.6-beta/FluentMai.apk"))
        listOf("http://github.com/Daozhu1007/FluentMai/releases/download/x/a.apk",
            "https://github.com/Other/FluentMai/releases/download/x/a.apk",
            "https://evil.example/a.apk", "https://github.com/Daozhu1007/FluentMai/releases/download/x/a.ipa",
            "https://secret@github.com/Daozhu1007/FluentMai/releases/download/x/a.apk").forEach { assertFalse(isOfficialApkUrl(it)) }
    }
    @Test fun includesBetaButIgnoresDraftsIosAndInvalidUrls() {
        val json = """[
          {"name":"v0.2.6 Beta","tag_name":"v0.2.6-beta","prerelease":true,"draft":false,
           "assets":[{"name":"FluentMai-v0.2.6-beta.apk","size":123,"browser_download_url":"https://github.com/Daozhu1007/FluentMai/releases/download/v0.2.6-beta/FluentMai-v0.2.6-beta.apk"}]},
          {"tag_name":"v9.0.0","draft":true,"assets":[]},
          {"tag_name":"v9.0.0","assets":[{"name":"app.ipa","browser_download_url":"https://github.com/Daozhu1007/FluentMai/releases/download/v9.0.0/app.ipa"}]},
          {"tag_name":"v9.0.0","assets":[{"name":"app.apk","browser_download_url":"https://evil.example/app.apk"}]}
        ]"""
        val releases = parseAppReleases(json)
        assertEquals(1, releases.size)
        assertEquals(AppVersion.parse("0.2.6 Beta"), releases.single().version)
    }
}

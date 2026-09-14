package dev.fluentmai.android

import dev.fluentmai.android.core.importer.WahlapPlayerProfileParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class WahlapPlayerHomeCaptureTest {
    private val home = """
        <div class="name_block">Local player</div>
        <a href="https://maimai.wahlap.com/maimai-mobile/collection/">Collection</a>
    """

    @Test fun readsOnlyOfficialLinkedPagesAndKeepsEquippedArtwork() = runBlocking {
        val visited = mutableListOf<String>()
        val result = enrichWahlapPlayerHome(home) { url ->
            visited.add(url)
            if (url.endsWith("/collection/")) """
                <div><img src="/maimai-mobile/img/NamePlate/current.png"><span>設定中</span></div>
                <a href="https://maimai.wahlap.com/maimai-mobile/collection/frame/">Frame</a>
                <a href="https://maimai.lxns.net/api/v0/user/maimai/player">Never read</a>
            """ else """<div><img src="/maimai-mobile/img/Frame/current.png"><span>設定中</span></div>"""
        }
        assertEquals(2, visited.size)
        assertTrue(visited.all { it.startsWith("https://maimai.wahlap.com/maimai-mobile/collection/") })
        val profile = WahlapPlayerProfileParser.parse(result)!!
        assertEquals("Local player", profile.name)
        assertTrue(profile.plateUrl!!.endsWith("/NamePlate/current.png"))
        assertTrue(profile.frameUrl!!.endsWith("/Frame/current.png"))
    }

    @Test fun collectionFailureDoesNotBlockImportOrLoseHomeProfile() = runBlocking {
        val result = enrichWahlapPlayerHome(home) { throw java.io.IOException("Offline") }
        assertEquals("Local player", WahlapPlayerProfileParser.parse(result)!!.name)
    }

    @Test fun missingProfileDoesNotFetchOrRequireAnyToken() = runBlocking {
        val result = enrichWahlapPlayerHome("<html>Missing metadata</html>") { error("Must not fetch") }
        assertNull(WahlapPlayerProfileParser.parse(result))
    }
}

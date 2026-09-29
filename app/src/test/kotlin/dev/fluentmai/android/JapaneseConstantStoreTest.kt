package dev.fluentmai.android

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class JapaneseConstantStoreTest {
    private val json = """[{"title":"Test","dx_lev_mas":"14","dx_lev_mas_i":"14.1","date_added":"20260917"}]"""

    @Test fun revalidatesWithEtagAndKeepsSourceTimestampSeparateFromCheckTime() {
        var calls = 0
        var time = 1000L
        val store = JapaneseConstantStore(RuntimeEnvironment.getApplication(), fetch = { _, etag ->
            if (calls++ == 0) { assertNull(etag); JapaneseConstantResponse(200, json, "v1", 500) }
            else { assertEquals("v1", etag); JapaneseConstantResponse(304) }
        }, now = { time }, minimumEntries = 1)
        assertEquals(1000L, store.refresh().checkedAt)
        time = 2000
        val updated = store.refresh()
        assertEquals(2000L, updated.checkedAt)
        assertEquals(500L, updated.sourceModifiedAt)
        assertEquals(1, store.cached()!!.catalog.values.size)
    }

    @Test fun fallbackDoesNotReuseValidatorsFromDifferentOrigin() {
        var failPrimary = false
        val seen = mutableListOf<String>()
        val store = JapaneseConstantStore(RuntimeEnvironment.getApplication(), fetch = { url, etag ->
            seen += url
            if (failPrimary && url == JapaneseConstantStore.ENDPOINTS.first()) throw java.io.IOException()
            if (url == JapaneseConstantStore.ENDPOINTS.last()) assertNull(etag)
            JapaneseConstantResponse(200, json, "v1", 500)
        }, minimumEntries = 1)
        store.refresh()
        failPrimary = true
        assertEquals(JapaneseConstantStore.ENDPOINTS.last(), store.refresh().endpoint)
        assertEquals(3, seen.size)
    }

    @Test fun failureAndStaleDataPreserveGoodCache() {
        var response = JapaneseConstantResponse(200, json, "v1", 500)
        val store = JapaneseConstantStore(RuntimeEnvironment.getApplication(), fetch = { _, _ -> response }, minimumEntries = 1)
        val first = store.refresh()
        for (bad in listOf(JapaneseConstantResponse(200, "<html>Error</html>"), JapaneseConstantResponse(200, "[]"),
            JapaneseConstantResponse(200, json, "old", 100), JapaneseConstantResponse(503))) {
            response = bad
            assertTrue(runCatching { store.refresh() }.isFailure)
            assertEquals(first.json, store.cached()!!.json)
            assertEquals(first.checkedAt, store.cached()!!.checkedAt)
        }
    }
}

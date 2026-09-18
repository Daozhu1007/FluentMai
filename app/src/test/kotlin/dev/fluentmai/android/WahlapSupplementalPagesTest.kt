package dev.fluentmai.android

import org.junit.Assert.assertEquals
import org.junit.Test

class WahlapSupplementalPagesTest {
    @Test fun supplementalRequestsOnlyUseTheConfirmedCnEndpoint() {
        assertEquals(listOf("https://maimai.wahlap.com/maimai-mobile/home/ratingTargetMusic/"),
            WahlapSupplementalPages.pages.map { it.url })
    }
}

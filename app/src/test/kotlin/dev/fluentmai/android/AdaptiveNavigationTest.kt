package dev.fluentmai.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveNavigationTest {
    @Test
    fun productNavigationHasExactlyFourConvergedDestinations() {
        assertEquals(listOf("首页", "导入", "谱面", "工具"), AppTab.entries.map { it.label })
    }

    @Test
    fun phoneWidthUsesBottomNavigation() {
        assertFalse(usesNavigationRail(599f))
    }

    @Test
    fun expandedWidthsUseNavigationRail() {
        assertTrue(usesNavigationRail(600f))
        assertTrue(usesNavigationRail(840f))
    }

    @Test
    fun reselectingCurrentTabKeepsItsCurrentPageAndRequestsScrollToTop() {
        var selectedTab: AppTab? = null
        var scrollToTopRequested = false

        selectNavigationTab(
            selectedTab = AppTab.Home,
            requestedTab = AppTab.Home,
            onTabSelected = { selectedTab = it },
            onSelectedTabReselected = { scrollToTopRequested = true },
        )

        assertEquals(null, selectedTab)
        assertTrue(scrollToTopRequested)
    }

    @Test
    fun selectingAnotherTabStillChangesDestination() {
        var selectedTab: AppTab? = null
        var scrollToTopRequested = false

        selectNavigationTab(
            selectedTab = AppTab.Home,
            requestedTab = AppTab.Charts,
            onTabSelected = { selectedTab = it },
            onSelectedTabReselected = { scrollToTopRequested = true },
        )

        assertEquals(AppTab.Charts, selectedTab)
        assertFalse(scrollToTopRequested)
    }
}

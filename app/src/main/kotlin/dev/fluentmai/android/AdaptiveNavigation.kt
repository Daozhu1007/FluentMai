package dev.fluentmai.android

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview

internal const val NAVIGATION_RAIL_MIN_WIDTH_DP = 600f

internal fun usesNavigationRail(widthDp: Float): Boolean =
    widthDp >= NAVIGATION_RAIL_MIN_WIDTH_DP

internal fun selectNavigationTab(
    selectedTab: AppTab,
    requestedTab: AppTab,
    onTabSelected: (AppTab) -> Unit,
    onSelectedTabReselected: () -> Unit,
) {
    if (selectedTab == requestedTab) onSelectedTabReselected() else onTabSelected(requestedTab)
}

@Composable
internal fun AdaptiveNavigationScaffold(
    selectedTab: AppTab,
    onTabSelected: (AppTab) -> Unit,
    onSelectedTabReselected: () -> Unit = {},
    modifier: Modifier = Modifier,
    content: @Composable (PaddingValues) -> Unit,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val useNavigationRail = usesNavigationRail(maxWidth.value)
        Row(modifier = Modifier.fillMaxSize()) {
            if (useNavigationRail) {
                NavigationRail {
                    AppTab.entries.forEach { tab ->
                        NavigationRailItem(
                            selected = selectedTab == tab,
                            onClick = {
                                selectNavigationTab(selectedTab, tab, onTabSelected, onSelectedTabReselected)
                            },
                            icon = { Icon(imageVector = tab.icon, contentDescription = tab.label) },
                            label = { Text(text = tab.label) },
                        )
                    }
                }
            }
            Scaffold(
                modifier = Modifier.weight(1f),
                bottomBar = {
                    if (!useNavigationRail) {
                        NavigationBar {
                            AppTab.entries.forEach { tab ->
                                NavigationBarItem(
                                    selected = selectedTab == tab,
                                    onClick = {
                                        selectNavigationTab(selectedTab, tab, onTabSelected, onSelectedTabReselected)
                                    },
                                    icon = { Icon(imageVector = tab.icon, contentDescription = tab.label) },
                                    label = { Text(text = tab.label) },
                                )
                            }
                        }
                    }
                },
                content = content,
            )
        }
    }
}

@Preview(name = "Phone portrait", widthDp = 411, heightDp = 914, showBackground = true)
@Preview(name = "Phone landscape", widthDp = 914, heightDp = 411, showBackground = true)
@Preview(name = "Expanded tablet", widthDp = 840, heightDp = 900, showBackground = true)
@Composable
private fun AdaptiveNavigationPreview() {
    var selectedTab by remember { mutableStateOf(AppTab.Home) }
    MaterialTheme {
        AdaptiveNavigationScaffold(
            selectedTab = selectedTab,
            onTabSelected = { selectedTab = it },
        ) { padding ->
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = selectedTab.label)
            }
        }
    }
}

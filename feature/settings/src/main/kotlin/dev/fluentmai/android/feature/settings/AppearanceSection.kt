package dev.fluentmai.android.feature.settings

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

private val SunOrange = Color(0xFFC76A16)
private val SystemGreen = Color(0xFF7DD8C2)
private val MoonBlue = Color(0xFF243E78)

@Composable
internal fun AppearanceSection(mode: ThemeMode, onModeChanged: (ThemeMode) -> Unit) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("外观", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(mode.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            ThemeModeSwitch(mode, onModeChanged)
        }
    }
}

@Composable
private fun ThemeModeSwitch(mode: ThemeMode, onModeChanged: (ThemeMode) -> Unit) {
    val trackColor by animateColorAsState(
        targetValue = mode.accent(),
        animationSpec = tween(250),
        label = "themeTrackColor",
    )
    val thumbOffset by animateDpAsState(
        targetValue = 4.dp + 48.dp * mode.ordinal,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "themeThumbPosition",
    )
    val thumbColor by animateColorAsState(
        targetValue = if (mode == ThemeMode.SYSTEM) Color(0xFF183D34) else Color.White,
        animationSpec = tween(250),
        label = "themeThumbBackground",
    )
    // Positions are deliberately physical left / middle / right, including in RTL locales.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Box(
            modifier = Modifier.size(width = 148.dp, height = 52.dp)
                .clip(CircleShape)
                .background(trackColor)
                .selectableGroup(),
        ) {
            Row(Modifier.fillMaxSize().padding(horizontal = 2.dp)) {
                repeat(ThemeMode.entries.size) {
                    Box(Modifier.size(48.dp, 52.dp), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(4.dp).background(Color.White.copy(alpha = 0.65f), CircleShape))
                    }
                }
            }
            Box(
                modifier = Modifier.absoluteOffset(x = thumbOffset, y = 4.dp)
                    .size(44.dp)
                    .shadow(2.dp, CircleShape)
                    .background(thumbColor, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Crossfade(targetState = mode, animationSpec = tween(160), label = "themeThumbIcon") { selected ->
                    Icon(
                        imageVector = when (selected) {
                            ThemeMode.LIGHT -> Icons.Filled.WbSunny
                            ThemeMode.SYSTEM -> Icons.Filled.Settings
                            ThemeMode.DARK -> Icons.Filled.DarkMode
                        },
                        contentDescription = null,
                        tint = selected.accent(),
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            // Transparent hit areas stay still while the thumb moves, so rapid taps remain reliable.
            Row(Modifier.fillMaxSize().padding(horizontal = 2.dp)) {
                ThemeMode.entries.forEach { option ->
                    val target = if (mode == option) {
                        ThemeMode.entries[(option.ordinal + 1) % ThemeMode.entries.size]
                    } else option
                    Box(
                        Modifier.size(48.dp, 52.dp)
                            .semantics {
                                contentDescription = if (mode == option) "${option.label}，点击切换为${target.label}" else option.label
                            }
                            .selectable(
                                selected = mode == option,
                                role = Role.RadioButton,
                                onClick = { onModeChanged(target) },
                            ),
                    )
                }
            }
        }
    }
}

private fun ThemeMode.accent(): Color = when (this) {
    ThemeMode.LIGHT -> SunOrange
    ThemeMode.SYSTEM -> SystemGreen
    ThemeMode.DARK -> MoonBlue
}

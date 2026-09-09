package dev.fluentmai.android.feature.settings

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

private val SunOrange = Color(0xFFC76A16)
private val MoonBlue = Color(0xFF243E78)

@Composable
internal fun AppearanceSection(mode: ThemeMode, onModeChanged: (ThemeMode) -> Unit) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
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
    val interactionSource = remember { MutableInteractionSource() }
    val systemGreen = MaterialTheme.colorScheme.primary
    val trackColor by animateColorAsState(
        targetValue = mode.accent(systemGreen),
        animationSpec = tween(250),
        label = "themeTrackColor",
    )
    val thumbOffset by animateDpAsState(
        targetValue = 3.dp + 34.dp * mode.ordinal,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "themeThumbPosition",
    )
    val thumbColor by animateColorAsState(
        targetValue = if (mode == ThemeMode.SYSTEM && systemGreen.luminance() > 0.5f) Color(0xFF183D34) else Color.White,
        animationSpec = tween(250),
        label = "themeThumbBackground",
    )
    // Positions are deliberately physical left / middle / right, including in RTL locales.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Box(
            modifier = Modifier.size(width = 100.dp, height = 48.dp)
                .clip(RoundedCornerShape(16.dp))
                .semantics {
                    contentDescription = "切换外观"
                    stateDescription = mode.label
                }
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    role = Role.Button,
                    onClickLabel = "切换为${mode.next().label}",
                ) {
                    onModeChanged(mode.next())
                },
            contentAlignment = Alignment.Center,
        ) {
          Box(
              Modifier.size(100.dp, 32.dp)
                  .clip(CircleShape)
                  .background(trackColor)
                  .indication(interactionSource, ripple()),
          ) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.SpaceBetween) {
                repeat(ThemeMode.entries.size) {
                    Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(3.dp).background(Color.White.copy(alpha = 0.65f), CircleShape))
                    }
                }
            }
            Box(
                modifier = Modifier.absoluteOffset(x = thumbOffset, y = 3.dp)
                    .size(26.dp)
                    .shadow(1.dp, CircleShape)
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
                        tint = selected.accent(systemGreen),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
          }
        }
    }
}

private fun ThemeMode.accent(systemGreen: Color): Color = when (this) {
    ThemeMode.LIGHT -> SunOrange
    ThemeMode.SYSTEM -> systemGreen
    ThemeMode.DARK -> MoonBlue
}

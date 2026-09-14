package dev.fluentmai.android.feature.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun ExperimentalSection(enabled: Boolean, onChanged: (Boolean) -> Unit, automaticUpdates: Boolean, onAutomaticUpdatesChanged: (Boolean) -> Unit) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("其他", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("自动检测更新", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Switch(automaticUpdates, onAutomaticUpdatesChanged)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("实验性功能", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                ExperimentalSwitch(enabled, onChanged)
            }
            Text("开启实验性功能可能会导致软件稳定性出现问题", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ExperimentalSwitch(enabled: Boolean, onChanged: (Boolean) -> Unit) {
    val source = remember { MutableInteractionSource() }
    val primary = MaterialTheme.colorScheme.primary
    val gray = if (MaterialTheme.colorScheme.background.luminance() > .5f) Color(0xFF858C91) else Color(0xFF535D65)
    val track by animateColorAsState(if (enabled) primary else gray, tween(250), label = "experimentColor")
    val offset by animateDpAsState(if (enabled) 37.dp else 3.dp,
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow), label = "experimentThumb")
    val thumb by animateColorAsState(if (enabled && primary.luminance() > .5f) Color(0xFF183D34) else Color.White,
        tween(250), label = "experimentThumbColor")
    Box(Modifier.size(66.dp, 48.dp)
        .semantics { contentDescription = "实验性功能" }
        .toggleable(enabled, source, indication = null, role = Role.Switch, onValueChange = onChanged),
        contentAlignment = Alignment.Center) {
        Box(Modifier.size(66.dp, 32.dp).clip(CircleShape).background(track).indication(source, ripple())) {
            Box(Modifier.absoluteOffset(x = offset, y = 3.dp).size(26.dp).shadow(1.dp, CircleShape)
                .background(thumb, CircleShape), contentAlignment = Alignment.Center) {
                Crossfade(enabled, animationSpec = tween(160), label = "experimentIcon") { active ->
                    if (active) Icon(Icons.Default.Science, null, Modifier.size(18.dp), tint = primary)
                }
            }
        }
    }
}

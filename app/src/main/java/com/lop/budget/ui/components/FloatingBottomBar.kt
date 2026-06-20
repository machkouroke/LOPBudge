package com.lop.budget.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * Barre de navigation flottante en pilule, façon One UI récent / iOS.
 * Le FAB central "+" est intégré, légèrement mis en avant.
 */
@Composable
fun FloatingBottomBar(
    current: String,
    onSelect: (String) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = CircleShape

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp)
            .clip(shape),
    ) {
        // Couche "frosted glass" (blur + teinte légère) — style iOS.
        // NB: la taille du Box est déterminée par la Surface (contenu). On peut donc matcher sa taille ici.
        Box(
            modifier = Modifier
                .matchParentSize()
                .blur(22.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)),
        )

        Surface(
            shape = shape,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.78f),
            tonalElevation = 6.dp,
            shadowElevation = 10.dp,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                BarItem(Icons.Filled.Home, "home", current, intent = HapticIntent.Tap) { onSelect("home") }
                BarItem(Icons.Filled.Assessment, "analytics", current, intent = HapticIntent.Tap) { onSelect("analytics") }

                // FAB central (action primaire)
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(54.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = "Ajouter",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier
                                .size(28.dp)
                                .pressScaleClickable(intent = HapticIntent.Confirm) { onAdd() },
                        )
                    }
                }

                BarItem(Icons.Filled.Flag, "goals", current, intent = HapticIntent.Tap) { onSelect("goals") }
                BarItem(Icons.Outlined.AccountBalanceWallet, "accounts", current, intent = HapticIntent.Tap) { onSelect("accounts") }
            }
        }
    }
}

@Composable
private fun BarItem(
    icon: ImageVector,
    route: String,
    current: String,
    intent: HapticIntent,
    onClick: () -> Unit,
) {
    val selected = current == route
    val tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant

    Icon(
        icon,
        contentDescription = route,
        tint = tint,
        modifier = Modifier
            .size(26.dp)
            .pressScaleClickable(intent = intent, pressedScale = 0.96f, onClick = onClick),
    )
}

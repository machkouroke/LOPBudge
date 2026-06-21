package com.lop.budget.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Bottom bar "liquid glass" inspirée de Budge.
 *
 * - Pilule flottante centrée avec blur + bordure
 * - Onglet sélectionné dans une bulle circulaire
 * - Bouton "+" séparé à droite
 */
@Composable
fun FloatingBottomBar(
    current: String,
    onSelect: (String) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pillShape = RoundedCornerShape(36.dp)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        // --- Pilule principale ---
        Box(
            modifier = Modifier
                .weight(1f)
                .height(72.dp)
                .clip(pillShape),
        ) {
            // "Liquid glass" layer
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .blur(26.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
                            ),
                        ),
                    ),
            )

            Surface(
                shape = pillShape,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.28f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
                shadowElevation = 14.dp,
                tonalElevation = 0.dp,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    NavItem(
                        icon = Icons.Filled.Home,
                        label = "Accueil",
                        route = "home",
                        current = current,
                        onClick = { onSelect("home") },
                    )
                    NavItem(
                        icon = Icons.Filled.Assessment,
                        label = "Analyses",
                        route = "analytics",
                        current = current,
                        onClick = { onSelect("analytics") },
                    )
                    NavItem(
                        icon = Icons.Filled.Flag,
                        label = "Objectifs",
                        route = "goals",
                        current = current,
                        onClick = { onSelect("goals") },
                    )
                    NavItem(
                        icon = Icons.Outlined.AccountBalanceWallet,
                        label = "Comptes",
                        route = "accounts",
                        current = current,
                        onClick = { onSelect("accounts") },
                    )
                }
            }
        }

        Spacer(Modifier.width(14.dp))

        // --- Bouton + séparé ---
        LiquidGlassFab(onClick = onAdd)
    }
}

@Composable
private fun NavItem(
    icon: ImageVector,
    label: String,
    route: String,
    current: String,
    onClick: () -> Unit,
) {
    val selected = current == route

    val bubbleColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
    val bubbleBorder = MaterialTheme.colorScheme.primary.copy(alpha = 0.32f)

    val iconTint = if (selected) MaterialTheme.colorScheme.onSurface
    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f)

    val textColor = if (selected) MaterialTheme.colorScheme.onSurface
    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)

    val content: @Composable () -> Unit = {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(icon, contentDescription = label, tint = iconTint, modifier = Modifier.size(22.dp))
            Spacer(Modifier.height(6.dp))
            Text(
                text = label,
                color = textColor,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
    }

    if (selected) {
        Surface(
            shape = CircleShape,
            color = bubbleColor,
            border = BorderStroke(1.dp, bubbleBorder),
            modifier = Modifier
                .size(64.dp)
                .pressScaleClickable(intent = HapticIntent.Tap, pressedScale = 0.97f, onClick = onClick),
        ) {
            Box(contentAlignment = Alignment.Center) { content() }
        }
    } else {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(56.dp)
                .pressScaleClickable(intent = HapticIntent.Tap, pressedScale = 0.97f, onClick = onClick),
        ) {
            content()
        }
    }
}

@Composable
private fun LiquidGlassFab(
    onClick: () -> Unit,
) {
    val shape = CircleShape

    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(shape),
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .blur(28.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                            Color.Transparent,
                        ),
                    ),
                ),
        )

        Surface(
            shape = shape,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.18f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
            shadowElevation = 18.dp,
            modifier = Modifier
                .matchParentSize()
                .pressScaleClickable(intent = HapticIntent.Confirm, pressedScale = 0.96f, onClick = onClick),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = "Ajouter",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(30.dp),
                )
            }
        }
    }
}

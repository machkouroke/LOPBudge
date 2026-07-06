package com.lop.budget.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import dev.chrisbanes.haze.rememberHazeState

/**
 * Bottom bar "liquid glass" inspirée de Budge (blur arrière-plan "vrai").
 *
 * IMPORTANT: Haze nécessite que l'écran parent fournisse un Modifier.hazeSource(state).
 * (Voir LopNavHost.kt : Box root / contenu scrollable)
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun FloatingBottomBar(
    current: String,
    onSelect: (String) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
    hazeState: HazeState = rememberHazeState(),
) {
    val pillShape = RoundedCornerShape(36.dp)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 30.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        // --- Pilule principale ---
        Surface(
            shape = pillShape,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)),
            shadowElevation = 14.dp,
            tonalElevation = 15.dp,
            modifier = Modifier
                .weight(1f)
                .height(65.dp)
                .clip(pillShape)
                // Blur plus fort pour éviter l'illisibilité quand du contenu passe derrière.
//                .hazeEffect(state = hazeState, style = HazeMaterials.regular())
                // Un "scrim" + gradient léger pour améliorer le contraste.

        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
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
                    label = "Analyse",
                    route = "analytics",
                    current = current,
                    onClick = { onSelect("analytics") },
                )
                NavItem(
                    icon = Icons.Filled.Flag,
                    label = "Objectif",
                    route = "goals",
                    current = current,
                    onClick = { onSelect("goals") },
                )
//                NavItem(
//                    icon = Icons.Outlined.AccountBalanceWallet,
//                    label = "Compte",
//                    route = "accounts",
//                    current = current,
//                    onClick = { onSelect("accounts") },
//                )
            }
        }

        Spacer(Modifier.width(14.dp))

        // --- Bouton + séparé ---
        LiquidGlassFab(hazeState = hazeState, onClick = onAdd)
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

    val bubbleColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.26f)
    val bubbleBorder = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)

    val iconTint = if (selected) MaterialTheme.colorScheme.onSurface
    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f)

    val textColor = if (selected) MaterialTheme.colorScheme.onSurface
    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)

    val content: @Composable () -> Unit = {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,

            modifier = Modifier.padding(vertical = 15.dp)
        ) {
            Icon(icon, contentDescription = label, tint = iconTint, modifier = Modifier.size(20.dp))
//            Spacer(Modifier.height(2.dp))
//            Text(
//                text = label,
//                color = textColor,
//                style = MaterialTheme.typography.labelSmall,
//                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
//                maxLines = 1,
//                softWrap = false,
//                overflow = TextOverflow.Ellipsis,
//            )
        }
    }

    if (selected) {
        Surface(
            shape = CircleShape,
            color = bubbleColor,
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
    hazeState: HazeState,
    onClick: () -> Unit,
) {
    val shape = CircleShape

    // PERF FIX #1 : hazeEffect (blur GPU temps-réel) désactivé sur le FAB.
    // Ce blur était recalculé à chaque frame pendant le scroll, causant des saccades.
    // Remplacé par un fond opaque primary avec une légère transparence pour l'effet visuel.
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.primary,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.22f)),
        shadowElevation = 18.dp,
        modifier = Modifier
            .size(72.dp)
            .clip(shape)
            // .hazeEffect(state = hazeState, style = HazeMaterials.regular()) // DÉSACTIVÉ : coût GPU trop élevé au scroll
            .pressScaleClickable(intent = HapticIntent.Confirm, pressedScale = 0.96f, onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                Icons.Filled.Add,
                contentDescription = "Ajouter",
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(30.dp),
            )
        }
    }
}

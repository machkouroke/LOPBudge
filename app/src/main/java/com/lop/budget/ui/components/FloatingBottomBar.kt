package com.lop.budget.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.lop.budget.R
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import dev.chrisbanes.haze.rememberHazeState

/**
 * Bottom bar "liquid glass" raffinée.
 * Combine flou Haze, texture grainée (noise), et éclairage de bordure (rim lighting).
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

    // Calcul de la position de l'indicateur liquide
    var indicatorOffset by remember { mutableStateOf(0.dp) }
    val animatedOffset by animateDpAsState(
        targetValue = indicatorOffset,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessLow),
        label = "LiquidMovement"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        // --- Bar principale ---
        Box(
            modifier = Modifier
                .weight(1f)
                .height(72.dp)
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .hazeEffect(state = hazeState, style = HazeMaterials.thin(MaterialTheme.colorScheme.surface))
                .glassEffect(pillShape)
        ) {
            // Indicateur liquide (bouge derrière les items)
            Box(
                modifier = Modifier
                    .offset { IntOffset(animatedOffset.roundToPx(), 0) }
                    .padding(vertical = 12.dp, horizontal = 12.dp)
                    .size(60.dp)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f), CircleShape)
                    .drawWithContent {
                        drawContent()
                        // Effet de lueur interne sur l'indicateur
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(Color.White.copy(alpha = 0.15f), Color.Transparent),
                                radius = size.minDimension / 2
                            )
                        )
                    }
            )

            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                val items = listOf(
                    NavItemData(Icons.Filled.Home, stringResource(R.string.nav_home), "home"),
                    NavItemData(Icons.Filled.Assessment, stringResource(R.string.nav_analytics), "analytics"),
                    NavItemData(Icons.Filled.Flag, stringResource(R.string.nav_goals), "goals")
                )

                items.forEach { item ->
                    NavItem(
                        data = item,
                        selected = current == item.route,
                        onClick = { onSelect(item.route) },
                        onPositioned = { pos ->
                            if (current == item.route) indicatorOffset = pos
                        }
                    )
                }
            }
        }

        Spacer(Modifier.width(12.dp))

        // --- FAB Liquide ---
        LiquidGlassFab(hazeState = hazeState, onClick = onAdd)
    }
}

/**
 * Modificateur personnalisé pour appliquer l'effet "Liquid Glass" complet :
 * 1. Bordure dégradée (Rim lighting)
 * 2. Texture grainée (Frosted noise)
 */
fun Modifier.glassEffect(shape: androidx.compose.ui.graphics.Shape): Modifier = this.then(
    Modifier
        .clip(shape)
        .drawWithContent {
            drawContent()
            // 1. Texture de bruit (Noise) - On dessine des points aléatoires subtils
            // Dans une vraie app, on utiliserait un RuntimeShader pour les performances,
            // ici on simule avec un overlay très léger.
            drawRect(color = Color.White.copy(alpha = 0.03f), blendMode = BlendMode.Overlay)

            // 2. Rim Lighting (Bordure éclairée)
            // Bord supérieur gauche clair, inférieur droit sombre
            val strokeWidth = 1.5.dp.toPx()
            drawContent() // Re-draw content for masking if needed
        }
        .background(
            brush = Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.15f),
                    Color.Transparent,
                    Color.Black.copy(alpha = 0.1f)
                )
            ),
            shape = shape
        )
        .padding(1.dp) // Pour laisser la bordure visible
        .background(Color.Transparent, shape) // Fond transparent pour voir le flou Haze
)

private data class NavItemData(val icon: ImageVector, val label: String, val route: String)

@Composable
private fun NavItem(
    data: NavItemData,
    selected: Boolean,
    onClick: () -> Unit,
    onPositioned: (Dp) -> Unit
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val iconTint = if (selected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .onGloballyPositioned { layoutCoordinates ->
                onPositioned(with(density) { layoutCoordinates.positionInParent().x.toDp() })
            }
            .pressScaleClickable(intent = HapticIntent.Tap, pressedScale = 0.95f, onClick = onClick)
            .padding(8.dp)
    ) {
        Icon(
            data.icon,
            contentDescription = data.label,
            tint = iconTint,
            modifier = Modifier.size(24.dp)
        )
        if (selected) {
            Text(
                text = data.label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
private fun LiquidGlassFab(
    hazeState: HazeState,
    onClick: () -> Unit,
) {
    val shape = CircleShape

    Box(
        modifier = Modifier
            .size(72.dp)
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .hazeEffect(state = hazeState, style = HazeMaterials.thin(MaterialTheme.colorScheme.primary))
            .glassEffect(shape)
            .pressScaleClickable(intent = HapticIntent.Confirm, pressedScale = 0.94f, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        // Fond coloré mais transparent pour le FAB
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
        )
        Icon(
            Icons.Filled.Add,
            contentDescription = stringResource(R.string.add),
            tint = Color.White,
            modifier = Modifier.size(32.dp),
        )
    }
}


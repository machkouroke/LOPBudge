package com.lop.budget.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lop.budget.R
import com.lop.budget.ui.navigation.Routes
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials

/**
 * Barre de navigation type "Pill" flottante, inspirée du Galaxy Store.
 * 3 éléments avec indicateur de pilule sombre et bouton Ajouter séparé.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun FloatingBottomBar(
    current: String,
    onSelect: (String) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
) {
    val pillShape = RoundedCornerShape(32.dp)

    Row(
        modifier = modifier
            .fillMaxWidth(fraction = 0.95F)
            .padding(horizontal = 24.dp)
            .padding(bottom = 12.dp)
            .navigationBarsPadding(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        // --- Pilule principale de navigation ---
        Surface(
            shape = pillShape,
            color = Color.Transparent,
            modifier = Modifier
                .weight(1f)
                .height(64.dp)
                .shadow(elevation = 4.dp, shape = CircleShape)
        ) {
            Row(
                modifier = Modifier


//                    .background(MaterialTheme.colorScheme.surfaceContainer, CircleShape)
                    .hazeEffect(state = hazeState, style = HazeMaterials.regular())
                    .padding(horizontal = 8.dp)
                    .fillMaxSize()
                ,
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                GalaxyNavItem(
                    icon = Icons.Filled.Home,
                    label = stringResource(R.string.nav_home),
                    selected = current == Routes.HOME,
                    onClick = { onSelect(Routes.HOME) }
                )
                GalaxyNavItem(
                    icon = Icons.Filled.Assessment,
                    label = stringResource(R.string.nav_analytics),
                    selected = current == Routes.ANALYTICS,
                    onClick = { onSelect(Routes.ANALYTICS) }
                )
                GalaxyNavItem(
                    icon = Icons.Filled.Flag,
                    label = stringResource(R.string.nav_goals),
                    selected = current == Routes.GOALS,
                    onClick = { onSelect(Routes.GOALS) }
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        // --- Bouton Ajouter (FAB) ---
        Surface(
            onClick = onAdd,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(56.dp),
            shadowElevation = 8.dp
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = "Ajouter",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    }
}

@Composable
private fun GalaxyNavItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }

    // Animation des couleurs pour coller au style Galaxy Store
    val tintColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onSurface
        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        label = "tint"
    )

    Column(
        modifier = Modifier
            .width(80.dp)
            .fillMaxHeight(fraction = 0.8F)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .clip(RoundedCornerShape(32.dp))
            .background(if (selected) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f) else Color.Transparent),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {

        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tintColor,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.height(4.dp))

        Text(
            text = label,
            color = tintColor,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 11.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
            ),
            maxLines = 1
        )


    }
}

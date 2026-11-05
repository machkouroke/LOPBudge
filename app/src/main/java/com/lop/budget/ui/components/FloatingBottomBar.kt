package com.lop.budget.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.outlined.Assessment
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lop.budget.R
import com.lop.budget.ui.common.TestTags
import com.lop.budget.ui.motion.MotionSpec
import com.lop.budget.ui.navigation.Routes
import com.lop.budget.ui.theme.lopRimBrush
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * Barre de navigation type "Pill" flottante, inspirée du Galaxy Store.
 * 3 éléments avec indicateur de pilule sombre et bouton Ajouter séparé.
 *
 * Le bouton Ajouter ne choisit aucune opération : action, libellé accessible et repère de type
 * viennent de l'appelant, selon l'écran visible (LOP-22, I-2). Sans repère, il affiche un `+` seul.
 */
@Composable
fun FloatingBottomBar(
    current: String,
    onSelect: (String) -> Unit,
    onAdd: () -> Unit,
    addLabel: String,
    modifier: Modifier = Modifier,
    addTypeIcon: ImageVector? = null,
    hazeState: HazeState? = null,
    // Sert à mesurer le + : le ✕ du menu d'ajout se pose exactement dessus (LOP-230, P-3).
    addButtonModifier: Modifier = Modifier,
) {
    val pillShape = RoundedCornerShape(32.dp)
    // Contrôle en relief (P-6) : plus clair que les cartes en sombre, blanc en clair.
    val pillColor = MaterialTheme.colorScheme.surfaceContainerHigh


    Row(
        modifier = modifier
            .fillMaxWidth(fraction = 0.95F)
            .padding(horizontal = 24.dp)
            .padding(bottom = 12.dp)
            .navigationBarsPadding()
            .testTag(TestTags.NAV_BOTTOM_BAR),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        // --- Pilule principale de navigation ---
        // Surface en relief, liseré éclairé, ombre douce (LOP-229, CA-04, P-6).
        Surface(
            shape = pillShape,
            color = Color.Transparent,
            border = BorderStroke(1.dp, lopRimBrush()),
            modifier = Modifier
                .weight(1f)
                .height(64.dp)
                .softShadow(CircleShape)
        ) {
            // Place de chaque onglet dans la pilule, relevée au placement : l'indicateur s'y aligne.
            val tabBounds = remember { mutableStateMapOf<String, Rect>() }
            fun Modifier.tabOf(route: String) = onPlaced {
                val bounds = it.boundsInParent()
                if (tabBounds[route] != bounds) tabBounds[route] = bounds
            }

            Box(
                modifier = Modifier
                    .background(pillColor)
                    .hazeEffect(state = hazeState) {
                        backgroundColor = pillColor
                        tints = listOf(HazeTint(pillColor.copy(alpha = 0.85f)))
                    }
                    .padding(horizontal = 8.dp)
                    .fillMaxSize(),
            ) {
                tabBounds[current]?.let { NavIndicator(target = it) }
                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    GalaxyNavItem(
                        icon = Icons.Outlined.Home,
                        selectedIcon = Icons.Filled.Home,
                        label = stringResource(R.string.nav_home),
                        selected = current == Routes.HOME,
                        onClick = { onSelect(Routes.HOME) },
                        modifier = Modifier.testTag(TestTags.NAV_HOME).tabOf(Routes.HOME)
                    )
                    GalaxyNavItem(
                        icon = Icons.Outlined.Assessment,
                        selectedIcon = Icons.Filled.Assessment,
                        label = stringResource(R.string.nav_analytics),
                        selected = current == Routes.ANALYTICS,
                        onClick = { onSelect(Routes.ANALYTICS) },
                        modifier = Modifier.testTag(TestTags.NAV_ANALYTICS).tabOf(Routes.ANALYTICS)
                    )
                    GalaxyNavItem(
                        icon = Icons.Outlined.Flag,
                        selectedIcon = Icons.Filled.Flag,
                        label = stringResource(R.string.nav_goals),
                        selected = current == Routes.GOALS,
                        onClick = { onSelect(Routes.GOALS) },
                        modifier = Modifier.testTag(TestTags.NAV_GOALS).tabOf(Routes.GOALS)
                    )
                }
            }
        }

        Spacer(Modifier.width(12.dp))

        // --- Bouton Ajouter (FAB) ---
        Surface(
            onClick = onAdd,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            // Accent conservé (P-7), avec le reflet de bord des contrôles en relief (P-6).
            border = BorderStroke(
                1.dp,
                Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.35f), Color.Transparent)),
            ),
            modifier = addButtonModifier.size(56.dp).testTag(TestTags.NAV_ADD_BUTTON),
            shadowElevation = 8.dp
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (addTypeIcon == null) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = addLabel,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(28.dp)
                    )
                } else {
                    // Repère de type, `+` en coin (P-3) : le sens ne repose pas sur l'icône seule.
                    Box(Modifier.size(34.dp)) {
                        Icon(
                            imageVector = addTypeIcon,
                            contentDescription = addLabel,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(26.dp).align(Alignment.Center)
                        )
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier
                                .size(15.dp)
                                .align(Alignment.TopEnd)
                                .background(MaterialTheme.colorScheme.primary, CircleShape)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Indicateur unique de l'onglet sélectionné (LOP-226, CA-02, CA-03, P-6). Le bord avant suit un
 * ressort rapide, le bord arrière un lent : il s'étire vers l'onglet visé puis s'y resserre. Un
 * ressort repart de sa position et de sa vitesse, des taps rapides le réorientent en plein vol.
 */
@Composable
private fun NavIndicator(target: Rect) {
    val left = remember { Animatable(target.left) }
    val right = remember { Animatable(target.right) }
    LaunchedEffect(target) {
        val towardsRight = target.left > left.value
        val leading = MotionSpec.spatialFast(visibilityThreshold = 0.5f)
        val trailing = MotionSpec.spatialSlow(visibilityThreshold = 0.5f)
        launch { left.animateTo(target.left, if (towardsRight) trailing else leading) }
        launch { right.animateTo(target.right, if (towardsRight) leading else trailing) }
    }
    Box(
        Modifier
            // Lus au placement : le glissement ne recompose pas la barre.
            .layout { measurable, constraints ->
                val width = (right.value - left.value).roundToInt().coerceAtLeast(0)
                val placeable = measurable.measure(Constraints.fixed(width, target.height.roundToInt()))
                layout(constraints.maxWidth, constraints.maxHeight) {
                    placeable.place(left.value.roundToInt(), target.top.roundToInt())
                }
            }
            .background(MaterialTheme.colorScheme.surfaceDim, RoundedCornerShape(32.dp))
            .testTag(TestTags.NAV_INDICATOR)
    )
}

@Composable
private fun GalaxyNavItem(
    icon: ImageVector,
    selectedIcon: ImageVector,
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }

    // L'icône qu'on vient de choisir rebondit légèrement (LOP-226, P-6) ; pas au premier affichage.
    val iconScale = remember { Animatable(1f) }
    var wasSelected by remember { mutableStateOf(selected) }
    LaunchedEffect(selected) {
        if (selected && !wasSelected) {
            iconScale.snapTo(0.9f)
            iconScale.animateTo(1f, MotionSpec.spatialBouncy())
        } else {
            iconScale.snapTo(1f)
        }
        wasSelected = selected
    }

    // Onglet sélectionné à l'accent actif (LOP-229, CA-02). Sans la couleur, il se distingue par
    // le creux, l'icône pleine (contour ailleurs) et le gras (CA-04, P-6).
    val tintColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
        label = "tint"
    )

    Column(
        modifier = modifier
            .width(80.dp)
            .fillMaxHeight(fraction = 0.8F)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {

        Icon(
            imageVector = if (selected) selectedIcon else icon,
            contentDescription = label,
            tint = tintColor,
            modifier = Modifier
                .size(24.dp)
                .graphicsLayer {
                    scaleX = iconScale.value
                    scaleY = iconScale.value
                }
        )
        Spacer(Modifier.height(4.dp))

        Text(
            text = label,
            color = tintColor,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold
            ),
            maxLines = 1
        )
    }
}

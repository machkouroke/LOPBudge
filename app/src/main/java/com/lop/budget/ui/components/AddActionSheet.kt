package com.lop.budget.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.lop.budget.R
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.ui.common.TestTags
import com.lop.budget.ui.motion.MotionSpec
import com.lop.budget.ui.theme.LopTheme
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Menu d'ajout (LOP-230) : pile verticale calée sur le +, Revenu en haut, Dépense au plus près du
 * pouce, et un ✕ posé exactement sur le + (P-3). Titre et description de chaque action sont dans une
 * pastille opaque, lisible quel que soit l'écran flouté dessous (P-4). À l'ouverture, le + pivote en ✕
 * et les actions montent en rebond, Dépense d'abord, comme Silo ; la fermeture rejoue ce mouvement à
 * l'envers, sans rebond (P-5).
 *
 * [anchor] rend les bornes du + dans la racine ; il n'est lu qu'au placement, jamais en composition.
 *
 * Ordre de dessin du flou : `hazeEffect` peint le rendu flouté *par-dessus* ce que les modificateurs
 * précédents ont dessiné. La teinte du voile passe donc par haze lui-même, pas par un `background()`.
 */
@Composable
fun AddActionSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    onSelect: (TransactionType) -> Unit,
    hazeState: HazeState?,
    anchor: () -> Rect?,
    modifier: Modifier = Modifier,
) {
    // Thème réellement appliqué, mode Système compris (LOP-229) : `themeMode == DARK` s'y trompait.
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f

    if (visible) {
        BackHandler(onBack = onDismiss)
    }

    val scrimColor = if (isDark) Color.Black.copy(alpha = 0.55f) else Color.Black.copy(alpha = 0.30f)
    val hazeBackground = MaterialTheme.colorScheme.background
    val paneTitle = stringResource(R.string.add_action_title)

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(MotionSpec.MEDIUM_MS, easing = MotionSpec.easeOut)),
        // Le voile attend que les actions aient amorcé leur descente : il s'efface ensuite sur le +
        // réel, que le ✕ a rejoint en repivotant.
        exit = fadeOut(tween(MotionSpec.MEDIUM_MS, delayMillis = 60, easing = MotionSpec.easeOut)),
        modifier = modifier.fillMaxSize(),
    ) {
        var origin by remember { mutableStateOf(Offset.Zero) }
        Box(
            Modifier
                .fillMaxSize()
                .onGloballyPositioned { origin = it.positionInRoot() }
                // `hazeEffect(state)` seul retombe sur HazeDefaults.blurRadius (20 dp), trop faible :
                // le texte du fond restait lisible. Rayon et teinte sont donc explicites.
                .then(
                    if (hazeState != null) {
                        Modifier.hazeEffect(state = hazeState) {
                            blurRadius = 40.dp
                            noiseFactor = 0f
                            backgroundColor = hazeBackground
                            tints = listOf(HazeTint(scrimColor))
                        }
                    } else {
                        Modifier.background(scrimColor)
                    }
                )
                // Pas de `clickable` sur le voile : TalkBack y verrait un bouton sans libellé. Le ✕
                // porte la fermeture pour le lecteur d'écran.
                .pointerInput(onDismiss) { detectTapGestures { onDismiss() } }
                .semantics { this.paneTitle = paneTitle },
        ) {
            Column(
                modifier = Modifier
                    .layout { measurable, constraints ->
                        // Coin bas droit de la pile sur celui du + ; sans mesure, coin bas droit de l'écran.
                        val bounds = anchor()?.translate(-origin)
                        val right = bounds?.right?.roundToInt() ?: (constraints.maxWidth - 24.dp.roundToPx())
                        val bottom = bounds?.bottom?.roundToInt() ?: (constraints.maxHeight - 24.dp.roundToPx())
                        // Marge gauche : un libellé agrandi passe à la ligne au lieu de sortir de l'écran.
                        val placeable = measurable.measure(
                            Constraints(
                                maxWidth = (right - 16.dp.roundToPx()).coerceAtLeast(0),
                                maxHeight = bottom.coerceAtLeast(0),
                            )
                        )
                        layout(constraints.maxWidth, constraints.maxHeight) {
                            placeable.place(right - placeable.width, bottom - placeable.height)
                        }
                    }
                    .testTag(TestTags.ADD_ACTION_SHEET),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                AddActionRow(
                    label = stringResource(R.string.add_action_income),
                    description = stringResource(R.string.add_action_income_desc),
                    icon = Icons.AutoMirrored.Filled.CallMade,
                    tint = LopTheme.extended.income,
                    testTag = TestTags.ADD_ACTION_INCOME,
                    shown = visible,
                    enterDelayMs = 70,
                    exitDelayMs = 0,
                    onClick = { onSelect(TransactionType.INCOME) },
                )
                AddActionRow(
                    label = stringResource(R.string.add_action_expense),
                    description = stringResource(R.string.add_action_expense_desc),
                    icon = Icons.AutoMirrored.Filled.CallReceived,
                    tint = LopTheme.extended.expense,
                    testTag = TestTags.ADD_ACTION_EXPENSE,
                    shown = visible,
                    enterDelayMs = 30,
                    exitDelayMs = 30,
                    onClick = { onSelect(TransactionType.EXPENSE) },
                )
                CloseButton(shown = visible, onClick = onDismiss)
            }
        }
    }
}

/**
 * Une action : pastille titre + description, puis rond d'icône aligné sur le ✕. Toute la ligne est la
 * cible du tap et un seul nœud pour TalkBack (« Dépense, Sorties d'argent », bouton) ; l'icône n'a pas
 * de description, sinon elle serait lue deux fois.
 */
@Composable
private fun AddActionRow(
    label: String,
    description: String,
    icon: ImageVector,
    tint: Color,
    testTag: String,
    shown: Boolean,
    enterDelayMs: Long,
    exitDelayMs: Long,
    onClick: () -> Unit,
) {
    val rise = rememberMenuProgress(shown, enterDelayMs, exitDelayMs)
    val pillShape = MaterialTheme.shapes.small

    Row(
        modifier = Modifier
            // Monte d'environ une action plus bas, de derrière le ✕ (dessiné après), et dépasse sa
            // place avant de s'y poser (P-5).
            .graphicsLayer {
                alpha = rise.value.coerceIn(0f, 1f)
                translationY = (1f - rise.value) * 64.dp.toPx()
            }
            .pressScaleClickable(
                intent = HapticIntent.Selection,
                pressedScale = 0.96f,
                onClick = onClick,
            )
            .semantics { role = Role.Button }
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .surfaceRelief(pillShape)
                .background(MaterialTheme.colorScheme.surface, pillShape)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.End,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.End,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
            )
        }
        Box(
            modifier = Modifier
                .graphicsLayer {
                    val scale = 0.6f + 0.4f * rise.value
                    scaleX = scale
                    scaleY = scale
                }
                .size(56.dp)
                .surfaceRelief(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(26.dp),
            )
        }
    }
}

/**
 * ✕ posé sur le + : même rendu que lui (DESIGN.md § 6). C'est le glyphe + qui pivote de 45°, comme
 * dans Silo : à la première image il se confond avec le bouton qu'il recouvre, et il redevient + à la
 * fermeture (P-5).
 */
@Composable
private fun CloseButton(shown: Boolean, onClick: () -> Unit) {
    val turn = rememberMenuProgress(shown, enterDelayMs = 0, exitDelayMs = 0)

    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary,
        border = BorderStroke(
            1.dp,
            Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.35f), Color.Transparent)),
        ),
        shadowElevation = 8.dp,
        modifier = Modifier
            .size(56.dp)
            .testTag(TestTags.ADD_ACTION_CLOSE),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = stringResource(R.string.close),
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .size(28.dp)
                    .graphicsLayer { rotationZ = 45f * turn.value },
            )
        }
    }
}

/**
 * Progression du menu, de 0 (replié dans le +) à 1 (en place). À l'ouverture, le ressort rebondissant
 * de l'app la fait dépasser 1 avant de s'y poser ; à la fermeture, le ressort ferme des overlays la
 * ramène à 0 sans rebond, pendant que `AnimatedVisibility` garde le contenu le temps de son fondu.
 * Rouvrir en pleine fermeture repart de la valeur courante. Avec les animations système coupées, les
 * ressorts aboutissent aussitôt.
 */
@Composable
private fun rememberMenuProgress(
    shown: Boolean,
    enterDelayMs: Long,
    exitDelayMs: Long,
): Animatable<Float, AnimationVector1D> {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(shown) {
        if (shown) {
            delay(enterDelayMs)
            progress.animateTo(1f, MotionSpec.floatSpring())
        } else {
            delay(exitDelayMs)
            progress.animateTo(0f, MotionSpec.sheetExitSpring())
        }
    }
    return progress
}

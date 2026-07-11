package com.lop.budget.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Composant générique de swipe pour les lignes de transaction.
 *
 * Implémentation manuelle avec [detectHorizontalDragGestures] + [Animatable] pour contourner
 * le bug Material3 1.4.0+ où [rememberSwipeToDismissBoxState.positionalThreshold] est ignoré
 * (seuil toujours bloqué à 50%).
 * Voir : https://stackoverflow.com/questions/79834653/
 *
 * - Swipe droite (StartToEnd) : toggle Payé / Non payé — la ligne revient à sa place
 * - Swipe gauche  (EndToStart) : suppression via onDelete()
 *
 * Seuil configurable via [thresholdFraction] (défaut 40% de la largeur du composant).
 * Un fling rapide (vélocité > [flingVelocityThreshold] px/s) déclenche l'action même
 * si le seuil positionnel n'est pas atteint.
 */
@Composable
fun SwipeableTransactionRow(
    isPaid: Boolean,
    onTogglePaid: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    /** Fraction de la largeur à dépasser pour confirmer le swipe (0f–1f). */
    thresholdFraction: Float = 0.40f,
    /** Vélocité minimale (px/s) pour déclencher l'action par fling. */
    flingVelocityThreshold: Float = 800f,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    // Largeur mesurée du composant (en px), mise à jour par onSizeChanged
    var componentWidthPx by remember { mutableFloatStateOf(0f) }

    // Offset horizontal animé (en px) : positif = droite, négatif = gauche
    val offsetX = remember { Animatable(0f) }

    // Direction courante du swipe pour l'affichage du fond
    var swipeDirection by remember { mutableStateOf<SwipeDir>(SwipeDir.None) }

    // Retour haptique déclenché une seule fois par passage du seuil
    var hapticFired by remember { mutableStateOf(false) }

    // Garde-fou : une seule action par geste
    var actionFired by remember { mutableStateOf(false) }

    /** Remet l'item à sa position initiale avec animation. */
    fun resetToSettled() {
        scope.launch {
            offsetX.animateTo(0f, tween(250))
            swipeDirection = SwipeDir.None
            hapticFired = false
            actionFired = false
        }
    }

    Box(
        modifier = modifier
            .onSizeChanged { componentWidthPx = it.width.toFloat() }
    ) {
        // ── Fond coloré ──────────────────────────────────────────────────────
        val bgColor by remember(swipeDirection, isPaid) {
            mutableStateOf(
                when (swipeDirection) {
                    SwipeDir.Right -> if (isPaid) Color(0xFFE53935) else Color(0xFF4CAF50)
                    SwipeDir.Left -> Color(0xFFE53935)
                    SwipeDir.None -> Color.Transparent
                }
            )
        }
        val bgAlignment = when (swipeDirection) {
            SwipeDir.Right -> Alignment.CenterStart
            SwipeDir.Left -> Alignment.CenterEnd
            SwipeDir.None -> Alignment.Center
        }
        val bgIcon = when (swipeDirection) {
            SwipeDir.Right -> if (isPaid) Icons.Default.Close else Icons.Default.Check
            SwipeDir.Left -> Icons.Default.Delete
            SwipeDir.None -> null
        }

        Box(
            modifier = Modifier
                .matchParentSize()
                .background(bgColor, RoundedCornerShape(24.dp))
                .padding(horizontal = 20.dp),
            contentAlignment = bgAlignment,
        ) {
            if (bgIcon != null) {
                Icon(imageVector = bgIcon, contentDescription = null, tint = Color.White)
            }
        }

        // ── Contenu avant-plan (glissant) ────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .pointerInput(isPaid) {
                    val velocityTracker = VelocityTracker()
                    detectHorizontalDragGestures(
                        onDragStart = {
                            actionFired = false
                            hapticFired = false
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            velocityTracker.addPosition(
                                change.uptimeMillis,
                                change.position,
                            )
                            val newOffset = offsetX.value + dragAmount
                            // Limiter le déplacement à ±80% de la largeur
                            val clamped = newOffset.coerceIn(
                                -componentWidthPx * 0.80f,
                                componentWidthPx * 0.80f,
                            )
                            scope.launch { offsetX.snapTo(clamped) }

                            // Mise à jour de la direction affichée
                            swipeDirection = when {
                                clamped > 4f  -> SwipeDir.Right
                                clamped < -4f -> SwipeDir.Left
                                else          -> SwipeDir.None
                            }

                            // Retour haptique au passage du seuil
                            val threshold = componentWidthPx * thresholdFraction
                            val pastThreshold = abs(clamped) >= threshold
                            if (pastThreshold && !hapticFired) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                hapticFired = true
                            } else if (!pastThreshold) {
                                hapticFired = false
                            }
                        },
                        onDragEnd = {
                            if (actionFired) return@detectHorizontalDragGestures

                            val velocity = velocityTracker.calculateVelocity().x
                            val threshold = componentWidthPx * thresholdFraction
                            val currentOffset = offsetX.value

                            val triggeredByPosition = abs(currentOffset) >= threshold
                            val triggeredByFling = abs(velocity) >= flingVelocityThreshold &&
                                    // Le fling doit aller dans la même direction que le drag
                                    (velocity > 0f) == (currentOffset > 0f)

                            if (triggeredByPosition || triggeredByFling) {
                                actionFired = true
                                if (currentOffset > 0f) {
                                    // Swipe droite → toggle payé
                                    onTogglePaid()
                                } else {
                                    // Swipe gauche → supprimer
                                    onDelete()
                                }
                            }
                            // Dans tous les cas, on remet l'item à sa place
                            resetToSettled()
                        },
                        onDragCancel = { resetToSettled() },
                    )
                },
        ) {
            content()
        }
    }
}

/** Direction interne du swipe en cours. */
private enum class SwipeDir { None, Left, Right }

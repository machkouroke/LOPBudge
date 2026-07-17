package com.lop.budget.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Un wrapper qui permet de fermer un écran par un swipe vers le bas.
 * Typiquement utilisé pour les écrans de détails.
 */
@Composable
fun SwipeDownDismissWrapper(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit
) {
    val offsetY = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    
    // Seuil de déclenchement de la fermeture (en pixels)
    // On peut baser ça sur une fraction de l'écran plus tard si besoin
    val dismissThreshold = 400f

    Box(
        modifier = Modifier
            .offset { IntOffset(0, offsetY.value.roundToInt().coerceAtLeast(0)) }
            .alpha((1f - (offsetY.value / 1000f)).coerceIn(0.5f, 1f))
            .draggable(
                state = rememberDraggableState { delta ->
                    scope.launch {
                        offsetY.snapTo(offsetY.value + delta)
                    }
                },
                orientation = Orientation.Vertical,
                onDragStopped = { velocity ->
                    if (offsetY.value > dismissThreshold || velocity > 1000f) {
                        // Dismiss
                        onDismiss()
                    } else {
                        // Retour à la position initiale
                        scope.launch {
                            offsetY.animateTo(0f, spring())
                        }
                    }
                }
            )
    ) {
        content()
    }
}

package com.lop.budget.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Composant générique de swipe pour les lignes de transaction.
 *
 * - Swipe droite (StartToEnd) : toggle Payé / Non payé — la ligne reste visible
 * - Swipe gauche (EndToStart)  : suppression avec animation de disparition + callback onDelete
 *
 * Utilise la nouvelle API [rememberSwipeToDismissBoxState] sans `confirmValueChange`
 * (paramètre déprécié depuis Material3 1.3.x). La logique métier est gérée via
 * [LaunchedEffect] qui observe [SwipeToDismissBoxState.currentValue].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeableTransactionRow(
    isPaid: Boolean,
    onTogglePaid: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var isRemoved by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Nouvelle API : pas de confirmValueChange (déprécié).
    // positionalThreshold : le swipe doit couvrir 40 % de la largeur pour se déclencher.
    val dismissState = rememberSwipeToDismissBoxState(
        initialValue = SwipeToDismissBoxValue.Settled,
        positionalThreshold = { totalDistance -> totalDistance * 0.9f },
    )

    // Observer currentValue pour déclencher la logique métier après que le swipe est confirmé.
    LaunchedEffect(dismissState.currentValue) {
        when (dismissState.currentValue) {
            SwipeToDismissBoxValue.StartToEnd -> {
                // Swipe droite : toggle statut, puis reset immédiat de la position
                onTogglePaid()
                scope.launch { dismissState.snapTo(SwipeToDismissBoxValue.Settled) }
            }
            SwipeToDismissBoxValue.EndToStart -> {
                // Swipe gauche : animation de disparition puis suppression
                isRemoved = true
                delay(300)
                onDelete()
            }
            SwipeToDismissBoxValue.Settled -> Unit
        }
    }

    AnimatedVisibility(
        visible = !isRemoved,
        exit = shrinkVertically(
            animationSpec = tween(durationMillis = 300),
            shrinkTowards = Alignment.Top,
        ) + fadeOut(animationSpec = tween(durationMillis = 300)),
    ) {
        SwipeToDismissBox(
            state = dismissState,
            modifier = modifier,
            backgroundContent = {
                val direction = dismissState.dismissDirection
                val bgColor = when (direction) {
                    SwipeToDismissBoxValue.StartToEnd ->
                        if (isPaid) Color(0xFFE53935) else Color(0xFF4CAF50)
                    SwipeToDismissBoxValue.EndToStart -> Color(0xFFE53935)
                    else -> Color.Transparent
                }
                val alignment = when (direction) {
                    SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
                    SwipeToDismissBoxValue.EndToStart -> Alignment.CenterEnd
                    else -> Alignment.Center
                }
                val icon = when (direction) {
                    SwipeToDismissBoxValue.StartToEnd ->
                        if (isPaid) Icons.Default.Close else Icons.Default.Check
                    SwipeToDismissBoxValue.EndToStart -> Icons.Default.Delete
                    else -> null
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
//                        .padding(horizontal = 20.dp, vertical = 6.dp)
                        .background(
                            color = bgColor,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                        )
                        .padding(horizontal = 20.dp),
                    contentAlignment = alignment,
                ) {
                    if (icon != null) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = Color.White,
                        )
                    }
                }
            },
            content = { content() },
        )
    }
}

package com.lop.budget.ui.components

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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Composant générique de swipe pour les lignes de transaction.
 *
 * - Swipe droite (StartToEnd) : toggle Payé / Non payé — la ligne reste visible
 * - Swipe gauche (EndToStart)  : suppression via onDelete()
 *
 * Utilise un flag [hasActionFired] pour garantir qu'une action de swipe ne se déclenche
 * qu'une seule fois par composant, même si LazyColumn réutilise l'instance après un Undo.
 * Quand la transaction est restaurée (Undo), LazyColumn détruit et recrée le composant
 * avec une nouvelle instance de [hasActionFired] à false.
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
        val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    var hasActionFired by remember { mutableStateOf(false) }

    // On utilise un seuil de 40% pour déclencher le swipe (totalDistance * 0.4f).
    // Si l'utilisateur relâche avant 40%, l'item revient à sa place.
    // S'il swipe rapidement (fling), la vélocité déclenche l'action même avant 40%.
    val dismissState = rememberSwipeToDismissBoxState(
        initialValue = SwipeToDismissBoxValue.Settled,
        positionalThreshold = { totalDistance -> totalDistance * 0.4f },
    )

    // Retour haptique lors du passage du seuil (quand l'icône apparaît / change d'état)
    LaunchedEffect(dismissState.targetValue) {
        if (dismissState.targetValue != SwipeToDismissBoxValue.Settled) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    LaunchedEffect(dismissState.currentValue) {
        if (hasActionFired) return@LaunchedEffect
        when (dismissState.currentValue) {
            SwipeToDismissBoxValue.StartToEnd -> {
                hasActionFired = true
                onTogglePaid()
                scope.launch {
                    dismissState.snapTo(SwipeToDismissBoxValue.Settled)
                    hasActionFired = false
                }
            }
            SwipeToDismissBoxValue.EndToStart -> {
                hasActionFired = true
                onDelete()
                // Pour éviter que l'item ne reste "bloqué" à l'écran si le filtre in-memory 
                // met quelques millisecondes à s'appliquer, on force le reset visuel immédiatement.
                // Comme la clé Compose (versionnée) changera au prochain Undo, ce composant
                // sera détruit de toute façon, mais au moins il ne glitchera pas pendant la transition.
                scope.launch {
                    dismissState.snapTo(SwipeToDismissBoxValue.Settled)
                }
            }
            SwipeToDismissBoxValue.Settled -> Unit
        }
    }

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

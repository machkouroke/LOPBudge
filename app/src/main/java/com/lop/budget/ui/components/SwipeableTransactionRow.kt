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
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Composant générique de swipe pour les lignes de transaction.
 *
 * - Swipe droite (StartToEnd) : toggle Payé / Non payé — la ligne reste visible
 * - Swipe gauche (EndToStart)  : suppression immédiate (onDelete).
 *
 * NOTE: La disparition visuelle est entièrement gérée par la LazyColumn.
 * Dès que onDelete() (qui fait un softDelete en DB) est appelé, la transaction
 * est retirée du StateFlow et la LazyColumn supprime le composant de l'UI.
 * S'il y a un "Undo", la transaction réapparaît dans le StateFlow et est redessinée.
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

    val dismissState = rememberSwipeToDismissBoxState(
        initialValue = SwipeToDismissBoxValue.Settled,
        positionalThreshold = { totalDistance -> totalDistance * 0.4f },
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
                // Swipe gauche : suppression (soft-delete en DB).
                // Pas besoin de reset l'état ici, le composant va être détruit par la LazyColumn.
                onDelete()
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
                    .padding(horizontal = 20.dp, vertical = 6.dp)
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

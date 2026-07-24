package com.lop.budget.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.lop.budget.R
import com.lop.budget.ui.theme.ExpenseCoral
import com.lop.budget.ui.theme.IncomeGreen
import com.lop.budget.util.Format
import kotlinx.coroutines.launch
import java.time.YearMonth
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun BalanceDashboardWidget(
    month: YearMonth,
    income: Double,
    expense: Double,
    currency: String,
    onPrevMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onOpenMonthly: (com.lop.budget.domain.model.TransactionType) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val offsetX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    
    // Threshold for triggering month change (30% of typical screen width, roughly 100dp)
    val threshold = 300f 
    
    val solde = income - expense
    val soldeColor = when {
        solde > 50 -> IncomeGreen
        solde < -50 -> ExpenseCoral
        else -> com.lop.budget.ui.theme.CategoryOrange
    }
    
    val monthName = month.month.name.lowercase().capitalize(Locale.ROOT)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .draggable(
                state = rememberDraggableState { delta ->
                    // Add resistance to the drag
                    val resistance = 0.5f
                    scope.launch {
                        offsetX.snapTo(offsetX.value + delta * resistance)
                    }
                },
                orientation = Orientation.Horizontal,
                onDragStopped = {
                    if (abs(offsetX.value) > threshold) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (offsetX.value > 0) onPrevMonth() else onNextMonth()
                    }
                    offsetX.animateTo(0f, spring(stiffness = Spring.StiffnessLow))
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        // Directional Indicators (Arrows)
        val dragProgress = (abs(offsetX.value) / threshold).coerceIn(0f, 1f)
        
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .graphicsLayer { alpha = dragProgress },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (offsetX.value > 0) {
                // Dragging right -> Previous month
                Indicator(icon = Icons.AutoMirrored.Filled.ArrowBack, label = "Précédent")
                Spacer(Modifier.weight(1f))
            } else if (offsetX.value < 0) {
                // Dragging left -> Next month
                Spacer(Modifier.weight(1f))
                Indicator(icon = Icons.AutoMirrored.Filled.ArrowForward, label = "Suivant")
            }
        }

        // The Main Card Content
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .graphicsLayer {
                    // Subtle scale down effect when dragging
                    val scale = 1f - (dragProgress * 0.05f)
                    scaleX = scale
                    scaleY = scale
                },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Solde de $monthName",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = Format.money(solde, currency),
                style = MaterialTheme.typography.displayLarge,
                fontWeight = FontWeight.Bold,
                color = soldeColor
            )
            Spacer(Modifier.height(32.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                StatCard(
                    label = stringResource(R.string.expense),
                    amount = expense,
                    currency = currency,
                    icon = Icons.Filled.ArrowDownward,
                    color = ExpenseCoral,
                    modifier = Modifier.weight(1f).clickableNoRipple { onOpenMonthly(com.lop.budget.domain.model.TransactionType.EXPENSE) }
                )
                StatCard(
                    label = stringResource(R.string.income),
                    amount = income,
                    currency = currency,
                    icon = Icons.Filled.ArrowUpward,
                    color = IncomeGreen,
                    modifier = Modifier.weight(1f).clickableNoRipple { onOpenMonthly(com.lop.budget.domain.model.TransactionType.INCOME) }
                )
            }
        }
    }
}

@Composable
private fun Indicator(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun StatCard(label: String, amount: Double, currency: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color, modifier: Modifier = Modifier) {
    FloatingCard(modifier = modifier, color = MaterialTheme.colorScheme.surfaceVariant, contentPadding = PaddingValues(16.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            CircleIcon(icon = icon, tint = color, background = color.copy(alpha = 0.15f), size = 40.dp)
            Spacer(Modifier.height(12.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(Format.money(amount, currency), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

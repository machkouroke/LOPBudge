package com.lop.budget.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lop.budget.R
import com.lop.budget.ui.theme.ExpenseCoral
import com.lop.budget.ui.theme.IncomeGreen
import com.lop.budget.util.Format
import kotlinx.coroutines.launch
import java.time.YearMonth
import java.time.format.TextStyle
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
    
    // Reduced threshold for easier validation (from 300f to 180f)
    val threshold = 200f
    
    val solde = income - expense
    val soldeColor = when {
        solde > 50 -> IncomeGreen
        solde < -50 -> ExpenseCoral
        else -> com.lop.budget.ui.theme.CategoryOrange
    }
    
    val locale = Locale.getDefault()
    val monthName = remember(month) {
        month.month.getDisplayName(TextStyle.FULL, locale).replaceFirstChar { it.uppercase() }
    }

    val prevMonthLabel = remember(month) {
        val prev = month.minusMonths(1)
        val name = prev.month.getDisplayName(TextStyle.FULL, locale).replaceFirstChar { it.uppercase() }
        "$name ${prev.year}"
    }
    val nextMonthLabel = remember(month) {
        val next = month.plusMonths(1)
        val name = next.month.getDisplayName(TextStyle.FULL, locale).replaceFirstChar { it.uppercase() }
        "$name ${next.year}"
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                // Use detectHorizontalDragGestures for more reliable gesture handling
                detectHorizontalDragGestures(
                    onDragEnd = {
                        scope.launch {
                            if (abs(offsetX.value) > threshold) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                if (offsetX.value > 0) onPrevMonth() else onNextMonth()
                            }
                            // Always animate back to 0 to prevent getting "stuck"
                            offsetX.animateTo(0f, spring(stiffness = Spring.StiffnessLow))
                        }
                    },
                    onDragCancel = {
                        scope.launch {
                            offsetX.animateTo(0f, spring(stiffness = Spring.StiffnessLow))
                        }
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        val resistance = 0.5f
                        scope.launch {
                            offsetX.snapTo(offsetX.value + dragAmount * resistance)
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        val dragProgress = (abs(offsetX.value) / threshold).coerceIn(0f, 1f)
        
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .graphicsLayer { alpha = dragProgress },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (offsetX.value > 0) {
                Indicator(icon = Icons.AutoMirrored.Filled.ArrowBack, label = prevMonthLabel)
                Spacer(Modifier.weight(1f))
            } else if (offsetX.value < 0) {
                Spacer(Modifier.weight(1f))
                Indicator(icon = Icons.AutoMirrored.Filled.ArrowForward, label = nextMonthLabel)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .graphicsLayer {
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
                color = soldeColor,
                modifier = Modifier.testTag("dashboard_balance_text")
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
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
        Text(
            text = label, 
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp), 
            color = MaterialTheme.colorScheme.primary, 
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
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

package com.lop.budget.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.domain.model.RecurrenceFrequency
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.ui.theme.LopTheme
import com.lop.budget.util.Format
import com.lop.budget.util.IconMapper

@Composable
fun SwipeableTransactionRow(
    item: TransactionWithRelations,
    currency: String,
    onClick: () -> Unit,
    onToggleStatus: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    showAccount: Boolean = true,
) {
    val ext = LopTheme.extended
    val haptic = LocalHapticFeedback.current

    val tx = item.transaction
    val isIncome = tx.type == TransactionType.INCOME
    val isPaid = tx.status == TransactionStatus.PAID

    @Suppress("DEPRECATION")
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    onToggleStatus()
                    false
                }
                SwipeToDismissBoxValue.EndToStart -> {
                    onDelete()
                    true
                }
                SwipeToDismissBoxValue.Settled -> false
            }
        },
        positionalThreshold = { distance -> distance * 0.45f },
    )

    LaunchedEffect(dismissState.targetValue) {
        if (dismissState.targetValue != SwipeToDismissBoxValue.Settled) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier,
        backgroundContent = {
            SwipeBackground(
                direction = dismissState.targetValue,
                isPaid = isPaid,
                paidColor = ext.income,
                deleteColor = ext.expense,
            )
        },
    ) {
        val amountColor = if (isIncome) ext.income else ext.expense
        val catColor = item.category?.colorArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.primary
        val recurring = tx.recurrenceFrequency != RecurrenceFrequency.NONE
        val contentAlpha = if (isPaid) 0.62f else 1f

        FloatingCard(
            modifier = Modifier.fillMaxWidth().clickableNoRipple(onClick),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box {
                    CircleIcon(
                        icon = IconMapper.get(item.category?.icon ?: "category"),
                        tint = catColor,
                        background = catColor.copy(alpha = 0.18f),
                        modifier = Modifier.alpha(contentAlpha),
                    )
                    if (isPaid) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = "Réglé",
                            tint = ext.income,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .size(18.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surface),
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            tx.title,
                            style = MaterialTheme.typography.titleMedium,
                            textDecoration = if (isPaid) TextDecoration.LineThrough else null,
                            modifier = Modifier.alpha(contentAlpha),
                        )
                        if (recurring) {
                            Spacer(Modifier.width(6.dp))
                            Icon(
                                Icons.Filled.Replay,
                                contentDescription = "Récurrent",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(15.dp).alpha(contentAlpha),
                            )
                        }
                    }
                    Text(
                        if (showAccount) (item.account?.name ?: "") else Format.dayMonth(tx.date),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.alpha(contentAlpha),
                    )
                }
                Text(
                    (if (isIncome) "+" else "−") + Format.money(tx.amount, currency),
                    style = MaterialTheme.typography.titleMedium,
                    color = amountColor,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.alpha(contentAlpha),
                )
            }
        }
    }
}

@Composable
private fun SwipeBackground(
    direction: SwipeToDismissBoxValue,
    isPaid: Boolean,
    paidColor: Color,
    deleteColor: Color,
) {
    val (color, icon, label, alignment) = when (direction) {
        SwipeToDismissBoxValue.StartToEnd -> SwipeVisual(
            color = paidColor,
            icon = if (isPaid) Icons.Filled.Replay else Icons.Filled.CheckCircle,
            label = if (isPaid) "À régler" else "Réglé",
            alignment = Alignment.CenterStart,
        )
        SwipeToDismissBoxValue.EndToStart -> SwipeVisual(
            color = deleteColor,
            icon = Icons.Filled.Delete,
            label = "Supprimer",
            alignment = Alignment.CenterEnd,
        )
        SwipeToDismissBoxValue.Settled -> SwipeVisual(Color.Transparent, null, "", Alignment.Center)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(28.dp))
            .background(color.copy(alpha = 0.22f))
            .padding(horizontal = 24.dp),
        contentAlignment = alignment,
    ) {
        if (icon != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (alignment == Alignment.CenterStart) {
                    Icon(icon, contentDescription = label, tint = color, modifier = Modifier.size(24.dp))
                    Text(label, color = color, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                } else {
                    Text(label, color = color, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    Icon(icon, contentDescription = label, tint = color, modifier = Modifier.size(24.dp))
                }
            }
        }
    }
}

private data class SwipeVisual(
    val color: Color,
    val icon: ImageVector?,
    val label: String,
    val alignment: Alignment,
)

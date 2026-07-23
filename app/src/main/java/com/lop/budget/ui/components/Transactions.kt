package com.lop.budget.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lop.budget.R
import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.domain.model.DayGroup
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.ui.theme.LopTheme
import com.lop.budget.util.Format
import com.lop.budget.util.IconMapper
import dev.chrisbanes.haze.HazeState
import java.time.format.TextStyle
import java.util.Locale

/**
 * Extension pour LazyListScope permettant d'afficher des groupes de transactions par jour
 * avec support du swipe et du marquage payé/non payé.
 */
fun LazyListScope.transactionDayGroups(
    dayGroups: List<DayGroup>,
    currency: String,
    txVersions: Map<Long, Int>,
    onOpenTransaction: (Long) -> Unit,
    onMaterializeAndOpen: (Long, Long) -> Unit,
    onTogglePaid: (Long, TransactionStatus) -> Unit,
    onDeleteRequest: (TransactionWithRelations) -> Unit,
    onPreviewTransaction: (TransactionWithRelations) -> Unit,
    onDeleteSimple: (Long) -> Unit,
    hazeState: HazeState? = null
) {
    dayGroups.forEach { day ->
        item(key = "day_header_${day.date}", contentType = "day_header") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${day.date.dayOfMonth} ${day.date.month.getDisplayName(TextStyle.SHORT, Locale.FRANCE)}",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = Format.money(day.total, currency),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        items(
            items = day.transactions,
            key = { tx ->
                val id = tx.transaction.id
                if (id < 0L) {
                    // Unique stable key for virtual transactions without heavy string concat
                    "v_${tx.transaction.seriesId}_${tx.transaction.seriesDate}"
                } else {
                    // For real transactions, use Long ID if versions are not changing often
                    // If versions are needed for Undo, concat is necessary but we can use a simpler format
                    if (txVersions.isEmpty()) id else "${id}_${txVersions[id] ?: 0}"
                }
            },
            contentType = { "transaction" }
        ) { tx ->
            Box(modifier = Modifier.animateItem()) {
                TransactionRow(
                    tx = tx,
                    currency = currency,
                    onOpenTransaction = onOpenTransaction,
                    onMaterializeAndOpen = onMaterializeAndOpen,
                    onTogglePaid = onTogglePaid,
                    onDeleteRequest = onDeleteRequest,
                    onPreviewTransaction = onPreviewTransaction,
                    onDeleteSimple = onDeleteSimple,
                    hazeState = hazeState
                )
            }
        }
    }
}

@Composable
fun TransactionRow(
    tx: TransactionWithRelations,
    currency: String,
    onOpenTransaction: (Long) -> Unit,
    onMaterializeAndOpen: (Long, Long) -> Unit,
    onTogglePaid: (Long, TransactionStatus) -> Unit,
    onDeleteRequest: (TransactionWithRelations) -> Unit,
    onPreviewTransaction: (TransactionWithRelations) -> Unit,
    onDeleteSimple: (Long) -> Unit,
    hazeState: HazeState? = null
) {
    val ext = LopTheme.extended
    val isIncome = tx.transaction.type == TransactionType.INCOME
    val amountColor = if (isIncome) ext.income else ext.expense
    val catColor = tx.category?.colorArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.primary
    val isPaid = tx.transaction.status == TransactionStatus.PAID
    
    SwipeableTransactionRow(
        isPaid = isPaid,
        onTogglePaid = { onTogglePaid(tx.transaction.id, tx.transaction.status) },
        onDelete = {
            if (tx.transaction.seriesId != null) {
                onDeleteRequest(tx)
            } else {
                onDeleteSimple(tx.transaction.id)
            }
        }
    ) {
        FloatingCard(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickableHaptic(
                    onClick = {
                        if (tx.transaction.id >= 0L) onOpenTransaction(tx.transaction.id)
                        else tx.transaction.seriesId?.let { onMaterializeAndOpen(it.toLong(), tx.transaction.seriesDate!!) }
                    },
                    onLongClick = { onPreviewTransaction(tx) }
                )
                .graphicsLayer {
                    alpha = if (isPaid) 0.5f else 1f
                },
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            contentPadding = PaddingValues(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircleIcon(
                    icon = IconMapper.get(tx.category?.icon ?: "category"),
                    tint = catColor,
                    background = catColor.copy(alpha = 0.18f),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(tx.transaction.title, style = MaterialTheme.typography.titleMedium)
                        if (tx.transaction.seriesId != null) {
                            Spacer(Modifier.width(6.dp))
                            Icon(
                                Icons.Filled.Repeat,
                                stringResource(R.string.home_recurring_tag),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }
                    Text(
                        tx.account?.name ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    if (tx.tags.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            tx.tags.forEach { tag ->
                                PillTag(
                                    text = tag.name,
                                    color = Color(tag.colorArgb).copy(alpha = 0.8f),
                                )
                            }
                        }
                    }
                }
                Text(
                    (if (isIncome) "+" else "−") + Format.money(tx.transaction.amount, currency),
                    style = MaterialTheme.typography.titleMedium,
                    color = amountColor,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

package com.lop.budget.ui.screens.monthly

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lop.budget.R
import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.domain.model.SeriesDeletionMode
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.ui.components.CircleIcon
import com.lop.budget.ui.components.DonutChart
import com.lop.budget.ui.components.DonutSlice
import com.lop.budget.ui.components.FloatingCard
import com.lop.budget.ui.components.RecurringDeleteChoice
import com.lop.budget.ui.components.RecurringDeleteSheet
import com.lop.budget.ui.components.SwipeableTransactionRow
import com.lop.budget.ui.components.clickableNoRipple
import com.lop.budget.ui.theme.LopTheme
import com.lop.budget.util.Format
import com.lop.budget.util.IconMapper
import java.time.format.TextStyle
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MonthlyTransactionsScreen(
    onBack: () -> Unit,
    onOpenTransaction: (Long) -> Unit,
    snackbarHostState: androidx.compose.material3.SnackbarHostState = remember { androidx.compose.material3.SnackbarHostState() },
    vm: MonthlyTransactionsViewModel = hiltViewModel(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val ext = LopTheme.extended
    val context = LocalContext.current

    val title = if (state.type == TransactionType.EXPENSE) stringResource(R.string.expense) else stringResource(R.string.income)
    val accent = if (state.type == TransactionType.EXPENSE) ext.expense else ext.income

    val top = state.breakdown.take(6)
    val othersTotal = state.breakdown.drop(6).sumOf { it.total }
    val slices = buildList {
        top.forEach { add(DonutSlice(it.total, Color(it.colorArgb), it.name)) }
        if (othersTotal > 0) add(DonutSlice(othersTotal, Color(0xFF9E9E9E), stringResource(R.string.others)))
    }

    var showDeleteConfirmForTx by remember { mutableStateOf<TransactionWithRelations?>(null) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), modifier = Modifier.size(26.dp).clickableNoRipple(onBack))
                Text(title, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.size(26.dp))
            }
        }

        item {
            Text(
                "${state.month.month.getDisplayName(TextStyle.FULL, Locale.FRANCE).replaceFirstChar { it.uppercase() }} ${state.month.year}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Filtre payé
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilterChip(stringResource(R.string.monthly_filter_all), state.filter == PaidFilter.ALL, Modifier.weight(1f), accent) { vm.setFilter(PaidFilter.ALL) }
                FilterChip(stringResource(R.string.monthly_filter_paid), state.filter == PaidFilter.PAID, Modifier.weight(1f), accent) { vm.setFilter(PaidFilter.PAID) }
                FilterChip(stringResource(R.string.monthly_filter_planned), state.filter == PaidFilter.PLANNED, Modifier.weight(1f), accent) { vm.setFilter(PaidFilter.PLANNED) }
            }
        }

        // Insights + Toggle Mode
        item {
            FloatingCard(Modifier.fillMaxWidth()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        InsightToggle(
                            label = "Catégories",
                            selected = state.insightMode == InsightMode.CATEGORY,
                            accent = accent,
                            onClick = { vm.setInsightMode(InsightMode.CATEGORY) }
                        )
                        Spacer(Modifier.width(8.dp))
                        InsightToggle(
                            label = "Étiquettes",
                            selected = state.insightMode == InsightMode.TAG,
                            accent = accent,
                            onClick = { vm.setInsightMode(InsightMode.TAG) }
                        )
                    }

                    if (slices.isEmpty()) {
                        Text(stringResource(R.string.monthly_no_data), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(40.dp))
                    } else {
                        DonutChart(slices = slices) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(stringResource(R.string.total), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    Format.money(state.total, state.currency),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = accent,
                                )
                            }
                        }
                    }
                }
            }
        }

        // Breakdown
        items(state.breakdown, key = { it.name }) { b ->
            FloatingCard(
                Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(14.dp).clip(CircleShape).background(Color(b.colorArgb)))
                    Spacer(Modifier.width(12.dp))
                    Text(b.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text("${(b.share * 100).toInt()} %", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(12.dp))
                    Text(Format.money(b.total, state.currency), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        item {
            Text(stringResource(R.string.monthly_transactions_title), style = MaterialTheme.typography.titleLarge)
        }

        items(state.transactions, key = { tx -> 
            val id = tx.transaction.id
            if (id < 0L) "tx_virtual_${tx.transaction.seriesId}_${tx.transaction.seriesDate}" 
            else "tx_${id}_v${state.txVersions[id] ?: 0}"
        }) { tx ->
            val catColor = tx.category?.colorArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.primary
            val isPaid = tx.transaction.status == TransactionStatus.PAID
            
            val txDeletedMsg = stringResource(R.string.tx_deleted_snackbar)
            val undoMsg = stringResource(R.string.undo)

            SwipeableTransactionRow(
                isPaid = isPaid,
                onTogglePaid = { vm.togglePaid(tx.transaction.id, tx.transaction.status) },
                onDelete = {
                    if (tx.transaction.seriesId != null) {
                        showDeleteConfirmForTx = tx
                    } else {
                        vm.deleteWithUndo(tx.transaction.id, snackbarHostState, txDeletedMsg, undoMsg)
                    }
                }
            ) {
                FloatingCard(
                    modifier = Modifier.fillMaxWidth().clickableNoRipple { 
                        if (tx.transaction.id >= 0L) {
                            onOpenTransaction(tx.transaction.id) 
                        } else if (tx.transaction.seriesId != null) {
                            vm.materializeAndOpen(tx.transaction.seriesId!!.toLong(), tx.transaction.seriesDate!!, onOpenTransaction)
                        }
                    }.alpha(if (isPaid) 0.5f else 1f),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
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
                                    Icon(Icons.Filled.Repeat, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(15.dp))
                                }
                            }
                            Text(Format.dayMonth(tx.transaction.date), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (tx.tags.isNotEmpty()) {
                                Spacer(Modifier.height(4.dp))
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    tx.tags.take(3).forEach { tag ->
                                        com.lop.budget.ui.components.PillTag(
                                            text = tag.name,
                                            color = Color(tag.colorArgb)
                                        )
                                    }
                                }
                            }
                        }
                        Text(
                            (if (state.type == TransactionType.INCOME) "+" else "−") + Format.money(tx.transaction.amount, state.currency),
                            style = MaterialTheme.typography.titleMedium,
                            color = accent,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }

        if (state.transactions.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.monthly_no_transactions),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (showDeleteConfirmForTx != null) {
        val toDelete = showDeleteConfirmForTx!!
        RecurringDeleteSheet(
            onDismiss = { showDeleteConfirmForTx = null },
            showFutureOnly = true,
            onChoose = { choice ->
                showDeleteConfirmForTx = null
                when (choice) {
                    RecurringDeleteChoice.THIS_OCCURRENCE -> {
                        vm.deleteWithUndo(toDelete.transaction.id, snackbarHostState, context.getString(R.string.tx_deleted_snackbar), context.getString(R.string.undo))
                    }
                    RecurringDeleteChoice.FUTURE_ONLY -> {
                        toDelete.transaction.seriesId?.let { 
                            vm.deleteSeriesWithUndo(it, SeriesDeletionMode.FUTURE, toDelete.transaction.date, snackbarHostState, context.getString(R.string.tx_deleted_snackbar), context.getString(R.string.undo)) 
                        }
                    }
                    RecurringDeleteChoice.ALL_SERIES -> {
                        toDelete.transaction.seriesId?.let { 
                            vm.deleteSeriesWithUndo(it, SeriesDeletionMode.ALL, null, snackbarHostState, context.getString(R.string.tx_deleted_snackbar), context.getString(R.string.undo)) 
                        }
                    }
                }
            }
        )
    }
}

@Composable
private fun FilterChip(
    label: String,
    selected: Boolean,
    modifier: Modifier,
    accent: Color,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier.clickableNoRipple(onClick),
        shape = CircleShape,
        color = if (selected) accent.copy(alpha = 0.22f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
    ) {
        Text(
            label,
            color = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(vertical = 12.dp),
        )
    }
}

@Composable
private fun InsightToggle(
    label: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.clickableNoRipple(onClick),
        shape = MaterialTheme.shapes.small,
        color = if (selected) accent.copy(alpha = 0.1f) else Color.Transparent,
        border = if (selected) androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = 0.3f)) else null
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

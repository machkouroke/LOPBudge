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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.ui.components.DonutChart
import com.lop.budget.ui.components.DonutSlice
import com.lop.budget.ui.components.FloatingCard
import com.lop.budget.ui.components.LocalUndoController
import com.lop.budget.ui.components.SwipeableTransactionRow
import com.lop.budget.ui.components.clickableNoRipple
import com.lop.budget.ui.theme.LopTheme
import com.lop.budget.util.Format
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun MonthlyTransactionsScreen(
    onBack: () -> Unit,
    onOpenTransaction: (Long) -> Unit,
    vm: MonthlyTransactionsViewModel = hiltViewModel(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val ext = LopTheme.extended
    val undo = LocalUndoController.current

    val title = if (state.type == TransactionType.EXPENSE) "Dépenses" else "Revenus"
    val accent = if (state.type == TransactionType.EXPENSE) ext.expense else ext.income

    val top = state.breakdown.take(6)
    val othersTotal = state.breakdown.drop(6).sumOf { it.total }
    val slices = buildList {
        top.forEach { add(DonutSlice(it.total, Color(it.colorArgb), it.name)) }
        if (othersTotal > 0) add(DonutSlice(othersTotal, Color(0xFF9E9E9E), "Autres"))
    }

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
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour", modifier = Modifier.size(26.dp).clickableNoRipple(onBack))
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
                FilterChip("Tous", state.filter == PaidFilter.ALL, Modifier.weight(1f), accent) { vm.setFilter(PaidFilter.ALL) }
                FilterChip("Payé", state.filter == PaidFilter.PAID, Modifier.weight(1f), accent) { vm.setFilter(PaidFilter.PAID) }
                FilterChip("Planifié", state.filter == PaidFilter.PLANNED, Modifier.weight(1f), accent) { vm.setFilter(PaidFilter.PLANNED) }
            }
        }

        // Insights
        item {
            FloatingCard(Modifier.fillMaxWidth()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    if (slices.isEmpty()) {
                        Text("Aucune donnée", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(40.dp))
                    } else {
                        DonutChart(slices = slices) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Total", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            Text("Transactions", style = MaterialTheme.typography.titleLarge)
        }

        items(state.transactions, key = { it.transaction.id }) { tx ->
            SwipeableTransactionRow(
                item = tx,
                currency = state.currency,
                showAccount = false,
                onClick = { onOpenTransaction(tx.transaction.id) },
                onToggleStatus = {
                    val nowPaid = tx.transaction.status != TransactionStatus.PAID
                    vm.toggleStatus(tx.transaction.id)
                    undo.show(
                        message = if (nowPaid) "« ${tx.transaction.title} » marqué comme réglé" else "« ${tx.transaction.title} » remis à régler",
                    ) { vm.toggleStatus(tx.transaction.id) }
                },
                onDelete = {
                    vm.delete(tx)
                    undo.show(message = "« ${tx.transaction.title} » supprimé") { vm.restore(tx) }
                },
            )
        }

        if (state.transactions.isEmpty()) {
            item {
                Text(
                    "Aucune transaction pour ce filtre.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
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

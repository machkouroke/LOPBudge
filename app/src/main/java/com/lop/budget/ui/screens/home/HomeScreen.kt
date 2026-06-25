package com.lop.budget.ui.screens.home

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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.ui.components.FloatingCard
import com.lop.budget.ui.components.LocalUndoController
import com.lop.budget.ui.components.MonthPickerBottomSheet
import com.lop.budget.ui.components.SwipeableTransactionRow
import com.lop.budget.ui.components.clickableNoRipple
import com.lop.budget.ui.theme.LopTheme
import com.lop.budget.util.Format
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun HomeScreen(
    onOpenTransaction: (Long) -> Unit,
    onOpenAi: () -> Unit,
    onOpenMonthly: (TransactionType, YearMonth) -> Unit,
    vm: HomeViewModel = hiltViewModel(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val ext = LopTheme.extended
    val undo = LocalUndoController.current

    var isMonthPickerOpen by remember { mutableStateOf(false) }

    if (isMonthPickerOpen) {
        MonthPickerBottomSheet(
            selected = state.month,
            onSelect = vm::setMonth,
            onDismiss = { isMonthPickerOpen = false },
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Bonjour 👋", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Tableau de bord", style = MaterialTheme.typography.headlineMedium)

            FloatingCard(
                modifier = Modifier.fillMaxWidth().clickableNoRipple(onOpenAi),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Assistant LOP", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Au rythme actuel, tu peux épargner ~180 € ce mois-ci. Touche pour en discuter.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            FloatingCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.ChevronLeft, "Mois précédent", modifier = Modifier.size(28.dp).clickableNoRipple(vm::prevMonth))

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "${state.month.month.getDisplayName(TextStyle.FULL, Locale.FRANCE).replaceFirstChar { it.uppercase() }} ${state.month.year}",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.clickableNoRipple { isMonthPickerOpen = true },
                            )

                            if (!state.isCurrentMonth) {
                                Spacer(Modifier.height(6.dp))
                                AssistChip(
                                    onClick = vm::goToCurrentMonth,
                                    label = { Text("Aujourd’hui") },
                                    colors = AssistChipDefaults.assistChipColors(
                                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.85f),
                                        labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                    ),
                                )
                            }
                        }

                        Icon(Icons.Filled.ChevronRight, "Mois suivant", modifier = Modifier.size(28.dp).clickableNoRipple(vm::nextMonth))
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("Solde projeté", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        Format.money(state.projectedBalance, state.currency),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                    )
                    state.daysUntilPayday?.let {
                        Spacer(Modifier.height(4.dp))
                        Text("Prochaine rentrée d'argent dans $it jours", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SummaryTile(
                    modifier = Modifier.weight(1f).clickableNoRipple { onOpenMonthly(TransactionType.INCOME, state.month) },
                    label = "Revenus",
                    amount = Format.money(state.monthIncome, state.currency),
                    color = ext.income,
                    container = ext.incomeContainer,
                    up = true,
                )
                SummaryTile(
                    modifier = Modifier.weight(1f).clickableNoRipple { onOpenMonthly(TransactionType.EXPENSE, state.month) },
                    label = "Dépenses",
                    amount = Format.money(state.monthExpense, state.currency),
                    color = ext.expense,
                    container = ext.expenseContainer,
                    up = false,
                )
            }

            Spacer(Modifier.height(8.dp))
        }

        val listState = rememberLazyListState()
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 20.dp, end = 20.dp, top = 18.dp, bottom = 120.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.dayGroups, key = { it.date.toString() }) { day ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${day.date.dayOfMonth} ${day.date.month.getDisplayName(TextStyle.SHORT, Locale.FRANCE)}",
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            text = Format.money(day.total, state.currency),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        day.transactions.forEach { tx ->
                            SwipeableTransactionRow(
                                item = tx,
                                currency = state.currency,
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
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(22.dp)
                    .align(Alignment.TopCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.background,
                                MaterialTheme.colorScheme.background.copy(alpha = 0f),
                            ),
                        ),
                    ),
            )
        }
    }
}

@Composable
private fun SummaryTile(
    modifier: Modifier,
    label: String,
    amount: String,
    color: Color,
    container: Color,
    up: Boolean,
) {
    FloatingCard(modifier = modifier, color = container.copy(alpha = 0.45f)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (up) Icons.AutoMirrored.Filled.TrendingUp else Icons.AutoMirrored.Filled.TrendingDown,
                    null,
                    tint = color,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(6.dp))
            Text(amount, style = MaterialTheme.typography.titleLarge, color = color, fontWeight = FontWeight.Bold)
        }
    }
}

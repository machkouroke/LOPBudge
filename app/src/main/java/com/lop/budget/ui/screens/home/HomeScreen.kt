package com.lop.budget.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.domain.model.RecurrenceFrequency
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.ui.components.CircleIcon
import com.lop.budget.ui.components.FloatingCard
import com.lop.budget.ui.components.MonthPickerBottomSheet
import com.lop.budget.ui.components.clickableNoRipple
import com.lop.budget.ui.theme.LopTheme
import com.lop.budget.util.Format
import com.lop.budget.util.IconMapper
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun HomeScreen(
    onOpenTransaction: (Long) -> Unit,
    onOpenAi: () -> Unit,
    vm: HomeViewModel = hiltViewModel(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val ext = LopTheme.extended

    var isMonthPickerOpen by remember { mutableStateOf(false) }

    if (isMonthPickerOpen) {
        MonthPickerBottomSheet(
            selected = state.month,
            onSelect = vm::setMonth,
            onDismiss = { isMonthPickerOpen = false },
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 20.dp, end = 20.dp, top = 24.dp, bottom = 120.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text("Bonjour 👋", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Tableau de bord", style = MaterialTheme.typography.headlineMedium)
        }

        // Bandeau IA
        item {
            FloatingCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickableNoRipple(onOpenAi),
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
        }

        // Sélecteur de mois + solde projeté
        item {
            FloatingCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.ChevronLeft, "Mois précédent", modifier = Modifier.size(28.dp).clickableNoRipple(vm::prevMonth))

                        Text(
                            "${state.month.month.getDisplayName(TextStyle.FULL, Locale.FRANCE).replaceFirstChar { it.uppercase() }} ${state.month.year}",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.clickableNoRipple { isMonthPickerOpen = true },
                        )

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
        }

        // Revenus / Dépenses du mois (demandé : comme Budge)
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SummaryTile(
                    modifier = Modifier.weight(1f),
                    label = "Revenus",
                    amount = Format.money(state.monthIncome, state.currency),
                    color = ext.income,
                    container = ext.incomeContainer,
                    up = true,
                )
                SummaryTile(
                    modifier = Modifier.weight(1f),
                    label = "Dépenses",
                    amount = Format.money(state.monthExpense, state.currency),
                    color = ext.expense,
                    container = ext.expenseContainer,
                    up = false,
                )
            }
        }

        item {
            Text("À venir", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 4.dp))
        }

        items(state.upcoming, key = { it.transaction.id }) { tx ->
            UpcomingRow(tx = tx, currency = state.currency, onClick = { onOpenTransaction(tx.transaction.id) })
        }

        if (state.upcoming.isEmpty()) {
            item {
                Text(
                    "Aucune transaction planifiée ce mois-ci.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
                    null, tint = color, modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(6.dp))
            Text(amount, style = MaterialTheme.typography.titleLarge, color = color, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun UpcomingRow(tx: TransactionWithRelations, currency: String, onClick: () -> Unit) {
    val ext = LopTheme.extended
    val isIncome = tx.transaction.type == TransactionType.INCOME
    val amountColor = if (isIncome) ext.income else ext.expense
    val catColor = tx.category?.colorArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.primary
    val recurring = tx.transaction.recurrenceFrequency != RecurrenceFrequency.NONE

    FloatingCard(
        modifier = Modifier.fillMaxWidth().clickableNoRipple(onClick),
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
                    if (recurring) {
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Filled.Repeat, "Récurrent", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(15.dp))
                    }
                }
                Text(Format.dayMonth(tx.transaction.date), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

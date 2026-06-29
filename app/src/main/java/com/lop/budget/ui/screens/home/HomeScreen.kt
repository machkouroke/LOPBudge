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
import androidx.compose.material.icons.filled.Repeat
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

    var isMonthPickerOpen by remember { mutableStateOf(false) }

    if (isMonthPickerOpen) {
        MonthPickerBottomSheet(
            selected = state.month,
            onSelect = vm::setMonth,
            onDismiss = { isMonthPickerOpen = false },
        )
    }

    // UX: zone haute fixe, seul le contenu des transactions défile
    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // En-tête : Avatar + My space + Boutons actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Avatar ring (simulé)
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(com.lop.budget.ui.theme.AccentYellow.copy(alpha = 0.8f), androidx.compose.foundation.shape.CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(modifier = Modifier.size(16.dp).background(MaterialTheme.colorScheme.background, androidx.compose.foundation.shape.CircleShape))
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "My space",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.AutoAwesome, null, tint = Color(0xFF2196F3), modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("1,16", style = MaterialTheme.typography.bodyMedium)
                    }
                    Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) // Placeholder wallet
                    Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) // Placeholder menu
                }
            }

            // Section Cartes Catégories (Restaurants, Groceries...)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                // Carte Restaurants
                FloatingCard(
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)
                ) {
                    Column {
                        CircleIcon(
                            icon = Icons.Filled.ChevronRight, // Placeholder restaurant
                            tint = com.lop.budget.ui.theme.CategoryOrange,
                            background = com.lop.budget.ui.theme.CategoryOrange.copy(alpha = 0.2f),
                            size = 36.dp
                        )
                        Spacer(Modifier.height(16.dp))
                        Text("Restaurants", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Text(Format.money(128.0, state.currency), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("/ ${Format.money(1372.0, state.currency)} left", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                
                // Carte Groceries
                FloatingCard(
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)
                ) {
                    Column {
                        CircleIcon(
                            icon = Icons.Filled.ChevronRight, // Placeholder groceries
                            tint = com.lop.budget.ui.theme.CategoryGreen,
                            background = com.lop.budget.ui.theme.CategoryGreen.copy(alpha = 0.2f),
                            size = 36.dp
                        )
                        Spacer(Modifier.height(16.dp))
                        Text("Groceries", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Text(Format.money(55.0, state.currency), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("/ ${Format.money(2445.0, state.currency)} left", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            
            // Section Upcoming this month
            Text("Upcoming this month", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            
            FloatingCard(
                modifier = Modifier.fillMaxWidth().clickableNoRipple { /* Open Upcoming Modal */ },
                color = MaterialTheme.colorScheme.surfaceVariant,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Stacked icons placeholder
                        Box(modifier = Modifier.width(48.dp)) {
                            CircleIcon(Icons.Filled.ChevronRight, Color.White, com.lop.budget.ui.theme.CategoryRed, 28.dp, Modifier.align(Alignment.CenterStart))
                            CircleIcon(Icons.Filled.ChevronRight, Color.White, com.lop.budget.ui.theme.CategoryBlue, 28.dp, Modifier.align(Alignment.CenterEnd))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("Expenses", style = MaterialTheme.typography.titleMedium)
                            Text("3 transactions", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Text(Format.money(29.97, state.currency), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            
            // Section Accounts
            Text("Accounts", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            
            FloatingCard(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)
            ) {
                Column {
                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Column {
                            Text("Total", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(Format.money(672.36, state.currency), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        }
                        // Placeholder pour le mini graphique
                        Box(modifier = Modifier.size(80.dp, 30.dp).background(com.lop.budget.ui.theme.IncomeGreen.copy(alpha = 0.2f)))
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("R", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.background(Color.DarkGray, androidx.compose.foundation.shape.RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
                            Spacer(Modifier.width(12.dp))
                            Text("Revolut", style = MaterialTheme.typography.bodyLarge)
                        }
                        Text(Format.money(622.36, state.currency), style = MaterialTheme.typography.bodyLarge)
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("M", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.background(Color.DarkGray, androidx.compose.foundation.shape.RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
                            Spacer(Modifier.width(12.dp))
                            Text("Monobank", style = MaterialTheme.typography.bodyLarge)
                        }
                        Text(Format.money(50.0, state.currency), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }

            // Titre Recent transactions
            Text("Recent transactions", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            
            // Petit "souffle" visuel avant la liste (évite la coupure nette)
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
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${day.date.dayOfMonth} ${
                                day.date.month.getDisplayName(
                                    TextStyle.SHORT,
                                    Locale.FRANCE
                                )
                            }",
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
                            val isIncome = tx.transaction.type == TransactionType.INCOME
                            val amountColor = if (isIncome) ext.income else ext.expense
                            val catColor = tx.category?.colorArgb?.let { Color(it) }
                                ?: MaterialTheme.colorScheme.primary
                            val recurring =
                                tx.transaction.recurrenceFrequency != RecurrenceFrequency.NONE

                            FloatingCard(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickableNoRipple { onOpenTransaction(tx.transaction.id) },
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
                                            Text(
                                                tx.transaction.title,
                                                style = MaterialTheme.typography.titleMedium
                                            )
                                            if (recurring) {
                                                Spacer(Modifier.width(6.dp))
                                                Icon(
                                                    Icons.Filled.Repeat,
                                                    "Récurrent",
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(15.dp)
                                                )
                                            }
                                        }
                                        Text(
                                            tx.account?.name ?: "",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Text(
                                        (if (isIncome) "+" else "−") + Format.money(
                                            tx.transaction.amount,
                                            state.currency
                                        ),
                                        style = MaterialTheme.typography.titleMedium,
                                        color = amountColor,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }
                        }
                    }
                }

                if (state.dayGroups.isEmpty()) {
                    item {
                        Text(
                            "Aucune transaction ce mois-ci.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Overlay "fade" en haut de la liste : masque la coupure quand des cards passent derrière.
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
                    null, tint = color, modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                amount,
                style = MaterialTheme.typography.titleLarge,
                color = color,
                fontWeight = FontWeight.Bold
            )
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
        modifier = Modifier
            .fillMaxWidth()
            .clickableNoRipple(onClick),
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
                        Icon(
                            Icons.Filled.Repeat,
                            "Récurrent",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
                Text(
                    Format.dayMonth(tx.transaction.date),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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

package com.lop.budget.ui.screens.detail

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.lop.budget.domain.model.RecurrenceFrequency
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.ui.components.CircleIcon
import com.lop.budget.ui.components.FloatingCard
import com.lop.budget.ui.components.PillTag
import com.lop.budget.ui.components.clickableNoRipple
import com.lop.budget.ui.theme.LopTheme
import com.lop.budget.util.Format
import com.lop.budget.util.IconMapper

@Composable
fun TransactionDetailScreen(
    transactionId: Long,
    onBack: () -> Unit,
    vm: TransactionDetailViewModel = hiltViewModel(),
) {
    LaunchedEffect(transactionId) { vm.load(transactionId) }
    val state by vm.uiState.collectAsStateWithLifecycle()
    val ext = LopTheme.extended
    val haptic = LocalHapticFeedback.current
    var editingCategory by remember { mutableStateOf(false) }

    val twr = state.transaction
    val tx = twr?.transaction

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 20.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 20.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour", modifier = Modifier.size(26.dp).clickableNoRipple(onBack))
                Text("Détail", style = MaterialTheme.typography.titleLarge)
                Icon(Icons.Filled.Delete, "Supprimer", modifier = Modifier.size(24.dp).clickableNoRipple { vm.delete(onBack) })
            }
        }

        if (tx == null) {
            item { Text("Chargement…", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            return@LazyColumn
        }

        val isIncome = tx.type == TransactionType.INCOME
        val accent = if (isIncome) ext.income else ext.expense

        // En-tête montant
        item {
            FloatingCard(Modifier.fillMaxWidth()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    val catColor = twr.category?.colorArgb?.let { Color(it) } ?: accent
                    CircleIcon(IconMapper.get(twr.category?.icon ?: "category"), catColor, catColor.copy(alpha = 0.18f), size = 60.dp)
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(tx.title, style = MaterialTheme.typography.titleLarge)
                        if (tx.recurrenceFrequency != RecurrenceFrequency.NONE) {
                            Spacer(Modifier.width(8.dp))
                            Icon(Icons.Filled.Repeat, "Récurrent", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        }
                    }
                    Text(
                        (if (isIncome) "+" else "−") + Format.money(tx.amount),
                        style = MaterialTheme.typography.displaySmall,
                        color = accent,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(Format.fullDate(tx.date), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    PillTag(
                        text = if (tx.status == TransactionStatus.PAID) "Payé" else "Planifié",
                        color = if (tx.status == TransactionStatus.PAID) ext.income else MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        // Tags
        if (twr.tags.isNotEmpty()) {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(twr.tags, key = { it.id }) { PillTag("#${it.name}", Color(it.colorArgb)) }
                }
            }
        }

        // Catégorie modifiable (même si payé) — suggestion utilisateur
        item {
            FloatingCard(Modifier.fillMaxWidth()) {
                Column {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Catégorie", style = MaterialTheme.typography.titleMedium)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickableNoRipple { editingCategory = !editingCategory },
                        ) {
                            Icon(Icons.Filled.Edit, "Modifier la catégorie", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Modifier", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    if (editingCategory) {
                        Spacer(Modifier.height(10.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(state.availableCategories, key = { it.id }) { cat ->
                                val c = Color(cat.colorArgb)
                                val selected = cat.id == tx.categoryId
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.clickableNoRipple {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        vm.changeCategory(cat.id)
                                        editingCategory = false
                                    },
                                ) {
                                    CircleIcon(IconMapper.get(cat.icon), c, if (selected) c.copy(alpha = 0.32f) else c.copy(alpha = 0.14f), size = 48.dp)
                                    Spacer(Modifier.height(4.dp))
                                    Text(cat.name, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    } else {
                        Spacer(Modifier.height(6.dp))
                        Text(twr.category?.name ?: "—", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        // Occurrences récurrentes à venir — suggestion utilisateur
        if (tx.recurrenceFrequency != RecurrenceFrequency.NONE && state.upcomingDates.isNotEmpty()) {
            item {
                FloatingCard(Modifier.fillMaxWidth()) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.CalendarMonth, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Prochaines occurrences", style = MaterialTheme.typography.titleMedium)
                        }
                        Spacer(Modifier.height(10.dp))
                        state.upcomingDates.forEach { d ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(Format.fullDate(d), style = MaterialTheme.typography.bodyLarge)
                                Text((if (isIncome) "+" else "−") + Format.money(tx.amount), color = accent, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            }
        }

        // Action : marquer payé
        if (tx.status == TransactionStatus.PLANNED) {
            item {
                FloatingCard(
                    modifier = Modifier.fillMaxWidth().clickableNoRipple {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        vm.markPaid()
                    },
                    color = ext.incomeContainer.copy(alpha = 0.4f),
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Check, null, tint = ext.income)
                        Spacer(Modifier.width(8.dp))
                        Text("Marquer comme payé", color = ext.income, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

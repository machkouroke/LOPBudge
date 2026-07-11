package com.lop.budget.ui.screens.detail

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lop.budget.R
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
    onEdit: (Long) -> Unit = {},
    vm: TransactionDetailViewModel = hiltViewModel(),
) {
    LaunchedEffect(transactionId) { vm.load(transactionId) }
    val state by vm.uiState.collectAsStateWithLifecycle()
    val ext = LopTheme.extended
    val haptic = LocalHapticFeedback.current
    var editingCategory by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val twr = state.transaction
    val tx = twr?.transaction

    // Bottom Sheet Style (Redesign)
    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            top = 40.dp,
            bottom = 40.dp
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // Close button (cross)
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            androidx.compose.foundation.shape.CircleShape
                        )
                        .clickableNoRipple(onBack),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Close,
                        stringResource(R.string.close),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Edit button
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                androidx.compose.foundation.shape.CircleShape
                            )
                            .clickableNoRipple {
                                // TODO: Gérer l'édition d'une série récurrente avec modal contextuelle
                                onEdit(transactionId)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.Edit,
                            stringResource(R.string.edit),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    // Delete button
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                androidx.compose.foundation.shape.CircleShape
                            )
                            .clickableNoRipple {
                                if (tx?.seriesId != null) {
                                    // TODO: Modal contextuelle (cette occurrence / suivantes / toutes)
                                    showDeleteConfirm = true
                                } else {
                                    showDeleteConfirm = true
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.Delete,
                            stringResource(R.string.delete),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        if (tx == null) {
            item { Text(stringResource(R.string.loading), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            return@LazyColumn
        }

        val isIncome = tx.type == TransactionType.INCOME
        val accent = if (isIncome) ext.income else ext.expense

        // En-tête montant (Redesign)
        item {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                val catColor = twr.category?.colorArgb?.let { Color(it) }
                    ?: com.lop.budget.ui.theme.CategoryOrange
                CircleIcon(
                    IconMapper.get(twr.category?.icon ?: "category"),
                    Color.White,
                    catColor,
                    size = 80.dp
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    Format.money(tx.amount),
                    style = MaterialTheme.typography.displayMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        // Détails en liste
        item {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(top = 24.dp)
            ) {
                // Category
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Category,
                            null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            stringResource(R.string.tx_detail_category),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val catColor = twr.category?.colorArgb?.let { Color(it) }
                            ?: com.lop.budget.ui.theme.CategoryOrange
                        CircleIcon(
                            IconMapper.get(twr.category?.icon ?: "category"),
                            Color.White,
                            catColor,
                            size = 24.dp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            twr.category?.name ?: stringResource(R.string.other),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }

                // Date
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.CalendarMonth,
                            null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            stringResource(R.string.tx_detail_date),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(Format.fullDate(tx.date), style = MaterialTheme.typography.bodyLarge)
                }

                // Type
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.SyncAlt,
                            null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            stringResource(R.string.tx_detail_type),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        if (isIncome) stringResource(R.string.tx_type_income) else stringResource(R.string.tx_type_expense),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }

                // Account
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.AccountBalance,
                            null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            stringResource(R.string.tx_detail_account),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "R",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .background(
                                    Color.DarkGray,
                                    androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            twr.account?.name ?: stringResource(R.string.other),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
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
                        Text(stringResource(R.string.tx_category_label), style = MaterialTheme.typography.titleMedium)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickableNoRipple {
                                editingCategory = !editingCategory
                            },
                        ) {
                            Icon(
                                Icons.Filled.Edit,
                                stringResource(R.string.tx_detail_edit_category),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                stringResource(R.string.edit),
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelLarge
                            )
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
                                    CircleIcon(
                                        IconMapper.get(cat.icon),
                                        c,
                                        if (selected) c.copy(alpha = 0.32f) else c.copy(alpha = 0.14f),
                                        size = 48.dp
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(cat.name, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    } else {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            twr.category?.name ?: "—",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        if (tx.seriesId != null && state.upcomingDates.isNotEmpty()) {
            item {
                FloatingCard(Modifier.fillMaxWidth()) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.CalendarMonth,
                                null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                stringResource(R.string.tx_detail_upcoming_occurrences),
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        state.upcomingDates.forEach { d ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(Format.fullDate(d), style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    (if (isIncome) "+" else "−") + Format.money(tx.amount),
                                    color = accent,
                                    style = MaterialTheme.typography.bodyLarge
                                )
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickableNoRipple {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            vm.markPaid()
                        },
                    color = ext.incomeContainer.copy(alpha = 0.4f),
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Check, null, tint = ext.income)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.tx_detail_mark_as_paid),
                            color = ext.income,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }

    if (showDeleteConfirm && tx != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.tx_detail_delete_title)) },
            text = {
                if (tx.seriesId != null) {
                    Text(stringResource(R.string.tx_detail_delete_recurring_msg))
                } else {
                    Text(stringResource(R.string.tx_detail_delete_msg))
                }
            },
            confirmButton = {
                if (tx.seriesId != null) {
                    Column(horizontalAlignment = Alignment.End) {
                        androidx.compose.material3.TextButton(onClick = {
                            showDeleteConfirm = false
                            vm.deleteOccurrence(onBack)
                        }) { Text(stringResource(R.string.tx_detail_delete_occurrence), color = MaterialTheme.colorScheme.error) }
                        androidx.compose.material3.TextButton(onClick = {
                            showDeleteConfirm = false
                            vm.deleteSeries(onBack)
                        }) { Text(stringResource(R.string.tx_detail_delete_series), color = MaterialTheme.colorScheme.error) }
                    }
                } else {
                    androidx.compose.material3.TextButton(onClick = {
                        showDeleteConfirm = false
                        vm.delete(onBack)
                    }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = {
                    showDeleteConfirm = false
                }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

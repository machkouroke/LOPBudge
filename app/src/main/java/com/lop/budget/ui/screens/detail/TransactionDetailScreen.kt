package com.lop.budget.ui.screens.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import com.lop.budget.domain.model.SeriesDeletionMode
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.ui.components.CircleIcon
import com.lop.budget.ui.components.ConfirmDeleteSheet
import com.lop.budget.ui.components.FloatingCard
import com.lop.budget.ui.components.LopScreenScaffold
import com.lop.budget.ui.components.PillTag
import com.lop.budget.ui.components.RecurringDeleteChoice
import com.lop.budget.ui.components.RecurringDeleteSheet
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

    LaunchedEffect(state.transaction, state.isLoaded) {
        if (state.isLoaded && state.transaction == null) {
            onBack()
        }
    }
    var editingCategory by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val twr = state.transaction
    val tx = twr?.transaction

    LopScreenScaffold(
        title = stringResource(R.string.tx_default_title),
        onBack = onBack,
        navigationIcon = Icons.Filled.Close
    ) {
        if (tx == null) {
            item {
                Text(
                    stringResource(R.string.loading),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            val isIncome = tx.type == TransactionType.INCOME
            val accent = if (isIncome) ext.income else ext.expense

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                androidx.compose.foundation.shape.CircleShape
                            )
                            .clickableNoRipple { onEdit(transactionId) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.Edit,
                            stringResource(R.string.edit),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                androidx.compose.foundation.shape.CircleShape
                            )
                            .clickableNoRipple { showDeleteConfirm = true },
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

            item {
                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.padding(top = 8.dp)
                ) {
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
                            val c = twr.category?.colorArgb?.let { Color(it) }
                                ?: com.lop.budget.ui.theme.CategoryOrange
                            CircleIcon(
                                IconMapper.get(twr.category?.icon ?: "category"),
                                Color.White,
                                c,
                                size = 24.dp
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                twr.category?.name ?: stringResource(R.string.other),
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }

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
                            if (isIncome) stringResource(R.string.tx_type_income) else stringResource(
                                R.string.tx_type_expense
                            ),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }

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

            if (twr.tags.isNotEmpty()) {
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(twr.tags, key = { it.id }) {
                            PillTag(
                                "#${it.name}",
                                Color(it.colorArgb)
                            )
                        }
                    }
                }
            }

            item {
                FloatingCard(Modifier.fillMaxWidth()) {
                    Column {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(R.string.tx_category_label),
                                style = MaterialTheme.typography.titleMedium
                            )
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
    }

    if (showDeleteConfirm && tx != null) {
        if (tx.seriesId != null) {
            RecurringDeleteSheet(
                onDismiss = { showDeleteConfirm = false },
                showFutureOnly = true,
                onChoose = { choice ->
                    showDeleteConfirm = false
                    when (choice) {
                        RecurringDeleteChoice.THIS_OCCURRENCE -> {
                            vm.deleteOccurrence(onBack)
                        }

                        RecurringDeleteChoice.FUTURE_ONLY -> {
                            vm.deleteSeries(SeriesDeletionMode.FUTURE, tx.date, onBack)
                        }

                        RecurringDeleteChoice.ALL_SERIES -> {
                            vm.deleteSeries(SeriesDeletionMode.ALL, null, onBack)
                        }
                    }
                },
            )
        } else {
            ConfirmDeleteSheet(
                title = stringResource(R.string.tx_detail_delete_title),
                message = stringResource(R.string.tx_detail_delete_msg),
                confirmLabel = stringResource(R.string.delete),
                onDismiss = { showDeleteConfirm = false },
                onConfirm = {
                    showDeleteConfirm = false
                    vm.delete(onBack)
                },
            )
        }
    }
}

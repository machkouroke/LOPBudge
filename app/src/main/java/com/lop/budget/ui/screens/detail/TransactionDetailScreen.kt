package com.lop.budget.ui.screens.detail

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lop.budget.R
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.domain.model.EditScope
import com.lop.budget.domain.model.SeriesDeletionMode
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.ui.components.CategoryBottomSheet
import com.lop.budget.ui.components.CircleIcon
import com.lop.budget.ui.components.ConfirmDeleteSheet
import com.lop.budget.ui.components.FloatingCard
import com.lop.budget.ui.components.LopScreenScaffold
import com.lop.budget.ui.components.PillTag
import com.lop.budget.ui.components.RecurringDeleteChoice
import com.lop.budget.ui.components.RecurringDeleteSheet
import com.lop.budget.ui.components.RecurringEditSheet
import com.lop.budget.ui.components.SwipeDownDismissWrapper
import com.lop.budget.ui.components.clickableNoRipple
import com.lop.budget.ui.theme.LopTheme
import com.lop.budget.util.Format
import com.lop.budget.util.IconMapper

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionDetailScreen(
    transactionId: Long,
    onBack: () -> Unit,
    onEdit: (id: Long, scope: String?, date: Long?) -> Unit = { _, _, _ -> },
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
    var showCategorySheet by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showAccountSheet by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showEditScopeChoice by remember { mutableStateOf(false) }

    val twr = state.transaction
    val tx = twr?.transaction
    val scaffoldTitle = tx?.title ?: stringResource(R.string.tx_default_title)
    val isBusy = state.isUpdating

    SwipeDownDismissWrapper(onDismiss = onBack) {
        LopScreenScaffold(
            title = scaffoldTitle,
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
                                .clickableNoRipple { 
                                    if (!isBusy) {
                                        if (tx.seriesId != null) {
                                            showEditScopeChoice = true
                                        } else {
                                            onEdit(transactionId, null, null)
                                        }
                                    }
                                },
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
                                .clickableNoRipple { if (!isBusy) showDeleteConfirm = true },
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
                            tx.title,
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            Format.money(tx.amount),
                            style = MaterialTheme.typography.displayMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }

                item {
                    FloatingCard(Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            DetailFieldRow(
                                label = stringResource(R.string.tx_detail_category),
                                value = twr.category?.name ?: stringResource(R.string.other),
                                leading = {
                                    val c = twr.category?.colorArgb?.let { Color(it) }
                                        ?: com.lop.budget.ui.theme.CategoryOrange
                                    CircleIcon(
                                        IconMapper.get(twr.category?.icon ?: "category"),
                                        Color.White,
                                        c,
                                        size = 34.dp
                                    )
                                },
                                onClick = { if (!isBusy) showCategorySheet = true },
                                trailing = {
                                    Icon(
                                        Icons.Filled.Edit,
                                        null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            )

                            DetailFieldRow(
                                label = stringResource(R.string.tx_detail_date),
                                value = Format.fullDate(tx.date),
                                leading = {
                                    Icon(
                                        Icons.Filled.CalendarMonth,
                                        null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(22.dp)
                                    )
                                },
                                onClick = { if (!isBusy) showDatePicker = true },
                                trailing = {
                                    Icon(
                                        Icons.Filled.Edit,
                                        null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            )

                            DetailFieldRow(
                                label = stringResource(R.string.tx_detail_account),
                                value = twr.account?.name ?: stringResource(R.string.other),
                                leading = {
                                    val account = twr.account
                                    if (account != null) {
                                        val color = Color(account.colorArgb)
                                        CircleIcon(
                                            IconMapper.get(account.icon),
                                            Color.White,
                                            color,
                                            size = 34.dp
                                        )
                                    } else {
                                        Icon(
                                            Icons.Filled.AccountBalance,
                                            null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                },
                                onClick = { if (!isBusy) showAccountSheet = true },
                                trailing = {
                                    Icon(
                                        Icons.Filled.Edit,
                                        null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            )

                            DetailFieldRow(
                                label = stringResource(R.string.tx_detail_type),
                                value = if (isIncome) stringResource(R.string.tx_type_income) else stringResource(
                                    R.string.tx_type_expense
                                ),
                                leading = {
                                    Icon(
                                        Icons.Filled.SyncAlt,
                                        null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(22.dp)
                                    )
                                },
                                onClick = null,
                                trailing = null
                            )
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

                val isPaid = tx.status == TransactionStatus.PAID
                if (tx.status == TransactionStatus.PLANNED || isPaid) {
                    item {
                        val buttonColor = if (isPaid) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                        else ext.incomeContainer.copy(alpha = 0.4f)
                        val tintColor = if (isPaid) MaterialTheme.colorScheme.onSurfaceVariant else ext.income

                        FloatingCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickableNoRipple {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    if (isPaid) {
                                        vm.markUnpaid()
                                    } else {
                                        vm.markPaid()
                                    }
                                },
                            color = buttonColor,
                        ) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    if (isPaid) Icons.AutoMirrored.Filled.Undo else Icons.Filled.Check,
                                    null,
                                    tint = tintColor
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    stringResource(if (isPaid) R.string.tx_detail_mark_as_unpaid else R.string.tx_detail_mark_as_paid),
                                    color = tintColor,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showEditScopeChoice && tx != null) {
        RecurringEditSheet(
            onDismiss = { showEditScopeChoice = false },
            onChoose = { scope ->
                showEditScopeChoice = false
                when (scope) {
                    EditScope.SINGLE -> {
                        vm.materializeAndEdit { realId ->
                            onEdit(realId, null, null)
                        }
                    }
                    EditScope.FUTURE -> {
                        onEdit(transactionId, "FUTURE", tx.date)
                    }
                    EditScope.ALL -> {
                        onEdit(transactionId, "ALL", null)
                    }
                }
            }
        )
    }

    if (showDatePicker && tx != null) {
        val pickerState = androidx.compose.material3.rememberDatePickerState(initialSelectedDateMillis = tx.date)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { vm.changeDate(it) }
                    showDatePicker = false
                }) {
                    Text(stringResource(R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }

    if (showCategorySheet && tx != null) {
        CategoryBottomSheet(
            title = stringResource(R.string.tx_category_sheet_title),
            categories = state.availableCategories,
            selectedId = tx.categoryId,
            onSelect = { categoryId ->
                vm.changeCategory(categoryId)
                showCategorySheet = false
            },
            onDismiss = { showCategorySheet = false }
        )
    }

    if (showAccountSheet) {
        AccountBottomSheet(
            accounts = state.availableAccounts,
            selectedId = twr?.account?.id,
            onSelect = { accountId ->
                vm.changeAccount(accountId)
                showAccountSheet = false
            },
            onDismiss = { showAccountSheet = false },
        )
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

@Composable
private fun DetailFieldRow(
    label: String,
    value: String,
    leading: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val shape = RoundedCornerShape(16.dp)
    val background = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        color = background,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.10f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickableNoRipple(onClick = onClick) else Modifier)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(12.dp))
            }

            Column(Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    value,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
            }

            if (trailing != null) {
                Spacer(Modifier.width(12.dp))
                trailing()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountBottomSheet(
    accounts: List<AccountEntity>,
    selectedId: Long?,
    onSelect: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        scrimColor = Color.Black.copy(alpha = 0.55f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.tx_detail_account),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )

            if (accounts.isEmpty()) {
                Text(
                    stringResource(R.string.tx_no_accounts),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            accounts.forEach { account ->
                val selected = account.id == selectedId
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .clickableNoRipple { onSelect(account.id) },
                    color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.10f))
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val color = Color(account.colorArgb)
                        CircleIcon(
                            icon = IconMapper.get(account.icon),
                            tint = Color.White,
                            background = color,
                            size = 38.dp,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            account.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        if (selected) {
                            Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
        }
    }
}


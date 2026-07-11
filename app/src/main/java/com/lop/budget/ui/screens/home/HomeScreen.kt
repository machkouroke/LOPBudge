package com.lop.budget.ui.screens.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
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
import com.lop.budget.ui.components.MonthPickerBottomSheet
import com.lop.budget.ui.components.SwipeableTransactionRow
import com.lop.budget.ui.components.clickableNoRipple
import com.lop.budget.ui.navigation.Routes
import com.lop.budget.ui.theme.ExpenseCoral
import com.lop.budget.ui.theme.LopTheme
import com.lop.budget.util.Format
import com.lop.budget.util.IconMapper
import kotlinx.coroutines.launch
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    snackbarHostState: androidx.compose.material3.SnackbarHostState,
    onOpenTransaction: (Long) -> Unit,
    onOpenAi: () -> Unit,
    onOpenMonthly: (TransactionType, YearMonth) -> Unit,
    navController: androidx.navigation.NavController,
    vm: HomeViewModel = hiltViewModel(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val ext = LopTheme.extended
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    var isMonthPickerOpen by remember { mutableStateOf(false) }
    var showDeleteConfirmForTx by remember { mutableStateOf<com.lop.budget.data.local.entity.TransactionWithRelations?>(null) }

    if (isMonthPickerOpen) {
        MonthPickerBottomSheet(
            selected = state.month,
            onSelect = vm::setMonth,
            onDismiss = { isMonthPickerOpen = false },
        )
    }

    val listState = rememberLazyListState()
    val showScrollTop = listState.firstVisibleItemIndex > 0

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(
                start = 20.dp, end = 20.dp,
                top = statusBarPadding + 50.dp,
                bottom = 120.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            item(key = "budget_summary", contentType = "summary") {
                val monthName = Format.monthYear(state.month).split(" ").first()
                val solde = state.monthIncome - state.monthExpense

                var targetSolde by remember(state.month) { mutableStateOf(0f) }
                LaunchedEffect(state.month, solde) { targetSolde = solde.toFloat() }
                val animatedSolde by animateFloatAsState(
                    targetValue = targetSolde,
                    animationSpec = tween(durationMillis = 1000),
                    label = "soldeAnimation"
                )

                val soldeColor = when {
                    animatedSolde > 50 -> com.lop.budget.ui.theme.IncomeGreen
                    animatedSolde < -50 -> ExpenseCoral
                    else -> com.lop.budget.ui.theme.CategoryOrange
                }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        stringResource(R.string.home_balance_title, monthName),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        Format.money(animatedSolde.toDouble(), state.currency),
                        style = MaterialTheme.typography.displayLarge,
                        fontWeight = FontWeight.Bold,
                        color = soldeColor
                    )

                    Spacer(Modifier.height(32.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        FloatingCard(
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                                CircleIcon(
                                    icon = Icons.Filled.ArrowDownward,
                                    tint = ExpenseCoral,
                                    background = ExpenseCoral.copy(alpha = 0.15f),
                                    size = 40.dp
                                )
                                Spacer(Modifier.height(12.dp))
                                Text(stringResource(R.string.expense), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    Format.money(state.monthExpense, state.currency),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        FloatingCard(
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                                CircleIcon(
                                    icon = Icons.Filled.ArrowUpward,
                                    tint = com.lop.budget.ui.theme.IncomeGreen,
                                    background = com.lop.budget.ui.theme.IncomeGreen.copy(alpha = 0.15f),
                                    size = 40.dp
                                )
                                Spacer(Modifier.height(12.dp))
                                Text(stringResource(R.string.income), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    Format.money(state.monthIncome, state.currency),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }

            item(contentType = "unpaid_subscriptions") {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(R.string.home_unpaid_subscriptions), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)

                    FloatingCard(
                        modifier = Modifier.fillMaxWidth().clickableNoRipple { },
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircleIcon(
                                    icon = Icons.Filled.Repeat,
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    background = MaterialTheme.colorScheme.surface,
                                    size = 40.dp
                                )
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(stringResource(R.string.home_subscriptions), style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        if (state.subscriptions.isEmpty()) stringResource(R.string.home_no_pending_subscriptions) else stringResource(R.string.home_pending_subscriptions_count, state.subscriptions.size),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            if (state.subscriptions.isNotEmpty()) {
                                val totalSubs = state.subscriptions.sumOf { it.transaction.amount }
                                Text(Format.money(totalSubs, state.currency), style = MaterialTheme.typography.titleMedium, color = ExpenseCoral)
                            }
                        }
                    }
                }
            }

            item(contentType = "recent_transactions_header") {
                Text(stringResource(R.string.home_recent_transactions), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            }

            state.dayGroups.forEach { day ->
                item(key = "day_header_${day.date}", contentType = "day_header") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
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
                }

                items(
                    items = day.transactions,
                    key = { tx ->
                        val id = tx.transaction.id
                        if (id < 0L) "tx_virtual_${tx.transaction.seriesId}_${tx.transaction.seriesDate}" else {
                            val v = state.txVersions[id] ?: 0
                            "tx_${id}_v$v"
                        }
                    },
                    contentType = { "transaction" }
                ) { tx ->
                    Box(modifier = Modifier.animateItem()) {
                        val isIncome = tx.transaction.type == TransactionType.INCOME
                        val amountColor = if (isIncome) ext.income else ext.expense
                        val catColor = tx.category?.colorArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.primary
                        val recurring = tx.transaction.seriesId != null

                        val isPaid = tx.transaction.status == TransactionStatus.PAID
                        val txDeletedMsg = stringResource(R.string.tx_deleted_snackbar)
                        val undoMsg = stringResource(R.string.undo)
                        SwipeableTransactionRow(
                            isPaid = isPaid,
                            onTogglePaid = { vm.togglePaid(tx.transaction.id, tx.transaction.status) },
                            onDelete = {
                                if (tx.transaction.seriesId != null) showDeleteConfirmForTx = tx
                                else vm.deleteWithUndo(tx.transaction.id, snackbarHostState, txDeletedMsg, undoMsg)
                            }
                        ) {
                            FloatingCard(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickableNoRipple {
                                        if (tx.transaction.id >= 0L) onOpenTransaction(tx.transaction.id)
                                        else if (tx.transaction.seriesId != null) {
                                            vm.materializeAndOpen(tx.transaction.seriesId!!.toLong(), tx.transaction.seriesDate!!, onOpenTransaction)
                                        }
                                    }
                                    .alpha(if (isPaid) 0.5f else 1f),
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
                                                Icon(Icons.Filled.Repeat, stringResource(R.string.home_recurring_tag), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(15.dp))
                                            }
                                        }
                                        Text(tx.account?.name ?: "", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        if (tx.tags.isNotEmpty()) {
                                            Spacer(Modifier.height(4.dp))
                                            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                tx.tags.take(3).forEach { tag ->
                                                    com.lop.budget.ui.components.PillTag(text = tag.name, color = Color(tag.colorArgb))
                                                }
                                            }
                                        }
                                    }
                                    Text(
                                        (if (isIncome) "+" else "−") + Format.money(tx.transaction.amount, state.currency),
                                        style = MaterialTheme.typography.titleMedium,
                                        color = amountColor,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (state.dayGroups.isEmpty()) {
                item(contentType = "empty_state") {
                    Text(stringResource(R.string.home_no_transactions_month), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        AnimatedVisibility(
            visible = showScrollTop,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(
                    start = 20.dp,
                    top = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 88.dp,
                ),
        ) {
            Surface(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    scope.launch { listState.animateScrollToItem(0) }
                },
                shape = androidx.compose.foundation.shape.CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f),
                shadowElevation = 4.dp,
                modifier = Modifier.size(48.dp),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(Icons.Filled.ArrowUpward, contentDescription = stringResource(R.string.home_scroll_to_top), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.background.copy(alpha = 0.95f),
                            MaterialTheme.colorScheme.background.copy(alpha = 0.8f),
                            MaterialTheme.colorScheme.background.copy(alpha = 0f),
                        ),
                        startY = 0f,
                        endY = 300f
                    )
                )
                .padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 16.dp)
                .align(Alignment.TopCenter)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f), androidx.compose.foundation.shape.RoundedCornerShape(50))
                        .clickableNoRipple { isMonthPickerOpen = true }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Icon(Icons.Filled.CalendarMonth, contentDescription = stringResource(R.string.home_change_month), tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(Format.monthYear(state.month), style = MaterialTheme.typography.titleSmall)
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!state.isCurrentMonth) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f), androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                                .clickableNoRipple { vm.goToCurrentMonth() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Filled.Today, contentDescription = stringResource(R.string.home_go_to_current_month), tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
                        }
                    }

                    // Icône "transactions détectées" (remplace le chip € 1,16)
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f), androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                            .clickableNoRipple { navController.navigate(Routes.DETECTED) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.NotificationsActive,
                            contentDescription = stringResource(R.string.home_detected_transactions),
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(20.dp),
                        )

                        if (state.detectedCount > 0) {
                            Surface(
                                color = MaterialTheme.colorScheme.error,
                                shape = androidx.compose.foundation.shape.CircleShape,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(top = 6.dp, end = 6.dp)
                                    .size(16.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = state.detectedCount.coerceAtMost(9).toString(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onError,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f), androidx.compose.foundation.shape.RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.Settings,
                            null,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .size(20.dp)
                                .clickableNoRipple { navController.navigate(Routes.SETTINGS) },
                        )
                    }
                }
            }
        }
    }
}

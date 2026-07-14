package com.lop.budget.ui.screens.home

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.domain.model.SeriesDeletionMode
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.ui.components.CircleIcon
import com.lop.budget.ui.components.FloatingCard
import com.lop.budget.ui.components.MonthPickerBottomSheet
import com.lop.budget.ui.components.RecurringDeleteChoice
import com.lop.budget.ui.components.RecurringDeleteSheet
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
import kotlin.math.abs

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
    val haptic = LocalHapticFeedback.current

    val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    var isMonthPickerOpen by remember { mutableStateOf(false) }
    var showDeleteConfirmForTx by remember { mutableStateOf<TransactionWithRelations?>(null) }

    if (isMonthPickerOpen) {
        MonthPickerBottomSheet(
            selected = state.month,
            onSelect = vm::setMonth,
            onDismiss = { isMonthPickerOpen = false },
        )
    }

    // Pager configuration for infinite-like horizontal scrolling
    val initialPage = 5000
    val pagerState = rememberPagerState(initialPage = initialPage) { 10000 }

    // Sync Pager -> ViewModel
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            val diff = (page - initialPage).toLong()
            val targetMonth = YearMonth.now().plusMonths(diff)
            if (state.month != targetMonth) {
                vm.setMonth(targetMonth)
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
        }
    }

    // Sync ViewModel -> Pager (for manual month selection)
    LaunchedEffect(state.month) {
        val now = YearMonth.now()
        val diff = (state.month.year - now.year) * 12 + (state.month.monthValue - now.monthValue)
        val targetPage = initialPage + diff
        if (pagerState.currentPage != targetPage) {
            val distance = abs(pagerState.currentPage - targetPage)
            if (distance > 1) {
                pagerState.scrollToPage(targetPage)
            } else {
                pagerState.animateScrollToPage(targetPage)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 1,
            userScrollEnabled = true
        ) { page ->
            val diff = (page - initialPage).toLong()
            val pageMonth = remember(page) { YearMonth.now().plusMonths(diff) }
            val monthState by remember(pageMonth) { vm.observeMonthState(pageMonth) }
                .collectAsStateWithLifecycle(initialValue = HomeUiState(month = pageMonth))

            HomeContent(
                state = monthState,
                statusBarPadding = statusBarPadding,
                onOpenTransaction = onOpenTransaction,
                onOpenMonthly = onOpenMonthly,
                onDeleteRequest = { showDeleteConfirmForTx = it },
                snackbarHostState = snackbarHostState,
                vm = vm
            )
        }

        // Overlay UI (Header and Floating elements)
        HomeOverlay(
            state = state,
            isCurrentMonth = state.isCurrentMonth,
            onMonthClick = { isMonthPickerOpen = true },
            onTodayClick = { vm.goToCurrentMonth() },
            onDetectedClick = { navController.navigate(Routes.DETECTED) },
            onSettingsClick = { navController.navigate(Routes.SETTINGS) },
            onScrollTop = { /* Handled in HomeContent if needed, or via global state */ },
            modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter)
        )
    }

    if (showDeleteConfirmForTx != null) {
        val toDelete = showDeleteConfirmForTx!!
        val context = androidx.compose.ui.platform.LocalContext.current
        RecurringDeleteSheet(
            onDismiss = { showDeleteConfirmForTx = null },
            showFutureOnly = true,
            onChoose = { choice ->
                showDeleteConfirmForTx = null
                when (choice) {
                    RecurringDeleteChoice.THIS_OCCURRENCE -> {
                        vm.deleteOccurrenceWithUndo(toDelete.transaction.id, snackbarHostState, context.getString(R.string.tx_deleted_snackbar), context.getString(R.string.undo))
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
fun HomeContent(
    state: HomeUiState,
    statusBarPadding: androidx.compose.ui.unit.Dp,
    onOpenTransaction: (Long) -> Unit,
    onOpenMonthly: (TransactionType, YearMonth) -> Unit,
    onDeleteRequest: (TransactionWithRelations) -> Unit,
    snackbarHostState: androidx.compose.material3.SnackbarHostState,
    vm: HomeViewModel
) {
    val listState = rememberLazyListState()
    val ext = LopTheme.extended

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
            val animatedSolde by animateFloatAsState(targetValue = targetSolde, animationSpec = tween(1000), label = "soldeAnimation")
            val soldeColor = when {
                animatedSolde > 50 -> com.lop.budget.ui.theme.IncomeGreen
                animatedSolde < -50 -> ExpenseCoral
                else -> com.lop.budget.ui.theme.CategoryOrange
            }

            Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.home_balance_title, monthName), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Text(Format.money(animatedSolde.toDouble(), state.currency), style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Bold, color = soldeColor)
                Spacer(Modifier.height(32.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    StatCard(
                        label = stringResource(R.string.expense),
                        amount = state.monthExpense,
                        currency = state.currency,
                        icon = Icons.Filled.ArrowDownward,
                        color = ExpenseCoral,
                        modifier = Modifier
                            .weight(1f)
                            .clickableNoRipple { onOpenMonthly(TransactionType.EXPENSE, state.month) }
                    )

                    StatCard(
                        label = stringResource(R.string.income),
                        amount = state.monthIncome,
                        currency = state.currency,
                        icon = Icons.Filled.ArrowUpward,
                        color = com.lop.budget.ui.theme.IncomeGreen,
                        modifier = Modifier
                            .weight(1f)
                            .clickableNoRipple { onOpenMonthly(TransactionType.INCOME, state.month) }
                    )
                }
            }
        }

        item(contentType = "unpaid_subscriptions") {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.home_unpaid_subscriptions), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                FloatingCard(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant, contentPadding = PaddingValues(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircleIcon(icon = Icons.Filled.Repeat, tint = MaterialTheme.colorScheme.onSurface, background = MaterialTheme.colorScheme.surface, size = 40.dp)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(stringResource(R.string.home_subscriptions), style = MaterialTheme.typography.titleMedium)
                                Text(if (state.subscriptions.isEmpty()) stringResource(R.string.home_no_pending_subscriptions) else stringResource(R.string.home_pending_subscriptions_count, state.subscriptions.size), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "${day.date.dayOfMonth} ${day.date.month.getDisplayName(TextStyle.SHORT, Locale.FRANCE)}", style = MaterialTheme.typography.titleLarge)
                    Text(text = Format.money(day.total, state.currency), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
                }
            }

            items(items = day.transactions, key = { tx -> 
                val id = tx.transaction.id
                if (id < 0L) "tx_virtual_${tx.transaction.seriesId}_${tx.transaction.seriesDate}" else "tx_${id}_v${state.txVersions[id] ?: 0}"
            }, contentType = { "transaction" }) { tx ->
                Box(modifier = Modifier.animateItem()) {
                    val isIncome = tx.transaction.type == TransactionType.INCOME
                    val amountColor = if (isIncome) ext.income else ext.expense
                    val catColor = tx.category?.colorArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.primary
                    val isPaid = tx.transaction.status == TransactionStatus.PAID
                    
                    val txDeletedMsg = stringResource(R.string.tx_deleted_snackbar)
                    val undoMsg = stringResource(R.string.undo)
                    
                    SwipeableTransactionRow(
                        isPaid = isPaid,
                        onTogglePaid = { vm.togglePaid(tx.transaction.id, tx.transaction.status) },
                        onDelete = {
                            if (tx.transaction.seriesId != null) {
                                onDeleteRequest(tx)
                            } else {
                                vm.deleteWithUndo(
                                    tx.transaction.id,
                                    snackbarHostState,
                                    txDeletedMsg,
                                    undoMsg
                                )
                            }
                        }
                    ) {
                        FloatingCard(
                            modifier = Modifier.fillMaxWidth().clickableNoRipple {
                                if (tx.transaction.id >= 0L) onOpenTransaction(tx.transaction.id)
                                else tx.transaction.seriesId?.let { vm.materializeAndOpen(it.toLong(), tx.transaction.seriesDate!!, onOpenTransaction) }
                            }.alpha(if (isPaid) 0.5f else 1f),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            contentPadding = PaddingValues(14.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircleIcon(icon = IconMapper.get(tx.category?.icon ?: "category"), tint = catColor, background = catColor.copy(alpha = 0.18f))
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(tx.transaction.title, style = MaterialTheme.typography.titleMedium)
                                        if (tx.transaction.seriesId != null) {
                                            Spacer(Modifier.width(6.dp))
                                            Icon(Icons.Filled.Repeat, stringResource(R.string.home_recurring_tag), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(15.dp))
                                        }
                                    }
                                    Text(tx.account?.name ?: "", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text((if (isIncome) "+" else "−") + Format.money(tx.transaction.amount, state.currency), style = MaterialTheme.typography.titleMedium, color = amountColor, fontWeight = FontWeight.SemiBold)
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
}

@Composable
fun StatCard(label: String, amount: Double, currency: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color, modifier: Modifier = Modifier) {
    FloatingCard(modifier = modifier, color = MaterialTheme.colorScheme.surfaceVariant, contentPadding = PaddingValues(16.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            CircleIcon(icon = icon, tint = color, background = color.copy(alpha = 0.15f), size = 40.dp)
            Spacer(Modifier.height(12.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(Format.money(amount, currency), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
fun HomeOverlay(
    state: HomeUiState,
    isCurrentMonth: Boolean,
    onMonthClick: () -> Unit,
    onTodayClick: () -> Unit,
    onDetectedClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onScrollTop: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.background(Brush.verticalGradient(colors = listOf(MaterialTheme.colorScheme.background.copy(alpha = 0.95f), MaterialTheme.colorScheme.background.copy(alpha = 0.8f), MaterialTheme.colorScheme.background.copy(alpha = 0f)), startY = 0f, endY = 300f)).padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f), androidx.compose.foundation.shape.RoundedCornerShape(50)).clickableNoRipple { onMonthClick() }.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Icon(Icons.Filled.CalendarMonth, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(Format.monthYear(state.month), style = MaterialTheme.typography.titleSmall)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!isCurrentMonth) {
                    IconButton(Icons.Filled.Today, onTodayClick)
                }
                DetectedIcon(state.detectedCount, onDetectedClick)
                IconButton(Icons.Filled.Settings, onSettingsClick)
            }
        }
    }
}

@Composable
fun IconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Box(modifier = Modifier.size(40.dp).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f), androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).clickableNoRipple { onClick() }, contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
    }
}

@Composable
fun DetectedIcon(count: Int, onClick: () -> Unit) {
    Box(modifier = Modifier.size(40.dp).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f), androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).clickableNoRipple { onClick() }, contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.NotificationsActive, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
        if (count > 0) {
            Surface(color = MaterialTheme.colorScheme.error, shape = androidx.compose.foundation.shape.CircleShape, modifier = Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 6.dp).size(16.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Text(text = count.coerceAtMost(9).toString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onError, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

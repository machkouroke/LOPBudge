package com.lop.budget.ui.screens.home

import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.lop.budget.R
import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.domain.model.AccountBalance
import com.lop.budget.domain.model.SeriesDeletionMode
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.ui.components.CircleIcon
import com.lop.budget.ui.components.FloatingCard
import com.lop.budget.ui.components.MonthPickerBottomSheet
import com.lop.budget.ui.components.RecurringDeleteChoice
import com.lop.budget.ui.components.RecurringDeleteSheet
import com.lop.budget.ui.components.clickableNoRipple
import com.lop.budget.ui.components.transactionDayGroups
import com.lop.budget.ui.navigation.Routes
import com.lop.budget.ui.theme.ExpenseCoral
import com.lop.budget.ui.theme.LopTheme
import com.lop.budget.util.Format
import com.lop.budget.util.IconMapper
import java.time.YearMonth

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    snackbarHostState: androidx.compose.material3.SnackbarHostState,
    onOpenTransaction: (Long) -> Unit,
    onOpenAi: () -> Unit,
    onOpenMonthly: (TransactionType, YearMonth) -> Unit,
    navController: NavController,
    vm: HomeViewModel = hiltViewModel(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current

    // Vérification de la permission au lancement
    LaunchedEffect(state.notificationDetectionEnabled) {
        if (state.notificationDetectionEnabled) {
            val isListenerEnabled = android.provider.Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            )?.contains(context.packageName) == true
            
            if (!isListenerEnabled) {
                val result = snackbarHostState.showSnackbar(
                    message = context.getString(R.string.notif_listener_missing_msg),
                    actionLabel = context.getString(R.string.notif_listener_missing_action),
                    duration = androidx.compose.material3.SnackbarDuration.Indefinite
                )
                if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                    context.startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
        }
    }

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

    // Sync Pager -> ViewModel (Déclenché uniquement quand la page est fixée)
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { page ->
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
            val distance = kotlin.math.abs(pagerState.currentPage - targetPage)
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
            userScrollEnabled = true,
            key = { it } // Utilisation d'une clé stable pour éviter de recréer les mois adjacents
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
                onOpenAccounts = { navController.navigate(Routes.ACCOUNTS) },
                onOpenAccountDetail = { id -> navController.navigate(Routes.accountDetail(id)) },
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
            onSearchClick = { navController.navigate(Routes.SEARCH) },
            onDetectedClick = { navController.navigate(Routes.DETECTED) },
            onSettingsClick = { navController.navigate(Routes.SETTINGS) },
            modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter)
        )
    }

    if (showDeleteConfirmForTx != null) {
        val toDelete = showDeleteConfirmForTx!!
        val txDeletedMsg = stringResource(R.string.tx_deleted_snackbar)
        val undoMsg = stringResource(R.string.undo)
        
        RecurringDeleteSheet(
            onDismiss = { showDeleteConfirmForTx = null },
            showFutureOnly = true,
            onChoose = { choice ->
                showDeleteConfirmForTx = null
                when (choice) {
                    RecurringDeleteChoice.THIS_OCCURRENCE -> {
                        vm.deleteOccurrenceWithUndo(toDelete.transaction.id, snackbarHostState, txDeletedMsg, undoMsg)
                    }
                    RecurringDeleteChoice.FUTURE_ONLY -> {
                        toDelete.transaction.seriesId?.let { 
                            vm.deleteSeriesWithUndo(it, SeriesDeletionMode.FUTURE, toDelete.transaction.date, snackbarHostState, txDeletedMsg, undoMsg) 
                        }
                    }
                    RecurringDeleteChoice.ALL_SERIES -> {
                        toDelete.transaction.seriesId?.let { 
                            vm.deleteSeriesWithUndo(it, SeriesDeletionMode.ALL, null, snackbarHostState, txDeletedMsg, undoMsg)
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
    onOpenAccounts: () -> Unit,
    onOpenAccountDetail: (Long) -> Unit,
    onDeleteRequest: (TransactionWithRelations) -> Unit,
    snackbarHostState: androidx.compose.material3.SnackbarHostState,
    vm: HomeViewModel
) {
    val listState = rememberLazyListState()

    val txDeletedMsg = stringResource(R.string.tx_deleted_snackbar)
    val undoMsg = stringResource(R.string.undo)

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
            val solde = remember(state.monthIncome, state.monthExpense) {
                state.monthIncome - state.monthExpense
            }
            
            val soldeColor = remember(solde) {
                when {
                    solde > 50 -> com.lop.budget.ui.theme.IncomeGreen
                    solde < -50 -> ExpenseCoral
                    else -> com.lop.budget.ui.theme.CategoryOrange
                }
            }

            val monthLabel = remember(state.month, state.isCurrentMonth) {
                val name = Format.monthYear(state.month).split(" ").first()
                if (state.isCurrentMonth) "Solde de $name" else "Solde en $name"
            }

            Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = monthLabel,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = Format.money(solde, state.currency),
                    style = MaterialTheme.typography.displayLarge,
                    fontWeight = FontWeight.Bold,
                    color = soldeColor
                )
                Spacer(Modifier.height(32.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    StatCard(
                        label = stringResource(R.string.expense),
                        amount = state.monthExpense,
                        currency = state.currency,
                        icon = Icons.Filled.ArrowDownward,
                        color = ExpenseCoral,
                        modifier = Modifier.weight(1f).clickableNoRipple { onOpenMonthly(TransactionType.EXPENSE, state.month) }
                    )
                    StatCard(
                        label = stringResource(R.string.income),
                        amount = state.monthIncome,
                        currency = state.currency,
                        icon = Icons.Filled.ArrowUpward,
                        color = com.lop.budget.ui.theme.IncomeGreen,
                        modifier = Modifier.weight(1f).clickableNoRipple { onOpenMonthly(TransactionType.INCOME, state.month) }
                    )
                }
            }
        }

        item(key = "accounts_widget", contentType = "accounts") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        stringResource(R.string.accounts_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    TextButton(onClick = onOpenAccounts) {
                        Text(stringResource(R.string.see_all))
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, modifier = Modifier.size(16.dp))
                    }
                }
                
                if (state.accounts.isNotEmpty()) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(horizontal = 0.dp)
                    ) {
                        items(state.accounts, key = { it.account.id }) { balance ->
                            AccountWidgetCard(balance, state.currency) {
                                onOpenAccountDetail(balance.account.id)
                            }
                        }
                    }
                } else {
                    Text(
                        stringResource(R.string.no_accounts_to_show),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp)
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

        transactionDayGroups(
            dayGroups = state.dayGroups,
            currency = state.currency,
            txVersions = state.txVersions,
            onOpenTransaction = onOpenTransaction,
            onMaterializeAndOpen = { sid, date -> vm.materializeAndOpen(sid, date, onOpenTransaction) },
            onTogglePaid = vm::togglePaid,
            onDeleteRequest = onDeleteRequest,
            onDeleteSimple = { id -> vm.deleteWithUndo(id, snackbarHostState, txDeletedMsg, undoMsg) }
        )

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
fun AccountWidgetCard(
    balance: AccountBalance,
    currency: String,
    onClick: () -> Unit
) {
    val color = Color(balance.account.colorArgb)
    FloatingCard(
        modifier = Modifier.width(160.dp).clickableNoRipple(onClick),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        contentPadding = PaddingValues(12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircleIcon(
                    icon = IconMapper.get(balance.account.icon),
                    tint = color,
                    background = color.copy(alpha = 0.15f),
                    size = 32.dp
                )
            }
            Column {
                Text(
                    balance.account.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    Format.money(balance.balance, currency),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
fun HomeOverlay(
    state: HomeUiState,
    isCurrentMonth: Boolean,
    onMonthClick: () -> Unit,
    onTodayClick: () -> Unit,
    onSearchClick: () -> Unit,
    onDetectedClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Optimization: Read monthYear once
    val monthLabel = remember(state.month) { Format.monthYear(state.month) }
    
    Box(
        modifier = modifier
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.background.copy(alpha = 0.95f),
                        MaterialTheme.colorScheme.background.copy(alpha = 0.8f),
                        MaterialTheme.colorScheme.background.copy(alpha = 0f)
                    ),
                    startY = 0f,
                    endY = 300f
                )
            )
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
                        androidx.compose.foundation.shape.RoundedCornerShape(50)
                    )
                    .clickableNoRipple { onMonthClick() }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Icon(
                    Icons.Filled.CalendarMonth,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(monthLabel, style = MaterialTheme.typography.titleSmall)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!isCurrentMonth) {
                    IconButton(Icons.Filled.Today, onTodayClick)
                }
                IconButton(Icons.Default.Search, onSearchClick)
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

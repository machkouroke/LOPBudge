package com.lop.budget.ui.screens.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.lop.budget.R
import com.lop.budget.domain.model.AccountBalance
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.ui.common.TestTags
import com.lop.budget.ui.components.BalanceDashboardWidget
import com.lop.budget.ui.components.CircleIcon
import com.lop.budget.ui.components.FloatingCard
import com.lop.budget.ui.components.MonthPickerBottomSheet
import com.lop.budget.ui.components.TransactionsDashboardWidget
import com.lop.budget.ui.components.clickableNoRipple
import com.lop.budget.ui.navigation.Routes
import com.lop.budget.ui.theme.ExpenseCoral
import com.lop.budget.util.Format
import com.lop.budget.util.IconMapper
import dev.chrisbanes.haze.HazeState
import java.time.YearMonth

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    snackbarHostState: androidx.compose.material3.SnackbarHostState,
    onOpenTransaction: (Long) -> Unit,
    navController: NavController,
    hazeState: HazeState? = null,
    vm: HomeViewModel = hiltViewModel()
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Vérification de la permission au lancement
    val notifMsg = stringResource(R.string.notif_listener_missing_msg)
    val notifAction = stringResource(R.string.notif_listener_missing_action)
    LaunchedEffect(state.notificationDetectionEnabled) {
        if (state.notificationDetectionEnabled) {
            val isListenerEnabled = android.provider.Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            )?.contains(context.packageName) == true
            
            if (!isListenerEnabled) {
                val result = snackbarHostState.showSnackbar(
                    message = notifMsg,
                    actionLabel = notifAction,
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

    @Suppress("UNUSED_VALUE")
    var isMonthPickerOpen by remember { mutableStateOf(false) }

    if (isMonthPickerOpen) {
        MonthPickerBottomSheet(
            selected = state.month,
            onSelect = vm::setMonth,

            onDismiss = { isMonthPickerOpen = false },
        )
    }

    Box(modifier = Modifier.fillMaxSize().testTag(TestTags.SCREEN_HOME)) {
        HomeContent(
            state = state,
            statusBarPadding = statusBarPadding,
            onOpenTransaction = onOpenTransaction,
            onOpenMonthly = { type, ym ->
                navController.navigate(Routes.monthly(type, ym, mode = "ANALYTICS"))
            },
            onSeeAllTransactions = { type, ym ->
                navController.navigate(Routes.monthly(type, ym, mode = "HISTORY"))
            },
            onOpenAccounts = { navController.navigate(Routes.ACCOUNTS) },
            onOpenAccountDetail = { id -> navController.navigate(Routes.accountDetail(id)) },
            onPrevMonth = { vm.prevMonth() },
            onNextMonth = { vm.nextMonth() },
            hazeState = hazeState,
            vm = vm
        )

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
}

@Composable
fun HomeContent(
    state: HomeUiState,
    statusBarPadding: androidx.compose.ui.unit.Dp,
    onOpenTransaction: (Long) -> Unit,
    onOpenMonthly: (TransactionType, YearMonth) -> Unit,
    onSeeAllTransactions: (TransactionType, YearMonth) -> Unit,
    onOpenAccounts: () -> Unit,
    onOpenAccountDetail: (Long) -> Unit,
    onPrevMonth: () -> Unit,
    onNextMonth: () -> Unit,
    hazeState: HazeState? = null,
    vm: HomeViewModel
) {
    val listState = rememberLazyListState()

    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("home_lazy_column"),
        state = listState,
        contentPadding = PaddingValues(
            start = 20.dp, end = 20.dp,
            top = statusBarPadding + 50.dp,
            bottom = 120.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item(key = "budget_summary", contentType = "summary") {
            // Using AnimatedContent for smooth transitions between months
            AnimatedContent(
                targetState = state.month,
                transitionSpec = {
                    fadeIn(animationSpec = tween(300)).togetherWith(fadeOut(animationSpec = tween(300)))
                },
                label = "dashboard_balance"
            ) { targetMonth ->
                // Note: since the rest of 'state' (income, expense) is already for 'targetMonth', 
                // we just use it directly.
                BalanceDashboardWidget(
                    month = targetMonth,
                    income = state.monthIncome,
                    expense = state.monthExpense,
                    currency = state.currency,
                    onPrevMonth = onPrevMonth,
                    onNextMonth = onNextMonth,
                    onOpenMonthly = { onOpenMonthly(it, targetMonth) }
                )
            }
        }

        item(key = "accounts_widget", contentType = "accounts") {
            AnimatedContent(
                targetState = state.month,
                transitionSpec = {
                    fadeIn(animationSpec = tween(300)).togetherWith(fadeOut(animationSpec = tween(300)))
                },
                label = "dashboard_accounts"
            ) { targetMonth ->
                // Using targetMonth to ensure AnimatedContent updates correctly
                val accounts = remember(targetMonth, state.accounts) { state.accounts }
                FloatingCard(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 18.dp, end = 8.dp, top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                stringResource(R.string.accounts_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            TextButton(
                                onClick = onOpenAccounts,
                                modifier = Modifier.testTag(TestTags.HOME_SEE_ALL_ACCOUNTS)
                            ) {
                                Text(stringResource(R.string.see_all))
                                Icon(
                                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        if (accounts.isNotEmpty()) {
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 18.dp, top = 8.dp)
                            ) {
                                items(accounts, key = { it.account.id }) { balance ->
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
                                modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 18.dp, top = 8.dp)
                            )
                        }
                    }
                }
            }
        }

        item(contentType = "unpaid_subscriptions") {
            AnimatedContent(
                targetState = state.month,
                transitionSpec = {
                    fadeIn(animationSpec = tween(300)).togetherWith(fadeOut(animationSpec = tween(300)))
                },
                label = "dashboard_subscriptions"
            ) { targetMonth ->
                // Using targetMonth to ensure AnimatedContent updates correctly
                val subscriptions = remember(targetMonth, state.subscriptions) { state.subscriptions }
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        stringResource(R.string.home_unpaid_subscriptions),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    FloatingCard(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        contentPadding = PaddingValues(16.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircleIcon(
                                    icon = Icons.Filled.Repeat,
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    background = MaterialTheme.colorScheme.surface,
                                    size = 40.dp
                                )
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(
                                        stringResource(R.string.home_subscriptions),
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Text(
                                        if (subscriptions.isEmpty()) stringResource(R.string.home_no_pending_subscriptions) else stringResource(
                                            R.string.home_pending_subscriptions_count,
                                            subscriptions.size
                                        ),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            if (subscriptions.isNotEmpty()) {
                                val totalSubs = subscriptions.sumOf { it.transaction.amount }
                                Text(
                                    Format.money(totalSubs, state.currency),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = ExpenseCoral
                                )
                            }
                        }
                    }
                }
            }
        }

        item(contentType = "recent_transactions_header") {
            AnimatedContent(
                targetState = state.month,
                transitionSpec = {
                    fadeIn(animationSpec = tween(300)).togetherWith(fadeOut(animationSpec = tween(300)))
                },
                label = "dashboard_transactions"
            ) { targetMonth ->
                TransactionsDashboardWidget(
                    transactions = state.dashboardTransactions,
                    currency = state.currency,
                    onSeeAll = { onSeeAllTransactions(TransactionType.EXPENSE, targetMonth) },
                    onOpenTransaction = onOpenTransaction,
                    onMaterializeAndOpen = { sid, date ->
                        vm.materializeAndOpen(
                            sid,
                            date,
                            onOpenTransaction
                        )
                    },
                    hazeState = hazeState
                )
            }
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
        modifier = Modifier
            .width(160.dp)
            .clickableNoRipple(onClick)
            .testTag("${TestTags.HOME_ACCOUNT_CARD}_${balance.account.id}"),
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
                    .testTag(TestTags.HOME_MONTH_PICKER)
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
                    IconButton(
                        icon = Icons.Filled.Today, 
                        onClick = onTodayClick,
                        modifier = Modifier.testTag(TestTags.HOME_GO_TO_TODAY)
                    )
                }
                IconButton(
                    icon = Icons.Default.Search, 
                    onClick = onSearchClick, 
                    contentDescription = "Rechercher",
                    modifier = Modifier.testTag(TestTags.NAV_SEARCH_BUTTON)
                )
                DetectedIcon(state.detectedCount, onDetectedClick)
                IconButton(
                    icon = Icons.Filled.Settings, 
                    onClick = onSettingsClick,
                    contentDescription = "Réglages",
                    modifier = Modifier.testTag(TestTags.NAV_SETTINGS_BUTTON)
                )
            }
        }
    }
}

@Composable
fun IconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    Box(

        modifier = modifier
            .size(40.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f), androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .clickableNoRipple { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
    }
}

@Composable
fun DetectedIcon(count: Int, onClick: () -> Unit) {
    Box(modifier = Modifier.size(40.dp).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f), androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).clickableNoRipple { onClick() }.testTag(TestTags.NAV_DETECTED_BUTTON), contentAlignment = Alignment.Center) {
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

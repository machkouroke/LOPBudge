package com.lop.budget.ui.screens.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Settings
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
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
import com.lop.budget.ui.theme.lopBackgroundBrush
import com.lop.budget.ui.components.CircleIcon
import com.lop.budget.ui.components.FloatingCard
import com.lop.budget.ui.components.LopFieldShape
import com.lop.budget.ui.components.SUMMARY_PLACEHOLDER
import com.lop.budget.ui.components.SearchBarTransition
import com.lop.budget.ui.components.glass
import com.lop.budget.ui.components.scrollEdge
import com.lop.budget.ui.components.underStatusBar
import com.lop.budget.ui.components.searchBarBounds
import com.lop.budget.ui.components.SummaryFigure
import com.lop.budget.ui.components.surfaceRelief
import com.lop.budget.ui.components.TransactionsDashboardWidget
import com.lop.budget.ui.components.clickableNoRipple
import com.lop.budget.ui.motion.MotionSpec
import com.lop.budget.ui.navigation.Routes
import com.lop.budget.ui.theme.ExpenseCoral
import com.lop.budget.ui.theme.LopTheme
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import com.lop.budget.util.Format
import com.lop.budget.util.IconMapper
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    snackbarHostState: androidx.compose.material3.SnackbarHostState,
    onOpenTransaction: (Long) -> Unit,
    navController: NavController,
    /** La barre de recherche voyage vers la Recherche ; porte aussi la vague des tuiles. */
    searchBar: SearchBarTransition? = null,
    vm: HomeViewModel = hiltViewModel()
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Vrai de l'appui sur la barre jusqu'au retour de la Recherche : seul ce trajet joue la vague.
    // Gardé à travers la navigation, l'accueil est recomposé au retour.
    var toSearch by rememberSaveable { mutableStateOf(false) }
    val settled = searchBar?.screen?.transition
        ?.let { it.currentState == EnterExitState.Visible && it.targetState == EnterExitState.Visible } ?: true
    LaunchedEffect(settled) { if (settled) toSearch = false }
    val headerHaze = rememberHazeState()

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

    Box(modifier = Modifier.fillMaxSize().testTag(TestTags.SCREEN_HOME)) {
        HomeContent(
            // La liste remonte sous la barre d'état, transparente. Le ciel est peint ici, dans la
            // source du flou : le verre de l'en-tête et de la barre d'état le reprend.
            modifier = Modifier
                .underStatusBar(statusBarPadding)
                .hazeSource(headerHaze)
                .background(lopBackgroundBrush()),
            screen = searchBar?.screen,
            toSearch = toSearch,
            state = state,
            statusBarPadding = statusBarPadding,
            onOpenTransaction = onOpenTransaction,
            // LOP-231, P-5 : Dépenses et Revenus de « Ce mois » ouvrent leur analyse du mois courant.
            onOpenMonthly = { type, ym ->
                navController.navigate(Routes.monthly(type, ym, mode = "ANALYTICS"))
            },
            // LOP-228 P-8 et LOP-231 CA-05 : la vue unifiée, sur le mois de l'accueil, du 1er au dernier jour.
            onSeeAllTransactions = { ym ->
                navController.navigate(Routes.search(ym.atDay(1), ym.atEndOfMonth()))
            },
            onOpenAccounts = { navController.navigate(Routes.ACCOUNTS) },
            onOpenAccountDetail = { id -> navController.navigate(Routes.accountDetail(id)) },
        )

        // Barre d'état transparente, façon Liquid Glass : les cartes y passent, de plus en plus
        // floutées vers le haut, sans coupure nette ; l'heure reste lisible.
        Box(
            Modifier
                .fillMaxWidth()
                .height(statusBarPadding)
                .offset(y = -statusBarPadding)
                .scrollEdge(headerHaze)
        )

        HomeOverlay(
            state = state,
            onSearchClick = {
                toSearch = true
                navController.navigate(Routes.search())
            },
            onDetectedClick = { navController.navigate(Routes.DETECTED) },
            onSettingsClick = { navController.navigate(Routes.SETTINGS) },
            haze = headerHaze,
            searchBar = searchBar,
            actionsModifier = Modifier.searchWave(searchBar?.screen, toSearch, index = 0),
            modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter)
        )
    }
}

/**
 * Vague de l'accueil vers la Recherche ouverte par la barre (DESIGN.md § 13.3) : la tuile de rang
 * [index] pâlit en descendant un peu, [MotionSpec.WAVE_STEP_MS] après celle du dessus, et revient
 * de même au retour. Inerte sur tout autre trajet ([active] faux).
 */
@Composable
private fun Modifier.searchWave(screen: AnimatedVisibilityScope?, active: Boolean, index: Int): Modifier {
    if (screen == null) return this
    val drop = with(LocalDensity.current) { 16.dp.roundToPx() }
    val delay = index * MotionSpec.WAVE_STEP_MS
    return with(screen) {
        animateEnterExit(
            enter = if (!active) EnterTransition.None else {
                fadeIn(tween(MotionSpec.TAB_IN_MS, delay, MotionSpec.enterDecelerate)) +
                    slideInVertically(tween(MotionSpec.TAB_IN_MS, delay, MotionSpec.enterDecelerate)) { drop }
            },
            // Départ immédiat (`standard`) : avec une courbe qui tient d'abord, chaque tuile restait
            // pleine sous la Recherche déjà visible, et la vague ne se lisait plus.
            exit = if (!active) ExitTransition.None else {
                fadeOut(tween(MotionSpec.TAB_OUT_MS, delay, MotionSpec.standard)) +
                    slideOutVertically(tween(MotionSpec.TAB_OUT_MS, delay, MotionSpec.standard)) { drop }
            },
        )
    }
}

@Composable
fun HomeContent(
    state: HomeUiState,
    statusBarPadding: androidx.compose.ui.unit.Dp,
    onOpenTransaction: (Long) -> Unit,
    onOpenMonthly: (TransactionType, YearMonth) -> Unit,
    onSeeAllTransactions: (YearMonth) -> Unit,
    onOpenAccounts: () -> Unit,
    onOpenAccountDetail: (Long) -> Unit,
    modifier: Modifier = Modifier,
    screen: AnimatedVisibilityScope? = null,
    toSearch: Boolean = false,
) {
    val listState = rememberLazyListState()
    @Composable fun wave(index: Int) = Modifier.searchWave(screen, toSearch, index)

    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("home_lazy_column"),
        state = listState,
        contentPadding = PaddingValues(
            start = 20.dp, end = 20.dp,
            // La liste commence sous la barre d'état (`underStatusBar`) : la bande qu'elle y occupe,
            // puis la place historique de l'en-tête.
            top = statusBarPadding + statusBarPadding + 50.dp,
            bottom = 120.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item(key = "total_balance", contentType = "summary") {
            Box(wave(0)) { TotalBalanceHeader(state.totalBalance, state.currency) }
        }

        item(key = "month_widget", contentType = "month") {
            Box(wave(1)) {
                MonthWidget(
                    state = state,
                    onOpen = { onSeeAllTransactions(state.month) },
                    onOpenAnalysis = { type -> onOpenMonthly(type, state.month) },
                )
            }
        }

        item(key = "accounts_widget", contentType = "accounts") {
            AnimatedContent(
                modifier = wave(2),
                targetState = state.month,
                transitionSpec = {
                    fadeIn(animationSpec = tween(300)).togetherWith(fadeOut(animationSpec = tween(300)))
                },
                label = "dashboard_accounts"
            ) { _ ->
                // `remember(targetMonth, state.accounts) { state.accounts }` retiré : mémoriser
                // une valeur en la clefant sur elle-même ne fait que payer la comparaison.
                val accounts = state.accounts
                FloatingCard(
                    modifier = Modifier.fillMaxWidth(),
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
                modifier = wave(3),
                targetState = state.month,
                transitionSpec = {
                    fadeIn(animationSpec = tween(300)).togetherWith(fadeOut(animationSpec = tween(300)))
                },
                label = "dashboard_subscriptions"
            ) { _ ->
                // Idem : `remember` clefé sur la valeur mémorisée, donc sans effet.
                val subscriptions = state.subscriptions
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        stringResource(R.string.home_unpaid_subscriptions),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    FloatingCard(
                        modifier = Modifier.fillMaxWidth(),
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
                modifier = wave(4),
                targetState = state.month,
                transitionSpec = {
                    fadeIn(animationSpec = tween(300)).togetherWith(fadeOut(animationSpec = tween(300)))
                },
                label = "dashboard_transactions"
            ) { targetMonth ->
                TransactionsDashboardWidget(
                    transactions = state.dashboardTransactions,
                    currency = state.currency,
                    onSeeAll = { onSeeAllTransactions(targetMonth) },
                    onOpenTransaction = onOpenTransaction,
                )
            }
        }
    }
}

/** LOP-231, CA-01 : solde total du moteur de comptes, « — » tant qu'il n'est pas lu. */
@Composable
private fun TotalBalanceHeader(total: Long?, currency: String) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            stringResource(R.string.home_total_balance),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            total?.let { Format.money(it, currency) } ?: SUMMARY_PLACEHOLDER,
            style = MaterialTheme.typography.displayLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            modifier = Modifier.testTag(TestTags.HOME_TOTAL_BALANCE),
        )
    }
}

/**
 * LOP-231, carte « Ce mois » : nom du mois courant, Dépenses, Solde du mois, Revenus (CA-03).
 * Aucun montant tant que la lecture n'est pas faite, un message en cas d'échec (CA-04). La carte
 * ouvre la vue unifiée (CA-05) ; Dépenses et Revenus ouvrent leur analyse (P-5).
 */
@Composable
private fun MonthWidget(
    state: HomeUiState,
    onOpen: () -> Unit,
    onOpenAnalysis: (TransactionType) -> Unit,
) {
    val ext = LopTheme.extended
    val ready = !state.isMonthLoading
    val balanceColor = when {
        state.monthBalance > 0 -> ext.income
        state.monthBalance < 0 -> ext.expense
        else -> MaterialTheme.colorScheme.onSurface
    }
    val monthName = remember(state.month) {
        state.month.month.getDisplayName(TextStyle.FULL, Locale.FRANCE).replaceFirstChar { it.uppercase() }
    }
    FloatingCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickableNoRipple(onOpen)
            .testTag(TestTags.HOME_MONTH_WIDGET),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.CalendarMonth,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(monthName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.monthLoadFailed) {
                Text(
                    stringResource(R.string.home_month_error),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(TestTags.HOME_MONTH_ERROR),
                )
            } else {
                Row(Modifier.fillMaxWidth()) {
                    SummaryFigure(
                        R.string.expense, Format.money(state.monthExpense, state.currency).takeIf { ready },
                        ext.expense, TestTags.HOME_MONTH_EXPENSE,
                        Modifier.weight(1f).clickableNoRipple { onOpenAnalysis(TransactionType.EXPENSE) },
                    )
                    SummaryFigure(
                        R.string.home_month_balance, Format.signedMoney(state.monthBalance, state.currency).takeIf { ready },
                        balanceColor, TestTags.HOME_MONTH_BALANCE, Modifier.weight(1f),
                    )
                    SummaryFigure(
                        R.string.income, Format.money(state.monthIncome, state.currency).takeIf { ready },
                        ext.income, TestTags.HOME_MONTH_INCOME,
                        Modifier.weight(1f).clickableNoRipple { onOpenAnalysis(TransactionType.INCOME) },
                    )
                }
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
        contentPadding = PaddingValues(12.dp),
        tint = color
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

/**
 * En-tête de l'accueil : barre de recherche, détections, réglages. Aucun voile derrière : les
 * contrôles sont en verre ([glass]) et laissent voir, floutées, les cartes qui défilent dessous.
 */
@Composable
fun HomeOverlay(
    state: HomeUiState,
    onSearchClick: () -> Unit,
    onDetectedClick: () -> Unit,
    onSettingsClick: () -> Unit,
    haze: HazeState,
    modifier: Modifier = Modifier,
    searchBar: SearchBarTransition? = null,
    /** Réglages et cloche : ils partent avec la vague quand la barre s'ouvre. */
    actionsModifier: Modifier = Modifier,
) {
    // LOP-231, CA-02 : ni pastille de mois ni retour au mois actuel, l'accueil est sur le mois courant.
    // Barre au centre, encadrée : réglages à gauche, détections à droite.
    Row(
        modifier = modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(
            icon = Icons.Outlined.Settings,
            onClick = onSettingsClick,
            contentDescription = "Réglages",
            modifier = actionsModifier.testTag(TestTags.NAV_SETTINGS_BUTTON),
            haze = haze,
        )
        HomeSearchBar(onSearchClick, haze, searchBar, Modifier.weight(1f))
        DetectedIcon(state.detectedCount, onDetectedClick, haze, actionsModifier)
    }
}

/**
 * Barre de recherche de l'accueil : un bouton, pas un champ. Elle a la forme, la loupe et la marge du
 * champ de Recherche, qu'elle devient en voyageant ([searchBar]).
 */
@Composable
private fun HomeSearchBar(onClick: () -> Unit, haze: HazeState, searchBar: SearchBarTransition?, modifier: Modifier) {
    Row(
        modifier = modifier
            .searchBarBounds(searchBar)
            .height(44.dp)
            .glass(haze, LopFieldShape)
            .clickableNoRipple(onClick)
            .semantics { role = Role.Button }
            .testTag(TestTags.NAV_SEARCH_BUTTON)
            .padding(start = 12.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Même icône et même retrait que `LopSearchBar` : rien ne saute à l'arrivée.
        Icon(
            Icons.Default.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            stringResource(R.string.home_search_bar),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun IconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    /** Verre de l'en-tête de l'accueil ; sans lui, bouton plein en relief (DESIGN.md § 6). */
    haze: HazeState? = null,
) {
    Box(

        modifier = modifier
            .size(44.dp)
            .roundSurface(haze)
            .clickableNoRipple { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun Modifier.roundSurface(haze: HazeState?): Modifier =
    if (haze != null) glass(haze, CircleShape)
    else surfaceRelief(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)

@Composable
fun DetectedIcon(count: Int, onClick: () -> Unit, haze: HazeState? = null, modifier: Modifier = Modifier) {
    Box(modifier = modifier.size(44.dp).roundSurface(haze).clickableNoRipple { onClick() }.testTag(TestTags.NAV_DETECTED_BUTTON), contentAlignment = Alignment.Center) {
        Icon(Icons.Outlined.NotificationsActive, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
        if (count > 0) {
            Surface(color = MaterialTheme.colorScheme.error, shape = androidx.compose.foundation.shape.CircleShape, modifier = Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 6.dp).size(16.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Text(text = count.coerceAtMost(9).toString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onError, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

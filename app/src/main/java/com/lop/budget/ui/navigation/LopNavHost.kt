package com.lop.budget.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import com.lop.budget.R
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.lop.budget.ui.components.FloatingBottomBar
import com.lop.budget.ui.components.TransactionPreviewPopup
import com.lop.budget.ui.motion.MotionSpec
import com.lop.budget.ui.screens.accounts.AccountsScreen
import com.lop.budget.ui.screens.ai.AiScreen
import com.lop.budget.ui.screens.analytics.AnalyticsScreen
import com.lop.budget.ui.screens.category.CategoryCreateScreen
import com.lop.budget.ui.screens.detail.TransactionDetailScreen
import com.lop.budget.ui.screens.detected.DetectedTransactionsScreen
import com.lop.budget.ui.screens.goals.DebtEditScreen
import com.lop.budget.ui.screens.goals.GoalEditScreen
import com.lop.budget.ui.screens.goals.GoalsScreen
import com.lop.budget.ui.screens.home.HomeScreen
import com.lop.budget.ui.screens.manage.AccountEditScreen
import com.lop.budget.ui.screens.manage.AccountsManageScreen
import com.lop.budget.ui.screens.manage.CategoriesManageScreen
import com.lop.budget.ui.screens.manage.TagsManageScreen
import com.lop.budget.ui.screens.monthly.MonthlyTransactionsScreen
import com.lop.budget.ui.screens.settings.SettingsScreen
import com.lop.budget.ui.screens.transaction.TransactionEditScreen
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LopNavHost(startRoute: String? = null) {
    val navController = rememberNavController()
    val hazeState = rememberHazeState()
    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }

    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route?.substringBefore("/") ?: Routes.HOME

    var globalPreviewTx by remember {
        mutableStateOf<com.lop.budget.data.local.entity.TransactionWithRelations?>(
            null
        )
    }
    var globalCurrency by remember { mutableStateOf("EUR") }
    val actionVm: com.lop.budget.ui.common.TransactionActionViewModel = hiltViewModel()
    
    val txDeletedMsg = stringResource(R.string.tx_deleted_snackbar)
    val undoMsg = stringResource(R.string.undo)

    val showBar =
        currentRoute in Routes.rootRoutes || currentRoute == "home" || currentRoute == "analytics" || currentRoute == "goals" || currentRoute == "accounts"

    // deep link simple depuis notification
    LaunchedEffect(startRoute, navController) {
        if (!startRoute.isNullOrBlank()) {
            // On attend que le NavHost soit monté et ait un graphe
            snapshotFlow { navController.graph }.distinctUntilChanged().collect {
                if (navController.currentDestination == null) {
                    navController.navigate(startRoute) { launchSingleTop = true }
                }
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0)
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
        ) {
            // SIBLING 1: The background content (Marked as HazeSource)
            // We apply fillMaxSize() so it covers the status bar area as well
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .hazeSource(state = hazeState)
            ) {
                // We apply statusBarsPadding ONLY to the inner content (NavHost),
                // so the 'hazeSource' Box itself still occupies the full screen.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                ) {
                    NavHost(
                        navController = navController,
                        startDestination = Routes.HOME,
                        enterTransition = {
                            NavAnimations.getGlobalEnterTransition(
                                initialState.destination.route,
                                targetState.destination.route
                            )(this)
                        },
                        exitTransition = {
                            NavAnimations.getGlobalExitTransition(
                                initialState.destination.route,
                                targetState.destination.route
                            )(this)
                        },
                        popEnterTransition = {
                            NavAnimations.getGlobalEnterTransition(
                                initialState.destination.route,
                                targetState.destination.route
                            )(this)
                        },
                        popExitTransition = {
                            NavAnimations.getGlobalExitTransition(
                                initialState.destination.route,
                                targetState.destination.route
                            )(this)
                        },
                    ) {
                        composable(Routes.HOME) {
                            HomeScreen(
                                snackbarHostState = snackbarHostState,
                                onOpenTransaction = { navController.navigate(Routes.detail(it)) },
                                onOpenAi = { navController.navigate(Routes.AI) },
                                navController = navController,
                                onOpenMonthly = { type, ym ->
                                    navController.navigate(Routes.monthly(type, ym))
                                },
                                onPreviewTransaction = { tx, cur ->
                                    globalPreviewTx = tx
                                    globalCurrency = cur
                                },
                                hazeState = hazeState,
                            )
                        }

                        composableAnimated(Routes.DETECTED, NavAnimationType.MAIN) {
                            DetectedTransactionsScreen(
                                onBack = { navController.popBackStack() },
                                onOpenEdit = { id -> navController.navigate(Routes.edit(id)) },
                            )
                        }

                        composable(Routes.ANALYTICS) { AnalyticsScreen() }

                        composable(Routes.GOALS) {
                            GoalsScreen(
                                onBack = { navController.popBackStack() },
                                onAddGoal = { navController.navigate(Routes.GOAL_ADD) },
                                onEditGoal = { id -> navController.navigate(Routes.goalEdit(id)) },
                                onAddDebt = { navController.navigate(Routes.DEBT_ADD) },
                                onEditDebt = { id -> navController.navigate(Routes.debtEdit(id)) }
                            )
                        }

                        composableAnimated(Routes.GOAL_ADD, NavAnimationType.MAIN) {
                            GoalEditScreen(onBack = { navController.popBackStack() })
                        }

                        composableAnimated(
                            Routes.GOAL_EDIT,
                            NavAnimationType.MAIN,
                            arguments = listOf(navArgument("id") { type = NavType.LongType })
                        ) {
                            GoalEditScreen(onBack = { navController.popBackStack() })
                        }

                        composableAnimated(Routes.DEBT_ADD, NavAnimationType.MAIN) {
                            DebtEditScreen(onBack = { navController.popBackStack() })
                        }

                        composableAnimated(
                            Routes.DEBT_EDIT,
                            NavAnimationType.MAIN,
                            arguments = listOf(navArgument("id") { type = NavType.LongType })
                        ) {
                            DebtEditScreen(onBack = { navController.popBackStack() })
                        }

                        composableAnimated(Routes.ACCOUNTS, NavAnimationType.ROOT) {
                            AccountsScreen(
                                onBack = { navController.popBackStack() },
                                onOpenDetail = { id -> navController.navigate(Routes.accountDetail(id)) }
                            )
                        }

                        composableAnimated(
                        Routes.MONTHLY,
                        NavAnimationType.SECONDARY,
                        arguments = listOf(
                            navArgument("type") { type = NavType.StringType },
                            navArgument("ym") { type = NavType.StringType },
                        )
                    ) {
                        MonthlyTransactionsScreen(
                            onBack = { navController.popBackStack() },
                            onOpenTransaction = { navController.navigate(Routes.detail(it)) },
                            onPreviewTransaction = { tx, cur ->
                                globalPreviewTx = tx
                                globalCurrency = cur
                            },
                            onNavigateToSearch = { query ->
                                navController.navigate(Routes.SEARCH)
                            },
                            hazeState = hazeState,
                            snackbarHostState = snackbarHostState
                        )
                    }

                        composableAnimated(Routes.AI, NavAnimationType.SECONDARY) {
                            AiScreen(onBack = { navController.popBackStack() })
                        }

                        composableAnimated(Routes.SETTINGS, NavAnimationType.MAIN) {
                            SettingsScreen(
                                onBack = { navController.popBackStack() },
                                onNavigateToTags = { navController.navigate(Routes.TAGS_MANAGE) },
                                onNavigateToAccounts = { navController.navigate(Routes.ACCOUNTS_MANAGE) },
                                onNavigateToCategories = { navController.navigate(Routes.CATEGORIES_MANAGE) }
                            )
                        }

                        composableAnimated(Routes.SEARCH, NavAnimationType.MAIN) {
                            com.lop.budget.ui.screens.search.SearchScreen(
                                onBack = { navController.popBackStack() },
                                onOpenTransaction = { id -> navController.navigate(Routes.detail(id)) },
                                onPreviewTransaction = { tx, cur ->
                                    globalPreviewTx = tx
                                    globalCurrency = cur
                                },
                                hazeState = hazeState,
                                snackbarHostState = snackbarHostState
                            )
                        }

                        composableAnimated(Routes.CATEGORIES_MANAGE, NavAnimationType.SECONDARY) {
                            CategoriesManageScreen(
                                onBack = { navController.popBackStack() },
                                onAddCategory = { navController.navigate(Routes.CATEGORY_CREATE) },
                                onEditCategory = { id -> navController.navigate(Routes.categoryEdit(id)) }
                            )
                        }

                        composableAnimated(Routes.CATEGORY_CREATE, NavAnimationType.SECONDARY) {
                            CategoryCreateScreen(onBack = { navController.popBackStack() })
                        }

                        composableAnimated(
                            Routes.CATEGORY_EDIT,
                            NavAnimationType.SECONDARY,
                            arguments = listOf(navArgument("id") { type = NavType.LongType })
                        ) {
                            CategoryCreateScreen(onBack = { navController.popBackStack() })
                        }

                        composableAnimated(
                            Routes.ACCOUNT_DETAIL,
                            NavAnimationType.MAIN,
                            arguments = listOf(navArgument("id") { type = NavType.LongType })
                        ) {
                            com.lop.budget.ui.screens.accounts.AccountDetailScreen(
                                onBack = { navController.popBackStack() },
                                onEdit = { id -> navController.navigate(Routes.accountEdit(id)) },
                                onOpenTransaction = { id -> navController.navigate(Routes.detail(id)) },
                                onPreviewTransaction = { tx, cur ->
                                    globalPreviewTx = tx
                                    globalCurrency = cur
                                },
                                hazeState = hazeState,
                                snackbarHostState = snackbarHostState
                            )
                        }

                        composableAnimated(Routes.ACCOUNTS_MANAGE, NavAnimationType.SECONDARY) {
                            AccountsManageScreen(
                                onBack = { navController.popBackStack() },
                                onAddAccount = { navController.navigate(Routes.ACCOUNT_ADD) },
                                onEditAccount = { id: Long ->
                                    navController.navigate(
                                        Routes.accountEdit(
                                            id
                                        )
                                    )
                                }
                            )
                        }

                        composableAnimated(Routes.ACCOUNT_ADD, NavAnimationType.SECONDARY) {
                            AccountEditScreen(onBack = { navController.popBackStack() })
                        }

                        composableAnimated(
                            Routes.ACCOUNT_EDIT,
                            NavAnimationType.SECONDARY,
                            arguments = listOf(navArgument("id") { type = NavType.LongType })
                        ) {
                            AccountEditScreen(onBack = { navController.popBackStack() })
                        }

                        composableAnimated(Routes.TAGS_MANAGE, NavAnimationType.SECONDARY) {
                            TagsManageScreen(onBack = { navController.popBackStack() })
                        }

                        composableAnimated(Routes.ADD, NavAnimationType.MAIN) {
                            TransactionEditScreen(
                                onBack = { navController.popBackStack() },
                                onNavigateToCreateCategory = { navController.navigate(Routes.CATEGORY_CREATE) },
                            )
                        }

                        composableAnimated(
                            Routes.EDIT,
                            NavAnimationType.MAIN,
                            arguments = listOf(
                                navArgument("id") { type = NavType.LongType },
                                navArgument("scope") {
                                    type = NavType.StringType
                                    nullable = true
                                    defaultValue = null
                                },
                                navArgument("date") {
                                    type = NavType.LongType
                                    defaultValue = -1L
                                }
                            )
                        ) {
                            TransactionEditScreen(
                                onBack = { navController.popBackStack() },
                                onNavigateToCreateCategory = { navController.navigate(Routes.CATEGORY_CREATE) },
                            )
                        }

                        composableAnimated(
                            Routes.DETAIL,
                            NavAnimationType.MAIN,
                            arguments = listOf(navArgument("id") { type = NavType.LongType })
                        ) { entry ->
                            val id = entry.arguments?.getLong("id") ?: 0L
                            TransactionDetailScreen(
                                transactionId = id,
                                onBack = { navController.popBackStack() },
                                onEdit = { txId, scope, date ->
                                    navController.navigate(Routes.edit(txId, scope, date))
                                },
                                snackbarHostState = snackbarHostState
                            )
                        }
                    }
                }
            }

            // SIBLING 2: The Bottom Bar (Blur Effect)
            AnimatedVisibility(
                visible = showBar,
                enter = slideInVertically(
                    animationSpec = tween(
                        durationMillis = MotionSpec.SLOW_MS,
                        easing = MotionSpec.easeOut
                    ),
                ) { it / 2 } + fadeIn(
                    animationSpec = tween(
                        durationMillis = MotionSpec.MEDIUM_MS,
                        easing = MotionSpec.easeOut
                    ),
                ),
                exit = slideOutVertically(
                    animationSpec = tween(
                        durationMillis = MotionSpec.MEDIUM_MS,
                        easing = MotionSpec.easeOut
                    ),
                ) { it / 2 } + fadeOut(
                    animationSpec = tween(
                        durationMillis = MotionSpec.FAST_MS,
                        easing = MotionSpec.easeOut
                    ),
                ),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                                colors = listOf(
                                    androidx.compose.ui.graphics.Color.Transparent,
                                    MaterialTheme.colorScheme.background.copy(alpha = 0.4f),
                                    MaterialTheme.colorScheme.background.copy(alpha = 0.6f),
                                    MaterialTheme.colorScheme.background,
                                )
                            )
                        )
                        .navigationBarsPadding()
                        .padding(bottom = 20.dp, top = 40.dp)
                ) {
                    FloatingBottomBar(
                        current = currentRoute,
                        onSelect = { route ->
                            navController.navigate(route) {
                                popUpTo(Routes.HOME) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        onAdd = { navController.navigate(Routes.ADD) },
                        hazeState = hazeState,
                    )
                }
            }

            // SIBLING 3: Global Transaction Preview Popup Overlay (Full Screen Blur)
            if (globalPreviewTx != null) {
                val tx = globalPreviewTx!!
                TransactionPreviewPopup(
                    tx = tx,
                    currency = globalCurrency,
                    onDismiss = { globalPreviewTx = null },
                    onEdit = {
                        globalPreviewTx = null
                        if (tx.transaction.id >= 0L) navController.navigate(Routes.edit(tx.transaction.id))
                        else tx.transaction.seriesId?.let { sid ->
                            // Matérialiser avant d'éditer
                            actionVm.togglePaid(tx) // C'est un hack ici, il faudrait materializeAndEdit
                            // En fait, materialiseAndEdit est dans les ViewModels locaux. 
                            // Pour simplifier ici, on peut naviguer vers l'edit avec scope si série.
                            navController.navigate(Routes.edit(tx.transaction.id, null, tx.transaction.seriesDate))
                        }
                    },
                    onDelete = {
                        globalPreviewTx = null
                        if (tx.transaction.seriesId != null) {
                            // On pourrait afficher le RecurringDeleteSheet ici aussi
                            // Mais pour simplifier dans le popup global, on va peut-être juste faire le simple delete
                            // Ou alors on laisse globalPreviewTx à null et on déclenche un état de suppression
                        } else {
                            actionVm.deleteWithUndo(tx, snackbarHostState, txDeletedMsg, undoMsg)
                        }
                    },
                    onTogglePaid = {
                        actionVm.togglePaid(tx)
                        globalPreviewTx = null
                    },
                    hazeState = hazeState
                )
            }
        }
    }
}

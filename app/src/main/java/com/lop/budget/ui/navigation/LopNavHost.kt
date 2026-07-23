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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LopNavHost(startRoute: String? = null) {
    val navController = rememberNavController()
    val hazeState = rememberHazeState()
    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }

    // deep link simple depuis notification
    androidx.compose.runtime.LaunchedEffect(startRoute) {
        if (!startRoute.isNullOrBlank()) {
            navController.navigate(startRoute) { launchSingleTop = true }
        }
    }

    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route?.substringBefore("/") ?: Routes.HOME

    var globalPreviewTx by remember {
        mutableStateOf<com.lop.budget.data.local.entity.TransactionWithRelations?>(
            null
        )
    }
    var globalCurrency by remember { mutableStateOf("EUR") }

    val showBar =
        currentRoute in Routes.rootRoutes || currentRoute == "home" || currentRoute == "analytics" || currentRoute == "goals" || currentRoute == "accounts"

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0)
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .hazeSource(state = hazeState)
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
                            hazeState = hazeState,
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
                            }
                        )
                    }
                }
            }

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

            // Global Transaction Preview Popup Overlay
            if (globalPreviewTx != null) {
                val tx = globalPreviewTx!!
                TransactionPreviewPopup(
                    tx = tx,
                    currency = globalCurrency,
                    onDismiss = { globalPreviewTx = null },
                    onEdit = {
                        globalPreviewTx = null
                        if (tx.transaction.id >= 0L) navController.navigate(Routes.edit(tx.transaction.id))
                        // Manual handle series if needed
                    },
                    onDelete = {
                        globalPreviewTx = null
                        // Actions are handled in original screens for simplicity
                    },
                    onTogglePaid = {
                        globalPreviewTx = null
                        // Actions are handled in original screens for simplicity
                    },
                    hazeState = hazeState
                )
            }
        }
    }
}

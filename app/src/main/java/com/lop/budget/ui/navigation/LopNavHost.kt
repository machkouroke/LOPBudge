package com.lop.budget.ui.navigation

import androidx.activity.ComponentActivity
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
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.lop.budget.ui.components.DeleteConfirmationDialog
import com.lop.budget.ui.components.FloatingBottomBar
import com.lop.budget.ui.components.RecurringDeleteSheet
import com.lop.budget.ui.components.RecurringEditSheet
import com.lop.budget.ui.components.TransactionPreviewPopup
import com.lop.budget.ui.motion.MotionSpec
import com.lop.budget.ui.screens.accounts.AccountDetailScreen
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
import com.lop.budget.ui.screens.search.SearchScreen
import com.lop.budget.ui.screens.settings.SettingsScreen
import com.lop.budget.ui.screens.transaction.TransactionEditScreen
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.flow.distinctUntilChanged

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LopNavHost(startRoute: String? = null) {
    val navController = rememberNavController()
    val hazeState = rememberHazeState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val actionVm: com.lop.budget.ui.common.TransactionActionViewModel =
        hiltViewModel(context as ComponentActivity)

    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route?.substringBefore("/") ?: Routes.HOME

    val globalPreviewTx by actionVm.previewTx.collectAsStateWithLifecycle()
    val globalCurrency by actionVm.previewCurrency.collectAsStateWithLifecycle()
    val deleteRequest by actionVm.deleteRequest.collectAsStateWithLifecycle()
    val pendingConfirmation by actionVm.pendingConfirmation.collectAsStateWithLifecycle()
    val editRequest by actionVm.editRequest.collectAsStateWithLifecycle()


    val showBar =
        (currentRoute in Routes.rootRoutes || currentRoute == "home" || currentRoute == "analytics" || currentRoute == "goals" || currentRoute == "accounts")

    // deep link simple depuis notification
    LaunchedEffect(startRoute, navController) {
        if (!startRoute.isNullOrBlank()) {
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
                .padding(padding)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .hazeSource(state = hazeState)
            ) {
                NavHost(
                    navController = navController,
                    startDestination = startRoute ?: Routes.HOME,
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding(),
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
                            onOpenTransaction = { id -> navController.navigate(Routes.detail(id)) },
                            navController = navController,

                            hazeState = hazeState,
                        )
                    }

                    composableAnimated(Routes.DETECTED, NavAnimationType.MAIN) {
                        DetectedTransactionsScreen(
                            onBack = { navController.popBackStack() },
                            onOpenEdit = { id -> navController.navigate(Routes.edit(id)) },
                        )
                    }

                    composableAnimated(
                        Routes.ANALYTICS,
                        NavAnimationType.ROOT,
                        arguments = listOf(
                            navArgument("type") {
                                type = NavType.StringType
                                nullable = true
                                defaultValue = null
                            },
                            navArgument("ym") {
                                type = NavType.StringType
                                nullable = true
                                defaultValue = null
                            }
                        )
                    ) {
                        AnalyticsScreen(onBack = { navController.popBackStack() })
                    }

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
                            navArgument("mode") { 
                                type = NavType.StringType 
                                defaultValue = "HISTORY"
                            },
                        )
                    ) {
                        MonthlyTransactionsScreen(
                            onBack = { navController.popBackStack() },
                            onOpenTransaction = { id -> navController.navigate(Routes.detail(id)) },
                            onNavigateToSearch = { query ->
                                navController.navigate(Routes.SEARCH + "?q=$query")
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
                        SearchScreen(
                            onBack = { navController.popBackStack() },
                            onOpenTransaction = { id -> navController.navigate(Routes.detail(id)) },
                            hazeState = hazeState,
                            snackbarHostState = snackbarHostState
                        )
                    }

                    composableAnimated(Routes.CATEGORIES_MANAGE, NavAnimationType.SECONDARY) {
                        CategoriesManageScreen(
                            onBack = { navController.popBackStack() },
                            onAddCategory = { navController.navigate(Routes.CATEGORY_CREATE) },
                            onEditCategory = { id: Long ->
                                navController.navigate(
                                    Routes.categoryEdit(
                                        id
                                    )
                                )
                            }
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
                        AccountDetailScreen(
                            onBack = { navController.popBackStack() },
                            onEdit = { id -> navController.navigate(Routes.accountEdit(id)) },
                            onOpenTransaction = { id -> navController.navigate(Routes.detail(id)) },
                            hazeState = hazeState,
                            snackbarHostState = snackbarHostState
                        )
                    }

                    composableAnimated(Routes.ACCOUNTS_MANAGE, NavAnimationType.SECONDARY) {
                        AccountsManageScreen(
                            onBack = { navController.popBackStack() },
                            onAddAccount = { navController.navigate(Routes.ACCOUNT_ADD) },
                            onEditAccount = { id ->
                                navController.navigate(Routes.accountEdit(id))
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
                            onDone = { newId ->
                                if (newId > 0) {
                                    navController.navigate(Routes.detail(newId)) {
                                        popUpTo(Routes.HOME)
                                        launchSingleTop = true
                                    }
                                } else {
                                    navController.popBackStack()
                                }
                            },
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
                    ) { entry ->
                        val editingId = entry.arguments?.getLong("id") ?: 0L
                        TransactionEditScreen(
                            onDone = { newId ->
                                if (newId != editingId && newId > 0) {
                                    // Troncature FUTURE ou ALL (si rematérialisé) : on remplace le détail
                                    navController.navigate(Routes.detail(newId)) {
                                        popUpTo(Routes.detail(editingId)) { inclusive = true }
                                        launchSingleTop = true
                                    }
                                } else {
                                    navController.popBackStack()
                                }
                            },
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
                            snackbarHostState = snackbarHostState
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

            // SIBLING 3: Global Transaction Preview Popup Overlay
            globalPreviewTx?.let { tx ->
                TransactionPreviewPopup(
                    tx = tx,
                    currency = globalCurrency,
                    onDismiss = { actionVm.dismissPreview() },
                    onEdit = {
                        actionVm.dismissPreview()
                        actionVm.requestEdit(tx)
                    },
                    onDelete = {
                        actionVm.dismissPreview()
                        actionVm.requestDelete(tx)
                    },
                    onTogglePaid = {
                        actionVm.togglePaid(tx)
                        actionVm.dismissPreview()
                    },
                    hazeState = hazeState
                )
            }

            // SIBLING 4: Global Delete Request Handler
            deleteRequest?.let { toDelete ->
                if (toDelete.transaction.seriesId != null) {
                    RecurringDeleteSheet(
                        onDismiss = { actionVm.dismissDeleteRequest() },
                        showFutureOnly = true,
                        onChoose = { choice ->
                            actionVm.dismissDeleteRequest()
                            actionVm.requestConfirmation(toDelete, choice)
                        }
                    )
                } else {
                    SideEffect {
                        actionVm.dismissDeleteRequest()
                        actionVm.requestConfirmation(toDelete, null)
                    }
                }
            }

            // SIBLING 4b: Delete Confirmation Dialog
            pendingConfirmation?.let { request ->
                DeleteConfirmationDialog(
                    choice = request.choice,
                    onDismiss = { actionVm.dismissConfirmation() },
                    onConfirm = { actionVm.confirmDelete() }
                )
            }
            // SIBLING 5: Global Edit Request Handler
            editRequest?.let { tx ->
                val transaction = tx.transaction
                if (transaction.seriesId != null) {
                   RecurringEditSheet(
                        onDismiss = { actionVm.dismissEditRequest() },
                        onChoose = { scope ->
                            actionVm.dismissEditRequest()
                            when (scope) {
                                com.lop.budget.domain.model.EditScope.SINGLE -> {
                                    navController.navigate(Routes.edit(transaction.id))
                                }
                                com.lop.budget.domain.model.EditScope.FUTURE -> {
                                    navController.navigate(Routes.edit(transaction.id, "FUTURE", transaction.date))
                                }
                                com.lop.budget.domain.model.EditScope.ALL -> {
                                    navController.navigate(Routes.edit(transaction.id, "ALL", null))
                                }
                            }
                        }
                    )
                } else {
                    SideEffect {
                        navController.navigate(Routes.edit(transaction.id))
                        actionVm.dismissEditRequest()
                    }
                }
            }
        }
    }
}

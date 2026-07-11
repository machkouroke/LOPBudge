package com.lop.budget.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.lop.budget.ui.components.FloatingBottomBar
import com.lop.budget.ui.motion.MotionSpec
import com.lop.budget.ui.screens.accounts.AccountsScreen
import com.lop.budget.ui.screens.ai.AiScreen
import com.lop.budget.ui.screens.analytics.AnalyticsScreen
import com.lop.budget.ui.screens.category.CategoryCreateScreen
import com.lop.budget.ui.screens.detail.TransactionDetailScreen
import com.lop.budget.ui.screens.detected.DetectedTransactionsScreen
import com.lop.budget.ui.screens.goals.GoalsScreen
import com.lop.budget.ui.screens.home.HomeScreen
import com.lop.budget.ui.screens.monthly.MonthlyTransactionsScreen
import com.lop.budget.ui.screens.settings.SettingsScreen
import com.lop.budget.ui.screens.transaction.TransactionEditScreen
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

private val screenOrder = listOf(Routes.HOME, Routes.ANALYTICS, Routes.GOALS, Routes.ACCOUNTS)

@OptIn(ExperimentalAnimationApi::class)
private fun createEnterTransition(
    initialState: NavBackStackEntry,
    targetState: NavBackStackEntry
): EnterTransition {
    val initialIndex = screenOrder.indexOf(initialState.destination.route)
    val targetIndex = screenOrder.indexOf(targetState.destination.route)
    return if (initialIndex == -1 || targetIndex == -1) fadeIn()
    else if (initialIndex > targetIndex) slideInHorizontally(initialOffsetX = { -it })
    else slideInHorizontally(initialOffsetX = { it })
}

@OptIn(ExperimentalAnimationApi::class)
private fun createExitTransition(
    initialState: NavBackStackEntry,
    targetState: NavBackStackEntry
): ExitTransition {
    val initialIndex = screenOrder.indexOf(initialState.destination.route)
    val targetIndex = screenOrder.indexOf(targetState.destination.route)
    return if (initialIndex == -1 || targetIndex == -1) fadeOut()
    else if (initialIndex > targetIndex) slideOutHorizontally(targetOffsetX = { it })
    else slideOutHorizontally(targetOffsetX = { -it })
}

private typealias EnterTransition = androidx.compose.animation.EnterTransition
private typealias ExitTransition = androidx.compose.animation.ExitTransition

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
    val currentRoute = backStack?.destination?.route
    val showBar = currentRoute in Routes.rootRoutes

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .hazeSource(state = hazeState),
        ) {
            NavHost(
                navController = navController,
                startDestination = Routes.HOME,
                enterTransition = { createEnterTransition(initialState, targetState) },
                exitTransition = { createExitTransition(initialState, targetState) },
                popEnterTransition = { createEnterTransition(initialState, targetState) },
                popExitTransition = { createExitTransition(initialState, targetState) },
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
                    )
                }

                composable(
                    Routes.DETECTED,
                    enterTransition = { slideInHorizontally(initialOffsetX = { it }) },
                    exitTransition = { slideOutHorizontally(targetOffsetX = { -it }) },
                    popEnterTransition = { slideInHorizontally(initialOffsetX = { -it }) },
                    popExitTransition = { slideOutHorizontally(targetOffsetX = { it }) },
                ) {
                    DetectedTransactionsScreen(
                        onBack = { navController.popBackStack() },
                        onOpenEdit = { id -> navController.navigate(Routes.edit(id)) },
                    )
                }

                composable(Routes.ANALYTICS) { AnalyticsScreen() }
                composable(Routes.GOALS) { GoalsScreen() }
                composable(Routes.ACCOUNTS) { AccountsScreen() }

                composable(
                    Routes.MONTHLY,
                    arguments = listOf(
                        navArgument("type") { type = NavType.StringType },
                        navArgument("ym") { type = NavType.StringType },
                    ),
                    enterTransition = {
                        slideIntoContainer(
                            AnimatedContentTransitionScope.SlideDirection.Left,
                            animationSpec = MotionSpec.mediumTween()
                        ) + fadeIn(animationSpec = MotionSpec.mediumTween())
                    },
                    exitTransition = {
                        slideOutOfContainer(
                            AnimatedContentTransitionScope.SlideDirection.Right,
                            animationSpec = MotionSpec.fastTween()
                        ) + fadeOut(animationSpec = MotionSpec.fastTween())
                    },
                ) {
                    MonthlyTransactionsScreen(
                        onBack = { navController.popBackStack() },
                        onOpenTransaction = { navController.navigate(Routes.detail(it)) },
                    )
                }

                composable(Routes.AI) { AiScreen(onBack = { navController.popBackStack() }) }

                composable(
                    Routes.SETTINGS,
                    enterTransition = { slideInHorizontally(initialOffsetX = { it }) },
                    exitTransition = { slideOutHorizontally(targetOffsetX = { -it }) },
                    popEnterTransition = { slideInHorizontally(initialOffsetX = { -it }) },
                    popExitTransition = { slideOutHorizontally(targetOffsetX = { it }) },
                ) {
                    SettingsScreen(onBack = { navController.popBackStack() })
                }

                composable(Routes.CATEGORY_CREATE) {
                    CategoryCreateScreen(onBack = { navController.popBackStack() })
                }

                // NEW: Add is a full screen (not a modal bottom sheet)
                composable(
                    Routes.ADD,
                    enterTransition = {
                        slideIntoContainer(
                            AnimatedContentTransitionScope.SlideDirection.Up,
                            animationSpec = MotionSpec.mediumTween(),
                        ) + fadeIn(animationSpec = MotionSpec.mediumTween())
                    },
                    exitTransition = {
                        slideOutOfContainer(
                            AnimatedContentTransitionScope.SlideDirection.Down,
                            animationSpec = MotionSpec.fastTween(),
                        ) + fadeOut(animationSpec = MotionSpec.fastTween())
                    },
                    popEnterTransition = {
                        slideIntoContainer(
                            AnimatedContentTransitionScope.SlideDirection.Up,
                            animationSpec = MotionSpec.mediumTween(),
                        ) + fadeIn(animationSpec = MotionSpec.mediumTween())
                    },
                    popExitTransition = {
                        slideOutOfContainer(
                            AnimatedContentTransitionScope.SlideDirection.Down,
                            animationSpec = MotionSpec.fastTween(),
                        ) + fadeOut(animationSpec = MotionSpec.fastTween())
                    },
                ) {
                    TransactionEditScreen(
                        onBack = { navController.popBackStack() },
                        onNavigateToCreateCategory = { navController.navigate(Routes.CATEGORY_CREATE) },
                    )
                }

                composable(
                    Routes.EDIT,
                    arguments = listOf(navArgument("id") { type = NavType.LongType }),
                    enterTransition = {
                        slideIntoContainer(
                            AnimatedContentTransitionScope.SlideDirection.Up,
                            animationSpec = MotionSpec.mediumTween()
                        ) + fadeIn(animationSpec = MotionSpec.mediumTween())
                    },
                    exitTransition = {
                        slideOutOfContainer(
                            AnimatedContentTransitionScope.SlideDirection.Down,
                            animationSpec = MotionSpec.fastTween()
                        ) + fadeOut(animationSpec = MotionSpec.fastTween())
                    }
                ) {
                    TransactionEditScreen(
                        onBack = { navController.popBackStack() },
                        onNavigateToCreateCategory = { navController.navigate(Routes.CATEGORY_CREATE) },
                    )
                }

                composable(
                    Routes.DETAIL,
                    arguments = listOf(navArgument("id") { type = NavType.LongType }),
                ) { entry ->
                    val id = entry.arguments?.getLong("id") ?: 0L
                    TransactionDetailScreen(
                        transactionId = id,
                        onBack = { navController.popBackStack() },
                        onEdit = { txId -> navController.navigate(Routes.edit(txId)) })
                }
            }

            AnimatedVisibility(
                visible = showBar,
                enter = slideInVertically(
                    animationSpec = androidx.compose.animation.core.tween(
                        durationMillis = MotionSpec.SLOW_MS,
                        easing = MotionSpec.easeOut
                    ),
                ) { it / 2 } + fadeIn(
                    animationSpec = androidx.compose.animation.core.tween(
                        durationMillis = MotionSpec.MEDIUM_MS,
                        easing = MotionSpec.easeOut
                    ),
                ),
                exit = slideOutVertically(
                    animationSpec = androidx.compose.animation.core.tween(
                        durationMillis = MotionSpec.MEDIUM_MS,
                        easing = MotionSpec.easeOut
                    ),
                ) { it / 2 } + fadeOut(
                    animationSpec = androidx.compose.animation.core.tween(
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
                        current = currentRoute ?: Routes.HOME,
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
        }
    }
}

package com.lop.budget.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
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
import androidx.compose.material3.SnackbarHost
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
import com.lop.budget.ui.screens.manage.TagsManageScreen
import com.lop.budget.ui.screens.monthly.MonthlyTransactionsScreen
import com.lop.budget.ui.screens.settings.SettingsScreen
import com.lop.budget.ui.screens.transaction.TransactionEditScreen
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

private val screenOrder = listOf(Routes.HOME, Routes.ANALYTICS, Routes.GOALS, Routes.ACCOUNTS)
private const val NAV_ANIM_DURATION = 500

@OptIn(ExperimentalAnimationApi::class)
private fun AnimatedContentTransitionScope<NavBackStackEntry>.createEnterTransition(): EnterTransition {
    val initialIndex = screenOrder.indexOf(initialState.destination.route)
    val targetIndex = screenOrder.indexOf(targetState.destination.route)

    // Fallback for non-root routes or same route
    if (initialIndex == -1 || targetIndex == -1 || initialIndex == targetIndex) {
        return fadeIn(animationSpec = tween(NAV_ANIM_DURATION))
    }

    val direction = if (initialIndex > targetIndex)
        AnimatedContentTransitionScope.SlideDirection.Right
    else
        AnimatedContentTransitionScope.SlideDirection.Left

    return slideIntoContainer(
        towards = direction,
        animationSpec = tween(NAV_ANIM_DURATION, easing = FastOutSlowInEasing)
    ) + fadeIn(animationSpec = tween(NAV_ANIM_DURATION))
}

@OptIn(ExperimentalAnimationApi::class)
private fun AnimatedContentTransitionScope<NavBackStackEntry>.createExitTransition(): ExitTransition {
    val initialIndex = screenOrder.indexOf(initialState.destination.route)
    val targetIndex = screenOrder.indexOf(targetState.destination.route)

    if (initialIndex == -1 || targetIndex == -1 || initialIndex == targetIndex) {
        return fadeOut(animationSpec = tween(NAV_ANIM_DURATION))
    }

    val direction = if (initialIndex > targetIndex)
        AnimatedContentTransitionScope.SlideDirection.Right
    else
        AnimatedContentTransitionScope.SlideDirection.Left

    return slideOutOfContainer(
        towards = direction,
        animationSpec = tween(NAV_ANIM_DURATION, easing = FastOutSlowInEasing)
    ) + fadeOut(animationSpec = tween(NAV_ANIM_DURATION))
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
    val animDuration = 400

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                enterTransition = { createEnterTransition() },
                exitTransition = { createExitTransition() },
                popEnterTransition = { createEnterTransition() },
                popExitTransition = { createExitTransition() },
            ) {
                composable(
                    Routes.HOME,
                    exitTransition = {
                        scaleOut(
                            targetScale = 0.95f,
                            animationSpec = tween(animDuration, easing = FastOutSlowInEasing)
                        ) + fadeOut(
                            targetAlpha = 0.5f,
                            animationSpec = tween(animDuration, easing = FastOutSlowInEasing)
                        )
                    },
                    popEnterTransition = {
                        scaleIn(
                            initialScale = 0.95f,
                            animationSpec = tween(animDuration, easing = FastOutSlowInEasing)
                        ) + fadeIn(
                            initialAlpha = 0.5f,
                            animationSpec = tween(animDuration, easing = FastOutSlowInEasing)
                        )
                    }) {
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
                    enterTransition = {
                        slideIntoContainer(
                            towards = AnimatedContentTransitionScope.SlideDirection.Up,
                            animationSpec = tween(animDuration, easing = FastOutSlowInEasing)
                        )
                    },
                    exitTransition = {
                        slideOutOfContainer(
                            towards = AnimatedContentTransitionScope.SlideDirection.Left,
                            animationSpec = tween(animDuration, easing = FastOutSlowInEasing)
                        )
                    },
                    popEnterTransition = {
                        slideIntoContainer(
                            towards = AnimatedContentTransitionScope.SlideDirection.Right,
                            animationSpec = tween(animDuration, easing = FastOutSlowInEasing)
                        )
                    },
                    popExitTransition = {
                        slideOutOfContainer(
                            towards = AnimatedContentTransitionScope.SlideDirection.Down,
                            animationSpec = tween(animDuration, easing = FastOutSlowInEasing)
                        )
                    }
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
                    enterTransition = {
                        slideIntoContainer(
                            towards = AnimatedContentTransitionScope.SlideDirection.Up,
                            animationSpec = tween(animDuration, easing = FastOutSlowInEasing)
                        )
                    },
                    exitTransition = {
                        slideOutOfContainer(
                            towards = AnimatedContentTransitionScope.SlideDirection.Left,
                            animationSpec = tween(animDuration, easing = FastOutSlowInEasing)
                        )
                    },
                    popEnterTransition = {
                        slideIntoContainer(
                            towards = AnimatedContentTransitionScope.SlideDirection.Right,
                            animationSpec = tween(animDuration, easing = FastOutSlowInEasing)
                        )
                    },
                    popExitTransition = {
                        slideOutOfContainer(
                            towards = AnimatedContentTransitionScope.SlideDirection.Down,
                            animationSpec = tween(animDuration, easing = FastOutSlowInEasing)
                        )
                    }
                ) {
                    SettingsScreen(
                        onBack = { navController.popBackStack() },
                        onOpenTags = { navController.navigate(Routes.TAGS) },
                    )
                }

                composable(
                    Routes.TAGS,
                    enterTransition = {
                        slideIntoContainer(
                            towards = AnimatedContentTransitionScope.SlideDirection.Left,
                            animationSpec = tween(animDuration, easing = FastOutSlowInEasing)
                        )
                    },
                    popEnterTransition = {
                        slideIntoContainer(
                            towards = AnimatedContentTransitionScope.SlideDirection.Right,
                            animationSpec = tween(animDuration, easing = FastOutSlowInEasing)
                        )
                    },
                    exitTransition = {
                        slideOutOfContainer(
                            towards = AnimatedContentTransitionScope.SlideDirection.Left,
                            animationSpec = tween(animDuration, easing = FastOutSlowInEasing)
                        )
                    },
                    popExitTransition = {
                        slideOutOfContainer(
                            towards = AnimatedContentTransitionScope.SlideDirection.Right,
                            animationSpec = tween(animDuration, easing = FastOutSlowInEasing)
                        )
                    },
                ) {
                    TagsManageScreen(onBack = { navController.popBackStack() })
                }

                composable(Routes.CATEGORY_CREATE) {
                    CategoryCreateScreen(onBack = { navController.popBackStack() })
                }

                composable(
                    Routes.ADD,
                    enterTransition = {
                        slideIntoContainer(
                            towards = AnimatedContentTransitionScope.SlideDirection.Up,
                            animationSpec = tween(animDuration, easing = FastOutSlowInEasing)
                        )
                    },
                    exitTransition = {
                        slideOutOfContainer(
                            towards = AnimatedContentTransitionScope.SlideDirection.Left,
                            animationSpec = tween(animDuration, easing = FastOutSlowInEasing)
                        )
                    },
                    popEnterTransition = {
                        slideIntoContainer(
                            towards = AnimatedContentTransitionScope.SlideDirection.Right,
                            animationSpec = tween(animDuration, easing = FastOutSlowInEasing)
                        )
                    },
                    popExitTransition = {
                        slideOutOfContainer(
                            towards = AnimatedContentTransitionScope.SlideDirection.Down,
                            animationSpec = tween(animDuration, easing = FastOutSlowInEasing)
                        )
                    }
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
                            towards = AnimatedContentTransitionScope.SlideDirection.Left,
                            animationSpec = tween(animDuration, easing = FastOutSlowInEasing)
                        )
                    },
                    exitTransition = {
                        slideOutOfContainer(
                            towards = AnimatedContentTransitionScope.SlideDirection.Left,
                            animationSpec = tween(animDuration, easing = FastOutSlowInEasing)
                        )
                    },
                    popEnterTransition = {
                        slideIntoContainer(
                            towards = AnimatedContentTransitionScope.SlideDirection.Right,
                            animationSpec = tween(animDuration, easing = FastOutSlowInEasing)
                        )
                    },
                    popExitTransition = {
                        slideOutOfContainer(
                            towards = AnimatedContentTransitionScope.SlideDirection.Right,
                            animationSpec = tween(animDuration, easing = FastOutSlowInEasing)
                        )
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
                    enterTransition = {
                        slideIntoContainer(
                            towards = AnimatedContentTransitionScope.SlideDirection.Up,
                            animationSpec = tween(animDuration, easing = FastOutSlowInEasing)
                        )
                    },
                    exitTransition = {
                        slideOutOfContainer(
                            towards = AnimatedContentTransitionScope.SlideDirection.Left,
                            animationSpec = tween(animDuration, easing = FastOutSlowInEasing)
                        )
                    },
                    popEnterTransition = {
                        slideIntoContainer(
                            towards = AnimatedContentTransitionScope.SlideDirection.Right,
                            animationSpec = tween(animDuration, easing = FastOutSlowInEasing)
                        )
                    },
                    popExitTransition = {
                        slideOutOfContainer(
                            towards = AnimatedContentTransitionScope.SlideDirection.Down,
                            animationSpec = tween(animDuration, easing = FastOutSlowInEasing)
                        )
                    }
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

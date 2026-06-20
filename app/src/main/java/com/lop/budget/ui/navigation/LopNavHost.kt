package com.lop.budget.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.lop.budget.ui.components.clickableNoRipple
import com.lop.budget.ui.motion.MotionSpec
import com.lop.budget.ui.screens.accounts.AccountsScreen
import com.lop.budget.ui.screens.ai.AiScreen
import com.lop.budget.ui.screens.analytics.AnalyticsScreen
import com.lop.budget.ui.screens.detail.TransactionDetailScreen
import com.lop.budget.ui.screens.goals.GoalsScreen
import com.lop.budget.ui.screens.home.HomeScreen
import com.lop.budget.ui.screens.settings.SettingsScreen
import com.lop.budget.ui.screens.transaction.TransactionEditScreen

@Composable
fun LopNavHost() {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val showBar = currentRoute in Routes.rootRoutes

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            NavHost(
                navController = navController,
                startDestination = Routes.HOME,
                // Fallback transitions (si une destination n'en définit pas)
                enterTransition = { fadeIn(animationSpec = MotionSpec.mediumTween()) },
                exitTransition = { fadeOut(animationSpec = MotionSpec.fastTween()) },
                popEnterTransition = { fadeIn(animationSpec = MotionSpec.mediumTween()) },
                popExitTransition = { fadeOut(animationSpec = MotionSpec.fastTween()) },
            ) {
                // ROOT (tabs) : crossfade subtil
                composable(
                    Routes.HOME,
                    enterTransition = { fadeIn(animationSpec = MotionSpec.mediumTween()) },
                    exitTransition = { fadeOut(animationSpec = MotionSpec.fastTween()) },
                    popEnterTransition = { fadeIn(animationSpec = MotionSpec.mediumTween()) },
                    popExitTransition = { fadeOut(animationSpec = MotionSpec.fastTween()) },
                ) {
                    HomeScreen(
                        onOpenTransaction = { navController.navigate(Routes.detail(it)) },
                        onOpenAi = { navController.navigate(Routes.AI) },
                    )
                }

                composable(
                    Routes.ANALYTICS,
                    enterTransition = { fadeIn(animationSpec = MotionSpec.mediumTween()) },
                    exitTransition = { fadeOut(animationSpec = MotionSpec.fastTween()) },
                    popEnterTransition = { fadeIn(animationSpec = MotionSpec.mediumTween()) },
                    popExitTransition = { fadeOut(animationSpec = MotionSpec.fastTween()) },
                ) { AnalyticsScreen() }

                composable(
                    Routes.GOALS,
                    enterTransition = { fadeIn(animationSpec = MotionSpec.mediumTween()) },
                    exitTransition = { fadeOut(animationSpec = MotionSpec.fastTween()) },
                    popEnterTransition = { fadeIn(animationSpec = MotionSpec.mediumTween()) },
                    popExitTransition = { fadeOut(animationSpec = MotionSpec.fastTween()) },
                ) { GoalsScreen() }

                composable(
                    Routes.ACCOUNTS,
                    enterTransition = { fadeIn(animationSpec = MotionSpec.mediumTween()) },
                    exitTransition = { fadeOut(animationSpec = MotionSpec.fastTween()) },
                    popEnterTransition = { fadeIn(animationSpec = MotionSpec.mediumTween()) },
                    popExitTransition = { fadeOut(animationSpec = MotionSpec.fastTween()) },
                ) { AccountsScreen() }

                // SECONDARY
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
                            animationSpec = MotionSpec.mediumTween(),
                        ) + fadeOut(animationSpec = MotionSpec.fastTween())
                    },
                ) { TransactionEditScreen(onBack = { navController.popBackStack() }) }

                composable(
                    Routes.AI,
                    enterTransition = {
                        slideIntoContainer(
                            AnimatedContentTransitionScope.SlideDirection.Left,
                            animationSpec = MotionSpec.mediumTween(),
                        ) + fadeIn(animationSpec = MotionSpec.mediumTween())
                    },
                    exitTransition = {
                        slideOutOfContainer(
                            AnimatedContentTransitionScope.SlideDirection.Right,
                            animationSpec = MotionSpec.fastTween(),
                        ) + fadeOut(animationSpec = MotionSpec.fastTween())
                    },
                    popEnterTransition = {
                        slideIntoContainer(
                            AnimatedContentTransitionScope.SlideDirection.Left,
                            animationSpec = MotionSpec.mediumTween(),
                        ) + fadeIn(animationSpec = MotionSpec.mediumTween())
                    },
                    popExitTransition = {
                        slideOutOfContainer(
                            AnimatedContentTransitionScope.SlideDirection.Right,
                            animationSpec = MotionSpec.mediumTween(),
                        ) + fadeOut(animationSpec = MotionSpec.fastTween())
                    },
                ) { AiScreen(onBack = { navController.popBackStack() }) }

                composable(
                    Routes.SETTINGS,
                    enterTransition = {
                        slideIntoContainer(
                            AnimatedContentTransitionScope.SlideDirection.Left,
                            animationSpec = MotionSpec.mediumTween(),
                        ) + fadeIn(animationSpec = MotionSpec.mediumTween())
                    },
                    exitTransition = {
                        slideOutOfContainer(
                            AnimatedContentTransitionScope.SlideDirection.Right,
                            animationSpec = MotionSpec.fastTween(),
                        ) + fadeOut(animationSpec = MotionSpec.fastTween())
                    },
                    popEnterTransition = {
                        slideIntoContainer(
                            AnimatedContentTransitionScope.SlideDirection.Left,
                            animationSpec = MotionSpec.mediumTween(),
                        ) + fadeIn(animationSpec = MotionSpec.mediumTween())
                    },
                    popExitTransition = {
                        slideOutOfContainer(
                            AnimatedContentTransitionScope.SlideDirection.Right,
                            animationSpec = MotionSpec.mediumTween(),
                        ) + fadeOut(animationSpec = MotionSpec.fastTween())
                    },
                ) { SettingsScreen(onBack = { navController.popBackStack() }) }

                composable(
                    Routes.DETAIL,
                    arguments = listOf(navArgument("id") { type = NavType.LongType }),
                    enterTransition = {
                        slideIntoContainer(
                            AnimatedContentTransitionScope.SlideDirection.Left,
                            animationSpec = MotionSpec.mediumTween(),
                        ) + fadeIn(animationSpec = MotionSpec.mediumTween())
                    },
                    exitTransition = {
                        slideOutOfContainer(
                            AnimatedContentTransitionScope.SlideDirection.Right,
                            animationSpec = MotionSpec.fastTween(),
                        ) + fadeOut(animationSpec = MotionSpec.fastTween())
                    },
                    popEnterTransition = {
                        slideIntoContainer(
                            AnimatedContentTransitionScope.SlideDirection.Left,
                            animationSpec = MotionSpec.mediumTween(),
                        ) + fadeIn(animationSpec = MotionSpec.mediumTween())
                    },
                    popExitTransition = {
                        slideOutOfContainer(
                            AnimatedContentTransitionScope.SlideDirection.Right,
                            animationSpec = MotionSpec.mediumTween(),
                        ) + fadeOut(animationSpec = MotionSpec.fastTween())
                    },
                ) { entry ->
                    val id = entry.arguments?.getLong("id") ?: 0L
                    TransactionDetailScreen(transactionId = id, onBack = { navController.popBackStack() })
                }
            }

            // Bottom bar en overlay (vraiment flottante)
            AnimatedVisibility(
                visible = showBar,
                enter = slideInVertically(
                    animationSpec = androidx.compose.animation.core.tween(
                        durationMillis = MotionSpec.SLOW_MS,
                        easing = MotionSpec.easeOut,
                    ),
                ) { fullHeight -> fullHeight / 2 } + fadeIn(
                    animationSpec = androidx.compose.animation.core.tween(
                        durationMillis = MotionSpec.MEDIUM_MS,
                        easing = MotionSpec.easeOut,
                    ),
                ),
                exit = slideOutVertically(
                    animationSpec = androidx.compose.animation.core.tween(
                        durationMillis = MotionSpec.MEDIUM_MS,
                        easing = MotionSpec.easeOut,
                    ),
                ) { fullHeight -> fullHeight / 2 } + fadeOut(
                    animationSpec = androidx.compose.animation.core.tween(
                        durationMillis = MotionSpec.FAST_MS,
                        easing = MotionSpec.easeOut,
                    ),
                ),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 20.dp),
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
                )
            }

            if (showBar) {
                Icon(
                    Icons.Filled.Settings,
                    contentDescription = "Réglages",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 26.dp, end = 20.dp)
                        .clickableNoRipple { navController.navigate(Routes.SETTINGS) },
                )
            }
        }
    }
}

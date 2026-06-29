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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import com.lop.budget.ui.screens.detail.TransactionDetailScreen
import com.lop.budget.ui.screens.goals.GoalsScreen
import com.lop.budget.ui.screens.home.HomeScreen
import com.lop.budget.ui.screens.monthly.MonthlyTransactionsScreen
import com.lop.budget.ui.screens.settings.SettingsScreen
import com.lop.budget.ui.screens.transaction.TransactionEditScreen
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.launch

private val screenOrder =
    listOf(Routes.HOME, Routes.ANALYTICS, Routes.GOALS, Routes.ACCOUNTS)

@OptIn(ExperimentalAnimationApi::class)
private fun createEnterTransition(
    initialState: NavBackStackEntry,
    targetState: NavBackStackEntry,
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
    targetState: NavBackStackEntry,
): ExitTransition {
    val initialIndex = screenOrder.indexOf(initialState.destination.route)
    val targetIndex = screenOrder.indexOf(targetState.destination.route)
    return if (initialIndex == -1 || targetIndex == -1) fadeOut()
    else if (initialIndex > targetIndex) slideOutHorizontally(targetOffsetX = { it })
    else slideOutHorizontally(targetOffsetX = { -it })
}

// Nécessaire pour résoudre EnterTransition / ExitTransition sans ambiguïté
private typealias EnterTransition = androidx.compose.animation.EnterTransition
private typealias ExitTransition = androidx.compose.animation.ExitTransition

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LopNavHost() {
    val navController = rememberNavController()
    val hazeState = rememberHazeState()
    val scope = rememberCoroutineScope()

    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val showBar = currentRoute in Routes.rootRoutes

    // ─── État du ModalBottomSheet d'ajout ───────────────────────────────────
    // skipPartiallyExpanded = false : permet les 3 états (Hidden → PartiallyExpanded → Expanded)
    // confirmValueChange : empêche la fermeture accidentelle si le clavier est ouvert
    var showAddSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = false,
        confirmValueChange = { it != SheetValue.Hidden || true },
    )

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .hazeSource(state = hazeState),
        ) {
            // ─── NavHost principal (sans la route ADD) ───────────────────────
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
                        onOpenTransaction = { navController.navigate(Routes.detail(it)) },
                        onOpenAi = { navController.navigate(Routes.AI) },
                        navController = navController,
                        onOpenMonthly = { type, ym ->
                            navController.navigate(Routes.monthly(type, ym))
                        },
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
                ) {
                    MonthlyTransactionsScreen(
                        onBack = { navController.popBackStack() },
                        onOpenTransaction = { navController.navigate(Routes.detail(it)) },
                    )
                }

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

                composable(Routes.SETTINGS) {
                    SettingsScreen(onBack = { navController.popBackStack() })
                }

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
                    TransactionDetailScreen(
                        transactionId = id,
                        onBack = { navController.popBackStack() },
                    )
                }
            }

            // ─── Bottom bar flottante ────────────────────────────────────────
            AnimatedVisibility(
                visible = showBar,
                enter = slideInVertically(
                    animationSpec = androidx.compose.animation.core.tween(
                        durationMillis = MotionSpec.SLOW_MS,
                        easing = MotionSpec.easeOut,
                    ),
                ) { it / 2 } + fadeIn(
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
                ) { it / 2 } + fadeOut(
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
                    // FAB ouvre le ModalBottomSheet au lieu de naviguer vers Routes.ADD
                    onAdd = { showAddSheet = true },
                    hazeState = hazeState,
                )
            }
        }

        // ─── ModalBottomSheet expansible ────────────────────────────────────
        // Comportement :
        //   • Ouverture → état PartiallyExpanded (mi-hauteur, ~50% de l'écran)
        //   • Glisse vers le haut → état Expanded (plein écran)
        //   • Glisse vers le bas depuis PartiallyExpanded → fermeture
        if (showAddSheet) {
            ModalBottomSheet(
                onDismissRequest = {
                    // Fermeture via glisse vers le bas ou tap sur le scrim
                    showAddSheet = false
                },
                sheetState = sheetState,
                // Fond du sheet aligné sur la surface de l'app
                containerColor = MaterialTheme.colorScheme.surface,
                // Drag handle visible pour indiquer l'expansibilité
                dragHandle = {
                    // Handle personnalisé centré
                    Box(
                        modifier = Modifier
                            .padding(vertical = 12.dp)
                            .fillMaxSize(),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        androidx.compose.foundation.layout.Box(
                            modifier = Modifier
                                .padding(top = 0.dp)
                                .fillMaxWidth(0.12f)
                                .padding(vertical = 0.dp),
                        ) {
                            androidx.compose.foundation.Canvas(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 0.dp)
                                    .height(4.dp),
                            ) {
                                drawRoundRect(
                                    color = androidx.compose.ui.graphics.Color.Gray.copy(alpha = 0.4f),
                                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(50f),
                                )
                            }
                        }
                    }
                },
            ) {
                // Contenu du formulaire — occupe toute la hauteur disponible
                // (PartiallyExpanded = ~50% ; Expanded = 100%)
                TransactionEditScreen(
                    onBack = {
                        scope.launch {
                            sheetState.hide()
                        }.invokeOnCompletion {
                            showAddSheet = false
                        }
                    },
                )
            }
        }
    }
}

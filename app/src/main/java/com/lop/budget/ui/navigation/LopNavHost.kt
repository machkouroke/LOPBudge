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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
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
import com.lop.budget.ui.screens.category.CategoryCreateScreen
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
    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }

    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val showBar = currentRoute in Routes.rootRoutes

    var showAddSheet by remember { mutableStateOf(false) }
    var returnToSheetAfterCategoryCreate by remember { mutableStateOf(false) }
    // skipPartiallyExpanded = false : active les 3 états (Hidden → PartiallyExpanded → Expanded)
    // NE PAS utiliser confirmValueChange ici — cela interfère avec la gestion interne
    // des états du sheet et provoque des oscillations (vibrations) lors du glissement.
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = false,
    )

    // FIX sheet vide — CAUSE RACINE :
    // hiltViewModel() dans un ModalBottomSheet ne trouve pas de ViewModelStoreOwner
    // valide car le sheet est rendu dans une fenêtre séparée (PopupLayout).
    // Solution : capturer le LocalViewModelStoreOwner ICI (avant le Scaffold,
    // dans le contexte Activity) et le réinjecter via CompositionLocalProvider
    // à l'intérieur du sheet.
    val vmStoreOwner = checkNotNull(LocalViewModelStoreOwner.current) {
        "Aucun ViewModelStoreOwner trouvé — LopNavHost doit être dans un contexte Activity."
    }

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
            // ─── NavHost principal ───────────────────────────────────────────
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
                composable(Routes.CATEGORY_CREATE) {
                    CategoryCreateScreen(onBack = { navController.popBackStack() })
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

            // ─── Bottom bar flottante + Dégradé de fond ──────────────────────
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
                    .fillMaxWidth(),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        // Dégradé : du transparent (haut) vers la couleur de fond (bas)
                        .background(
                            brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                                colors = listOf(
                                    androidx.compose.ui.graphics.Color.Transparent,
                                    MaterialTheme.colorScheme.background.copy(alpha = 0.4f),
                                    MaterialTheme.colorScheme.background.copy(alpha = 0.6f),
                                    MaterialTheme.colorScheme.background
                                )
                            )
                        )
                        .navigationBarsPadding()
                        .padding(
                            bottom = 20.dp,
                            top = 40.dp
                        ) // padding top pour étendre le dégradé au-dessus de la barre
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
                        onAdd = { showAddSheet = true },
                        hazeState = hazeState,
                    )
                }
            }

            // ─── ModalBottomSheet expansible ────────────────────────────────
            // IMPORTANT : le sheet est DANS le Box (même scope Composable que
            // le NavHost). Le CompositionLocalProvider réinjecte le vmStoreOwner
            // capturé avant le Scaffold pour que hiltViewModel() fonctionne.
            //
            // Comportement :
            //   • Ouverture → PartiallyExpanded (~50% de l'écran)
            //   • Glisse vers le haut → Expanded (plein écran)
            //   • Glisse vers le bas / tap scrim → fermeture
            if (showAddSheet) {
                CompositionLocalProvider(LocalViewModelStoreOwner provides vmStoreOwner) {
                    ModalBottomSheet(
                        onDismissRequest = { showAddSheet = false },
                        sheetState = sheetState,
                        containerColor = MaterialTheme.colorScheme.surface,
                        dragHandle = {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth() // <-- CORRECTION ICI : fillMaxWidth au lieu de fillMaxSize
                                    .padding(vertical = 12.dp),
                                contentAlignment = Alignment.TopCenter,
                            ) {
                                androidx.compose.foundation.Canvas(
                                    modifier = Modifier
                                        .width(40.dp)
                                        .height(4.dp),
                                ) {
                                    drawRoundRect(
                                        color = androidx.compose.ui.graphics.Color.Gray.copy(alpha = 0.4f),
                                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(50f),
                                    )
                                }
                            }
                        },
                    ) {
                        TransactionEditScreen(
                            onBack = {
                                scope.launch { sheetState.hide() }.invokeOnCompletion {
                                    showAddSheet = false
                                }
                            },
                            onNavigateToCreateCategory = {
                                // Fermer le sheet puis naviguer vers l'écran de création de catégorie
                                scope.launch { sheetState.hide() }.invokeOnCompletion {
                                    showAddSheet = false
                                    returnToSheetAfterCategoryCreate = true
                                    navController.navigate(Routes.CATEGORY_CREATE)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

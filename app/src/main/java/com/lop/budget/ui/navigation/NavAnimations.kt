package com.lop.budget.ui.navigation

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDeepLink
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable

/**
 * Types d'animations de navigation disponibles.
 */
enum class NavAnimationType {
    /** Glissement vertical (Up/Down) - Utilisé pour les transactions et écrans principaux. */
    MAIN,
    /** Glissement latéral (Left/Right) - Utilisé pour les menus secondaires (paramètres, recherche, etc.). */
    SECONDARY,
    /** Animation par défaut du NavHost (Slide horizontal entre les onglets racines). */
    ROOT
}

/**
 * Centralisation des animations de navigation pour assurer une cohérence visuelle.
 */
object NavAnimations {
    private const val DEFAULT_DURATION = 400
    private val defaultEasing = FastOutSlowInEasing

    fun enter(
        type: NavAnimationType,
        duration: Int = DEFAULT_DURATION
    ): AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition? = {
        when (type) {
            NavAnimationType.MAIN -> slideIntoContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Up,
                animationSpec = tween(duration, easing = defaultEasing)
            )
            NavAnimationType.SECONDARY -> slideIntoContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Left,
                animationSpec = tween(duration, easing = defaultEasing)
            )
            NavAnimationType.ROOT -> null // Laissé à la gestion globale du NavHost
        }
    }

    fun exit(
        type: NavAnimationType,
        duration: Int = DEFAULT_DURATION
    ): AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition? = {
        when (type) {
            NavAnimationType.MAIN -> slideOutOfContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Left,
                animationSpec = tween(duration, easing = defaultEasing)
            )
            NavAnimationType.SECONDARY -> slideOutOfContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Left,
                animationSpec = tween(duration, easing = defaultEasing)
            )
            NavAnimationType.ROOT -> null
        }
    }

    fun popEnter(
        type: NavAnimationType,
        duration: Int = DEFAULT_DURATION
    ): AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition? = {
        when (type) {
            NavAnimationType.MAIN -> slideIntoContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Right,
                animationSpec = tween(duration, easing = defaultEasing)
            )
            NavAnimationType.SECONDARY -> slideIntoContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Right,
                animationSpec = tween(duration, easing = defaultEasing)
            )
            NavAnimationType.ROOT -> null
        }
    }

    fun popExit(
        type: NavAnimationType,
        duration: Int = DEFAULT_DURATION
    ): AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition? = {
        when (type) {
            NavAnimationType.MAIN -> slideOutOfContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Down,
                animationSpec = tween(duration, easing = defaultEasing)
            )
            NavAnimationType.SECONDARY -> slideOutOfContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Right,
                animationSpec = tween(duration, easing = defaultEasing)
            )
            NavAnimationType.ROOT -> null
        }
    }
}

/**
 * Extension de NavGraphBuilder pour déclarer une destination avec ses animations prédéfinies.
 */
fun NavGraphBuilder.composableAnimated(
    route: String,
    type: NavAnimationType,
    arguments: List<NamedNavArgument> = emptyList(),
    deepLinks: List<NavDeepLink> = emptyList(),
    content: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit
) {
    composable(
        route = route,
        arguments = arguments,
        deepLinks = deepLinks,
        enterTransition = NavAnimations.enter(type),
        exitTransition = NavAnimations.exit(type),
        popEnterTransition = NavAnimations.popEnter(type),
        popExitTransition = NavAnimations.popExit(type),
        content = content
    )
}

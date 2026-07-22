package com.lop.budget.ui.navigation

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
    private const val ROOT_DURATION = 500
    private val defaultEasing = FastOutSlowInEasing
    
    private val screenOrder = listOf(Routes.HOME, Routes.ANALYTICS, Routes.GOALS, Routes.ACCOUNTS)

    private fun isRoot(route: String?): Boolean {
        val baseRoute = route?.substringBefore("/") ?: return false
        return baseRoute in Routes.rootRoutes
    }

    /**
     * Transition d'entrée globale pour le NavHost.
     * Gère intelligemment les transitions entre onglets (Shared Axis) et la signature Home.
     */
    fun getGlobalEnterTransition(
        initial: String?,
        target: String?
    ): AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition {
        return {
            val initialRoot = isRoot(initial)
            val targetRoot = isRoot(target)

            when {
                // Transition entre onglets principaux (Shared Axis)
                initialRoot && targetRoot -> {
                    val initialIndex = screenOrder.indexOf(initial?.substringBefore("/"))
                    val targetIndex = screenOrder.indexOf(target?.substringBefore("/"))
                    val direction = if (initialIndex > targetIndex) 
                        AnimatedContentTransitionScope.SlideDirection.Right 
                    else 
                        AnimatedContentTransitionScope.SlideDirection.Left

                    slideIntoContainer(
                        towards = direction,
                        animationSpec = tween(ROOT_DURATION, easing = defaultEasing)
                    ) + fadeIn(animationSpec = tween(ROOT_DURATION))
                }
                
                // Retour à l'accueil depuis un écran secondaire (Signature Home)
                target?.substringBefore("/") == Routes.HOME && !initialRoot -> {
                    scaleIn(
                        initialScale = 0.95f,
                        animationSpec = tween(DEFAULT_DURATION, easing = defaultEasing)
                    ) + fadeIn(
                        initialAlpha = 0.5f,
                        animationSpec = tween(DEFAULT_DURATION, easing = defaultEasing)
                    )
                }

                // Par défaut
                else -> fadeIn(animationSpec = tween(DEFAULT_DURATION))
            }
        }
    }

    /**
     * Transition de sortie globale pour le NavHost.
     */
    fun getGlobalExitTransition(
        initial: String?,
        target: String?
    ): AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition {
        return {
            val initialRoot = isRoot(initial)
            val targetRoot = isRoot(target)

            when {
                // Transition entre onglets principaux (Shared Axis)
                initialRoot && targetRoot -> {
                    val initialIndex = screenOrder.indexOf(initial?.substringBefore("/"))
                    val targetIndex = screenOrder.indexOf(target?.substringBefore("/"))
                    val direction = if (initialIndex > targetIndex) 
                        AnimatedContentTransitionScope.SlideDirection.Right 
                    else 
                        AnimatedContentTransitionScope.SlideDirection.Left

                    slideOutOfContainer(
                        towards = direction,
                        animationSpec = tween(ROOT_DURATION, easing = defaultEasing)
                    ) + fadeOut(animationSpec = tween(ROOT_DURATION))
                }

                // Départ de l'accueil vers un écran secondaire (Signature Home)
                initial?.substringBefore("/") == Routes.HOME && !targetRoot -> {
                    scaleOut(
                        targetScale = 0.95f,
                        animationSpec = tween(DEFAULT_DURATION, easing = defaultEasing)
                    ) + fadeOut(
                        targetAlpha = 0.5f,
                        animationSpec = tween(DEFAULT_DURATION, easing = defaultEasing)
                    )
                }

                // Par défaut
                else -> fadeOut(animationSpec = tween(DEFAULT_DURATION))
            }
        }
    }

    // --- Helpers pour composableAnimated ---

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
            NavAnimationType.ROOT -> null
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

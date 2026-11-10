package com.lop.budget.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.ui.unit.IntOffset
import androidx.navigation.NavBackStackEntry
import com.lop.budget.ui.motion.MotionSpec
import com.lop.budget.ui.screens.search.SearchViewModel

private typealias NavTransitionScope = AnimatedContentTransitionScope<NavBackStackEntry>

/**
 * Transitions de navigation, communes à toutes les destinations (LOP-226, CA-05).
 *
 * - Entre deux onglets principaux : l'ancien s'efface, puis le nouveau apparaît en passant de 96 %
 *   à 100 %. Aucun glissement (CA-02, P-6). Les écrans racines sont transparents : un fondu croisé
 *   superposerait les deux pages, d'où l'enchaînement.
 * - Ouverture d'un écran secondaire : il entre par le bord droit ; l'écran recouvert recule d'un
 *   quart de largeur vers la gauche en s'effaçant (CA-01, P-4, P-5).
 * - Retour : miroir exact de l'ouverture (CA-01, P-3).
 * - Accueil ↔ Recherche ouverte par la barre : aucun glissement. La barre voyage jusqu'au champ,
 *   les tuiles de l'accueil partent en vague, la recherche paraît en fondu autour (DESIGN.md § 13.3).
 */
object NavAnimations {

    private fun isRoot(entry: NavBackStackEntry) =
        entry.destination.route?.substringBefore("/") in Routes.rootRoutes

    private fun NavTransitionScope.betweenTabs() = isRoot(initialState) && isRoot(targetState)

    /** Recherche ouverte par la barre de l'accueil : la seule sans période, « Voir tout » en passe une. */
    fun isBarSearch(entry: NavBackStackEntry) =
        entry.destination.route == Routes.SEARCH &&
            entry.arguments?.getString(SearchViewModel.ARG_START) == null &&
            entry.arguments?.getString(SearchViewModel.ARG_END) == null

    private fun isHome(entry: NavBackStackEntry) = entry.destination.route == Routes.HOME

    private fun NavTransitionScope.viaSearchBar() =
        (isHome(initialState) && isBarSearch(targetState)) || (isBarSearch(initialState) && isHome(targetState))

    private val slide = MotionSpec.screenTween<IntOffset>()
    // Le recul s'efface au rythme de l'écran qui le recouvre.
    private val recedeFade = MotionSpec.screenTween<Float>()

    // Courbes de sortie et d'entrée appariées : l'ancien tient puis part, le nouveau est visible
    // dès son départ. Avec une même courbe lente au départ, l'écran restait vide ~50 ms.
    private val tabEnter = tween<Float>(MotionSpec.TAB_IN_MS, MotionSpec.TAB_OUT_MS, MotionSpec.enterDecelerate)
        .let { fadeIn(it) + scaleIn(it, initialScale = 0.96f) }
    private val tabExit = fadeOut(tween(MotionSpec.TAB_OUT_MS, easing = MotionSpec.exitAccelerate))

    // La recherche paraît quand la vague a vidé l'accueil et finit avec la barre ; plus tôt, elle
    // se superposait aux tuiles encore pleines. L'accueil, lui, tient en place (aucune transition
    // propre) : ses tuiles s'animent elles-mêmes.
    private val searchEnter = fadeIn(
        tween(MotionSpec.TAB_OUT_MS, MotionSpec.SCREEN_MS - MotionSpec.TAB_OUT_MS, MotionSpec.enterDecelerate),
    )
    // Au retour, elle part aussitôt : les tuiles qui reviennent dessous se voient dès leur départ.
    private val searchExit = fadeOut(tween(MotionSpec.TAB_OUT_MS, easing = MotionSpec.standard))

    val enter: NavTransitionScope.() -> EnterTransition = {
        if (betweenTabs()) tabEnter
        else if (viaSearchBar()) searchEnter
        else slideIntoContainer(SlideDirection.Left, slide)
    }

    val exit: NavTransitionScope.() -> ExitTransition = {
        if (betweenTabs()) tabExit
        else if (viaSearchBar()) ExitTransition.None
        else slideOutOfContainer(SlideDirection.Left, slide) { it / 4 } + fadeOut(recedeFade)
    }

    val popEnter: NavTransitionScope.() -> EnterTransition = {
        if (betweenTabs()) tabEnter
        else if (viaSearchBar()) EnterTransition.None
        else slideIntoContainer(SlideDirection.Right, slide) { it / 4 } + fadeIn(recedeFade)
    }

    val popExit: NavTransitionScope.() -> ExitTransition = {
        if (betweenTabs()) tabExit
        else if (viaSearchBar()) searchExit
        else slideOutOfContainer(SlideDirection.Right, slide)
    }
}

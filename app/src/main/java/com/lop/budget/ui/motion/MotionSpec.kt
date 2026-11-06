package com.lop.budget.ui.motion

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/**
 * Spécifications d'animation centralisées.
 *
 * Objectif: une sensation cohérente "premium" (iOS / One UI / Material motion)
 * sans disperser des durées/easing partout dans le code.
 */
object MotionSpec {
    /** Très court: feedback de tap, micro-transitions. */
    const val FAST_MS = 90
    /** Standard: transitions UI simples. */
    const val MEDIUM_MS = 180
    /** Plus doux: apparitions de gros blocs / overlays. */
    const val SLOW_MS = 260

    /** Easing type iOS/OneUI: rapide au début, douce en fin. */
    val easeOut: Easing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)
    /** Easing plus neutre. */
    val standard: Easing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)
    /** Élément qui part : tient puis s'en va vite (Material 3 « emphasized accelerate »). */
    val exitAccelerate: Easing = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)
    /** Élément qui arrive : visible tout de suite, se pose en douceur (« emphasized decelerate »). */
    val enterDecelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)

    fun <T> fastTween(durationMs: Int = FAST_MS) = tween<T>(durationMillis = durationMs, easing = easeOut)
    fun <T> mediumTween(durationMs: Int = MEDIUM_MS) = tween<T>(durationMillis = durationMs, easing = easeOut)

    // --- Écrans (LOP-226, CA-05, P-8) --- Règles d'emploi : DESIGN.md § Mouvement.

    /** Ouverture et retour d'un écran. */
    const val SCREEN_MS = 400
    /**
     * Départ doux, arrivée douce. La première image d'un écran neuf coûte cher (~65 ms en debug) :
     * avec un départ doux, elle n'avance presque pas le mouvement, qui ne paraît pas sauter.
     */
    val screenEasing: Easing = FastOutSlowInEasing
    fun <T> screenTween() = tween<T>(durationMillis = SCREEN_MS, easing = screenEasing)

    /** Fondu enchaîné entre onglets : l'ancien part, puis le nouveau arrive. */
    const val TAB_OUT_MS = 200
    const val TAB_IN_MS = 300

    // --- Squelette (LOP-226, CA-06, P-12) ---
    /** Attente en deçà de laquelle aucun squelette n'apparaît : le contenu arrive directement. */
    const val SKELETON_DELAY_MS = 200L
    /** Durée minimale d'un squelette affiché : il ne clignote jamais sur une seule image. */
    const val SKELETON_MIN_MS = 300L

    // --- Ressorts de déplacement (LOP-226, CA-05, P-7) ---
    // Un ressort garde sa vitesse quand sa cible change : un mouvement interrompu repart de là
    // où il est, sans à-coup. Réservés aux éléments qu'on peut réorienter en plein vol.

    /** Bord avant de l'indicateur (~0,3 s). */
    fun <T> spatialFast(visibilityThreshold: T? = null) =
        spring(dampingRatio = 0.9f, stiffness = 400f, visibilityThreshold = visibilityThreshold)

    /** Bord arrière de l'indicateur, qui traîne pour l'étirer (~0,5 s). */
    fun <T> spatialSlow(visibilityThreshold: T? = null) =
        spring(dampingRatio = 0.9f, stiffness = 150f, visibilityThreshold = visibilityThreshold)

    /** Rebond visible, réservé aux petits éléments : icône de l'onglet choisi. */
    fun <T> spatialBouncy(visibilityThreshold: T? = null) =
        spring(dampingRatio = 0.6f, stiffness = 800f, visibilityThreshold = visibilityThreshold)

    /** Spring doux pour éléments flottants. */
    fun floatSpring() = spring<Float>(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMedium,
    )

    /** Spring élastique naturel pour l'apparition des cartes/overlays flottants (iOS/Material 3 feel). */
    fun <T> sheetEnterSpring() = spring<T>(
        dampingRatio = 0.78f,
        stiffness = 380f,
    )

    /** Spring ferme et rapide pour la fermeture directe des cartes/overlays. */
    fun <T> sheetExitSpring() = spring<T>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = 500f,
    )
}

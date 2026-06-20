package com.lop.budget.ui.motion

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
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

    fun <T> fastTween(durationMs: Int = FAST_MS) = tween<T>(durationMillis = durationMs, easing = easeOut)
    fun <T> mediumTween(durationMs: Int = MEDIUM_MS) = tween<T>(durationMillis = durationMs, easing = easeOut)

    /** Spring doux pour éléments flottants. */
    fun floatSpring() = spring<Float>(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMedium,
    )
}

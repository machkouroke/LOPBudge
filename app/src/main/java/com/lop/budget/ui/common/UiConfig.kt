package com.lop.budget.ui.common

import com.lop.budget.BuildConfig

/**
 * Configuration globale des paramètres UI, adaptée selon l'environnement.
 */
object UiConfig {
    /**
     * Fraction de la largeur à dépasser pour confirmer un swipe.
     * Réduite en DEBUG pour faciliter les tests Maestro.
     */
    val swipeThresholdFraction: Float = if (BuildConfig.DEBUG) 0.15f else 0.40f

    /**
     * Vélocité minimale (px/s) pour déclencher l'action par fling.
     * Réduite en DEBUG pour faciliter les tests Maestro.
     */
    val swipeFlingVelocityThreshold: Float = if (BuildConfig.DEBUG) 200f else 800f
}

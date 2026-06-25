package com.lop.budget.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Tokens visuels "source de vérité" pour obtenir un rendu Budge-like.
 *
 * Important: on sépare ces tokens de MaterialTheme.colorScheme pour ne pas subir
 * des teintes inattendues (surfaceVariant, dynamic color, etc.).
 */
@Immutable
data class LopTokens(
    // Corner radii
    val radiusCard: Dp = 28.dp,
    val radiusPill: Dp = 36.dp,

    // Glass / surfaces
    val cardAlpha: Float = 0.62f,
    val cardBorderAlpha: Float = 0.10f,
    val cardShadow: Dp = 2.dp,

    // Bottom bar glass
    val barAlphaTop: Float = 0.70f,
    val barAlphaBottom: Float = 0.55f,
    val barBorderAlpha: Float = 0.14f,

    // Paid attenuation
    val paidContentAlpha: Float = 0.62f,

    // Background gradient overlays
    val bgVignetteAlpha: Float = 0.55f,
)

val LocalTokens = staticCompositionLocalOf { LopTokens() }

/**
 * Couleurs de fond propres au design system (dark + light), indépendantes du dynamic color.
 */
@Immutable
data class LopBackgroundColors(
    val top: Color,
    val bottom: Color,
    val vignette: Color,
)

val LocalBackgroundColors = staticCompositionLocalOf {
    LopBackgroundColors(
        top = Color(0xFF0D0E12),
        bottom = Color(0xFF07070A),
        vignette = Color.Black,
    )
}

object LopDesign {
    val tokens: LopTokens
        @Composable get() = LocalTokens.current

    val spacing: Spacing
        @Composable get() = LocalSpacing.current

    val background: LopBackgroundColors
        @Composable get() = LocalBackgroundColors.current
}

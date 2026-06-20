package com.lop.budget.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Palette de repli (fallback) lavande/violet, utilisée quand la couleur dynamique
 * Material You n'est pas disponible (Android < 12) ou désactivée par l'utilisateur.
 * Inspirée des apps PromptBox / LopWrite : dark OLED + accent lavande.
 */

// --- Accents lavande ---
val LavenderPrimary = Color(0xFFB69DF8)
val LavenderOnPrimary = Color(0xFF381E72)
val LavenderPrimaryContainer = Color(0xFF4F378B)
val LavenderOnPrimaryContainer = Color(0xFFEADDFF)

val LavenderSecondary = Color(0xFFCCC2DC)
val LavenderOnSecondary = Color(0xFF332D41)
val LavenderSecondaryContainer = Color(0xFF4A4458)
val LavenderOnSecondaryContainer = Color(0xFFE8DEF8)

// --- Surfaces dark OLED ---
val DarkBackground = Color(0xFF000000)
val DarkSurface = Color(0xFF0F0E13)
val DarkSurfaceVariant = Color(0xFF1C1B22)
val DarkOnSurface = Color(0xFFE6E1E9)
val DarkOnSurfaceVariant = Color(0xFFCAC4D0)
val DarkOutline = Color(0xFF49454F)

// --- Surfaces clair ---
val LightBackground = Color(0xFFFEF7FF)
val LightSurface = Color(0xFFFEF7FF)
val LightSurfaceVariant = Color(0xFFE7E0EB)
val LightOnSurface = Color(0xFF1D1B20)
val LightOnSurfaceVariant = Color(0xFF49454F)

/**
 * Couleurs sémantiques de l'app (constantes, ne dépendent PAS de la couleur dynamique) :
 * vert = revenu, corail = dépense. Exposées via [LopExtendedColors].
 */
val IncomeGreen = Color(0xFF4ADE80)
val IncomeGreenContainer = Color(0xFF143D24)
val ExpenseCoral = Color(0xFFFF6B6B)
val ExpenseCoralContainer = Color(0xFF4A1717)
val WarningAmber = Color(0xFFFFB74D)

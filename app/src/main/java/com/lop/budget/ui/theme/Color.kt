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

// --- Surfaces dark OLED (Redesign) ---
val DarkBackground = Color(0xFF1A1A1A) // Fond principal très sombre
val DarkSurface = Color(0xFF1E1E1E) // Bottom sheets
val DarkSurfaceVariant = Color(0xFF242424) // Cartes arrondies
val DarkOnSurface = Color(0xFFFFFFFF) // Texte principal blanc
val DarkOnSurfaceVariant = Color(0xFF888888) // Texte secondaire gris
val DarkOutline = Color(0xFF333333) // Séparateurs discrets

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
val IncomeGreen = Color(0xFF4CAF50) // Vert "Groceries"
val IncomeGreenContainer = Color(0xFF1B3B22)
val ExpenseCoral = Color(0xFFE53935) // Rouge tendance négative
val ExpenseCoralContainer = Color(0xFF4A1717)
val WarningAmber = Color(0xFFFFB74D)

// --- Nouvelles couleurs sémantiques (Redesign) ---
val AccentYellow = Color(0xFFC8A84B) // Avatar ring, icônes accent
val CategoryOrange = Color(0xFFE8732A) // Icône Restaurants
val CategoryGreen = Color(0xFF4CAF50) // Icône Groceries
val CategoryBlue = Color(0xFF2196F3) // Prime Video
val CategoryRed = Color(0xFFE53935) // Apple Music, YouTube

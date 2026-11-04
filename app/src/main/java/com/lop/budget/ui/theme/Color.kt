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

// --- Neutres sombres (LOP-229) : jamais teintés par l'accent, même dynamique (I-1) ---
// Fond noir bleuté (P-10), cartes et contrôles noirs (P-12) : sous le ciel ils se découpent, plus
// bas le liseré éclairé dessine leurs bords ; l'ombre y reste invisible.
val DarkBackground = Color(0xFF080B11) // Fond principal noir bleuté (CA-01, P-10)
val DarkSurface = Color(0xFF121722) // Feuilles, popups
val DarkSurfaceVariant = Color(0xFF232A36) // Aplats (pastilles, puces), plus clairs que les cartes
val DarkOnSurface = Color(0xFFF5F6F8)
val DarkOnSurfaceVariant = Color(0xFF9EA3AD) // ≥ 4,5:1 sur cartes, contrôles et haut du ciel (CA-05)
val DarkOutline = Color(0xFF8D919A) // Contours d'interaction, ≥ 3:1 sur le fond
val DarkOutlineVariant = Color(0xFF262C38)
val DarkSurfaceDim = Color(0xFF1A1E26) // Onglet sélectionné : pastille ardoise sur la barre noire (P-12)
val DarkSurfaceContainerLowest = Color(0xFF06080D)
val DarkSurfaceContainerLow = Color(0xFF000000) // Cartes noires, pendant des cartes blanches du clair (P-12)
val DarkSurfaceContainer = Color(0xFF161B24)
val DarkSurfaceContainerHigh = Color(0xFF000000) // Contrôles : boutons ronds, barre du bas, noirs (P-12)
val DarkSurfaceContainerHighest = Color(0xFF2E3644)

// Liseré éclairé par le haut et reflet des cartes (P-6) : blanc translucide, propres au sombre.
val DarkRimTop = Color(0x1FFFFFFF) // 12 %
val DarkRimBottom = Color(0x0DFFFFFF) // 5 %
val DarkSheen = Color(0x00FFFFFF) // Pas de reflet : cartes noires pures (P-12)

// --- Neutres clairs (P-11) : cartes et contrôles blancs sans contour, détachés par l'ombre ---
val LightBackground = Color(0xFFFFFFFF) // Fond principal blanc, sous le ciel (CA-01)
val LightSurface = Color(0xFFF7F8FA) // Feuilles
val LightSurfaceVariant = Color(0xFFF1F2F5) // Aplats, visibles sur les cartes blanches
val LightOnSurface = Color(0xFF15171A)
val LightOnSurfaceVariant = Color(0xFF555962) // ≥ 4,5:1 sur le haut du ciel (CA-05)
val LightOutline = Color(0xFF7A7E87)
val LightOutlineVariant = Color(0xFFE6E8EC)
val LightSurfaceDim = Color(0xFFEEF0F3)
val LightSurfaceContainerLowest = Color(0xFFFFFFFF)
val LightSurfaceContainerLow = Color(0xFFFFFFFF) // Cartes
val LightSurfaceContainer = Color(0xFFF4F5F7)
val LightSurfaceContainerHigh = Color(0xFFFFFFFF) // Contrôles en relief
val LightSurfaceContainerHighest = Color(0xFFE9EBEF)
// Pas de liseré ni de reflet en clair (P-11) : l'ombre diffuse fait seule le relief.
val LightRimTop = Color(0x00FFFFFF)
val LightRimBottom = Color(0x00FFFFFF)
val LightSheen = Color(0x00FFFFFF)

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

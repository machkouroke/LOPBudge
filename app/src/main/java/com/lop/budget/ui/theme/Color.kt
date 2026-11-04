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
// Fond noir neutre, sans teinte (P-16) : le ciel de tout accent s'y fond sans virer au vert.
// Cartes et contrôles noirs (P-12) : sous le ciel ils se découpent ; plus bas, le reflet de leur
// bord haut les dessine (P-15). L'ombre y reste invisible.
val DarkBackground = Color(0xFF0A0A0A) // Fond principal noir neutre (CA-01, P-16)
val DarkSurface = Color(0xFF141414) // Feuilles, popups
val DarkSurfaceVariant = Color(0xFF262626) // Aplats (pastilles, puces), plus clairs que les cartes
val DarkOnSurface = Color(0xFFF5F6F8)
val DarkOnSurfaceVariant = Color(0xFFA1A1A6) // ≥ 4,5:1 sur cartes, contrôles et haut du ciel (CA-05)
val DarkOutline = Color(0xFF8E8E93) // Contours d'interaction, ≥ 3:1 sur le fond
val DarkOutlineVariant = Color(0xFF2A2A2A)
val DarkSurfaceDim = Color(0xFF1C1C1E) // Onglet sélectionné : pastille grise sur la barre noire (P-12, P-16)
val DarkSurfaceContainerLowest = Color(0xFF050505)
val DarkSurfaceContainerLow = Color(0xFF000000) // Cartes noires, pendant des cartes blanches du clair (P-12)
val DarkSurfaceContainer = Color(0xFF161616)
val DarkSurfaceContainerHigh = Color(0xFF000000) // Contrôles : boutons ronds, barre du bas, noirs (P-12)
val DarkSurfaceContainerHighest = Color(0xFF2E2E2E)

// Reflet du bord haut (P-6, P-15) : blanc translucide, propre au sombre.
val DarkRimTop = Color(0x1AFFFFFF) // 10 % : reflet du bord haut (P-15)
val DarkRimBottom = Color(0x00FFFFFF) // éteint dès la mi-hauteur : pas de cadre (P-15)
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

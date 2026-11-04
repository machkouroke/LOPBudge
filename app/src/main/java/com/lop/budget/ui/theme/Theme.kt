package com.lop.budget.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

/** Préférence de thème choisie par l'utilisateur dans les réglages. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

private val DarkColors = darkColorScheme(
    primary = LavenderPrimary,
    onPrimary = LavenderOnPrimary,
    primaryContainer = LavenderPrimaryContainer,
    onPrimaryContainer = LavenderOnPrimaryContainer,
    secondary = LavenderSecondary,
    onSecondary = LavenderOnSecondary,
    secondaryContainer = LavenderSecondaryContainer,
    onSecondaryContainer = LavenderOnSecondaryContainer,
    background = DarkBackground,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    surfaceTint = DarkSurface,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    surfaceDim = DarkSurfaceDim,
    surfaceContainerLowest = DarkSurfaceContainerLowest,
    surfaceContainerLow = DarkSurfaceContainerLow,
    surfaceContainer = DarkSurfaceContainer,
    surfaceContainerHigh = DarkSurfaceContainerHigh,
    surfaceContainerHighest = DarkSurfaceContainerHighest,
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF6750A4),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEADDFF),
    onPrimaryContainer = Color(0xFF21005D),
    secondary = Color(0xFF625B71),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE8DEF8),
    onSecondaryContainer = Color(0xFF1D192B),
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    surfaceTint = LightSurface,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    surfaceDim = LightSurfaceDim,
    surfaceContainerLowest = LightSurfaceContainerLowest,
    surfaceContainerLow = LightSurfaceContainerLow,
    surfaceContainer = LightSurfaceContainer,
    surfaceContainerHigh = LightSurfaceContainerHigh,
    surfaceContainerHighest = LightSurfaceContainerHighest,
)

/** Hauteur du ciel d'accent, depuis le haut de la fenêtre (LOP-229, CA-01, P-10). */
private val AccentGradientHeight = 470.dp

/**
 * Fond principal (LOP-229, CA-01, I-1) : neutre, teinté en haut par l'accent actif, qui s'efface
 * jusqu'au fond neutre à [AccentGradientHeight]. Le pinceau est calé sur le haut de la fenêtre :
 * un élément posé plus bas passe sa distance [fromWindowTop] et prolonge le même dégradé, sans
 * couture avec ce qui est peint au-dessus de lui.
 */
@Composable
fun lopBackgroundBrush(fromWindowTop: Dp = 0.dp): Brush {
    val colors = MaterialTheme.colorScheme
    // Teinte du haut plafonnée pour garder le texte secondaire ≥ 4,5:1 (CA-05).
    val tint = if (colors.background.luminance() < 0.5f) 0.22f else 0.20f
    fun sky(share: Float) = colors.primary.copy(alpha = tint * share).compositeOver(colors.background)
    val density = LocalDensity.current
    val offset = with(density) { fromWindowTop.toPx() }
    val height = with(density) { AccentGradientHeight.toPx() }
    // Décroissance douce, plus rapide en haut, comme un ciel (P-10, P-11).
    return Brush.verticalGradient(
        0f to sky(1f),
        0.35f to sky(0.55f),
        0.7f to sky(0.2f),
        1f to colors.background,
        startY = -offset,
        endY = height - offset,
    )
}

/**
 * Liseré des surfaces (LOP-229, CA-04, P-6, P-15) : reflet du bord haut, éteint à mi-hauteur. Il
 * dessine le bord sans faire de cadre ; transparent en clair, où l'ombre fait le relief (P-11).
 */
@Composable
fun lopRimBrush(): Brush {
    val colors = LocalLopExtendedColors.current
    return Brush.verticalGradient(0f to colors.rimTop, 0.5f to colors.rimBottom, 1f to colors.rimBottom)
}

/** Reflet discret en haut d'une carte (P-6), estompé sur ses 80 premiers dp. */
@Composable
fun lopSheenBrush(): Brush = Brush.verticalGradient(
    listOf(LocalLopExtendedColors.current.sheen, Color.Transparent),
    endY = with(LocalDensity.current) { 80.dp.toPx() },
)

/** Accès pratique aux couleurs étendues : LopTheme.extended.income, etc. */
object LopTheme {
    val extended: LopExtendedColors
        @Composable get() = LocalLopExtendedColors.current
}

/**
 * Thème racine de LOPBudge.
 *
 * @param dynamicColor quand true et Android >= 12, l'accent (primary, secondary, tertiary et
 * leurs variantes) vient du thème Material You du système ; sinon, accent lavande de repli.
 * Fonds, surfaces et contours restent neutres dans les deux cas (LOP-229, I-1). Les couleurs
 * revenu/dépense restent constantes via [LopExtendedColors] (I-2).
 */
@Composable
fun LopBudgeTheme(
    themeMode: ThemeMode = ThemeMode.DARK, // Forcer le Dark Mode par défaut pour le nouveau design
    dynamicColor: Boolean = false, // Désactiver les couleurs dynamiques par défaut pour garder notre palette
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val neutral = if (darkTheme) DarkColors else LightColors
    val colorScheme = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        // I-1 : Android ne fournit que l'accent ; il ne remplace jamais le fond neutre.
        val dynamic = if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        neutral.copy(
            primary = dynamic.primary,
            onPrimary = dynamic.onPrimary,
            primaryContainer = dynamic.primaryContainer,
            onPrimaryContainer = dynamic.onPrimaryContainer,
            inversePrimary = dynamic.inversePrimary,
            secondary = dynamic.secondary,
            onSecondary = dynamic.onSecondary,
            secondaryContainer = dynamic.secondaryContainer,
            onSecondaryContainer = dynamic.onSecondaryContainer,
            tertiary = dynamic.tertiary,
            onTertiary = dynamic.onTertiary,
            tertiaryContainer = dynamic.tertiaryContainer,
            onTertiaryContainer = dynamic.onTertiaryContainer,
        )
    } else {
        neutral
    }

    // Les couleurs sémantiques s'adaptent légèrement entre clair et sombre.
    val extended = if (darkTheme) {
        LopExtendedColors(
            income = IncomeGreen, onIncome = Color.Black, incomeContainer = IncomeGreenContainer,
            expense = ExpenseCoral, onExpense = Color.Black, expenseContainer = ExpenseCoralContainer,
            warning = WarningAmber,
            rimTop = DarkRimTop, rimBottom = DarkRimBottom, sheen = DarkSheen,
        )
    } else {
        LopExtendedColors(
            income = Color(0xFF1B873F), onIncome = Color.White, incomeContainer = Color(0xFFCDF5D8),
            expense = Color(0xFFC62828), onExpense = Color.White, expenseContainer = Color(0xFFFDD7D7),
            warning = Color(0xFFB26A00),
            rimTop = LightRimTop, rimBottom = LightRimBottom, sheen = LightSheen,
        )
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
            }
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
        }
    }

    CompositionLocalProvider(LocalLopExtendedColors provides extended) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = LopTypography,
            shapes = LopShapes,
            content = content,
        )
    }
}

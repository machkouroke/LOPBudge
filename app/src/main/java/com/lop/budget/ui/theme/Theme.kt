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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
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
    outline = DarkOutline,
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
)

/** Accès pratique aux couleurs étendues : LopTheme.extended.income, etc. */
object LopTheme {
    val extended: LopExtendedColors
        @Composable get() = LocalLopExtendedColors.current
}

/**
 * Thème racine de LOPBudge.
 *
 * @param dynamicColor quand true (par défaut) et Android >= 12, le schéma de
 * couleurs est dérivé du wallpaper / thème Material You du système. Sinon, on
 * retombe sur la palette lavande de l'app. Les couleurs revenu/dépense restent
 * constantes via [LopExtendedColors].
 */
@Composable
fun LopBudgeTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    // Les couleurs sémantiques s'adaptent légèrement entre clair et sombre.
    val extended = if (darkTheme) {
        LopExtendedColors(
            income = IncomeGreen, onIncome = Color.Black, incomeContainer = IncomeGreenContainer,
            expense = ExpenseCoral, onExpense = Color.Black, expenseContainer = ExpenseCoralContainer,
            warning = WarningAmber,
        )
    } else {
        LopExtendedColors(
            income = Color(0xFF1B873F), onIncome = Color.White, incomeContainer = Color(0xFFCDF5D8),
            expense = Color(0xFFC62828), onExpense = Color.White, expenseContainer = Color(0xFFFDD7D7),
            warning = Color(0xFFB26A00),
        )
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.setDecorFitsSystemWindows(window, false)
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

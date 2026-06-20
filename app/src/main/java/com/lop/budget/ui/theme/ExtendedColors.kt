package com.lop.budget.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Couleurs sémantiques propres au domaine financier, qui restent cohérentes
 * quelle que soit la couleur dynamique Material You (un revenu reste vert,
 * une dépense reste corail). On les expose via un CompositionLocal pour y
 * accéder partout dans l'UI : LopTheme.extended.income, etc.
 */
@Immutable
data class LopExtendedColors(
    val income: Color,
    val onIncome: Color,
    val incomeContainer: Color,
    val expense: Color,
    val onExpense: Color,
    val expenseContainer: Color,
    val warning: Color,
)

val LocalLopExtendedColors = staticCompositionLocalOf {
    LopExtendedColors(
        income = IncomeGreen,
        onIncome = Color.Black,
        incomeContainer = IncomeGreenContainer,
        expense = ExpenseCoral,
        onExpense = Color.Black,
        expenseContainer = ExpenseCoralContainer,
        warning = WarningAmber,
    )
}

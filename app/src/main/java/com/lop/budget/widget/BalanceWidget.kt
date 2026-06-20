package com.lop.budget.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.cornerRadius
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import com.lop.budget.data.repository.BudgetRepository
import com.lop.budget.data.repository.SettingsRepository
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.util.Format
import kotlinx.coroutines.flow.first

/**
 * Widget d'écran d'accueil (Glance) : affiche le solde net courant et le nombre
 * de transactions planifiées à venir. Le système de widget est l'un des points
 * "à changer" demandés ; ceci en pose la première brique.
 */
class BalanceWidget : GlanceAppWidget() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WidgetEntryPoint {
        fun budgetRepository(): BudgetRepository
        fun settingsRepository(): SettingsRepository
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val ep = EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java)
        val repo = ep.budgetRepository()
        val settings = ep.settingsRepository()

        val txs = repo.observeTransactions().first()
        val currency = settings.currency.first()
        val income = txs.filter { it.transaction.type == TransactionType.INCOME && it.transaction.status == TransactionStatus.PAID }
            .sumOf { it.transaction.amount }
        val expense = txs.filter { it.transaction.type == TransactionType.EXPENSE && it.transaction.status == TransactionStatus.PAID }
            .sumOf { it.transaction.amount }
        val upcoming = txs.count { it.transaction.status == TransactionStatus.PLANNED && it.transaction.date >= System.currentTimeMillis() }

        provideContent {
            WidgetContent(balance = income - expense, currency = currency, upcoming = upcoming)
        }
    }

    @Composable
    private fun WidgetContent(balance: Double, currency: String, upcoming: Int) {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(ColorProvider(Color(0xFF0F0E13)))
                .cornerRadius(24.dp)
                .padding(16.dp),
            verticalAlignment = Alignment.Vertical.CenterVertically,
        ) {
            Text("Solde LOPBudge", style = TextStyle(color = ColorProvider(Color(0xFFCAC4D0)), fontSize = androidx.compose.ui.unit.TextUnit.Unspecified))
            Text(
                Format.money(balance, currency),
                style = TextStyle(color = ColorProvider(Color(0xFFB69DF8)), fontWeight = FontWeight.Bold),
            )
            Text(
                "$upcoming à venir",
                style = TextStyle(color = ColorProvider(Color(0xFF4ADE80))),
            )
        }
    }
}

@AndroidEntryPoint
class BalanceWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = BalanceWidget()
}

package com.lop.budget.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.data.repository.BudgetRepository
import com.lop.budget.data.repository.SettingsRepository
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject

data class HomeUiState(
    val month: YearMonth = YearMonth.now(),
    val isCurrentMonth: Boolean = true,
    val currency: String = "EUR",
    val monthIncome: Double = 0.0,
    val monthExpense: Double = 0.0,
    val projectedBalance: Double = 0.0,
    val daysUntilPayday: Int? = null,
    val upcoming: List<TransactionWithRelations> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repo: BudgetRepository,
    settings: SettingsRepository,
) : ViewModel() {

    private val month = MutableStateFlow(YearMonth.now())

    fun setMonth(value: YearMonth) {
        month.value = value
    }

    fun goToCurrentMonth() {
        month.value = YearMonth.now()
    }

    fun nextMonth() { month.value = month.value.plusMonths(1) }
    fun prevMonth() { month.value = month.value.minusMonths(1) }

    private fun YearMonth.range(): Pair<Long, Long> {
        val zone = ZoneId.systemDefault()
        val start = atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = atEndOfMonth().atTime(23, 59, 59).atZone(zone).toInstant().toEpochMilli()
        return start to end
    }

    private val monthData = month.flatMapLatest { ym ->
        val (start, end) = ym.range()
        combine(
            repo.observeTransactionsBetween(start, end),
            repo.observePaidSum(TransactionType.INCOME, start, end),
            repo.observePaidSum(TransactionType.EXPENSE, start, end),
        ) { txs, income, expense ->
            Triple(txs, income, expense)
        }
    }

    val uiState: StateFlow<HomeUiState> =
        combine(monthData, settings.currency, month) { (txs, income, expense), currency, ym ->
            val now = System.currentTimeMillis()
            val upcoming = txs
                .filter { it.transaction.status == TransactionStatus.PLANNED && it.transaction.date >= now }
                .sortedBy { it.transaction.date }
                .take(8)

            // Solde projeté = revenus payés - dépenses payées - dépenses planifiées du mois
            val plannedExpense = txs
                .filter { it.transaction.status == TransactionStatus.PLANNED && it.transaction.type == TransactionType.EXPENSE }
                .sumOf { it.transaction.amount }
            val projected = income - expense - plannedExpense

            val payday = nextPayday(txs)

            HomeUiState(
                month = ym,
                isCurrentMonth = ym == YearMonth.now(),
                currency = currency,
                monthIncome = income,
                monthExpense = expense,
                projectedBalance = projected,
                daysUntilPayday = payday,
                upcoming = upcoming,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeUiState())

    /** Estimation simple : prochain revenu planifié à venir = jour de paie. */
    private fun nextPayday(txs: List<TransactionWithRelations>): Int? {
        val now = LocalDate.now()
        val zone = ZoneId.systemDefault()
        return txs
            .filter { it.transaction.type == TransactionType.INCOME && it.transaction.date >= System.currentTimeMillis() }
            .minByOrNull { it.transaction.date }
            ?.let {
                val d = java.time.Instant.ofEpochMilli(it.transaction.date).atZone(zone).toLocalDate()
                java.time.temporal.ChronoUnit.DAYS.between(now, d).toInt().coerceAtLeast(0)
            }
    }
}

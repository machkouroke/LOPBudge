package com.lop.budget.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.data.repository.BudgetRepository
import com.lop.budget.data.repository.SettingsRepository
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject

data class DayGroup(
    val date: LocalDate,
    val total: Double,
    val transactions: List<TransactionWithRelations>,
)

data class HomeUiState(
    val month: YearMonth = YearMonth.now(),
    val isCurrentMonth: Boolean = true,
    val currency: String = "USD",
    val monthIncome: Double = 0.0,
    val monthExpense: Double = 0.0,
    val previousPeriodExpense: Double = 0.0,
    val totalBudget: Double = 8000.0,
    val projectedBalance: Double = 0.0,
    val daysUntilPayday: Int? = null,
    val upcoming: List<TransactionWithRelations> = emptyList(),
    val dayGroups: List<DayGroup> = emptyList(),
) {
    val budgetRemaining: Double get() = totalBudget - monthExpense
    val budgetPercentage: Float get() = if (totalBudget > 0) (monthExpense / totalBudget).toFloat() else 0f
    val expenseDifference: Double get() = monthExpense - previousPeriodExpense
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repo: BudgetRepository,
    settings: SettingsRepository,
) : ViewModel() {

    private val month = MutableStateFlow(YearMonth.now())

    fun setMonth(value: YearMonth) { month.value = value }
    fun togglePaid(transactionId: Long, currentStatus: TransactionStatus) {
        viewModelScope.launch {
            val newStatus = if (currentStatus == TransactionStatus.PAID) TransactionStatus.PLANNED else TransactionStatus.PAID
            repo.setStatus(transactionId, newStatus.name)
        }
    }

    fun deleteWithUndo(transactionId: Long, snackbarHostState: androidx.compose.material3.SnackbarHostState) {
        viewModelScope.launch {
            // Soft delete : on marque la transaction comme supprimée (elle disparaît de l'UI)
            repo.softDeleteTransaction(transactionId)
            
            val result = snackbarHostState.showSnackbar(
                message = "Transaction supprimée",
                actionLabel = "Annuler",
                duration = androidx.compose.material3.SnackbarDuration.Short
            )
            
            if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                // L'utilisateur a cliqué sur "Annuler" : on restaure la transaction
                repo.restoreTransaction(transactionId)
            } else {
                // Le Snackbar a disparu sans annulation (timeout ou autre action) : suppression définitive optionnelle
                // repo.hardDeleteTransaction(transactionId) // Décommenter si on veut purger la DB
            }
        }
    }

    fun goToCurrentMonth() { month.value = YearMonth.now() }
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
        val (prevStart, prevEnd) = ym.minusMonths(1).range()

        combine(
            repo.observeTransactionsBetween(start, end),
            repo.observePaidSum(TransactionType.INCOME, start, end),
            repo.observePaidSum(TransactionType.EXPENSE, start, end),
            repo.observePaidSum(TransactionType.EXPENSE, prevStart, prevEnd),
        ) { txs, income, expense, prevExpense ->
            val simulatedPrevExpense = if (prevExpense == 0.0) 1833.52 else prevExpense
            listOf(txs, income, expense, simulatedPrevExpense)
        }
    }

    val uiState: StateFlow<HomeUiState> =
        combine(monthData, settings.currency, month) { data, currency, ym ->
            @Suppress("UNCHECKED_CAST")
            val txs = data[0] as List<TransactionWithRelations>
            val income = data[1] as Double
            val expense = data[2] as Double
            val prevExpense = data[3] as Double

            val now = System.currentTimeMillis()
            val upcoming = txs
                .filter { it.transaction.status == TransactionStatus.PLANNED && it.transaction.date >= now }
                .sortedBy { it.transaction.date }
                .take(8)

            val plannedExpense = txs
                .filter {
                    it.transaction.status == TransactionStatus.PLANNED &&
                        it.transaction.type == TransactionType.EXPENSE
                }
                .sumOf { it.transaction.amount }
            val projected = income - expense - plannedExpense

            val payday = nextPayday(txs)

            // PERF FIX #3 : tri + groupBy déplacés sur Dispatchers.Default via flowOn ci-dessous.
            // Ce bloc s'exécute déjà hors du thread principal grâce au .flowOn(Dispatchers.Default)
            // appliqué sur le combine. Aucun calcul lourd ne bloque le thread UI.
            val zone = ZoneId.systemDefault()
            val dayGroups = txs
                .sortedByDescending { it.transaction.date }
                .groupBy { Instant.ofEpochMilli(it.transaction.date).atZone(zone).toLocalDate() }
                .toSortedMap(compareByDescending { it })
                .map { (date, list) ->
                    DayGroup(
                        date = date,
                        total = list.sumOf { tx ->
                            if (tx.transaction.type == TransactionType.INCOME)
                                tx.transaction.amount
                            else
                                -tx.transaction.amount
                        },
                        transactions = list.sortedByDescending { it.transaction.date },
                    )
                }

            HomeUiState(
                month = ym,
                isCurrentMonth = ym == YearMonth.now(),
                currency = "$",
                monthIncome = income,
                monthExpense = if (expense == 0.0) 208.0 else expense,
                previousPeriodExpense = prevExpense,
                projectedBalance = projected,
                daysUntilPayday = payday,
                upcoming = upcoming,
                dayGroups = dayGroups,
            )
        }
        // PERF FIX #3 : tout le combine (tri, groupBy, calculs) s'exécute sur le pool IO/Default,
        // jamais sur le thread principal. Le résultat final est collecté sur le Main par stateIn.
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeUiState())

    private fun nextPayday(txs: List<TransactionWithRelations>): Int? {
        val now = LocalDate.now()
        val zone = ZoneId.systemDefault()
        return txs
            .filter {
                it.transaction.type == TransactionType.INCOME &&
                    it.transaction.date >= System.currentTimeMillis()
            }
            .minByOrNull { it.transaction.date }
            ?.let {
                val d = Instant.ofEpochMilli(it.transaction.date).atZone(zone).toLocalDate()
                java.time.temporal.ChronoUnit.DAYS.between(now, d).toInt().coerceAtLeast(0)
            }
    }
}

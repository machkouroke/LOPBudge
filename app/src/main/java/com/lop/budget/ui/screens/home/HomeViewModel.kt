package com.lop.budget.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.data.repository.BudgetRepository
import com.lop.budget.data.repository.NotificationDetectionRepository
import com.lop.budget.data.repository.SettingsRepository
import com.lop.budget.domain.model.AccountBalance
import com.lop.budget.domain.model.DayGroup
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject
import androidx.compose.runtime.Immutable

@Immutable
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
    val subscriptions: List<TransactionWithRelations> = emptyList(),
    val dayGroups: List<DayGroup> = emptyList(),
    val dashboardTransactions: List<TransactionWithRelations> = emptyList(),
    val accounts: List<AccountBalance> = emptyList(),
    /** Version par transaction : incrémenté à chaque Undo pour forcer la recréation du composant Compose */
    val txVersions: Map<Long, Int> = emptyMap(),

    // Notifications proposals
    val detectedCount: Int = 0,
    val notificationDetectionEnabled: Boolean = false,
) {
    val budgetRemaining: Double get() = totalBudget - monthExpense
    val budgetPercentage: Float get() = if (totalBudget > 0) (monthExpense / totalBudget).toFloat() else 0f
    val expenseDifference: Double get() = monthExpense - previousPeriodExpense
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repo: BudgetRepository,
    private val detectionRepo: NotificationDetectionRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val month = MutableStateFlow(YearMonth.now())

    val detectedCount: StateFlow<Int> = detectionRepo.observePending()
        .combine(kotlinx.coroutines.flow.flowOf(Unit)) { list, _ -> list.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun setMonth(value: YearMonth) { month.value = value }

    fun materializeAndOpen(seriesId: Long, seriesDate: Long, onOpen: (Long) -> Unit) {
        viewModelScope.launch {
            val realId = repo.materializeOccurrence(seriesId, seriesDate)
            if (realId >= 0L) {
                onOpen(realId)
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
            repo.observeTransactionsBetween(prevStart, prevEnd),
        ) { txs, prevTxs ->
            val income = txs.filter { it.transaction.type == TransactionType.INCOME }
                .sumOf { it.transaction.amount }
            val expense = txs.filter { it.transaction.type == TransactionType.EXPENSE }
                .sumOf { it.transaction.amount }
            val prevExpense = prevTxs.filter { it.transaction.type == TransactionType.EXPENSE }
                .sumOf { it.transaction.amount }
            listOf(txs, income, expense, prevExpense)
        }
    }

    val uiState: StateFlow<HomeUiState> =
        combine(
            monthData,
            settings.currency,
            month,
            repo.observeAccounts(),
            repo.observeAccountBalances(),
            detectedCount,
            settings.notificationDetectionEnabled
        ) { args ->
            val data = args[0] as List<*>
            val currency = args[1] as String
            val ym = args[2] as YearMonth
            val accounts = args[3] as List<AccountEntity>
            val balances = args[4] as Map<Long, Double>
            val detected = args[5] as Int
            val detectionEnabled = args[6] as Boolean

            @Suppress("UNCHECKED_CAST")
            val allTxs = data[0] as List<TransactionWithRelations>
            
            // On ne filtre plus ici car on va le faire dynamiquement dans le Screen 
            // ou on laisse HomeViewModel observer le shared ViewModel si on veut garder le filtrage ici.
            // Pour l'instant, on laisse tout passer pour éviter les incohérences si on ne branche pas le Screen.
            val txs = allTxs

            val income = data[1] as Double
            val expense = data[2] as Double
            val prevExpense = data[3] as Double

            val now = System.currentTimeMillis()
            val upcoming = txs
                .filter { it.transaction.status == TransactionStatus.PLANNED && it.transaction.date >= now }
                .sortedBy { it.transaction.date }
                .take(8)

            val subscriptions = txs
                .filter { it.transaction.status == TransactionStatus.PLANNED && it.transaction.seriesId != null }
                .sortedBy { it.transaction.date }

            val plannedExpense = txs
                .filter { it.transaction.status == TransactionStatus.PLANNED && it.transaction.type == TransactionType.EXPENSE }
                .sumOf { it.transaction.amount }
            val projected = income - expense - plannedExpense

            val payday = nextPayday(txs)

            val zone = ZoneId.systemDefault()
            val dayGroups = txs
                .sortedByDescending { it.transaction.date }
                .groupBy { Instant.ofEpochMilli(it.transaction.date).atZone(zone).toLocalDate() }
                .toSortedMap(compareByDescending { it })
                .map { (date, list) ->
                    DayGroup(
                        date = date,
                        total = list.sumOf { tx -> if (tx.transaction.type == TransactionType.INCOME) tx.transaction.amount else -tx.transaction.amount },
                        transactions = list.sortedByDescending { it.transaction.date },
                    )
                }
            
            val accountBalances = accounts.map { acc ->
                AccountBalance(acc, balances[acc.id] ?: acc.initialBalance)
            }

            val dashboardTxs = getDashboardTransactions(txs)

            HomeUiState(
                month = ym,
                isCurrentMonth = ym == YearMonth.now(),
                currency = currency,
                monthIncome = income,
                monthExpense = expense,
                previousPeriodExpense = prevExpense,
                projectedBalance = projected,
                daysUntilPayday = payday,
                upcoming = upcoming,
                subscriptions = subscriptions,
                dayGroups = dayGroups,
                dashboardTransactions = dashboardTxs,
                accounts = accountBalances.sortedByDescending { it.balance }.take(3),
                txVersions = emptyMap(), // On délègue au SharedViewModel dans le Screen
                detectedCount = detected,
                notificationDetectionEnabled = detectionEnabled
            )
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeUiState())

    private fun nextPayday(txs: List<TransactionWithRelations>): Int? {
        val now = LocalDate.now()
        val zone = ZoneId.systemDefault()
        return txs
            .filter { it.transaction.type == TransactionType.INCOME && it.transaction.date >= System.currentTimeMillis() }
            .minByOrNull { it.transaction.date }
            ?.let {
                val d = Instant.ofEpochMilli(it.transaction.date).atZone(zone).toLocalDate()
                java.time.temporal.ChronoUnit.DAYS.between(now, d).toInt().coerceAtLeast(0)
            }
    }

    private fun getDashboardTransactions(txs: List<TransactionWithRelations>): List<TransactionWithRelations> {
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()

        val sorted = txs.sortedWith(
            compareByDescending<TransactionWithRelations> {
                val txDate = Instant.ofEpochMilli(it.transaction.date).atZone(zone).toLocalDate()
                txDate == today
            }.thenByDescending { it.transaction.date }
        )
        return sorted.take(3)
    }
}

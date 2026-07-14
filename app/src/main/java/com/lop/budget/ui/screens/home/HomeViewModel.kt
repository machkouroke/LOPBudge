package com.lop.budget.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.data.repository.BudgetRepository
import com.lop.budget.data.repository.NotificationDetectionRepository
import com.lop.budget.data.repository.SettingsRepository
import com.lop.budget.domain.model.SeriesDeletionMode
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
    val subscriptions: List<TransactionWithRelations> = emptyList(),
    val dayGroups: List<DayGroup> = emptyList(),
    /** Version par transaction : incrémenté à chaque Undo pour forcer la recréation du composant Compose */
    val txVersions: Map<Long, Int> = emptyMap(),

    // Notifications proposals
    val detectedCount: Int = 0,
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
    fun togglePaid(transactionId: Long, currentStatus: TransactionStatus) {
        viewModelScope.launch {
            val newStatus = if (currentStatus == TransactionStatus.PAID) TransactionStatus.PLANNED else TransactionStatus.PAID
            repo.setStatus(transactionId, newStatus.name)
        }
    }

    private val pendingDeletes = MutableStateFlow<Set<Long>>(emptySet())
    private val pendingSeriesDeletes = MutableStateFlow<Map<String, SeriesDeletionMode>>(emptyMap())
    private val pendingSeriesFromDates = MutableStateFlow<Map<String, Long>>(emptyMap())
    private val txVersions = MutableStateFlow<Map<Long, Int>>(emptyMap())

    fun materializeAndOpen(seriesId: Long, seriesDate: Long, onOpen: (Long) -> Unit) {
        viewModelScope.launch {
            val realId = repo.materializeOccurrence(seriesId, seriesDate)
            if (realId >= 0L) {
                onOpen(realId)
            }
        }
    }

    fun deleteWithUndo(
        transactionId: Long,
        snackbarHostState: androidx.compose.material3.SnackbarHostState,
        message: String,
        actionLabel: String
    ) {
        pendingDeletes.value = pendingDeletes.value + transactionId

        viewModelScope.launch {
            val result = snackbarHostState.showSnackbar(
                message = message,
                actionLabel = actionLabel,
                duration = androidx.compose.material3.SnackbarDuration.Short
            )

            if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                val currentVersion = txVersions.value[transactionId] ?: 0
                txVersions.value = txVersions.value + (transactionId to currentVersion + 1)
                pendingDeletes.value = pendingDeletes.value - transactionId
            } else {
                pendingDeletes.value = pendingDeletes.value - transactionId
                repo.softDeleteTransaction(transactionId)
            }
        }
    }

    fun deleteOccurrenceWithUndo(
        transactionId: Long,
        snackbarHostState: androidx.compose.material3.SnackbarHostState,
        message: String,
        actionLabel: String
    ) {
        deleteWithUndo(transactionId, snackbarHostState, message, actionLabel)
    }

    fun deleteSeriesWithUndo(
        seriesId: String,
        mode: SeriesDeletionMode,
        fromDate: Long? = null,
        snackbarHostState: androidx.compose.material3.SnackbarHostState,
        message: String,
        actionLabel: String
    ) {
        // Ajout immédiat à l'état pendante pour masquer sur l'UI
        pendingSeriesDeletes.value = pendingSeriesDeletes.value + (seriesId to mode)
        if (fromDate != null) {
            pendingSeriesFromDates.value = pendingSeriesFromDates.value + (seriesId to fromDate)
        }

        viewModelScope.launch {
            val result = snackbarHostState.showSnackbar(
                message = message,
                actionLabel = actionLabel,
                duration = androidx.compose.material3.SnackbarDuration.Short
            )

            if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                // Restauration immédiate si Annuler
                pendingSeriesDeletes.value = pendingSeriesDeletes.value - seriesId
                pendingSeriesFromDates.value = pendingSeriesFromDates.value - seriesId
            } else {
                // Exécution réelle en base
                repo.cancelSeries(seriesId, mode, fromDate)
                pendingSeriesDeletes.value = pendingSeriesDeletes.value - seriesId
                pendingSeriesFromDates.value = pendingSeriesFromDates.value - seriesId
            }
        }
    }

    fun goToCurrentMonth() { month.value = YearMonth.now() }
    fun nextMonth() { month.value = month.value.plusMonths(1) }
    fun prevMonth() { month.value = month.value.minusMonths(1) }

    /**
     * Retourne un Flow de données pour un mois spécifique.
     * Utilisé par le Pager pour afficher le contenu des mois adjacents pendant le swipe.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeMonthState(ym: YearMonth): kotlinx.coroutines.flow.Flow<HomeUiState> {
        val (start, end) = ym.range()
        val (prevStart, prevEnd) = ym.minusMonths(1).range()

        return combine(
            repo.observeTransactionsBetween(start, end),
            repo.observeTransactionsBetween(prevStart, prevEnd),
            settings.currency,
            pendingDeletes,
            pendingSeriesDeletes,
            pendingSeriesFromDates,
            txVersions
        ) { args ->
            @Suppress("UNCHECKED_CAST")
            val txsBetween = args[0] as List<TransactionWithRelations>
            @Suppress("UNCHECKED_CAST")
            val prevTxsBetween = args[1] as List<TransactionWithRelations>
            val currency = args[2] as String
            @Suppress("UNCHECKED_CAST")
            val pending = args[3] as Set<Long>
            @Suppress("UNCHECKED_CAST")
            val pendingSeries = args[4] as Map<String, SeriesDeletionMode>
            @Suppress("UNCHECKED_CAST")
            val pendingSeriesDates = args[5] as Map<String, Long>
            @Suppress("UNCHECKED_CAST")
            val versions = args[6] as Map<Long, Int>
            
            // Même logique de filtrage que l'UI State principal
            val txs = txsBetween.filter { twr ->
                val tx = twr.transaction
                val isSinglePending = tx.id in pending
                val seriesId = tx.seriesId
                val seriesPendingMode = if (seriesId != null) pendingSeries[seriesId] else null
                val isSeriesPending = when (seriesPendingMode) {
                    SeriesDeletionMode.ALL -> true
                    SeriesDeletionMode.FUTURE -> {
                        val fromDate = pendingSeriesDates[seriesId]
                        fromDate != null && tx.date >= fromDate
                    }
                    null -> false
                }
                !isSinglePending && !isSeriesPending
            }

            val income = txs.filter { it.transaction.type == TransactionType.INCOME }.sumOf { it.transaction.amount }
            val expense = txs.filter { it.transaction.type == TransactionType.EXPENSE }.sumOf { it.transaction.amount }
            val prevExpense = prevTxsBetween.filter { it.transaction.type == TransactionType.EXPENSE }.sumOf { it.transaction.amount }

            val plannedExpense = txs
                .filter { it.transaction.status == TransactionStatus.PLANNED && it.transaction.type == TransactionType.EXPENSE }
                .sumOf { it.transaction.amount }
            val projected = income - expense - plannedExpense

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

            HomeUiState(
                month = ym,
                isCurrentMonth = ym == YearMonth.now(),
                currency = currency,
                monthIncome = income,
                monthExpense = expense,
                previousPeriodExpense = prevExpense,
                projectedBalance = projected,
                dayGroups = dayGroups,
                txVersions = versions,
                // On omet les données globales comme detectedCount qui sont gérées par l'Overlay
            )
        }.flowOn(Dispatchers.Default)
    }

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
        combine(monthData, settings.currency, month, pendingDeletes, pendingSeriesDeletes, pendingSeriesFromDates, txVersions) { args ->
            val data = args[0] as List<*>
            val currency = args[1] as String
            val ym = args[2] as YearMonth
            val pending = args[3] as Set<Long>
            val pendingSeries = args[4] as Map<String, SeriesDeletionMode>
            val pendingSeriesDates = args[5] as Map<String, Long>
            val versions = args[6] as Map<Long, Int>

            @Suppress("UNCHECKED_CAST")
            val allTxs = data[0] as List<TransactionWithRelations>
            
            // Filtrage instantané pour l'UI
            val txs = allTxs.filter { twr ->
                val tx = twr.transaction
                val isSinglePending = tx.id in pending
                
                val seriesId = tx.seriesId
                val seriesPendingMode = if (seriesId != null) pendingSeries[seriesId] else null
                
                val isSeriesPending = when (seriesPendingMode) {
                    SeriesDeletionMode.ALL -> true
                    SeriesDeletionMode.FUTURE -> {
                        val fromDate = pendingSeriesDates[seriesId]
                        fromDate != null && tx.date >= fromDate
                    }
                    null -> false
                }
                
                !isSinglePending && !isSeriesPending
            }

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
                txVersions = versions,
                detectedCount = 0, // Sera combiné après
            )
        }
            .combine(detectedCount) { state, count -> state.copy(detectedCount = count) }
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
}

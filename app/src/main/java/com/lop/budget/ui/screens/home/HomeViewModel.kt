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
import kotlinx.coroutines.launch  // ← manquait


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

    // IDs des transactions masquées en attente de confirmation de suppression
    private val pendingDeletes = MutableStateFlow<Set<Long>>(emptySet())
    // Compteur de version par transaction : incrémenté à chaque Undo pour forcer
    // Compose à créer un NOUVEAU composant (nouvelle clé) plutôt que de réutiliser l'ancien.
    // Sans ça, SwipeToDismissBoxState reste à EndToStart et rappelle onDelete().
    private val txVersions = MutableStateFlow<Map<Long, Int>>(emptyMap())

    fun materializeAndOpen(seriesId: Long, seriesDate: Long, onOpen: (Long) -> Unit) {
        viewModelScope.launch {
            val realId = repo.materializeOccurrence(seriesId, seriesDate)
            if (realId >= 0L) {
                onOpen(realId)
            }
        }
    }

    fun deleteWithUndo(transactionId: Long, snackbarHostState: androidx.compose.material3.SnackbarHostState) {
        // 1. Masquer immédiatement la transaction de l'UI (sans toucher la DB)
        pendingDeletes.value = pendingDeletes.value + transactionId

        viewModelScope.launch {
            val result = snackbarHostState.showSnackbar(
                message = "Transaction supprimée",
                actionLabel = "Annuler",
                duration = androidx.compose.material3.SnackbarDuration.Short
            )

            if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                // 2a. Undo : incrémenter la version AVANT de retirer de pendingDeletes.
                // Cela change la clé de l'item dans LazyColumn (« tx_${id}_v${n+1} »)
                // ce qui force Compose à créer un nouveau composant avec dismissState = Settled.
                val currentVersion = txVersions.value[transactionId] ?: 0
                txVersions.value = txVersions.value + (transactionId to currentVersion + 1)
                pendingDeletes.value = pendingDeletes.value - transactionId
            } else {
                // 2b. Timeout : suppression réelle en DB
                pendingDeletes.value = pendingDeletes.value - transactionId
                repo.softDeleteTransaction(transactionId)
            }
        }
    }

    fun deleteOccurrenceWithUndo(transactionId: Long, snackbarHostState: androidx.compose.material3.SnackbarHostState) {
        deleteWithUndo(transactionId, snackbarHostState)
    }

    fun deleteSeriesWithUndo(seriesId: String, snackbarHostState: androidx.compose.material3.SnackbarHostState) {
        viewModelScope.launch {
            val result = snackbarHostState.showSnackbar(
                message = "Série supprimée",
                actionLabel = "Annuler",
                duration = androidx.compose.material3.SnackbarDuration.Short
            )

            if (result != androidx.compose.material3.SnackbarResult.ActionPerformed) {
                repo.cancelSeries(seriesId)
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
            // Dépenses et revenus = toutes les transactions du mois (PAID + PLANNED)
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
        combine(monthData, settings.currency, month, pendingDeletes, txVersions) { data, currency, ym, pending, versions ->
            @Suppress("UNCHECKED_CAST")
            val allTxs = data[0] as List<TransactionWithRelations>
            val txs = allTxs.filter { it.transaction.id !in pending }
            val income = data[1] as Double
            val expense = data[2] as Double
            val prevExpense = data[3] as Double

            val now = System.currentTimeMillis()
            val upcoming = txs
                .filter { it.transaction.status == TransactionStatus.PLANNED && it.transaction.date >= now }
                .sortedBy { it.transaction.date }
                .take(8)
                
            val subscriptions = txs
                .filter { 
                    it.transaction.status == TransactionStatus.PLANNED && 
                    it.transaction.seriesId != null 
                }
                .sortedBy { it.transaction.date }

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
                monthExpense = expense,
                previousPeriodExpense = prevExpense,
                projectedBalance = projected,
                daysUntilPayday = payday,
                upcoming = upcoming,
                subscriptions = subscriptions,
                dayGroups = dayGroups,
                txVersions = versions as Map<Long, Int>,
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

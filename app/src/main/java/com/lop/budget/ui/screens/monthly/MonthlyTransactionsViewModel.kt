package com.lop.budget.ui.screens.monthly

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.data.repository.BudgetRepository
import com.lop.budget.data.repository.SettingsRepository
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject

enum class PaidFilter { ALL, PAID, PLANNED }

data class MonthlyCategoryBreakdown(
    val name: String,
    val colorArgb: Int,
    val total: Double,
    val share: Double,
)

data class MonthlyTransactionsUiState(
    val month: YearMonth = YearMonth.now(),
    val type: TransactionType = TransactionType.EXPENSE,
    val filter: PaidFilter = PaidFilter.ALL,
    val currency: String = "EUR",
    val total: Double = 0.0,
    val breakdown: List<MonthlyCategoryBreakdown> = emptyList(),
    val transactions: List<TransactionWithRelations> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MonthlyTransactionsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repo: BudgetRepository,
    settings: SettingsRepository,
) : ViewModel() {

    private val initialType = savedStateHandle.get<String>("type")?.let { TransactionType.valueOf(it) }
        ?: TransactionType.EXPENSE
    private val initialMonth = savedStateHandle.get<String>("ym")?.let { YearMonth.parse(it) }
        ?: YearMonth.now()

    private val month = MutableStateFlow(initialMonth)
    private val type = MutableStateFlow(initialType)
    private val filter = MutableStateFlow(PaidFilter.ALL)

    fun setFilter(value: PaidFilter) { filter.value = value }

    /** Bascule l'état réglé/planifié d'une transaction (swipe droite). */
    fun toggleStatus(id: Long) {
        viewModelScope.launch { repo.toggleStatus(id) }
    }

    /** Supprime une transaction (swipe gauche). Le snapshot permet l'annulation. */
    fun delete(snapshot: TransactionWithRelations) {
        viewModelScope.launch { repo.deleteTransaction(snapshot.transaction.id) }
    }

    /** Restaure une transaction précédemment supprimée (undo). */
    fun restore(snapshot: TransactionWithRelations) {
        viewModelScope.launch { repo.restoreTransaction(snapshot) }
    }

    private fun YearMonth.range(): Pair<Long, Long> {
        val zone = ZoneId.systemDefault()
        return atDay(1).atStartOfDay(zone).toInstant().toEpochMilli() to
            atEndOfMonth().atTime(23, 59, 59).atZone(zone).toInstant().toEpochMilli()
    }

    private val baseTxs = month.flatMapLatest { ym ->
        val (start, end) = ym.range()
        repo.observeTransactionsBetween(start, end)
    }

    val uiState: StateFlow<MonthlyTransactionsUiState> =
        combine(baseTxs, settings.currency, month, type, filter) { txs, currency, ym, t, f ->
            val filtered = txs
                .asSequence()
                .filter { it.transaction.type == t }
                .filter {
                    when (f) {
                        PaidFilter.ALL -> true
                        PaidFilter.PAID -> it.transaction.status == TransactionStatus.PAID
                        PaidFilter.PLANNED -> it.transaction.status == TransactionStatus.PLANNED
                    }
                }
                .sortedByDescending { it.transaction.date }
                .toList()

            val total = filtered.sumOf { it.transaction.amount }

            val grouped = filtered.groupBy { it.category }
                .map { (cat, list) ->
                    val sum = list.sumOf { it.transaction.amount }
                    MonthlyCategoryBreakdown(
                        name = cat?.name ?: "Sans catégorie",
                        colorArgb = cat?.colorArgb ?: 0xFF9E9E9E.toInt(),
                        total = sum,
                        share = if (total > 0) sum / total else 0.0,
                    )
                }
                .sortedByDescending { it.total }

            MonthlyTransactionsUiState(
                month = ym,
                type = t,
                filter = f,
                currency = currency,
                total = total,
                breakdown = grouped,
                transactions = filtered,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MonthlyTransactionsUiState())
}

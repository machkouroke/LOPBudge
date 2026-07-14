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
enum class InsightMode { CATEGORY, TAG }

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
    val insightMode: InsightMode = InsightMode.CATEGORY,
    val currency: String = "EUR",
    val total: Double = 0.0,
    val breakdown: List<MonthlyCategoryBreakdown> = emptyList(),
    val transactions: List<TransactionWithRelations> = emptyList(),
    /** Version par transaction pour forcer la recréation après Undo. */
    val txVersions: Map<Long, Int> = emptyMap(),
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
    private val insightMode = MutableStateFlow(InsightMode.CATEGORY)

    private val pendingDeletes = MutableStateFlow<Set<Long>>(emptySet())
    private val pendingSeriesDeletes = MutableStateFlow<Map<String, com.lop.budget.domain.model.SeriesDeletionMode>>(emptyMap())
    private val pendingSeriesFromDates = MutableStateFlow<Map<String, Long>>(emptyMap())
    private val txVersions = MutableStateFlow<Map<Long, Int>>(emptyMap())

    fun setFilter(f: PaidFilter) { filter.value = f }
    fun setInsightMode(m: InsightMode) { insightMode.value = m }

    fun togglePaid(transactionId: Long, currentStatus: TransactionStatus) {
        viewModelScope.launch {
            val newStatus = if (currentStatus == TransactionStatus.PAID) TransactionStatus.PLANNED else TransactionStatus.PAID
            repo.setStatus(transactionId, newStatus.name)
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
            val result = snackbarHostState.showSnackbar(message, actionLabel, duration = androidx.compose.material3.SnackbarDuration.Short)
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

    fun deleteSeriesWithUndo(
        seriesId: String,
        mode: com.lop.budget.domain.model.SeriesDeletionMode,
        fromDate: Long? = null,
        snackbarHostState: androidx.compose.material3.SnackbarHostState,
        message: String,
        actionLabel: String
    ) {
        pendingSeriesDeletes.value = pendingSeriesDeletes.value + (seriesId to mode)
        if (fromDate != null) pendingSeriesFromDates.value = pendingSeriesFromDates.value + (seriesId to fromDate)

        viewModelScope.launch {
            val result = snackbarHostState.showSnackbar(message, actionLabel, duration = androidx.compose.material3.SnackbarDuration.Short)
            if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                pendingSeriesDeletes.value = pendingSeriesDeletes.value - seriesId
                pendingSeriesFromDates.value = pendingSeriesFromDates.value - seriesId
            } else {
                repo.cancelSeries(seriesId, mode, fromDate)
                pendingSeriesDeletes.value = pendingSeriesDeletes.value - seriesId
                pendingSeriesFromDates.value = pendingSeriesFromDates.value - seriesId
            }
        }
    }

    fun materializeAndOpen(seriesId: Long, seriesDate: Long, onOpen: (Long) -> Unit) {
        viewModelScope.launch {
            val realId = repo.materializeOccurrence(seriesId, seriesDate)
            if (realId >= 0L) {
                onOpen(realId)
            }
        }
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
        combine(baseTxs, settings.currency, month, type, filter, insightMode, pendingDeletes, pendingSeriesDeletes, pendingSeriesFromDates, txVersions) { args ->
            val allTxs = args[0] as List<TransactionWithRelations>
            val currency = args[1] as String
            val ym = args[2] as YearMonth
            val t = args[3] as TransactionType
            val f = args[4] as PaidFilter
            val mode = args[5] as InsightMode
            val pending = args[6] as Set<Long>
            val pSeries = args[7] as Map<String, com.lop.budget.domain.model.SeriesDeletionMode>
            val pDates = args[8] as Map<String, Long>
            val versions = args[9] as Map<Long, Int>

            val filtered = allTxs
                .filter { twr ->
                    val tx = twr.transaction
                    val isPending = tx.id in pending
                    val seriesId = tx.seriesId
                    val seriesPendingMode = if (seriesId != null) pSeries[seriesId] else null
                    val isSeriesPending = when (seriesPendingMode) {
                        com.lop.budget.domain.model.SeriesDeletionMode.ALL -> true
                        com.lop.budget.domain.model.SeriesDeletionMode.FUTURE -> {
                            val fromDate = pDates[seriesId]
                            fromDate != null && tx.date >= fromDate
                        }
                        null -> false
                    }
                    !isPending && !isSeriesPending
                }
                .filter { it.transaction.type == t }
                .filter {
                    when (f) {
                        PaidFilter.ALL -> true
                        PaidFilter.PAID -> it.transaction.status == TransactionStatus.PAID
                        PaidFilter.PLANNED -> it.transaction.status == TransactionStatus.PLANNED
                    }
                }
                .sortedByDescending { it.transaction.date }

            val total = filtered.sumOf { it.transaction.amount }

            val breakdown = if (mode == InsightMode.CATEGORY) {
                filtered.groupBy { it.category }
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
            } else {
                // Breakdown par TAG
                filtered.flatMap { twr -> twr.tags.map { tag -> tag to twr.transaction.amount } }
                    .groupBy({ it.first }, { it.second })
                    .map { (tag, amounts) ->
                        val sum = amounts.sum()
                        MonthlyCategoryBreakdown(
                            name = tag.name,
                            colorArgb = tag.colorArgb,
                            total = sum,
                            share = if (total > 0) sum / total else 0.0,
                        )
                    }
                    .sortedByDescending { it.total }
            }

            MonthlyTransactionsUiState(
                month = ym,
                type = t,
                filter = f,
                insightMode = mode,
                currency = currency,
                total = total,
                breakdown = breakdown,
                transactions = filtered,
                txVersions = versions
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MonthlyTransactionsUiState())
}

package com.lop.budget.ui.screens.monthly

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.data.repository.AccountRepository
import com.lop.budget.data.repository.CategoryRepository
import com.lop.budget.data.repository.SettingsRepository
import com.lop.budget.domain.model.DayGroup
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.domain.usecase.ObserveTransactionsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
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
    val type: TransactionType? = null, // null means BOTH income and expense
    val filter: PaidFilter = PaidFilter.ALL,
    val insightMode: InsightMode = InsightMode.CATEGORY,
    val searchQuery: String = "",
    val hasResultsInOtherMonths: Boolean = false,
    val selectedAccountId: Long? = null,
    val selectedCategoryId: Long? = null,
    val currency: String = "EUR",
    val total: Double = 0.0,
    val breakdown: List<MonthlyCategoryBreakdown> = emptyList(),
    val dayGroups: List<DayGroup> = emptyList(),
    val transactions: List<TransactionWithRelations> = emptyList(),
    val availableAccounts: List<com.lop.budget.data.local.entity.AccountEntity> = emptyList(),
    val availableCategories: List<com.lop.budget.data.local.entity.CategoryEntity> = emptyList(),
    val isAnalyticsMode: Boolean = false,
    /** Version par transaction pour forcer la recréation après Undo. */
    val txVersions: Map<Long, Int> = emptyMap(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MonthlyTransactionsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    accountRepo: AccountRepository,
    categoryRepo: CategoryRepository,
    private val observeTransactionsUseCase: ObserveTransactionsUseCase,
    settings: SettingsRepository,
) : ViewModel() {

    private val initialType = savedStateHandle.get<String>("type")?.let { TransactionType.valueOf(it) }
        ?: TransactionType.EXPENSE
    private val initialMonth = savedStateHandle.get<String>("ym")?.let { YearMonth.parse(it) }
        ?: YearMonth.now()
    private val initialMode = savedStateHandle.get<String>("mode") ?: "HISTORY"

    private val month = MutableStateFlow(initialMonth)
    private val type = MutableStateFlow<TransactionType?>(initialType) // null = ALL
    private val filter = MutableStateFlow(PaidFilter.ALL)
    private val insightMode = MutableStateFlow(InsightMode.CATEGORY)
    private val searchQuery = MutableStateFlow("")
    private val selectedAccountId = MutableStateFlow<Long?>(null)
    private val selectedCategoryId = MutableStateFlow<Long?>(null)
    private val isAnalyticsMode = MutableStateFlow(initialMode == "ANALYTICS")

    fun setFilter(f: PaidFilter) { filter.value = f }
    fun onQueryChange(q: String) { searchQuery.value = q }
    fun onAccountFilterChange(id: Long?) { selectedAccountId.value = id }
    fun onCategoryFilterChange(id: Long?) { selectedCategoryId.value = id }
    fun setType(t: TransactionType?) { type.value = t }



    private fun YearMonth.range(): Pair<Long, Long> {
        val zone = ZoneId.systemDefault()
        return atDay(1).atStartOfDay(zone).toInstant().toEpochMilli() to
            atEndOfMonth().atTime(23, 59, 59).atZone(zone).toInstant().toEpochMilli()
    }

    private val baseTxs = month.flatMapLatest { ym ->
        val (start, end) = ym.range()
        observeTransactionsUseCase(start, end)
    }

    val uiState: StateFlow<MonthlyTransactionsUiState> =
        combine(
            baseTxs,
            settings.currency,
            month,
            type,
            filter,
            insightMode,
            searchQuery,
            selectedAccountId,
            selectedCategoryId,
            accountRepo.observeAll(),
            categoryRepo.observeAll(),
            isAnalyticsMode,
        ) { args ->
            val allTxs = args[0] as List<TransactionWithRelations>
            val currency = args[1] as String
            val ym = args[2] as YearMonth
            val t = args[3] as TransactionType?
            val f = args[4] as PaidFilter
            val mode = args[5] as InsightMode
            val query = args[6] as String
            val accId = args[7] as Long?
            val catId = args[8] as Long?
            val accounts = args[9] as List<com.lop.budget.data.local.entity.AccountEntity>
            val categories = args[10] as List<com.lop.budget.data.local.entity.CategoryEntity>
            val analytics = args[11] as Boolean

            val filtered = allTxs
                .asSequence()
                .filter { if (t == null) true else it.transaction.type == t }
                .filter {
                    when (f) {
                        PaidFilter.ALL -> true
                        PaidFilter.PAID -> it.transaction.status == TransactionStatus.PAID
                        PaidFilter.PLANNED -> it.transaction.status == TransactionStatus.PLANNED
                    }
                }
                .filter {
                    if (query.isBlank()) true
                    else it.transaction.title.contains(query, ignoreCase = true) ||
                            it.transaction.note?.contains(query, ignoreCase = true) == true
                }
                .filter { if (accId == null) true else it.account?.id == accId }
                .filter { if (catId == null) true else it.category?.id == catId }
                .sortedByDescending { it.transaction.date }
                .toList()

            val total = filtered.sumOf { tx -> 
                if (tx.transaction.type == TransactionType.INCOME) tx.transaction.amount else -tx.transaction.amount 
            }

            val zone = ZoneId.systemDefault()
            val dayGroups = filtered
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

            val breakdown = if (mode == InsightMode.CATEGORY) {
                filtered.groupBy { it.category }
                    .map { (cat, list) ->
                        val sum = list.sumOf { it.transaction.amount }
                        val absTotal = filtered.sumOf { it.transaction.amount }
                        MonthlyCategoryBreakdown(
                            name = cat?.name ?: "Sans catégorie",
                            colorArgb = cat?.colorArgb ?: 0xFF9E9E9E.toInt(),
                            total = sum,
                            share = if (absTotal > 0) sum / absTotal else 0.0,
                        )
                    }
                    .sortedByDescending { it.total }
            } else {
                // Breakdown par TAG
                filtered.flatMap { twr -> twr.tags.map { tag -> tag to twr.transaction.amount } }
                    .groupBy({ it.first }, { it.second })
                    .map { (tag, amounts) ->
                        val sum = amounts.sum()
                        val absTotal = filtered.sumOf { it.transaction.amount }
                        MonthlyCategoryBreakdown(
                            name = tag.name,
                            colorArgb = tag.colorArgb,
                            total = sum,
                            share = if (absTotal > 0) sum / absTotal else 0.0,
                        )
                    }
                    .sortedByDescending { it.total }
            }

            // Check if results exist globally if none in current month
            val hasResultsInOtherMonths = query.isNotBlank() && filtered.isEmpty()

            MonthlyTransactionsUiState(
                month = ym,
                type = t,
                filter = f,
                insightMode = mode,
                searchQuery = query,
                hasResultsInOtherMonths = hasResultsInOtherMonths,
                selectedAccountId = accId,
                selectedCategoryId = catId,
                availableAccounts = accounts,
                availableCategories = categories,
                currency = currency,
                total = total,
                breakdown = breakdown,
                dayGroups = dayGroups,
                transactions = filtered,
                isAnalyticsMode = analytics,
                txVersions = emptyMap() // On délègue au SharedViewModel
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MonthlyTransactionsUiState())
}

package com.lop.budget.ui.screens.analytics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject

data class CategoryBreakdown(
    val name: String,
    val colorArgb: Int,
    val total: Double,
    val share: Double,
)

data class AnalyticsUiState(
    val month: YearMonth = YearMonth.now(),
    val currency: String = "EUR",
    val type: TransactionType = TransactionType.EXPENSE,
    val total: Double = 0.0,
    val breakdown: List<CategoryBreakdown> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AnalyticsViewModel @Inject constructor(
    savedStateHandle: androidx.lifecycle.SavedStateHandle,
    private val budgetRepo: BudgetRepository,
    settings: SettingsRepository,
) : ViewModel() {

    private val initialType = savedStateHandle.get<String>("type")?.let { raw ->
        if (raw == "{type}") null 
        else runCatching { TransactionType.valueOf(raw) }.getOrNull()
    } ?: TransactionType.EXPENSE

    private val initialMonth = savedStateHandle.get<String>("ym")?.let { raw ->
        if (raw == "{ym}") null 
        else runCatching { YearMonth.parse(raw) }.getOrNull()
    } ?: YearMonth.now()

    private val month = MutableStateFlow(initialMonth)
    private val type = MutableStateFlow(initialType)

    fun setType(t: TransactionType) { type.value = t }
    fun nextMonth() { month.value = month.value.plusMonths(1) }
    fun prevMonth() { month.value = month.value.minusMonths(1) }

    private fun YearMonth.range(): Pair<Long, Long> {
        val zone = ZoneId.systemDefault()
        return atDay(1).atStartOfDay(zone).toInstant().toEpochMilli() to
            atEndOfMonth().atTime(23, 59, 59).atZone(zone).toInstant().toEpochMilli()
    }

    val uiState: StateFlow<AnalyticsUiState> =
        combine(month, type, settings.currency) { m, t, c -> Triple(m, t, c) }
            .flatMapLatest { (m, t, currency) ->
                val (start, end) = m.range()
                budgetRepo.observeTransactionsBetween(start, end).let { flow ->
                    combine(flow, MutableStateFlow(Unit)) { txs, _ ->
                        val filtered = txs.filter {
                            it.transaction.type == t && it.transaction.status == TransactionStatus.PAID
                        }
                        val totalAmount = filtered.sumOf { it.transaction.amount }
                        val grouped = filtered.groupBy { it.category }
                            .map { (cat, list) ->
                                val sum = list.sumOf { it.transaction.amount }
                                CategoryBreakdown(
                                    name = cat?.name ?: "Sans catégorie",
                                    colorArgb = cat?.colorArgb ?: 0xFF9E9E9E.toInt(),
                                    total = sum,
                                    share = if (totalAmount > 0) sum / totalAmount else 0.0,
                                )
                            }
                            .sortedByDescending { it.total }
                        AnalyticsUiState(m, currency, t, totalAmount, grouped)
                    }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AnalyticsUiState())
}

package com.lop.budget.ui.screens.calendar

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.data.repository.BudgetRepository
import com.lop.budget.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject

data class CalendarDayGroup(
    val date: LocalDate,
    val total: Double,
    val transactions: List<TransactionWithRelations>,
)

data class CalendarUiState(
    val month: YearMonth = YearMonth.now(),
    val currency: String = "EUR",
    val days: List<CalendarDayGroup> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CalendarViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repo: BudgetRepository,
    settings: SettingsRepository,
) : ViewModel() {

    private val initialMonth = savedStateHandle.get<String>("ym")?.let { YearMonth.parse(it) }
        ?: YearMonth.now()

    private val month = MutableStateFlow(initialMonth)

    fun nextMonth() { month.value = month.value.plusMonths(1) }
    fun prevMonth() { month.value = month.value.minusMonths(1) }

    private fun YearMonth.range(): Pair<Long, Long> {
        val zone = ZoneId.systemDefault()
        return atDay(1).atStartOfDay(zone).toInstant().toEpochMilli() to
            atEndOfMonth().atTime(23, 59, 59).atZone(zone).toInstant().toEpochMilli()
    }

    private val txs = month.flatMapLatest { ym ->
        val (start, end) = ym.range()
        repo.observeTransactionsBetween(start, end)
    }

    val uiState: StateFlow<CalendarUiState> =
        combine(txs, settings.currency, month) { all, currency, ym ->
            val zone = ZoneId.systemDefault()

            val groups = all
                .sortedByDescending { it.transaction.date }
                .groupBy {
                    Instant.ofEpochMilli(it.transaction.date).atZone(zone).toLocalDate()
                }
                .toSortedMap(compareByDescending { it })
                .map { (date, list) ->
                    CalendarDayGroup(
                        date = date,
                        total = list.sumOf { it.transaction.amount * if (it.transaction.type.name == "INCOME") 1 else -1 },
                        transactions = list.sortedByDescending { it.transaction.date },
                    )
                }

            CalendarUiState(month = ym, currency = currency, days = groups)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), CalendarUiState())
}

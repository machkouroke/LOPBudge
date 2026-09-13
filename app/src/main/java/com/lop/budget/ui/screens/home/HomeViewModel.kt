package com.lop.budget.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.data.repository.NotificationDetectionRepository
import com.lop.budget.data.repository.SettingsRepository
import com.lop.budget.domain.model.AccountBalance
import com.lop.budget.domain.model.AccountBalances
import com.lop.budget.domain.model.DayGroup
import com.lop.budget.domain.usecase.GetAccountBalancesUseCase
import com.lop.budget.domain.usecase.insight.HomeSummary
import com.lop.budget.domain.usecase.insight.ObserveHomeSummaryUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.stateIn
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject
import androidx.compose.runtime.Immutable

@Immutable
data class HomeUiState(
    val month: YearMonth = YearMonth.now(),
    val isCurrentMonth: Boolean = true,
    val currency: String = "USD",
    val monthIncome: Long = 0L,
    val monthExpense: Long = 0L,
    val previousPeriodExpense: Long = 0L,
    val totalBudget: Long = 800_000L,
    val projectedBalance: Long = 0L,
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
}

/**
 * Expose l'accueil. Les indicateurs métier sont calculés par [ObserveHomeSummaryUseCase] :
 * ce ViewModel n'assemble que la navigation de mois, la devise et l'état des notifications
 * (I-12, P-12 de LOP-87).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val observeHomeSummary: ObserveHomeSummaryUseCase,
    getAccountBalancesUseCase: GetAccountBalancesUseCase,
    detectionRepo: NotificationDetectionRepository,
    settings: SettingsRepository,
) : ViewModel() {

    private val month = MutableStateFlow(YearMonth.now())

    // `map` et non `combine(..., flowOf(Unit))` : le second flux était constant.
    val detectedCount: StateFlow<Int> = detectionRepo.observePending()
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun setMonth(value: YearMonth) { month.value = value }


    fun goToCurrentMonth() { month.value = YearMonth.now() }
    fun nextMonth() { month.value = month.value.plusMonths(1) }
    fun prevMonth() { month.value = month.value.minusMonths(1) }

    private fun YearMonth.range(): Pair<Long, Long> {
        val zone = ZoneId.systemDefault()
        val start = atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = atEndOfMonth().atTime(23, 59, 59).atZone(zone).toInstant().toEpochMilli()
        return start to end
    }

    private val summary: Flow<HomeSummary> = month.flatMapLatest { ym ->
        val (start, end) = ym.range()
        val (previousStart, previousEnd) = ym.minusMonths(1).range()
        observeHomeSummary(start, end, previousStart, previousEnd)
    }

    val uiState: StateFlow<HomeUiState> =
        combine(
            summary,
            settings.currency,
            month,
            getAccountBalancesUseCase.observe(),
            detectedCount,
            settings.notificationDetectionEnabled
        ) { args ->
            val data = args[0] as HomeSummary
            val currency = args[1] as String
            val ym = args[2] as YearMonth
            val balances = args[3] as AccountBalances
            val detected = args[4] as Int
            val detectionEnabled = args[5] as Boolean

            HomeUiState(
                month = ym,
                isCurrentMonth = ym == YearMonth.now(),
                currency = currency,
                monthIncome = data.income,
                monthExpense = data.expense,
                previousPeriodExpense = data.previousPeriodExpense,
                projectedBalance = data.projectedBalance,
                daysUntilPayday = data.daysUntilPayday,
                upcoming = data.upcoming,
                subscriptions = data.subscriptions,
                dayGroups = data.dayGroups,
                dashboardTransactions = data.dashboardTransactions,
                accounts = balances.accounts.sortedByDescending { it.balance }.take(3),
                txVersions = emptyMap(), // On délègue au SharedViewModel dans le Screen
                detectedCount = detected,
                notificationDetectionEnabled = detectionEnabled
            )
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeUiState())
}

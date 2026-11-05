package com.lop.budget.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.data.repository.NotificationDetectionRepository
import com.lop.budget.data.repository.SettingsRepository
import com.lop.budget.domain.model.AccountBalance
import com.lop.budget.domain.model.CurrencyCatalog
import com.lop.budget.domain.model.DayGroup
import com.lop.budget.domain.usecase.account.GetAccountBalancesUseCase
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
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import java.time.Clock
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject
import androidx.compose.runtime.Immutable

@Immutable
data class HomeUiState(
    /**
     * Mois de « Ce mois » : toujours le mois courant (LOP-231, CA-02, P-4). Sans valeur par défaut :
     * il vient de l'horloge injectée du ViewModel.
     */
    val month: YearMonth,
    /** Solde total du moteur de comptes (LOP-231, CA-01) ; `null` tant qu'il n'est pas lu. */
    val totalBalance: Long? = null,
    /** Montants du mois pas encore lus : l'écran n'affiche aucun zéro (LOP-231, CA-04). */
    val isMonthLoading: Boolean = true,
    /** Lecture du mois en échec : distinguée d'un mois vide (LOP-231, CA-04). */
    val monthLoadFailed: Boolean = false,
    /**
     * Même valeur initiale que le dépôt et que tous les autres états (I-3).
     *
     * Valait `"USD"` en dur, si bien que l'accueil légendait ses montants en dollars le temps que
     * le flux de préférences émette — une devise que l'utilisateur n'avait jamais choisie.
     */
    val currency: String = CurrencyCatalog.default.code,
    val monthIncome: Long = 0L,
    val monthExpense: Long = 0L,
    /** « Solde du mois » : revenus moins dépenses du mois, calculé par le use case (CA-03). */
    val monthBalance: Long = 0L,
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
 * Expose l'accueil. Les indicateurs métier sont calculés par [ObserveHomeSummaryUseCase] et le
 * solde total par [GetAccountBalancesUseCase] : ce ViewModel n'assemble que le mois courant, la
 * devise et l'état des notifications (I-12, P-12 de LOP-87).
 *
 * L'accueil n'a plus de navigation de mois (LOP-231, CA-02, P-4) : il est toujours sur le mois
 * courant.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val observeHomeSummary: ObserveHomeSummaryUseCase,
    getAccountBalancesUseCase: GetAccountBalancesUseCase,
    detectionRepo: NotificationDetectionRepository,
    settings: SettingsRepository,
    private val clock: Clock,
) : ViewModel() {

    /** Mois courant lu sur l'horloge injectée ; le fuseau est relu à chaque appel, comme [ObserveHomeSummaryUseCase]. */
    private fun currentMonth(): YearMonth = YearMonth.now(clock.withZone(ZoneId.systemDefault()))

    /**
     * Relu à chaque abonnement, et non figé à la création du ViewModel : l'accueil rouvert après
     * un changement de mois montre le nouveau mois.
     */
    private val month: Flow<YearMonth> = flow { emit(currentMonth()) }

    // `map` et non `combine(..., flowOf(Unit))` : le second flux était constant.
    val detectedCount: StateFlow<Int> = detectionRepo.observePending()
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private fun YearMonth.range(): Pair<Long, Long> {
        val zone = ZoneId.systemDefault()
        val start = atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        // Dernière milliseconde du mois incluse, comme l'analyse que les cartes ouvrent (LOP-197).
        val end = atEndOfMonth().atTime(23, 59, 59, 999_000_000).atZone(zone).toInstant().toEpochMilli()
        return start to end
    }

    /** `null` : lecture en échec, distinguée d'un mois vide (LOP-231, CA-04). */
    private val summary: Flow<Pair<YearMonth, HomeSummary?>> = month.flatMapLatest { ym ->
        val (start, end) = ym.range()
        val (previousStart, previousEnd) = ym.minusMonths(1).range()
        observeHomeSummary(start, end, previousStart, previousEnd)
            .map<HomeSummary, Pair<YearMonth, HomeSummary?>> { ym to it }
            .catch { emit(ym to null) }
    }

    val uiState: StateFlow<HomeUiState> =
        combine(
            summary,
            settings.currency,
            getAccountBalancesUseCase.observe(),
            detectedCount,
            settings.notificationDetectionEnabled
        ) { read, currency, balances, detected, detectionEnabled ->
            val (ym, monthSummary) = read
            val data = monthSummary ?: HomeSummary()

            HomeUiState(
                month = ym,
                totalBalance = balances.total,
                isMonthLoading = false,
                monthLoadFailed = monthSummary == null,
                currency = currency,
                monthIncome = data.income,
                monthExpense = data.expense,
                monthBalance = data.balance,
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
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeUiState(month = currentMonth()))
}

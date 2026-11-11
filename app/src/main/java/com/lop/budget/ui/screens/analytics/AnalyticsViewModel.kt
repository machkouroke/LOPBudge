package com.lop.budget.ui.screens.analytics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lop.budget.data.repository.SettingsRepository
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.domain.CategoryBreakdown
import com.lop.budget.domain.usecase.insight.AnalyticsTrend
import com.lop.budget.domain.usecase.insight.BalanceHistory
import com.lop.budget.domain.usecase.insight.GoalPace
import com.lop.budget.domain.usecase.insight.ObserveGoalPaceUseCase
import com.lop.budget.domain.usecase.insight.HabitsComparison
import com.lop.budget.domain.usecase.insight.ObserveMonthlyAnalyticsUseCase
import com.lop.budget.domain.usecase.insight.ObserveRecurringChargesUseCase
import com.lop.budget.domain.usecase.insight.CalendarStart
import com.lop.budget.domain.usecase.insight.RecurringCharges
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject

/** Granularité de la période d'analyse (LOP-234) : mois civil ou année civile, fuseau de l'appareil. */
enum class AnalysisMode { MONTH, YEAR }

/** Période demandée. En mode [AnalysisMode.YEAR], seule l'année de [month] compte. */
data class AnalyticsPeriod(val mode: AnalysisMode, val month: YearMonth) {
    /** Premier jour inclus : le 1er du mois, ou le 1er janvier (CA-02 de LOP-233). */
    val first: LocalDate get() = if (mode == AnalysisMode.MONTH) month.atDay(1) else month.withMonth(1).atDay(1)

    /** Dernier jour inclus : le dernier du mois, ou le 31 décembre (CA-02 de LOP-233). */
    val last: LocalDate get() = if (mode == AnalysisMode.MONTH) month.atEndOfMonth() else month.withMonth(12).atEndOfMonth()
}

data class AnalyticsUiState(
    /** Mois affiché en mode [AnalysisMode.MONTH] ; en mode [AnalysisMode.YEAR], seule son année compte. */
    val month: YearMonth = YearMonth.now(),
    val mode: AnalysisMode = AnalysisMode.MONTH,
    val currency: String = "EUR",
    val type: TransactionType = TransactionType.EXPENSE,
    val total: Long = 0L,
    val breakdown: List<CategoryBreakdown> = emptyList(),
    /** Série de tendance de la **même** lecture de période que l'anneau (LOP-235, CA-05). */
    val trend: AnalyticsTrend = AnalyticsTrend(),
    /** Lecture de la tendance en échec pour cette période (CA-04) ; l'anneau n'en dépend pas. */
    val trendFailed: Boolean = false,
    /** Carte de LOP-240 ; `null` en mode Année ou pour un mois futur : la carte est absente (CA-06). */
    val habits: HabitsComparison? = null,
    /** Lecture de la carte de LOP-240 en échec (CA-06) ; tendance et tuiles n'en dépendent pas. */
    val habitsFailed: Boolean = false,
    /** Carte de LOP-242 : fenêtre de douze mois qui finit avec la période ; `null` en échec. */
    val balances: BalanceHistory? = null,
    /** Lecture de la carte de LOP-242 en échec (CA-07) ; le reste de l'onglet n'en dépend pas. */
    val balancesFailed: Boolean = false,
)

/** Carte de LOP-241, hors période (CA-06) : un chargement n'est jamais présenté comme 0 €. */
sealed interface ChargesState {
    data object Loading : ChargesState
    data object Failed : ChargesState
    data class Ready(val charges: RecurringCharges) : ChargesState
}

/** Carte de LOP-243, hors période (CA-06) : un chargement n'affiche ni date ni situation (CA-07). */
sealed interface GoalsState {
    data object Loading : GoalsState
    data object Failed : GoalsState
    data class Ready(val goals: List<GoalPace>) : GoalsState
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AnalyticsViewModel @Inject constructor(
    savedStateHandle: androidx.lifecycle.SavedStateHandle,
    private val observeMonthlyAnalytics: ObserveMonthlyAnalyticsUseCase,
    settings: SettingsRepository,
    private val clock: Clock,
    private val observeRecurringCharges: ObserveRecurringChargesUseCase,
    private val observeGoalPace: ObserveGoalPaceUseCase,
) : ViewModel() {

    /** Lu sur l'horloge injectée ; le fuseau est relu à chaque appel, comme l'accueil. */
    fun currentMonth(): YearMonth = YearMonth.now(clock.withZone(ZoneId.systemDefault()))

    private val initialType = savedStateHandle.get<String>("type")?.let { raw ->
        if (raw == "{type}") null 
        else runCatching { TransactionType.valueOf(raw) }.getOrNull()
    } ?: TransactionType.EXPENSE

    private val initialMonth = savedStateHandle.get<String>("ym")?.let { raw ->
        if (raw == "{ym}") null 
        else runCatching { YearMonth.parse(raw) }.getOrNull()
    } ?: currentMonth()

    // Mode et mois changent ensemble : une seule source, pas d'état intermédiaire lu pour rien.
    // Gardée par le ViewModel : revenir sur l'onglet retrouve le mode et la période (P-8 de LOP-234).
    private val _period = MutableStateFlow(AnalyticsPeriod(AnalysisMode.MONTH, initialMonth))

    /**
     * Période demandée, à jour dès le geste : l'en-tête l'affiche sans attendre la lecture.
     * [uiState] porte la période de **ses** données ; tant que les deux diffèrent, ces données ne
     * sont pas celles de la période affichée.
     */
    val period: StateFlow<AnalyticsPeriod> = _period.asStateFlow()
    private val type = MutableStateFlow(initialType)

    /**
     * Sets the transaction type to filter the analytics data.
     */
    fun setType(t: TransactionType) { type.value = t }

    /** Période suivante : un mois en mode Mois, une année en mode Année (CA-02). */
    fun nextPeriod() = step(1)

    /** Période précédente : un mois en mode Mois, une année en mode Année (CA-02). */
    fun previousPeriod() = step(-1)

    private fun step(n: Long) = _period.update {
        it.copy(month = if (it.mode == AnalysisMode.MONTH) it.month.plusMonths(n) else it.month.plusYears(n))
    }

    /** Choix direct d'un mois (CA-03) ; le mode reste Mois. */
    fun pickMonth(ym: YearMonth) = _period.update { AnalyticsPeriod(AnalysisMode.MONTH, ym) }

    /** Choix direct d'une année (CA-03) ; le mode reste Année. */
    fun pickYear(year: Int) = _period.update { AnalyticsPeriod(AnalysisMode.YEAR, it.month.withYear(year)) }

    /**
     * CA-04 : vers Année, l'année du mois est gardée ; vers Mois, le mois courant si l'année est
     * l'année courante, sinon janvier de cette année (P-3 de LOP-234). Même mode : sans effet.
     */
    fun setMode(mode: AnalysisMode) = _period.update {
        when {
            it.mode == mode -> it
            mode == AnalysisMode.YEAR -> it.copy(mode = mode)
            else -> {
                val current = currentMonth()
                AnalyticsPeriod(mode, if (it.month.year == current.year) current else YearMonth.of(it.month.year, 1))
            }
        }
    }

    /** Numéro de lecture : [retryTrend] relance la lecture sans changer la période (LOP-235, CA-04). */
    private val attempt = MutableStateFlow(0)

    /** CA-04 de LOP-235 : relit la période affichée après un échec de la tendance. */
    fun retryTrend() = attempt.update { it + 1 }

    private data class Criteria(val period: AnalyticsPeriod, val type: TransactionType, val currency: String, val attempt: Int)

    /** Bornes en millisecondes, premier et dernier jours inclus, dans [zone]. */
    private fun AnalyticsPeriod.range(zone: ZoneId): Pair<Long, Long> {
        return first.atStartOfDay(zone).toInstant().toEpochMilli() to
            // Dernière milliseconde de la période incluse (même cause que LOP-197).
            last.atTime(23, 59, 59, 999_000_000).atZone(zone).toInstant().toEpochMilli()
    }

    private val loaded = MutableStateFlow(false)
    /**
     * Vrai quand l'état porte la lecture des critères demandés ; faux de leur changement à leur
     * lecture. Faux, l'écran montre son squelette (LOP-226, CA-06). Séparé de [uiState], dont
     * les émissions restent celles d'avant.
     */
    val isLoaded: StateFlow<Boolean> = loaded.asStateFlow()

    val uiState: StateFlow<AnalyticsUiState> =
        combine(_period, type, settings.currency, attempt) { p, t, c, n -> Criteria(p, t, c, n) }
            .flatMapLatest { (p, t, currency) ->
                loaded.value = false
                val zone = ZoneId.systemDefault()
                val (start, end) = p.range(zone)
                val unit = if (p.mode == AnalysisMode.MONTH) ChronoUnit.DAYS else ChronoUnit.MONTHS
                // Son propre `catch` : un échec de la tendance ne coupe ni l'anneau ni la
                // répartition, et ne laisse aucune ancienne courbe (CA-04 de LOP-235).
                val trend = observeMonthlyAnalytics.trend(start, end, unit, zone)
                    .map<AnalyticsTrend, AnalyticsTrend?> { it }
                    .catch { emit(null) }
                // LOP-240 : carte du mode Mois seulement (CA-06), jour de comparaison lu sur
                // l'horloge injectée. Son propre `catch`, comme la tendance : son échec ne coupe rien.
                val habits = if (p.mode == AnalysisMode.MONTH) {
                    observeMonthlyAnalytics.aboveHabits(p.month, LocalDate.now(clock.withZone(zone)), zone)
                        .map { Result.success(it) }
                        .catch { emit(Result.failure(it)) }
                } else {
                    flowOf(Result.success<HabitsComparison?>(null))
                }
                // LOP-242 : fenêtre qui finit au mois affiché, ou en décembre en mode Année (CA-02),
                // mois courant lu sur l'horloge injectée. Son propre `catch` : son échec ne coupe rien.
                val balances = observeMonthlyAnalytics
                    .balanceHistory(YearMonth.from(p.last), YearMonth.now(clock.withZone(zone)), zone)
                    .map { Result.success(it) }
                    .catch { emit(Result.failure(it)) }
                // Anneau et tendance dans le même état, sous la même période : une série n'est
                // jamais présentée avec les montants d'une autre période (CA-05 de LOP-235).
                combine(observeMonthlyAnalytics(start, end, t), trend, habits, balances) { analytics, tr, h, b ->
                    AnalyticsUiState(
                        p.month, p.mode, currency, t, analytics.total, analytics.breakdown,
                        trend = tr ?: AnalyticsTrend(),
                        trendFailed = tr == null,
                        habits = h.getOrNull(),
                        habitsFailed = h.isFailure,
                        balances = b.getOrNull(),
                        balancesFailed = b.isFailure,
                    )
                }
            }
            .onEach { loaded.value = true }
            // Le filtrage, le groupement et le tri tournaient sur le thread UI : `stateIn`
            // collecte sur `viewModelScope`, donc `Dispatchers.Main.immediate`.
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AnalyticsUiState())

    private val chargesAttempt = MutableStateFlow(0)

    /** CA-06 de LOP-241 : relit la carte des charges après un échec, sans toucher au reste. */
    fun retryCharges() = chargesAttempt.update { it + 1 }

    /**
     * LOP-241 : hors de la lecture de période, donc ni mois, ni année, ni mode ne la relisent
     * (CA-06). Son propre `catch` : son échec ne coupe ni la tendance ni les tuiles.
     *
     * ponytail: jour lu à chaque (re)lecture ; si minuit passe onglet ouvert, une série terminée la
     * veille reste comptée jusqu'à la lecture suivante. Exposer un flux minuté si cela gêne.
     */
    val charges: StateFlow<ChargesState> = chargesAttempt
        .flatMapLatest {
            val zone = ZoneId.systemDefault()
            observeRecurringCharges(LocalDate.now(clock.withZone(zone)), zone)
                .map<RecurringCharges, ChargesState> { ChargesState.Ready(it) }
                .catch { emit(ChargesState.Failed) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ChargesState.Loading)

    private val goalsAttempt = MutableStateFlow(0)

    /** CA-07 de LOP-243 : relit la carte des objectifs après un échec, sans toucher au reste. */
    fun retryGoals() = goalsAttempt.update { it + 1 }

    /**
     * LOP-243 : hors de la lecture de période, comme les charges (CA-06). Son propre `catch` : son
     * échec ne coupe rien d'autre (CA-07).
     *
     * ponytail: jour lu à chaque (re)lecture, comme [charges] ; un changement de mois onglet ouvert
     * n'est vu qu'à la lecture suivante.
     */
    val goals: StateFlow<GoalsState> = goalsAttempt
        .flatMapLatest {
            val zone = ZoneId.systemDefault()
            observeGoalPace(LocalDate.now(clock.withZone(zone)), zone)
                .map<List<GoalPace>, GoalsState> { GoalsState.Ready(it) }
                .catch { emit(GoalsState.Failed) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), GoalsState.Loading)

    /**
     * P-7 et P-10 de LOP-241 : départ et jour d'ouverture du calendrier de la série [seriesId], ou
     * `null` si elle n'a plus d'échéance. Un échec de lecture n'ouvre rien : la carte, observée,
     * porte son propre échec.
     */
    suspend fun calendarStart(seriesId: Long): CalendarStart? {
        val zone = ZoneId.systemDefault()
        return runCatching {
            observeRecurringCharges.calendarStart(seriesId, LocalDate.now(clock.withZone(zone)), zone)
        }.getOrNull()
    }
}

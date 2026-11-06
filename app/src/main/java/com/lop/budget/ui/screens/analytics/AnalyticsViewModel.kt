package com.lop.budget.ui.screens.analytics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lop.budget.data.repository.SettingsRepository
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.domain.CategoryBreakdown
import com.lop.budget.domain.usecase.insight.ObserveMonthlyAnalyticsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import java.time.Clock
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject

/** Granularité de la période d'analyse (LOP-234) : mois civil ou année civile, fuseau de l'appareil. */
enum class AnalysisMode { MONTH, YEAR }

/** Période demandée. En mode [AnalysisMode.YEAR], seule l'année de [month] compte. */
data class AnalyticsPeriod(val mode: AnalysisMode, val month: YearMonth)

data class AnalyticsUiState(
    /** Mois affiché en mode [AnalysisMode.MONTH] ; en mode [AnalysisMode.YEAR], seule son année compte. */
    val month: YearMonth = YearMonth.now(),
    val mode: AnalysisMode = AnalysisMode.MONTH,
    val currency: String = "EUR",
    val type: TransactionType = TransactionType.EXPENSE,
    val total: Long = 0L,
    val breakdown: List<CategoryBreakdown> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AnalyticsViewModel @Inject constructor(
    savedStateHandle: androidx.lifecycle.SavedStateHandle,
    private val observeMonthlyAnalytics: ObserveMonthlyAnalyticsUseCase,
    settings: SettingsRepository,
    private val clock: Clock,
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

    /** Bornes en millisecondes, premier et dernier jours inclus, fuseau de l'appareil. */
    private fun AnalyticsPeriod.range(): Pair<Long, Long> {
        val zone = ZoneId.systemDefault()
        val first = if (mode == AnalysisMode.MONTH) month.atDay(1) else month.withMonth(1).atDay(1)
        val last = if (mode == AnalysisMode.MONTH) month.atEndOfMonth() else month.withMonth(12).atEndOfMonth()
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
        combine(_period, type, settings.currency) { p, t, c -> Triple(p, t, c) }
            .flatMapLatest { (p, t, currency) ->
                loaded.value = false
                val (start, end) = p.range()
                // `map` et non `combine(flow, MutableStateFlow(Unit))` : le second flux était
                // constant, il n'apportait qu'un niveau de combine et sa synchronisation.
                observeMonthlyAnalytics(start, end, t).map { analytics ->
                    AnalyticsUiState(p.month, p.mode, currency, t, analytics.total, analytics.breakdown)
                }
            }
            .onEach { loaded.value = true }
            // Le filtrage, le groupement et le tri tournaient sur le thread UI : `stateIn`
            // collecte sur `viewModelScope`, donc `Dispatchers.Main.immediate`.
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AnalyticsUiState())
}

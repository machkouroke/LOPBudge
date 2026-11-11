package com.lop.budget.ui.screens.subscriptions

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.data.repository.SettingsRepository
import com.lop.budget.domain.model.CurrencyCatalog
import com.lop.budget.domain.usecase.subscription.ObserveSubscriptionsMonthUseCase
import com.lop.budget.domain.usecase.subscription.SubscriptionsMonth
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject

sealed interface SubscriptionsContent {
    data object Loading : SubscriptionsContent

    /** CA-08 : ni un mois vide, ni un chargement permanent. */
    data object Error : SubscriptionsContent
    data class Loaded(val month: YearMonth, val data: SubscriptionsMonth) : SubscriptionsContent
}

data class SubscriptionsUiState(
    val month: YearMonth,
    val selectedDay: LocalDate? = null,
    val content: SubscriptionsContent = SubscriptionsContent.Loading,
) {
    /**
     * CA-06 : sans jour, les échéances planifiées du mois ; avec un jour, toutes celles de ce jour.
     * `null` tant que le mois n'est pas chargé.
     */
    val rows: List<TransactionWithRelations>?
        get() = (content as? SubscriptionsContent.Loaded)?.data?.let { data ->
            if (selectedDay == null) data.unpaid else data.byDay[selectedDay].orEmpty()
        }
}

/**
 * Calendrier mensuel des abonnements (LOP-244).
 *
 * Ne porte que l'état d'écran : mois consulté, jour sélectionné, réessai. Le filtre et les sommes
 * sont ceux de [ObserveSubscriptionsMonthUseCase]. Aucune écriture (I-1).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SubscriptionsViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val observeMonth: ObserveSubscriptionsMonthUseCase,
    settings: SettingsRepository,
    clock: Clock,
) : ViewModel() {

    private val zone: ZoneId = ZoneId.systemDefault()

    /**
     * CA-03 : le jour marqué « aujourd'hui », lu sur l'horloge injectée.
     *
     * ponytail: lu à l'ouverture de l'écran, comme le calendrier d'une série ; exposer un flux minuté
     * si le passage de minuit écran ouvert devient gênant.
     */
    val today: LocalDate = LocalDate.now(clock.withZone(zone))
    val currentMonth: YearMonth = YearMonth.from(today)

    // CA-07 : mois et jour vivent dans le SavedStateHandle ; le retour du détail les retrouve.
    private val month = savedStateHandle.getStateFlow(KEY_MONTH, currentMonth.toString())
    private val selectedDay = savedStateHandle.getStateFlow<String?>(KEY_DAY, null)
    private val retries = MutableStateFlow(0)

    /** `flatMapLatest` abandonne la lecture d'un mois dès qu'un autre est demandé. */
    private val content: Flow<SubscriptionsContent> = combine(month, retries) { requested, _ -> requested }
        .flatMapLatest { requested ->
            val ym = YearMonth.parse(requested)
            observeMonth(ym, zone)
                .map<SubscriptionsMonth, SubscriptionsContent> { SubscriptionsContent.Loaded(ym, it) }
                .onStart { emit(SubscriptionsContent.Loading) }
                .catch { emit(SubscriptionsContent.Error) }
        }

    val uiState: StateFlow<SubscriptionsUiState> =
        combine(month, selectedDay, content) { requested, selected, read ->
            val ym = YearMonth.parse(requested)
            SubscriptionsUiState(
                month = ym,
                selectedDay = selected?.let(LocalDate::parse),
                // Une réponse d'un autre mois ne s'affiche jamais comme celle du mois demandé.
                content = if (read is SubscriptionsContent.Loaded && read.month != ym) SubscriptionsContent.Loading else read,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SubscriptionsUiState(month = currentMonth))

    /** Devise de l'application, pour la synthèse ; les lignes lisent la leur. */
    val currency: StateFlow<String> = settings.currency
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), CurrencyCatalog.default.code)

    fun previousMonth() = changeMonth(YearMonth.parse(month.value).minusMonths(1))

    fun nextMonth() = changeMonth(YearMonth.parse(month.value).plusMonths(1))

    fun pickMonth(target: YearMonth) = changeMonth(target)

    /** P-7 : le bouton « Aujourd'hui », affiché hors du mois courant. */
    fun goToCurrentMonth() = changeMonth(currentMonth)

    /** CA-06 : toucher un jour le sélectionne, le toucher à nouveau le désélectionne. */
    fun selectDay(day: LocalDate) {
        savedStateHandle[KEY_DAY] = if (selectedDay.value == day.toString()) null else day.toString()
    }

    /** CA-08 : relit le mois consulté, sans en changer. */
    fun retry() = retries.update { it + 1 }

    /** CA-04 : tout changement de mois efface la sélection. */
    private fun changeMonth(target: YearMonth) {
        savedStateHandle[KEY_MONTH] = target.toString()
        savedStateHandle[KEY_DAY] = null
    }

    private companion object {
        const val KEY_MONTH = "subscriptions.month"
        const val KEY_DAY = "subscriptions.day"
    }
}

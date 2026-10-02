package com.lop.budget.ui.screens.calendar

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.domain.usecase.transaction.DaySelection
import com.lop.budget.domain.usecase.transaction.MonthOccurrences
import com.lop.budget.domain.usecase.transaction.ObserveRecurringOccurrencesUseCase
import com.lop.budget.domain.usecase.transaction.SeriesContext
import com.lop.budget.domain.usecase.transaction.TargetResolution
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject

sealed interface CalendarContent {
    data object Loading : CalendarContent

    /** L'occurrence de départ n'a jamais pu être résolue : il n'y a pas de série à explorer. */
    data object Unavailable : CalendarContent

    /** CA-07 : ni un calendrier vide, ni un chargement permanent. */
    data object Error : CalendarContent
    data class Loaded(val occurrences: MonthOccurrences) : CalendarContent
}

data class SeriesCalendarUiState(
    /** `null` tant que le contexte n'a pas fixé le mois d'ouverture. */
    val month: YearMonth? = null,
    val selectedDay: LocalDate? = null,
    val content: CalendarContent = CalendarContent.Loading,
    /** Contenu du jour sélectionné ; `null` si aucun jour ne l'est ou si le mois n'est pas chargé. */
    val selection: DaySelection? = null,
    val canGoToNextDue: Boolean = false,
)

sealed interface CalendarEvent {
    data class OpenOccurrence(val id: Long) : CalendarEvent

    /** La cible est l'occurrence de départ : revenir à son détail plutôt qu'en ouvrir un second. */
    data object ReturnToStart : CalendarEvent
    data object OccurrenceUnavailable : CalendarEvent
}

/**
 * Calendrier d'une série (LOP-7).
 *
 * Ne porte que l'état d'écran : mois demandé, jour sélectionné, annulation des demandes obsolètes.
 * Recherche des échéances, regroupement par jour, sélection et disponibilité d'une cible sont
 * décidés par [ObserveRecurringOccurrencesUseCase].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SeriesCalendarViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val occurrences: ObserveRecurringOccurrencesUseCase,
) : ViewModel() {

    private val startId: Long = checkNotNull(savedStateHandle[ARG_START_ID])
    private val zone: ZoneId = ZoneId.systemDefault()

    // CA-06 : le mois et le jour vivent dans le SavedStateHandle et survivent à une recréation.
    private val month = savedStateHandle.getStateFlow<String?>(KEY_MONTH, null)
    private val selectedDay = savedStateHandle.getStateFlow<String?>(KEY_DAY, null)
    private val retries = MutableStateFlow(0)

    private sealed interface ContextState {
        data object Loading : ContextState
        data object Missing : ContextState
        data object Failed : ContextState
        data class Ready(val context: SeriesContext) : ContextState
    }

    private val context: StateFlow<ContextState> = retries.flatMapLatest {
        occurrences.observeContext(startId, zone)
            .onEach { ctx -> if (ctx != null && month.value == null) show(ctx.openingDay) }
            .map { ctx -> if (ctx == null) ContextState.Missing else ContextState.Ready(ctx) }
            .catch { emit(ContextState.Failed) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ContextState.Loading)

    /** `flatMapLatest` annule la lecture d'un mois dès qu'un autre est demandé (CA-03). */
    private val monthContent: Flow<CalendarContent> = combine(
        context.map { (it as? ContextState.Ready)?.context?.seriesId }.distinctUntilChanged(),
        month,
        retries,
    ) { seriesId, requested, _ -> seriesId to requested }
        .flatMapLatest { (seriesId, requested) ->
            if (seriesId == null || requested == null) return@flatMapLatest flowOf(CalendarContent.Loading)
            occurrences.observeMonth(seriesId, YearMonth.parse(requested), zone)
                .map<MonthOccurrences, CalendarContent> { CalendarContent.Loaded(it) }
                .onStart { emit(CalendarContent.Loading) }
                .catch { emit(CalendarContent.Error) }
        }

    val uiState: StateFlow<SeriesCalendarUiState> =
        combine(context, month, selectedDay, monthContent) { ctx, requested, selected, content ->
            val ym = requested?.let(YearMonth::parse)
            val day = selected?.let(LocalDate::parse)
            val visible = when {
                ctx is ContextState.Missing -> CalendarContent.Unavailable
                ctx is ContextState.Failed -> CalendarContent.Error
                // Une réponse d'un autre mois ne s'affiche jamais comme celle du mois demandé.
                content is CalendarContent.Loaded && content.occurrences.month != ym -> CalendarContent.Loading
                else -> content
            }
            SeriesCalendarUiState(
                month = ym,
                selectedDay = day,
                content = visible,
                selection = if (visible is CalendarContent.Loaded && day != null) {
                    occurrences.selectDay(visible.occurrences, day)
                } else {
                    null
                },
                canGoToNextDue = (ctx as? ContextState.Ready)?.context?.nextDue != null,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SeriesCalendarUiState())

    private val _events = Channel<CalendarEvent>(Channel.BUFFERED)
    val events: Flow<CalendarEvent> = _events.receiveAsFlow()

    fun previousMonth() = changeMonth { it.minusMonths(1) }

    fun nextMonth() = changeMonth { it.plusMonths(1) }

    fun pickMonth(target: YearMonth) = changeMonth { target }

    /** CA-03 : la première échéance après l'occurrence de départ, et son mois. */
    fun goToNextDue() {
        val day = (context.value as? ContextState.Ready)?.context?.nextDueDay ?: return
        show(day)
    }

    /** CA-04 : sélectionne le jour ; une occurrence unique s'ouvre directement. */
    fun selectDay(day: LocalDate) {
        savedStateHandle[KEY_DAY] = day.toString()
        // CA-07 : pendant le chargement, rien n'est ouvrable.
        val loaded = (uiState.value.content as? CalendarContent.Loaded)?.occurrences ?: return
        if (loaded.month != YearMonth.from(day)) return
        val single = occurrences.selectDay(loaded, day) as? DaySelection.Single ?: return
        openOccurrence(single.occurrence)
    }

    fun openOccurrence(occurrence: TransactionWithRelations) {
        val start = (context.value as? ContextState.Ready)?.context?.start
        viewModelScope.launch {
            _events.send(
                when (val target = occurrences.resolveTarget(occurrence, start)) {
                    is TargetResolution.Available -> CalendarEvent.OpenOccurrence(target.id)
                    TargetResolution.Start -> CalendarEvent.ReturnToStart
                    TargetResolution.Unavailable -> CalendarEvent.OccurrenceUnavailable
                }
            )
        }
    }

    /** CA-07 : relit le mois demandé, sans en changer. */
    fun retry() = retries.update { it + 1 }

    /** CA-03 : tout changement de mois efface la sélection. */
    private fun changeMonth(transform: (YearMonth) -> YearMonth) {
        val current = month.value?.let(YearMonth::parse) ?: return
        savedStateHandle[KEY_MONTH] = transform(current).toString()
        savedStateHandle[KEY_DAY] = null
    }

    private fun show(day: LocalDate) {
        savedStateHandle[KEY_MONTH] = YearMonth.from(day).toString()
        savedStateHandle[KEY_DAY] = day.toString()
    }

    companion object {
        const val ARG_START_ID = "startId"
        private const val KEY_MONTH = "calendar.month"
        private const val KEY_DAY = "calendar.day"
    }
}

package com.lop.budget.ui.screens.home

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lop.budget.data.repository.SettingsRepository
import com.lop.budget.domain.usecase.insight.ObserveRemainingToSpendUseCase
import com.lop.budget.domain.usecase.insight.RemainingToSpend
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/** États de la carte « reste à dépenser » de l'accueil (LOP-239). */
sealed interface RemainingCardState {
    /** CA-07 : pas encore lu, la carte affiche « — », jamais 0 €. */
    data object Loading : RemainingCardState

    /** CA-07 : lecture en échec, message et Réessayer. */
    data object Failed : RemainingCardState

    /** CA-05 : aucune série de revenu n'a d'occurrence à venir. */
    data object NoPayday : RemainingCardState

    @Immutable
    data class Ready(val data: RemainingToSpend, val currency: String) : RemainingCardState
}

/**
 * Carte « reste à dépenser » (LOP-239). ViewModel à part de [HomeViewModel] : la carte est un bloc
 * autonome de l'accueil (P-1). Le calcul est entièrement dans [ObserveRemainingToSpendUseCase] ;
 * ici, seulement le jour courant, la devise et le réessai. La lecture n'écrit rien (I-1).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class RemainingToSpendViewModel @Inject constructor(
    observeRemaining: ObserveRemainingToSpendUseCase,
    settings: SettingsRepository,
    clock: Clock,
) : ViewModel() {

    private val retries = MutableStateFlow(0)

    /** Jour et fuseau relus à chaque abonnement : l'accueil rouvert le lendemain repart du bon jour. */
    val state: StateFlow<RemainingCardState> = retries.flatMapLatest {
        val zone = ZoneId.systemDefault()
        combine(observeRemaining(LocalDate.now(clock.withZone(zone)), zone), settings.currency) { data, currency ->
            if (data == null) RemainingCardState.NoPayday else RemainingCardState.Ready(data, currency)
        }
            .onStart { emit(RemainingCardState.Loading) }
            .catch { emit(RemainingCardState.Failed) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), RemainingCardState.Loading)

    /** CA-07 : relance la lecture, jamais une écriture (I-1). */
    fun retry() = retries.update { it + 1 }
}

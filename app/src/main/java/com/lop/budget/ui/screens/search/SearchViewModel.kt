package com.lop.budget.ui.screens.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.CategoryEntity
import com.lop.budget.data.local.entity.TagEntity
import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.data.repository.AccountRepository
import com.lop.budget.data.repository.SettingsRepository
import com.lop.budget.domain.model.DayGroup
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.domain.usecase.category.ObserveCategoriesUseCase
import com.lop.budget.domain.usecase.tag.ObserveTagsUseCase
import com.lop.budget.domain.usecase.transaction.SearchTransactionsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/**
 * Critères de la feuille Filtres (LOP-228). `null` ou un ensemble vide ne filtre pas.
 *
 * Plusieurs catégories, comptes et étiquettes (P-7, P-10) : OU dans un axe, ET entre axes. Type et
 * statut n'ont que deux valeurs chacun ; les deux ensemble valent « tous », d'où une seule valeur.
 * Les étiquettes se désignent par leur nom, comme le moteur les compare.
 */
data class SearchFilters(
    val start: LocalDate? = null,
    val end: LocalDate? = null,
    val type: TransactionType? = null,
    val status: TransactionStatus? = null,
    val categoryIds: Set<Long> = emptySet(),
    val accountIds: Set<Long> = emptySet(),
    val tagNames: Set<String> = emptySet(),
) {
    /** CA-02 : une fin antérieure au début ne s'applique pas. */
    val isValid: Boolean get() = start == null || end == null || !end.isBefore(start)

    /** Nombre de critères appliqués, la période comptant pour un : la pastille du bouton Filtres. */
    val count: Int
        get() = listOfNotNull(start ?: end, type, status).size +
            categoryIds.size + accountIds.size + tagNames.size
}

data class SearchUiState(
    val query: String = "",
    val filters: SearchFilters = SearchFilters(),
    /** Ni texte ni critère : rien n'est cherché, la vue invite à le faire (LOP-70 CA-01, P-2). */
    val isPrompt: Boolean = true,
    val dayGroups: List<DayGroup> = emptyList(),
    /** Synthèse des lignes listées (CA-04), en centimes. */
    val count: Int = 0,
    val expense: Long = 0L,
    val income: Long = 0L,
    val balance: Long = 0L,
    val currency: String = "EUR",
    /**
     * `true` tant que liste et synthèse ne sont pas celles du texte et des critères affichés :
     * elles viennent d'une recherche précédente, ou sont vides avant la première (CA-05).
     */
    val isRefreshing: Boolean = true,
    /** Lecture en échec pour les critères affichés : distincte d'un résultat vide (CA-05). */
    val loadFailed: Boolean = false,
    val availableAccounts: List<AccountEntity> = emptyList(),
    val availableCategories: List<CategoryEntity> = emptyList(),
    val availableTags: List<TagEntity> = emptyList(),
)

/**
 * Vue unifiée recherche + historique (LOP-228).
 *
 * Tous les critères vont au moteur ; rien n'est refiltré ici (I-4 de LOP-70). Liste, nombre et
 * synthèse sortent d'un même résultat, qui porte les critères qui l'ont produit (I-2).
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    accountRepo: AccountRepository,
    observeCategories: ObserveCategoriesUseCase,
    observeTags: ObserveTagsUseCase,
    private val searchTransactionsUseCase: SearchTransactionsUseCase,
    settings: SettingsRepository,
) : ViewModel() {

    private val query = MutableStateFlow("")

    /** CA-01 : ouverte sur un mois, la vue part de ce mois entier et d'aucun autre critère. */
    private val filters = MutableStateFlow(
        SearchFilters(
            start = savedStateHandle.get<String>(ARG_START)?.let(LocalDate::parse),
            end = savedStateHandle.get<String>(ARG_END)?.let(LocalDate::parse),
        )
    )

    /** Numéro de lecture : [retry] relance la recherche sans changer aucun critère. */
    private val attempt = MutableStateFlow(0)

    fun onQueryChange(q: String) { query.value = q }

    /** CA-02 : seul « Appliquer » arrive ici ; une période inversée est refusée. */
    fun applyFilters(f: SearchFilters) {
        if (f.isValid) filters.value = f
    }

    fun retry() { attempt.update { it + 1 } }

    private data class Criteria(val query: String, val filters: SearchFilters, val attempt: Int)

    private data class Result(
        val criteria: Criteria,
        val rows: List<TransactionWithRelations>,
        val failed: Boolean = false,
    )

    /** Délai nul sur une chaîne vide : ouvrir la vue ou effacer le champ n'attend pas. */
    private val searchedQuery = query.debounce { if (it.isEmpty()) 0L else SEARCH_DEBOUNCE_MS }

    private val results = combine(searchedQuery, filters, attempt, ::Criteria)
        .distinctUntilChanged()
        .flatMapLatest(::search)

    private fun search(c: Criteria): Flow<Result> {
        val f = c.filters
        val zone = ZoneId.systemDefault()
        return searchTransactionsUseCase.matching(
            query = c.query,
            accountIds = f.accountIds,
            categoryIds = f.categoryIds,
            startDate = f.start?.atStartOfDay(zone)?.toInstant()?.toEpochMilli(),
            // CA-01 : fin incluse jusqu'à 23:59:59.999 du dernier jour.
            endDate = f.end?.atTime(23, 59, 59, 999_000_000)?.atZone(zone)?.toInstant()?.toEpochMilli(),
            type = f.type,
            status = f.status,
            tagNames = f.tagNames,
        )
            .map { Result(c, it) }
            .catch { emit(Result(c, emptyList(), failed = true)) }
    }

    private val shown = combine(query, filters, attempt, ::Criteria)

    private data class References(
        val currency: String,
        val accounts: List<AccountEntity>,
        val categories: List<CategoryEntity>,
        val tags: List<TagEntity>,
    )

    private val references =
        combine(settings.currency, accountRepo.observeAll(), observeCategories(), observeTags(), ::References)

    val uiState: StateFlow<SearchUiState> = combine(results, shown, references) { result, displayed, (currency, accounts, categories, tags) ->
        val rows = result.rows
        val income = rows.filter { it.transaction.type == TransactionType.INCOME }.sumOf { it.transaction.amount }
        val expense = rows.filter { it.transaction.type == TransactionType.EXPENSE }.sumOf { it.transaction.amount }
        val refreshing = result.criteria != displayed
        SearchUiState(
            query = displayed.query,
            filters = displayed.filters,
            isPrompt = displayed.query.isBlank() && displayed.filters == SearchFilters(),
            dayGroups = DayGroup.fromTransactions(rows),
            count = rows.size,
            expense = expense,
            income = income,
            balance = income - expense,
            currency = currency,
            isRefreshing = refreshing,
            loadFailed = !refreshing && result.failed,
            availableAccounts = accounts,
            availableCategories = categories,
            availableTags = tags,
        )
    }
        // `DayGroup.fromTransactions` trie et groupe toute la liste : hors du thread UI.
        .flowOn(Dispatchers.Default)
        // Les critères reçus, sans résultat : rien n'est présenté comme leur résultat avant la
        // première lecture (CA-05), et une ouverture sur un mois ne passe pas par l'invite.
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            SearchUiState(filters = filters.value, isPrompt = filters.value == SearchFilters()),
        )

    companion object {
        const val ARG_START = "start"
        const val ARG_END = "end"
        private const val SEARCH_DEBOUNCE_MS = 300L
    }
}

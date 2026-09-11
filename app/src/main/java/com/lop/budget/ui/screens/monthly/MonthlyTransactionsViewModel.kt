package com.lop.budget.ui.screens.monthly

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.data.repository.AccountRepository
import com.lop.budget.data.repository.CategoryRepository
import com.lop.budget.data.repository.SettingsRepository
import com.lop.budget.domain.model.DayGroup
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.domain.usecase.SearchTransactionsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject

enum class PaidFilter { ALL, PAID, PLANNED }
enum class InsightMode { CATEGORY, TAG }

data class MonthlyCategoryBreakdown(
    val name: String,
    val colorArgb: Int,
    /** Total de la catégorie ou du tag, en centimes. */
    val total: Long,
    /** Part du total, entre 0 et 1 — une proportion, pas un montant. */
    val share: Double,
)

data class MonthlyTransactionsUiState(
    val month: YearMonth = YearMonth.now(),
    val type: TransactionType? = null, // null means BOTH income and expense
    val filter: PaidFilter = PaidFilter.ALL,
    val insightMode: InsightMode = InsightMode.CATEGORY,
    val searchQuery: String = "",
    val hasResultsInOtherMonths: Boolean = false,
    val selectedAccountId: Long? = null,
    val selectedCategoryId: Long? = null,
    val currency: String = "EUR",
    val total: Long = 0L,
    val breakdown: List<MonthlyCategoryBreakdown> = emptyList(),
    val dayGroups: List<DayGroup> = emptyList(),
    val transactions: List<TransactionWithRelations> = emptyList(),
    val availableAccounts: List<com.lop.budget.data.local.entity.AccountEntity> = emptyList(),
    val availableCategories: List<com.lop.budget.data.local.entity.CategoryEntity> = emptyList(),
    val isAnalyticsMode: Boolean = false,
    /** Version par transaction pour forcer la recréation après Undo. */
    val txVersions: Map<Long, Int> = emptyMap(),
)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class MonthlyTransactionsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    accountRepo: AccountRepository,
    categoryRepo: CategoryRepository,
    private val searchTransactionsUseCase: SearchTransactionsUseCase,
    settings: SettingsRepository,
) : ViewModel() {

    // Dans MonthlyTransactionsViewModel.kt

    private val initialType = savedStateHandle.get<String>("type")?.let { TransactionType.valueOf(it) }
    private val initialMonth = savedStateHandle.get<String>("ym")?.let { YearMonth.parse(it) }
        ?: YearMonth.now()
    private val initialMode = savedStateHandle.get<String>("mode") ?: "HISTORY"

    private val month = MutableStateFlow(initialMonth)
    private val type = MutableStateFlow<TransactionType?>(initialType) // null = ALL
    private val filter = MutableStateFlow(PaidFilter.ALL)
    private val insightMode = MutableStateFlow(InsightMode.CATEGORY)
    private val searchQuery = MutableStateFlow("")
    private val selectedAccountId = MutableStateFlow<Long?>(null)
    private val selectedCategoryId = MutableStateFlow<Long?>(null)
    private val isAnalyticsMode = MutableStateFlow(initialMode == "ANALYTICS")

    fun setFilter(f: PaidFilter) { filter.value = f }
    fun onQueryChange(q: String) { searchQuery.value = q }
    fun onAccountFilterChange(id: Long?) { selectedAccountId.value = id }
    fun onCategoryFilterChange(id: Long?) { selectedCategoryId.value = id }
    fun setType(t: TransactionType?) { type.value = t }



    private fun YearMonth.range(): Pair<Long, Long> {
        val zone = ZoneId.systemDefault()
        return atDay(1).atStartOfDay(zone).toInstant().toEpochMilli() to
            atEndOfMonth().atTime(23, 59, 59).atZone(zone).toInstant().toEpochMilli()
    }

    /**
     * Entrée de **filtrage** de la recherche, distincte de [searchQuery] qui alimente le champ.
     *
     * Sans ce découplage, chaque frappe relance la recherche. Le champ de saisie continue de lire
     * la valeur brute : il reste réactif à la frappe, seule la recherche attend la fin de la saisie.
     *
     * `timeoutMillis` nul sur une chaîne vide : ouvrir l'écran ou effacer le champ ne doit pas
     * retarder le premier état de 300 ms.
     */
    private val filterQuery = searchQuery.debounce { q -> if (q.isEmpty()) 0L else SEARCH_DEBOUNCE_MS }

    /** Ce qui définit la recherche à lancer. Le mois en fixe la fenêtre. */
    private data class MonthlySearch(
        val month: YearMonth,
        val query: String,
        val accountId: Long?,
        val categoryId: Long?,
        val type: TransactionType?,
        val status: TransactionStatus?,
    )

    /**
     * Type et statut réunis en amont pour tenir dans un `combine` typé : au-delà de cinq flux,
     * seule la forme à `Array<*>` existe, et elle troque la vérification du compilateur contre
     * des transtypages.
     */
    private val screenFilters = combine(type, filter) { t, f ->
        t to when (f) {
            PaidFilter.ALL -> null
            PaidFilter.PAID -> TransactionStatus.PAID
            PaidFilter.PLANNED -> TransactionStatus.PLANNED
        }
    }

    /**
     * Lignes trouvées, accompagnées du critère qui les a produites.
     *
     * Un type nommé plutôt qu'une `Pair` générique : le transtypage depuis l'`Array<*>` du
     * `combine` est alors vérifié à l'exécution, donc sans `@Suppress("UNCHECKED_CAST")` — lequel
     * ne peut de toute façon pas cibler une déclaration déstructurante.
     */
    private data class MonthlyResult(
        val criteria: MonthlySearch,
        val rows: List<TransactionWithRelations>,
    )

    /**
     * Résultat de la recherche du mois affiché, accompagné du critère qui l'a produit.
     *
     * C'est **le même use case que l'écran Recherche**, appelé avec la plage mensuelle pour
     * fenêtre : la règle de correspondance (titre/note) et **tous** les filtres, type et statut
     * payé/planifié compris, ne vivent qu'à un seul endroit et se testent une seule fois. Cet
     * écran choisit les critères qu'il expose ; il ne refiltre pas les lignes rendues (I-4).
     *
     * Le critère voyage avec ses lignes : `hasResultsInOtherMonths` doit savoir quelle requête a
     * réellement produit la liste, et non lire la saisie brute qui la devance pendant le debounce.
     *
     * `distinctUntilChanged` avant le `flatMapLatest` : sans lui, une émission de `searchQuery`
     * qui ne change pas la valeur débouncée relancerait la recherche pour rien.
     */
    private val monthResults = combine(
        month,
        filterQuery,
        selectedAccountId,
        selectedCategoryId,
        screenFilters,
    ) { ym, query, accId, catId, (t, status) ->
        MonthlySearch(ym, query, accId, catId, t, status)
    }
        .distinctUntilChanged()
        .flatMapLatest { criteria ->
            val (start, end) = criteria.month.range()
            searchTransactionsUseCase(
                query = criteria.query,
                accountId = criteria.accountId,
                categoryId = criteria.categoryId,
                startDate = start,
                endDate = end,
                type = criteria.type,
                status = criteria.status,
            ).map { rows -> MonthlyResult(criteria, rows) }
        }

    val uiState: StateFlow<MonthlyTransactionsUiState> =
        combine(
            monthResults,
            settings.currency,
            month,
            type,
            filter,
            insightMode,
            searchQuery,
            selectedAccountId,
            selectedCategoryId,
            accountRepo.observeAll(),
            categoryRepo.observeAll(),
            isAnalyticsMode,
        ) { args ->
            val (criteria, filtered) = args[0] as MonthlyResult
            val currency = args[1] as String
            val ym = args[2] as YearMonth
            val t = args[3] as TransactionType?
            val f = args[4] as PaidFilter
            val mode = args[5] as InsightMode
            // Saisie brute : n'alimente que le champ de texte, jamais le filtrage.
            val query = args[6] as String
            val accId = args[7] as Long?
            val catId = args[8] as Long?
            val accounts = args[9] as List<com.lop.budget.data.local.entity.AccountEntity>
            val categories = args[10] as List<com.lop.budget.data.local.entity.CategoryEntity>
            val analytics = args[11] as Boolean

            // Tous les critères ont été appliqués par le use case, qui rend les lignes déjà
            // triées par date décroissante. `t` et `f` ne servent plus qu'à réafficher l'état
            // des puces : les relire ici pour refiltrer recréerait un second moteur (I-4).
            val total = filtered.sumOf { tx ->
                if (tx.transaction.type == TransactionType.INCOME) tx.transaction.amount else -tx.transaction.amount 
            }

            // `filtered` est déjà trié par date croissante (CA-16) : `groupBy` conserve l'ordre
            // de parcours, donc les clés sortent déjà croissantes et chaque groupe est déjà
            // trié. Le relevé mensuel se lit donc du 1er au dernier jour du mois. Les deux
            // re-tris et le `toSortedMap` intermédiaire étaient redondants.
            val zone = ZoneId.systemDefault()
            val dayGroups = filtered
                .groupBy { Instant.ofEpochMilli(it.transaction.date).atZone(zone).toLocalDate() }
                .map { (date, list) ->
                    DayGroup(
                        date = date,
                        total = list.sumOf { tx -> if (tx.transaction.type == TransactionType.INCOME) tx.transaction.amount else -tx.transaction.amount },
                        transactions = list,
                    )
                }

            // Hissé hors des `map` ci-dessous : le dénominateur est le même pour tous les
            // groupes, le recalculer par groupe rendait le breakdown O(groupes x N).
            val absTotal = filtered.sumOf { it.transaction.amount }

            val breakdown = if (mode == InsightMode.CATEGORY) {
                filtered.groupBy { it.category }
                    .map { (cat, list) ->
                        val sum = list.sumOf { it.transaction.amount }
                        MonthlyCategoryBreakdown(
                            name = cat?.name ?: "Sans catégorie",
                            colorArgb = cat?.colorArgb ?: 0xFF9E9E9E.toInt(),
                            total = sum,
                            share = if (absTotal > 0) sum.toDouble() / absTotal else 0.0,
                        )
                    }
                    .sortedByDescending { it.total }
            } else {
                // Breakdown par TAG
                filtered.flatMap { twr -> twr.tags.map { tag -> tag to twr.transaction.amount } }
                    .groupBy({ it.first }, { it.second })
                    .map { (tag, amounts) ->
                        val sum = amounts.sum()
                        MonthlyCategoryBreakdown(
                            name = tag.name,
                            colorArgb = tag.colorArgb,
                            total = sum,
                            share = if (absTotal > 0) sum.toDouble() / absTotal else 0.0,
                        )
                    }
                    .sortedByDescending { it.total }
            }

            // Check if results exist globally if none in current month.
            // `criteria.query` et non `query` : c'est la requête avec laquelle ces lignes ont
            // réellement été cherchées, les deux ne coïncident pas pendant le debounce.
            val hasResultsInOtherMonths = criteria.query.isNotBlank() && filtered.isEmpty()

            MonthlyTransactionsUiState(
                month = ym,
                type = t,
                filter = f,
                insightMode = mode,
                searchQuery = query,
                hasResultsInOtherMonths = hasResultsInOtherMonths,
                selectedAccountId = accId,
                selectedCategoryId = catId,
                availableAccounts = accounts,
                availableCategories = categories,
                currency = currency,
                total = total,
                breakdown = breakdown,
                dayGroups = dayGroups,
                transactions = filtered,
                isAnalyticsMode = analytics,
                txVersions = emptyMap() // On délègue au SharedViewModel
            )
        }
            // `stateIn(viewModelScope)` collecte sur `Dispatchers.Main.immediate` : sans ce
            // `flowOn`, tout le filtrage/tri/groupement ci-dessus s'exécute sur le thread UI.
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MonthlyTransactionsUiState())

    private companion object {
        /** Aligné sur le `debounce(300)` de `SearchViewModel`. */
        const val SEARCH_DEBOUNCE_MS = 300L
    }
}

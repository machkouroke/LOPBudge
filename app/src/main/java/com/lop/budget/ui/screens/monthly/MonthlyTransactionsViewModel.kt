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
import com.lop.budget.domain.usecase.ObserveTransactionsUseCase
import com.lop.budget.domain.usecase.matchesSearchQuery
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
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
    private val observeTransactionsUseCase: ObserveTransactionsUseCase,
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

    private val baseTxs = month.flatMapLatest { ym ->
        val (start, end) = ym.range()
        observeTransactionsUseCase(start, end)
    }

    /**
     * Entrée de **filtrage** de la recherche, distincte de [searchQuery] qui alimente le champ.
     *
     * Sans ce découplage, chaque frappe relance le filtrage, les trois tris, le groupement par jour
     * et le breakdown. Le champ de saisie continue de lire la valeur brute : il reste réactif à la
     * frappe, seul le recalcul attend la fin de la saisie.
     *
     * `timeoutMillis` nul sur une chaîne vide : ouvrir l'écran ou effacer le champ ne doit pas
     * retarder le premier état de 250 ms.
     */
    private val filterQuery = searchQuery.debounce { q -> if (q.isEmpty()) 0L else SEARCH_DEBOUNCE_MS }

    val uiState: StateFlow<MonthlyTransactionsUiState> =
        combine(
            baseTxs,
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
            filterQuery,
        ) { args ->
            val allTxs = args[0] as List<TransactionWithRelations>
            val currency = args[1] as String
            val ym = args[2] as YearMonth
            val t = args[3] as TransactionType?
            val f = args[4] as PaidFilter
            val mode = args[5] as InsightMode
            val query = args[6] as String
            val accId = args[7] as Long?
            val catId = args[8] as Long?
            val accounts = args[9] as List<com.lop.budget.data.local.entity.AccountEntity>
            val categories = args[10] as List<com.lop.budget.data.local.entity.CategoryEntity>
            val analytics = args[11] as Boolean

            // `query` (brut) n'alimente que le champ de saisie ; le filtrage lit la version
            // stabilisée, sinon chaque frappe relance tout le pipeline ci-dessous.
            val appliedQuery = args[12] as String

            val filtered = allTxs
                .asSequence()
                .filter { if (t == null) true else it.transaction.type == t }
                .filter {
                    when (f) {
                        PaidFilter.ALL -> true
                        PaidFilter.PAID -> it.transaction.status == TransactionStatus.PAID
                        PaidFilter.PLANNED -> it.transaction.status == TransactionStatus.PLANNED
                    }
                }
                // Prédicat partagé avec l'écran Recherche : la règle de correspondance ne doit
                // pas diverger entre les deux écrans.
                .filter { it.matchesSearchQuery(appliedQuery) }
                .filter { if (accId == null) true else it.account?.id == accId }
                .filter { if (catId == null) true else it.category?.id == catId }
                .sortedByDescending { it.transaction.date }
                .toList()

            val total = filtered.sumOf { tx -> 
                if (tx.transaction.type == TransactionType.INCOME) tx.transaction.amount else -tx.transaction.amount 
            }

            // `filtered` est déjà trié par date décroissante : `groupBy` conserve l'ordre de
            // parcours, donc les clés sortent déjà décroissantes et chaque groupe est déjà
            // trié. Les deux re-tris et le `toSortedMap` intermédiaire étaient redondants.
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
            // `appliedQuery` et non `query` : c'est la requête avec laquelle `filtered` a
            // réellement été calculé, les deux ne coïncident pas pendant le debounce.
            val hasResultsInOtherMonths = appliedQuery.isNotBlank() && filtered.isEmpty()

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

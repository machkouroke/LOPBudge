package com.lop.budget.ui.screens.monthly

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.CategoryEntity
import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.data.repository.AccountRepository
import com.lop.budget.data.repository.SettingsRepository
import com.lop.budget.domain.BreakdownEngine
import com.lop.budget.domain.CategoryBreakdown
import com.lop.budget.domain.model.DayGroup
import com.lop.budget.domain.model.NO_CATEGORY_LABEL
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.domain.usecase.category.ObserveCategoriesUseCase
import com.lop.budget.domain.usecase.transaction.SearchTransactionsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

enum class PaidFilter { ALL, PAID, PLANNED }
enum class InsightMode { CATEGORY, TAG }

/** Période analysée, en jours entiers, bornes incluses (LOP-40, P-1). */
data class AnalysisPeriod(val start: LocalDate, val end: LocalDate) {
    companion object {
        fun of(month: YearMonth) = AnalysisPeriod(month.atDay(1), month.atEndOfMonth())
    }
}

/** Capsules visibles avant « Voir toutes » : les six portions individuelles de l'anneau (P-9). */
const val LEGEND_SLOTS = 6

/**
 * Catégories de la légende : au plus [LEGEND_SLOTS] emplacements (LOP-40, CA-09).
 *
 * La catégorie active hors des premières prend le dernier, sans réordonner les autres : la grille
 * ne saute pas sous le doigt.
 */
fun legendSlots(choices: List<CategoryBreakdown>, selectedId: Long?): List<CategoryBreakdown> {
    val first = choices.take(LEGEND_SLOTS)
    if (selectedId == null || first.any { it.categoryId == selectedId }) return first
    return choices.take(LEGEND_SLOTS - 1) + listOfNotNull(choices.find { it.categoryId == selectedId })
}

data class MonthlyTransactionsUiState(
    val month: YearMonth = YearMonth.now(),
    /** Le mode historique la cale sur [month] ; seul le mode Analyses la change (CA-05). */
    val period: AnalysisPeriod = AnalysisPeriod.of(month),
    val type: TransactionType? = null, // null means BOTH income and expense
    val filter: PaidFilter = PaidFilter.ALL,
    val insightMode: InsightMode = InsightMode.CATEGORY,
    val searchQuery: String = "",
    val hasResultsInOtherMonths: Boolean = false,
    val selectedAccountId: Long? = null,
    val selectedCategoryId: Long? = null,
    val currency: String = "EUR",
    val total: Long = 0L,
    val breakdown: List<CategoryBreakdown> = emptyList(),
    val dayGroups: List<DayGroup> = emptyList(),
    val transactions: List<TransactionWithRelations> = emptyList(),
    val availableAccounts: List<AccountEntity> = emptyList(),
    val availableCategories: List<CategoryEntity> = emptyList(),
    val isAnalyticsMode: Boolean = false,
    /** Version par transaction pour forcer la recréation après Undo. */
    val txVersions: Map<Long, Int> = emptyMap(),
    /**
     * Catégories proposées au choix, en mode Analyses : celles de période + type + statut,
     * **sans** le filtre de catégorie, pour que les autres restent sélectionnables (CA-06, P-2).
     * Par montant décroissant puis par nom (CA-09). La catégorie active y figure même sans
     * mouvement sur la période, pour rester visible et retirable (CA-05).
     */
    val categoryChoices: List<CategoryBreakdown> = emptyList(),
    /**
     * `true` tant que [total], [breakdown], [dayGroups] et [transactions] ne sont pas ceux des
     * critères affichés : ils viennent alors d'une sélection précédente, ou sont vides avant le
     * premier résultat. L'analyse ne les montre pas (I-3, CA-10).
     */
    val isRefreshing: Boolean = false,
    /** Lecture en échec pour les critères affichés, en mode Analyses (CA-10). */
    val loadFailed: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class MonthlyTransactionsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    accountRepo: AccountRepository,
    observeCategories: ObserveCategoriesUseCase,
    private val searchTransactionsUseCase: SearchTransactionsUseCase,
    settings: SettingsRepository,
) : ViewModel() {

    private val initialType = savedStateHandle.get<String>("type")?.let { TransactionType.valueOf(it) }
    private val initialMonth = savedStateHandle.get<String>("ym")?.let { YearMonth.parse(it) }
        ?: YearMonth.now()
    private val initialMode = savedStateHandle.get<String>("mode") ?: "HISTORY"
    private val analytics = initialMode == "ANALYTICS"

    private val period = MutableStateFlow(AnalysisPeriod.of(initialMonth))
    private val type = MutableStateFlow<TransactionType?>(initialType) // null = ALL
    private val filter = MutableStateFlow(PaidFilter.ALL)
    private val insightMode = MutableStateFlow(InsightMode.CATEGORY)
    private val searchQuery = MutableStateFlow("")
    private val selectedAccountId = MutableStateFlow<Long?>(null)
    private val selectedCategoryId = MutableStateFlow<Long?>(null)

    /** Numéro de lecture : [retry] relance les recherches sans changer aucun critère affiché. */
    private val attempt = MutableStateFlow(0)

    fun setFilter(f: PaidFilter) { filter.value = f }
    fun onQueryChange(q: String) { searchQuery.value = q }
    fun onAccountFilterChange(id: Long?) { selectedAccountId.value = id }
    fun onCategoryFilterChange(id: Long?) { selectedCategoryId.value = id }
    fun setType(t: TransactionType?) { type.value = t }

    /**
     * LOP-40, CA-05 : une fin antérieure au début ne change pas l'analyse ; un seul jour est
     * accepté. Type, statut et catégorie sont conservés.
     */
    fun setPeriod(start: LocalDate, end: LocalDate) {
        if (end.isBefore(start)) return
        period.value = AnalysisPeriod(start, end)
    }

    /** LOP-40, CA-06 : une autre catégorie remplace l'active, retaper l'active retire le filtre. */
    fun toggleCategory(id: Long) {
        selectedCategoryId.update { if (it == id) null else id }
    }

    /** LOP-40, CA-10 : relit après un échec, avec les critères affichés. */
    fun retry() {
        attempt.update { it + 1 }
    }

    private fun AnalysisPeriod.range(): Pair<Long, Long> {
        val zone = ZoneId.systemDefault()
        return start.atStartOfDay(zone).toInstant().toEpochMilli() to
            // LOP-40, CA-02 : la fin devrait être 23:59:59.999. Les 999 dernières millisecondes
            // du dernier jour restent exclues ; défaut reconduit tel quel (LOP-197).
            end.atTime(23, 59, 59).atZone(zone).toInstant().toEpochMilli()
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

    /** Ce qui définit la recherche à lancer. La période en fixe la fenêtre. */
    private data class MonthlySearch(
        val period: AnalysisPeriod,
        val query: String,
        val accountId: Long?,
        val categoryId: Long?,
        val type: TransactionType?,
        val status: TransactionStatus?,
        val attempt: Int,
    )

    /**
     * Type, statut et numéro de lecture réunis en amont pour tenir dans un `combine` typé : au-delà
     * de cinq flux, seule la forme à `Array<*>` existe, et elle troque la vérification du
     * compilateur contre des transtypages.
     */
    private data class ScreenFilters(
        val type: TransactionType?,
        val status: TransactionStatus?,
        val attempt: Int,
    )

    private val screenFilters = combine(type, filter, attempt) { t, f, n -> ScreenFilters(t, f.status(), n) }

    private fun PaidFilter.status(): TransactionStatus? = when (this) {
        PaidFilter.ALL -> null
        PaidFilter.PAID -> TransactionStatus.PAID
        PaidFilter.PLANNED -> TransactionStatus.PLANNED
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
        val failed: Boolean = false,
    )

    /**
     * `distinctUntilChanged` avant tout `flatMapLatest` : sans lui, une émission de `searchQuery`
     * qui ne change pas la valeur débouncée relancerait la recherche pour rien.
     */
    private val searchCriteria = combine(
        period,
        filterQuery,
        selectedAccountId,
        selectedCategoryId,
        screenFilters,
    ) { p, query, accId, catId, f ->
        MonthlySearch(p, query, accId, catId, f.type, f.status, f.attempt)
    }.distinctUntilChanged()

    /**
     * C'est **le même use case que l'écran Recherche**, appelé avec la période pour fenêtre : la
     * règle de correspondance (titre/note) et **tous** les filtres, type et statut payé/planifié
     * compris, ne vivent qu'à un seul endroit et se testent une seule fois. Cet écran choisit les
     * critères qu'il expose ; il ne refiltre pas les lignes rendues (I-4).
     *
     * Le critère voyage avec ses lignes : l'état doit savoir quelle requête a réellement produit
     * la liste, et non lire les filtres bruts qui la devancent (I-3 de LOP-40).
     *
     * Seul le mode Analyses a un état d'erreur (CA-10 de LOP-40) : en mode historique, une erreur
     * de lecture remonte comme avant.
     */
    private fun search(c: MonthlySearch): Flow<MonthlyResult> {
        val (start, end) = c.period.range()
        val rows = searchTransactionsUseCase(
            query = c.query,
            accountId = c.accountId,
            categoryId = c.categoryId,
            startDate = start,
            endDate = end,
            type = c.type,
            status = c.status,
        ).map { MonthlyResult(c, it) }
        return if (analytics) rows.catch { emit(MonthlyResult(c, emptyList(), failed = true)) } else rows
    }

    private val monthResults = searchCriteria.flatMapLatest(::search)

    /**
     * Choix de catégories du mode Analyses : mêmes critères, **sans** la catégorie (P-2 de LOP-40).
     * Ce n'est pas un second moteur, rien n'est refiltré ici. Changer de catégorie ne relance pas
     * cette lecture : l'ordre des choix ne bouge pas sous le doigt (CA-09).
     */
    private val choiceResults: Flow<MonthlyResult?> =
        if (!analytics) flowOf(null)
        else searchCriteria.map { it.copy(categoryId = null) }.distinctUntilChanged().flatMapLatest(::search)

    /** Catégorie active sans mouvement sur la période : elle reste visible et retirable (CA-05). */
    private fun withoutMovement(id: Long, categories: List<CategoryEntity>): CategoryBreakdown {
        val category = categories.find { it.id == id }
        return CategoryBreakdown(
            name = category?.name ?: NO_CATEGORY_LABEL,
            colorArgb = category?.colorArgb ?: BreakdownEngine.NO_CATEGORY_COLOR,
            total = 0L,
            share = 0.0,
            categoryId = id,
        )
    }

    val uiState: StateFlow<MonthlyTransactionsUiState> =
        combine(
            monthResults,
            choiceResults,
            settings.currency,
            period,
            type,
            filter,
            insightMode,
            searchQuery,
            selectedAccountId,
            selectedCategoryId,
            accountRepo.observeAll(),
            observeCategories(),
            attempt,
        ) { args ->
            val result = args[0] as MonthlyResult
            val choices = args[1] as MonthlyResult?
            val currency = args[2] as String
            val p = args[3] as AnalysisPeriod
            val t = args[4] as TransactionType?
            val f = args[5] as PaidFilter
            val mode = args[6] as InsightMode
            // Saisie brute : n'alimente que le champ de texte, jamais le filtrage.
            val query = args[7] as String
            val accId = args[8] as Long?
            val catId = args[9] as Long?
            val accounts = args[10] as List<AccountEntity>
            val categories = args[11] as List<CategoryEntity>
            val n = args[12] as Int
            val (criteria, filtered) = result

            // I-3 de LOP-40 : un résultat n'est présenté que s'il a été produit par les critères
            // affichés. Sinon il vient d'une sélection précédente, et l'analyse ne le montre pas.
            // Pendant le debounce de la recherche, le mode historique voit aussi ce drapeau levé ;
            // il ne le lit pas.
            val displayed = MonthlySearch(p, query, accId, catId, t, f.status(), n)
            val refreshing = criteria != displayed ||
                (choices != null && choices.criteria != displayed.copy(categoryId = null))

            // Tous les critères ont été appliqués par le use case, qui rend les lignes déjà
            // triées. `t` et `f` ne servent plus qu'à réafficher l'état des puces : les relire
            // ici pour refiltrer recréerait un second moteur (I-4).
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

            // La règle de regroupement vit dans le domaine : l'écran des analyses en portait
            // une copie (I-12, P-12 de LOP-87).
            val breakdown = when (mode) {
                InsightMode.CATEGORY -> BreakdownEngine.byCategory(filtered)
                InsightMode.TAG -> BreakdownEngine.byTag(filtered)
            }

            // CA-09 de LOP-40 : montant décroissant, puis nom à montant égal.
            val categoryChoices = choices?.let { c ->
                val ordered = BreakdownEngine.byCategory(c.rows)
                    .sortedWith(compareByDescending<CategoryBreakdown> { it.total }.thenBy { it.name })
                if (catId == null || ordered.any { it.categoryId == catId }) ordered
                else ordered + withoutMovement(catId, categories)
            }.orEmpty()

            // Check if results exist globally if none in current month.
            // `criteria.query` et non `query` : c'est la requête avec laquelle ces lignes ont
            // réellement été cherchées, les deux ne coïncident pas pendant le debounce.
            val hasResultsInOtherMonths = criteria.query.isNotBlank() && filtered.isEmpty()

            MonthlyTransactionsUiState(
                month = YearMonth.from(p.start),
                period = p,
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
                txVersions = emptyMap(), // On délègue au SharedViewModel
                categoryChoices = categoryChoices,
                isRefreshing = refreshing,
                loadFailed = !refreshing && (result.failed || choices?.failed == true),
            )
        }
            // `stateIn(viewModelScope)` collecte sur `Dispatchers.Main.immediate` : sans ce
            // `flowOn`, tout le filtrage/tri/groupement ci-dessus s'exécute sur le thread UI.
            .flowOn(Dispatchers.Default)
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                // Les critères reçus, sans résultat : l'écran s'ouvre sur le type et la période
                // demandés, et rien n'est présenté comme leur résultat avant la première lecture
                // (I-3, CA-01 de LOP-40).
                MonthlyTransactionsUiState(
                    month = initialMonth,
                    type = initialType,
                    isAnalyticsMode = analytics,
                    isRefreshing = true,
                ),
            )

    private companion object {
        /** Aligné sur le `debounce(300)` de `SearchViewModel`. */
        const val SEARCH_DEBOUNCE_MS = 300L
    }
}

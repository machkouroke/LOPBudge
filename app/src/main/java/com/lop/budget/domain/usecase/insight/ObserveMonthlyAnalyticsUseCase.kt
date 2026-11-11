package com.lop.budget.domain.usecase.insight

import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.domain.BreakdownEngine
import com.lop.budget.domain.CategoryBreakdown
import com.lop.budget.domain.model.NO_CATEGORY_LABEL
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.domain.usecase.transaction.ObserveTransactionsUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Agrégats métier d'une période, en centimes.
 *
 * [total] est le total du type demandé — c'est ce que l'écran d'analyses affiche. [income],
 * [expense] et [balance] sont les trois mesures que CA-14 et CA-14b comparent avant et après
 * un ajustement ; [balance] est le « solde du mois », dérivé et non stocké.
 */
data class MonthlyAnalytics(
    val income: Long = 0L,
    val expense: Long = 0L,
    val balance: Long = 0L,
    val total: Long = 0L,
    val breakdown: List<CategoryBreakdown> = emptyList(),
)

/**
 * Lecture métier des agrégats d'une période (LOP-87, use case n° 4 — « agrégats »).
 *
 * Extrait d'`AnalyticsViewModel` à comportement **constant** : un ViewModel n'est pas un lieu
 * de règles (I-12, P-12), et un agrégat calculé dans un ViewModel n'est atteignable par aucun
 * test d'intégration (CA-26).
 *
 * Les ajustements de solde n'entrent dans aucun de ces agrégats (I-2, I-11) : l'exclusion est
 * portée **une seule fois**, par `TransactionDao.observeForMerge`, dont ce use case hérite via
 * [ObserveTransactionsUseCase]. Rien n'est filtré ici, et aucune option ne permet de les inclure
 * (P-4, CA-13). L'écart E-1 est corrigé depuis le 13 septembre 2026.
 */
@Singleton
class ObserveMonthlyAnalyticsUseCase @Inject constructor(
    private val observeTransactionsUseCase: ObserveTransactionsUseCase,
) {
    /** [accountId] à `null` agrège tous les comptes ; sinon, le seul compte demandé. */
    operator fun invoke(
        start: Long,
        end: Long,
        type: TransactionType,
        accountId: Long? = null,
    ): Flow<MonthlyAnalytics> =
        observeTransactionsUseCase(start, end, accountId).map { txs ->
            val paid = txs.filter { it.transaction.status == TransactionStatus.PAID }
            val income = paid.sumAmountOf(TransactionType.INCOME)
            val expense = paid.sumAmountOf(TransactionType.EXPENSE)

            val filtered = paid.filter { it.transaction.type == type }
            val totalAmount = filtered.sumOf { it.transaction.amount }

            MonthlyAnalytics(
                income = income,
                expense = expense,
                balance = income - expense,
                total = totalAmount,
                breakdown = BreakdownEngine.byCategory(filtered),
            )
        }.flowOn(Dispatchers.Default)

    /**
     * Série de tendance de [start]..[end] (LOP-235) : une case par jour ([ChronoUnit.DAYS]) ou par
     * mois ([ChronoUnit.MONTHS]), de la première à la dernière, zéros compris (CA-02).
     *
     * Payées **et** planifiées, occurrences virtuelles comprises, tous comptes et sans compte (P-5).
     * Chaque case ne compte que ses transactions, à leur date affichée, sans cumul (P-4, CA-01).
     * Supprimées, ajustements et doublons d'occurrence sont déjà écartés par
     * [ObserveTransactionsUseCase] (I-2) ; rien n'est écrit (I-1).
     */
    fun trend(start: Long, end: Long, unit: ChronoUnit, zone: ZoneId): Flow<AnalyticsTrend> =
        observeTransactionsUseCase(start, end).map { rows ->
            trendPoints(rows, start.toLocalDate(zone), end.toLocalDate(zone), unit, zone)
        }.flowOn(Dispatchers.Default)

    /**
     * Catégories au-dessus de leurs habitudes en [month] (LOP-240), ou `null` pour un mois futur :
     * la carte n'y figure pas (CA-06). Jour de comparaison : [today] pour le mois courant, le
     * dernier jour pour un mois passé. Une seule lecture, du 1er du sixième mois de référence au
     * jour de comparaison inclus ; supprimées et ajustements en sont déjà absents (I-2) ; rien
     * n'est écrit (I-1).
     */
    fun aboveHabits(month: YearMonth, today: LocalDate, zone: ZoneId): Flow<HabitsComparison?> {
        val day = when {
            month > YearMonth.from(today) -> return flowOf(null)
            month == YearMonth.from(today) -> today
            else -> month.atEndOfMonth()
        }
        val start = month.minusMonths(REFERENCE_MONTHS).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        // Dernière milliseconde du jour de comparaison incluse (même cause que LOP-197).
        val end = day.atTime(23, 59, 59, 999_000_000).atZone(zone).toInstant().toEpochMilli()
        return observeTransactionsUseCase(start, end)
            .map { rows -> habitsComparison(rows, month, day, zone) }
            .flowOn(Dispatchers.Default)
    }

    /**
     * Solde du mois sur la fenêtre de douze mois qui finit en [last] (LOP-242). Chaque mois suit
     * LOP-231 par la découpe de la tendance : payées et planifiées, occurrences virtuelles, tous
     * comptes et sans compte (CA-03). Un mois couvert n'est ni après [current], ni avant le mois de
     * la première transaction (CA-02). Supprimées, ajustements et doublons d'occurrence sont déjà
     * écartés (I-2) ; rien n'est écrit (I-1).
     */
    fun balanceHistory(last: YearMonth, current: YearMonth, zone: ZoneId): Flow<BalanceHistory> {
        val first = last.minusMonths(BALANCE_WINDOW_MONTHS - 1)
        val start = first.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        // Dernière milliseconde du dernier mois incluse (même cause que LOP-197).
        val end = last.atEndOfMonth().atTime(23, 59, 59, 999_000_000).atZone(zone).toInstant().toEpochMilli()
        return combine(observeTransactionsUseCase(start, end), observeTransactionsUseCase.observeFirstDate()) { rows, firstDate ->
            val points = trendPoints(rows, first.atDay(1), last.atEndOfMonth(), ChronoUnit.MONTHS, zone).points
            balanceHistory(points, firstDate?.let { YearMonth.from(it.toLocalDate(zone)) }, current)
        }.flowOn(Dispatchers.Default)
    }
}

/** Revenus et solde d'un mois couvert (LOP-242), en centimes ; solde = revenus − dépenses (LOP-231). */
data class MonthBalance(val income: Long, val balance: Long)

/**
 * Fenêtre de LOP-242. [months] : les douze mois, du plus ancien au plus récent. [covered] : les
 * seuls mois couverts ; un mois absent est un emplacement vide, ni barre ni zéro (CA-02).
 */
data class BalanceHistory(val months: List<YearMonth>, val covered: Map<YearMonth, MonthBalance>) {
    /** Solde moyen, tronqué au centime (vers zéro) ; `null` sans mois couvert (CA-05). */
    val average: Long? get() = covered.values.takeIf { it.isNotEmpty() }?.let { m -> m.sumOf { it.balance } / m.size }

    /** Taux de solde en pourcentage entier tronqué ; `null` si les revenus couverts sont nuls (CA-05). */
    val rate: Long? get() {
        val income = covered.values.sumOf { it.income }
        return if (income == 0L) null else covered.values.sumOf { it.balance } * 100 / income
    }
}

private const val BALANCE_WINDOW_MONTHS = 12L

/**
 * Mois couverts de la fenêtre [points] (une case par mois) : de [firstMonth], mois de la première
 * transaction, à [current] inclus (CA-02, P-5 de LOP-242). [firstMonth] `null` : aucun mois couvert.
 */
internal fun balanceHistory(points: List<TrendPoint>, firstMonth: YearMonth?, current: YearMonth): BalanceHistory {
    val months = points.map { YearMonth.from(it.start) }
    val covered = points
        .filter { p -> YearMonth.from(p.start).let { firstMonth != null && it >= firstMonth && it <= current } }
        .associate { YearMonth.from(it.start) to MonthBalance(it.income, it.income - it.expense) }
    return BalanceHistory(months, covered)
}

/** Résultat de la carte de LOP-240 pour un mois passé ou courant. */
sealed interface HabitsComparison {
    /** CA-05 : moins de trois mois de référence contiennent une transaction ; [missing] = 3 − ce nombre. */
    data class NotEnoughHistory(val missing: Int) : HabitsComparison

    /**
     * [until] : jour de comparaison, fin de la période ouverte au toucher d'une ligne (CA-07).
     * [lines] vide : « Aucune catégorie au-dessus de vos habitudes » (CA-05).
     */
    data class Ready(val until: LocalDate, val lines: List<CategoryAboveHabits>) : HabitsComparison
}

/** Une ligne de la carte : [spent] dépensé à date, [usual] niveau habituel ; en centimes. */
data class CategoryAboveHabits(
    val categoryId: Long,
    val name: String,
    val colorArgb: Int,
    /** Nom d'icône de la catégorie (`CategoryEntity.icon`) ; `null` sans catégorie résolue. */
    val icon: String?,
    val spent: Long,
    val usual: Long,
) {
    val gap: Long get() = spent - usual
}

private const val REFERENCE_MONTHS = 6L
private const val MIN_HISTORY_MONTHS = 3
private const val MAX_LINES = 3
private const val MIN_GAP_CENTS = 2_000L

/**
 * Règles du vocabulaire de LOP-240 (P-2), sur les lignes lues de `month − 6` au jour [day].
 *
 * Dépensé à date : dépenses d'une catégorie, ponctuelles et occurrences de séries (P-5), payées et
 * planifiées, du 1er au jour de comparaison, borné à la longueur de chaque mois (CA-02). Un mois de
 * référence compte dans l'historique dès qu'il contient une transaction, quelle qu'elle soit (P-6).
 */
internal fun habitsComparison(
    rows: List<TransactionWithRelations>,
    month: YearMonth,
    day: LocalDate,
    zone: ZoneId,
): HabitsComparison {
    val references = (1..REFERENCE_MONTHS).map { month.minusMonths(it) }
    val byMonth = rows.groupBy { YearMonth.from(it.transaction.date.toLocalDate(zone)) }
    val withHistory = references.count { !byMonth[it].isNullOrEmpty() }
    if (withHistory < MIN_HISTORY_MONTHS) return HabitsComparison.NotEnoughHistory(MIN_HISTORY_MONTHS - withHistory)

    fun spentToDate(ym: YearMonth): Map<Long, Long> {
        val lastDay = minOf(day.dayOfMonth, ym.lengthOfMonth())
        return byMonth[ym].orEmpty()
            .filter { it.transaction.type == TransactionType.EXPENSE && it.transaction.date.toLocalDate(zone).dayOfMonth <= lastDay }
            .groupBy({ it.transaction.categoryId }, { it.transaction.amount })
            .mapValues { (_, amounts) -> amounts.sum() }
    }

    val referenceSpent = references.map(::spentToDate)
    val categories = byMonth[month].orEmpty().associate { it.transaction.categoryId to it.category }
    val lines = spentToDate(month)
        .map { (categoryId, spent) ->
            val category = categories[categoryId]
            CategoryAboveHabits(
                categoryId = categoryId,
                name = category?.name ?: NO_CATEGORY_LABEL,
                colorArgb = category?.colorArgb ?: BreakdownEngine.NO_CATEGORY_COLOR,
                icon = category?.icon,
                spent = spent,
                usual = medianOfEven(referenceSpent.map { it[categoryId] ?: 0L }),
            )
        }
        // Au moins 20 € et au moins 20 % de l'habituel (5 × écart ≥ habituel, exact en entiers) ;
        // un habituel nul n'exige que les 20 €.
        .filter { it.gap >= MIN_GAP_CENTS && it.gap * 5 >= it.usual }
        .sortedWith(compareByDescending<CategoryAboveHabits> { it.gap }.thenBy { it.name })
        .take(MAX_LINES)
    return HabitsComparison.Ready(day, lines)
}

/** Moyenne des deux valeurs centrales, arrondie au centime inférieur ; un mois sans dépense vaut 0. */
private fun medianOfEven(values: List<Long>): Long {
    val sorted = values.sorted()
    val mid = sorted.size / 2
    return Math.floorDiv(sorted[mid - 1] + sorted[mid], 2L)
}

/** Une case de la série de tendance : un jour en mode Mois, un mois en mode Année ; en centimes. */
data class TrendPoint(val start: LocalDate, val income: Long, val expense: Long)

/**
 * Série de tendance et totaux des tuiles d'une période (LOP-235, LOP-233).
 *
 * [income] et [expense] sont les sommes des points sur la période, payées et planifiées comprises
 * (CA-01 de LOP-233, CA-05 de LOP-235). [empty] : aucune transaction lue sur la période (CA-04).
 */
data class AnalyticsTrend(
    val points: List<TrendPoint> = emptyList(),
    val empty: Boolean = true,
    val income: Long = 0L,
    val expense: Long = 0L,
) {
    /** Tuile Solde de LOP-233 (P-6, CA-07) : revenus − dépenses, comme le « Solde du mois » de l'accueil. */
    val balance: Long get() = income - expense
}

/** Cases de [first] à [last] inclus ; chaque ligne tombe dans celle de sa date affichée dans [zone]. */
internal fun trendPoints(
    rows: List<TransactionWithRelations>,
    first: LocalDate,
    last: LocalDate,
    unit: ChronoUnit,
    zone: ZoneId,
): AnalyticsTrend {
    require(unit == ChronoUnit.DAYS || unit == ChronoUnit.MONTHS) { "unit: $unit" }
    fun caseOf(date: LocalDate) = if (unit == ChronoUnit.MONTHS) date.withDayOfMonth(1) else date
    val byCase = rows.groupBy { caseOf(it.transaction.date.toLocalDate(zone)) }
    val points = generateSequence(caseOf(first)) { it.plus(1, unit) }
        .takeWhile { !it.isAfter(last) }
        .map { case ->
            val txs = byCase[case].orEmpty()
            TrendPoint(case, txs.sumAmountOf(TransactionType.INCOME), txs.sumAmountOf(TransactionType.EXPENSE))
        }
        .toList()
    return AnalyticsTrend(
        points = points,
        empty = rows.isEmpty(),
        income = points.sumOf { it.income },
        expense = points.sumOf { it.expense },
    )
}

private fun Long.toLocalDate(zone: ZoneId): LocalDate = Instant.ofEpochMilli(this).atZone(zone).toLocalDate()

private fun List<TransactionWithRelations>.sumAmountOf(type: TransactionType): Long =
    filter { it.transaction.type == type }.sumOf { it.transaction.amount }

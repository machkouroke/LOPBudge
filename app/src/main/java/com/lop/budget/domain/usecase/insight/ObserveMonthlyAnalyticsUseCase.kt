package com.lop.budget.domain.usecase.insight

import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.domain.BreakdownEngine
import com.lop.budget.domain.CategoryBreakdown
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.domain.usecase.transaction.ObserveTransactionsUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
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
)

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

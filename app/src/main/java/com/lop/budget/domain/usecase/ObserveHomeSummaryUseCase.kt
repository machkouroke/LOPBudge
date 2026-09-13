package com.lop.budget.domain.usecase

import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.domain.model.DayGroup
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Indicateurs d'accueil d'une période, montants en centimes. */
data class HomeSummary(
    val income: Long = 0L,
    val expense: Long = 0L,
    /** « Solde du mois » : dérivé de [income] et [expense], jamais stocké (CA-14). */
    val balance: Long = 0L,
    val previousPeriodExpense: Long = 0L,
    val projectedBalance: Long = 0L,
    val daysUntilPayday: Int? = null,
    val upcoming: List<TransactionWithRelations> = emptyList(),
    val subscriptions: List<TransactionWithRelations> = emptyList(),
    val dayGroups: List<DayGroup> = emptyList(),
    val dashboardTransactions: List<TransactionWithRelations> = emptyList(),
)

/**
 * Lecture métier des indicateurs d'accueil (LOP-87, use case n° 4 — « accueil »).
 *
 * Extrait de `HomeViewModel` à comportement **constant** : solde projeté, prochaine paie,
 * groupes par jour et listes tronquées sont des règles métier, pas de l'état d'écran (I-12,
 * P-12), et un test d'intégration ne peut pas les atteindre depuis un ViewModel (CA-26).
 *
 * L'horloge est injectée là où le ViewModel lisait `System.currentTimeMillis()` et
 * `LocalDate.now()` : le fuseau, lui, reste relu à chaque appel (même règle que
 * [SearchTransactionsUseCase]).
 *
 * ÉCART E-1 (LOP-87, I-2, I-11) : la source n'exclut **pas** les ajustements. Ils entrent donc
 * dans les revenus, les dépenses, le solde projeté, les groupes par jour et la liste du
 * tableau de bord, où ils peuvent évincer une transaction réelle.
 */
@Singleton
class ObserveHomeSummaryUseCase @Inject constructor(
    private val observeTransactionsUseCase: ObserveTransactionsUseCase,
    private val clock: Clock,
) {
    /** [accountId] à `null` agrège tous les comptes ; sinon, le seul compte demandé, sur les **deux** fenêtres. */
    operator fun invoke(
        start: Long,
        end: Long,
        previousStart: Long,
        previousEnd: Long,
        accountId: Long? = null,
    ): Flow<HomeSummary> = combine(
        observeTransactionsUseCase(start, end, accountId),
        observeTransactionsUseCase(previousStart, previousEnd, accountId),
    ) { txs, previousTxs ->
        val income = txs.sumAmountOf(TransactionType.INCOME)
        val expense = txs.sumAmountOf(TransactionType.EXPENSE)
        val now = clock.millis()

        val plannedExpense = txs
            .filter { it.transaction.status == TransactionStatus.PLANNED }
            .sumAmountOf(TransactionType.EXPENSE)

        HomeSummary(
            income = income,
            expense = expense,
            balance = income - expense,
            previousPeriodExpense = previousTxs.sumAmountOf(TransactionType.EXPENSE),
            projectedBalance = income - expense - plannedExpense,
            daysUntilPayday = nextPayday(txs, now),
            upcoming = txs
                .filter { it.transaction.status == TransactionStatus.PLANNED && it.transaction.date >= now }
                .sortedBy { it.transaction.date }
                .take(8),
            subscriptions = txs
                .filter { it.transaction.status == TransactionStatus.PLANNED && it.transaction.seriesId != null }
                .sortedBy { it.transaction.date },
            dayGroups = dayGroups(txs),
            dashboardTransactions = dashboardTransactions(txs, now),
        )
    }.flowOn(Dispatchers.Default)

    /**
     * Un seul tri : `groupBy` conserve l'ordre de parcours, donc les clés sortent déjà par date
     * décroissante et chaque groupe est déjà trié.
     */
    private fun dayGroups(txs: List<TransactionWithRelations>): List<DayGroup> {
        val zone = ZoneId.systemDefault()
        return txs
            .sortedByDescending { it.transaction.date }
            .groupBy { Instant.ofEpochMilli(it.transaction.date).atZone(zone).toLocalDate() }
            .map { (date, list) ->
                DayGroup(
                    date = date,
                    total = list.sumOf { tx ->
                        if (tx.transaction.type == TransactionType.INCOME) tx.transaction.amount
                        else -tx.transaction.amount
                    },
                    transactions = list,
                )
            }
    }

    /** Jours restants avant le prochain revenu à venir, 0 au minimum, `null` s'il n'y en a pas. */
    private fun nextPayday(txs: List<TransactionWithRelations>, now: Long): Int? {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(clock.withZone(zone))
        return txs
            .filter { it.transaction.type == TransactionType.INCOME && it.transaction.date >= now }
            .minByOrNull { it.transaction.date }
            ?.let {
                val date = Instant.ofEpochMilli(it.transaction.date).atZone(zone).toLocalDate()
                ChronoUnit.DAYS.between(today, date).toInt().coerceAtLeast(0)
            }
    }

    /**
     * Les 3 transactions du tableau de bord : celles du jour d'abord, puis les plus récentes.
     *
     * Les bornes de la journée sont converties **une fois** en millis : `date in dayStart until
     * dayEnd` est le même prédicat que `toLocalDate() == today`, sans allouer un
     * `ZonedDateTime` par comparaison.
     */
    private fun dashboardTransactions(
        txs: List<TransactionWithRelations>,
        now: Long,
    ): List<TransactionWithRelations> {
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val dayStart = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        return txs.sortedWith(
            compareByDescending<TransactionWithRelations> {
                it.transaction.date in dayStart until dayEnd
            }.thenByDescending { it.transaction.date }
        ).take(3)
    }
}

private fun List<TransactionWithRelations>.sumAmountOf(type: TransactionType): Long =
    filter { it.transaction.type == type }.sumOf { it.transaction.amount }

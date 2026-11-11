package com.lop.budget.domain.usecase.insight

import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.data.repository.TransactionRepository
import com.lop.budget.domain.BalanceEngine
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.domain.usecase.account.GetAccountBalancesUseCase
import com.lop.budget.domain.usecase.transaction.ObserveTransactionsUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Mouvement à venir de LOP-239 et solde projeté juste après lui, en centimes (CA-06). */
data class UpcomingMovement(val transaction: TransactionWithRelations, val balanceAfter: Long)

/** Point bas : solde projeté le plus faible de l'horizon et son jour, le premier à égalité (CA-02). */
data class LowPoint(val amount: Long, val date: LocalDate)

/**
 * Carte de LOP-239, montants en centimes. L'horizon va d'aujourd'hui à la veille de [payday] :
 * [days] jours. [perDay] est `null` en dépassement (CA-04) ; [lowPoint] l'est s'il n'est pas
 * négatif (CA-02). [movements] par date croissante : la liste part de [totalBalance] et sa dernière
 * ligne se termine sur [remaining] (CA-06).
 */
data class RemainingToSpend(
    val payday: LocalDate,
    val days: Int,
    val totalBalance: Long,
    val remaining: Long,
    val perDay: Long?,
    val lowPoint: LowPoint?,
    val movements: List<UpcomingMovement>,
)

/**
 * Reste à dépenser jusqu'à la prochaine paie (LOP-239). Ne fait que lire (I-1) : séries, fusion
 * des transactions et soldes. Ajustements, supprimées et doubles représentations d'une occurrence
 * sont déjà écartés par la fusion de [ObserveTransactionsUseCase] (I-2).
 */
@Singleton
class ObserveRemainingToSpendUseCase @Inject constructor(
    private val transactionRepo: TransactionRepository,
    private val observeTransactions: ObserveTransactionsUseCase,
    private val getAccountBalances: GetAccountBalancesUseCase,
) {
    /** [today] lu par l'appelant sur l'horloge injectée. `null` : aucune paie connue (CA-05). */
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(today: LocalDate, zone: ZoneId): Flow<RemainingToSpend?> =
        observePayday(today, zone).flatMapLatest { payday ->
            if (payday == null) return@flatMapLatest flowOf(null)
            // ponytail: fenêtre ouverte à 0 pour attraper toutes les planifiées passées (P-3) : la
            // fusion relit tout l'historique. La borner à la plus ancienne planifiée si le délai se voit.
            combine(
                getAccountBalances.observe(),
                observeTransactions(0L, lastMilliOf(payday.minusDays(1), zone)),
            ) { balances, rows ->
                val inTotal = balances.accounts
                    .filter { BalanceEngine.countsInTotal(it.account) }
                    .mapTo(HashSet()) { it.account.id }
                // CA-03 : une payée est déjà dans le solde total ; un compte archivé, exclu ou absent
                // (`NO_ACCOUNT_ID`) n'est pas dans `inTotal`.
                val planned = rows.filter {
                    it.transaction.status == TransactionStatus.PLANNED && it.transaction.accountId in inTotal
                }
                remainingToSpend(today, payday, zone, balances.total, planned)
            }
        }.flowOn(Dispatchers.Default)

    /**
     * P-2 : prochaine occurrence **visible**, strictement après [today], d'une série de revenu non
     * annulée ; la plus proche s'il y en a plusieurs. Une occurrence déplacée ou supprimée est lue
     * comme la liste la montre. `observeActiveSeries` écarte les séries annulées ; une série terminée
     * n'a plus d'occurrence à venir (CA-05).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observePayday(today: LocalDate, zone: ZoneId): Flow<LocalDate?> {
        val after = lastMilliOf(today, zone)
        return transactionRepo.observeActiveSeries()
            .map { series -> series.filter { it.type == TransactionType.INCOME }.map { it.id } }
            .distinctUntilChanged()
            .flatMapLatest { ids ->
                if (ids.isEmpty()) return@flatMapLatest flowOf(null)
                combine(ids.map { observeTransactions.observeUpcoming(it, after, 1) }) { nexts ->
                    nexts.mapNotNull { it.firstOrNull()?.transaction?.date }.minOrNull()
                }
            }
            .map { it?.let { date -> Instant.ofEpochMilli(date).atZone(zone).toLocalDate() } }
            .distinctUntilChanged()
    }
}

/**
 * Vocabulaire de LOP-239. [planned] : les mouvements à venir, déjà restreints aux planifiées des
 * comptes du solde total datées au plus tard la veille de [payday]. Une ligne datée avant [today]
 * est due [today] (P-3). Solde projeté d'un jour : [totalBalance] plus les mouvements dus ce jour ou
 * avant. Par jour : reste divisé par [RemainingToSpend.days], centime inférieur (CA-01).
 */
internal fun remainingToSpend(
    today: LocalDate,
    payday: LocalDate,
    zone: ZoneId,
    totalBalance: Long,
    planned: List<TransactionWithRelations>,
): RemainingToSpend {
    val days = ChronoUnit.DAYS.between(today, payday).toInt()
    // Solde projeté en fin de chaque jour qui porte un mouvement, aujourd'hui en tête : un jour sans
    // mouvement reprend la veille, il ne peut donc pas être le premier point bas.
    val endOfDay = LinkedHashMap<LocalDate, Long>().apply { put(today, totalBalance) }
    var balance = totalBalance
    val movements = planned.sortedBy { it.transaction.date }.map { row ->
        balance += if (row.transaction.type == TransactionType.INCOME) row.transaction.amount else -row.transaction.amount
        val due = maxOf(Instant.ofEpochMilli(row.transaction.date).atZone(zone).toLocalDate(), today)
        endOfDay[due] = balance
        UpcomingMovement(row, balance)
    }
    // `minBy` rend le premier minimum dans l'ordre des jours : à égalité, le premier (CA-02).
    val low = endOfDay.entries.minBy { it.value }
    return RemainingToSpend(
        payday = payday,
        days = days,
        totalBalance = totalBalance,
        remaining = balance,
        perDay = if (balance >= 0) balance / days else null,
        lowPoint = if (low.value < 0) LowPoint(low.value, low.key) else null,
        movements = movements,
    )
}

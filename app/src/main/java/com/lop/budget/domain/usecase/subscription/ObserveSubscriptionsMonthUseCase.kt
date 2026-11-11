package com.lop.budget.domain.usecase.subscription

import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.domain.usecase.transaction.ObserveTransactionsUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** Échéances d'abonnement d'un mois, montants en centimes (vocabulaire de LOP-244). */
data class SubscriptionsMonth(
    /** Toutes les échéances, payées et planifiées, au jour de leur date affichée (CA-04). */
    val byDay: Map<LocalDate, List<TransactionWithRelations>> = emptyMap(),
    /** « Abonnements non payés » : les planifiées, par date croissante (CA-02, CA-06). */
    val unpaid: List<TransactionWithRelations> = emptyList(),
    /** « Total du mois » : payées et planifiées confondues (CA-05). */
    val totalAmount: Long = 0L,
    /** « À venir du mois » : les seules planifiées (CA-05). */
    val upcomingAmount: Long = 0L,
    val totalCount: Int = 0,
)

/**
 * I-2 de LOP-244 : échéance d'un abonnement. Supprimées, séries annulées et ajustements de solde
 * sont déjà écartés par la lecture fusionnée ; restent exclus ici les ponctuelles, les séries non
 * marquées et les revenus.
 */
fun TransactionWithRelations.isSubscriptionDue(): Boolean =
    transaction.isSubscription && transaction.type == TransactionType.EXPENSE && transaction.seriesId != null

/** Une seule règle pour l'accueil et l'écran Abonnements : même filtre, mêmes sommes. */
fun subscriptionsMonth(rows: List<TransactionWithRelations>, zone: ZoneId): SubscriptionsMonth {
    val dues = rows.filter { it.isSubscriptionDue() }.sortedBy { it.transaction.date }
    val unpaid = dues.filter { it.transaction.status == TransactionStatus.PLANNED }
    return SubscriptionsMonth(
        byDay = dues.groupBy { Instant.ofEpochMilli(it.transaction.date).atZone(zone).toLocalDate() },
        unpaid = unpaid,
        totalAmount = dues.sumOf { it.transaction.amount },
        upcomingAmount = unpaid.sumOf { it.transaction.amount },
        totalCount = dues.size,
    )
}

/**
 * Calendrier mensuel des abonnements (LOP-244, CA-04 à CA-07).
 *
 * Lecture seule (I-1) : elle passe par la fusion réels + virtuels de [ObserveTransactionsUseCase],
 * sans appeler le moteur de récurrence ni rien matérialiser.
 */
@Singleton
class ObserveSubscriptionsMonthUseCase @Inject constructor(
    private val observeTransactions: ObserveTransactionsUseCase,
) {
    /** Du 1er à 00:00:00.000 au dernier jour à 23:59:59.999 dans [zone], comme l'accueil. */
    operator fun invoke(month: YearMonth, zone: ZoneId): Flow<SubscriptionsMonth> {
        val start = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = month.atEndOfMonth().atTime(23, 59, 59, 999_000_000).atZone(zone).toInstant().toEpochMilli()
        return observeTransactions(start, end)
            .map { subscriptionsMonth(it, zone) }
            .flowOn(Dispatchers.Default)
    }
}

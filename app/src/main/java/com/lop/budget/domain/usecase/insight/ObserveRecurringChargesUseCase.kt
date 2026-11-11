package com.lop.budget.domain.usecase.insight

import com.lop.budget.data.local.entity.CategoryEntity
import com.lop.budget.data.local.entity.RecurringSeriesEntity
import com.lop.budget.data.repository.CategoryRepository
import com.lop.budget.data.repository.TransactionRepository
import com.lop.budget.domain.BreakdownEngine
import com.lop.budget.domain.RecurrenceEngine
import com.lop.budget.domain.model.RecurrenceFrequency
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.domain.usecase.transaction.ObserveTransactionsUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import java.text.Collator
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** Une charge récurrente de la carte de LOP-241 ; coûts équivalents en centimes, arrondis (P-6). */
data class RecurringCharge(
    val seriesId: Long,
    val title: String,
    /** Nom d'icône de la catégorie (`CategoryEntity.icon`) ; `null` sans catégorie résolue. */
    val icon: String?,
    val colorArgb: Int,
    val monthly: Long,
    val annual: Long,
)

/**
 * Carte de LOP-241. [charges] sont triées par coût annuel décroissant, puis par nom (CA-04).
 * [monthlyTotal] et [monthlyIncome] : sommes des coûts mensuels arrondis, des charges et des
 * revenus récurrents (P-6).
 */
data class RecurringCharges(
    val charges: List<RecurringCharge>,
    val monthlyTotal: Long,
    val monthlyIncome: Long,
) {
    /** Part engagée, en pourcentage entier inférieur ; `null` sans revenu récurrent (CA-03). */
    val share: Long? get() = if (monthlyIncome == 0L) null else Math.floorDiv(monthlyTotal * 100, monthlyIncome)
}

/**
 * Charges et revenus récurrents des séries actives aujourd'hui (LOP-241). Ne dépend d'aucune
 * période (CA-06) et n'écrit rien (I-1) : séries et catégories sont seulement lues.
 */
@Singleton
class ObserveRecurringChargesUseCase @Inject constructor(
    private val transactionRepo: TransactionRepository,
    private val categoryRepo: CategoryRepository,
    private val observeTransactions: ObserveTransactionsUseCase,
) {
    /** [today] lu par l'appelant sur l'horloge injectée ; « après aujourd'hui » = dès demain dans [zone]. */
    operator fun invoke(today: LocalDate, zone: ZoneId): Flow<RecurringCharges> {
        val after = lastMilliOf(today, zone)
        // `observeActiveSeries` écarte déjà les séries annulées (CA-02, CA-05).
        return combine(transactionRepo.observeActiveSeries(), categoryRepo.observeAll()) { series, categories ->
            recurringCharges(series, categories.associateBy { it.id }, after)
        }.flowOn(Dispatchers.Default)
    }

    /**
     * Ouverture du calendrier d'une charge. Départ (P-7) : sa dernière occurrence visible au plus
     * tard [today], à défaut la première à venir. Jour d'ouverture (P-10) : toujours la prochaine
     * échéance, que LOP-7 ne déduirait pas d'un départ qui est lui-même la première à venir.
     * `null` si la série n'a plus d'échéance.
     */
    suspend fun calendarStart(seriesId: Long, today: LocalDate, zone: ZoneId): CalendarStart? {
        val series = transactionRepo.getSeriesById(seriesId) ?: return null
        val after = lastMilliOf(today, zone)
        val next = observeTransactions.observeUpcoming(seriesId, after, 1).first().firstOrNull() ?: return null
        // ponytail: relit la fusion depuis le début de la série, une fois, au toucher. Borner la
        // fenêtre à une période si le délai devient visible sur une longue histoire.
        val past = observeTransactions(series.startDate, after).first()
            .lastOrNull { it.transaction.seriesId == seriesId }
        return CalendarStart(
            occurrenceId = (past ?: next).transaction.id,
            openingDay = Instant.ofEpochMilli(next.transaction.date).atZone(zone).toLocalDate(),
        )
    }
}

/** Calendrier d'une charge : occurrence de départ (P-7) et jour d'ouverture (P-10). */
data class CalendarStart(val occurrenceId: Long, val openingDay: LocalDate)

/** Dernière milliseconde de [day] dans [zone] (même cause que LOP-197). Partagée avec LOP-239. */
internal fun lastMilliOf(day: LocalDate, zone: ZoneId): Long =
    day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1

/**
 * Vocabulaire de LOP-241. Charge : série de dépense, au moins une occurrence après [after], sans
 * objectif (P-2) ; avec ou sans compte, dette comprise (CA-02). Revenu récurrent : même règle pour
 * une série de revenu (P-5). Le montant est celui de la série, jamais celui d'une occurrence (CA-01).
 */
internal fun recurringCharges(
    series: List<RecurringSeriesEntity>,
    categories: Map<Long, CategoryEntity>,
    after: Long,
): RecurringCharges {
    val recurring = series.filter {
        it.linkedGoalId == null && occurrencesPerYear(it) != null &&
            RecurrenceEngine.nextOccurrences(it, after, 1).isNotEmpty()
    }
    val collator = Collator.getInstance(Locale.FRANCE)
    val charges = recurring.filter { it.type == TransactionType.EXPENSE }
        .map { s ->
            val category = categories[s.categoryId]
            RecurringCharge(
                seriesId = s.id,
                title = s.title,
                icon = category?.icon,
                colorArgb = category?.colorArgb ?: BreakdownEngine.NO_CATEGORY_COLOR,
                monthly = monthlyCost(s),
                annual = annualCost(s),
            )
        }
        .sortedWith(compareByDescending<RecurringCharge> { it.annual }.thenBy(collator) { it.title })
    return RecurringCharges(
        charges = charges,
        monthlyTotal = charges.sumOf { it.monthly },
        monthlyIncome = recurring.filter { it.type == TransactionType.INCOME }.sumOf(::monthlyCost),
    )
}

/**
 * Nombre d'occurrences par an, en fraction `numérateur / dénominateur`, sans tenir compte de la date
 * de fin : 365 / n, 52 × k / n (k jours cochés, un seul si aucun), 12 / n, 1 / n. `null` pour une
 * fréquence `NONE` ou un intervalle invalide : pas de coût annuel défini, pas une charge.
 */
private fun occurrencesPerYear(series: RecurringSeriesEntity): Pair<Long, Long>? {
    val n = series.interval.toLong().takeIf { it > 0 } ?: return null
    return when (series.frequency) {
        RecurrenceFrequency.DAILY -> 365L to n
        RecurrenceFrequency.WEEKLY -> 52L * RecurrenceEngine.parseDaysOfWeek(series.daysOfWeek).size.coerceAtLeast(1) to n
        RecurrenceFrequency.MONTHLY -> 12L to n
        RecurrenceFrequency.YEARLY -> 1L to n
        RecurrenceFrequency.NONE -> null
    }
}

/** Coût annuel équivalent, arrondi au centime le plus proche (P-6). */
internal fun annualCost(series: RecurringSeriesEntity): Long {
    val (perYear, n) = checkNotNull(occurrencesPerYear(series))
    return roundHalfUp(series.amount * perYear, n)
}

/** Coût mensuel équivalent : l'annuel **exact** divisé par 12, arrondi au centime le plus proche (P-6). */
internal fun monthlyCost(series: RecurringSeriesEntity): Long {
    val (perYear, n) = checkNotNull(occurrencesPerYear(series))
    return roundHalfUp(series.amount * perYear, n * 12)
}

/** `a / b` arrondi au plus proche, demi vers le haut, comme `Format.centsOrNull` ; `a ≥ 0`, `b > 0`. */
private fun roundHalfUp(a: Long, b: Long): Long = Math.floorDiv(2 * a + b, 2 * b)

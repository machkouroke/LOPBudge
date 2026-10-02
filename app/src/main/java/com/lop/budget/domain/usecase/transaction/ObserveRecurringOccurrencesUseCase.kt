package com.lop.budget.domain.usecase.transaction

import com.lop.budget.data.local.entity.TransactionWithRelations
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.scan
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Identité d'une occurrence de série : son slot `seriesId + seriesDate`, jamais sa date affichée
 * (I-3 de LOP-7). [id] est l'identifiant sous lequel elle a été affichée, virtuel ou persisté.
 */
data class OccurrenceRef(val id: Long, val seriesId: Long, val seriesDate: Long) {
    fun sameSlot(other: OccurrenceRef) = seriesId == other.seriesId && seriesDate == other.seriesDate

    companion object {
        /** `null` pour une transaction ponctuelle, qui n'appartient à aucune série. */
        fun of(occurrence: TransactionWithRelations): OccurrenceRef? {
            val tx = occurrence.transaction
            val seriesId = tx.seriesId ?: return null
            return OccurrenceRef(tx.id, seriesId, tx.seriesDate ?: tx.date)
        }
    }
}

/** Contexte du calendrier : la série explorée, son occurrence de départ et le jour d'ouverture. */
data class SeriesContext(
    val start: OccurrenceRef,
    /** Première échéance strictement postérieure à l'occurrence de départ, si la série en a encore. */
    val nextDue: TransactionWithRelations?,
    val nextDueDay: LocalDate?,
    /** Jour de [nextDue], à défaut celui de l'occurrence de départ. */
    val openingDay: LocalDate,
) {
    val seriesId: Long get() = start.seriesId
}

/** Occurrences visibles d'une série sur un mois, regroupées par jour de leur date affichée. */
data class MonthOccurrences(
    val month: YearMonth,
    val byDay: Map<LocalDate, List<TransactionWithRelations>>,
)

sealed interface DaySelection {
    data object Empty : DaySelection
    data class Single(val occurrence: TransactionWithRelations) : DaySelection

    /** Chaque ligne garde son identité propre : deux slots le même jour restent deux choix. */
    data class Multiple(val occurrences: List<TransactionWithRelations>) : DaySelection
}

sealed interface TargetResolution {
    /** [id] désigne la version visible courante du slot choisi, matérialisée le cas échéant. */
    data class Available(val id: Long) : TargetResolution

    /** La cible est l'occurrence de départ : son détail existe déjà, ne pas en ouvrir un second. */
    data object Start : TargetResolution
    data object Unavailable : TargetResolution
}

/**
 * Consultation des occurrences d'une série : aperçu du détail et calendrier (LOP-7).
 *
 * Point d'entrée unique du parcours. Il orchestre la résolution du détail
 * ([ObserveTransactionDetailUseCase]) et la liste fusionnée ([ObserveTransactionsUseCase]) sans
 * réimplémenter ni le moteur de récurrence ni la fusion. Il ne fait que lire (I-1) : aucune de ses
 * opérations ne matérialise ni ne modifie une occurrence.
 */
@Singleton
class ObserveRecurringOccurrencesUseCase @Inject constructor(
    private val observeTransactions: ObserveTransactionsUseCase,
    private val observeTransactionDetail: ObserveTransactionDetailUseCase,
) {
    /**
     * Prochaines échéances de la série de [start], strictement après sa date affichée (CA-01).
     *
     * `null` pour une transaction ponctuelle : ni aperçu ni calendrier. Une liste vide signifie
     * « série sans échéance restante », qui garde l'accès au calendrier.
     */
    fun observeUpcoming(
        start: TransactionWithRelations,
        count: Int = PREVIEW_COUNT,
    ): Flow<List<TransactionWithRelations>?> {
        val seriesId = start.transaction.seriesId ?: return flowOf(null)
        return observeTransactions.observeUpcoming(seriesId, start.transaction.date, count)
    }

    /**
     * Contexte du calendrier ouvert depuis [startId], `null` si celle-ci n'est pas une occurrence
     * de série visible.
     *
     * Si l'occurrence de départ disparaît ensuite, sa dernière version connue reste l'ancre : la
     * série explorée ne change pas et le calendrier reste consultable (CA-06).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeContext(startId: Long, zone: ZoneId): Flow<SeriesContext?> =
        observeTransactionDetail(startId)
            .map { current -> current?.let { OccurrenceRef.of(it)?.let { ref -> ref to it.transaction.date } } }
            .scan<Pair<OccurrenceRef, Long>?, Pair<OccurrenceRef, Long>?>(null) { last, current -> current ?: last }
            .drop(1)
            .distinctUntilChanged()
            .flatMapLatest { anchor ->
                val (start, startDate) = anchor ?: return@flatMapLatest flowOf(null)
                observeTransactions.observeUpcoming(start.seriesId, startDate, 1).map { upcoming ->
                    val nextDue = upcoming.firstOrNull()
                    val nextDueDay = nextDue?.transaction?.date?.let { dayOf(it, zone) }
                    SeriesContext(
                        start = start,
                        nextDue = nextDue,
                        nextDueDay = nextDueDay,
                        openingDay = nextDueDay ?: dayOf(startDate, zone),
                    )
                }
            }

    /**
     * Occurrences visibles de la série sur [month], du premier au dernier jour inclus dans [zone],
     * chacune à sa date affichée (CA-02). La même lecture alimente les marqueurs et la liste.
     */
    fun observeMonth(seriesId: Long, month: YearMonth, zone: ZoneId): Flow<MonthOccurrences> {
        val start = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        return observeTransactions(start, end).map { rows ->
            MonthOccurrences(
                month = month,
                byDay = rows.filter { it.transaction.seriesId == seriesId }
                    .groupBy { dayOf(it.transaction.date, zone) },
            )
        }
    }

    /** CA-04 : jour vide, occurrence unique à ouvrir directement, ou choix explicite. */
    fun selectDay(month: MonthOccurrences, day: LocalDate): DaySelection {
        val occurrences = month.byDay[day].orEmpty()
        return when (occurrences.size) {
            0 -> DaySelection.Empty
            1 -> DaySelection.Single(occurrences.single())
            else -> DaySelection.Multiple(occurrences)
        }
    }

    /**
     * Revalide la cible juste avant son ouverture (CA-05).
     *
     * La résolution du détail rend la version visible courante du slot : une exception matérialisée
     * entre l'affichage et le clic prévaut donc sur le virtuel. Si elle rend un autre slot, ou rien,
     * la cible n'est plus disponible : on ne lui substitue jamais une autre occurrence (I-3).
     */
    suspend fun resolveTarget(
        target: TransactionWithRelations,
        start: OccurrenceRef? = null,
    ): TargetResolution {
        val wanted = OccurrenceRef.of(target) ?: return TargetResolution.Unavailable
        val current = observeTransactionDetail.getById(wanted.id)?.let(OccurrenceRef::of)
        return when {
            current == null || !current.sameSlot(wanted) -> TargetResolution.Unavailable
            start != null && current.sameSlot(start) -> TargetResolution.Start
            else -> TargetResolution.Available(current.id)
        }
    }

    private fun dayOf(millis: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    companion object {
        /** Nombre d'échéances de l'aperçu du détail (CA-01). */
        const val PREVIEW_COUNT = 3
    }
}

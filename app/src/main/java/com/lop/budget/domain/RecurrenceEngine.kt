package com.lop.budget.domain

import com.lop.budget.data.local.entity.RecurringSeriesEntity
import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.domain.model.RecurrenceFrequency
import com.lop.budget.domain.model.TransactionKind
import com.lop.budget.domain.model.TransactionStatus
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Moteur de récurrence centralisé.
 * Gère la génération d'occurrences virtuelles à partir d'une série.
 */
object RecurrenceEngine {

    /**
     * Génère les occurrences virtuelles d'une série sur une période donnée.
     */
    fun generateOccurrences(
        series: RecurringSeriesEntity,
        startRange: Long,
        endRange: Long
    ): List<TransactionEntity> {
        if (series.isCancelled) return emptyList()

        val occurrences = mutableListOf<TransactionEntity>()
        var count = 0

        // Le compteur avance sur TOUS les slots de la série depuis startDate, y compris ceux
        // situés avant la fenêtre : endDate et maxOccurrences sont des limites de série, jamais
        // de fenêtre. La fenêtre ne fait que filtrer ce qui est retourné.
        for (slot in slots(series)) {
            if (slot > endRange) break
            if (series.endDate != null && slot > series.endDate) break
            if (series.maxOccurrences != null && count >= series.maxOccurrences) break

            count++
            if (slot >= startRange) occurrences.add(createVirtualTransaction(series, slot))
        }

        return occurrences
    }

    /**
     * Suite paresseuse des instants de slot de la série, dans l'ordre chronologique croissant.
     *
     * Chaque slot est calculé en décalant la date de début d'origine, jamais le slot précédent.
     * C'est ce qui conserve l'ancrage calendaire quand un mois court impose un rabattement :
     * 31 janvier puis 28 février puis 31 mars, et 29 février retrouvé en année bissextile.
     */
    private fun slots(series: RecurringSeriesEntity): Sequence<Long> = sequence {
        val start = Instant.ofEpochMilli(series.startDate).atZone(ZoneId.systemDefault())
        val selectedDays = parseDaysOfWeek(series.daysOfWeek)
        val byWeekday = series.frequency == RecurrenceFrequency.WEEKLY && selectedDays.isNotEmpty()
        val firstMonday = start.minusDays((start.dayOfWeek.value - 1).toLong())

        var step = 0L
        while (true) {
            if (byWeekday) {
                val monday = firstMonday.plusWeeks(step * series.interval)
                for (isoDay in selectedDays) {
                    val slot = monday.plusDays((isoDay - 1).toLong())
                    if (!slot.isBefore(start)) yield(slot.toInstant().toEpochMilli())
                }
            } else {
                yield(shift(start, series.frequency, step * series.interval).toInstant().toEpochMilli())
            }

            // Sécurité : une fréquence NONE ou un intervalle invalide ne produit pas de suite.
            if (series.frequency == RecurrenceFrequency.NONE || series.interval <= 0) return@sequence
            step++
        }
    }

    private fun shift(
        start: ZonedDateTime,
        frequency: RecurrenceFrequency,
        offset: Long
    ): ZonedDateTime = when (frequency) {
        RecurrenceFrequency.DAILY -> start.plusDays(offset)
        RecurrenceFrequency.WEEKLY -> start.plusWeeks(offset)
        RecurrenceFrequency.MONTHLY -> start.plusMonths(offset)
        RecurrenceFrequency.YEARLY -> start.plusYears(offset)
        RecurrenceFrequency.NONE -> start
    }

    private fun parseDaysOfWeek(raw: String?): List<Int> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(",")
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it in 1..7 }
            .sorted()
    }

    /**
     * Crée une transaction virtuelle (non persistée) pour une occurrence.
     */
    private fun createVirtualTransaction(series: RecurringSeriesEntity, date: Long): TransactionEntity {
        return TransactionEntity(
            id = calculateVirtualId(series.id, date),
            title = series.title,
            amount = series.amount,
            type = series.type,
            status = TransactionStatus.PLANNED, // Une occurrence virtuelle est par défaut planifiée
            kind = TransactionKind.STANDARD,
            date = date,
            accountId = series.accountId,
            categoryId = series.categoryId,
            seriesId = series.id,
            seriesDate = date,
            isException = false,
            note = series.note,
            linkedGoalId = series.linkedGoalId,
            linkedDebtId = series.linkedDebtId
        )
    }

    /**
     * Calcule un ID négatif stable et déterministe pour une occurrence virtuelle.
     * Utilise le seriesId et la date pour éviter les collisions.
     */
    fun calculateVirtualId(seriesId: Long, date: Long): Long {
        // Combinaison simple pour générer un hash stable négatif
        val hash = 31 * seriesId + date
        val virtualId = -(hash.coerceAtLeast(1)) // Toujours < 0
        return if (virtualId >= 0) -1 else virtualId
    }

    /**
     * Inverse algébrique de [calculateVirtualId] : la date de slot encodée dans [virtualId] **si**
     * celui-ci appartient à la série [seriesId].
     *
     * L'inversion seule ne prouve rien : elle rend une date pour n'importe quelle série, puisque
     * `date` est justement calculée pour satisfaire l'équation. C'est à l'appelant de départager les
     * candidates, en vérifiant qu'un slot persisté ou une occurrence générée existe réellement à
     * cette date pour cette série.
     *
     * Rend `null` lorsque l'aller-retour ne se referme pas — cas du clamp de [calculateVirtualId]
     * sur les dates antérieures à 1970, où l'ID est écrasé à -1 et n'encode plus rien.
     */
    fun slotDateOf(seriesId: Long, virtualId: Long): Long? {
        val date = -virtualId - 31 * seriesId
        return date.takeIf { calculateVirtualId(seriesId, it) == virtualId }
    }
}

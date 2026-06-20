package com.lop.budget.domain.recurrence

import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.domain.model.RecurrenceFrequency
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Calcule les prochaines dates d'échéance d'une transaction récurrente.
 * Utilisé pour la section "Occurrences à venir" de l'écran de détail et pour
 * matérialiser les transactions planifiées dans le calendrier.
 */
object RecurrenceEngine {

    /**
     * Renvoie jusqu'à [limit] dates (epoch millis) postérieures à [fromMillis]
     * pour la transaction [tx], en respectant l'intervalle, les jours de semaine
     * et les conditions de fin (date / nombre d'occurrences).
     */
    fun upcomingDates(
        tx: TransactionEntity,
        fromMillis: Long = System.currentTimeMillis(),
        limit: Int = 6,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<Long> {
        if (tx.recurrenceFrequency == RecurrenceFrequency.NONE) return emptyList()

        val result = mutableListOf<Long>()
        var current = Instant.ofEpochMilli(tx.date).atZone(zone).toLocalDate()
        val from = Instant.ofEpochMilli(fromMillis).atZone(zone).toLocalDate()
        val end = tx.recurrenceEndDate?.let {
            Instant.ofEpochMilli(it).atZone(zone).toLocalDate()
        }
        val interval = tx.recurrenceInterval.coerceAtLeast(1)
        val targetDows = tx.recurrenceDaysOfWeek
            ?.split(",")
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?.mapNotNull { runCatching { DayOfWeek.of(it) }.getOrNull() }
            ?.toSet()
            .orEmpty()

        var occurrences = 0
        var guard = 0
        val maxGuard = 5000

        while (result.size < limit && guard < maxGuard) {
            guard++
            val next = when (tx.recurrenceFrequency) {
                RecurrenceFrequency.DAILY -> current.plusDays(interval.toLong())
                RecurrenceFrequency.WEEKLY -> current.plusDays(1)
                RecurrenceFrequency.MONTHLY -> current.plusMonths(interval.toLong())
                RecurrenceFrequency.YEARLY -> current.plusYears(interval.toLong())
                RecurrenceFrequency.NONE -> return result
            }
            current = next
            occurrences++

            if (end != null && current.isAfter(end)) break
            if (tx.recurrenceMaxOccurrences != null && occurrences > tx.recurrenceMaxOccurrences) break

            val matchesWeekly = tx.recurrenceFrequency != RecurrenceFrequency.WEEKLY ||
                targetDows.isEmpty() || targetDows.contains(current.dayOfWeek)
            // Pour WEEKLY avec intervalle, on filtre par semaine modulo interval.
            val weeklyIntervalOk = tx.recurrenceFrequency != RecurrenceFrequency.WEEKLY ||
                interval == 1 ||
                (ChronoUnit.WEEKS.between(
                    Instant.ofEpochMilli(tx.date).atZone(zone).toLocalDate(), current
                ) % interval == 0L)

            if (matchesWeekly && weeklyIntervalOk && current.isAfter(from)) {
                result.add(current.atStartOfDay(zone).toInstant().toEpochMilli())
            }
        }
        return result
    }
}

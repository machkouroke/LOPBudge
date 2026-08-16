package com.lop.budget.domain

import com.lop.budget.data.local.entity.RecurringSeriesEntity
import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.domain.model.RecurrenceFrequency
import com.lop.budget.domain.model.TransactionKind
import com.lop.budget.domain.model.TransactionStatus
import java.util.Calendar

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
        val calendar = Calendar.getInstance().apply { timeInMillis = series.startDate }
        
        var count = 0
        
        // On boucle tant qu'on n'a pas dépassé la fin de la période demandée 
        // et qu'on respecte les limites de la série (endDate, maxOccurrences)
        while (calendar.timeInMillis <= endRange) {
            val currentDate = calendar.timeInMillis
            
            // Vérifier si la date est dans la plage demandée
            if (currentDate >= startRange) {
                // Vérifier les limites de la série
                if (series.endDate != null && currentDate > series.endDate) break
                if (series.maxOccurrences != null && count >= series.maxOccurrences) break
                
                occurrences.add(createVirtualTransaction(series, currentDate))
            }
            
            // Incrémenter selon la fréquence
            moveCalendar(calendar, series.frequency, series.interval)
            count++
            
            // Sécurité pour éviter les boucles infinies si NONE ou intervalle invalide
            if (series.frequency == RecurrenceFrequency.NONE || series.interval <= 0) break
        }
        
        return occurrences
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
            seriesId = series.id.toString(),
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

    private fun moveCalendar(calendar: Calendar, frequency: RecurrenceFrequency, interval: Int) {
        when (frequency) {
            RecurrenceFrequency.DAILY -> calendar.add(Calendar.DAY_OF_YEAR, interval)
            RecurrenceFrequency.WEEKLY -> calendar.add(Calendar.WEEK_OF_YEAR, interval)
            RecurrenceFrequency.MONTHLY -> calendar.add(Calendar.MONTH, interval)
            RecurrenceFrequency.YEARLY -> calendar.add(Calendar.YEAR, interval)
            RecurrenceFrequency.NONE -> { /* No-op */ }
        }
    }
}

package com.lop.budget.domain

import com.lop.budget.data.local.entity.RecurringSeriesEntity
import com.lop.budget.domain.model.RecurrenceFrequency
import com.lop.budget.domain.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * Suite de tests unitaires pour le moteur de récurrence.
 * Valide la génération algorithmique des occurrences virtuelles.
 */
class RecurrenceEngineTest {

    /**
     * TC-24 - JUnit — Stockage série et génération virtuelle.
     * Objectif : Vérifier que le socle Série + Exceptions stocke une récurrence comme une série unique 
     * et génère des occurrences virtuelles conformes, sans pré-création en base.
     * Référence Notion : https://app.notion.com/p/89d083d6f5fb491795dbc96523adc69e
     */
    @Test
    fun `TC-24 - generateOccurrences should generate correct number of virtual transactions`() {
        // Étape 1 : Créer une série récurrente active mensuelle via RecurringSeriesEntity
        val startCalendar = Calendar.getInstance().apply {
            set(2024, Calendar.JANUARY, 1, 10, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val series = RecurringSeriesEntity(
            id = 100L,
            title = "Loyer",
            amount = 800.0,
            type = TransactionType.EXPENSE,
            categoryId = 1L,
            accountId = 1L,
            frequency = RecurrenceFrequency.MONTHLY,
            interval = 1,
            startDate = startCalendar.timeInMillis
        )

        // Étape 2 : Définir une période d'observation couvrant Janvier et Février (2 occurrences attendues)
        val endCalendar = Calendar.getInstance().apply {
            set(2024, Calendar.FEBRUARY, 28, 23, 59, 59)
        }

        // Étape 3 : Demander les transactions sur la période via generateOccurrences
        val occurrences = RecurrenceEngine.generateOccurrences(
            series,
            startCalendar.timeInMillis,
            endCalendar.timeInMillis
        )

        // Étape 4 : Vérifier que 2 occurrences virtuelles attendues sont générées
        assertEquals(2, occurrences.size)

        // Étape 5 : Inspecter que chaque occurrence possède seriesId et seriesDate
        occurrences.forEach { occ ->
            assertEquals("100", occ.seriesId)
            assertTrue(occ.seriesDate != null)
        }

        // Étape 6 : Inspecter que chaque occurrence virtuelle possède un id < 0
        val firstOcc = occurrences[0]
        assertTrue("L'ID virtuel doit être < 0", firstOcc.id < 0)

        // Étape 7 : Regénérer les occurrences sur la même période et vérifier que les IDs restent stables
        val regenerated = RecurrenceEngine.generateOccurrences(
            series,
            startCalendar.timeInMillis,
            endCalendar.timeInMillis
        )
        assertEquals(firstOcc.id, regenerated[0].id)
    }

    /**
     * TC-28 - JUnit — Règles de récurrence.
     * Objectif : Vérifier que les règles de récurrence sont correctement appliquées : 
     * fréquence, intervalle, date de fin et série annulée.
     * Référence Notion : https://app.notion.com/p/54d5501d606a47a786b474c8dc432b61
     */
    @Test
    fun `TC-28 - Recurrence rules should respect frequency and intervals`() {
        // --- TEST QUOTIDIEN ---
        // Étape 1 : Créer une série quotidienne active
        val dailyStart =
            Calendar.getInstance().apply { set(2024, Calendar.JANUARY, 1, 10, 0, 0) }.timeInMillis
        val dailyEndRange =
            Calendar.getInstance().apply { set(2024, Calendar.JANUARY, 5, 23, 59, 59) }.timeInMillis
        val dailySeries =
            createTestSeries(id = 1, freq = RecurrenceFrequency.DAILY, start = dailyStart)

        // Étape 2 : Générer les occurrences de la série quotidienne sur 5 jours
        val dailyOccs = RecurrenceEngine.generateOccurrences(dailySeries, dailyStart, dailyEndRange)

        // Résultat attendu : Une occurrence est générée pour chaque jour attendu (5)
        assertEquals(5, dailyOccs.size)

        // --- TEST HEBDOMADAIRE AVEC INTERVALLE 2 ---
        // Étape 3 : Créer une série hebdomadaire active avec intervalle 2
        val weeklyStart =
            Calendar.getInstance().apply { set(2024, Calendar.JANUARY, 1, 10, 0, 0) }.timeInMillis
        val weeklyEndRange = Calendar.getInstance()
            .apply { set(2024, Calendar.JANUARY, 31, 23, 59, 59) }.timeInMillis
        val weeklySeries = createTestSeries(
            id = 2,
            freq = RecurrenceFrequency.WEEKLY,
            start = weeklyStart,
            interval = 2
        )

        // Étape 4 : Générer les occurrences (toutes les 2 semaines)
        val weeklyOccs =
            RecurrenceEngine.generateOccurrences(weeklySeries, weeklyStart, weeklyEndRange)

        // Résultat attendu : Une occurrence toutes les 2 semaines (1er janv, 15 janv, 29 janv -> 3 occurrences)
        assertEquals(3, weeklyOccs.size)

        // --- TEST MENSUEL AVEC INTERVALLE 3 ---
        // Étape 5 : Créer une série mensuelle active avec intervalle 3
        val monthlyStart =
            Calendar.getInstance().apply { set(2024, Calendar.JANUARY, 1, 10, 0, 0) }.timeInMillis
        val monthlyEndRange = Calendar.getInstance()
            .apply { set(2024, Calendar.DECEMBER, 31, 23, 59, 59) }.timeInMillis
        val monthlySeries = createTestSeries(
            id = 3,
            freq = RecurrenceFrequency.MONTHLY,
            start = monthlyStart,
            interval = 3
        )

        // Étape 6 : Générer les occurrences (tous les 3 mois)
        val monthlyOccs =
            RecurrenceEngine.generateOccurrences(monthlySeries, monthlyStart, monthlyEndRange)

        // Résultat attendu : Jan, Avr, Juil, Oct -> 4 occurrences
        assertEquals(4, monthlyOccs.size)
    }

    /**
     * TC-28 - JUnit — Règles de récurrence (Limites et Annulation).
     * Objectif : Vérifier le respect strict de la date de fin et du statut annulé.
     */
    @Test
    fun `TC-28 - Recurrence rules should respect endDate and isCancelled`() {
        // --- TEST DATE DE FIN ---
        // Étape 1 : Créer une série mensuelle avec une date de fin au 10 Mars
        val start =
            Calendar.getInstance().apply { set(2024, Calendar.JANUARY,
                1, 10, 0, 0) }.timeInMillis
        val endLimit =
            Calendar.getInstance().apply { set(2024, Calendar.MARCH,
                10, 10, 0, 0) }.timeInMillis
        val endRange = Calendar.getInstance()
            .apply { set(2024, Calendar.DECEMBER, 31, 23, 59, 59) }.timeInMillis
        val seriesWithEnd = createTestSeries(
            id = 4,
            freq = RecurrenceFrequency.MONTHLY,
            start = start
        ).copy(endDate = endLimit)

        // Étape 2 : Générer les occurrences
        val occsWithEnd = RecurrenceEngine.generateOccurrences(seriesWithEnd, start, endRange)

        // Résultat attendu : Jan, Fev, Mar -> 3 occurrences (aucune après le 10 Mars)
        assertEquals(3, occsWithEnd.size)

        // --- TEST SÉRIE ANNULÉE ---
        // Étape 3 : Créer une série annulée
        val cancelledSeries =
            createTestSeries(id = 5, freq = RecurrenceFrequency.DAILY, start = start).copy(
                isCancelled = true
            )

        // Étape 4 : Générer les occurrences
        val cancelledOccs = RecurrenceEngine.generateOccurrences(cancelledSeries, start, endRange)

        // Résultat attendu : Aucune occurrence future n’est générée
        assertTrue("Une série annulée ne doit générer aucune occurrence", cancelledOccs.isEmpty())
    }

    /**
     * Helper pour créer une série de test rapidement.
     */
    private fun createTestSeries(
        id: Long,
        freq: RecurrenceFrequency,
        start: Long,
        interval: Int = 1
    ) = RecurringSeriesEntity(
        id = id,
        title = "Test Series",
        amount = 100.0,
        type = TransactionType.EXPENSE,
        categoryId = 1L,
        accountId = 1L,
        frequency = freq,
        interval = interval,
        startDate = start
    )
}

package com.lop.budget.data.repository

import com.lop.budget.data.local.dao.AccountDao
import com.lop.budget.data.local.dao.CategoryDao
import com.lop.budget.data.local.dao.DebtDao
import com.lop.budget.data.local.dao.GoalDao
import com.lop.budget.data.local.dao.RecurringSeriesDao
import com.lop.budget.data.local.dao.TagDao
import com.lop.budget.data.local.dao.TransactionDao
import com.lop.budget.data.local.entity.RecurringSeriesEntity
import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.domain.model.RecurrenceFrequency
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar

/**
 * Suite de tests d'intégration technique pour le repository BudgetRepository.
 * Focalisée sur la fusion des données (Réelles + Virtuelles + Exceptions) et la centralisation.
 */
class BudgetRepositoryRecurrenceTest {

    private val transactionDao = mockk<TransactionDao>()
    private val recurringSeriesDao = mockk<RecurringSeriesDao>()
    private val accountDao = mockk<AccountDao>()
    private val categoryDao = mockk<CategoryDao>()
    private val tagDao = mockk<TagDao>()
    private val goalDao = mockk<GoalDao>()
    private val debtDao = mockk<DebtDao>()

    private lateinit var repository: BudgetRepository

    @Before
    fun setup() {
        // Initialisation du repository avec les mocks des DAOs
        repository = BudgetRepository(
            transactionDao, recurringSeriesDao, accountDao, categoryDao, tagDao, goalDao, debtDao
        )
    }

    /**
     * TC-26 - JUnit — Transactions ponctuelles et centralisation.
     * Objectif : Vérifier que les transactions ponctuelles restent indépendantes des séries 
     * et que la génération/fusion passe par une logique centrale (observeTransactionsBetween).
     * Référence Notion : https://app.notion.com/p/d8f107bdc198499e818bc5cac030c2a8
     */
    @Test
    fun `TC-26 - JUnit Transactions ponctuelles et centralisation`() = runBlocking {
        // Étape 1 : Créer une transaction ponctuelle (seriesId == null) dans la période testée (Janvier 2024)
        val start =
            Calendar.getInstance().apply { set(2024, Calendar.JANUARY, 1, 0, 0, 0) }.timeInMillis
        val end = Calendar.getInstance()
            .apply { set(2024, Calendar.JANUARY, 31, 23, 59, 59) }.timeInMillis

        val ponctuelle = TransactionEntity(
            id = 1L,
            title = "Achat ponctuel",
            amount = 50.0,
            type = TransactionType.EXPENSE,
            status = TransactionStatus.PAID,
            date = start,
            accountId = 1L,
            categoryId = 1L,
            seriesId = null // CA-09
        )

        // Étape 2 : Simuler le retour des DAOs via MockK
        every { transactionDao.observeBetween(start, end) } returns flowOf(
            listOf(
                TransactionWithRelations(ponctuelle, null, null, emptyList())
            )
        )
        every { recurringSeriesDao.observeActiveSeries() } returns flowOf(emptyList())
        every { accountDao.observeAll() } returns flowOf(emptyList())
        every { categoryDao.observeAll() } returns flowOf(emptyList())

        // Étape 3 : Appeler la méthode centrale observeTransactionsBetween sur la période (CA-11)
        val result = repository.observeTransactionsBetween(start, end).first()

        // Étape 4 : Vérifier que la transaction ponctuelle est présente dans la liste finale
        assertTrue(
            "La transaction ponctuelle doit être présente",
            result.any { it.transaction.id == 1L })

        // Étape 5 : Vérifier que la transaction ponctuelle n'est pas altérée (seriesId reste null)
        val tx = result.first { it.transaction.id == 1L }.transaction
        assertTrue("seriesId doit rester null pour une transaction ponctuelle", tx.seriesId == null)
    }

    /**
     * TC-25 - JUnit — Fusion exceptions et remplacement virtuel.
     * Objectif : Vérifier que les exceptions matérialisées remplacent correctement les occurrences virtuelles.
     * Utilise la fonction réelle materializeOccurrence pour valider la logique de création d'exception.
     * Référence Notion : https://app.notion.com/p/cad301e3ead640f084caf34e3aee6b2e
     */
    @Test
    fun `TC-25 - JUnit Fusion exceptions`() = runBlocking {
        // Étape 1 : Créer une série active avec une occurrence au 1er Janvier
        val start = Calendar.getInstance().apply {
            set(2024, Calendar.JANUARY, 1, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val end = Calendar.getInstance()
            .apply { set(2024, Calendar.JANUARY, 31, 23, 59, 59) }.timeInMillis

        val seriesId = 100L
        val series = RecurringSeriesEntity(
            id = seriesId, title = "Loyer", amount = 800.0,
            type = TransactionType.EXPENSE, categoryId = 1L, accountId = 1L,
            frequency = RecurrenceFrequency.MONTHLY, interval = 1, startDate = start
        )

        // Étape 2 : Matérialiser l'occurrence via la fonction réelle du repository (CA-06)
        // On configure le mock pour simuler l'absence d'exception existante puis la création
        coEvery { transactionDao.getException(seriesId.toString(), start) } returns null
        coEvery { recurringSeriesDao.getSeriesById(seriesId) } returns series

        val capturedTx = slot<TransactionEntity>()
        coEvery { transactionDao.upsert(capture(capturedTx)) } returns 2L // On simule l'ID 2 généré en base

        val materializedId = repository.materializeOccurrence(seriesId, start)

        // Vérifier que la matérialisation a bien créé une exception persistée correcte
        assertEquals("L'ID retourné par la matérialisation doit être 2", 2L, materializedId)
        assertTrue(
            "La transaction créée doit être marquée comme exception",
            capturedTx.captured.isException
        )
        assertEquals(
            "L'exception doit porter le bon seriesId",
            seriesId.toString(),
            capturedTx.captured.seriesId
        )
        assertEquals(
            "L'exception doit porter la bonne date de série",
            start,
            capturedTx.captured.seriesDate
        )

        // Étape 3 : Simuler le retour du DAO avec l'exception physique et la série pour la fusion
        val exception = capturedTx.captured.copy(id = 2L)
        every { transactionDao.observeBetween(start, end) } returns flowOf(
            listOf(
                TransactionWithRelations(exception, null, null, emptyList())
            )
        )
        every { recurringSeriesDao.observeActiveSeries() } returns flowOf(listOf(series))
        every { accountDao.observeAll() } returns flowOf(emptyList())
        every { categoryDao.observeAll() } returns flowOf(emptyList())

        // Étape 4 : Récupérer la liste fusionnée via observeTransactionsBetween
        val result = repository.observeTransactionsBetween(start, end).first()

        // Étape 5 : Vérifier que l'exception remplace l'occurrence virtuelle sans doublon (CA-07)
        val occurrences =
            result.filter { it.transaction.seriesId == seriesId.toString() && it.transaction.seriesDate == start }
        assertEquals(
            "Il ne doit y avoir qu'une seule occurrence pour cette date (fusion)",
            1,
            occurrences.size
        )

        // Étape 6 : Vérifier que c'est bien l'ID physique (2) qui est présent et non un ID virtuel négatif
        assertEquals(
            "L'exception doit avoir priorité sur le virtuel",
            2L,
            occurrences[0].transaction.id
        )
    }

    /**
     * TC-27 - JUnit — Exclusion des transactions supprimées.
     * Objectif : Vérifier qu'une occurrence supprimée est exclue de l'affichage sans masquer toute la série.
     * Référence Notion (Page dédiée créée) : https://app.notion.com/p/3b450f34a8c581c5a615e5d63a29ba3c
     */
    @Test
    fun `TC-27 - JUnit Exclusion des transactions supprimees`() = runBlocking {
        // Étape 1 : Créer une série active avec plusieurs occurrences dans la période testée (Janvier 2024)
        val start =
            Calendar.getInstance().apply { set(2024, Calendar.JANUARY, 1, 0, 0, 0) }.timeInMillis
        val end = Calendar.getInstance()
            .apply { set(2024, Calendar.JANUARY, 31, 23, 59, 59) }.timeInMillis
        val dateFeb1 = Calendar.getInstance().apply {
            set(2024, Calendar.FEBRUARY, 1, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val seriesId = 100L
        val series = RecurringSeriesEntity(
            id = seriesId, title = "Abonnement", amount = 10.0,
            type = TransactionType.EXPENSE, categoryId = 1L, accountId = 1L,
            frequency = RecurrenceFrequency.MONTHLY, interval = 1, startDate = start
        )

        // Étape 2 : Matérialiser une occurrence ciblée (1er Janvier)
        val exceptionJan = TransactionEntity(
            id = 2L,
            title = "Abonnement",
            amount = 10.0,
            type = TransactionType.EXPENSE,
            status = TransactionStatus.PLANNED,
            date = start,
            accountId = 1L,
            categoryId = 1L,
            seriesId = seriesId.toString(),
            seriesDate = start,
            isException = true
        )

        // Étape 3 : Récupérer la liste fusionnée sur Janvier
        every { transactionDao.observeBetween(start, end) } returns flowOf(
            listOf(
                TransactionWithRelations(exceptionJan, null, null, emptyList())
            )
        )
        every { recurringSeriesDao.observeActiveSeries() } returns flowOf(listOf(series))
        every { accountDao.observeAll() } returns flowOf(emptyList())
        every { categoryDao.observeAll() } returns flowOf(emptyList())

        var result = repository.observeTransactionsBetween(start, end).first()

        // Étape 4 : Vérifier l'absence de doublons (CA-07)
        val occurrencesJan =
            result.filter { it.transaction.seriesId == seriesId.toString() && it.transaction.seriesDate == start }
        assertEquals(
            "L'exception matérialisée doit remplacer le virtuel sans doublon",
            1,
            occurrencesJan.size
        )

        // Étape 5 : Supprimer une autre occurrence individuelle (simulation d'une exception soft-deleted)
        val deletedExceptionFeb = TransactionEntity(
            id = 3L,
            title = "Abonnement",
            amount = 10.0,
            type = TransactionType.EXPENSE,
            status = TransactionStatus.PLANNED,
            date = dateFeb1,
            accountId = 1L,
            categoryId = 1L,
            seriesId = seriesId.toString(),
            seriesDate = dateFeb1,
            isException = true,
            deleted = true
        )

        // Étape 6 : Récupérer à nouveau la liste fusionnée (sur une période incluant Février)
        val endFeb =
            Calendar.getInstance()
                .apply { set(2024, Calendar.FEBRUARY, 29, 23, 59, 59) }.timeInMillis
        every { transactionDao.observeBetween(start, endFeb) } returns flowOf(
            listOf(
                TransactionWithRelations(exceptionJan, null, null, emptyList()),
                TransactionWithRelations(deletedExceptionFeb, null, null, emptyList())
            )
        )

        result = repository.observeTransactionsBetween(start, endFeb).first()

        // L'occurrence supprimée doit être absente (CA-08 / CA-10)
        assertFalse(
            "L'occurrence supprimée (Février) doit être absente",
            result.any { it.transaction.seriesDate == dateFeb1 })

        // Étape 7 : Vérifier que les autres occurrences restent visibles (Janvier est toujours là)
        assertTrue(
            "L'occurrence de Janvier doit rester visible",
            result.any { it.transaction.seriesDate == start })

        // Étape 8 : Ajouter une transaction ponctuelle soft-deleted dans la période et vérifier son exclusion
        val deletedPonctuelle = TransactionEntity(
            id = 4L,
            title = "Achat annulé",
            amount = 5.0,
            type = TransactionType.EXPENSE,
            status = TransactionStatus.PAID,
            date = start + 1000,
            accountId = 1L,
            categoryId = 1L,
            deleted = true
        )

        every { transactionDao.observeBetween(start, endFeb) } returns flowOf(
            listOf(
                TransactionWithRelations(exceptionJan, null, null, emptyList()),
                TransactionWithRelations(deletedExceptionFeb, null, null, emptyList()),
                TransactionWithRelations(deletedPonctuelle, null, null, emptyList())
            )
        )

        result = repository.observeTransactionsBetween(start, endFeb).first()
        assertFalse(
            "La transaction ponctuelle supprimée doit être exclue",
            result.any { it.transaction.id == 4L })
    }
}

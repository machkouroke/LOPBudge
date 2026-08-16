package com.lop.budget.ui.common

import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.data.repository.TransactionRepository
import com.lop.budget.domain.model.SeriesCancelMode
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.domain.usecase.CancelRecurringSeriesUseCase
import com.lop.budget.domain.usecase.SaveTransactionUseCase
import com.lop.budget.domain.usecase.SoftDeleteTransactionOccurrenceUseCase
import com.lop.budget.ui.components.RecurringDeleteChoice
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * TC-33 - JUnit — Mapping portées suppression récurrente.
 * Objectif : Vérifier que chaque choix de la BottomSheet (scope) déclenche le bon appel au Repository.
 * Référence Notion : https://app.notion.com/p/343b99165ddb4cf5bb2b944b4187ac37
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContextualDeletionMappingTest {

    // private val repo = mockk<BudgetRepository>(relaxed = true)
    private val softDeleteTransactionOccurrenceUseCase = mockk<SoftDeleteTransactionOccurrenceUseCase>(relaxed = true)
    private val cancelRecurringSeriesUseCase = mockk<CancelRecurringSeriesUseCase>(relaxed = true)
    private val saveTransactionUseCase = mockk<SaveTransactionUseCase>(relaxed = true)
    private val transactionRepo = mockk<TransactionRepository>(relaxed = true)
    private lateinit var viewModel: TransactionActionViewModel
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        viewModel = TransactionActionViewModel(transactionRepo, softDeleteTransactionOccurrenceUseCase, cancelRecurringSeriesUseCase, saveTransactionUseCase)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * Test du mapping pour la portée "Cette occurrence uniquement".
     * Vérifie l'appel à repo.softDeleteTransactionOccurrence.
     */
    @Test
    fun `TC-33 - Mapping THIS_OCCURRENCE should call softDeleteTransactionOccurrence`() {
        // Étape 1 : Créer une transaction récurrente cible
        val tx = createRecurringTransaction(id = 10L, seriesId = 1L, date = 1000L)
        val twr = TransactionWithRelations(tx, null, null, emptyList())

        // Étape 2 : Simuler la requête de suppression et le choix de l'utilisateur
        viewModel.requestConfirmation(twr, RecurringDeleteChoice.THIS_OCCURRENCE)

        // Étape 3 : Confirmer la suppression
        viewModel.confirmDelete()

        // Étape 4 : Vérifier que le mapping appelle bien softDeleteOccurrence
        coVerify { softDeleteTransactionOccurrenceUseCase(twr) }
    }

    /**
     * Test du mapping pour la portée "Cette occurrence et les suivantes".
     * Vérifie l'appel à repo.cancelSeries avec le mode FUTURE et la date pivot.
     */
    @Test
    fun `TC-33 - Mapping FUTURE_ONLY should call cancelSeries with mode FUTURE`() {
        // Étape 1 : Créer une transaction récurrente cible le 15 du mois
        val datePivot = 1500L
        val tx = createRecurringTransaction(id = 20L, seriesId = 1L, date = datePivot)
        val twr = TransactionWithRelations(tx, null, null, emptyList())

        // Étape 2 : Simuler le choix "Les suivantes uniquement"
        viewModel.requestConfirmation(twr, RecurringDeleteChoice.FUTURE_ONLY)

        // Étape 3 : Confirmer
        viewModel.confirmDelete()

        // Étape 4 : Vérifier l'appel à cancelRecurringSeriesUseCase(S1, FUTURE, datePivot)
        coVerify { cancelRecurringSeriesUseCase(any(), SeriesCancelMode.Future(datePivot)) }
    }

    /**
     * Test du mapping pour la portée "Toutes les occurrences".
     * Vérifie l'appel à repo.cancelSeries avec le mode ALL.
     */
    @Test
    fun `TC-33 - Mapping ALL_SERIES should call cancelSeries with mode ALL`() {
        // Étape 1 : Créer une transaction récurrente cible
        val tx = createRecurringTransaction(id = 30L, seriesId = 1L, date = 1000L)
        val twr = TransactionWithRelations(tx, null, null, emptyList())

        // Étape 2 : Simuler le choix "Toute la série"
        viewModel.requestConfirmation(twr, RecurringDeleteChoice.ALL_SERIES)

        // Étape 3 : Confirmer
        viewModel.confirmDelete()

        // Étape 4 : Vérifier l'appel à cancelRecurringSeriesUseCase(S1, ALL)
        coVerify { cancelRecurringSeriesUseCase(any(), SeriesCancelMode.All) }
    }

    private fun createRecurringTransaction(id: Long, seriesId: Long, date: Long) = TransactionEntity(
        id = id,
        title = "Recurring Tx",
        amount = 50.0,
        type = TransactionType.EXPENSE,
        status = TransactionStatus.PLANNED,
        date = date,
        accountId = 1L,
        categoryId = 1L,
        seriesId = seriesId,
        seriesDate = date
    )
}

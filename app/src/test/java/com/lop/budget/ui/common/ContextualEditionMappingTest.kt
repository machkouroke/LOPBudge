package com.lop.budget.ui.common

import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.data.repository.BudgetRepository
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * TC-34 - JUnit — Mapping des portées d'édition récurrente.
 * Objectif : Vérifier que le TransactionActionViewModel traduit correctement les choix UI 
 * en actions techniques (matérialisation ou navigation avec arguments).
 * Référence Notion : https://app.notion.com/p/machkouroke/JUnit-mapping-des-port-es-d-dition-r-currente-b86efca783144c07a46fc9bcadb1277f
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContextualEditionMappingTest {

    private val repository = mockk<BudgetRepository>(relaxed = true)
    private lateinit var viewModel: TransactionActionViewModel
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        viewModel = TransactionActionViewModel(repository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }


    /**
     * Scénario 2 : Portée FUTURE (Cette occurrence et les suivantes).
     * Ce scénario est géré par la navigation directe dans LopNavHost via les arguments.
     * On vérifie ici que le ViewModel expose bien la transaction pour que l'UI puisse naviguer.
     */
    @Test
    fun `TC-34 - Choice FUTURE should maintain transaction context for navigation`() {
        println("\n--- [START] TC-34 - Scénario FUTURE ---")
        
        // --- PRÉPARATION ---
        val tx = TransactionWithRelations(
            TransactionEntity(id = 100, title = "Loyer", amount = 800.0, type = TransactionType.EXPENSE, 
                status = TransactionStatus.PLANNED, date = 1706778000000L, accountId = 1, categoryId = 1), 
            null, null, emptyList()
        )

        // --- ACTION ---
        println("Étape 1 : Action - Demande d'édition (Ouvre la BottomSheet)")
        viewModel.requestEdit(tx)

        // --- VALIDATION ---
        println("Étape 2 : Vérification de l'état du ViewModel")
        assertEquals("La transaction doit être stockée dans editRequest", tx, viewModel.editRequest.value)
        println("Log : Contexte de transaction préservé pour l'UI (FUTURE/ALL)")
        
        println("--- [END] TC-34 - Scénario FUTURE/ALL Context SUCCESS ---")
    }
}

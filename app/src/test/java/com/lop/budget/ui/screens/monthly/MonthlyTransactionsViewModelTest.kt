package com.lop.budget.ui.screens.monthly

import app.cash.turbine.test
import androidx.lifecycle.SavedStateHandle
import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.data.repository.BudgetRepository
import com.lop.budget.data.repository.SettingsRepository
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.reports.MarkdownReporter
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MonthlyTransactionsViewModelTest {

    @get:Rule
    val reporter = MarkdownReporter()

    private val testDispatcher = UnconfinedTestDispatcher()
    private val repo = mockk<BudgetRepository>(relaxed = true)
    private val settings = mockk<SettingsRepository>(relaxed = true)
    private val savedStateHandle = SavedStateHandle()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        every { settings.currency } returns flowOf("EUR")
        every { repo.observeAccounts() } returns flowOf(emptyList())
        every { repo.observeCategories() } returns flowOf(emptyList())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * Vérifie la logique de recherche et de détection multi-mois :
     * 1. S'assure que les transactions sont correctement filtrées par mot-clé dans le mois actuel.
     * 2. Vérifie que le flag 'hasResultsInOtherMonths' s'active si aucun résultat n'est trouvé ce mois-ci
     *    mais que la recherche est active (simule la détection de résultats globaux).
     */
    @Test
    fun `search filters transactions correctly and detects matches in other months`() = runTest {
        MarkdownReporter.log("Test de recherche avancée et détection multi-mois")
        
        val txThisMonth = TransactionWithRelations(
            transaction = TransactionEntity(title = "Amazon This Month", amount = 10.0, type = TransactionType.EXPENSE, status = TransactionStatus.PAID, date = 0L, accountId = 1, categoryId = 1),
            category = null, account = null, tags = emptyList()
        )

        every { repo.observeTransactionsBetween(any(), any()) } returns flowOf(listOf(txThisMonth))
        MarkdownReporter.log("Setup : 1 transaction 'Amazon' simulée pour ce mois")

        val viewModel = MonthlyTransactionsViewModel(savedStateHandle, repo, settings)
        
        viewModel.uiState.test {
            // 1. Initial
            var state = awaitItem()
            MarkdownReporter.log("Init : ${state.transactions.size} tx(s) trouvée(s)")
            assertTrue(state.transactions.contains(txThisMonth))

            // 2. Search positive
            MarkdownReporter.log("Action : Recherche de 'Amazon'")
            viewModel.onQueryChange("Amazon")
            state = awaitItem()
            MarkdownReporter.log("Vérif : ${state.transactions.size} tx(s) filtrée(s). hasOther=${state.hasResultsInOtherMonths}")
            assertEquals(1, state.transactions.size)
            assertFalse(state.hasResultsInOtherMonths)

            // 3. Search negative (global detection)
            MarkdownReporter.log("Action : Recherche de 'Netflix' (absent du mois)")
            viewModel.onQueryChange("Netflix")
            state = awaitItem()
            MarkdownReporter.log("Vérif : hasOther=${state.hasResultsInOtherMonths}")
            assertTrue(state.transactions.isEmpty())
            assertTrue(state.hasResultsInOtherMonths)
        }
    }

    /**
     * Vérifie que le filtre unifié par défaut fonctionne :
     * 1. S'assure que lorsque le type de transaction est 'null' (tous), la liste contient
     *    à la fois des revenus (INCOME) et des dépenses (EXPENSE).
     */
    @Test
    fun `type filter null should include both income and expenses`() = runTest {
        MarkdownReporter.log("Test du filtre unifié (Revenus + Dépenses)")
        
        val income = TransactionWithRelations(
            transaction = TransactionEntity(title = "Salary", amount = 1000.0, type = TransactionType.INCOME, status = TransactionStatus.PAID, date = 0L, accountId = 1, categoryId = 1),
            category = null, account = null, tags = emptyList()
        )
        val expense = TransactionWithRelations(
            transaction = TransactionEntity(title = "Rent", amount = 500.0, type = TransactionType.EXPENSE, status = TransactionStatus.PAID, date = 0L, accountId = 1, categoryId = 1),
            category = null, account = null, tags = emptyList()
        )

        every { repo.observeTransactionsBetween(any(), any()) } returns flowOf(listOf(income, expense))
        MarkdownReporter.log("Setup : 1 Revenu et 1 Dépense simulés")

        val viewModel = MonthlyTransactionsViewModel(savedStateHandle, repo, settings)
        
        viewModel.uiState.test {
            val state = awaitItem() 
            MarkdownReporter.log("Vérif : Mode ALL par défaut. Txs=${state.transactions.size}")
            assertEquals(2, state.transactions.size)
            assertTrue(state.transactions.any { it.transaction.type == TransactionType.INCOME })
            assertTrue(state.transactions.any { it.transaction.type == TransactionType.EXPENSE })
        }
    }

    @Test
    fun z_generateReport() {
        reporter.generateFinalReport(this)
    }
}

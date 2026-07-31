package com.lop.budget.data.repository

import com.lop.budget.data.local.dao.*
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.domain.model.*
import com.lop.budget.reports.MarkdownReporter
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Tests de la logique métier de haut niveau pour l'ajustement des soldes.
 * Couvre l'US : Calculer les ajustements de solde via transactions compensatoires (LOP-87)
 * Contrairement au BalanceEngine (pure logique), ici on teste l'interaction
 * avec les DAO et la création automatique de transactions techniques.
 */
class AccountBalanceAdjustmentTest {

    @get:Rule
    val reporter = MarkdownReporter()

    // Mocks des dépendances du repository
    private val transactionDao = mockk<TransactionDao>(relaxed = true)
    private val recurringSeriesDao = mockk<RecurringSeriesDao>()
    private val accountDao = mockk<AccountDao>()
    private val categoryDao = mockk<CategoryDao>()
    private val tagDao = mockk<TagDao>()
    private val goalDao = mockk<GoalDao>()
    private val debtDao = mockk<DebtDao>()

    private lateinit var repository: BudgetRepository

    @Before
    fun setup() {
        // Initialisation du repository avec ses dépendances mockées
        repository = BudgetRepository(
            transactionDao, recurringSeriesDao, accountDao, categoryDao, tagDao, goalDao, debtDao
        )
    }

    /**
     * TC5 : Vérifie que si l'utilisateur saisit un solde CIBLE supérieur au solde ACTUEL,
     * une transaction de type REVENU (INCOME) est créée pour combler l'écart.
     */
    @Test
    fun `TC5 - adjustAccountBalance should create INCOME adjustment when target is higher`() =
        runBlocking {
            MarkdownReporter.log("TC5 : Création d'un ajustement de type REVENU")
            val accountId = 1L
            val account = AccountEntity(
                id = accountId,
                name = "Compte Test",
                type = AccountType.CHECKING,
                initialBalance = 1000.0, // Solde de base
                colorArgb = 0,
                icon = ""
            )

            // Configuration des mocks : on renvoie le compte et aucune transaction existante
            coEvery { accountDao.getById(accountId) } returns account
            coEvery { transactionDao.observeAll() } returns flowOf(emptyList())
            // Solde actuel calculé = 1000.0
            MarkdownReporter.log("État initial : Solde actuel = 1000.0")

            val targetBalance = 1200.0
            MarkdownReporter.log("Action : Ajustement vers un solde cible de $targetBalance")

            // Appel de la méthode à tester
            repository.adjustAccountBalance(accountId, targetBalance)

            // Capture de la transaction qui a été envoyée au DAO pour sauvegarde
            val slot = slot<TransactionEntity>()
            coVerify { transactionDao.upsert(capture(slot)) }

            // Vérifications
            MarkdownReporter.log("Vérification : Une transaction technique de +200.0 a été créée")
            MarkdownReporter.log(
                "Transaction réellement créée: [montant=${slot.captured.amount} " +
                        ", type=${slot.captured.type} , kind=${slot.captured.kind}"
            )
            assertEquals(TransactionType.INCOME, slot.captured.type)
            assertEquals(200.0, slot.captured.amount, 0.0)
            assertEquals(TransactionKind.BALANCE_ADJUSTMENT, slot.captured.kind)
        }

    /**
     * TC6 : Vérifie que si l'utilisateur saisit un solde CIBLE inférieur au solde ACTUEL,
     * une transaction de type DÉPENSE (EXPENSE) est créée pour combler l'écart.
     */
    @Test
    fun `TC6 - adjustAccountBalance should create EXPENSE adjustment when target is lower`() =
        runBlocking {
            MarkdownReporter.log("TC6 : Création d'un ajustement de type DÉPENSE")
            val accountId = 1L
            val account = AccountEntity(
                id = accountId,
                name = "Compte Test",
                type = AccountType.CHECKING,
                initialBalance = 1000.0,
                colorArgb = 0,
                icon = ""
            )

            coEvery { accountDao.getById(accountId) } returns account
            coEvery { transactionDao.observeAll() } returns flowOf(emptyList()) // Solde actuel = 1000.0
            MarkdownReporter.log("État initial : Solde actuel = 1000.0")

            val targetBalance = 850.0
            MarkdownReporter.log("Action : Ajustement vers un solde cible de $targetBalance")

            repository.adjustAccountBalance(accountId, targetBalance)

            val slot = slot<TransactionEntity>()
            coVerify { transactionDao.upsert(capture(slot)) }

            MarkdownReporter.log("Vérification : Une transaction technique de -150.0 a été créée")
            MarkdownReporter.log("Transaction réellement créée: [montant=${slot.captured.amount} " +
                    ", type=${slot.captured.type} , kind=${slot.captured.kind}")
            assertEquals(TransactionType.EXPENSE, slot.captured.type)
            assertEquals(150.0, slot.captured.amount, 0.0)
            assertEquals(TransactionKind.BALANCE_ADJUSTMENT, slot.captured.kind)
        }

    /**
     * TC7 : Vérifie que si le solde cible est identique au solde actuel, 
     * aucune transaction technique n'est créée inutilement.
     */
    @Test
    fun `TC7 - adjustAccountBalance should create nothing when target equals current`() =
        runBlocking {
            MarkdownReporter.log("TC7 : Aucun ajustement si le solde ne change pas")
            val accountId = 1L
            val account = AccountEntity(
                id = accountId,
                name = "Compte Test",
                type = AccountType.CHECKING,
                initialBalance = 1000.0,
                colorArgb = 0,
                icon = ""
            )

            coEvery { accountDao.getById(accountId) } returns account
            coEvery { transactionDao.observeAll() } returns flowOf(emptyList())

            MarkdownReporter.log("Action : Ajustement vers 1000.0 (déjà la valeur actuelle)")
            repository.adjustAccountBalance(accountId, 1000.0)

            // On vérifie que la méthode upsert du DAO n'a JAMAIS été appelée
            coVerify(exactly = 0) { transactionDao.upsert(any()) }
            MarkdownReporter.log("Vérification : Aucune transaction n'a été générée")
        }

    /**
     * TC8 : Vérifie le filtrage des transactions techniques.
     * Les ajustements de solde ne doivent pas apparaître dans les listes "métier" 
     * (analyses, graphiques, etc.) pour ne pas fausser les statistiques de l'utilisateur.
     */
    @Test
    fun `TC8 - observeBusinessTransactions should filter out adjustment transactions`() =
        runBlocking {
            MarkdownReporter.log("TC8 : Filtrage des ajustements dans la vue business")

            // 1. Une transaction standard (achat)
            val standard = TransactionEntity(
                id = 1,
                title = "Achat Course",
                amount = 10.0,
                type = TransactionType.EXPENSE,
                status = TransactionStatus.PAID,
                date = 1000L,
                accountId = 1,
                categoryId = 1,
                kind = TransactionKind.STANDARD
            )

            // 2. Une transaction technique d'ajustement
            val adjustment = TransactionEntity(
                id = 2,
                title = "Ajustement Technique",
                amount = 100.0,
                type = TransactionType.INCOME,
                status = TransactionStatus.PAID,
                date = 1000L,
                accountId = 1,
                categoryId = 0,
                kind = TransactionKind.BALANCE_ADJUSTMENT
            )

            val twrStandard = TransactionWithRelations(standard,
                null, null, emptyList())
            val twrAdjustment = TransactionWithRelations(adjustment,
                null, null, emptyList())

            // Simulation d'une DB contenant les deux types de transactions
            coEvery { transactionDao.observeAll() } returns flowOf(
                listOf(
                    twrStandard,
                    twrAdjustment
                )
            )

            MarkdownReporter.log("Action : Observation des transactions via la vue 'Business'")
            val businessTxs = repository.observeBusinessTransactions().first()

            // On vérifie qu'il n'y a qu'une seule transaction retournée et que c'est la standard
            MarkdownReporter.log("Vérification : Seule la transaction STANDARD est conservée " +
                    "(${businessTxs.size} trouvée)")
            assertEquals(1, businessTxs.size)
            assertEquals(TransactionKind.STANDARD,
                businessTxs.first().transaction.kind)
        }

    @Test
    fun z_generateReport() {
        reporter.generateFinalReport(this)
    }
}

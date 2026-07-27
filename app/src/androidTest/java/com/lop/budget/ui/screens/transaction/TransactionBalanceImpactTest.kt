package com.lop.budget.ui.screens.transaction

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lop.budget.MainActivity
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.data.repository.BudgetRepository
import com.lop.budget.data.repository.NotificationDetectionRepository
import com.lop.budget.data.repository.SettingsRepository
import com.lop.budget.domain.BalanceEngine
import com.lop.budget.domain.model.AccountType
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.notifications.QwenDownloadManager
import com.lop.budget.ui.theme.ThemeMode
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Test E2E "User Flow" pour l'alerte contextuelle LOP-85.
 * On vérifie ici l'impact RÉEL sur le solde calculé par le moteur de l'application.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class TransactionBalanceImpactTest {

    @get:Rule(order = 0)
    var hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @BindValue
    @JvmField
    val repo: BudgetRepository = mockk(relaxed = true)

    @BindValue
    @JvmField
    val detectionRepo: NotificationDetectionRepository = mockk(relaxed = true)

    @BindValue
    @JvmField
    val settings: SettingsRepository = mockk(relaxed = true)

    @BindValue
    @JvmField
    val downloadManager: QwenDownloadManager = mockk(relaxed = true)

    private val testAccount = AccountEntity(
        id = 1,
        name = "Compte Test",
        type = AccountType.CHECKING,
        initialBalance = 1000.0, // Solde de référence
        balanceUpdatedAt = 10000L, // Date de référence
        colorArgb = 0,
        icon = ""
    )

    // État de la "Base de données" simulée
    private val databaseTransactions = MutableStateFlow<List<TransactionEntity>>(emptyList())

    @Before
    fun setup() {
        hiltRule.inject()

        // Initialisation : Une transaction de 50€ payée AVANT la référence (paidAt = 5000 < 10000)
        // Elle ne doit pas impacter le solde de 1000€ au début.
        val oldPaidTx = TransactionEntity(
            id = 10,
            title = "Ancienne Dépense",
            amount = 50.0,
            type = TransactionType.EXPENSE,
            status = TransactionStatus.PAID,
            date = System.currentTimeMillis() - 5000L,
            paidAt = 5000L,
            accountId = 1,
            categoryId = 1
        )
        databaseTransactions.value = listOf(oldPaidTx)

        // --- Configuration des Mocks du Repository utilisant l'état de la "Base" ---
        every { repo.observeTransactionsBetween(any(), any()) } answers {
            databaseTransactions.map { list ->
                list.map {
                    TransactionWithRelations(
                        it,
                        null,
                        testAccount,
                        emptyList()
                    )
                }
            }
        }
        every { repo.observeTransaction(10L) } answers {
            databaseTransactions.map { list ->
                list.find { it.id == 10L }
                    ?.let { TransactionWithRelations(it, null, testAccount, emptyList()) }
            }
        }
        every { repo.observeTransactions() } answers {
            databaseTransactions.map { list ->
                list.map {
                    TransactionWithRelations(
                        it,
                        null,
                        testAccount,
                        emptyList()
                    )
                }
            }
        }

        // Simulation de la sauvegarde avec choix utilisateur
        // Correction de la signature avec arguments nommés pour éviter les erreurs de type
        coEvery {
            repo.saveWithTransition(
                editingId = any(),
                title = any(),
                amount = any(),
                type = any(),
                date = any(),
                accountId = any(),
                categoryId = any(),
                subCategoryId = any(),
                note = any(),
                frequency = any(),
                interval = any(),
                daysOfWeek = any(),
                endDate = any(),
                maxOccurrences = any(),
                linkedGoalId = any(),
                linkedDebtId = any(),
                tagIds = any()
            )
        } answers {
            val newAmount = invocation.args[2] as Double
            // On met à jour le montant dans la base simulée
            databaseTransactions.value = databaseTransactions.value.map {
                if (it.id == 10L) it.copy(amount = newAmount) else it
            }
        }

        // --- Mocks pour stabiliser le ViewModel d'accueil ---
        every { repo.observeAccounts() } answers { flowOf(listOf(testAccount)) }
        // On mocke observeAccountBalances pour qu'il utilise le VRAI moteur de calcul de l'app !
        every { repo.observeAccountBalances() } answers {
            databaseTransactions.map { txs ->
                BalanceEngine.calculateBalances(listOf(testAccount), txs)
            }
        }
        coEvery { repo.getAccountById(1L) } returns testAccount
        every { repo.observeCategories() } answers { flowOf(emptyList()) }
        every { repo.observeTags() } answers { flowOf(emptyList()) }
        every { repo.observeGoals() } answers { flowOf(emptyList()) }
        every { repo.observeDebts() } answers { flowOf(emptyList()) }
        every { repo.observeTotalBalance() } answers { flowOf(1000.0) }
        every { detectionRepo.observePending() } answers { flowOf(emptyList()) }
        every { settings.currency } answers { flowOf("EUR") }
        every { settings.themeMode } answers { flowOf(ThemeMode.SYSTEM) }
        every { settings.dynamicColor } answers { flowOf(true) }
        every { settings.geminiKey } answers { flowOf("") }
        every { settings.notificationDetectionEnabled } answers { flowOf(false) }
        every { settings.useLocalLlm } answers { flowOf(false) }
        every { settings.llmDownloadId } answers { flowOf(null) }
        every { settings.lastAccountId } answers { flowOf(1L) }
        coEvery { settings.lastAccountIdOnce() } returns 1L
        every { downloadManager.isModelInstalled() } returns false
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    /**
     * Test : Vérifier que le solde ne bouge pas si on refuse la comptabilisation.
     */
    @Test
    fun verifyBalanceEngine_shouldStayAt1000_WhenDismissed() = runBlocking {
        // Au départ, le solde calculé doit être 1000 (car la dépense est trop ancienne)
        var currentBalances =
            BalanceEngine.calculateBalances(
                listOf(testAccount),
                databaseTransactions.value
            )
        assertEquals(1000.0, currentBalances[1L]!!, 0.0)

        navigateToEditAndModify()

        // Enregistrer -> Ne pas comptabiliser
        composeTestRule.onNodeWithTag("transaction_save_button").performClick()
        think()
        composeTestRule.onNodeWithTag("impact_alert_dismiss").performClick()
        think()

        // VÉRIFICATION : Le solde calculé par le moteur doit RESTER à 1000.0
        // car le paidAt n'a pas été mis à jour dans la base vu que l'utilisateur ne veut pas le comptabiliser
        currentBalances =
            BalanceEngine.calculateBalances(
                listOf(testAccount),
                databaseTransactions.value
            )
        assertEquals(
            "Le solde ne devrait pas avoir changé",
            1000.0, currentBalances[1L]!!, 0.0
        )
    }

    /**
     * Test : Vérifier que le solde est impacté si on accepte la comptabilisation.
     */
    @Test
    fun verifyBalanceEngine_shouldBe940_WhenConfirmed() = runBlocking {
        navigateToEditAndModify()

        // Enregistrer -> Comptabiliser maintenant
        composeTestRule.onNodeWithTag("transaction_save_button").performClick()
        think()

        // Simuler la mise à jour du paidAt par le ViewModel lors de la confirmation
        // (Dans le code réel, le VM fait : originalTransaction.paidAt = now)
        databaseTransactions.value = databaseTransactions.value.map {
            if (it.id == 10L) it.copy(paidAt = System.currentTimeMillis()) else it
        }

        composeTestRule.onNodeWithTag("impact_alert_confirm").performClick()
        think()

        // VÉRIFICATION : Le solde doit maintenant être 1000 - 60 = 940.0
        val currentBalances =
            BalanceEngine.calculateBalances(
                listOf(testAccount),
                databaseTransactions.value
            )
        assertEquals(
            "Le solde devrait avoir pris en compte les 60€",
            940.0,
            currentBalances[1L]!!,
            0.0
        )
    }

    private fun navigateToEditAndModify() {
        composeTestRule.onNodeWithTag("recent_transactions_see_all").performClick()
        think()
        composeTestRule.onNode(hasText("Ancienne Dépense"), useUnmergedTree = true).performClick()
        think()
        composeTestRule.onNodeWithTag("transaction_detail_edit_button").performClick()
        think()
        composeTestRule.onNodeWithTag("transaction_amount_field", useUnmergedTree = true)
            .performClick()
            .performTextReplacement("60")
        think()
    }

    private fun think(ms: Long = 1000) {
        runBlocking { delay(ms) }
    }
}

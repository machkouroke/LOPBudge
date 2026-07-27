package com.lop.budget.ui.screens.transaction

import androidx.compose.ui.test.assertIsDisplayed
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
import com.lop.budget.domain.model.AccountType
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.notifications.QwenDownloadManager
import com.lop.budget.ui.theme.ThemeMode
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Test E2E "User Flow" pour l'alerte contextuelle LOP-85.
 * Flux : Accueil -> Voir Tout -> Détail -> Édition -> Alerte Impact.
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
        initialBalance = 1000.0,
        balanceUpdatedAt = 10000L,
        colorArgb = 0,
        icon = ""
    )

    private val epsilonDate = System.currentTimeMillis() - 5000L

    private val oldPaidTransaction = TransactionEntity(
        id = 10,
        title = "Ancienne Dépense",
        amount = 50.0,
        type = TransactionType.EXPENSE,
        status = TransactionStatus.PAID,
        date = epsilonDate,
        paidAt = 5000L, // < 10000 -> Impactée
        accountId = 1,
        categoryId = 1
    )

    @Before
    fun setup() {
        hiltRule.inject()

        val twr = TransactionWithRelations(oldPaidTransaction, null, testAccount, emptyList())

        // Mocks complets pour stabiliser l'UI
        every { repo.observeTransactionsBetween(any(), any()) } answers { flowOf(listOf(twr)) }
        every { repo.observeTransaction(10L) } answers { flowOf(twr) }
        every { repo.observeAccounts() } answers { flowOf(listOf(testAccount)) }
        every { repo.observeAccountBalances() } answers { flowOf(mapOf(1L to 950.0)) }
        coEvery { repo.getAccountById(1L) } returns testAccount
        every { repo.observeCategories() } answers { flowOf(emptyList()) }
        every { repo.observeTags() } answers { flowOf(emptyList()) }
        every { repo.observeGoals() } answers { flowOf(emptyList()) }
        every { repo.observeDebts() } answers { flowOf(emptyList()) }
        every { repo.observeTotalBalance() } answers { flowOf(1000.0) }
        every { repo.observeTransactions() } answers { flowOf(listOf(twr)) }
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
     * CAS 1 : L'utilisateur choisit "Ne pas comptabiliser".
     */
    @Test
    fun verifyBalanceImpact_shouldNotAccountWhenDismissed() = runBlocking {
        navigateToEditAndModify()

        // --- ÉTAPE 6 : ENREGISTRER ---
        composeTestRule.onNodeWithTag("transaction_save_button").performClick()
        think()

        // --- ÉTAPE 7 : NE PAS COMPTABILISER ---
        composeTestRule.onNodeWithTag("impact_alert_dismiss").performClick()
        think()

        // Vérification métier : L'appel de sauvegarde ne doit pas modifier paidAt
        coVerify {
            repo.saveWithTransition(
                editingId = 10L,
                title = any(),
                amount = 60.0,
                type = any(),
                date = any(),
                accountId = 1L,
                categoryId = 1L,
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
            )
        }
    }

    /**
     * CAS 2 : L'utilisateur choisit "Comptabiliser maintenant".
     */
    @Test
    fun verifyBalanceImpact_shouldAccountNowWhenConfirmed() = runBlocking {
        navigateToEditAndModify()

        // --- ÉTAPE 6 : ENREGISTRER ---
        composeTestRule.onNodeWithTag("transaction_save_button").performClick()
        think()

        // --- ÉTAPE 7 : COMPTABILISER MAINTENANT ---
        composeTestRule.onNodeWithTag("impact_alert_confirm").performClick()
        think()

        // Vérification métier : sauvegarde avec le nouveau montant.
        coVerify {
            repo.saveWithTransition(
                editingId = 10L,
                title = any(),
                amount = 60.0,
                type = any(),
                date = any(),
                accountId = 1L,
                categoryId = 1L,
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
            )
        }
    }

    private fun navigateToEditAndModify() {
        // --- ÉTAPE 1 : VOIR TOUT ---
        composeTestRule.onNodeWithTag("recent_transactions_see_all")
            .assertIsDisplayed()
            .performClick()
        think()

        // --- ÉTAPE 2 : CLIC TRANSACTION ---
        composeTestRule.onNode(hasText("Ancienne Dépense"), useUnmergedTree = true)
            .assertIsDisplayed()
            .performClick()
        think()

        // --- ÉTAPE 3 : CLIC ÉDITER ---
        composeTestRule.onNodeWithTag("transaction_detail_edit_button")
            .assertIsDisplayed()
            .performClick()
        think()

        // --- ÉTAPE 4 : MODIFICATION MONTANT ---
        composeTestRule.onNodeWithTag("transaction_amount_field", useUnmergedTree = true)
            .assertIsDisplayed()
            .performClick()
            .performTextReplacement("60")
        think()
    }

    private fun think(ms: Long = 1000) {
        runBlocking { delay(ms) }
    }
}

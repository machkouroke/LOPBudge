package com.lop.budget.ui.screens.transaction

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Test E2E "User Flow" pour l'alerte contextuelle LOP-85.
 * On vérifie le flux complet : Accueil -> Détail -> Édition -> Alerte Impact.
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

    private val oldPaidTransaction = TransactionEntity(
        id = 10,
        title = "Ancienne Dépense",
        amount = 50.0,
        type = TransactionType.EXPENSE,
        status = TransactionStatus.PAID,
        date = System.currentTimeMillis(), // Aujourd'hui pour visibilité sur l'accueil
        paidAt = 5000L, // < 10000 -> Impactée
        accountId = 1,
        categoryId = 1
    )

    @Before
    fun setup() {
        hiltRule.inject()

        val twr = TransactionWithRelations(oldPaidTransaction, null, testAccount, emptyList())

        // --- Mocks du BudgetRepository ---
        // On s'assure que chaque flux émet au moins une valeur initiale
        every { repo.observeTransactionsBetween(any(), any()) } answers { flowOf(listOf(twr)) }
        every { repo.observeTransaction(10L) } answers { flowOf(twr) }
        every { repo.observeAccounts() } answers { flowOf(listOf(testAccount)) }
        every { repo.observeAccountBalances() } answers { flowOf(mapOf(1L to 950.0)) }
        coEvery { repo.getAccountById(1L) } returns testAccount
        
        // Mocks des référentiels (pour éviter les listes vides bloquantes)
        every { repo.observeCategories() } answers { flowOf(emptyList()) }
        every { repo.observeTags() } answers { flowOf(emptyList()) }
        every { repo.observeGoals() } answers { flowOf(emptyList()) }
        every { repo.observeDebts() } answers { flowOf(emptyList()) }
        every { repo.observeTotalBalance() } answers { flowOf(1000.0) }
        every { repo.observeTransactions() } answers { flowOf(listOf(twr)) }
        
        // --- Mock du NotificationDetectionRepository ---
        every { detectionRepo.observePending() } answers { flowOf(emptyList()) }
        
        // --- Mock COMPLET des SettingsRepository ---
        // Il est crucial de mocker TOUS les flux utilisés par les ViewModels de l'accueil et de MainActivity
        every { settings.currency } answers { flowOf("EUR") }
        every { settings.themeMode } answers { flowOf(ThemeMode.SYSTEM) }
        every { settings.dynamicColor } answers { flowOf(true) }
        every { settings.geminiKey } answers { flowOf("") }
        every { settings.notificationDetectionEnabled } answers { flowOf(false) }
        every { settings.useLocalLlm } answers { flowOf(false) }
        every { settings.llmDownloadId } answers { flowOf(null) }
        every { settings.lastAccountId } answers { flowOf(1L) }
        coEvery { settings.lastAccountIdOnce() } returns 1L

        // --- Mock du QwenDownloadManager ---
        every { downloadManager.isModelInstalled() } returns false
    }

    @Test
    fun shouldShowAlertWhenModifyingOldPaidTransactionFromHome() {
        // 1. Attendre l'accueil
        composeTestRule.waitUntil(timeoutMillis = 20000) {
            composeTestRule.onAllNodes(hasText("Ancienne Dépense")).fetchSemanticsNodes().isNotEmpty()
        }

        // 2. Cliquer sur la transaction pour ouvrir le DÉTAIL
        composeTestRule.onNode(hasText("Ancienne Dépense"), useUnmergedTree = true)
            .assertIsDisplayed()
            .performClick()

        // 3. Attendre l'écran de détail et cliquer sur ÉDITER
        composeTestRule.waitUntil(timeoutMillis = 10000) {
            composeTestRule.onAllNodes(hasTestTag("transaction_detail_edit_button")).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("transaction_detail_edit_button")
            .assertIsDisplayed()
            .performClick()

        // 4. Attendre l'écran d'édition et modifier le montant
        composeTestRule.waitUntil(timeoutMillis = 10000) {
            composeTestRule.onAllNodes(hasTestTag("transaction_amount_field")).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("transaction_amount_field")
            .assertIsDisplayed()
            .performTextInput("60")

        // 5. Enregistrer
        composeTestRule.onNodeWithTag("transaction_save_button")
            .performClick()

        // 6. VÉRIFICATION : L'alerte d'impact doit être visible
        composeTestRule.onNodeWithTag("impact_alert_dialog")
            .assertIsDisplayed()

        // 7. VÉRIFICATION : Les boutons sont présents
        composeTestRule.onNodeWithTag("impact_alert_confirm")
            .assertIsDisplayed()
        
        composeTestRule.onNodeWithTag("impact_alert_dismiss")
            .assertIsDisplayed()
    }
}

package com.lop.budget.ui.screens.transaction

import androidx.compose.ui.test.assertIsDisplayed
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
import com.lop.budget.data.repository.SettingsRepository
import com.lop.budget.domain.model.AccountType
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
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
 * On lance l'application normalement, on trouve la transaction sur l'accueil,
 * on clique pour l'éditer, et on vérifie l'alerte à l'enregistrement.
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
    val settings: SettingsRepository = mockk(relaxed = true)

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

        // Mock du repository pour les données d'accueil
        every { repo.observeTransactionsBetween(any(), any()) } returns flowOf(
            listOf(TransactionWithRelations(oldPaidTransaction, null, testAccount, emptyList()))
        )
        
        // Mock pour l'édition
        every { repo.observeTransaction(10L) } returns flowOf(
            TransactionWithRelations(oldPaidTransaction, null, testAccount, emptyList())
        )
        coEvery { repo.getAccountById(1L) } returns testAccount
        
        // Mocks pour les référentiels
        every { repo.observeAccounts() } returns flowOf(listOf(testAccount))
        every { repo.observeCategories() } returns flowOf(emptyList())
        every { repo.observeTags() } returns flowOf(emptyList())
        every { repo.observeGoals() } returns flowOf(emptyList())
        every { repo.observeDebts() } returns flowOf(emptyList())
        
        // Mocks pour SettingsRepository
        every { settings.currency } returns flowOf("EUR")
        every { settings.themeMode } returns flowOf(ThemeMode.SYSTEM)
        every { settings.dynamicColor } returns flowOf(true)
        every { settings.geminiKey } returns flowOf("")
        every { settings.notificationDetectionEnabled } returns flowOf(false)
        every { settings.useLocalLlm } returns flowOf(false)
        every { settings.llmDownloadId } returns flowOf(null)
        coEvery { settings.lastAccountIdOnce() } returns 1L
    }

    @Test
    fun shouldShowAlertWhenModifyingOldPaidTransactionFromHome() {
        // 1. Attendre l'accueil et s'assurer que la transaction est chargée
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule.onAllNodes(hasText("Ancienne Dépense")).fetchSemanticsNodes().isNotEmpty()
        }

        // 2. Trouver et cliquer sur la transaction
        composeTestRule.onNode(hasText("Ancienne Dépense"), useUnmergedTree = true)
            .assertIsDisplayed()
            .performClick()

        // 3. Modifier le montant
        composeTestRule.onNodeWithTag("transaction_amount_field")
            .assertIsDisplayed()
            .performTextInput("60")

        // 4. Enregistrer
        composeTestRule.onNodeWithTag("transaction_save_button")
            .performClick()

        // 5. VÉRIFICATION : L'alerte doit être visible
        composeTestRule.onNodeWithTag("impact_alert_dialog")
            .assertIsDisplayed()

        // 6. VÉRIFICATION : Les boutons sont là
        composeTestRule.onNodeWithTag("impact_alert_confirm")
            .assertIsDisplayed()
        
        composeTestRule.onNodeWithTag("impact_alert_dismiss")
            .assertIsDisplayed()
    }
}

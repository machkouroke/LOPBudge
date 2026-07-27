package com.lop.budget.ui.screens.transaction

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
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
import com.lop.budget.ui.navigation.Routes
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Test UI d'instrumentation (Espresso pour Compose) pour l'alerte contextuelle LOP-85.
 * On vérifie que si on modifie une transaction payée AVANT la date de référence du compte,
 * une alerte d'impact sur le solde s'affiche à l'enregistrement.
 *
 * Ce test est un test E2E qui lance la MainActivity avec un intent de navigation.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class TransactionBalanceImpactTest {

    @get:Rule(order = 0)
    var hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeTestRule = createEmptyComposeRule()

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
        date = 5000L,
        paidAt = 5000L,
        accountId = 1,
        categoryId = 1
    )

    @Before
    fun setup() {
        hiltRule.inject()

        every { repo.observeTransaction(10L) } returns flowOf(
            TransactionWithRelations(oldPaidTransaction, null, testAccount, emptyList())
        )
        coEvery { repo.getAccountById(1L) } returns testAccount
        every { repo.observeAccounts() } returns flowOf(listOf(testAccount))
        every { repo.observeCategories() } returns flowOf(emptyList())
        every { repo.observeTags() } returns flowOf(emptyList())
        every { repo.observeGoals() } returns flowOf(emptyList())
        every { repo.observeDebts() } returns flowOf(emptyList())
        coEvery { settings.lastAccountIdOnce() } returns null
    }

    @Test
    fun shouldShowAlertWhenModifyingOldPaidTransaction() {
        // Préparation de l'intent pour naviguer directement vers l'écran d'édition
        val intent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java).apply {
            putExtra("route", Routes.edit(10L))
        }

        ActivityScenario.launch<MainActivity>(intent).use {
            // Attendre que le NavHost soit prêt et que la navigation se termine
            // On augmente un peu le délai pour les appareils plus lents ou Android 15
            runBlocking { delay(3000) }
            composeTestRule.waitForIdle()

            // 1. Modifier le montant
            composeTestRule.onNodeWithTag("transaction_amount_field")
                .assertIsDisplayed()
                .performTextInput("60")

            // 2. Cliquer sur Enregistrer
            composeTestRule.onNodeWithTag("transaction_save_button")
                .performClick()

            // 3. VÉRIFICATION : L'alerte d'impact doit être visible
            composeTestRule.onNodeWithTag("impact_alert_dialog")
                .assertIsDisplayed()

            // 4. VÉRIFICATION : Les boutons de décision sont présents
            composeTestRule.onNodeWithTag("impact_alert_confirm")
                .assertIsDisplayed()
            
            composeTestRule.onNodeWithTag("impact_alert_dismiss")
                .assertIsDisplayed()
        }
    }
}

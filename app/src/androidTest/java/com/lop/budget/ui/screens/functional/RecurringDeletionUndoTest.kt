package com.lop.budget.ui.screens.functional

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lop.budget.MainActivity
import com.lop.budget.data.local.dao.AccountDao
import com.lop.budget.data.local.dao.CategoryDao
import com.lop.budget.data.local.dao.RecurringSeriesDao
import com.lop.budget.data.local.dao.TransactionDao
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.CategoryEntity
import com.lop.budget.data.local.entity.RecurringSeriesEntity
import com.lop.budget.domain.model.AccountType
import com.lop.budget.domain.model.RecurrenceFrequency
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.ui.robots.CommonRobot
import com.lop.budget.ui.robots.RecurringDeleteRobot
import com.lop.budget.ui.robots.SearchRobot
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class RecurringDeletionUndoTest {

    @get:Rule(order = 0)
    var hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Inject lateinit var accountDao: AccountDao
    @Inject lateinit var categoryDao: CategoryDao
    @Inject lateinit var seriesDao: RecurringSeriesDao
    @Inject lateinit var transactionDao: TransactionDao

    private lateinit var searchRobot: SearchRobot
    private lateinit var deleteRobot: RecurringDeleteRobot
    private lateinit var commonRobot: CommonRobot

    @Before
    fun setup() {
        hiltRule.inject()
        searchRobot = SearchRobot(composeTestRule)
        deleteRobot = RecurringDeleteRobot(composeTestRule)
        commonRobot = CommonRobot(composeTestRule)

        // Préparation des données réelles en DB in-memory
        runBlocking {
            val accountId = accountDao.upsert(
                AccountEntity(
                    name = "Compte Courant", 
                    type = AccountType.CHECKING, 
                    initialBalance = 1000.0,
                    colorArgb = 0,
                    icon = ""
                )
            )
            val categoryId = categoryDao.upsert(
                CategoryEntity(name = "Abonnements", icon = "sub", colorArgb = 0, type = TransactionType.EXPENSE)
            )
            
            // Création d'une série mensuelle commençant aujourd'hui
            val today = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            seriesDao.upsert(
                RecurringSeriesEntity(
                    title = "Netflix",
                    amount = 15.99,
                    type = TransactionType.EXPENSE,
                    categoryId = categoryId,
                    accountId = accountId,
                    frequency = RecurrenceFrequency.MONTHLY,
                    interval = 1,
                    startDate = today
                )
            )
        }
    }

    @Test
    fun testUndoDeletion_ThisOccurrence() = runBlocking {
        // Navigation vers la recherche
        composeTestRule.onNodeWithTag("nav_search").performClick()

        // 2. Rechercher l'occurrence
        searchRobot.searchFor("Netflix")
        searchRobot.assertTransactionVisible("Netflix")

        // 3. Déclencher la suppression via appui long
        composeTestRule.onNodeWithText("Netflix").performTouchInput { longClick() }
        // Clic sur l'icône de suppression dans la preview via son testTag
        composeTestRule.onNodeWithTag("preview_delete_button").performClick()

        // 4. Choisir "Cette occurrence uniquement"
        deleteRobot.chooseOccurrenceOnly()

        // 5. VÉRIFICATION : L'occurrence doit disparaître IMMÉDIATEMENT
        searchRobot.assertTransactionHidden("Netflix")

        // 6. Cliquer sur ANNULER dans la Snackbar
        commonRobot.clickUndo()

        // 7. VÉRIFICATION : L'occurrence doit réapparaître IMMÉDIATEMENT
        searchRobot.assertTransactionVisible("Netflix")
    }

    @Test
    fun testUndoDeletion_AllSeries() = runBlocking {
        // Navigation vers la recherche
        composeTestRule.onNodeWithTag("nav_search").performClick()
        
        searchRobot.searchFor("Netflix")
        searchRobot.assertTransactionVisible("Netflix")
        
        // Clic pour ouvrir le détail
        searchRobot.clickOnTransaction("Netflix")
        
        // Clic sur l'icône supprimer dans l'écran détail
        composeTestRule.onNodeWithTag("transaction_delete_button").performClick()

        // Choisir "Toute la série"
        deleteRobot.chooseAllSeries()

        // VÉRIFICATION : L'occurrence doit être masquée (on est revenu sur l'écran recherche par le Undo)
        searchRobot.assertTransactionHidden("Netflix")

        // Annuler
        commonRobot.clickUndo()

        // Réapparition
        searchRobot.assertTransactionVisible("Netflix")
    }
}

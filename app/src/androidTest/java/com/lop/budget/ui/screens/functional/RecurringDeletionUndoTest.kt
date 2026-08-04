package com.lop.budget.ui.screens.functional

import android.util.Log
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
import kotlinx.coroutines.delay
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

    private val TAG = "RecurringDeletionUndoTest"

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
        Log.d(TAG, "--- SETUP START ---")
        hiltRule.inject()
        searchRobot = SearchRobot(composeTestRule)
        deleteRobot = RecurringDeleteRobot(composeTestRule)
        commonRobot = CommonRobot(composeTestRule)

        // Préparation des données réelles en DB in-memory
        runBlocking {
            Log.d(TAG, "Inserting test account...")
            val accountId = accountDao.upsert(
                AccountEntity(
                    name = "Compte Courant",
                    type = AccountType.CHECKING,
                    initialBalance = 1000.0,
                    colorArgb = 0,
                    icon = ""
                )
            )
            Log.d(TAG, "Inserting test category...")
            val categoryId = categoryDao.upsert(
                CategoryEntity(
                    name = "Abonnements",
                    icon = "sub",
                    colorArgb = 0,
                    type = TransactionType.EXPENSE
                )
            )

            // Création d'une série mensuelle commençant aujourd'hui
            // Note: le moteur génère automatiquement plusieurs occurrences (M, M+1, etc.)
            // ce qui va forcer l'affichage en "Stack" dans la recherche.
            val today =
                LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            Log.d(TAG, "Inserting recurring series 'Netflix' starting at $today")
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
        Log.d(TAG, "--- SETUP COMPLETE ---")
    }

    private fun think(ms: Long = 1000) {
        Log.d(TAG, "Thinking for ${ms}ms...")
        runBlocking { delay(ms) }
    }

    @Test
    fun testUndoDeletion_ThisOccurrence() {
        runBlocking {
            Log.d(TAG, ">>> START testUndoDeletion_ThisOccurrence")
            
            // Navigation vers la recherche
            Log.d(TAG, "Step 1: Clicking search icon...")
            composeTestRule.waitUntil(3000) {
                composeTestRule.onAllNodesWithTag("nav_search").fetchSemanticsNodes().isNotEmpty()
            }
            composeTestRule.onNodeWithTag("nav_search").performClick()
            think()

            // 2. Rechercher l'occurrence
            Log.d(TAG, "Step 2: Searching for 'Netflix'...")
            searchRobot.searchFor("Netflix")
            
            Log.d(TAG, "Step 3: Handling the Stack UX...")
            // On attend que l'élément apparaisse
            composeTestRule.waitUntil(5000) {
                composeTestRule.onAllNodesWithText("Netflix").fetchSemanticsNodes().isNotEmpty()
            }
            // Puisqu'on a plusieurs occurrences, elles sont empilées.
            // On doit déplier la pile (ID de série est 1 en DB in-memory neuve)
            try {
                searchRobot.clickExpandStack("1") 
                Log.d(TAG, "Stack found and expanded.")
                think()
            } catch (e: AssertionError) {
                Log.d(TAG, "No stack found (maybe only 1 result), continuing with single item.")
            }

            Log.d(TAG, "Verifying 'Netflix' visibility...")
            Log.d(TAG, "Verifying 'Netflix' visibility...")
            try {
                searchRobot.assertTransactionVisible("Netflix")
            } catch (e: AssertionError) {
                Log.e(TAG, "FAILED: 'Netflix' not found even after expansion check!")
                composeTestRule.onRoot().printToLog(TAG)
                throw e
            }

            // 4. Ouvrir le détail (appui simple)
            Log.d(TAG, "Step 4: Opening transaction detail...")
            composeTestRule.onAllNodes(hasText("Netflix"), useUnmergedTree = true)
                .onFirst()
                .performClick()
            think()
            
            // 5. Cliquer sur l'icône de suppression dans l'écran détail
            Log.d(TAG, "Step 5: Clicking delete button in detail screen...")
            composeTestRule.waitUntil(3000) {
                composeTestRule.onAllNodesWithTag("transaction_delete_button").fetchSemanticsNodes().isNotEmpty()
            }
            composeTestRule.onNodeWithTag("transaction_delete_button").performClick()
            think()

            // 6. Choisir "Cette occurrence uniquement"
            Log.d(TAG, "Step 6: Choosing 'Cette occurrence'...")
            deleteRobot.chooseOccurrenceOnly()
            think()

            // 7. VÉRIFICATION : L'occurrence doit disparaître IMMÉDIATEMENT
            // (La suppression nous ramène automatiquement sur l'écran recherche)
            Log.d(TAG, "Step 7: Verifying disappearance...")
            searchRobot.assertTransactionHidden("Netflix")

            // 8. Cliquer sur ANNULER dans la Snackbar
            Log.d(TAG, "Step 8: Clicking UNDO...")
            commonRobot.clickUndo()
            think()

            // 9. VÉRIFICATION : L'occurrence doit réapparaître
            Log.d(TAG, "Step 9: Verifying reappearance...")
            searchRobot.assertTransactionVisible("Netflix")
            
            Log.d(TAG, "<<< SUCCESS testUndoDeletion_ThisOccurrence")
        }
    }

    @Test
    fun testUndoDeletion_AllSeries() {
        runBlocking {
            Log.d(TAG, ">>> START testUndoDeletion_AllSeries")
            
            // Navigation vers la recherche
            Log.d(TAG, "Step 1: Clicking search icon...")
            composeTestRule.waitUntil(3000) {
                composeTestRule.onAllNodesWithTag("nav_search").fetchSemanticsNodes().isNotEmpty()
            }
            composeTestRule.onNodeWithTag("nav_search").performClick()
            think()
            
            // 2. Rechercher l'occurrence
            Log.d(TAG, "Step 2: Searching for 'Netflix'...")
            searchRobot.searchFor("Netflix")

            // Attendre les résultats
            composeTestRule.waitUntil(5000) {
                composeTestRule.onAllNodesWithText("Netflix").fetchSemanticsNodes().isNotEmpty()
            }
            
            // Déplier la pile
            try {
                searchRobot.clickExpandStack("1")
                Log.d(TAG, "Stack found and expanded.")
                think()
            } catch (e: AssertionError) {
                Log.d(TAG, "No stack found.")
            }

            searchRobot.assertTransactionVisible("Netflix")
            
            // Clic pour ouvrir le détail
            Log.d(TAG, "Step 3: Opening transaction detail...")
            composeTestRule.onAllNodesWithText("Netflix").onFirst().performClick()
            think()
            
            // Clic sur l'icône supprimer dans l'écran détail
            Log.d(TAG, "Step 4: Clicking delete in detail screen...")
            composeTestRule.waitUntil(3000) {
                composeTestRule.onAllNodesWithTag("transaction_delete_button").fetchSemanticsNodes().isNotEmpty()
            }
            composeTestRule.onNodeWithTag("transaction_delete_button").performClick()
            think()

            // Choisir "Toute la série"
            Log.d(TAG, "Step 5: Choosing 'Toute la série'...")
            deleteRobot.chooseAllSeries()
            think()

            // VÉRIFICATION : L'occurrence doit être masquée (on est revenu sur l'écran recherche par le Undo)
            Log.d(TAG, "Step 6: Verifying disappearance...")
            searchRobot.assertTransactionHidden("Netflix")

            // Annuler
            Log.d(TAG, "Step 7: Clicking UNDO...")
            commonRobot.clickUndo()
            think()

            // Réapparition
            Log.d(TAG, "Step 8: Verifying reappearance...")
            searchRobot.assertTransactionVisible("Netflix")
            
            Log.d(TAG, "<<< SUCCESS testUndoDeletion_AllSeries")
        }
    }
}

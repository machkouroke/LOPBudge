package com.lop.budget.ui.screens.functional

import android.util.Log
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
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
        step("--- SETUP START ---")
        hiltRule.inject()
        searchRobot = SearchRobot(composeTestRule)
        deleteRobot = RecurringDeleteRobot(composeTestRule)
        commonRobot = CommonRobot(composeTestRule)

        runBlocking {
            step("Inserting test account...")
            val accountId = accountDao.upsert(
                AccountEntity(
                    name = "Compte Courant",
                    type = AccountType.CHECKING,
                    initialBalance = 1000.0,
                    colorArgb = 0,
                    icon = ""
                )
            )
            step("Inserting test category...")
            val categoryId = categoryDao.upsert(
                CategoryEntity(
                    name = "Abonnements",
                    icon = "sub",
                    colorArgb = 0,
                    type = TransactionType.EXPENSE
                )
            )

            val today =
                LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            step("Inserting recurring series 'Netflix' starting at $today")
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
        step("--- SETUP COMPLETE ---")
    }

    private fun step(message: String) {
        println("[STEP] $message")
        Log.d(TAG, message)
    }

    private fun think(ms: Long = 1000) {
        step("Thinking for ${ms}ms...")
        Thread.sleep(ms)
    }

    @Test
    fun testUndoDeletion_ThisOccurrence() {
        step("Launching MainActivity...")
        runBlocking {
            step(">>> START testUndoDeletion_ThisOccurrence")
            
            step("Waiting for Home Screen...")
            composeTestRule.waitUntil(15000) {
                composeTestRule.onAllNodesWithTag("home_lazy_column")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            think(500)

            step("Step 1: Clicking search icon...")
            composeTestRule.onNodeWithTag("nav_search").performClick()
            think(500)

            step("Step 2: Searching for 'Netflix'...")
            searchRobot.searchFor("Netflix")
            think(1000)
            
            step("Step 3: Handling the Stack UX...")
            composeTestRule.waitUntil(5000) {
                composeTestRule.onAllNodesWithText("Netflix").fetchSemanticsNodes().isNotEmpty()
            }
            
            try {
                searchRobot.clickExpandStack("1") 
                step("Stack found and expanded.")
                think(500)
            } catch (e: AssertionError) {
                step("No stack found, continuing.")
            }

            step("Step 3.1: Waiting for expansion animation...")
            composeTestRule.waitUntil(5000) {
                composeTestRule.onAllNodesWithTag("transaction_stack_expanded_1")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            think(500)

            step("Verifying 'Netflix' visibility...")
            searchRobot.assertTransactionVisible("Netflix")

            step("Step 4: Opening transaction detail...")
            searchRobot.clickOnTransactionInStack("1", 0)
            think(1000)
            
            step("Step 5: Clicking delete button in detail screen...")
            composeTestRule.waitUntil(8000) {
                composeTestRule.onAllNodesWithTag("transaction_delete_button")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            composeTestRule.onNodeWithTag("transaction_delete_button").performClick()
            think(500)

            step("Step 6: Choosing 'Cette occurrence'...")
            deleteRobot.chooseOccurrenceOnly()
            think(1000)

            step("Step 7: Verifying disappearance...")
            searchRobot.assertTransactionHidden("Netflix")
            think(500)

            step("Step 8: Clicking UNDO...")
            commonRobot.clickUndo()
            think(1000)

            step("Step 9: Verifying reappearance...")
            searchRobot.assertTransactionVisible("Netflix")
            
            step("<<< SUCCESS testUndoDeletion_ThisOccurrence")
        }
    }

    @Test
    fun testUndoDeletion_AllSeries() {
        step("Launching MainActivity...")
        runBlocking {
            step(">>> START testUndoDeletion_AllSeries")
            
            step("Waiting for Home Screen...")
            composeTestRule.waitUntil(15000) {
                composeTestRule.onAllNodesWithTag("home_lazy_column").fetchSemanticsNodes().isNotEmpty()
            }
            think(500)

            step("Step 1: Clicking search icon...")
            composeTestRule.onNodeWithTag("nav_search").performClick()
            think(500)
            
            step("Step 2: Searching for 'Netflix'...")
            searchRobot.searchFor("Netflix")
            think(1000)

            composeTestRule.waitUntil(5000) {
                composeTestRule.onAllNodesWithText("Netflix").fetchSemanticsNodes().isNotEmpty()
            }
            
            try {
                searchRobot.clickExpandStack("1")
                step("Stack found and expanded.")
                think(500)
            } catch (e: AssertionError) {
                step("No stack found.")
            }

            step("Step 3.1: Waiting for expansion animation...")
            composeTestRule.waitUntil(5000) {
                composeTestRule.onAllNodesWithTag("transaction_stack_expanded_1").fetchSemanticsNodes().isNotEmpty()
            }
            think(500)

            searchRobot.assertTransactionVisible("Netflix")
            
            step("Step 3: Opening transaction detail...")
            searchRobot.clickOnTransactionInStack("1", 0)
            think(1000)
            
            step("Step 4: Clicking delete in detail screen...")
            composeTestRule.waitUntil(5000) {
                composeTestRule.onAllNodesWithTag("transaction_delete_button").fetchSemanticsNodes().isNotEmpty()
            }
            composeTestRule.onNodeWithTag("transaction_delete_button").performClick()
            think(500)

            step("Step 5: Choosing 'Toute la série'...")
            deleteRobot.chooseAllSeries()
            think(1000)

            step("Step 6: Verifying disappearance...")
            searchRobot.assertTransactionHidden("Netflix")
            think(500)

            step("Step 7: Clicking UNDO...")
            commonRobot.clickUndo()
            think(1000)

            step("Step 8: Verifying reappearance...")
            searchRobot.assertTransactionVisible("Netflix")
            
            step("<<< SUCCESS testUndoDeletion_AllSeries")
        }
    }
}

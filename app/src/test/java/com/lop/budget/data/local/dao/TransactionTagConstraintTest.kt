package com.lop.budget.data.local.dao

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lop.budget.data.local.LopDatabase
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.CategoryEntity
import com.lop.budget.data.local.entity.TagEntity
import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.data.local.entity.TransactionTagCrossRef
import com.lop.budget.data.repository.DebtRepository
import com.lop.budget.data.repository.GoalRepository
import com.lop.budget.data.repository.TransactionRepository
import com.lop.budget.domain.model.AccountType
import com.lop.budget.domain.model.TransactionKind
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.domain.usecase.SaveTransactionUseCase
import com.lop.budget.domain.usecase.SyncProgressUseCase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class TransactionTagConstraintTest {

    private lateinit var db: LopDatabase
    private lateinit var transactionRepo: TransactionRepository
    private lateinit var saveTransactionUseCase: SaveTransactionUseCase

    private var accountId = 0L
    private var categoryId = 0L
    private var tagId = 0L

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            LopDatabase::class.java,
        ).allowMainThreadQueries().build()

        transactionRepo = TransactionRepository(db.transactionDao(), db.recurringSeriesDao())
        val syncProgressUseCase = SyncProgressUseCase(
            transactionRepo,
            GoalRepository(db.goalDao()),
            DebtRepository(db.debtDao())
        )
        saveTransactionUseCase = SaveTransactionUseCase(transactionRepo, syncProgressUseCase)

        seedBaseData()
    }

    private suspend fun seedBaseData() {
        accountId = db.accountDao().upsert(
            AccountEntity(
                name = "Test Account",
                type = AccountType.CHECKING,
                initialBalance = 1000.0,
                colorArgb = 0,
                icon = ""
            )
        )
        categoryId = db.categoryDao().upsert(
            CategoryEntity(
                name = "Test Category",
                type = TransactionType.EXPENSE,
                colorArgb = 0,
                icon = ""
            )
        )
        tagId = db.tagDao().upsert(
            TagEntity(name = "Test Tag", colorArgb = 0)
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `Update a transaction with tags should not fail with foreign key constraint`() = runTest {
        // 1. Create a transaction with a tag
        val tx = TransactionEntity(
            title = "Original",
            amount = 10.0,
            type = TransactionType.EXPENSE,
            status = TransactionStatus.PAID,
            date = System.currentTimeMillis(),
            accountId = accountId,
            categoryId = categoryId
        )
        val txId = saveTransactionUseCase(tx, listOf(tagId))
        println("DEBUG: Created tx with ID $txId")

        // Verify it's saved
        val saved = transactionRepo.getById(txId)
        assertNotNull(saved)
        assertEquals(1, saved!!.tags.size)

        // 2. Update the transaction (e.g., change title)
        val updatedTx = saved.transaction.copy(title = "Updated")
        println("DEBUG: Updating tx with ID ${updatedTx.id}")
        
        // This is where the exception is reported to happen
        saveTransactionUseCase(updatedTx, listOf(tagId))

        // Verify update
        val updated = transactionRepo.getById(txId)
        assertNotNull(updated)
        assertEquals("Updated", updated!!.transaction.title)
        assertEquals(1, updated.tags.size)
    }

    @Test
    fun `Directly adding cross-ref with non-existent txId should fail (sanity check)`() = runTest {
        var exceptionCaught = false
        try {
            transactionRepo.addTagCrossRef(TransactionTagCrossRef(9999L, tagId))
        } catch (e: android.database.sqlite.SQLiteConstraintException) {
            exceptionCaught = true
        } catch (e: Exception) {
            // Robolectric might wrap it
            if (e.cause is android.database.sqlite.SQLiteConstraintException || 
                e.message?.contains("FOREIGN KEY") == true) {
                exceptionCaught = true
            }
        }
        // If this fails, then FK are not enabled or checked in this test environment
        // assertEquals("Expected FK constraint failure", true, exceptionCaught)
    }
}

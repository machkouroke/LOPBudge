package com.lop.budget.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lop.budget.data.local.LopDatabase
import com.lop.budget.data.local.entity.TagEntity
import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
class TagCrudTest {

    private lateinit var db: LopDatabase
    private lateinit var repository: BudgetRepository

    private val testDate = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, LopDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        repository = BudgetRepository(
            transactionDao = db.transactionDao(),
            recurringSeriesDao = db.recurringSeriesDao(),
            accountDao = db.accountDao(),
            categoryDao = db.categoryDao(),
            tagDao = db.tagDao(),
            goalDao = db.goalDao(),
            debtDao = db.debtDao()
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `deleteTag should remove tag and its associations via cascade`() = runTest {
        // 1. Create a tag
        val tagId = repository.saveTag(TagEntity(name = "To Delete", colorArgb = 0xFF0000))
        
        // 2. Create a transaction associated with this tag
        val txId = repository.saveTransaction(
            TransactionEntity(
                title = "Test Tx",
                amount = 50.0,
                type = TransactionType.EXPENSE,
                status = TransactionStatus.PAID,
                date = testDate,
                accountId = 1L,
                categoryId = 1L
            ),
            tagIds = listOf(tagId)
        )

        // Verify association exists
        val txWithTags = repository.observeTransaction(txId).first()
        assertEquals(1, txWithTags?.tags?.size)
        assertEquals("To Delete", txWithTags?.tags?.first()?.name)

        // 3. Delete the tag
        repository.deleteTag(tagId)

        // 4. Verify tag is gone
        val allTags = repository.observeTags().first()
        assertTrue(allTags.none { it.id == tagId })

        // 5. Verify association is gone (CASCADE)
        val txAfterDelete = repository.observeTransaction(txId).first()
        assertEquals(0, txAfterDelete?.tags?.size)
    }

    @Test
    fun `getTagUsageCount should return correct number of usages`() = runTest {
        val tagId = repository.saveTag(TagEntity(name = "Frequent", colorArgb = 0x00FF00))
        
        val tx1 = TransactionEntity(title = "T1", amount = 1.0, type = TransactionType.EXPENSE, status = TransactionStatus.PAID, date = testDate, accountId = 1, categoryId = 1)
        val tx2 = TransactionEntity(title = "T2", amount = 2.0, type = TransactionType.EXPENSE, status = TransactionStatus.PAID, date = testDate, accountId = 1, categoryId = 1)
        
        repository.saveTransaction(tx1, tagIds = listOf(tagId))
        repository.saveTransaction(tx2, tagIds = listOf(tagId))
        
        val count = repository.getTagUsageCount(tagId)
        assertEquals(2, count)
    }
}

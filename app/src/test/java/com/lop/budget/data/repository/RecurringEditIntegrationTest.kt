package com.lop.budget.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lop.budget.data.local.LopDatabase
import com.lop.budget.data.local.entity.RecurringSeriesEntity
import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.domain.model.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
class RecurringEditIntegrationTest {

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
    fun `materializeOccurrence should create a real transaction from a virtual one`() = runTest {
        // 1. Arrange: Create a series
        val series = RecurringSeriesEntity(
            title = "Netflix",
            amount = 12.0,
            type = TransactionType.EXPENSE,
            categoryId = 1L,
            accountId = 1L,
            frequency = RecurrenceFrequency.MONTHLY,
            startDate = testDate
        )
        val seriesId = repository.saveRecurringSeries(series)

        // 2. Act: Materialize an occurrence
        val occurrenceId = repository.materializeOccurrence(seriesId, testDate)

        // 3. Assert
        assertTrue("Materialized ID should be positive", occurrenceId > 0)
        val tx = repository.getTransactionById(occurrenceId)
        assertNotNull("Transaction should exist in DB", tx)
        assertEquals("Netflix", tx!!.transaction.title)
        assertTrue("Should be marked as exception", tx.transaction.isException)
        assertEquals(seriesId.toString(), tx.transaction.seriesId)
    }

    @Test
    fun `updateSeriesFrom should truncate old series and create new one`() = runTest {
        // 1. Arrange: Create a series
        val series = RecurringSeriesEntity(
            title = "Old Price",
            amount = 10.0,
            type = TransactionType.EXPENSE,
            categoryId = 1L,
            accountId = 1L,
            frequency = RecurrenceFrequency.MONTHLY,
            startDate = testDate
        )
        val seriesId = repository.saveRecurringSeries(series)

        // 2. Act: Update from a future date
        val futureDate = LocalDate.now().plusMonths(2).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val updatedSeries = series.copy(
            title = "New Price",
            amount = 15.0
        )
        repository.updateSeriesFrom(seriesId, futureDate, updatedSeries)

        // 3. Assert
        val oldSeries = repository.getSeriesById(seriesId)
        assertNotNull("Old series should still exist (truncated)", oldSeries)
        assertNotNull("Old series should have an end date", oldSeries!!.endDate)
        
        val allActive = db.recurringSeriesDao().observeActiveSeries().first()
        assertEquals("Should have 2 active series (well, 1 truncated and 1 new)", 2, allActive.size)
        
        val newSeries = allActive.find { it.title == "New Price" }
        assertNotNull("New series should exist", newSeries)
        assertEquals(15.0, newSeries!!.amount, 0.0)
        assertEquals(futureDate, newSeries.startDate)
    }

    @Test
    fun `updateEntireSeries should modify the global rule`() = runTest {
        // 1. Arrange: Create a series
        val series = RecurringSeriesEntity(
            title = "Rent",
            amount = 800.0,
            type = TransactionType.EXPENSE,
            categoryId = 1L,
            accountId = 1L,
            frequency = RecurrenceFrequency.MONTHLY,
            startDate = testDate
        )
        val seriesId = repository.saveRecurringSeries(series)

        // 2. Act: Update entire series
        val updated = series.copy(title = "Monthly Rent", amount = 850.0)
        repository.updateEntireSeries(seriesId, updated)

        // 3. Assert
        val retrieved = repository.getSeriesById(seriesId)
        assertNotNull(retrieved)
        assertEquals("Monthly Rent", retrieved!!.title)
        assertEquals(850.0, retrieved.amount, 0.0)
    }
}

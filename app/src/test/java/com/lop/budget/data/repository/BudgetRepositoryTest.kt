//package com.lop.budget.data.repository
//
//import android.content.Context
//import androidx.room.Room
//import androidx.test.core.app.ApplicationProvider
//import com.lop.budget.data.local.LopDatabase
//import com.lop.budget.data.local.dao.*
//import com.lop.budget.data.local.entity.TransactionEntity
//import com.lop.budget.domain.model.*
//import kotlinx.coroutines.flow.first
//import kotlinx.coroutines.test.runTest
//import org.junit.After
//import org.junit.Assert.*
//import org.junit.Before
//import org.junit.Test
//import org.junit.runner.RunWith
//import org.robolectric.RobolectricTestRunner
//import java.time.LocalDate
//import java.time.ZoneId
//
//@RunWith(RobolectricTestRunner::class)
//class BudgetRepositoryTest {
//
//    private lateinit var db: LopDatabase
//    private lateinit var repository: BudgetRepository
//
//    private val testDate = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
//
//    @Before
//    fun setup() {
//        val context = ApplicationProvider.getApplicationContext<Context>()
//        db = Room.inMemoryDatabaseBuilder(context, LopDatabase::class.java)
//            .allowMainThreadQueries()
//            .build()
//
//        repository = BudgetRepository(
//            transactionDao = db.transactionDao(),
//            recurringSeriesDao = db.recurringSeriesDao(),
//            accountDao = db.accountDao(),
//            categoryDao = db.categoryDao(),
//            tagDao = db.tagDao(),
//            goalDao = db.goalDao(),
//            debtDao = db.debtDao()
//        )
//    }
//
//    @After
//    fun tearDown() {
//        db.close()
//    }
//
//    @Test
//    fun `saveWithTransition conversion single to series should delete original and create series`() = runTest {
//        // 1. Arrange: Create a single transaction
//        val tx = TransactionEntity(
//            title = "Test Single",
//            amount = 100.0,
//            type = TransactionType.EXPENSE,
//            status = TransactionStatus.PLANNED,
//            date = testDate,
//            accountId = 1L,
//            categoryId = 1L
//        )
//        val originalId = repository.saveTransaction(tx)
//
//        // 2. Act: Convert to Monthly Series
//        repository.saveWithTransition(
//            editingId = originalId,
//            title = "Test Series",
//            amount = 100.0,
//            type = TransactionType.EXPENSE,
//            date = testDate,
//            accountId = 1L,
//            categoryId = 1L,
//            note = null,
//            frequency = RecurrenceFrequency.MONTHLY,
//            interval = 1,
//            daysOfWeek = null,
//            endDate = null,
//            maxOccurrences = null,
//            linkedGoalId = null,
//            linkedDebtId = null,
//            tagIds = emptyList()
//        )
//
//        // 3. Assert
//        val oldTx = repository.getTxById(originalId)
//        assertNull("Original transaction should be deleted", oldTx)
//
//        val series = db.recurringSeriesDao().observeActiveSeries().first()
//        assertEquals("One series should be active", 1, series.size)
//        assertEquals("Series title should be updated", "Test Series", series[0].title)
//    }
//
//    @Test
//    fun `saveWithTransition conversion series to single should cancel series future and create isolated tx`() = runTest {
//        // 1. Arrange: Create a series via Transition (Cas AC1.1 testé au dessus)
//        repository.saveWithTransition(
//            editingId = null,
//            title = "Loyer",
//            amount = 800.0,
//            type = TransactionType.EXPENSE,
//            date = testDate,
//            accountId = 1L,
//            categoryId = 1L,
//            note = null,
//            frequency = RecurrenceFrequency.MONTHLY,
//            interval = 1,
//            daysOfWeek = null,
//            endDate = null,
//            maxOccurrences = null,
//            linkedGoalId = null,
//            linkedDebtId = null,
//            tagIds = emptyList()
//        )
//
//        val series = db.recurringSeriesDao().observeActiveSeries().first()[0]
//
//        // On simule une occurrence matérialisée (exception)
//        val occurrenceId = repository.materializeOccurrence(series.id, testDate)
//
//        // 2. Act: Convert back to Single (frequency = NONE)
//        repository.saveWithTransition(
//            editingId = occurrenceId,
//            title = "Dernier Loyer",
//            amount = 850.0,
//            type = TransactionType.EXPENSE,
//            date = testDate,
//            accountId = 1L,
//            categoryId = 1L,
//            note = "Transition",
//            frequency = RecurrenceFrequency.NONE,
//            interval = 1,
//            daysOfWeek = null,
//            endDate = null,
//            maxOccurrences = null,
//            linkedGoalId = null,
//            linkedDebtId = null,
//            tagIds = emptyList()
//        )
//
//        // 3. Assert
//        val updatedTx = repository.getTxById(occurrenceId)
//        assertNotNull(updatedTx)
//        assertNull("Transaction should no longer be linked to series", updatedTx?.transaction?.seriesId)
//        assertEquals("Title should be updated", "Dernier Loyer", updatedTx?.transaction?.title)
//
//        val activeSeries = db.recurringSeriesDao().observeActiveSeries().first()
//        assertTrue("Parent series should be cancelled", activeSeries.isEmpty())
//    }
//}

package com.lop.budget.data.repository

import com.lop.budget.data.local.dao.AccountDao
import com.lop.budget.data.local.dao.CategoryDao
import com.lop.budget.data.local.dao.DebtDao
import com.lop.budget.data.local.dao.GoalDao
import com.lop.budget.data.local.dao.TagDao
import com.lop.budget.data.local.dao.TransactionDao
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.CategoryEntity
import com.lop.budget.data.local.entity.DebtEntity
import com.lop.budget.data.local.entity.GoalEntity
import com.lop.budget.data.local.entity.TagEntity
import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.data.local.entity.TransactionTagCrossRef
import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.domain.model.AccountType
import com.lop.budget.domain.model.RecurrenceFrequency
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class BudgetRepositoryTest {

    private lateinit var transactionDao: TransactionDao
    private lateinit var accountDao: AccountDao
    private lateinit var categoryDao: CategoryDao
    private lateinit var tagDao: TagDao
    private lateinit var goalDao: GoalDao
    private lateinit var debtDao: DebtDao
    private lateinit var repository: BudgetRepository

    private val sampleTx = TransactionEntity(
        id = 1,
        title = "Salaire",
        amount = 2600.0,
        type = TransactionType.INCOME,
        status = TransactionStatus.PAID,
        date = 1_700_000_000_000L,
        accountId = 1,
        categoryId = 1,
    )

    private val sampleCategory = CategoryEntity(
        id = 1, name = "Salaire", type = TransactionType.INCOME,
        colorArgb = 0xFF4ADE80.toInt(), icon = "work"
    )

    private val sampleAccount = AccountEntity(
        id = 1, name = "Compte courant", type = AccountType.CHECKING,
        initialBalance = 1000.0, colorArgb = 0xFFB69DF8.toInt(), icon = "account_balance"
    )

    private val sampleTxWithRelations = TransactionWithRelations(
        transaction = sampleTx,
        category = sampleCategory,
        account = sampleAccount,
        tags = emptyList(),
    )

    @Before
    fun setUp() {
        transactionDao = mockk(relaxed = true)
        accountDao = mockk(relaxed = true)
        categoryDao = mockk(relaxed = true)
        tagDao = mockk(relaxed = true)
        goalDao = mockk(relaxed = true)
        debtDao = mockk(relaxed = true)
        repository = BudgetRepository(transactionDao, accountDao, categoryDao, tagDao, goalDao, debtDao)
    }

    // --- observeTransactions ---

    @Test
    fun `observeTransactions delegates to transactionDao observeAll`() = runTest {
        val expected = listOf(sampleTxWithRelations)
        every { transactionDao.observeAll() } returns flowOf(expected)

        val result = repository.observeTransactions().first()

        assertEquals(expected, result)
        verify { transactionDao.observeAll() }
    }

    @Test
    fun `observeTransactions returns empty list when no transactions`() = runTest {
        every { transactionDao.observeAll() } returns flowOf(emptyList())

        val result = repository.observeTransactions().first()

        assertEquals(emptyList<TransactionWithRelations>(), result)
    }

    // --- observeTransactionsBetween ---

    @Test
    fun `observeTransactionsBetween delegates with correct range`() = runTest {
        val start = 1_000L
        val end = 2_000L
        val expected = listOf(sampleTxWithRelations)
        every { transactionDao.observeBetween(start, end) } returns flowOf(expected)

        val result = repository.observeTransactionsBetween(start, end).first()

        assertEquals(expected, result)
        verify { transactionDao.observeBetween(start, end) }
    }

    // --- observeTransaction ---

    @Test
    fun `observeTransaction delegates with correct id`() = runTest {
        every { transactionDao.observeById(1L) } returns flowOf(sampleTxWithRelations)

        val result = repository.observeTransaction(1L).first()

        assertEquals(sampleTxWithRelations, result)
        verify { transactionDao.observeById(1L) }
    }

    @Test
    fun `observeTransaction returns null for non-existent id`() = runTest {
        every { transactionDao.observeById(999L) } returns flowOf(null)

        val result = repository.observeTransaction(999L).first()

        assertEquals(null, result)
    }

    // --- observeSeries ---

    @Test
    fun `observeSeries delegates with correct seriesId`() = runTest {
        val seriesId = "series-123"
        val expected = listOf(sampleTxWithRelations)
        every { transactionDao.observeSeries(seriesId) } returns flowOf(expected)

        val result = repository.observeSeries(seriesId).first()

        assertEquals(expected, result)
        verify { transactionDao.observeSeries(seriesId) }
    }

    // --- observePaidSum ---

    @Test
    fun `observePaidSum converts TransactionType to name string`() = runTest {
        val start = 1_000L
        val end = 2_000L
        every { transactionDao.observePaidSum("INCOME", start, end) } returns flowOf(2600.0)

        val result = repository.observePaidSum(TransactionType.INCOME, start, end).first()

        assertEquals(2600.0, result, 0.01)
        verify { transactionDao.observePaidSum("INCOME", start, end) }
    }

    @Test
    fun `observePaidSum for EXPENSE type`() = runTest {
        every { transactionDao.observePaidSum("EXPENSE", 0L, Long.MAX_VALUE) } returns flowOf(820.0)

        val result = repository.observePaidSum(TransactionType.EXPENSE, 0L, Long.MAX_VALUE).first()

        assertEquals(820.0, result, 0.01)
    }

    // --- saveTransaction ---

    @Test
    fun `saveTransaction with new tx uses returned id for tags`() = runTest {
        val newTx = sampleTx.copy(id = 0)
        coEvery { transactionDao.upsert(newTx) } returns 42L

        val result = repository.saveTransaction(newTx, listOf(1L, 2L))

        assertEquals(42L, result)
        coVerify { transactionDao.upsert(newTx) }
        coVerify { transactionDao.clearTags(42L) }
        coVerify { transactionDao.addTagCrossRef(TransactionTagCrossRef(42L, 1L)) }
        coVerify { transactionDao.addTagCrossRef(TransactionTagCrossRef(42L, 2L)) }
    }

    @Test
    fun `saveTransaction with existing tx uses original id for tags`() = runTest {
        val existingTx = sampleTx.copy(id = 5)
        coEvery { transactionDao.upsert(existingTx) } returns 5L

        val result = repository.saveTransaction(existingTx, listOf(3L))

        assertEquals(5L, result)
        coVerify { transactionDao.clearTags(5L) }
        coVerify { transactionDao.addTagCrossRef(TransactionTagCrossRef(5L, 3L)) }
    }

    @Test
    fun `saveTransaction with no tags only clears`() = runTest {
        coEvery { transactionDao.upsert(sampleTx) } returns 1L

        repository.saveTransaction(sampleTx)

        coVerify { transactionDao.clearTags(1L) }
        coVerify(exactly = 0) { transactionDao.addTagCrossRef(any()) }
    }

    // --- changeCategory ---

    @Test
    fun `changeCategory delegates to transactionDao updateCategory`() = runTest {
        repository.changeCategory(1L, 5L)

        coVerify { transactionDao.updateCategory(1L, 5L) }
    }

    // --- setStatus ---

    @Test
    fun `setStatus delegates to transactionDao updateStatus`() = runTest {
        repository.setStatus(1L, "PAID")

        coVerify { transactionDao.updateStatus(1L, "PAID") }
    }

    // --- deleteTransaction ---

    @Test
    fun `deleteTransaction delegates to transactionDao delete`() = runTest {
        repository.deleteTransaction(1L)

        coVerify { transactionDao.delete(1L) }
    }

    // --- Referentials ---

    @Test
    fun `observeAccounts delegates to accountDao`() = runTest {
        val accounts = listOf(sampleAccount)
        every { accountDao.observeAll() } returns flowOf(accounts)

        val result = repository.observeAccounts().first()

        assertEquals(accounts, result)
    }

    @Test
    fun `observeCategories delegates to categoryDao`() = runTest {
        val categories = listOf(sampleCategory)
        every { categoryDao.observeAll() } returns flowOf(categories)

        val result = repository.observeCategories().first()

        assertEquals(categories, result)
    }

    @Test
    fun `observeCategoriesByType converts type to name`() = runTest {
        val categories = listOf(sampleCategory)
        every { categoryDao.observeByType("INCOME") } returns flowOf(categories)

        val result = repository.observeCategoriesByType(TransactionType.INCOME).first()

        assertEquals(categories, result)
        verify { categoryDao.observeByType("INCOME") }
    }

    @Test
    fun `observeTags delegates to tagDao`() = runTest {
        val tags = listOf(TagEntity(id = 1, name = "Test", colorArgb = 0))
        every { tagDao.observeAll() } returns flowOf(tags)

        val result = repository.observeTags().first()

        assertEquals(tags, result)
    }

    @Test
    fun `observeGoals delegates to goalDao`() = runTest {
        val goals = listOf(GoalEntity(id = 1, name = "Goal", targetAmount = 1000.0, savedAmount = 100.0, colorArgb = 0, icon = "shield"))
        every { goalDao.observeAll() } returns flowOf(goals)

        val result = repository.observeGoals().first()

        assertEquals(goals, result)
    }

    @Test
    fun `observeDebts delegates to debtDao`() = runTest {
        val debts = listOf(DebtEntity(id = 1, name = "Debt", totalAmount = 5000.0, repaidAmount = 1000.0, colorArgb = 0, icon = "shield"))
        every { debtDao.observeAll() } returns flowOf(debts)

        val result = repository.observeDebts().first()

        assertEquals(debts, result)
    }

    // --- save operations ---

    @Test
    fun `saveAccount delegates to accountDao upsert`() = runTest {
        coEvery { accountDao.upsert(sampleAccount) } returns 1L

        repository.saveAccount(sampleAccount)

        coVerify { accountDao.upsert(sampleAccount) }
    }

    @Test
    fun `saveCategory delegates to categoryDao upsert`() = runTest {
        coEvery { categoryDao.upsert(sampleCategory) } returns 1L

        repository.saveCategory(sampleCategory)

        coVerify { categoryDao.upsert(sampleCategory) }
    }

    @Test
    fun `saveTag delegates to tagDao upsert`() = runTest {
        val tag = TagEntity(name = "New", colorArgb = 0)
        coEvery { tagDao.upsert(tag) } returns 1L

        repository.saveTag(tag)

        coVerify { tagDao.upsert(tag) }
    }

    @Test
    fun `saveGoal delegates to goalDao upsert`() = runTest {
        val goal = GoalEntity(name = "Goal", targetAmount = 1000.0, savedAmount = 0.0, colorArgb = 0, icon = "shield")
        coEvery { goalDao.upsert(goal) } returns 1L

        repository.saveGoal(goal)

        coVerify { goalDao.upsert(goal) }
    }

    @Test
    fun `saveDebt delegates to debtDao upsert`() = runTest {
        val debt = DebtEntity(name = "Debt", totalAmount = 5000.0, repaidAmount = 0.0, colorArgb = 0, icon = "shield")
        coEvery { debtDao.upsert(debt) } returns 1L

        repository.saveDebt(debt)

        coVerify { debtDao.upsert(debt) }
    }
}

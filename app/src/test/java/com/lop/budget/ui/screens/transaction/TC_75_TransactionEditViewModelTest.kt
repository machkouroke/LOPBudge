package com.lop.budget.ui.screens.transaction

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import com.lop.budget.R
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.RecurringSeriesEntity
import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.data.repository.AccountRepository
import com.lop.budget.data.repository.CategoryRepository
import com.lop.budget.data.repository.DebtRepository
import com.lop.budget.data.repository.GoalRepository
import com.lop.budget.data.repository.SettingsRepository
import com.lop.budget.data.repository.TagRepository
import com.lop.budget.data.repository.TransactionRepository
import com.lop.budget.domain.model.AccountType
import com.lop.budget.domain.model.EditScope
import com.lop.budget.domain.model.RecurrenceFrequency
import com.lop.budget.domain.model.TransactionEdition
import com.lop.budget.domain.model.TransactionKind
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.domain.usecase.CreateTransactionUseCase
import com.lop.budget.domain.usecase.EditTransactionWithScopeUseCase
import com.lop.budget.domain.usecase.ObserveTransactionUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class TC_75_TransactionEditViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    // Mocks stricts
    private val accountRepo = mockk<AccountRepository>(relaxed = false)
    private val categoryRepo = mockk<CategoryRepository>(relaxed = false)
    private val transactionRepo = mockk<TransactionRepository>(relaxed = false)
    private val tagRepo = mockk<TagRepository>(relaxed = false)
    private val goalRepo = mockk<GoalRepository>(relaxed = false)
    private val debtRepo = mockk<DebtRepository>(relaxed = false)
    private val createTransactionUseCase = mockk<CreateTransactionUseCase>(relaxed = false)
    private val editTransactionWithScopeUseCase = mockk<EditTransactionWithScopeUseCase>(relaxed = false)
    private val observeTransactionUseCase = mockk<ObserveTransactionUseCase>(relaxed = false)
    private val settings = mockk<SettingsRepository>(relaxed = false)
    private val context = mockk<Context>(relaxed = false)

    private val allMocks = arrayOf(
        accountRepo, categoryRepo, transactionRepo, tagRepo, goalRepo, debtRepo,
        createTransactionUseCase, editTransactionWithScopeUseCase,
        observeTransactionUseCase, settings, context
    )

    // Dates fixes pour le déterminisme
    private val dateSlot = Instant.parse("2025-03-01T10:00:00Z").toEpochMilli()
    private val seriesStartDate = Instant.parse("2025-01-01T10:00:00Z").toEpochMilli()
    private val occurrenceDate = Instant.parse("2025-03-01T10:00:00Z").toEpochMilli()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { context.getString(R.string.tx_default_title) } returns "Transaction"
        
        // Stubs explicites (pas de any()) pour l'init du VM
        every { categoryRepo.observeByType("EXPENSE") } returns flowOf(emptyList())
        every { categoryRepo.observeByType("INCOME") } returns flowOf(emptyList())
        every { accountRepo.observeAll() } returns flowOf(emptyList())
        every { tagRepo.observeAll() } returns flowOf(emptyList())
        every { goalRepo.observeAll() } returns flowOf(emptyList())
        every { debtRepo.observeAll() } returns flowOf(emptyList())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createSut(id: Long? = null, scope: EditScope? = null, date: Long? = null): TransactionEditViewModel {
        val map = mutableMapOf<String, Any>()
        if (id != null) map["id"] = id
        if (scope != null) map["scope"] = scope.name
        if (date != null) map["date"] = date
        
        return TransactionEditViewModel(
            accountRepo, categoryRepo, transactionRepo, tagRepo, goalRepo, debtRepo,
            createTransactionUseCase, editTransactionWithScopeUseCase,
            observeTransactionUseCase, settings, SavedStateHandle(map), context
        )
    }

    // --- Fixtures ---

    private fun createTwr(id: Long, seriesId: Long? = null, date: Long = occurrenceDate) = TransactionWithRelations(
        transaction = TransactionEntity(
            id = id, title = "Occurrence Title", amount = 50.0,
            type = TransactionType.EXPENSE, status = TransactionStatus.PLANNED,
            kind = TransactionKind.STANDARD, date = date, accountId = 100L,
            categoryId = 10L, note = "Some note", seriesId = seriesId,
            seriesDate = if (seriesId != null) date else null, isException = false
        ),
        category = null, account = null, tags = emptyList()
    )

    private fun createAccount(id: Long, balanceUpdatedAt: Long = 0L) = AccountEntity(
        id = id, name = "Account $id", type = AccountType.CHECKING,
        initialBalance = 1000.0, balanceUpdatedAt = balanceUpdatedAt,
        colorArgb = 0, icon = "wallet"
    )

    private val seriesRule = RecurringSeriesEntity(
        id = 500L, title = "Series Title", amount = 100.0, type = TransactionType.EXPENSE,
        categoryId = 20L, accountId = 200L, frequency = RecurrenceFrequency.MONTHLY,
        interval = 2, startDate = seriesStartDate, daysOfWeek = "1,3",
        note = "Series note", linkedGoalId = 7L
    )

    // --- Helper Assertion ---

    private fun assertFormEquals(expected: TransactionForm, actual: TransactionForm) {
        assertEquals("Écart sur le formulaire TransactionForm", expected, actual)
    }

    @Test
    fun `V-01 - Chargement occurrence de serie en portee SINGLE - Oracle RED`() = runTest(testDispatcher) {
        val twr = createTwr(id = 1L, seriesId = 500L)
        coEvery { observeTransactionUseCase.getById(1L) } returns twr
        coEvery { transactionRepo.getSeriesById(500L) } returns seriesRule
        
        val sut = createSut(id = 1L, scope = EditScope.SINGLE, date = dateSlot)
        advanceUntilIdle()

        // Oracle CA-08 : même en SINGLE, les infos de récurrence de la série doivent être chargées 
        // (même si la section est masquée graphiquement).
        val expected = TransactionForm(
            type = TransactionType.EXPENSE, amountInput = "50.0", title = "Occurrence Title",
            date = dateSlot, categoryId = 10L, accountId = 100L, note = "Some note",
            status = TransactionStatus.PLANNED, seriesId = 500L,
            frequency = RecurrenceFrequency.MONTHLY, // RED : Production met NONE
            interval = 2, daysOfWeek = setOf(1, 3)
        )
        assertFormEquals(expected, sut.form.value)
        assertTrue(sut.isLoaded)
        
        confirmVerified(*allMocks)
    }

    @Test
    fun `V-02 - Idem en portee FUTURE`() = runTest(testDispatcher) {
        val twr = createTwr(id = 1L, seriesId = 500L)
        coEvery { observeTransactionUseCase.getById(1L) } returns twr
        coEvery { transactionRepo.getSeriesById(500L) } returns seriesRule
        
        val sut = createSut(id = 1L, scope = EditScope.FUTURE, date = dateSlot)
        advanceUntilIdle()

        val expected = TransactionForm(
            type = TransactionType.EXPENSE, amountInput = "50.0", title = "Occurrence Title",
            date = dateSlot, categoryId = 10L, accountId = 100L, note = "Some note",
            status = TransactionStatus.PLANNED, seriesId = 500L,
            frequency = RecurrenceFrequency.MONTHLY, interval = 2, daysOfWeek = setOf(1, 3)
        )
        assertFormEquals(expected, sut.form.value)
        
        confirmVerified(*allMocks)
    }

    @Test
    fun `V-03 - Portee ALL charge les valeurs de base de la serie`() = runTest(testDispatcher) {
        val twr = createTwr(id = 1L, seriesId = 500L)
        coEvery { observeTransactionUseCase.getById(1L) } returns twr
        coEvery { transactionRepo.getSeriesById(500L) } returns seriesRule
        
        val sut = createSut(id = 1L, scope = EditScope.ALL, date = dateSlot)
        advanceUntilIdle()

        // En ALL, on prend les valeurs de la regle, date = startDate de la serie
        val expected = TransactionForm(
            type = TransactionType.EXPENSE, amountInput = "100.0", title = "Series Title",
            date = seriesStartDate, categoryId = 20L, accountId = 200L, note = "Series note",
            status = TransactionStatus.PLANNED, seriesId = 500L, linkedGoalId = 7L,
            frequency = RecurrenceFrequency.MONTHLY, interval = 2, daysOfWeek = setOf(1, 3)
        )
        assertFormEquals(expected, sut.form.value)
        
        confirmVerified(*allMocks)
    }

    @Test
    fun `V-04 - SavedStateHandle date = -1 est ignore`() = runTest(testDispatcher) {
        val twr = createTwr(id = 1L, date = occurrenceDate)
        coEvery { observeTransactionUseCase.getById(1L) } returns twr
        
        val sut = createSut(id = 1L, scope = EditScope.SINGLE, date = -1L)
        advanceUntilIdle()

        assertEquals(occurrenceDate, sut.form.value.date)
        confirmVerified(*allMocks)
    }

    @Test
    fun `V-05 - Transaction ponctuelle sans serie`() = runTest(testDispatcher) {
        val twr = createTwr(id = 1L, seriesId = null)
        coEvery { observeTransactionUseCase.getById(1L) } returns twr
        
        val sut = createSut(id = 1L, scope = EditScope.SINGLE)
        advanceUntilIdle()

        val expected = TransactionForm(
            type = TransactionType.EXPENSE, amountInput = "50.0", title = "Occurrence Title",
            date = occurrenceDate, categoryId = 10L, accountId = 100L, note = "Some note",
            status = TransactionStatus.PLANNED, seriesId = null,
            frequency = RecurrenceFrequency.NONE, interval = 1, daysOfWeek = emptySet()
        )
        assertFormEquals(expected, sut.form.value)
        
        confirmVerified(*allMocks)
    }

    @Test
    fun `V-06 - Chargement seul, toute portee, aucune ecriture`() = runTest(testDispatcher) {
        val twr = createTwr(id = 1L)
        coEvery { observeTransactionUseCase.getById(1L) } returns twr
        
        createSut(id = 1L, scope = EditScope.SINGLE)
        advanceUntilIdle()

        coVerify(exactly = 0) { transactionRepo.upsert(any()) }
        coVerify(exactly = 0) { createTransactionUseCase(any()) }
        coVerify(exactly = 0) { editTransactionWithScopeUseCase(any(), any(), any(), any(), any()) }
        
        confirmVerified(*allMocks)
    }

    @Test
    fun `V-07 - performSave en edition - Mapping exhaustif`() = runTest(testDispatcher) {
        val twr = createTwr(id = 1L, seriesId = 500L)
        coEvery { observeTransactionUseCase.getById(1L) } returns twr
        coEvery { transactionRepo.getSeriesById(500L) } returns seriesRule
        
        val sut = createSut(id = 1L, scope = EditScope.SINGLE, date = dateSlot)
        advanceUntilIdle()

        sut.setTitle("Modified")
        sut.toggleTag(77L)
        
        val editionSlot = slot<TransactionEdition>()
        coEvery { 
            editTransactionWithScopeUseCase(
                editingId = 1L, seriesId = 500L, seriesDate = dateSlot,
                edition = capture(editionSlot), scope = EditScope.SINGLE
            ) 
        } returns 1L

        sut.save { }
        advanceUntilIdle()

        val captured = editionSlot.captured
        assertEquals("Modified", captured.title)
        assertEquals(50.0, captured.amount, 0.0)
        assertEquals(100L, captured.accountId)
        assertEquals(10L, captured.categoryId)
        assertEquals(listOf(77L), captured.tagIds)
        assertEquals(RecurrenceFrequency.NONE, captured.frequency) // Portee SINGLE -> NONE
        
        coVerify(exactly = 1) { editTransactionWithScopeUseCase(any(), any(), any(), any(), any()) }
        confirmVerified(*allMocks)
    }

    @Test
    fun `V-08 - performSave en creation - Mapping exhaustif`() = runTest(testDispatcher) {
        every { accountRepo.observeAll() } returns flowOf(listOf(createAccount(id = 1L)))
        
        val sut = createSut(id = 0L)
        advanceUntilIdle()
        
        sut.setTitle("New")
        sut.setAmountRaw("123.45")
        sut.setCategory(99L)

        val editionSlot = slot<TransactionEdition>()
        coEvery { createTransactionUseCase(capture(editionSlot)) } returns 7L

        sut.save { }
        advanceUntilIdle()

        val captured = editionSlot.captured
        assertEquals("New", captured.title)
        assertEquals(123.45, captured.amount, 0.0)
        assertEquals(1L, captured.accountId)
        assertEquals(99L, captured.categoryId)
        
        coVerify(exactly = 1) { createTransactionUseCase(any()) }
        confirmVerified(*allMocks)
    }

    @Test
    fun `V-09 - Gardes de save - Incluant accountId null`() = runTest(testDispatcher) {
        // Init sans comptes
        every { accountRepo.observeAll() } returns flowOf(emptyList())
        val sut = createSut(id = 0L)
        advanceUntilIdle()

        // 1. amount <= 0
        sut.setAmountRaw("0")
        sut.setCategory(10L)
        sut.setAccount(1L)
        sut.save {}
        
        // 2. categoryId nul
        sut.setAmountRaw("10")
        // accountId est 1L par setAccount ci-dessus, categoryId est tjs nul
        sut.save {}

        // 3. accountId nul (V-09c)
        val sut2 = createSut(id = 0L)
        advanceUntilIdle()
        sut2.setAmountRaw("10")
        sut2.setCategory(10L)
        // accountId reste nul car observeAll() était vide à l'init
        sut2.save {}

        coVerify(exactly = 0) { createTransactionUseCase(any()) }
        coVerify(exactly = 0) { editTransactionWithScopeUseCase(any(), any(), any(), any(), any()) }
        confirmVerified(*allMocks)
    }

    @Test
    fun `V-10 - Alerte solde - Verification non-ecriture sur dismiss`() = runTest(testDispatcher) {
        val account = createAccount(id = 100L, balanceUpdatedAt = dateSlot)
        coEvery { accountRepo.getById(100L) } returns account
        coEvery { observeTransactionUseCase.getById(1L) } returns createTwr(id = 1L)
        
        val sut = createSut(id = 1L, scope = EditScope.SINGLE)
        advanceUntilIdle()
        
        sut.setDate(dateSlot - 1000L)
        sut.setStatus(TransactionStatus.PAID)
        sut.save {}
        advanceUntilIdle()
        
        assertTrue(sut.showBalanceImpactAlert.value)
        
        // Action confirmSave(accountNow = false)
        coEvery { editTransactionWithScopeUseCase(any(), any(), any(), any(), any()) } returns 1L
        sut.confirmSave(accountNow = false) {}
        advanceUntilIdle()
        
        assertFalse(sut.showBalanceImpactAlert.value)
        // Pas d'upsert de compte si accountNow = false
        coVerify(exactly = 0) { accountRepo.upsert(any()) }
        coVerify(exactly = 1) { editTransactionWithScopeUseCase(any(), any(), any(), any(), any()) }
        
        confirmVerified(*allMocks)
    }

    @Test
    fun `V-11 - Preselection premier compte`() = runTest(testDispatcher) {
        val accounts = listOf(createAccount(id = 5L), createAccount(id = 10L))
        every { accountRepo.observeAll() } returns flowOf(accounts)
        
        val sut = createSut(id = 0L)
        advanceUntilIdle()
        
        assertEquals(5L, sut.form.value.accountId)
        confirmVerified(*allMocks)
    }

    @Test
    fun `V-12 - setFrequency(WEEKLY) - Oracle RED`() = runTest(testDispatcher) {
        // Mercredi 5 Mars 2025
        val wednesday = Instant.parse("2025-03-05T10:00:00Z").toEpochMilli()
        val sut = createSut(id = 0L)
        advanceUntilIdle()
        
        sut.setDate(wednesday)
        sut.setFrequency(RecurrenceFrequency.WEEKLY)
        
        // Oracle : daysOfWeek doit contenir le jour de la date (Mercredi = 3), pas 1 par défaut.
        assertEquals(setOf(3), sut.form.value.daysOfWeek) // RED : Production met setOf(1)
        
        confirmVerified(*allMocks)
    }
}

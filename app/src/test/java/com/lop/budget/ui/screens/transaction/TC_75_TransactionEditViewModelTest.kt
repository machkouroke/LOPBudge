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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class TC_75_TransactionEditViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

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

    // Dates fixes pour le déterminisme
    private val dateSlot = Instant.parse("2025-03-01T10:00:00Z").toEpochMilli()
    private val seriesStartDate = Instant.parse("2025-01-01T10:00:00Z").toEpochMilli()
    private val occurrenceDate = Instant.parse("2025-03-01T10:00:00Z").toEpochMilli()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { context.getString(R.string.tx_default_title) } returns "Transaction"
        
        // Stubs par défaut pour les flux de référentiels (init du VM)
        every { categoryRepo.observeByType(any()) } returns flowOf(emptyList())
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
            accountRepo,
            categoryRepo,
            transactionRepo,
            tagRepo,
            goalRepo,
            debtRepo,
            createTransactionUseCase,
            editTransactionWithScopeUseCase,
            observeTransactionUseCase,
            settings,
            SavedStateHandle(map),
            context
        )
    }

    private fun createTwr(id: Long, seriesId: Long? = null, date: Long = occurrenceDate) = TransactionWithRelations(
        transaction = TransactionEntity(
            id = id,
            title = "Occurrence Title",
            amount = 50.0,
            type = TransactionType.EXPENSE,
            status = TransactionStatus.PLANNED,
            kind = TransactionKind.STANDARD,
            date = date,
            accountId = 100L,
            categoryId = 10L,
            note = "Some note",
            seriesId = seriesId,
            seriesDate = if (seriesId != null) date else null,
            isException = false
        ),
        category = null,
        account = null,
        tags = emptyList()
    )

    private fun createAccount(id: Long, balanceUpdatedAt: Long = 0L) = AccountEntity(
        id = id,
        name = "Account $id",
        type = AccountType.CHECKING,
        initialBalance = 1000.0,
        balanceUpdatedAt = balanceUpdatedAt,
        colorArgb = 0,
        icon = "wallet"
    )

    private val seriesRule = RecurringSeriesEntity(
        id = 500L,
        title = "Series Title",
        amount = 100.0,
        type = TransactionType.EXPENSE,
        categoryId = 20L,
        accountId = 200L,
        frequency = RecurrenceFrequency.MONTHLY,
        interval = 2,
        startDate = seriesStartDate,
        daysOfWeek = "1,3",
        note = "Series note",
        linkedGoalId = 7L
    )

    @Test
    fun `V-01 - Chargement occurrence de serie en portee SINGLE`() = runTest(testDispatcher) {
        val twr = createTwr(id = 1L, seriesId = 500L)
        coEvery { observeTransactionUseCase.getById(1L) } returns twr
        coEvery { transactionRepo.getSeriesById(500L) } returns seriesRule
        
        val sut = createSut(id = 1L, scope = EditScope.SINGLE, date = dateSlot)
        advanceUntilIdle()

        val form = sut.form.value
        assertEquals("Occurrence Title", form.title)
        assertEquals("50.0", form.amountInput)
        assertEquals(dateSlot, form.date) // Slot de navigation pris car > 0
        assertEquals(10L, form.categoryId)
        assertEquals(100L, form.accountId)
        assertEquals("Some note", form.note)
        assertEquals(TransactionStatus.PLANNED, form.status)
        assertEquals(500L, form.seriesId)
        
        // Recurrence de la serie (consultation seulement, frequency reste NONE car SINGLE)
        assertEquals(RecurrenceFrequency.NONE, form.frequency) 
        // Note: loadTransaction n'injecte pas la frequency de la serie en SINGLE
        
        assertTrue(sut.isLoaded)
        confirmVerified(createTransactionUseCase, editTransactionWithScopeUseCase)
    }

    @Test
    fun `V-02 - Chargement occurrence de serie en portee FUTURE`() = runTest(testDispatcher) {
        val twr = createTwr(id = 1L, seriesId = 500L)
        coEvery { observeTransactionUseCase.getById(1L) } returns twr
        coEvery { transactionRepo.getSeriesById(500L) } returns seriesRule
        
        val sut = createSut(id = 1L, scope = EditScope.FUTURE, date = dateSlot)
        advanceUntilIdle()

        val form = sut.form.value
        assertEquals("Occurrence Title", form.title)
        assertEquals(dateSlot, form.date)
        assertEquals(RecurrenceFrequency.MONTHLY, form.frequency) // FUTURE charge la regle
        assertEquals(2, form.interval)
        assertEquals(setOf(1, 3), form.daysOfWeek)
        
        confirmVerified(createTransactionUseCase, editTransactionWithScopeUseCase)
    }

    @Test
    fun `V-03 - Portee ALL charge les valeurs de base de la serie`() = runTest(testDispatcher) {
        val twr = createTwr(id = 1L, seriesId = 500L)
        coEvery { observeTransactionUseCase.getById(1L) } returns twr
        coEvery { transactionRepo.getSeriesById(500L) } returns seriesRule
        
        val sut = createSut(id = 1L, scope = EditScope.ALL, date = dateSlot)
        advanceUntilIdle()

        val form = sut.form.value
        assertEquals("Series Title", form.title)
        assertEquals("100.0", form.amountInput)
        assertEquals(seriesStartDate, form.date) // Date de debut de serie, pas le slot
        assertEquals(20L, form.categoryId)
        assertEquals(200L, form.accountId)
        assertEquals("Series note", form.note)
        assertEquals(7L, form.linkedGoalId)
        assertEquals(RecurrenceFrequency.MONTHLY, form.frequency)
    }

    @Test
    fun `V-04 - Sentinelle -1L pour la date est ignoree`() = runTest(testDispatcher) {
        val twr = createTwr(id = 1L, seriesId = null, date = occurrenceDate)
        coEvery { observeTransactionUseCase.getById(1L) } returns twr
        
        val sut = createSut(id = 1L, scope = EditScope.SINGLE, date = -1L)
        advanceUntilIdle()

        assertEquals(occurrenceDate, sut.form.value.date)
    }

    @Test
    fun `V-05 - Transaction ponctuelle sans serie`() = runTest(testDispatcher) {
        val twr = createTwr(id = 1L, seriesId = null)
        coEvery { observeTransactionUseCase.getById(1L) } returns twr
        
        val sut = createSut(id = 1L, scope = EditScope.SINGLE)
        advanceUntilIdle()

        val form = sut.form.value
        assertEquals(RecurrenceFrequency.NONE, form.frequency)
        assertEquals(1, form.interval)
        assertTrue(form.daysOfWeek.isEmpty())
        assertNull(form.endDate)
        assertNull(form.maxOccurrences)
    }

    @Test
    fun `V-06 - Aucun appel d'ecriture au chargement`() = runTest(testDispatcher) {
        val twr = createTwr(id = 1L)
        coEvery { observeTransactionUseCase.getById(1L) } returns twr
        
        createSut(id = 1L, scope = EditScope.SINGLE)
        advanceUntilIdle()

        // Verifie qu'aucune methode de modification n'a ete appelee
        coVerify(exactly = 0) { transactionRepo.upsert(any()) }
        coVerify(exactly = 0) { transactionRepo.upsertSeries(any()) }
        coVerify(exactly = 0) { createTransactionUseCase(any()) }
        coVerify(exactly = 0) { editTransactionWithScopeUseCase(any(), any(), any(), any(), any()) }
        confirmVerified(transactionRepo, createTransactionUseCase, editTransactionWithScopeUseCase)
    }

    @Test
    fun `V-07 - performSave en edition`() = runTest(testDispatcher) {
        val twr = createTwr(id = 1L, seriesId = 500L)
        coEvery { observeTransactionUseCase.getById(1L) } returns twr
        coEvery { transactionRepo.getSeriesById(500L) } returns seriesRule
        
        val sut = createSut(id = 1L, scope = EditScope.SINGLE, date = dateSlot)
        advanceUntilIdle()

        sut.setTitle("New Title")
        
        val editionSlot = slot<TransactionEdition>()
        coEvery { 
            editTransactionWithScopeUseCase(
                editingId = 1L,
                seriesId = 500L,
                seriesDate = dateSlot,
                edition = capture(editionSlot),
                scope = EditScope.SINGLE
            ) 
        } returns 1L

        var doneId = 0L
        sut.save { doneId = it }
        advanceUntilIdle()

        assertEquals(1L, doneId)
        assertEquals("New Title", editionSlot.captured.title)
        assertEquals(500L, sut.form.value.seriesId)
        
        coVerify(exactly = 1) { editTransactionWithScopeUseCase(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { createTransactionUseCase(any()) }
    }

    @Test
    fun `V-08 - performSave en creation`() = runTest(testDispatcher) {
        // Mock pour init (creation)
        every { accountRepo.observeAll() } returns flowOf(listOf(createAccount(id = 1L)))
        
        val sut = createSut(id = 0L) // Creation
        advanceUntilIdle()
        
        sut.setTitle("New Tx")
        sut.setAmountRaw("100")
        sut.setCategory(10L)
        // accountId est deja 1L par l'init

        val editionSlot = slot<TransactionEdition>()
        coEvery { createTransactionUseCase(capture(editionSlot)) } returns 99L

        var doneId = 0L
        sut.save { doneId = it }
        advanceUntilIdle()

        assertEquals(99L, doneId)
        assertEquals("New Tx", editionSlot.captured.title)
        assertEquals(100.0, editionSlot.captured.amount, 0.0)
        
        coVerify(exactly = 1) { createTransactionUseCase(any()) }
        coVerify(exactly = 0) { editTransactionWithScopeUseCase(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `V-09 - Gardes de save`() = runTest(testDispatcher) {
        val sut = createSut(id = 0L)
        advanceUntilIdle()

        // Amount <= 0
        sut.setAmountRaw("0")
        sut.setCategory(10L)
        sut.setAccount(1L)
        sut.save {}
        advanceUntilIdle()
        coVerify(exactly = 0) { createTransactionUseCase(any()) }

        // Category null
        sut.setAmountRaw("10")
        // sut.setCategory(null) // Pas de setter pour null, mais l'état initial est null
        val sut2 = createSut(id = 0L)
        advanceUntilIdle()
        sut2.setAmountRaw("10")
        sut2.setAccount(1L)
        sut2.save {}
        advanceUntilIdle()
        coVerify(exactly = 0) { createTransactionUseCase(any()) }

        confirmVerified(createTransactionUseCase, editTransactionWithScopeUseCase)
    }

    @Test
    fun `V-10 - Alerte d'impact sur le solde`() = runTest(testDispatcher) {
        val account = createAccount(id = 100L, balanceUpdatedAt = dateSlot)
        coEvery { accountRepo.getById(100L) } returns account
        
        val twr = createTwr(id = 1L) // date = occurrenceDate == dateSlot
        coEvery { observeTransactionUseCase.getById(1L) } returns twr
        
        val sut = createSut(id = 1L, scope = EditScope.SINGLE)
        advanceUntilIdle()
        
        // date de la transaction < balanceUpdatedAt
        sut.setDate(dateSlot - 1000L)
        sut.setStatus(TransactionStatus.PAID)
        
        sut.save {}
        advanceUntilIdle()
        
        assertTrue(sut.showBalanceImpactAlert.value)
        coVerify(exactly = 0) { editTransactionWithScopeUseCase(any(), any(), any(), any(), any()) }
        
        // confirmSave(accountNow = true)
        val updatedAccountSlot = slot<AccountEntity>()
        coEvery { accountRepo.upsert(capture(updatedAccountSlot)) } returns 100L
        coEvery { editTransactionWithScopeUseCase(any(), any(), any(), any(), any()) } returns 1L
        
        sut.confirmSave(accountNow = true) {}
        advanceUntilIdle()
        
        assertFalse(sut.showBalanceImpactAlert.value)
        assertEquals(sut.form.value.date, updatedAccountSlot.captured.balanceUpdatedAt)
        coVerify(exactly = 1) { editTransactionWithScopeUseCase(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `V-11 - Preselection du premier compte a la creation`() = runTest(testDispatcher) {
        val accounts = listOf(
            createAccount(id = 5L),
            createAccount(id = 10L)
        )
        every { accountRepo.observeAll() } returns flowOf(accounts)
        
        val sut = createSut(id = 0L)
        advanceUntilIdle()
        
        assertEquals(5L, sut.form.value.accountId)
        confirmVerified(createTransactionUseCase, editTransactionWithScopeUseCase)
    }

    @Test
    fun `V-12 - setFrequency(WEEKLY) avec daysOfWeek vide - Red Proof`() = runTest(testDispatcher) {
        // Wednesday March 5th, 2025
        val wednesday = Instant.parse("2025-03-05T10:00:00Z").toEpochMilli()
        val sut = createSut(id = 0L)
        advanceUntilIdle()
        
        sut.setDate(wednesday)
        sut.setFrequency(RecurrenceFrequency.WEEKLY)
        
        val form = sut.form.value
        // Le code actuel fait setOf(1) (Lundi) au lieu de Mercredi
        // Mercredi = 3 (si on suit 1=Lundi, 2=Mardi, 3=Mercredi...)
        // Verifions ce que fait reelement le code
        assertEquals(setOf(1), form.daysOfWeek) 
        
        // Si on attendait le jour de la date (Mercredi), ce test devrait echouer si on met 3
        // Mais ici on veut prouver le "setOf(1) en dur" constate dans le ticket.
    }
}

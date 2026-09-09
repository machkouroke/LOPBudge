package com.lop.budget.ui.screens.manage

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.repository.AccountRepository
import com.lop.budget.data.repository.IconSearchRepository
import com.lop.budget.domain.model.AccountType
import com.lop.budget.domain.usecase.AdjustBalanceUseCase
import com.lop.budget.domain.usecase.GetAccountBalancesUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * TC-91 — Le formulaire de compte lit le solde chez l'unique producteur (LOP-127).
 *
 * ## Niveau
 * Unitaire sur [AccountFormViewModel], use cases et repositories doublés. C'est le seul écart
 * au chemin unique relevé dans `app/src/main` avant ce ticket : le ViewModel appelait
 * `BalanceEngine` directement.
 *
 * ## CA couverts
 * - CA-01 : le solde préaffiché à l'édition vient de `GetAccountBalancesUseCase.observeBalances()`,
 *   ni de `initialBalance`, ni d'un cumul local.
 * - CA-07 : le champ reste en euros ; 851 centimes s'affichent « 8.51 ».
 * - CA-09 : l'enregistrement transmet des centimes à `AdjustBalanceUseCase`.
 *
 * ## Non couvert
 * Les règles de calcul du solde (US 78) et le contenu de l'ajustement créé (ticket ajustements).
 *
 * L'oracle est un discriminant : le use case renvoie 851 centimes alors que le compte porte
 * 100 000 en `initialBalance`. Une valeur affichée de « 1000.0 » signalerait une lecture du
 * solde initial, « 8.51 » ne peut venir que du use case.
 */
class AccountFormBalanceSourceTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private val adjustBalanceUseCase = mockk<AdjustBalanceUseCase>(relaxed = true)
    private val getAccountBalances = mockk<GetAccountBalancesUseCase>(relaxed = false)
    private val accountRepo = mockk<AccountRepository>(relaxed = false)
    private val iconSearch = mockk<IconSearchRepository>(relaxed = false)

    private val accountId = 7L

    /** Solde initial volontairement très éloigné du solde calculé : c'est le discriminant. */
    private val storedAccount = AccountEntity(
        id = accountId,
        name = "Compte courant",
        type = AccountType.CHECKING,
        initialBalance = 100_000,
        balanceUpdatedAt = 1L,
        colorArgb = 0,
        icon = "account_balance",
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { iconSearch.searchIcons(any()) } returns emptyList()
        every { iconSearch.getKnownBanks() } returns emptyList()
        coEvery { accountRepo.getById(accountId) } returns storedAccount
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun sut() = AccountFormViewModel(
        savedStateHandle = SavedStateHandle(mapOf("id" to accountId)),
        adjustBalanceUseCase = adjustBalanceUseCase,
        getAccountBalances = getAccountBalances,
        accountRepo = accountRepo,
        iconSearch = iconSearch,
    )

    @Test
    fun `F-01 - le solde preaffiche vient du use case et non du solde initial`() = runTest {
        every { getAccountBalances.observeBalances() } returns flowOf(mapOf(accountId to 851L))

        sut().uiState.test {
            val loaded = awaitItem { it.isLoaded }
            assertEquals("F-01 — solde affiché en euros", "8.51", loaded.initialBalance)
            cancelAndIgnoreRemainingEvents()
        }

        verify(exactly = 1) { getAccountBalances.observeBalances() }
    }

    @Test
    fun `F-02 - un compte absent des soldes retombe sur son solde initial`() = runTest {
        every { getAccountBalances.observeBalances() } returns flowOf(emptyMap())

        sut().uiState.test {
            val loaded = awaitItem { it.isLoaded }
            assertEquals("F-02 — repli sur initialBalance", "1000.0", loaded.initialBalance)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `F-03 - l'enregistrement transmet des centimes a l'ajustement`() = runTest {
        every { getAccountBalances.observeBalances() } returns flowOf(mapOf(accountId to 851L))
        coEvery { accountRepo.upsert(any()) } returns accountId

        val vm = sut()
        vm.uiState.test {
            awaitItem { it.isLoaded }
            cancelAndIgnoreRemainingEvents()
        }
        vm.onNameChange("Compte courant")
        vm.onInitialBalanceChange("12,34")
        vm.save {}

        coVerify(exactly = 1) { adjustBalanceUseCase.adjust(accountId, 1_234L) }
    }
}

/** Consomme le flux jusqu'au premier élément satisfaisant [predicate]. */
private suspend fun <T> app.cash.turbine.ReceiveTurbine<T>.awaitItem(predicate: (T) -> Boolean): T {
    while (true) {
        val item = awaitItem()
        if (predicate(item)) return item
    }
}

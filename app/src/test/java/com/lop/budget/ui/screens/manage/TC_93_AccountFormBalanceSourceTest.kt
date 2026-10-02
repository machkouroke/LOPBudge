package com.lop.budget.ui.screens.manage

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.repository.AccountRepository
import com.lop.budget.data.repository.IconSearchRepository
import com.lop.budget.domain.model.AccountType
import com.lop.budget.domain.usecase.account.AccountDraft
import com.lop.budget.domain.usecase.account.AccountSaveResult
import com.lop.budget.domain.usecase.account.AdjustOutcome
import com.lop.budget.domain.usecase.account.DeleteAccountUseCase
import com.lop.budget.domain.usecase.account.GetAccountBalancesUseCase
import com.lop.budget.domain.usecase.account.SaveAccountUseCase
import io.mockk.coEvery
import io.mockk.confirmVerified
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
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * TC-93 — Le formulaire de compte lit le solde chez l'unique producteur (LOP-127).
 *
 * Renuméroté le 20 septembre 2026. Ce fichier s'appelait `TC_91_…`, mais la fiche Notion 91 traite
 * de la migration Room 18 → 19 : c'est la fiche **93**, « AccountFormViewModel : solde observé, pas
 * de BalanceEngine », qui décrit ce test. Les deux fiches ayant été créées en lot à trois secondes
 * d'intervalle, les numéros attribués n'ont pas suivi l'ordre attendu et le fichier a hérité de
 * celui qu'on croyait lui revenir.
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
 * - CA-09 : l'enregistrement transmet la saisie telle quelle à `SaveAccountUseCase`.
 *
 * **Déplacement du 2 octobre 2026 (LOP-20, décision D5).** L'écriture du compte a quitté le
 * ViewModel pour `SaveAccountUseCase`, qui porte désormais la conversion euros → centimes. F-03 ne
 * peut plus constater cette conversion ici : il assert le brouillon exact transmis, saisie « 12,34 »
 * comprise. La conversion d'une saisie à virgule est prouvée sur base réelle par TC-136 E-02
 * (« 1200,00 » → 120 000 centimes).
 *
 * **Écart signalé, volontairement non corrigé :** la fiche 93 déclare couvrir CA-01 et CA-02, ce
 * fichier couvre CA-01, CA-07 et CA-09. CA-02 — « les seuls appels à `BalanceEngine` sont
 * `AccountRepository` et `AdjustBalanceUseCase` » — est une revue de dépôt, pas une assertion : il
 * n'a jamais pu être couvert ici. À trancher côté Notion.
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

    private val saveAccountUseCase = mockk<SaveAccountUseCase>(relaxed = false)
    private val getAccountBalances = mockk<GetAccountBalancesUseCase>(relaxed = false)
    private val accountRepo = mockk<AccountRepository>(relaxed = false)
    private val iconSearch = mockk<IconSearchRepository>(relaxed = false)

    /**
     * Strict et sans stub : aucun scénario de cette fiche ne supprime de compte, donc tout appel
     * doit faire échouer le test plutôt que passer inaperçu.
     */
    private val deleteAccountUseCase = mockk<DeleteAccountUseCase>(relaxed = false)

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
        saveAccountUseCase = saveAccountUseCase,
        getAccountBalances = getAccountBalances,
        deleteAccountUseCase = deleteAccountUseCase,
        accountRepo = accountRepo,
        iconSearch = iconSearch,
        clock = Clock.fixed(Instant.parse("2026-03-10T08:00:00Z"), ZoneId.of("Europe/Paris")),
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
    fun `F-03 - l'enregistrement transmet la saisie exacte au use case de sauvegarde`() = runTest {
        every { getAccountBalances.observeBalances() } returns flowOf(mapOf(accountId to 851L))
        val expectedDraft = AccountDraft(
            id = accountId,
            name = "Compte courant",
            type = AccountType.CHECKING,
            balanceInput = "12,34",
            colorArgb = 0,
            iconName = "account_balance",
            bankName = "",
            comment = "",
            includeInTotal = true,
        )
        coEvery { saveAccountUseCase(expectedDraft) } returns
            AccountSaveResult.Saved(accountId, AdjustOutcome.Created(transactionId = 12L, delta = 383L))

        val vm = sut()
        vm.uiState.test {
            awaitItem { it.isLoaded }
            cancelAndIgnoreRemainingEvents()
        }
        vm.onNameChange("Compte courant")
        vm.onInitialBalanceChange("12,34")
        vm.save {}

        coVerify(exactly = 1) { saveAccountUseCase(expectedDraft) }
        coVerify(exactly = 1) { accountRepo.getById(accountId) }
        coVerify(exactly = 0) { accountRepo.upsert(any()) }
        confirmVerified(accountRepo)
    }
}

/** Consomme le flux jusqu'au premier élément satisfaisant [predicate]. */
private suspend fun <T> app.cash.turbine.ReceiveTurbine<T>.awaitItem(predicate: (T) -> Boolean): T {
    while (true) {
        val item = awaitItem()
        if (predicate(item)) return item
    }
}

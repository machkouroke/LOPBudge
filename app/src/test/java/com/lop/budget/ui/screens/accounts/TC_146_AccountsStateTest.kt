package com.lop.budget.ui.screens.accounts

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.repository.SettingsRepository
import com.lop.budget.domain.model.AccountBalance
import com.lop.budget.domain.model.AccountBalances
import com.lop.budget.domain.model.AccountType
import com.lop.budget.domain.usecase.account.GetAccountBalancesUseCase
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * TC-146 — Comptes & soldes actuels : états du ViewModel et instantané cohérent (US LOP-13).
 *
 * ## Niveau et chaîne exercée
 * ViewModel JVM. Le système testé est le vrai [AccountsViewModel]. Ses deux frontières sont des
 * doublures MockK strictes, alimentées par des canaux que le test remplit à la main :
 * `GetAccountBalancesUseCase.observe()` et `SettingsRepository.currency`. Les canaux ne
 * calculent, ne filtrent ni ne trient rien : l'état observé ne peut venir que du ViewModel.
 *
 * ## Traçabilité — cas → CA / invariant → production
 * ```
 * V-01a/b  CA-06, I-2, P-5   AccountsViewModel.uiState — valeur initiale Loading, combine
 * V-02     CA-02, CA-03, P-1 AccountsViewModel.uiState — filtrage archived, total transmis
 * V-03     CA-03             idem — un compte exclu reste listé
 * V-04a/b  CA-05, P-1        idem — succès vide distinct d'une attente
 * V-05     CA-04             idem — émission atomique, aucun mélange S1/S2
 * V-06a/b  CA-07, I-2        AccountsViewModel.uiState — catch → Error
 * ```
 *
 * ## Fixture discriminante
 * Le solde de départ d'A (77 700) diffère de son solde reçu (10 000) : un ViewModel qui
 * afficherait le solde de départ comme valeur de repli fait échouer V-02.
 *
 * ## Montage
 * `Dispatchers.Main` et `runTest` partagent un `StandardTestDispatcher`. Une seule collecte de
 * `uiState`, dans `backgroundScope`, enregistre la trace des états. Après chaque dépôt dans un
 * canal : `advanceUntilIdle()` puis `runCurrent()`, sans lequel le collecteur de fond ne tourne pas.
 * Le ViewModel vit dans un `ViewModelStore` vidé au teardown, avant la restauration de Main.
 *
 * ## ANO connues
 * Aucune.
 *
 * ## Résultats — 7 octobre 2026, production de `14bc86e`
 * 10 verts sur 10 du premier coup. Preuves de sensibilité, une mutation à la fois, retirée :
 * ```
 * M1  filtre archived retiré                       → V-01a/b, V-02 ×2, V-03, V-04b, V-05 rouges
 * M2  solde de départ exposé à la place du reçu     → V-01a/b, V-02 ×2, V-03, V-05 rouges
 * M3  catch retiré                                 → V-06a, V-06b rouges
 * M4  valeur initiale Loaded("EUR", 0, [])         → V-01a, V-01b, V-05 rouges
 * M5  première émission seulement (take(1))        → V-05, V-06b rouges
 * ```
 *
 * ## Hors périmètre
 * - Écritures réelles, réactivité de Room et I-1 : TC-148.
 * - Libellés, montants formatés, badge, navigation, absence de barre : TC-147. Conserver
 *   l'icône et la couleur dans l'état ne prouve pas leur rendu (CA-02 partiel).
 * - Calcul des soldes : campagne du moteur. Aucun réessai après erreur : l'US n'en demande pas.
 *
 * ## Exécution
 * `./gradlew :app:testDebugUnitTest --tests "*AccountsStateTest"`
 */
class AccountsStateTest {

    private val mainDispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()

    private lateinit var balances: Channel<AccountBalances>
    private lateinit var currency: Channel<String>
    private lateinit var useCase: GetAccountBalancesUseCase
    private lateinit var settings: SettingsRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        balances = Channel(Channel.UNLIMITED)
        currency = Channel(Channel.UNLIMITED)
        useCase = mockk()
        settings = mockk()
        every { useCase.observe() } returns balances.receiveAsFlow()
        every { settings.currency } returns currency.receiveAsFlow()
    }

    @After
    fun tearDown() {
        store.clear()
        balances.close()
        currency.close()
        Dispatchers.resetMain()
    }

    // ==========================================================================================
    // CA-06 / I-2 — attente tant qu'une des deux sources manque
    // ==========================================================================================

    @Test
    fun `V-01a - given devise EUR seule et soldes muets when on libere S1 then Loading jusque-la puis Loaded A, B, C total 8000`() =
        runTest {
            val trace = openCollection()

            currency.send(EUR)
            settle()
            assertOnlyLoading("V-01a", trace)

            balances.send(S1)
            settle()
            assertLoaded("V-01a", "CA-06", trace, EUR, 8_000L, S1_ACTIVE)
            assertNoZeroFallback("V-01a", trace)
            verifyBoundaryCalls()
        }

    @Test
    fun `V-01b - given S1 seul et devise muette when on libere EUR then Loading jusque-la puis Loaded A, B, C total 8000`() =
        runTest {
            val trace = openCollection()

            balances.send(S1)
            settle()
            assertOnlyLoading("V-01b", trace)

            currency.send(EUR)
            settle()
            assertLoaded("V-01b", "CA-06", trace, EUR, 8_000L, S1_ACTIVE)
            assertNoZeroFallback("V-01b", trace)
            verifyBoundaryCalls()
        }

    // ==========================================================================================
    // CA-02 / CA-03 / P-1 — valeurs conservées, archivés retirés
    // ==========================================================================================

    @Test
    fun `V-02 EUR - given S1 et devise EUR when chargement reussi then exactement A, B, C conserves, D absent, total 8000`() =
        runTest {
            val trace = openCollection()

            currency.send(EUR)
            balances.send(S1)
            settle()

            assertLoaded("V-02 EUR", "CA-02/CA-03", trace, EUR, 8_000L, S1_ACTIVE)
            verifyBoundaryCalls()
        }

    @Test
    fun `V-02 USD - given S1 et devise USD when chargement reussi then la devise recue est transmise telle quelle`() =
        runTest {
            val trace = openCollection()

            currency.send(USD)
            balances.send(S1)
            settle()

            assertLoaded("V-02 USD", "CA-02/CA-03", trace, USD, 8_000L, S1_ACTIVE)
            verifyBoundaryCalls()
        }

    @Test
    fun `V-03 - given S-EXCLUS when chargement reussi then A, B, C restent listes avec leurs soldes et le total vaut 0`() =
        runTest {
            val trace = openCollection()

            currency.send(EUR)
            balances.send(S_EXCLUS)
            settle()

            assertLoaded("V-03", "CA-03", trace, EUR, 0L, S_EXCLUS_ACTIVE)
            verifyBoundaryCalls()
        }

    // ==========================================================================================
    // CA-05 — succès sans compte actif
    // ==========================================================================================

    @Test
    fun `V-04a - given S-VIDE when chargement reussi then Loaded liste vide total 0, ni Loading ni Error`() =
        runTest {
            val trace = openCollection()

            currency.send(EUR)
            balances.send(S_VIDE)
            settle()

            assertLoaded("V-04a", "CA-05", trace, EUR, 0L, emptyList())
            verifyBoundaryCalls()
        }

    @Test
    fun `V-04b - given S-ARCHIVE avec D seul when chargement reussi then Loaded liste vide total 0`() =
        runTest {
            val trace = openCollection()

            currency.send(EUR)
            balances.send(S_ARCHIVE)
            settle()

            assertLoaded("V-04b", "CA-05/P-1", trace, EUR, 0L, emptyList())
            verifyBoundaryCalls()
        }

    // ==========================================================================================
    // CA-04 — un résultat actualisé remplace le précédent sans mélange
    // ==========================================================================================

    @Test
    fun `V-05 - given S1 affiche when S2 arrive sur la meme collecte then etat final A, C, E de S2 et aucun etat ne melange S1 et S2`() =
        runTest {
            val trace = openCollection()
            currency.send(EUR)
            balances.send(S1)
            settle()
            awaitIdentityA("V-05", trace)

            balances.send(S2)
            settle()

            assertLoaded("V-05", "CA-04", trace, EUR, -2_300L, S2_ACTIVE)

            // Chaque Loaded porte les lignes et le total d'un même résultat.
            val s1Ids = S1_ACTIVE.map { it.account.id }.toSet()
            val s2Ids = S2_ACTIVE.map { it.account.id }.toSet()
            trace.filterIsInstance<AccountsUiState.Loaded>().forEach { state ->
                val ids = state.accounts.map { it.account.id }.toSet()
                val expectedTotal = when (ids) {
                    s1Ids -> 8_000L
                    s2Ids -> -2_300L
                    else -> throw AssertionError(
                        "V-05 — CA-04 : un état expose des lignes qui ne sont ni celles de S1 " +
                            "($s1Ids) ni celles de S2 ($s2Ids) : $ids. Trace : $trace"
                    )
                }
                assertEquals(
                    "V-05 — CA-04 : les lignes $ids sont associées à un total qui n'est pas le leur. " +
                        "Trace : $trace",
                    expectedTotal,
                    state.totalBalance,
                )
            }
            verifyBoundaryCalls()
        }

    // ==========================================================================================
    // CA-07 / I-2 — l'échec remplace tout solde
    // ==========================================================================================

    @Test
    fun `V-06a - given aucun succes when le flux des soldes echoue then Error est l'etat courant`() =
        runTest {
            val trace = openCollection()
            currency.send(EUR)
            settle()

            balances.close(IllegalStateException("QA-13 échec contrôlé"))
            settle()

            assertError("V-06a", trace)
            verifyBoundaryCalls()
        }

    @Test
    fun `V-06b - given un succes contenant A when le flux des soldes echoue then Error remplace l'ancien Loaded`() =
        runTest {
            val trace = openCollection()
            currency.send(EUR)
            balances.send(S1)
            settle()
            awaitIdentityA("V-06b", trace)

            balances.close(IllegalStateException("QA-13 échec contrôlé"))
            settle()

            assertError("V-06b", trace)
            verifyBoundaryCalls()
        }

    // ==========================================================================================
    // Montage
    // ==========================================================================================

    private lateinit var vm: AccountsViewModel

    private fun TestScope.openCollection(): MutableList<AccountsUiState> {
        vm = ViewModelProvider(
            store,
            viewModelFactory { initializer { AccountsViewModel(useCase, settings) } },
        )[AccountsViewModel::class.java]
        val trace = mutableListOf<AccountsUiState>()
        backgroundScope.launch { vm.uiState.collect { trace += it } }
        settle()
        return trace
    }

    private fun TestScope.settle() {
        advanceUntilIdle()
        runCurrent()
    }

    /**
     * Une seule souscription au point d'entrée du domaine ; jamais ses projections, dont la
     * recombinaison pourrait associer un total et des soldes de deux émissions différentes.
     */
    private fun verifyBoundaryCalls() {
        verify(exactly = 1) { useCase.observe() }
        verify(exactly = 0) { useCase.observeBalances() }
        verify(exactly = 0) { useCase.observeTotalBalance() }
        verify(exactly = 1) { settings.currency }
        confirmVerified(useCase, settings)
    }

    // ==========================================================================================
    // Oracles
    // ==========================================================================================

    private fun assertOnlyLoading(case: String, trace: List<AccountsUiState>) {
        assertEquals(
            "$case — CA-06/I-2 : tant qu'une source manque, seul Loading doit être exposé. " +
                "Trace : $trace",
            listOf<AccountsUiState>(AccountsUiState.Loading),
            trace,
        )
    }

    /** P-5 : le zéro affiché avant tout résultat ne doit jamais réapparaître comme un succès. */
    private fun assertNoZeroFallback(case: String, trace: List<AccountsUiState>) {
        val fallbacks = trace.filterIsInstance<AccountsUiState.Loaded>()
            .filter { it.accounts.isEmpty() && it.totalBalance == 0L }
        assertEquals(
            "$case — CA-06/P-5 : aucun Loaded vide à zéro ne doit précéder le résultat. Trace : $trace",
            0,
            fallbacks.size,
        )
    }

    private fun awaitIdentityA(case: String, trace: List<AccountsUiState>) {
        val current = vm.uiState.value
        if (current !is AccountsUiState.Loaded || current.accounts.none { it.account.id == A.id }) {
            fail(
                "$case — synchronisation : un Loaded contenant A (id ${A.id}) était attendu avant " +
                    "le stimulus. Dernier état : $current. Trace : $trace"
            )
        }
    }

    private fun assertError(case: String, trace: List<AccountsUiState>) {
        assertEquals(
            "$case — CA-07/I-2 : après l'échec, l'état courant doit être Error, ni Loading ni un " +
                "ancien Loaded. Trace : $trace",
            AccountsUiState.Error,
            vm.uiState.value,
        )
    }

    /**
     * Devise, total, cardinalité, unicité et ensemble exact des IDs, puis chaque compte entier
     * (nom, type, banque, icône, couleur, inclusion, archivage) et son solde. Aucun ordre imposé.
     */
    private fun assertLoaded(
        case: String,
        ca: String,
        trace: List<AccountsUiState>,
        expectedCurrency: String,
        expectedTotal: Long,
        expectedRows: List<AccountBalance>,
    ) {
        val state = vm.uiState.value as? AccountsUiState.Loaded ?: throw AssertionError(
            "$case — $ca : Loaded attendu (total $expectedTotal, ${expectedRows.size} comptes). " +
                "Dernier état : ${vm.uiState.value}. Trace : $trace"
        )
        val context = "Dernier état : $state. Trace : $trace"

        assertEquals("$case — $ca : devise transmise. $context", expectedCurrency, state.currency)
        assertEquals("$case — $ca : total fourni par le moteur. $context", expectedTotal, state.totalBalance)

        val ids = state.accounts.map { it.account.id }
        assertEquals("$case — $ca : nombre de lignes. $context", expectedRows.size, ids.size)
        assertEquals("$case — $ca : un compte apparaît deux fois. $context", ids.size, ids.toSet().size)
        assertEquals(
            "$case — $ca : ensemble des comptes actifs (P-1). $context",
            expectedRows.map { it.account.id }.toSet(),
            ids.toSet(),
        )

        val actualById = state.accounts.associateBy { it.account.id }
        expectedRows.forEach { expected ->
            val actual = actualById.getValue(expected.account.id)
            assertEquals(
                "$case — $ca : compte ${expected.account.id} conservé tel que reçu. $context",
                expected.account,
                actual.account,
            )
            assertEquals(
                "$case — $ca : solde reçu du compte ${expected.account.id}, jamais le solde de " +
                    "départ. $context",
                expected.balance,
                actual.balance,
            )
        }
    }

    private companion object {
        const val EUR = "EUR"
        const val USD = "USD"

        // Identifiants de fixture, pas des IDs alloués par Room.
        val A = AccountEntity(
            id = 101, name = "QA-13 Alpha", type = AccountType.CHECKING, initialBalance = 77_700,
            balanceUpdatedAt = 0, colorArgb = 0xFF2196F3.toInt(), icon = "account_balance",
            bankName = "Banque QA-A", comment = null, includeInTotal = true, archived = false,
        )
        val B = AccountEntity(
            id = 102, name = "QA-13 Beta", type = AccountType.CASH, initialBalance = -5_500,
            balanceUpdatedAt = 0, colorArgb = 0xFF4CAF50.toInt(), icon = "payments",
            bankName = null, comment = null, includeInTotal = true, archived = false,
        )
        val C = AccountEntity(
            id = 103, name = "QA-13 Gamma", type = AccountType.SAVINGS, initialBalance = 4_500,
            balanceUpdatedAt = 0, colorArgb = 0xFF9C27B0.toInt(), icon = "savings",
            bankName = "", comment = null, includeInTotal = false, archived = false,
        )
        val D = AccountEntity(
            id = 104, name = "QA-13 Delta", type = AccountType.SAVINGS, initialBalance = 50_000,
            balanceUpdatedAt = 0, colorArgb = 0xFFFFC107.toInt(), icon = "savings",
            bankName = null, comment = null, includeInTotal = true, archived = true,
        )
        val E = AccountEntity(
            id = 105, name = "QA-13 Epsilon", type = AccountType.CASH, initialBalance = 123_456,
            balanceUpdatedAt = 0, colorArgb = 0xFF607D8B.toInt(), icon = "payments",
            bankName = null, comment = null, includeInTotal = true, archived = false,
        )

        val S1_ACTIVE = listOf(
            AccountBalance(A, 10_000), AccountBalance(B, -2_000), AccountBalance(C, 5_000),
        )
        val S1 = AccountBalances(S1_ACTIVE + AccountBalance(D, 50_000), total = 8_000)

        val S_EXCLUS_ACTIVE = listOf(
            AccountBalance(A.copy(includeInTotal = false), 10_000),
            AccountBalance(B.copy(includeInTotal = false), -2_000),
            AccountBalance(C, 5_000),
        )
        val S_EXCLUS = AccountBalances(S_EXCLUS_ACTIVE + AccountBalance(D, 50_000), total = 0)

        val S_VIDE = AccountBalances(emptyList(), total = 0)
        val S_ARCHIVE = AccountBalances(listOf(AccountBalance(D, 50_000)), total = 0)

        val S2_ACTIVE = listOf(
            AccountBalance(
                A.copy(
                    name = "QA-13 Alpha bis", type = AccountType.SAVINGS, bankName = "Banque QA-Z",
                    icon = "savings", colorArgb = 0xFFF44336.toInt(), includeInTotal = false,
                ),
                12_000,
            ),
            AccountBalance(C.copy(includeInTotal = true), -3_000),
            AccountBalance(E, 700),
        )
        val S2 = AccountBalances(S2_ACTIVE + AccountBalance(D, 50_000), total = -2_300)
    }
}

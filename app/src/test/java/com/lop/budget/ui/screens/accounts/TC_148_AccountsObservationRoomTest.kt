package com.lop.budget.ui.screens.accounts

import android.app.Application
import android.database.Cursor
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.Event
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import com.lop.budget.data.local.LopDatabase
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.data.repository.AccountRepository
import com.lop.budget.data.repository.SettingsRepository
import com.lop.budget.data.repository.TransactionRepository
import com.lop.budget.domain.model.AccountBalance
import com.lop.budget.domain.model.AccountType
import com.lop.budget.domain.model.NO_CATEGORY_ID
import com.lop.budget.domain.model.TransactionKind
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.domain.usecase.account.AccountDeleteResult
import com.lop.budget.domain.usecase.account.AccountSaveResult
import com.lop.budget.domain.usecase.account.AdjustBalanceUseCase
import com.lop.budget.domain.usecase.account.AdjustOutcome
import com.lop.budget.domain.usecase.account.DeleteAccountUseCase
import com.lop.budget.domain.usecase.account.GetAccountBalancesUseCase
import com.lop.budget.domain.usecase.account.SetAccountFlagsUseCase
import com.lop.budget.domain.usecase.transaction.AtomicWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.seconds

/**
 * TC-148 — Comptes & soldes actuels : propagation Room et consultation sans écriture (US LOP-13).
 *
 * ## Niveau et chaîne exercée
 * Intégration Room, hors interface. Aucune doublure sur la chaîne :
 * `AccountsViewModel.uiState` → `GetAccountBalancesUseCase.observe()`
 *   → `AccountRepository.observeBalances` → `BalanceEngine` → vrais DAO → `LopDatabase` en mémoire.
 * `SettingsRepository` est réel. Les écrivains (`AdjustBalanceUseCase`, `SetAccountFlagsUseCase`,
 * `DeleteAccountUseCase`, DAO) ne servent qu'à **préparer** la mutation ; leurs règles sont
 * couvertes par leurs propres campagnes.
 *
 * ## Traçabilité — cas → CA / invariant → production
 * ```
 * R-01        I-1          consultation : AccountsViewModel.uiState, aucune écriture
 * R-02 ×7     CA-04, I-1   TransactionDao (upsert, softDeleteTransaction) → état exposé
 * R-03 ×12    CA-04, I-1   AccountDao.upsert, DeleteAccountUseCase, AdjustBalanceUseCase,
 *                          SetAccountFlagsUseCase → état exposé ; filtrage archived du ViewModel
 * ```
 *
 * ## Déroulé d'un cas
 * Base semée → collecte ouverte (Turbine, délai réel de 10 s) → synchronisation sur un état
 * contenant A → **écriture de préparation** → instantané de référence et marque dans le journal
 * → attente de l'état final exact → barrière → contrôles.
 *
 * Les émissions intermédiaires cohérentes sont tolérées (Room réinterroge chaque table à part) ;
 * chaque émission doit avoir un total égal à la somme de ses propres comptes inclus. Une fois
 * l'état final atteint, aucune émission ne doit en différer jusqu'à la barrière.
 *
 * ## Preuve d'absence d'écriture (I-1)
 * - Instantanés SQL bruts complets, lignes supprimées comprises, comparés à la référence prise
 *   **après** la préparation.
 * - Journal synchrone des requêtes Room (`setQueryCallback`) : zéro `INSERT`/`UPDATE`/`DELETE`
 *   visant `accounts` ou `transactions` pendant chaque phase de lecture. Il voit une écriture
 *   annulée aussitôt, invisible dans un instantané.
 *
 * ## Hypothèses et écarts à la fiche
 * - La devise n'est pas écrite (la fiche demandait d'écrire EUR puis de restaurer) : sous Windows,
 *   un DataStore de test n'accepte qu'une écriture par fichier. Sans préférence, la devise vaut
 *   EUR ; c'est vérifié en précondition de montage.
 * - Barrière : l'exécuteur de requêtes Room est un fil unique possédé par le test. Deux tâches vides
 *   successives garantissent que les relectures déclenchées par l'invalidation sont passées, puis
 *   le Main de test est vidé. **Limite** : le saut `flowOn(Dispatchers.IO)` du repository ne se
 *   synchronise pas sans toucher la production ; une régression arrivant après la barrière ne
 *   serait pas vue.
 *
 * ## ANO connues
 * Aucune.
 *
 * ## Hors périmètre
 * - Règles de calcul des soldes : campagne du moteur (TC-94, TC-95). CRUD des comptes et contenu
 *   des ajustements : leurs campagnes propres.
 * - Chargement, erreur et émission atomique contrôlée : TC-146. Rendu, navigation, barres : TC-147.
 *
 * ## Exécution
 * `./gradlew :app:testDebugUnitTest --tests "*AccountsObservationRoomTest"`
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class AccountsObservationRoomTest {

    private val mainDispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val sqlLog = CopyOnWriteArrayList<String>()
    private lateinit var queryExecutor: ExecutorService

    private lateinit var db: LopDatabase
    private lateinit var accountRepo: AccountRepository
    private lateinit var transactionRepo: TransactionRepository
    private lateinit var settings: SettingsRepository
    private lateinit var vm: AccountsViewModel

    private lateinit var previousTimeZone: TimeZone
    private lateinit var previousLocale: Locale

    // Identifiants alloués par Room, conservés sous leurs noms symboliques.
    private var a = 0L
    private var b = 0L
    private var c = 0L
    private var d = 0L
    private var e = 0L
    private var txPayee = 0L
    private var txPlan = 0L

    @Before
    fun setUp() {
        previousTimeZone = TimeZone.getDefault()
        previousLocale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(ZONE))
        Locale.setDefault(Locale.FRANCE)
        Dispatchers.setMain(mainDispatcher)

        queryExecutor = Executors.newSingleThreadExecutor()
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Application>(),
            LopDatabase::class.java,
        )
            .allowMainThreadQueries()
            .setQueryExecutor(queryExecutor)
            .setQueryCallback({ sql, _ -> sqlLog += sql }, Executor { it.run() })
            .build()
        accountRepo = AccountRepository(db.accountDao())
        transactionRepo = TransactionRepository(db.transactionDao(), db.recurringSeriesDao())
        settings = SettingsRepository(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        store.clear()
        db.close()
        queryExecutor.shutdownNow()
        Dispatchers.resetMain()
        TimeZone.setDefault(previousTimeZone)
        Locale.setDefault(previousLocale)
    }

    // ==========================================================================================
    // R-01 — I-1 : consulter n'écrit rien
    // ==========================================================================================

    @Test
    fun `R-01 - given la base de reference when on observe puis relit l'etat then etat de reference exact et zero ecriture`() =
        scenario(
            case = "R-01", ca = "I-1",
            prepare = { },
            expectedRows = { referenceRows() },
            expectedTotal = 6_000L,
            accounts = 4, transactions = 3, adjustments = 0,
        )

    // ==========================================================================================
    // R-02 — CA-04 : mutations de transaction, collecte déjà ouverte
    // ==========================================================================================

    @Test
    fun `R-02 creation - given A observe when une depense payee de 500 est inseree then A 7500 et total 5500`() =
        scenario(
            case = "R-02 création", ca = "CA-04",
            prepare = {
                db.transactionDao().upsert(
                    tx("QA-13 Nouvelle", 500, TransactionType.EXPENSE, TransactionStatus.PAID, a, paidAt = T)
                )
            },
            expectedRows = { listOf(rowA(7_500), rowB(-2_000), rowC(5_000)) },
            expectedTotal = 5_500L,
            accounts = 4, transactions = 4, adjustments = 0,
        )

    @Test
    fun `R-02 suppression - given A observe when TX-PAYEE est supprimee then A 10000 et total 8000`() =
        scenario(
            case = "R-02 suppression", ca = "CA-04",
            prepare = { db.transactionDao().softDeleteTransaction(txPayee) },
            expectedRows = { listOf(rowA(10_000), rowB(-2_000), rowC(5_000)) },
            expectedTotal = 8_000L,
            accounts = 4, transactions = 3, adjustments = 0,
            extraCheck = { snapshot ->
                assertEquals(
                    "R-02 suppression — préparation : TX-PAYEE reste en base, marquée supprimée.",
                    1L,
                    snapshot.transactions.single { it["id"] == txPayee }["deleted"],
                )
            },
        )

    @Test
    fun `R-02 montant - given A observe when TX-PAYEE passe de 2000 a 3000 then A 7000 et total 5000`() =
        scenario(
            case = "R-02 montant", ca = "CA-04",
            prepare = { updateTx(txPayee) { it.copy(amount = 3_000) } },
            expectedRows = { listOf(rowA(7_000), rowB(-2_000), rowC(5_000)) },
            expectedTotal = 5_000L,
            accounts = 4, transactions = 3, adjustments = 0,
        )

    @Test
    fun `R-02 sens - given A observe when TX-PAYEE devient un revenu then A 12000 et total 10000`() =
        scenario(
            case = "R-02 sens", ca = "CA-04",
            prepare = { updateTx(txPayee) { it.copy(type = TransactionType.INCOME) } },
            expectedRows = { listOf(rowA(12_000), rowB(-2_000), rowC(5_000)) },
            expectedTotal = 10_000L,
            accounts = 4, transactions = 3, adjustments = 0,
        )

    @Test
    fun `R-02 compte - given A observe when TX-PAYEE passe de A a B then A 10000, B -4000 et total 6000`() =
        scenario(
            case = "R-02 compte", ca = "CA-04",
            prepare = { updateTx(txPayee) { it.copy(accountId = b) } },
            expectedRows = { listOf(rowA(10_000), rowB(-4_000), rowC(5_000)) },
            expectedTotal = 6_000L,
            accounts = 4, transactions = 3, adjustments = 0,
        )

    @Test
    fun `R-02 paye vers planifie - given A observe when TX-PAYEE redevient planifiee then A 10000 et total 8000`() =
        scenario(
            case = "R-02 payé vers planifié", ca = "CA-04",
            prepare = {
                updateTx(txPayee) { it.copy(status = TransactionStatus.PLANNED, paidAt = null) }
            },
            expectedRows = { listOf(rowA(10_000), rowB(-2_000), rowC(5_000)) },
            expectedTotal = 8_000L,
            accounts = 4, transactions = 3, adjustments = 0,
        )

    @Test
    fun `R-02 planifie vers paye - given A observe when TX-PLAN est payee then A 7700 et total 5700`() =
        scenario(
            case = "R-02 planifié vers payé", ca = "CA-04",
            prepare = {
                updateTx(txPlan) { it.copy(status = TransactionStatus.PAID, paidAt = T) }
            },
            expectedRows = { listOf(rowA(7_700), rowB(-2_000), rowC(5_000)) },
            expectedTotal = 5_700L,
            accounts = 4, transactions = 3, adjustments = 0,
        )

    // ==========================================================================================
    // R-03 — CA-04 : mutations de compte, même ViewModel et même collecte
    // ==========================================================================================

    @Test
    fun `R-03 creation - given A, B, C observes when E est cree then A, B, C, E et total 6700`() =
        scenario(
            case = "R-03 création", ca = "CA-04",
            prepare = { e = db.accountDao().upsert(ACCOUNT_E) },
            expectedRows = {
                listOf(
                    rowA(8_000), rowB(-2_000), rowC(5_000),
                    AccountBalance(ACCOUNT_E.copy(id = e), 700),
                )
            },
            expectedTotal = 6_700L,
            accounts = 5, transactions = 3, adjustments = 0,
        )

    @Test
    fun `R-03 suppression - given A, B, C observes when A est supprime then B, C seulement et total -2000`() =
        scenario(
            case = "R-03 suppression", ca = "CA-04/P-1",
            prepare = {
                val atomicWriter = object : AtomicWriter {
                    override suspend fun <T> atomically(block: suspend () -> T): T =
                        db.withTransaction { block() }
                }
                val result = DeleteAccountUseCase(accountRepo, transactionRepo, atomicWriter)(a)
                assertTrue("R-03 suppression — préparation refusée : $result", result is AccountDeleteResult.Deleted)
            },
            expectedRows = { listOf(rowB(-2_000), rowC(5_000)) },
            expectedTotal = -2_000L,
            accounts = 3, transactions = 3, adjustments = 0,
        )

    @Test
    fun `R-03 nom - given A observe when A est renomme then le nouveau nom est expose, solde 8000, total 6000`() =
        metadataScenario("R-03 nom") { it.copy(name = "QA-13 Alpha bis") }

    @Test
    fun `R-03 type - given A observe when A devient SAVINGS then le nouveau type est expose, solde 8000, total 6000`() =
        metadataScenario("R-03 type") { it.copy(type = AccountType.SAVINGS) }

    @Test
    fun `R-03 banque remplacee - given A observe when la banque devient Banque QA-Z then elle est exposee`() =
        metadataScenario("R-03 banque remplacée") { it.copy(bankName = "Banque QA-Z") }

    @Test
    fun `R-03 banque retiree - given A observe when la banque est retiree then aucune banque n'est exposee`() =
        metadataScenario("R-03 banque retirée") { it.copy(bankName = null) }

    @Test
    fun `R-03 icone - given A observe when l'icone devient savings then elle est exposee`() =
        metadataScenario("R-03 icône") { it.copy(icon = "savings") }

    @Test
    fun `R-03 couleur - given A observe when la couleur devient 0xFFF44336 then elle est exposee`() =
        metadataScenario("R-03 couleur") { it.copy(colorArgb = 0xFFF44336.toInt()) }

    @Test
    fun `R-03 solde corrige - given A a 8000 when A est corrige a 12000 then A 12000, total 10000 et un seul ajustement`() =
        scenario(
            case = "R-03 solde corrigé", ca = "CA-04",
            prepare = {
                val outcome = AdjustBalanceUseCase(accountRepo, transactionRepo, CLOCK).adjust(a, 12_000)
                assertEquals(
                    "R-03 solde corrigé — préparation : un ajustement de +4 000 était attendu.",
                    4_000L,
                    (outcome as? AdjustOutcome.Created)?.delta,
                )
            },
            expectedRows = { listOf(rowA(12_000), rowB(-2_000), rowC(5_000)) },
            expectedTotal = 10_000L,
            accounts = 4, transactions = 4, adjustments = 1,
        )

    @Test
    fun `R-03 inclusion retiree - given A inclus when A est exclu du total then A reste liste a 8000 et total -2000`() =
        scenario(
            case = "R-03 inclusion retirée", ca = "CA-04/CA-03",
            prepare = { setFlags(a, includeInTotal = false) },
            expectedRows = {
                listOf(rowA(8_000, ACCOUNT_A.copy(includeInTotal = false)), rowB(-2_000), rowC(5_000))
            },
            expectedTotal = -2_000L,
            accounts = 4, transactions = 3, adjustments = 0,
        )

    @Test
    fun `R-03 archivage - given A actif when A est archive then B, C seulement, total -2000 et A reste en base`() =
        scenario(
            case = "R-03 archivage", ca = "CA-04/P-1",
            prepare = { setFlags(a, archived = true) },
            expectedRows = { listOf(rowB(-2_000), rowC(5_000)) },
            expectedTotal = -2_000L,
            accounts = 4, transactions = 3, adjustments = 0,
            extraCheck = { snapshot ->
                assertEquals(
                    "R-03 archivage — P-1 : A doit rester en base, archivé.",
                    1L,
                    snapshot.accounts.single { it["id"] == a }["archived"],
                )
            },
        )

    @Test
    fun `R-03 desarchivage - given D archive when D est desarchive then A, B, C, D et total 56000`() =
        scenario(
            case = "R-03 désarchivage", ca = "CA-04/P-1",
            prepare = { setFlags(d, archived = false) },
            expectedRows = {
                listOf(
                    rowA(8_000), rowB(-2_000), rowC(5_000),
                    AccountBalance(ACCOUNT_D.copy(id = d, archived = false), 50_000),
                )
            },
            expectedTotal = 56_000L,
            accounts = 4, transactions = 3, adjustments = 0,
        )

    // ==========================================================================================
    // Scénario commun : synchronisation, préparation, attente, barrière, oracles
    // ==========================================================================================

    private fun metadataScenario(case: String, change: (AccountEntity) -> AccountEntity) =
        scenario(
            case = case, ca = "CA-04/CA-02",
            prepare = {
                val current = db.accountDao().getById(a)!!
                db.accountDao().upsert(change(current))
            },
            expectedRows = {
                listOf(rowA(8_000, change(ACCOUNT_A)), rowB(-2_000), rowC(5_000))
            },
            expectedTotal = 6_000L,
            accounts = 4, transactions = 3, adjustments = 0,
        )

    private fun scenario(
        case: String,
        ca: String,
        prepare: suspend () -> Unit,
        expectedRows: () -> List<AccountBalance>,
        expectedTotal: Long,
        accounts: Int,
        transactions: Int,
        adjustments: Int,
        extraCheck: (Snapshot) -> Unit = {},
    ) = runTest {
        val currency = settings.currency.first()
        if (currency != EUR) {
            throw AssertionError(
                "$case — montage : la devise par défaut devait être EUR, lu « $currency ». " +
                    "Échec de montage, pas une preuve métier."
            )
        }
        seedBase()
        val seedEnd = sqlLog.size
        val seedSnapshot = snapshot()
        // Témoin anti-oracle vacant : le journal doit voir les 7 insertions du semis.
        assertEquals(
            "$case — montage : le journal des requêtes ne voit pas les écritures du semis.",
            7,
            writesSince(0).size,
        )

        vm = ViewModelProvider(
            store,
            viewModelFactory {
                initializer {
                    AccountsViewModel(GetAccountBalancesUseCase(accountRepo, transactionRepo), settings)
                }
            },
        )[AccountsViewModel::class.java]

        val trace = mutableListOf<AccountsUiState>()
        lateinit var reference: Snapshot
        var prepEnd = 0
        var syncWrites = emptyList<String>()
        lateinit var finalState: AccountsUiState.Loaded
        val rows = mutableListOf<AccountBalance>()

        vm.uiState.test(timeout = TIMEOUT) {
            awaitState(this, trace, case, "synchronisation sur A", seedSnapshot) { state ->
                state.accounts.any { it.account.id == a }
            }
            syncWrites = writesSince(seedEnd)

            prepare()
            reference = snapshot()
            prepEnd = sqlLog.size
            rows += expectedRows()

            finalState = awaitState(this, trace, case, ca, reference) { state ->
                state.totalBalance == expectedTotal &&
                    state.accounts.size == rows.size &&
                    state.accounts.toSet() == rows.toSet()
            }
            barrier(this@runTest)
            trace += cancelAndConsumeRemainingEvents()
                .filterIsInstance<Event.Item<AccountsUiState>>()
                .map { it.value }
        }

        val context = "Trace : $trace"
        assertEquals("$case — I-1 : la consultation initiale a écrit : $syncWrites", emptyList<String>(), syncWrites)
        assertFinal(case, ca, finalState, rows, expectedTotal, context)

        val lateStates = trace.drop(trace.indexOf(finalState) + 1)
        assertEquals(
            "$case — $ca : après l'état final, l'état exposé est revenu en arrière ou a changé. $context",
            emptyList<AccountsUiState>(),
            lateStates,
        )
        assertEquals("$case — $ca : relecture de l'état courant. $context", finalState, vm.uiState.value)

        val after = snapshot()
        assertEquals(
            "$case — I-1 : la lecture a modifié les tables depuis le snapshot post-préparation.",
            reference,
            after,
        )
        assertEquals(
            "$case — I-1 : écritures pendant la phase de lecture seule.",
            emptyList<String>(),
            writesSince(prepEnd),
        )
        assertEquals("$case — préparation : nombre de comptes en base. $after", accounts, after.accounts.size)
        assertEquals("$case — préparation : nombre de transactions en base. $after", transactions, after.transactions.size)
        assertEquals(
            "$case — I-1 : nombre de lignes BALANCE_ADJUSTMENT. $after",
            adjustments,
            after.transactions.count { it["kind"] == TransactionKind.BALANCE_ADJUSTMENT.name },
        )
        extraCheck(after)
    }

    /**
     * Consomme les émissions jusqu'à l'état attendu, à partir du dernier état déjà reçu. Chaque
     * émission est enregistrée et contrôlée : `Error` arrête le cas, un total qui n'est pas la
     * somme de ses propres comptes inclus aussi. Un état jamais atteint devient un échec
     * diagnostiqué, pas un « No value produced ».
     */
    private suspend fun awaitState(
        turbine: ReceiveTurbine<AccountsUiState>,
        trace: MutableList<AccountsUiState>,
        case: String,
        what: String,
        snapshot: Snapshot,
        accept: (AccountsUiState.Loaded) -> Boolean,
    ): AccountsUiState.Loaded {
        (trace.lastOrNull() as? AccountsUiState.Loaded)?.takeIf(accept)?.let { return it }
        while (true) {
            val state = try {
                turbine.awaitItem()
            } catch (timeout: AssertionError) {
                throw AssertionError(
                    "$case — $what : état attendu non atteint en $TIMEOUT. Dernier état : " +
                        "${trace.lastOrNull()}. Trace : $trace. Snapshot : $snapshot. " +
                        "Écritures journalisées : ${writesSince(0)}",
                    timeout,
                )
            }
            trace += state
            when (state) {
                AccountsUiState.Loading -> Unit
                AccountsUiState.Error -> throw AssertionError("$case — $what : état Error reçu. Trace : $trace")
                is AccountsUiState.Loaded -> {
                    val included = state.accounts.filter { it.account.includeInTotal }.sumOf { it.balance }
                    assertEquals(
                        "$case — CA-04 : une émission associe un total à d'autres soldes que les " +
                            "siens. Émission : $state. Trace : $trace",
                        included,
                        state.totalBalance,
                    )
                    if (accept(state)) return state
                }
            }
        }
    }

    /**
     * Les relectures Room déclenchées par l'invalidation passent sur l'exécuteur à fil unique du
     * test : deux tâches vides successives garantissent qu'elles sont terminées. Puis le Main de
     * test est vidé.
     */
    private suspend fun barrier(scope: TestScope) {
        repeat(2) { withContext(Dispatchers.IO) { queryExecutor.submit {}.get() } }
        scope.advanceUntilIdle()
        scope.runCurrent()
    }

    /** Devise, total, cardinalité, unicité, ensemble des IDs, chaque compte entier et son solde. */
    private fun assertFinal(
        case: String,
        ca: String,
        state: AccountsUiState.Loaded,
        expectedRows: List<AccountBalance>,
        expectedTotal: Long,
        context: String,
    ) {
        assertEquals("$case — $ca : devise. $context", EUR, state.currency)
        assertEquals("$case — $ca : total. $context", expectedTotal, state.totalBalance)
        val ids = state.accounts.map { it.account.id }
        assertEquals("$case — $ca : nombre de lignes actives. $context", expectedRows.size, ids.size)
        assertEquals("$case — $ca : un compte apparaît deux fois. $context", ids.size, ids.toSet().size)
        assertEquals(
            "$case — $ca : ensemble des comptes actifs (P-1). $context",
            expectedRows.map { it.account.id }.toSet(),
            ids.toSet(),
        )
        val byId = state.accounts.associateBy { it.account.id }
        expectedRows.forEach { expected ->
            val actual = byId.getValue(expected.account.id)
            assertEquals(
                "$case — $ca : compte ${expected.account.id} (nom, type, banque, icône, couleur, " +
                    "inclusion, archivage). $context",
                expected.account,
                actual.account,
            )
            assertEquals("$case — $ca : solde du compte ${expected.account.id}. $context", expected.balance, actual.balance)
        }
    }

    // ==========================================================================================
    // Jeu de données et instruments
    // ==========================================================================================

    private suspend fun seedBase() {
        val accountDao = db.accountDao()
        a = accountDao.upsert(ACCOUNT_A)
        b = accountDao.upsert(ACCOUNT_B)
        c = accountDao.upsert(ACCOUNT_C)
        d = accountDao.upsert(ACCOUNT_D)
        val txDao = db.transactionDao()
        txPayee = txDao.upsert(tx("QA-13 Dépense", 2_000, TransactionType.EXPENSE, TransactionStatus.PAID, a, paidAt = T))
        txPlan = txDao.upsert(tx("QA-13 Planifiée", 300, TransactionType.EXPENSE, TransactionStatus.PLANNED, a, paidAt = null))
        txDao.upsert(
            tx("QA-13 Supprimée", 777, TransactionType.EXPENSE, TransactionStatus.PAID, a, paidAt = T)
                .copy(deleted = true)
        )
    }

    private fun referenceRows() = listOf(rowA(8_000), rowB(-2_000), rowC(5_000))
    private fun rowA(balance: Long, entity: AccountEntity = ACCOUNT_A) = AccountBalance(entity.copy(id = a), balance)
    private fun rowB(balance: Long) = AccountBalance(ACCOUNT_B.copy(id = b), balance)
    private fun rowC(balance: Long) = AccountBalance(ACCOUNT_C.copy(id = c), balance)

    private suspend fun updateTx(id: Long, change: (TransactionEntity) -> TransactionEntity) {
        val dao = db.transactionDao()
        dao.upsert(change(dao.getById(id)!!.transaction))
    }

    private suspend fun setFlags(id: Long, archived: Boolean? = null, includeInTotal: Boolean? = null) {
        val result = SetAccountFlagsUseCase(accountRepo)(id, archived = archived, includeInTotal = includeInTotal)
        assertTrue("Préparation refusée par SetAccountFlagsUseCase : $result", result is AccountSaveResult.Saved)
    }

    private fun writesSince(index: Int): List<String> =
        sqlLog.drop(index).filter { WRITE_ON_OBSERVED_TABLES.containsMatchIn(it) }

    private data class Snapshot(
        val accounts: List<Map<String, Any?>>,
        val transactions: List<Map<String, Any?>>,
    )

    private fun snapshot() = Snapshot(rows("accounts"), rows("transactions"))

    private fun rows(table: String): List<Map<String, Any?>> = buildList {
        db.openHelper.readableDatabase.query("SELECT * FROM $table ORDER BY id").use { cursor ->
            while (cursor.moveToNext()) {
                add((0 until cursor.columnCount).associate { i -> cursor.getColumnName(i) to cursor.typed(i) })
            }
        }
    }

    private fun Cursor.typed(i: Int): Any? = when (getType(i)) {
        Cursor.FIELD_TYPE_NULL -> null
        Cursor.FIELD_TYPE_INTEGER -> getLong(i)
        Cursor.FIELD_TYPE_FLOAT -> getDouble(i)
        Cursor.FIELD_TYPE_BLOB -> getBlob(i).toList()
        else -> getString(i)
    }

    private companion object {
        const val EUR = "EUR"
        const val ZONE = "Europe/Paris"

        /** Délai déclaré ici : il borne chaque attente de cette classe, en temps réel. */
        val TIMEOUT = 10.seconds

        val T: Long = Instant.parse("2026-03-05T11:00:00Z").toEpochMilli()
        val CLOCK: Clock = Clock.fixed(Instant.parse("2026-03-10T08:00:00Z"), ZoneId.of(ZONE))

        val WRITE_ON_OBSERVED_TABLES = Regex(
            """^\s*(INSERT|UPDATE|DELETE|REPLACE)\b.*\b(accounts|transactions)\b""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )

        val ACCOUNT_A = AccountEntity(
            name = "QA-13 Alpha", type = AccountType.CHECKING, initialBalance = 10_000,
            balanceUpdatedAt = 0, colorArgb = 0xFF2196F3.toInt(), icon = "account_balance",
            bankName = "Banque QA-A", comment = null, includeInTotal = true, archived = false,
        )
        val ACCOUNT_B = AccountEntity(
            name = "QA-13 Beta", type = AccountType.CASH, initialBalance = -2_000,
            balanceUpdatedAt = 0, colorArgb = 0xFF4CAF50.toInt(), icon = "payments",
            bankName = null, comment = null, includeInTotal = true, archived = false,
        )
        val ACCOUNT_C = AccountEntity(
            name = "QA-13 Gamma", type = AccountType.SAVINGS, initialBalance = 5_000,
            balanceUpdatedAt = 0, colorArgb = 0xFF9C27B0.toInt(), icon = "savings",
            bankName = "", comment = null, includeInTotal = false, archived = false,
        )
        val ACCOUNT_D = AccountEntity(
            name = "QA-13 Delta", type = AccountType.SAVINGS, initialBalance = 50_000,
            balanceUpdatedAt = 0, colorArgb = 0xFFFFC107.toInt(), icon = "savings",
            bankName = null, comment = null, includeInTotal = true, archived = true,
        )
        val ACCOUNT_E = AccountEntity(
            name = "QA-13 Epsilon", type = AccountType.CASH, initialBalance = 700,
            balanceUpdatedAt = 0, colorArgb = 0xFF607D8B.toInt(), icon = "payments",
            bankName = null, comment = null, includeInTotal = true, archived = false,
        )

        fun tx(
            title: String,
            amount: Long,
            type: TransactionType,
            status: TransactionStatus,
            accountId: Long,
            paidAt: Long?,
        ) = TransactionEntity(
            title = title, amount = amount, type = type, status = status,
            kind = TransactionKind.STANDARD, date = T, accountId = accountId,
            categoryId = NO_CATEGORY_ID, note = null, paidAt = paidAt,
            seriesId = null, seriesDate = null, isException = false,
            linkedGoalId = null, linkedLoanId = null, cardId = null, deleted = false,
        )
    }
}

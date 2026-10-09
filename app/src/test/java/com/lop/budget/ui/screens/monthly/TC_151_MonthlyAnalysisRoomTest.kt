package com.lop.budget.ui.screens.monthly

import android.app.Application
import android.database.Cursor
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lop.budget.data.local.LopDatabase
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.CategoryEntity
import com.lop.budget.data.local.entity.RecurringSeriesEntity
import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.data.repository.AccountRepository
import com.lop.budget.data.repository.CategoryRepository
import com.lop.budget.data.repository.SettingsRepository
import com.lop.budget.data.repository.TransactionRepository
import com.lop.budget.domain.CategoryBreakdown
import com.lop.budget.domain.model.AccountType
import com.lop.budget.domain.model.MissingDayBehavior
import com.lop.budget.domain.model.NO_ACCOUNT_ID
import com.lop.budget.domain.model.NO_CATEGORY_ID
import com.lop.budget.domain.model.RecurrenceFrequency
import com.lop.budget.domain.model.TransactionKind
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.domain.usecase.category.ObserveCategoriesUseCase
import com.lop.budget.domain.usecase.transaction.ObserveTransactionsUseCase
import com.lop.budget.domain.usecase.transaction.SearchTransactionsUseCase
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * TC-151 — Analyse Dépenses/Revenus : fenêtre réelle et consultation sans écriture (US LOP-40).
 *
 * ## Niveau et chaîne exercée
 * Intégration Room, hors interface. Aucune doublure sur la chaîne :
 * `MonthlyTransactionsViewModel.uiState` → `SearchTransactionsUseCase` → `ObserveTransactionsUseCase`
 *   → `TransactionRepository` / `AccountRepository` / `CategoryRepository` → vrais DAO
 *   → `TransactionDao.observeForMerge` → fusion lignes réelles / occurrences virtuelles
 *   → `BreakdownEngine` → `LopDatabase` en mémoire. `SettingsRepository` est réel.
 *
 * ## Traçabilité — cas → CA / invariant → production
 * ```
 * R-01 Dépenses  CA-02, CA-03, I-1, I-2  observeForMerge (kind, deleted, OR), mergeRealAndVirtual,
 *                                        setFilter, toggleCategory, setPeriod, BreakdownEngine
 * R-01 Revenus   CA-02, CA-03, I-1       idem, type INCOME, Non payé vide
 * R-02 ×3        CA-02, CA-05, I-1       AnalysisPeriod.range → bornes inclusives ; tri à date égale
 * ```
 *
 * ## Preuve d'absence d'écriture (I-1)
 * - **Photographie** de toutes les tables métier, lues dans `sqlite_master` (moins les tables internes
 *   SQLite et Room), toutes colonnes, lignes supprimées comprises, tuples triés. Référence prise après
 *   le semis, comparée après chaque étape. Le vieux SELECT de TC-99 n'est pas repris : il omet des
 *   colonnes ajoutées depuis.
 * - **Journal SQL** synchrone (`setQueryCallback`) : zéro `INSERT`/`UPDATE`/`DELETE`/`REPLACE` hors
 *   du journal d'invalidation de Room pendant les lectures. Il voit une écriture annulée aussitôt.
 *
 * ## Montage
 * Base neuve par cas, identifiants alloués par Room et gardés sous des noms. Exécuteur de requêtes à
 * fil unique possédé par le test (barrière de fin : deux tâches vides). Main = `StandardTestDispatcher`
 * ; Default et Room restent réels : les états sont collectés sans relâche et attendus en temps réel,
 * 10 s au plus, sans pause fixe ni réabonnement. Une seule observation par cas. Dérogation à
 * `app/src/test/AGENTS.md` §3 : le collecteur d'états, et lui seul, tourne sur un
 * `UnconfinedTestDispatcher`, pour qu'aucune émission ne se perde entre deux attentes. Horloge de recherche
 * figée au 15 mars 2026 12:00 UTC (la recherche reçoit ses bornes, l'horloge n'est pas lue). Fuseau
 * Europe/Paris et `Locale.FRANCE` forcés puis restaurés.
 *
 * **Contrôle des émissions.** Le premier état prêt pour la sélection visée porte l'oracle complet ;
 * on n'attend pas un « bon » état en sautant un mauvais. En fin de cas, tout autre état prêt de la
 * même sélection doit lui être identique (répétitions tolérées, variations non).
 *
 * ## Hypothèses et écarts à la fiche
 * - La devise n'est pas écrite (la fiche demandait d'écrire EUR puis de restaurer) : sous Windows, un
 *   DataStore de test n'accepte qu'une écriture par fichier. Sans préférence, la devise vaut EUR ;
 *   c'est vérifié en précondition de montage, comme TC-148.
 * - Couleur du groupe « Sans catégorie » non spécifiée par l'US : non vérifiée.
 * - Occurrence virtuelle : identifiant strictement négatif, unique, identique en revenant aux mêmes
 *   critères ; jamais recalculé par `RecurrenceEngine.calculateVirtualId`.
 *
 * ## ANO connues
 * - LOP-197 (Backlog) : fin de période à 23:59:59.000 → R-02 rouge attendu dans ses trois variantes
 *   (DERNIERE, datée .999, absente). L'assertion de cardinalité est faite en dernier.
 *
 * ## Résultats — 9 octobre 2026, production de `0f7419f`
 * 5 cas : R-01 Dépenses et Revenus verts du premier coup ; R-02 MOIS, LIBRE et JOUR rouges attendus,
 * stables sur trois passages. Les trois échouent sur la seule DERNIERE (datée E = …21:59:59.999Z) :
 * 4 lignes au lieu de 5, total −400 au lieu de −500 (LOP-197). AVANT/APRES absentes, tri à date égale
 * et tables intactes y sont vérifiés avant. Preuves de sensibilité, une mutation à la fois, retirée :
 * ```
 * M1  observeForMerge sans kind = 'STANDARD'          → R-01 Dépenses (ADJ visible, −96 000)
 * M2  kind rattaché à la seule branche série          → R-01 Dépenses (ADJ visible)
 * M3  slots supprimés non occupés dans la fusion      → R-01 Dépenses (virtuelle du 16 mars)
 * M4  lignes supprimées visibles                      → R-01 Dépenses (SUP visible, −87 000)
 * M5  écriture insérée puis effacée à la lecture      → les 5 cas, par le seul journal SQL
 * M6  écriture persistante à la lecture               → les 5 cas, par la photographie
 * M7  virtuelle avant les persistées à date égale     → R-02 ×3, sur le tri (avant LOP-197)
 * ```
 *
 * ## Hors périmètre
 * Nouvelle grille de récurrence, exceptions déplacées, CRUD, paiement, filtres tag/compte/texte,
 * détail, rendu (TC-152), performance. Les campagnes du socle (TC-98, TC-99, TC-140) ne sont pas
 * réécrites : le témoin de série prouve leur raccordement au nouvel écran.
 *
 * ## Exécution
 * `./gradlew :app:testDebugUnitTest --tests "*MonthlyAnalysisRoomTest"`
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class MonthlyAnalysisRoomTest {

    private val mainDispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val sqlLog = CopyOnWriteArrayList<String>()
    private lateinit var queryExecutor: ExecutorService

    private lateinit var db: LopDatabase
    private lateinit var accountRepo: AccountRepository
    private lateinit var categoryRepo: CategoryRepository
    private lateinit var transactionRepo: TransactionRepository
    private lateinit var settings: SettingsRepository

    private lateinit var previousTimeZone: TimeZone
    private lateinit var previousLocale: Locale

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
        categoryRepo = CategoryRepository(db.categoryDao())
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
    // R-01 — témoin métier : exclusions réelles, agrégats, aucune écriture
    // ==========================================================================================

    @Test
    fun `R-01 Depenses - given le temoin metier when on parcourt Tous Paye Non paye Tous CB CB retiree puis CB au 17 mars then ensembles et agregats exacts et tables intactes`() =
        runTest {
            val case = "R-01 Dépenses"
            assertCurrencyIsEur(case)
            val s = seedWitness()
            val reference = snapshot()
            val seedLogEnd = sqlLog.size
            // Témoin anti-oracle vacant : 1 compte, 3 catégories, 1 série, 7 transactions.
            assertEquals("$case — montage : le journal SQL ne voit pas le semis. ${writesSince(0)}", 12, writesSince(0).size)

            val vm = newVm("EXPENSE")
            val obs = observe(vm)
            val steps = mutableListOf<Pair<String, MonthlyTransactionsUiState>>()

            suspend fun step(name: String, filter: PaidFilter, cat: Long?, period: AnalysisPeriod = MARCH) {
                steps += name to obs.awaitReady(case, name, filter, cat, period)
                assertEquals("$case — I-1 : l'étape « $name » a modifié les tables.", reference, snapshot())
            }

            step("Tous initial", PaidFilter.ALL, null)
            vm.setFilter(PaidFilter.PAID); step("Payé", PaidFilter.PAID, null)
            vm.setFilter(PaidFilter.PLANNED); step("Non payé", PaidFilter.PLANNED, null)
            vm.setFilter(PaidFilter.ALL); step("Tous rétabli", PaidFilter.ALL, null)
            vm.toggleCategory(s.cb.id); step("CB active", PaidFilter.ALL, s.cb.id)
            vm.toggleCategory(s.cb.id); step("CB retirée", PaidFilter.ALL, null)
            vm.toggleCategory(s.cb.id); step("CB active bis", PaidFilter.ALL, s.cb.id)
            vm.setPeriod(MARCH_17, MARCH_17); step("CB active, 17 mars", PaidFilter.ALL, s.cb.id, AnalysisPeriod(MARCH_17, MARCH_17))
            barrier()

            val state = steps.toMap()
            val fallback = Group(NO_CATEGORY_ID, "Sans catégorie", null, 500, 1.0 / 12)
            val initial = StepExpect(
                rows = listOf(s.exc, VIRTUAL, s.x, s.n), total = -6_000,
                days = listOf(MARCH_15 to -1_500L, MARCH_17 to -1_000L, MARCH_18 to -3_000L, MARCH_19 to -500L),
                breakdown = listOf(g(s.ca, 3_000, 0.5), g(s.cb, 2_500, 5.0 / 12), fallback),
                choices = listOf(g(s.ca, 3_000, 0.5), g(s.cb, 2_500, 5.0 / 12), fallback),
            )
            assertStep(case, "Tous initial", state.getValue("Tous initial"), initial, s)
            assertStep(case, "Payé", state.getValue("Payé"), StepExpect(
                rows = listOf(s.exc, s.x), total = -4_500,
                days = listOf(MARCH_15 to -1_500L, MARCH_18 to -3_000L),
                breakdown = listOf(g(s.ca, 3_000, 2.0 / 3), g(s.cb, 1_500, 1.0 / 3)),
                choices = listOf(g(s.ca, 3_000, 2.0 / 3), g(s.cb, 1_500, 1.0 / 3)),
            ), s)
            assertStep(case, "Non payé", state.getValue("Non payé"), StepExpect(
                rows = listOf(VIRTUAL, s.n), total = -1_500,
                days = listOf(MARCH_17 to -1_000L, MARCH_19 to -500L),
                breakdown = listOf(g(s.cb, 1_000, 2.0 / 3), fallback.copy(share = 1.0 / 3)),
                choices = listOf(g(s.cb, 1_000, 2.0 / 3), fallback.copy(share = 1.0 / 3)),
            ), s)
            assertStep(case, "Tous rétabli", state.getValue("Tous rétabli"), initial, s)
            val cbActive = StepExpect(
                rows = listOf(s.exc, VIRTUAL), total = -2_500,
                days = listOf(MARCH_15 to -1_500L, MARCH_17 to -1_000L),
                breakdown = listOf(g(s.cb, 2_500, 1.0)),
                choices = initial.choices,
            )
            assertStep(case, "CB active", state.getValue("CB active"), cbActive, s)
            assertStep(case, "CB retirée", state.getValue("CB retirée"), initial, s)
            assertStep(case, "CB active bis", state.getValue("CB active bis"), cbActive, s)
            assertStep(case, "CB active, 17 mars", state.getValue("CB active, 17 mars"), StepExpect(
                rows = listOf(VIRTUAL), total = -1_000, days = listOf(MARCH_17 to -1_000L),
                breakdown = listOf(g(s.cb, 1_000, 1.0)), choices = listOf(g(s.cb, 1_000, 1.0)),
            ), s)

            // Occurrence virtuelle : même identifiant chaque fois qu'elle revient.
            val v17 = state.getValue("Tous initial").transactions.single { it.transaction.id < 0 }
            steps.forEach { (name, st) ->
                st.transactions.filter { it.transaction.id < 0 }.forEach {
                    assertEquals("$case — CA-02 : identifiant de V-17 instable à l'étape « $name ».", v17.transaction.id, it.transaction.id)
                }
            }
            assertRepeatsIdentical(case, obs)
            assertEquals("$case — I-1 : tables modifiées en fin de parcours.", reference, snapshot())
            assertEquals("$case — I-1 : écritures pendant les lectures.", emptyList<String>(), writesSince(seedLogEnd))
            assertWitnessRowsIntact(case, s)
        }

    @Test
    fun `R-01 Revenus - given le temoin metier when on ouvre Revenus puis Non paye then seule I a +4000 puis une analyse explicitement vide`() =
        runTest {
            val case = "R-01 Revenus"
            assertCurrencyIsEur(case)
            val s = seedWitness()
            val reference = snapshot()
            val seedLogEnd = sqlLog.size

            val vm = newVm("INCOME")
            val obs = observe(vm)
            val all = obs.awaitReady(case, "Tous", PaidFilter.ALL, null, MARCH, TransactionType.INCOME)
            assertEquals("$case — I-1 : l'ouverture a modifié les tables.", reference, snapshot())
            vm.setFilter(PaidFilter.PLANNED)
            val planned = obs.awaitReady(case, "Non payé", PaidFilter.PLANNED, null, MARCH, TransactionType.INCOME)
            barrier()

            assertStep(case, "Tous", all, StepExpect(
                rows = listOf(s.i), total = 4_000, days = listOf(MARCH_20 to 4_000L),
                breakdown = listOf(g(s.ci, 4_000, 1.0)), choices = listOf(g(s.ci, 4_000, 1.0)),
            ), s, TransactionType.INCOME)
            assertStep(case, "Non payé", planned, StepExpect(
                rows = emptyList(), total = 0, days = emptyList(), breakdown = emptyList(), choices = emptyList(),
            ), s, TransactionType.INCOME)
            assertRepeatsIdentical(case, obs)
            assertEquals("$case — I-1 : tables modifiées en fin de parcours.", reference, snapshot())
            assertEquals("$case — I-1 : écritures pendant les lectures.", emptyList<String>(), writesSince(seedLogEnd))
            assertWitnessRowsIntact(case, s)
        }

    /** Une ligne attendue : persistée (comparée entière) ou l'occurrence virtuelle V-17. */
    private sealed interface RowExpect
    private data class Persisted(val row: TransactionWithRelations) : RowExpect
    private object Virtual : RowExpect

    private val VIRTUAL: RowExpect = Virtual

    private data class StepExpect(
        val rows: List<RowExpect>,
        val total: Long,
        val days: List<Pair<LocalDate, Long>>,
        val breakdown: List<Group>,
        val choices: List<Group>,
    )

    private fun assertStep(
        case: String,
        step: String,
        state: MonthlyTransactionsUiState,
        exp: StepExpect,
        s: Witness,
        type: TransactionType = TransactionType.EXPENSE,
    ) {
        val where = "$case / $step"
        val ctx = "État : ${state.describe()}"
        assertEquals("$where — CA-02 : type. $ctx", type, state.type)
        assertEquals("$where — devise. $ctx", EUR, state.currency)
        val ids = state.transactions.map { it.transaction.id }
        listOf(s.adj to "ADJ (ajustement)", s.sup to "SUP (supprimée)", s.tomb to "TOMB (slot supprimé)").forEach { (row, label) ->
            assertTrue("$where — I-2 : $label ne doit jamais apparaître. $ctx", row.transaction.id !in ids)
        }
        val seriesRows = state.transactions.filter { it.transaction.seriesId == s.ser.id }
        assertTrue(
            "$where — I-2 : le slot supprimé du 16 mars réapparaît. $ctx",
            seriesRows.none { (it.transaction.seriesDate ?: it.transaction.date) == SLOT_16 },
        )
        assertEquals(
            "$where — I-2 : un slot de la série a deux représentations. $ctx",
            seriesRows.map { it.transaction.seriesDate ?: it.transaction.date }.distinct().size,
            seriesRows.size,
        )
        assertEquals("$where — CA-02 : nombre de lignes. $ctx", exp.rows.size, state.transactions.size)
        exp.rows.zip(state.transactions).forEachIndexed { index, (e, actual) ->
            when (e) {
                is Persisted -> assertEquals("$where — CA-02 : ligne $index (tous champs). $ctx", e.row, actual)
                Virtual -> {
                    assertTrue("$where — CA-02 : V-17 doit avoir un identifiant strictement négatif. $ctx", actual.transaction.id < 0)
                    assertEquals(
                        "$where — CA-02 : V-17, occurrence du 17 mars (champs hors identifiant). $ctx",
                        s.v17Template,
                        actual.copy(transaction = actual.transaction.copy(id = 0)),
                    )
                }
            }
        }
        assertEquals("$where — CA-02 : un identifiant apparaît deux fois. $ctx", ids.size, ids.toSet().size)
        assertEquals("$where — CA-03 : total signé. $ctx", exp.total, state.total)
        assertEquals("$where — groupes de jours (date, total signé). $ctx", exp.days, state.dayGroups.map { it.date to it.total })
        assertEquals("$where — groupes de jours : toutes les lignes, dans l'ordre. $ctx", state.transactions, state.dayGroups.flatMap { it.transactions })
        assertGroups(where, "CA-03 : répartition", exp.breakdown.sortedBy { it.categoryId }, state.breakdown.sortedBy { it.categoryId }, ctx)
        assertGroups(where, "CA-03/CA-09 : choix de base", exp.choices, state.categoryChoices, ctx)
    }

    // ==========================================================================================
    // R-02 — bornes inclusives et tri à date égale
    // ==========================================================================================

    @Test
    fun `R-02 MOIS - given les lignes aux bornes de mars when on ouvre l'analyse then premiere et derniere incluses avant et apres absentes et tri persistees puis virtuelle`() =
        runTest { boundsCase("R-02 MOIS", window = null, start = ms("2026-02-28T23:00:00.000Z"), end = ms("2026-03-31T21:59:59.999Z")) }

    @Test
    fun `R-02 LIBRE - given les lignes aux bornes du 30 au 31 mars when on applique cette periode then premiere et derniere incluses avant et apres absentes`() =
        runTest {
            boundsCase(
                "R-02 LIBRE", window = MARCH_30 to MARCH_31,
                start = ms("2026-03-29T22:00:00.000Z"), end = ms("2026-03-31T21:59:59.999Z"),
            )
        }

    @Test
    fun `R-02 JOUR - given les lignes aux bornes du seul 31 mars when on applique ce jour then premiere et derniere incluses avant et apres absentes`() =
        runTest {
            boundsCase(
                "R-02 JOUR", window = MARCH_31 to MARCH_31,
                start = ms("2026-03-30T22:00:00.000Z"), end = ms("2026-03-31T21:59:59.999Z"),
            )
        }

    /**
     * Bornes locales déclarées par la variante (jamais tirées d'une fixture partagée) : elles
     * conditionnent la cardinalité attendue. La cardinalité est vérifiée en dernier, pour que le
     * défaut .999 connu (LOP-197) ne masque ni l'absence d'AVANT/APRES ni le tri.
     */
    private suspend fun TestScope.boundsCase(case: String, window: Pair<LocalDate, LocalDate>?, start: Long, end: Long) {
        assertCurrencyIsEur(case)
        val cpt = db.accountDao().upsert(CPT)
        val catId = db.categoryDao().upsert(BORNES)
        val cat = BORNES.copy(id = catId)
        val account = CPT.copy(id = cpt)
        fun tx(title: String, amount: Long, date: Long) = TransactionEntity(
            title = title, amount = amount, type = TransactionType.EXPENSE, status = TransactionStatus.PAID,
            kind = TransactionKind.STANDARD, date = date, accountId = cpt, categoryId = catId, note = null,
            paidAt = date, seriesId = null, seriesDate = null, isException = false,
            linkedGoalId = null, linkedLoanId = null, cardId = null, deleted = false,
        )
        val txDao = db.transactionDao()
        val avant = tx("QA-40 Avant", 101, start - 1)
        val premiere = tx("QA-40 Première", 100, start)
        val tie1 = tx("QA-40 Tie 1", 100, TIE_AT)
        val tie2 = tx("QA-40 Tie 2", 100, TIE_AT)
        val derniere = tx("QA-40 Dernière", 100, end)
        val apres = tx("QA-40 Après", 101, end + 1)
        val ids = listOf(avant, premiere, tie1, tie2, derniere, apres).map { txDao.upsert(it) }
        val (avantId, premiereId, tie1Id, tie2Id, derniereId) = ids
        val apresId = ids[5]
        val seriesId = db.recurringSeriesDao().upsertSeries(
            RecurringSeriesEntity(
                title = "QA-40 Tie virtuelle", amount = 100, type = TransactionType.EXPENSE, categoryId = catId,
                accountId = cpt, frequency = RecurrenceFrequency.DAILY, interval = 1, startDate = TIE_AT,
                endDate = TIE_AT, maxOccurrences = null, daysOfWeek = null, isCancelled = false, note = null,
                linkedGoalId = null, linkedLoanId = null, missingDayBehavior = MissingDayBehavior.LAST_VALID_DAY,
                anchorDayOfMonth = null,
            )
        )
        val reference = snapshot()
        val seedLogEnd = sqlLog.size
        fun row(e: TransactionEntity, id: Long) = TransactionWithRelations(e.copy(id = id), cat, account, emptyList())

        val vm = newVm("EXPENSE")
        val obs = observe(vm)
        val period = window?.let { AnalysisPeriod(it.first, it.second) } ?: MARCH
        if (window != null) {
            // Synchronisation par la seule identité de TIE-1, puis nouvelle période sans réabonnement.
            obs.awaitState(case, "ouverture (TIE-1 reçue)") { st -> st.isReady() && st.transactions.any { it.transaction.id == tie1Id } }
            vm.setPeriod(window.first, window.second)
        }
        val state = obs.awaitReady(case, "fenêtre", PaidFilter.ALL, null, period)
        barrier()

        val ctx = "État : ${state.describe()}. Bornes attendues ${Instant.ofEpochMilli(start)} → ${Instant.ofEpochMilli(end)}"
        val got = state.transactions.map { it.transaction.id }
        assertTrue("$case — CA-02 : AVANT (S − 1 ms) doit être absente. $ctx", avantId !in got)
        assertTrue("$case — CA-02 : APRES (E + 1 ms) doit être absente. $ctx", apresId !in got)
        val virtual = state.transactions.filter { it.transaction.id < 0 }
        assertEquals("$case — CA-02 : exactement une occurrence virtuelle. $ctx", 1, virtual.size)
        assertEquals(
            "$case — CA-02 : occurrence virtuelle de SER-TIE (hors identifiant). $ctx",
            TransactionWithRelations(
                TransactionEntity(
                    id = 0, title = "QA-40 Tie virtuelle", amount = 100, type = TransactionType.EXPENSE,
                    status = TransactionStatus.PLANNED, kind = TransactionKind.STANDARD, date = TIE_AT,
                    accountId = cpt, categoryId = catId, note = null, paidAt = null, seriesId = seriesId,
                    seriesDate = TIE_AT, isException = false, linkedGoalId = null, linkedLoanId = null,
                    cardId = null, deleted = false,
                ),
                cat, account, emptyList(),
            ),
            virtual.single().let { it.copy(transaction = it.transaction.copy(id = 0)) },
        )
        assertTrue("$case — CA-02 : ordre des identifiants TIE-1 < TIE-2. $ctx", tie1Id < tie2Id)
        assertEquals(
            "$case — CA-02 : à date égale, les persistées par id croissant précèdent la virtuelle. $ctx",
            listOf(tie1Id, tie2Id, virtual.single().transaction.id),
            got.filter { it in setOf(tie1Id, tie2Id) || it < 0 },
        )
        assertEquals("$case — I-1 : la lecture a modifié les tables.", reference, snapshot())
        assertEquals("$case — I-1 : écritures pendant les lectures.", emptyList<String>(), writesSince(seedLogEnd))
        assertRepeatsIdentical(case, obs)
        // En dernier : la ligne datée E (23:59:59.999 locale) — LOP-197.
        assertEquals(
            "$case — CA-02 (LOP-197) : exactement PREMIERE, TIE-1, TIE-2, la virtuelle, DERNIERE ; la " +
                "dernière est datée ${Instant.ofEpochMilli(end)}. $ctx",
            listOf(premiereId, tie1Id, tie2Id, virtual.single().transaction.id, derniereId),
            got,
        )
        assertEquals("$case — CA-02 : lignes persistées entières. $ctx",
            listOf(row(premiere, premiereId), row(tie1, tie1Id), row(tie2, tie2Id), row(derniere, derniereId)),
            state.transactions.filter { it.transaction.id > 0 })
        assertEquals("$case — CA-03 : total, bornes incluses. $ctx", -500L, state.total)
    }

    // ==========================================================================================
    // Jeu de données R-01
    // ==========================================================================================

    private class Witness(
        val cpt: AccountEntity,
        val ca: CategoryEntity,
        val cb: CategoryEntity,
        val ci: CategoryEntity,
        val ser: RecurringSeriesEntity,
        val exc: Persisted,
        val tomb: TransactionWithRelations,
        val x: Persisted,
        val n: Persisted,
        val i: Persisted,
        val adj: TransactionWithRelations,
        val sup: TransactionWithRelations,
        val v17Template: TransactionWithRelations,
    )

    private suspend fun seedWitness(): Witness {
        val cpt = CPT.copy(id = db.accountDao().upsert(CPT))
        val ca = CA.copy(id = db.categoryDao().upsert(CA))
        val cb = CB.copy(id = db.categoryDao().upsert(CB))
        val ci = CI.copy(id = db.categoryDao().upsert(CI))
        val serEntity = RecurringSeriesEntity(
            title = "QA-40 Échéance Room", amount = 1_000, type = TransactionType.EXPENSE, categoryId = cb.id,
            accountId = cpt.id, frequency = RecurrenceFrequency.DAILY, interval = 1, startDate = SLOT_15,
            endDate = SLOT_17, maxOccurrences = null, daysOfWeek = null, isCancelled = false, note = null,
            linkedGoalId = null, linkedLoanId = null, missingDayBehavior = MissingDayBehavior.LAST_VALID_DAY,
            anchorDayOfMonth = null,
        )
        val ser = serEntity.copy(id = db.recurringSeriesDao().upsertSeries(serEntity))

        val txDao = db.transactionDao()
        suspend fun insert(e: TransactionEntity, category: CategoryEntity?, account: AccountEntity?) =
            TransactionWithRelations(e.copy(id = txDao.upsert(e)), category, account, emptyList())

        fun tx(
            title: String, amount: Long, type: TransactionType, status: TransactionStatus, date: Long,
            categoryId: Long, accountId: Long,
        ) = TransactionEntity(
            title = title, amount = amount, type = type, status = status, kind = TransactionKind.STANDARD,
            date = date, accountId = accountId, categoryId = categoryId, note = null,
            paidAt = if (status == TransactionStatus.PAID) date else null, seriesId = null, seriesDate = null,
            isException = false, linkedGoalId = null, linkedLoanId = null, cardId = null, deleted = false,
        )
        val e = TransactionType.EXPENSE
        val paid = TransactionStatus.PAID

        val exc = insert(
            tx("QA-40 Échéance Room", 1_500, e, paid, SLOT_15, cb.id, cpt.id)
                .copy(seriesId = ser.id, seriesDate = SLOT_15, isException = true),
            cb, cpt,
        )
        val tomb = insert(
            tx("QA-40 Échéance Room", 1_000, e, paid, SLOT_16, cb.id, cpt.id)
                .copy(seriesId = ser.id, seriesDate = SLOT_16, isException = true, deleted = true),
            cb, cpt,
        )
        val x = insert(tx("QA-40 Ponctuelle Room", 3_000, e, paid, ms("2026-03-18T08:00:00Z"), ca.id, cpt.id), ca, cpt)
        val n = insert(
            tx("QA-40 Sans compte ni catégorie Room", 500, e, TransactionStatus.PLANNED, ms("2026-03-19T08:00:00Z"), NO_CATEGORY_ID, NO_ACCOUNT_ID),
            null, null,
        )
        val i = insert(tx("QA-40 Revenu Room", 4_000, TransactionType.INCOME, paid, ms("2026-03-20T08:00:00Z"), ci.id, cpt.id), ci, cpt)
        val adj = insert(
            tx("QA-40 Ajustement Room", 90_000, e, paid, SLOT_15, cb.id, cpt.id).copy(kind = TransactionKind.BALANCE_ADJUSTMENT),
            cb, cpt,
        )
        val sup = insert(
            tx("QA-40 Supprimée Room", 80_000, e, paid, ms("2026-03-20T08:00:00Z"), cb.id, cpt.id).copy(deleted = true),
            cb, cpt,
        )
        val v17Template = TransactionWithRelations(
            TransactionEntity(
                id = 0, title = "QA-40 Échéance Room", amount = 1_000, type = e, status = TransactionStatus.PLANNED,
                kind = TransactionKind.STANDARD, date = SLOT_17, accountId = cpt.id, categoryId = cb.id, note = null,
                paidAt = null, seriesId = ser.id, seriesDate = SLOT_17, isException = false, linkedGoalId = null,
                linkedLoanId = null, cardId = null, deleted = false,
            ),
            cb, cpt, emptyList(),
        )
        return Witness(cpt, ca, cb, ci, ser, Persisted(exc), tomb, Persisted(x), Persisted(n), Persisted(i), adj, sup, v17Template)
    }

    /** SUP, TOMB et ADJ restent présentes et intactes : le masquage est une lecture, pas un effacement. */
    private fun assertWitnessRowsIntact(case: String, s: Witness) {
        val rows = rowsOf("transactions").associateBy { it["id"] }
        listOf(s.sup to "SUP", s.tomb to "TOMB", s.adj to "ADJ").forEach { (twr, label) ->
            val stored = rows[twr.transaction.id]
            assertTrue("$case — I-1 : $label n'est plus en base.", stored != null)
            assertEquals("$case — I-1 : $label, colonne deleted.", if (twr.transaction.deleted) 1L else 0L, stored!!["deleted"])
            assertEquals("$case — I-1 : $label, colonne kind.", twr.transaction.kind.name, stored["kind"])
            assertEquals("$case — I-1 : $label, montant.", twr.transaction.amount, stored["amount"])
        }
    }

    // ==========================================================================================
    // Modèle, observation, instruments
    // ==========================================================================================

    private fun newVm(type: String): MonthlyTransactionsViewModel {
        val handle = SavedStateHandle(mapOf("type" to type, "ym" to "2026-03", "mode" to "ANALYTICS"))
        val search = SearchTransactionsUseCase(ObserveTransactionsUseCase(transactionRepo, accountRepo, categoryRepo), CLOCK)
        val factory = viewModelFactory {
            initializer { MonthlyTransactionsViewModel(handle, accountRepo, ObserveCategoriesUseCase(categoryRepo), search, settings) }
        }
        return ViewModelProvider(store, factory)[MonthlyTransactionsViewModel::class.java]
    }

    private class Observation {
        val trace = CopyOnWriteArrayList<MonthlyTransactionsUiState>()
        val states = Channel<MonthlyTransactionsUiState>(Channel.UNLIMITED)
    }

    private fun TestScope.observe(vm: MonthlyTransactionsViewModel): Observation {
        val obs = Observation()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.uiState.collect {
                obs.trace += it
                obs.states.send(it)
            }
        }
        return obs
    }

    private suspend fun Observation.awaitState(
        case: String,
        what: String,
        accept: (MonthlyTransactionsUiState) -> Boolean,
    ): MonthlyTransactionsUiState {
        while (true) {
            val next = withContext(Dispatchers.Default) { withTimeoutOrNull(TIMEOUT_MS) { states.receive() } }
                ?: throw AssertionError(
                    "$case — $what : état attendu non atteint en ${TIMEOUT_MS / 1000} s. Dernier état : " +
                        "${trace.lastOrNull()?.describe()}. Tables : ${snapshot()}"
                )
            if (accept(next)) return next
        }
    }

    /** Le **premier** état prêt pour la sélection visée : c'est lui qui porte l'oracle. */
    private suspend fun Observation.awaitReady(
        case: String,
        what: String,
        filter: PaidFilter,
        categoryId: Long?,
        period: AnalysisPeriod,
        type: TransactionType = TransactionType.EXPENSE,
    ) = awaitState(case, what) {
        it.isReady() && it.type == type && it.filter == filter && it.selectedCategoryId == categoryId && it.period == period
    }

    private fun MonthlyTransactionsUiState.isReady() = !isRefreshing && !loadFailed

    /** Tout état prêt d'une même sélection est identique au premier : répétitions oui, variations non. */
    private fun assertRepeatsIdentical(case: String, obs: Observation) {
        obs.trace.filter { it.isReady() }
            .groupBy { listOf(it.type, it.filter, it.selectedCategoryId, it.period) }
            .forEach { (selection, states) ->
                val distinct = states.distinct()
                assertEquals(
                    "$case — I-3 : la sélection $selection a présenté plusieurs résultats prêts différents : " +
                        distinct.map { it.describe() },
                    1,
                    distinct.size,
                )
            }
    }

    private suspend fun assertCurrencyIsEur(case: String) {
        val currency = settings.currency.first()
        if (currency != EUR) {
            throw AssertionError("$case — montage : la devise par défaut devait être EUR, lu « $currency ». Échec de montage, pas une preuve métier.")
        }
    }

    /** Les relectures Room passent par l'exécuteur à fil unique du test ; puis Main est vidé. */
    private suspend fun TestScope.barrier() {
        repeat(2) { withContext(Dispatchers.IO) { queryExecutor.submit {}.get() } }
        advanceUntilIdle()
        runCurrent()
    }

    private fun writesSince(index: Int): List<String> =
        sqlLog.drop(index).filter { WRITE.containsMatchIn(it) && !it.contains("room_table_modification_log") }

    /** Toutes les tables métier, toutes colonnes, lignes triées : la photographie de I-1. */
    private fun snapshot(): Map<String, List<String>> = businessTables().associateWith { table ->
        rowsOf(table).map { it.toString() }.sorted()
    }

    private fun businessTables(): List<String> = buildList {
        db.openHelper.readableDatabase.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' " +
                "AND name NOT IN ('room_master_table', 'android_metadata') ORDER BY name"
        ).use { while (it.moveToNext()) add(it.getString(0)) }
    }

    private fun rowsOf(table: String): List<Map<String, Any?>> = buildList {
        db.openHelper.readableDatabase.query("SELECT * FROM `$table`").use { cursor ->
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

    /** Groupe attendu. [color] nul : couleur non spécifiée par l'US (« Sans catégorie »). */
    private data class Group(val categoryId: Long?, val name: String, val color: Int?, val total: Long, val share: Double)

    private fun g(category: CategoryEntity, total: Long, share: Double) =
        Group(category.id, category.name, category.colorArgb, total, share)

    private fun assertGroups(where: String, what: String, expected: List<Group>, actual: List<CategoryBreakdown>, ctx: String) {
        assertEquals("$where — $what : identifiants, dans l'ordre. $ctx", expected.map { it.categoryId }, actual.map { it.categoryId })
        expected.zip(actual).forEach { (e, a) ->
            assertEquals("$where — $what : nom du groupe ${e.categoryId}. $ctx", e.name, a.name)
            e.color?.let { assertEquals("$where — $what : couleur du groupe ${e.categoryId}. $ctx", it, a.colorArgb) }
            assertEquals("$where — $what : montant du groupe ${e.categoryId}. $ctx", e.total, a.total)
            assertEquals("$where — $what : part du groupe ${e.categoryId}. $ctx", e.share, a.share, 1e-12)
        }
    }

    private fun MonthlyTransactionsUiState.describe() =
        "{période=${period.start}..${period.end}, type=$type, statut=$filter, cat=$selectedCategoryId, " +
            "total=$total, lignes=${transactions.map { "${it.transaction.id}:${it.transaction.title}" }}, " +
            "anneau=${breakdown.map { "${it.categoryId}:${it.total}" }}, " +
            "choix=${categoryChoices.map { "${it.categoryId}:${it.total}" }}, " +
            "actualisation=$isRefreshing, échec=$loadFailed}"

    private companion object {
        const val EUR = "EUR"
        const val ZONE = "Europe/Paris"

        /** Délai d'échec de chaque attente, en temps réel ; propre à cette classe. */
        const val TIMEOUT_MS = 10_000L

        fun ms(instant: String) = Instant.parse(instant).toEpochMilli()

        val CLOCK: Clock = Clock.fixed(Instant.parse("2026-03-15T12:00:00Z"), ZoneId.of(ZONE))

        val MARCH = AnalysisPeriod(LocalDate.parse("2026-03-01"), LocalDate.parse("2026-03-31"))
        val MARCH_15: LocalDate = LocalDate.parse("2026-03-15")
        val MARCH_17: LocalDate = LocalDate.parse("2026-03-17")
        val MARCH_18: LocalDate = LocalDate.parse("2026-03-18")
        val MARCH_19: LocalDate = LocalDate.parse("2026-03-19")
        val MARCH_20: LocalDate = LocalDate.parse("2026-03-20")
        val MARCH_30: LocalDate = LocalDate.parse("2026-03-30")
        val MARCH_31: LocalDate = LocalDate.parse("2026-03-31")

        // Trois slots de SER, 09:00 locale (UTC+1 avant le 29 mars).
        val SLOT_15 = ms("2026-03-15T08:00:00Z")
        val SLOT_16 = ms("2026-03-16T08:00:00Z")
        val SLOT_17 = ms("2026-03-17T08:00:00Z")

        /** 31 mars 09:00 locale (UTC+2 après le 29 mars) : TIE-1, TIE-2 et la virtuelle de SER-TIE. */
        val TIE_AT = ms("2026-03-31T07:00:00Z")

        val WRITE = Regex("""^\s*(INSERT|UPDATE|DELETE|REPLACE)\b""", RegexOption.IGNORE_CASE)

        val CPT = AccountEntity(
            name = "QA-40 Compte Room", type = AccountType.CHECKING, initialBalance = 0, balanceUpdatedAt = 0,
            colorArgb = 0xFF1565C0.toInt(), icon = "wallet", archived = false,
        )
        val CA = CategoryEntity(name = "QA-40 A Room", type = TransactionType.EXPENSE, colorArgb = 0xFF1565C0.toInt(), icon = "wallet", parentCategoryId = null)
        val CB = CategoryEntity(name = "QA-40 B Room", type = TransactionType.EXPENSE, colorArgb = 0xFFE64A19.toInt(), icon = "cart", parentCategoryId = null)
        val CI = CategoryEntity(name = "QA-40 Revenu Room", type = TransactionType.INCOME, colorArgb = 0xFF00897B.toInt(), icon = "wallet", parentCategoryId = null)
        val BORNES = CategoryEntity(name = "QA-40 Bornes", type = TransactionType.EXPENSE, colorArgb = 0xFF1565C0.toInt(), icon = "wallet", parentCategoryId = null)
    }
}

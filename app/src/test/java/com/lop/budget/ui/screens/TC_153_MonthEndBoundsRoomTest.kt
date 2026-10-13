package com.lop.budget.ui.screens

import android.app.Application
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
import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.data.repository.AccountRepository
import com.lop.budget.data.repository.CategoryRepository
import com.lop.budget.data.repository.NotificationDetectionRepository
import com.lop.budget.data.repository.SettingsRepository
import com.lop.budget.data.repository.TransactionRepository
import com.lop.budget.domain.CategoryBreakdown
import com.lop.budget.domain.model.AccountType
import com.lop.budget.domain.model.TransactionKind
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.domain.usecase.account.GetAccountBalancesUseCase
import com.lop.budget.domain.usecase.insight.ObserveHomeSummaryUseCase
import com.lop.budget.domain.usecase.insight.ObserveMonthlyAnalyticsUseCase
import com.lop.budget.domain.usecase.transaction.ObserveTransactionsUseCase
import com.lop.budget.ui.screens.analytics.AnalyticsUiState
import com.lop.budget.ui.screens.analytics.AnalyticsViewModel
import com.lop.budget.ui.screens.home.HomeUiState
import com.lop.budget.ui.screens.home.HomeViewModel
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * TC-153 — Accueil et Analyses : le mois compte sa dernière milliseconde, pas la première du
 * suivant (anomalie LOP-207).
 *
 * ## Niveau et chaîne exercée
 * Intégration Room, hors interface. La fonction fautive, `YearMonth.range`, est privée dans chaque
 * modèle de vue : seul leur état l'atteint. Aucune doublure sur la chaîne :
 * `HomeViewModel.uiState` → `ObserveHomeSummaryUseCase` ; `AnalyticsViewModel.uiState` →
 * `ObserveMonthlyAnalyticsUseCase` ; puis `ObserveTransactionsUseCase` → repositories → vrais DAO
 * → `TransactionDao.observeForMerge` → `BreakdownEngine` → `LopDatabase` en mémoire.
 * `GetAccountBalancesUseCase`, `NotificationDetectionRepository` et `SettingsRepository` sont réels.
 *
 * ## Traçabilité — cas → contrat → production
 * ```
 * B-01 Accueil, mars          fin à .999 incluse, 1er avril exclu,   HomeViewModel.range (mars et février)
 *                             fin de février incluse                 → ObserveHomeSummaryUseCase
 * B-02 Analyses, Dépenses     idem, total et répartition             AnalyticsViewModel.range
 * B-03 Analyses, Revenus      idem                                   AnalyticsViewModel.range
 * ```
 *
 * ## Données
 * Mars 2026 commence en UTC+1 et finit en UTC+2 (heure d'été le 29) : un décalage fixe sur le mois
 * se tromperait d'une heure à la fin. Montants tous distincts : chaque somme dit quelles lignes ont
 * été comptées. Les attendus sont écrits en clair.
 *
 * ## Montage
 * Base neuve par cas. Main = `StandardTestDispatcher` ; Default et Room restent réels, attente en
 * temps réel de 10 s au plus, sans pause fixe ni réabonnement. Dérogation à `app/src/test/AGENTS.md`
 * §3, comme TC-151 : le collecteur d'états, et lui seul, tourne sur `UnconfinedTestDispatcher`, pour
 * qu'aucune émission ne se perde. Ces états n'ont pas d'indicateur de chargement : leur valeur
 * initiale porte le mois réel, donc le premier état de mars 2026 est le premier résultat lu. Il porte
 * l'oracle ; tout état de mars reçu ensuite doit lui être identique. L'accueil reçoit son mois
 * **avant** d'être observé : sinon un état transitoire peut associer mars au résumé du mois réel.
 * Fuseau Europe/Paris et `Locale.FRANCE` forcés puis restaurés. La devise n'est pas écrite
 * (DataStore sous Windows, voir TC-151) : EUR est vérifié en précondition.
 *
 * ## Résultats — 13 octobre 2026, production de `0b2e1cf`
 * 3 cas, 3 verts : LOP-207 était déjà corrigée. Preuve RED par la remise temporaire de l'ancien code,
 * un modèle de vue à la fois, retirée aussitôt :
 * ```
 * M1  HomeViewModel, fin à atTime(23, 59, 59)               → B-01 (dépenses 400, revenus 0, février 0)
 * M2  AnalyticsViewModel, fin à atTime(23, 59, 59)          → B-02 (total 400), B-03 (total 0)
 * M3  HomeViewModel, fin au 1er instant du mois suivant     → B-01 (dépenses 2800 : AVR-DEB-D comptée)
 * M4  AnalyticsViewModel, fin au 1er instant du mois suivant → B-02 (2800), B-03 (9600)
 * ```
 * M1 et M2 sont exactement le code d'avant le correctif : leur rouge est la preuve RED de LOP-207.
 * Après retrait : 3 verts ; suite JVM complète 749 tests, 0 échec.
 *
 * ## Hors périmètre
 * Périodes libres et jour seul de l'analyse mensuelle (TC-149, TC-151, LOP-197), navigation entre
 * mois, rendu, filtre par compte, ajustements de solde (TC-113), récurrences.
 *
 * ## Exécution
 * `./gradlew :app:testDebugUnitTest --tests "*MonthEndBoundsRoomTest"`
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class MonthEndBoundsRoomTest {

    private val mainDispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()

    private lateinit var db: LopDatabase
    private lateinit var accountRepo: AccountRepository
    private lateinit var transactionRepo: TransactionRepository
    private lateinit var observeTransactions: ObserveTransactionsUseCase
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

        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), LopDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        accountRepo = AccountRepository(db.accountDao())
        transactionRepo = TransactionRepository(db.transactionDao(), db.recurringSeriesDao())
        observeTransactions = ObserveTransactionsUseCase(transactionRepo, accountRepo, CategoryRepository(db.categoryDao()))
        settings = SettingsRepository(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        store.clear()
        db.close()
        Dispatchers.resetMain()
        TimeZone.setDefault(previousTimeZone)
        Locale.setDefault(previousLocale)
    }

    // ==========================================================================================
    // B-01 — accueil
    // ==========================================================================================

    @Test
    fun `B-01 Accueil - given les lignes aux bornes de mars when on ouvre l'accueil sur mars then derniere milliseconde de mars et de fevrier comptees et 1er avril exclu`() =
        runTest {
            val case = "B-01 Accueil, mars"
            assertCurrencyIsEur(case)
            val s = seed()
            val vm = homeVm()
            vm.setMonth(MARCH)
            val obs = observe(vm.uiState)

            val state = obs.await(case, { it.describe() }) { it.month == MARCH }
            val ctx = "État : ${state.describe()}"

            assertEquals(
                "$case — LOP-207 : carte Dépenses = MARS-DEB 400 + MARS-FIN-D 800 (31 mars 23:59:59.999), " +
                    "sans AVR-DEB-D 1600 (1er avril 00:00). $ctx",
                1_200L, state.monthExpense,
            )
            assertEquals(
                "$case — LOP-207 : carte Revenus = MARS-FIN-R 3200 (31 mars 23:59:59.999), sans AVR-DEB-R 6400. $ctx",
                3_200L, state.monthIncome,
            )
            assertEquals(
                "$case — LOP-207 : mois précédent = FEV-FIN 200 (28 février 23:59:59.999), sans MARS-DEB. $ctx",
                200L, state.previousPeriodExpense,
            )
            assertEquals("$case — LOP-207 : solde projeté 3200 − 1200, rien de prévu. $ctx", 2_000L, state.projectedBalance)
            assertEquals(
                "$case — LOP-207 : groupes par jour (date, total signé, lignes). $ctx",
                listOf(
                    Day(MARCH_31, 2_400L, setOf(s.marsFinD, s.marsFinR)),
                    Day(MARCH_1, -400L, setOf(s.marsDeb)),
                ),
                state.dayGroups.map { g -> Day(g.date, g.total, g.transactions.map { it.transaction.id }.toSet()) },
            )
            obs.assertRepeatsIdentical(case, { it.describe() }) { it.month == MARCH }
        }

    // ==========================================================================================
    // B-02, B-03 — écran Analyses
    // ==========================================================================================

    @Test
    fun `B-02 Analyses Depenses - given les lignes aux bornes de mars when on ouvre les Analyses de mars then derniere milliseconde comptee et 1er avril exclu`() =
        runTest {
            val case = "B-02 Analyses, Dépenses, mars"
            assertCurrencyIsEur(case)
            val s = seed()
            val state = analyticsCase(case, TransactionType.EXPENSE)
            val ctx = "État : ${state.describe()}"

            assertEquals(
                "$case — LOP-207 : total = MARS-DEB 400 + MARS-FIN-D 800, sans FEV-FIN ni AVR-DEB-D. $ctx",
                1_200L, state.total,
            )
            assertGroups(case, listOf(Group(s.cb, 800L, 2.0 / 3), Group(s.ca, 400L, 1.0 / 3)), state.breakdown, ctx)
        }

    @Test
    fun `B-03 Analyses Revenus - given les lignes aux bornes de mars when on ouvre les Analyses de mars then derniere milliseconde comptee et 1er avril exclu`() =
        runTest {
            val case = "B-03 Analyses, Revenus, mars"
            assertCurrencyIsEur(case)
            val s = seed()
            val state = analyticsCase(case, TransactionType.INCOME)
            val ctx = "État : ${state.describe()}"

            assertEquals("$case — LOP-207 : total = MARS-FIN-R 3200, sans AVR-DEB-R 6400. $ctx", 3_200L, state.total)
            assertGroups(case, listOf(Group(s.ci, 3_200L, 1.0)), state.breakdown, ctx)
        }

    private suspend fun TestScope.analyticsCase(case: String, type: TransactionType): AnalyticsUiState {
        val vm = analyticsVm(type)
        val obs = observe(vm.uiState)
        val state = obs.await(case, { it.describe() }) { it.month == MARCH }
        assertEquals("$case — montage : type de l'écran.", type, state.type)
        obs.assertRepeatsIdentical(case, { it.describe() }) { it.month == MARCH }
        return state
    }

    // ==========================================================================================
    // Jeu de données
    // ==========================================================================================

    private class Seed(
        val ca: CategoryEntity,
        val cb: CategoryEntity,
        val ci: CategoryEntity,
        val marsDeb: Long,
        val marsFinD: Long,
        val marsFinR: Long,
    )

    private suspend fun seed(): Seed {
        val cpt = db.accountDao().upsert(CPT)
        val ca = CA.copy(id = db.categoryDao().upsert(CA))
        val cb = CB.copy(id = db.categoryDao().upsert(CB))
        val ci = CI.copy(id = db.categoryDao().upsert(CI))
        val txDao = db.transactionDao()
        suspend fun paid(title: String, type: TransactionType, category: CategoryEntity, amount: Long, date: Long) =
            txDao.upsert(
                TransactionEntity(
                    title = title, amount = amount, type = type, status = TransactionStatus.PAID,
                    kind = TransactionKind.STANDARD, date = date, accountId = cpt, categoryId = category.id, note = null,
                    paidAt = date, seriesId = null, seriesDate = null, isException = false,
                    linkedGoalId = null, linkedLoanId = null, cardId = null, deleted = false,
                )
            )
        val e = TransactionType.EXPENSE
        val i = TransactionType.INCOME
        paid("QA-207 FEV-FIN", e, cb, 200, FEB_LAST_MS)
        val marsDeb = paid("QA-207 MARS-DEB", e, ca, 400, MARCH_FIRST_MS)
        val marsFinD = paid("QA-207 MARS-FIN-D", e, cb, 800, MARCH_LAST_MS)
        val marsFinR = paid("QA-207 MARS-FIN-R", i, ci, 3_200, MARCH_LAST_MS)
        paid("QA-207 AVR-DEB-D", e, ca, 1_600, APRIL_FIRST_MS)
        paid("QA-207 AVR-DEB-R", i, ci, 6_400, APRIL_FIRST_MS)
        return Seed(ca, cb, ci, marsDeb, marsFinD, marsFinR)
    }

    // ==========================================================================================
    // Modèles, observation
    // ==========================================================================================

    private fun homeVm(): HomeViewModel {
        val factory = viewModelFactory {
            initializer {
                HomeViewModel(
                    ObserveHomeSummaryUseCase(observeTransactions, CLOCK),
                    GetAccountBalancesUseCase(accountRepo, transactionRepo),
                    NotificationDetectionRepository(db.detectedTransactionProposalDao()),
                    settings,
                )
            }
        }
        return ViewModelProvider(store, factory)[HomeViewModel::class.java]
    }

    private fun analyticsVm(type: TransactionType): AnalyticsViewModel {
        val handle = SavedStateHandle(mapOf("type" to type.name, "ym" to MARCH.toString()))
        val factory = viewModelFactory {
            initializer { AnalyticsViewModel(handle, ObserveMonthlyAnalyticsUseCase(observeTransactions), settings) }
        }
        return ViewModelProvider(store, factory)[AnalyticsViewModel::class.java]
    }

    private class Observation<T> {
        val trace = CopyOnWriteArrayList<T>()
        val states = Channel<T>(Channel.UNLIMITED)
    }

    private fun <T> TestScope.observe(flow: StateFlow<T>): Observation<T> {
        val obs = Observation<T>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            flow.collect {
                obs.trace += it
                obs.states.send(it)
            }
        }
        return obs
    }

    /** Le **premier** état accepté : c'est lui qui porte l'oracle, on ne saute pas un mauvais état. */
    private suspend fun <T> Observation<T>.await(case: String, describe: (T) -> String, accept: (T) -> Boolean): T {
        while (true) {
            val next = withContext(Dispatchers.Default) { withTimeoutOrNull(TIMEOUT_MS) { states.receive() } }
                ?: throw AssertionError(
                    "$case — LOP-207 : aucun état de mars 2026 en ${TIMEOUT_MS / 1000} s. Dernier état : " +
                        "${trace.lastOrNull()?.let(describe)}"
                )
            if (accept(next)) return next
        }
    }

    /** Répétitions tolérées, variations non. */
    private fun <T> Observation<T>.assertRepeatsIdentical(case: String, describe: (T) -> String, selection: (T) -> Boolean) {
        val distinct = trace.filter(selection).distinct()
        assertEquals("$case — mars a présenté plusieurs résultats différents : ${distinct.map(describe)}", 1, distinct.size)
    }

    private suspend fun assertCurrencyIsEur(case: String) {
        val currency = settings.currency.first()
        if (currency != EUR) {
            throw AssertionError("$case — montage : la devise par défaut devait être EUR, lu « $currency ». Échec de montage, pas une preuve métier.")
        }
    }

    private data class Day(val date: LocalDate, val total: Long, val ids: Set<Long>)

    private data class Group(val category: CategoryEntity, val total: Long, val share: Double)

    private fun assertGroups(case: String, expected: List<Group>, actual: List<CategoryBreakdown>, ctx: String) {
        assertEquals("$case — LOP-207 : groupes de la répartition, dans l'ordre. $ctx", expected.map { it.category.id }, actual.map { it.categoryId })
        expected.zip(actual).forEach { (e, a) ->
            assertEquals("$case — nom du groupe ${e.category.id}. $ctx", e.category.name, a.name)
            assertEquals("$case — couleur du groupe ${e.category.id}. $ctx", e.category.colorArgb, a.colorArgb)
            assertEquals("$case — LOP-207 : montant du groupe ${e.category.name}. $ctx", e.total, a.total)
            assertEquals("$case — LOP-207 : part du groupe ${e.category.name}. $ctx", e.share, a.share, 1e-12)
        }
    }

    private fun HomeUiState.describe() =
        "{mois=$month, dépenses=$monthExpense, revenus=$monthIncome, précédent=$previousPeriodExpense, " +
            "projeté=$projectedBalance, jours=${dayGroups.map { g -> "${g.date}:${g.total}:${g.transactions.map { it.transaction.title }}" }}}"

    private fun AnalyticsUiState.describe() =
        "{mois=$month, type=$type, total=$total, répartition=${breakdown.map { "${it.name}:${it.total}:${it.share}" }}}"

    private companion object {
        const val EUR = "EUR"
        const val ZONE = "Europe/Paris"

        /** Délai d'échec de chaque attente, en temps réel. */
        const val TIMEOUT_MS = 10_000L

        fun ms(instant: String) = Instant.parse(instant).toEpochMilli()

        val CLOCK: Clock = Clock.fixed(Instant.parse("2026-03-15T12:00:00Z"), ZoneId.of(ZONE))

        val MARCH: YearMonth = YearMonth.of(2026, 3)
        val MARCH_1: LocalDate = LocalDate.parse("2026-03-01")
        val MARCH_31: LocalDate = LocalDate.parse("2026-03-31")

        /** 28 février 23:59:59.999 locale (UTC+1). */
        val FEB_LAST_MS = ms("2026-02-28T22:59:59.999Z")

        /** 1er mars 00:00:00.000 locale (UTC+1). */
        val MARCH_FIRST_MS = ms("2026-02-28T23:00:00.000Z")

        /** 31 mars 23:59:59.999 locale (UTC+2 depuis le 29 mars). */
        val MARCH_LAST_MS = ms("2026-03-31T21:59:59.999Z")

        /** 1er avril 00:00:00.000 locale (UTC+2). */
        val APRIL_FIRST_MS = ms("2026-03-31T22:00:00.000Z")

        val CPT = AccountEntity(
            name = "QA-207 Compte", type = AccountType.CHECKING, initialBalance = 0, balanceUpdatedAt = 0,
            colorArgb = 0xFF1565C0.toInt(), icon = "wallet", archived = false,
        )
        val CA = CategoryEntity(name = "QA-207 A", type = TransactionType.EXPENSE, colorArgb = 0xFF1565C0.toInt(), icon = "wallet", parentCategoryId = null)
        val CB = CategoryEntity(name = "QA-207 B", type = TransactionType.EXPENSE, colorArgb = 0xFFE64A19.toInt(), icon = "cart", parentCategoryId = null)
        val CI = CategoryEntity(name = "QA-207 Revenu", type = TransactionType.INCOME, colorArgb = 0xFF00897B.toInt(), icon = "wallet", parentCategoryId = null)
    }
}

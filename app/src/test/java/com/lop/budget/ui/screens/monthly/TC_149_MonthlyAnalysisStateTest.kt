package com.lop.budget.ui.screens.monthly

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lop.budget.data.local.entity.CategoryEntity
import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.data.repository.AccountRepository
import com.lop.budget.data.repository.SettingsRepository
import com.lop.budget.domain.CategoryBreakdown
import com.lop.budget.domain.model.DayGroup
import com.lop.budget.domain.model.NO_ACCOUNT_ID
import com.lop.budget.domain.model.NO_CATEGORY_ID
import com.lop.budget.domain.model.TransactionKind
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.domain.usecase.category.ObserveCategoriesUseCase
import com.lop.budget.domain.usecase.transaction.SearchTransactionsUseCase
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * TC-149 — Analyse Dépenses/Revenus : critères, répartition et états cohérents (US LOP-40).
 *
 * ## Niveau et chaîne exercée
 * ViewModel, JVM pur. Système testé : le vrai [MonthlyTransactionsViewModel] en mode `ANALYTICS`,
 * avec le vrai `BreakdownEngine`. Ses quatre frontières sont des doublures MockK strictes :
 * - `SearchTransactionsUseCase` : doublure **enregistreuse**. Chaque appel est consigné avec ses huit
 *   arguments et rend un flux que seul le test alimente (liste déclarée, ou `IOException`). Elle ne
 *   filtre ni ne trie : une ligne visible ne peut venir que de la liste déclarée par le cas ;
 * - `AccountRepository.observeAll()` (vide), `ObserveCategoriesUseCase()` (référentiel du JDD),
 *   `SettingsRepository.currency` (EUR). Aucune autre méthode n'est autorisée, `confirmVerified`
 *   le prouve en fin de cas. Cela ne prouve pas l'absence d'écriture en base : c'est TC-151.
 *
 * ## Traçabilité — cas → CA / invariant → production
 * ```
 * M-01a–f  CA-01, CA-02  uiState (état initial), setPeriod, AnalysisPeriod.range → bornes transmises
 * M-01g    CA-01         constructeur : un modèle neuf repart de Tous / sans catégorie
 * M-02a–c  CA-03         uiState : total signé, BreakdownEngine.byCategory, groupes de jours, repli
 * M-03a–c  CA-02, CA-06  toggleCategory, setFilter : critères transmis, choix sans catégorie, homonymes
 * M-04a–b  CA-05, CA-07  setPeriod (refus, jour unique, période vide), onCategoryFilterChange(null)
 * M-05a–d  CA-10, I-3    isRefreshing : réponses retenues, ordre choisi, réponse tardive annulée
 * M-06a–d  CA-10         catch → loadFailed, retry : deux origines, première lecture / après succès
 * M-07     CA-09         tri des choix : montant décroissant puis nom
 * ```
 *
 * ## Doublure de recherche et règle « pas de any() »
 * Le stub accepte tout appel (`any()` sur les huit arguments). C'est exigé par la fiche : la doublure
 * enregistre les bornes reçues sans les filtrer, pour que le défaut de fin de journée (LOP-197) ne
 * bloque pas tous les cas. En contrepartie, chaque appel enregistré est vérifié champ par champ, en
 * nombre exact, dans le cas qui le provoque. Exception assumée à `app/src/test/AGENTS.md` §4.
 *
 * ## Deux lectures, et l'ordre de leurs réponses (décision D1 du 9 octobre 2026)
 * Le ViewModel lance deux recherches : la liste (critères affichés) et les choix (mêmes critères sans
 * catégorie). Sans catégorie active, leurs arguments sont identiques : le test ne sait pas laquelle il
 * retient, l'ordre est tiré par `Dispatchers.Default`. M-05a et M-06a/b le disent et ne prétendent
 * pas le contraire. M-05b/c et M-06c/d rendent les deux lectures distinctes par la catégorie A active
 * (données F-PAID de la fiche) et choisissent l'ordre à coup sûr.
 *
 * ## Montage
 * `Dispatchers.Main` = `StandardTestDispatcher` ; le calcul de l'état tourne sur
 * `Dispatchers.Default`, que le test ne pilote pas. Les états sont donc collectés sans relâche
 * (collecteur `Unconfined` dans `backgroundScope`) et attendus en **temps réel**, 10 s au plus, par
 * `withContext(Dispatchers.Default) { withTimeoutOrNull(…) }` : un `withTimeout` dans `runTest`
 * compterait du temps simulé. Aucune pause fixe. Dérogation à `app/src/test/AGENTS.md` §3 : le seul
 * `UnconfinedTestDispatcher` est celui du collecteur d'états, pour qu'aucune émission ne se perde
 * entre deux attentes ; Main reste un `StandardTestDispatcher`. Fuseau Europe/Paris et `Locale.FRANCE` forcés puis
 * restaurés ; `ViewModelStore` vidé au démontage, avant la restauration de Main.
 *
 * **Barrière de M-04a.** Prouver « aucune recherche » après une période invalide exige un témoin :
 * `retry()` relance les deux lectures pour les critères en vigueur, sans en changer aucun. Leurs bornes
 * montrent la période réellement retenue.
 *
 * ## Hypothèses
 * - M-01 porte seul l'oracle de la fin de période. Ailleurs, les bornes sont enregistrées et seul le
 *   début (correct en production) sert à reconnaître une lecture.
 * - La couleur du groupe « Sans catégorie » n'est pas spécifiée par l'US : elle n'est pas vérifiée.
 * - Pas de Robolectric : le ViewModel n'utilise aucune classe Android (écart à la fiche, sans effet sur
 *   la preuve).
 *
 * ## ANO connues
 * - LOP-197 (Backlog) : fin de période à 23:59:59.000 au lieu de .999 → M-01a–f rouges attendus, sur la
 *   seule assertion de fin (vérifiée en dernier).
 * - LOP-196 (Testing) : ancien résultat présenté sous de nouveaux filtres → preuve M-05.
 *
 * ## Résultats — 9 octobre 2026, production de `0f7419f`
 * 24 cas : 18 verts du premier coup, 6 rouges attendus (M-01a–f), stables sur trois passages. Les six
 * échouent sur la seule fin de période : reçu `2026-03-31T21:59:59Z`, attendu `…21:59:59.999Z`
 * (LOP-197). Preuves de sensibilité, une mutation à la fois, retirée :
 * ```
 * M1   statut retiré de l'appel                  → M-01g, M-03b, M-04a, M-05a–d, M-06c/d
 * M2   catégorie retirée de l'appel              → M-01g, M-03a–c, M-04a, M-05b/c, M-06c/d
 * M3   regroupement par nom (BreakdownEngine)    → M-03c (un seul groupe pour deux homonymes)
 * M4   choix lus avec la catégorie active        → M-01g, M-03a–c, M-04a, M-05b/c, M-06c/d
 * M5   actualisation sans le critère des choix   → M-05b, M-03b, M-04a (M-05a : ordre au hasard, muet)
 * M6   actualisation sans le critère de la liste → M-05c, M-05d, M-03a–c, M-04a
 * M7   retry sans effet                          → M-06a–d, M-04a
 * M8   tri des choix sans le nom                 → M-07
 * M9   période fin < début acceptée              → M-04a (première lecture après 16→15)
 * M10  échec jamais exposé                       → M-06a–d
 * M12  total non signé                           → 15 cas, dont M-02a/c, M-05a–d, M-06a–d
 * M13  catégorie active sans mouvement absente   → M-04a
 * M14  état initial présenté comme prêt          → M-01a–g (I-3), M-05a, M-05d
 * ```
 * M5 confirme la décision D1 : seule la variante à ordre choisi (M-05b) attrape à coup sûr un
 * contrôle oublié côté choix.
 *
 * ## Hors périmètre
 * Moteur de recherche et de récurrence, SQL, écritures réelles, navigation, rendu, textes localisés,
 * arrondi des pourcentages (TC-150), sixième place de la légende (TC-152), performance. Ni tags, ni
 * champ de recherche, ni filtre de compte, ni mode HISTORY.
 *
 * ## Exécution
 * `./gradlew :app:testDebugUnitTest --tests "*MonthlyAnalysisStateTest"`
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MonthlyAnalysisStateTest {

    private val mainDispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private var vmCount = 0

    private val reads = CopyOnWriteArrayList<Read>()
    private val readEvents = Channel<Read>(Channel.UNLIMITED)

    private lateinit var search: SearchTransactionsUseCase
    private lateinit var accountRepo: AccountRepository
    private lateinit var observeCategories: ObserveCategoriesUseCase
    private lateinit var settings: SettingsRepository

    private lateinit var previousTimeZone: TimeZone
    private lateinit var previousLocale: Locale

    @Before
    fun setUp() {
        previousTimeZone = TimeZone.getDefault()
        previousLocale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Paris"))
        Locale.setDefault(Locale.FRANCE)
        Dispatchers.setMain(mainDispatcher)

        search = mockk()
        accountRepo = mockk()
        observeCategories = mockk()
        settings = mockk()
        every { search(any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            val read = Read(
                SearchCall(
                    query = arg(0), accountId = arg(1), categoryId = arg(2), startDate = arg(3),
                    endDate = arg(4), type = arg(5), status = arg(6), tagName = arg(7),
                )
            )
            reads += read
            readEvents.trySend(read)
            read.flow()
        }
        every { accountRepo.observeAll() } returns flowOf(emptyList())
        every { observeCategories() } returns flowOf(CATEGORIES)
        every { settings.currency } returns flowOf(EUR)
    }

    @After
    fun tearDown() {
        store.clear()
        reads.forEach { it.answers.cancel() }
        readEvents.close()
        Dispatchers.resetMain()
        TimeZone.setDefault(previousTimeZone)
        Locale.setDefault(previousLocale)
    }

    // ==========================================================================================
    // M-01 — CA-01 / CA-02 : paramètres d'ouverture et bornes transmises
    // ==========================================================================================

    @Test
    fun `M-01a - given Depenses mars when on ouvre l'analyse then Tous sans categorie et bornes du mois de 00h00 000 a 23h59 999`() =
        runTest { boundsCase("M-01a", "EXPENSE", TransactionType.EXPENSE, window = null, MARCH_START, MARCH_END) }

    @Test
    fun `M-01b - given Depenses mars when on applique 30 au 31 mars then bornes 30 mars 00h00 000 et 31 mars 23h59 999`() =
        runTest { boundsCase("M-01b", "EXPENSE", TransactionType.EXPENSE, MARCH_30 to MARCH_31, MARCH_30_START, MARCH_END) }

    @Test
    fun `M-01c - given Depenses mars when on applique le seul 31 mars then bornes 31 mars 00h00 000 et 23h59 999`() =
        runTest { boundsCase("M-01c", "EXPENSE", TransactionType.EXPENSE, MARCH_31 to MARCH_31, MARCH_31_START, MARCH_END) }

    @Test
    fun `M-01d - given Revenus mars when on ouvre l'analyse then Tous sans categorie et bornes du mois de 00h00 000 a 23h59 999`() =
        runTest { boundsCase("M-01d", "INCOME", TransactionType.INCOME, window = null, MARCH_START, MARCH_END) }

    @Test
    fun `M-01e - given Revenus mars when on applique 30 au 31 mars then bornes 30 mars 00h00 000 et 31 mars 23h59 999`() =
        runTest { boundsCase("M-01e", "INCOME", TransactionType.INCOME, MARCH_30 to MARCH_31, MARCH_30_START, MARCH_END) }

    @Test
    fun `M-01f - given Revenus mars when on applique le seul 31 mars then bornes 31 mars 00h00 000 et 23h59 999`() =
        runTest { boundsCase("M-01f", "INCOME", TransactionType.INCOME, MARCH_31 to MARCH_31, MARCH_31_START, MARCH_END) }

    @Test
    fun `M-01g - given un modele dont statut categorie et periode ont change when on ouvre un modele neuf then il repart de Tous sans categorie sur mars`() =
        runTest {
            val case = "M-01g"
            val first = newVm("EXPENSE")
            val obs1 = observe(first)
            open(case, obs1, F_ALL_ROWS, F_ALL_ROWS)
            first.toggleCategory(A.id)
            awaitRead(case, A.id, null).answer(listOf(A1, A2))
            obs1.await(case, "A active") { it.ready() && it.selectedCategoryId == A.id }
            first.setFilter(PaidFilter.PAID)
            awaitRead(case, A.id, TransactionStatus.PAID).answer(listOf(A1))
            awaitRead(case, null, TransactionStatus.PAID).answer(listOf(A1, B1))
            obs1.await(case, "A active, Payé") { it.ready() && it.filter == PaidFilter.PAID }
            first.setPeriod(MARCH_15, MARCH_15)
            awaitRead(case, A.id, TransactionStatus.PAID).answer(listOf(A1))
            awaitRead(case, null, TransactionStatus.PAID).answer(listOf(A1))
            obs1.await(case, "15 mars") { it.ready() && it.period.start == MARCH_15 }
            val readsBefore = reads.size

            val second = newVm("EXPENSE")
            val obs2 = observe(second)
            val initial = obs2.await(case, "état initial du modèle neuf") { true }
            val newReads = listOf(awaitRead(case, null, null), awaitRead(case, null, null))

            val context = "Modèle neuf, état initial : ${initial.describe()}. Lectures : ${readLog()}"
            assertEquals("$case — CA-01 : type du modèle neuf. $context", TransactionType.EXPENSE, initial.type)
            assertEquals("$case — CA-01 : statut du modèle neuf (Tous). $context", PaidFilter.ALL, initial.filter)
            assertEquals("$case — CA-01 : catégorie du modèle neuf (aucune). $context", null, initial.selectedCategoryId)
            assertEquals("$case — CA-01 : période du modèle neuf (mars). $context", AnalysisPeriod(MARCH_1, MARCH_31), initial.period)
            assertTrue("$case — CA-01 : mode analyse. $context", initial.isAnalyticsMode)
            assertTrue("$case — I-3 : aucun résultat avant la première lecture. $context", initial.isRefreshing)
            assertEquals("$case — lectures du modèle neuf : exactement deux. $context", readsBefore + 2, reads.size)
            newReads.forEach { read ->
                assertEquals("$case — CA-01 : catégorie transmise. $context", null, read.call.categoryId)
                assertEquals("$case — CA-01 : statut transmis (Tous). $context", null, read.call.status)
                assertEquals("$case — CA-01 : type transmis. $context", TransactionType.EXPENSE, read.call.type)
                assertEquals("$case — CA-01 : début de période transmis (1er mars). $context", MARCH_START, read.call.startDate)
            }
            verifyBoundaryReads()
        }

    /**
     * Ouvre un modèle neuf ; applique [window] s'il est fourni ; vérifie l'état initial puis les deux
     * lectures de la fenêtre visée. La fin de période est vérifiée **en dernier** : son défaut connu
     * (LOP-197) ne masque ainsi aucune autre assertion.
     */
    private suspend fun TestScope.boundsCase(
        case: String,
        typeArg: String,
        type: TransactionType,
        window: Pair<LocalDate, LocalDate>?,
        expectedStart: Long,
        expectedEnd: Long,
    ) {
        val vm = newVm(typeArg)
        val obs = observe(vm)
        val initial = obs.await(case, "état initial") { true }
        var windowReads = listOf(awaitRead(case, null, null, type), awaitRead(case, null, null, type))
        if (window != null) {
            vm.setPeriod(window.first, window.second)
            windowReads = listOf(awaitRead(case, null, null, type), awaitRead(case, null, null, type))
        }
        // Barrière : une fois l'état prêt pour la fenêtre visée, toutes ses lectures ont eu lieu.
        windowReads.forEach { it.answer(emptyList()) }
        val expectedPeriod = window?.let { AnalysisPeriod(it.first, it.second) } ?: AnalysisPeriod(MARCH_1, MARCH_31)
        obs.await(case, "état prêt pour la fenêtre") { it.ready() && it.period == expectedPeriod }

        val context = "État initial : ${initial.describe()}. Lectures : ${readLog()}"
        assertEquals("$case — CA-01 : type demandé. $context", type, initial.type)
        assertEquals("$case — CA-01 : statut initial (Tous). $context", PaidFilter.ALL, initial.filter)
        assertEquals("$case — CA-01 : aucune catégorie au départ. $context", null, initial.selectedCategoryId)
        assertEquals("$case — CA-01 : période initiale (mois de l'accueil). $context", AnalysisPeriod(MARCH_1, MARCH_31), initial.period)
        assertTrue("$case — CA-01 : mode analyse. $context", initial.isAnalyticsMode)
        assertTrue("$case — I-3 : aucun résultat présenté avant la première lecture. $context", initial.isRefreshing)
        assertEquals(
            "$case — exactement deux lectures par fenêtre (liste et choix). $context",
            if (window == null) 2 else 4,
            reads.size,
        )
        windowReads.forEach { read ->
            assertEquals("$case — critère texte vide. $context", "", read.call.query)
            assertEquals("$case — aucun compte. $context", null, read.call.accountId)
            assertEquals("$case — CA-01 : aucune catégorie, liste comme choix. $context", null, read.call.categoryId)
            assertEquals("$case — CA-01 : type transmis. $context", type, read.call.type)
            assertEquals("$case — CA-01 : statut transmis (Tous). $context", null, read.call.status)
            assertEquals("$case — aucun tag. $context", null, read.call.tagName)
            assertEquals(
                "$case — CA-02 : début de période, 00:00:00.000 locale, attendu ${Instant.ofEpochMilli(expectedStart)}. $context",
                expectedStart,
                read.call.startDate,
            )
        }
        windowReads.forEach { read ->
            assertEquals(
                "$case — CA-02 (LOP-197) : fin de période, 23:59:59.999 locale, attendu " +
                    "${Instant.ofEpochMilli(expectedEnd)}, reçu ${read.call.endDate?.let(Instant::ofEpochMilli)}. $context",
                expectedEnd,
                read.call.endDate,
            )
        }
        verifyBoundaryReads()
    }

    // ==========================================================================================
    // M-02 — CA-03 : agrégats d'un résultat reçu
    // ==========================================================================================

    @Test
    fun `M-02a - given F-ALL en depense when les deux lectures repondent then total -10000 trois lignes A 4000 0,4 et B 6000 0,6`() =
        runTest {
            val case = "M-02a"
            val obs = observe(newVm("EXPENSE"))
            val state = open(case, obs, F_ALL_ROWS, F_ALL_ROWS)
            assertState(case, "CA-03", state, expectedAll())
            verifyBoundaryReads()
        }

    @Test
    fun `M-02b - given D-INC en revenu when les deux lectures repondent then total +10000 et memes groupes positifs`() =
        runTest {
            val case = "M-02b"
            val rows = listOf(RA1, RA2, RB1)
            val obs = observe(newVm("INCOME"))
            val state = open(case, obs, rows, rows, TransactionType.INCOME)
            assertState(
                case, "CA-03", state,
                Expected(
                    period = MARCH, type = TransactionType.INCOME, filter = PaidFilter.ALL, categoryId = null,
                    total = 10_000, rows = rows,
                    days = listOf(
                        DayGroup(MARCH_15, 3_000, listOf(RA1)),
                        DayGroup(MARCH_16, 1_000, listOf(RA2)),
                        DayGroup(MARCH_20, 6_000, listOf(RB1)),
                    ),
                    breakdown = listOf(group(RB, 6_000, 0.6), group(RA, 4_000, 0.4)),
                    choices = listOf(group(RB, 6_000, 0.6), group(RA, 4_000, 0.4)),
                ),
            )
            verifyBoundaryReads()
        }

    @Test
    fun `M-02c - given A1 et une ligne sans categorie when les deux lectures repondent then total -4000 A 3000 0,75 et Sans categorie 1000 0,25`() =
        runTest {
            val case = "M-02c"
            val rows = listOf(A1, N)
            val obs = observe(newVm("EXPENSE"))
            val state = open(case, obs, rows, rows)
            val fallback = Group(NO_CATEGORY_ID, "Sans catégorie", color = null, total = 1_000, share = 0.25)
            assertState(
                case, "CA-03", state,
                Expected(
                    period = MARCH, type = TransactionType.EXPENSE, filter = PaidFilter.ALL, categoryId = null,
                    total = -4_000, rows = rows,
                    days = listOf(DayGroup(MARCH_15, -3_000, listOf(A1)), DayGroup(MARCH_21, -1_000, listOf(N))),
                    breakdown = listOf(group(A, 3_000, 0.75), fallback),
                    choices = listOf(group(A, 3_000, 0.75), fallback),
                ),
            )
            verifyBoundaryReads()
        }

    // ==========================================================================================
    // M-03 — CA-02 / CA-06 : catégorie et statut transmis, choix de base conservés
    // ==========================================================================================

    @Test
    fun `M-03a - given F-ALL when on choisit A puis B puis retape B then active A B null et appels portant exactement cette categorie`() =
        runTest {
            val case = "M-03a"
            val vm = newVm("EXPENSE")
            val obs = observe(vm)
            open(case, obs, F_ALL_ROWS, F_ALL_ROWS)

            vm.toggleCategory(A.id)
            awaitRead(case, A.id, null).answer(listOf(A1, A2))
            val withA = obs.await(case, "A active") { it.ready() && it.selectedCategoryId == A.id }
            vm.toggleCategory(B.id)
            awaitRead(case, B.id, null).answer(listOf(B1))
            val withB = obs.await(case, "B active") { it.ready() && it.selectedCategoryId == B.id }
            vm.toggleCategory(B.id)
            awaitRead(case, null, null).answer(F_ALL_ROWS)
            val none = obs.await(case, "B retirée") { it.ready() && it.selectedCategoryId == null && it.total == -10_000L }

            assertState(case, "CA-06", withA, expectedAll().copy(
                categoryId = A.id, total = -4_000, rows = listOf(A1, A2),
                days = listOf(DayGroup(MARCH_15, -3_000, listOf(A1)), DayGroup(MARCH_16, -1_000, listOf(A2))),
                breakdown = listOf(group(A, 4_000, 1.0)),
            ))
            assertState(case, "CA-06", withB, expectedAll().copy(
                categoryId = B.id, total = -6_000, rows = listOf(B1),
                days = listOf(DayGroup(MARCH_20, -6_000, listOf(B1))),
                breakdown = listOf(group(B, 6_000, 1.0)),
            ))
            assertState(case, "CA-06", none, expectedAll())
            assertCalls(
                case, "CA-02/CA-06 : liste et choix à l'ouverture, puis la seule liste avec A, B, aucune",
                listOf(key(null, null), key(null, null), key(A.id, null), key(B.id, null), key(null, null)),
            )
            verifyBoundaryReads()
        }

    @Test
    fun `M-03b - given A active when on passe Paye Non paye puis Tous then -3000 A1 -1000 A2 -4000 A1 A2 et choix de base du statut`() =
        runTest {
            val case = "M-03b"
            val vm = newVm("EXPENSE")
            val obs = observe(vm)
            open(case, obs, F_ALL_ROWS, F_ALL_ROWS)
            vm.toggleCategory(A.id)
            awaitRead(case, A.id, null).answer(listOf(A1, A2))
            obs.await(case, "A active") { it.ready() && it.selectedCategoryId == A.id }

            vm.setFilter(PaidFilter.PAID)
            awaitRead(case, A.id, TransactionStatus.PAID).answer(listOf(A1))
            awaitRead(case, null, TransactionStatus.PAID).answer(listOf(A1, B1))
            val paid = obs.await(case, "Payé") { it.ready() && it.filter == PaidFilter.PAID }
            vm.setFilter(PaidFilter.PLANNED)
            awaitRead(case, A.id, TransactionStatus.PLANNED).answer(listOf(A2))
            awaitRead(case, null, TransactionStatus.PLANNED).answer(listOf(A2))
            val planned = obs.await(case, "Non payé") { it.ready() && it.filter == PaidFilter.PLANNED }
            vm.setFilter(PaidFilter.ALL)
            awaitRead(case, A.id, null).answer(listOf(A1, A2))
            awaitRead(case, null, null).answer(F_ALL_ROWS)
            val all = obs.await(case, "Tous") { it.ready() && it.filter == PaidFilter.ALL && it.categoryChoices.size == 2 && it.total == -4_000L }

            assertState(case, "CA-02", paid, Expected(
                period = MARCH, type = TransactionType.EXPENSE, filter = PaidFilter.PAID, categoryId = A.id,
                total = -3_000, rows = listOf(A1), days = listOf(DayGroup(MARCH_15, -3_000, listOf(A1))),
                breakdown = listOf(group(A, 3_000, 1.0)), choices = CHOICES_PAID,
            ))
            assertState(case, "CA-02", planned, Expected(
                period = MARCH, type = TransactionType.EXPENSE, filter = PaidFilter.PLANNED, categoryId = A.id,
                total = -1_000, rows = listOf(A2), days = listOf(DayGroup(MARCH_16, -1_000, listOf(A2))),
                breakdown = listOf(group(A, 1_000, 1.0)), choices = CHOICES_PLANNED,
            ))
            assertState(case, "CA-02", all, expectedAll().copy(
                categoryId = A.id, total = -4_000, rows = listOf(A1, A2),
                days = listOf(DayGroup(MARCH_15, -3_000, listOf(A1)), DayGroup(MARCH_16, -1_000, listOf(A2))),
                breakdown = listOf(group(A, 4_000, 1.0)),
            ))
            assertCalls(
                case, "CA-02 : statut et catégorie exacts ; les choix ne reçoivent jamais de catégorie",
                listOf(
                    key(null, null), key(null, null), key(A.id, null),
                    key(A.id, TransactionStatus.PAID), key(null, TransactionStatus.PAID),
                    key(A.id, TransactionStatus.PLANNED), key(null, TransactionStatus.PLANNED),
                    key(A.id, null), key(null, null),
                ),
            )
            verifyBoundaryReads()
        }

    @Test
    fun `M-03c - given deux categories homonymes when on choisit H1 puis H2 then chaque appel porte son identifiant et la ligne de sa seule categorie`() =
        runTest {
            val case = "M-03c"
            val vm = newVm("EXPENSE")
            val obs = observe(vm)
            val opened = open(case, obs, listOf(TH1, TH2), listOf(TH1, TH2), syncRow = TH1)
            vm.toggleCategory(H1.id)
            awaitRead(case, H1.id, null).answer(listOf(TH1))
            val first = obs.await(case, "H1 active") { it.ready() && it.selectedCategoryId == H1.id }
            vm.toggleCategory(H2.id)
            awaitRead(case, H2.id, null).answer(listOf(TH2))
            val second = obs.await(case, "H2 active") { it.ready() && it.selectedCategoryId == H2.id }

            val base = Expected(
                period = MARCH, type = TransactionType.EXPENSE, filter = PaidFilter.ALL, categoryId = null,
                total = -5_000, rows = listOf(TH1, TH2),
                days = listOf(DayGroup(MARCH_15, -2_000, listOf(TH1)), DayGroup(MARCH_16, -3_000, listOf(TH2))),
                breakdown = listOf(group(H2, 3_000, 0.6), group(H1, 2_000, 0.4)),
                choices = listOf(group(H2, 3_000, 0.6), group(H1, 2_000, 0.4)),
            )
            assertState(case, "CA-06 : deux homonymes, deux choix distincts", opened, base)
            assertState(case, "CA-06", first, base.copy(
                categoryId = H1.id, total = -2_000, rows = listOf(TH1),
                days = listOf(DayGroup(MARCH_15, -2_000, listOf(TH1))), breakdown = listOf(group(H1, 2_000, 1.0)),
            ))
            assertState(case, "CA-06", second, base.copy(
                categoryId = H2.id, total = -3_000, rows = listOf(TH2),
                days = listOf(DayGroup(MARCH_16, -3_000, listOf(TH2))), breakdown = listOf(group(H2, 3_000, 1.0)),
            ))
            assertCalls(
                case, "CA-06 : H1 puis H2, jamais l'autre",
                listOf(key(null, null), key(null, null), key(H1.id, null), key(H2.id, null)),
            )
            verifyBoundaryReads()
        }

    // ==========================================================================================
    // M-04 — CA-05 / CA-07 : période refusée, jour unique, période vide
    // ==========================================================================================

    @Test
    fun `M-04a - given A active et Paye when fin avant debut puis jour unique puis 1er avril vide puis retrait de A then refus sans recherche et filtres conserves`() =
        runTest {
            val case = "M-04a"
            val vm = newVm("EXPENSE")
            val obs = observe(vm)
            open(case, obs, F_ALL_ROWS, F_ALL_ROWS)
            vm.toggleCategory(A.id)
            awaitRead(case, A.id, null).answer(listOf(A1, A2))
            obs.await(case, "A active") { it.ready() && it.selectedCategoryId == A.id }
            vm.setFilter(PaidFilter.PAID)
            awaitRead(case, A.id, TransactionStatus.PAID).answer(listOf(A1))
            awaitRead(case, null, TransactionStatus.PAID).answer(listOf(A1, B1))
            val beforeInvalid = obs.await(case, "A active, Payé") { it.ready() && it.filter == PaidFilter.PAID }
            val readsBeforeInvalid = reads.size

            // Fin antérieure au début : refusée. Barrière : retry() relit avec les critères en vigueur.
            vm.setPeriod(MARCH_16, MARCH_15)
            vm.retry()
            val barrierResult = awaitRead(case, A.id, TransactionStatus.PAID)
            val barrierChoices = awaitRead(case, null, TransactionStatus.PAID)
            // Vérifié avant de répondre : une période acceptée à tort rend ces lectures caduques.
            listOf(barrierResult, barrierChoices).forEach {
                assertEquals(
                    "$case — CA-05 : la première lecture après la demande 16→15 doit porter sur mars " +
                        "(début ${Instant.ofEpochMilli(MARCH_START)}). Lectures : ${readLog()}",
                    MARCH_START,
                    it.call.startDate,
                )
            }
            barrierResult.answer(listOf(A1))
            barrierChoices.answer(listOf(A1, B1))
            val afterInvalid = obs.await(case, "après la période refusée") { it.ready() }
            val readsAfterBarrier = reads.size

            vm.setPeriod(MARCH_15, MARCH_15)
            awaitRead(case, A.id, TransactionStatus.PAID).answer(listOf(A1))
            awaitRead(case, null, TransactionStatus.PAID).answer(listOf(A1))
            val single = obs.await(case, "jour unique") { it.ready() && it.period == AnalysisPeriod(MARCH_15, MARCH_15) }

            vm.setPeriod(APRIL_1, APRIL_1)
            awaitRead(case, A.id, TransactionStatus.PAID).answer(emptyList())
            awaitRead(case, null, TransactionStatus.PAID).answer(listOf(B_AVRIL))
            val empty = obs.await(case, "1er avril, A active") { it.ready() && it.period == AnalysisPeriod(APRIL_1, APRIL_1) }

            vm.onCategoryFilterChange(null)
            awaitRead(case, null, TransactionStatus.PAID).answer(listOf(B_AVRIL))
            val cleared = obs.await(case, "A retirée") { it.ready() && it.selectedCategoryId == null && it.total == -7_000L }

            val context = "Lectures : ${readLog()}"
            // Refus : aucune lecture entre la demande invalide et la barrière, et la barrière lit mars.
            assertEquals(
                "$case — CA-05 : la période invalide a déclenché une recherche (seules les deux relectures témoins sont attendues). $context",
                readsBeforeInvalid + 2,
                readsAfterBarrier,
            )
            assertFalse(
                "$case — CA-05 : un état a présenté la période refusée. Trace : ${obs.trace.map { it.describe() }}",
                obs.trace.any { it.period.start == MARCH_16 && it.period.end == MARCH_15 },
            )
            assertState(case, "CA-05 : la période refusée ne change rien", afterInvalid, stateFields(beforeInvalid))
            assertState(case, "CA-05", single, Expected(
                period = AnalysisPeriod(MARCH_15, MARCH_15), type = TransactionType.EXPENSE, filter = PaidFilter.PAID,
                categoryId = A.id, total = -3_000, rows = listOf(A1), days = listOf(DayGroup(MARCH_15, -3_000, listOf(A1))),
                breakdown = listOf(group(A, 3_000, 1.0)), choices = listOf(group(A, 3_000, 1.0)),
            ))
            assertState(case, "CA-05/CA-07 : catégorie sans mouvement, conservée et retirable", empty, Expected(
                period = AnalysisPeriod(APRIL_1, APRIL_1), type = TransactionType.EXPENSE, filter = PaidFilter.PAID,
                categoryId = A.id, total = 0, rows = emptyList(), days = emptyList(), breakdown = emptyList(),
                choices = listOf(group(B, 7_000, 1.0), group(A, 0, 0.0)),
            ))
            assertState(case, "CA-07", cleared, Expected(
                period = AnalysisPeriod(APRIL_1, APRIL_1), type = TransactionType.EXPENSE, filter = PaidFilter.PAID,
                categoryId = null, total = -7_000, rows = listOf(B_AVRIL),
                days = listOf(DayGroup(APRIL_1, -7_000, listOf(B_AVRIL))),
                breakdown = listOf(group(B, 7_000, 1.0)), choices = listOf(group(B, 7_000, 1.0)),
            ))
            assertEquals(
                "$case — CA-05 : début transmis pour le jour unique (15 mars 00:00 locale). $context",
                listOf(MARCH_15_START, MARCH_15_START),
                reads.drop(readsBeforeInvalid + 2).take(2).map { it.call.startDate },
            )
            assertEquals(
                "$case — CA-05 : début transmis pour le 1er avril (00:00 locale). $context",
                listOf(APRIL_1_START, APRIL_1_START, APRIL_1_START),
                reads.drop(readsBeforeInvalid + 4).map { it.call.startDate },
            )
            verifyBoundaryReads()
        }

    @Test
    fun `M-04b - given aucune ligne ni choix when les deux lectures repondent vide then toutes les collections sont explicitement vides et total 0`() =
        runTest {
            val case = "M-04b"
            val obs = observe(newVm("EXPENSE"))
            awaitRead(case, null, null).answer(emptyList())
            awaitRead(case, null, null).answer(emptyList())
            val state = obs.await(case, "F-NONE") { it.ready() }
            assertState(case, "CA-07", state, Expected(
                period = MARCH, type = TransactionType.EXPENSE, filter = PaidFilter.ALL, categoryId = null,
                total = 0, rows = emptyList(), days = emptyList(), breakdown = emptyList(), choices = emptyList(),
            ))
            verifyBoundaryReads()
        }

    // ==========================================================================================
    // M-05 — CA-10 / I-3 : jamais de résultat d'une autre sélection présenté comme prêt
    // ==========================================================================================

    @Test
    fun `M-05a - given F-ALL when on passe Paye et que les deux reponses arrivent une par une then actualisation tant qu'une manque puis A1 B1 -9000`() =
        runTest {
            val case = "M-05a"
            val vm = newVm("EXPENSE")
            val obs = observe(vm)
            open(case, obs, F_ALL_ROWS, F_ALL_ROWS)
            vm.setFilter(PaidFilter.PAID)
            val first = awaitRead(case, null, TransactionStatus.PAID)
            val second = awaitRead(case, null, TransactionStatus.PAID)

            // Arguments identiques : la lecture libérée d'abord est celle que les fils ont créée
            // d'abord, liste ou choix ; l'état reçu dit laquelle.
            first.answer(listOf(A1, B1))
            val half = obs.await(case, "une seule réponse Payé") {
                it.filter == PaidFilter.PAID && (it.transactions == listOf(A1, B1) || it.categoryChoices.size == 2 && it.categoryChoices[0].total == 6_000L && it.categoryChoices[1].total == 3_000L)
            }
            val side = if (half.transactions == listOf(A1, B1)) "liste" else "choix"
            second.answer(listOf(A1, B1))
            val final = obs.await(case, "les deux réponses Payé") { it.ready() && it.filter == PaidFilter.PAID }

            assertTrue(
                "$case — CA-10/I-3 : avec la seule réponse « $side » à jour, l'état doit rester en " +
                    "actualisation. État : ${half.describe()}",
                half.isRefreshing,
            )
            assertNoMixedReadyState(case, obs.trace, ::expectedNoCategory)
            assertState(case, "CA-10", final, Expected(
                period = MARCH, type = TransactionType.EXPENSE, filter = PaidFilter.PAID, categoryId = null,
                total = -9_000, rows = listOf(A1, B1),
                days = listOf(DayGroup(MARCH_15, -3_000, listOf(A1)), DayGroup(MARCH_20, -6_000, listOf(B1))),
                breakdown = listOf(group(B, 6_000, 2.0 / 3), group(A, 3_000, 1.0 / 3)), choices = CHOICES_PAID,
            ))
            verifyBoundaryReads()
        }

    @Test
    fun `M-05b - given A active when on passe Paye et que la liste repond avant les choix then actualisation jusqu'aux choix puis A1 -3000`() =
        runTest { orderedRefresh("M-05b", listFirst = true) }

    @Test
    fun `M-05c - given A active when on passe Paye et que les choix repondent avant la liste then actualisation jusqu'a la liste puis A1 -3000`() =
        runTest { orderedRefresh("M-05c", listFirst = false) }

    /** M-05b/c : lectures distinguées par la catégorie active, ordre des réponses choisi (D1). */
    private suspend fun TestScope.orderedRefresh(case: String, listFirst: Boolean) {
        val vm = newVm("EXPENSE")
        val obs = observe(vm)
        open(case, obs, F_ALL_ROWS, F_ALL_ROWS)
        vm.toggleCategory(A.id)
        awaitRead(case, A.id, null).answer(listOf(A1, A2))
        obs.await(case, "A active") { it.ready() && it.selectedCategoryId == A.id }

        vm.setFilter(PaidFilter.PAID)
        val list = awaitRead(case, A.id, TransactionStatus.PAID)
        val choices = awaitRead(case, null, TransactionStatus.PAID)
        val half = if (listFirst) {
            list.answer(listOf(A1))
            obs.await(case, "liste Payé seule") { it.filter == PaidFilter.PAID && it.transactions == listOf(A1) }
        } else {
            choices.answer(listOf(A1, B1))
            obs.await(case, "choix Payé seuls") {
                it.filter == PaidFilter.PAID && it.categoryChoices.map { c -> c.categoryId to c.total } == listOf(B.id to 6_000L, A.id to 3_000L)
            }
        }
        if (listFirst) choices.answer(listOf(A1, B1)) else list.answer(listOf(A1))
        val final = obs.await(case, "les deux réponses Payé") { it.ready() && it.filter == PaidFilter.PAID }

        val pending = if (listFirst) "choix" else "liste"
        assertTrue(
            "$case — CA-10/I-3 : la réponse « $pending » manque encore, l'état doit rester en actualisation. " +
                "État : ${half.describe()}",
            half.isRefreshing,
        )
        assertNoMixedReadyState(case, obs.trace, ::expectedWithA)
        assertState(case, "CA-10", final, Expected(
            period = MARCH, type = TransactionType.EXPENSE, filter = PaidFilter.PAID, categoryId = A.id,
            total = -3_000, rows = listOf(A1), days = listOf(DayGroup(MARCH_15, -3_000, listOf(A1))),
            breakdown = listOf(group(A, 3_000, 1.0)), choices = CHOICES_PAID,
        ))
        verifyBoundaryReads()
    }

    @Test
    fun `M-05d - given Non paye en attente when on passe Paye avant sa reponse then la reponse Non paye tardive ne remplace jamais l'etat Paye`() =
        runTest {
            val case = "M-05d"
            val vm = newVm("EXPENSE")
            val obs = observe(vm)
            open(case, obs, F_ALL_ROWS, F_ALL_ROWS)
            vm.setFilter(PaidFilter.PLANNED)
            val planned = listOf(awaitRead(case, null, TransactionStatus.PLANNED), awaitRead(case, null, TransactionStatus.PLANNED))
            vm.setFilter(PaidFilter.PAID)
            awaitRead(case, null, TransactionStatus.PAID).answer(listOf(A1, B1))
            awaitRead(case, null, TransactionStatus.PAID).answer(listOf(A1, B1))
            val final = obs.await(case, "Payé") { it.ready() && it.filter == PaidFilter.PAID }

            val lateAccepted = planned.map { it.answers.trySend(listOf(A2)).isSuccess }
            val context = "Lectures : ${readLog()}"
            assertEquals(
                "$case — I-3 : les lectures Non payé devaient être abandonnées avant leur réponse. $context",
                listOf(true, true),
                planned.map { it.ended },
            )
            assertEquals(
                "$case — I-3 : une réponse tardive à une lecture abandonnée a encore été acceptée. $context",
                listOf(false, false),
                lateAccepted,
            )
            assertNoMixedReadyState(case, obs.trace, ::expectedNoCategory)
            assertState(case, "CA-10", final, Expected(
                period = MARCH, type = TransactionType.EXPENSE, filter = PaidFilter.PAID, categoryId = null,
                total = -9_000, rows = listOf(A1, B1),
                days = listOf(DayGroup(MARCH_15, -3_000, listOf(A1)), DayGroup(MARCH_20, -6_000, listOf(B1))),
                breakdown = listOf(group(B, 6_000, 2.0 / 3), group(A, 3_000, 1.0 / 3)), choices = CHOICES_PAID,
            ))
            assertEquals("$case — I-3 : l'état courant n'est plus l'état Payé. $context", final, vm.uiState.value)
            verifyBoundaryReads()
        }

    // ==========================================================================================
    // M-06 — CA-10 : échec de lecture, puis Réessayer
    // ==========================================================================================

    @Test
    fun `M-06a - given premiere lecture when la lecture creee en premier echoue then echec sans attente puis retry relit les memes criteres`() =
        runTest { firstReadFailure("M-06a", failFirstCreated = true) }

    @Test
    fun `M-06b - given premiere lecture when la lecture creee en second echoue then echec sans attente puis retry relit les memes criteres`() =
        runTest { firstReadFailure("M-06b", failFirstCreated = false) }

    /**
     * Première lecture : liste et choix ont les mêmes arguments, l'origine de l'échec n'est pas
     * choisie (voir l'en-tête). Elle est lue dans l'état et citée dans les messages.
     */
    private suspend fun TestScope.firstReadFailure(case: String, failFirstCreated: Boolean) {
        val vm = newVm("EXPENSE")
        val obs = observe(vm)
        val first = awaitRead(case, null, null)
        val second = awaitRead(case, null, null)
        val (failing, succeeding) = if (failFirstCreated) first to second else second to first
        failing.fail()
        succeeding.answer(F_ALL_ROWS)
        val failed = obs.await(case, "échec") { !it.isRefreshing && it.loadFailed }
        val side = if (failed.transactions.isEmpty()) "liste" else "choix"
        val readsBeforeRetry = reads.size

        vm.retry()
        val retried = listOf(awaitRead(case, null, null), awaitRead(case, null, null))
        retried.forEach { it.answer(F_ALL_ROWS) }
        val recovered = obs.await(case, "après Réessayer") { it.ready() }

        val context = "Échec côté « $side ». Lectures : ${readLog()}"
        assertFailedKeepsCriteria(case, failed, MARCH, PaidFilter.ALL, null, context)
        assertEquals("$case — CA-10 : Réessayer ouvre exactement deux lectures. $context", readsBeforeRetry + 2, reads.size)
        retried.forEach {
            assertEquals("$case — CA-10 : Réessayer relit les mêmes critères. $context", first.call, it.call)
        }
        assertState(case, "CA-10 : le succès lève l'erreur et l'attente", recovered, expectedAll())
        verifyBoundaryReads()
    }

    @Test
    fun `M-06c - given un etat reussi avec A when la liste Paye echoue then echec criteres conserves puis retry rend A1 -3000`() =
        runTest { failureAfterSuccess("M-06c", failList = true) }

    @Test
    fun `M-06d - given un etat reussi avec A when les choix Paye echouent then echec criteres conserves puis retry rend A1 -3000`() =
        runTest { failureAfterSuccess("M-06d", failList = false) }

    private suspend fun TestScope.failureAfterSuccess(case: String, failList: Boolean) {
        val vm = newVm("EXPENSE")
        val obs = observe(vm)
        open(case, obs, F_ALL_ROWS, F_ALL_ROWS)
        vm.toggleCategory(A.id)
        awaitRead(case, A.id, null).answer(listOf(A1, A2))
        obs.await(case, "A active") { it.ready() && it.selectedCategoryId == A.id }

        vm.setFilter(PaidFilter.PAID)
        val list = awaitRead(case, A.id, TransactionStatus.PAID)
        val choices = awaitRead(case, null, TransactionStatus.PAID)
        if (failList) {
            list.fail(); choices.answer(listOf(A1, B1))
        } else {
            list.answer(listOf(A1)); choices.fail()
        }
        val failed = obs.await(case, "échec") { !it.isRefreshing && it.loadFailed }
        val readsBeforeRetry = reads.size

        vm.retry()
        val retriedList = awaitRead(case, A.id, TransactionStatus.PAID)
        val retriedChoices = awaitRead(case, null, TransactionStatus.PAID)
        retriedList.answer(listOf(A1))
        retriedChoices.answer(listOf(A1, B1))
        val recovered = obs.await(case, "après Réessayer") { it.ready() }

        val side = if (failList) "liste" else "choix"
        val context = "Échec côté « $side ». Lectures : ${readLog()}"
        assertFailedKeepsCriteria(case, failed, MARCH, PaidFilter.PAID, A.id, context)
        assertEquals("$case — CA-10 : Réessayer ouvre exactement deux lectures. $context", readsBeforeRetry + 2, reads.size)
        assertEquals("$case — CA-10 : Réessayer relit la liste avec les mêmes critères. $context", list.call, retriedList.call)
        assertEquals("$case — CA-10 : Réessayer relit les choix avec les mêmes critères. $context", choices.call, retriedChoices.call)
        assertState(case, "CA-10 : le succès lève l'erreur et l'attente", recovered, Expected(
            period = MARCH, type = TransactionType.EXPENSE, filter = PaidFilter.PAID, categoryId = A.id,
            total = -3_000, rows = listOf(A1), days = listOf(DayGroup(MARCH_15, -3_000, listOf(A1))),
            breakdown = listOf(group(A, 3_000, 1.0)), choices = CHOICES_PAID,
        ))
        verifyBoundaryReads()
    }

    private fun assertFailedKeepsCriteria(
        case: String,
        state: MonthlyTransactionsUiState,
        period: AnalysisPeriod,
        filter: PaidFilter,
        categoryId: Long?,
        context: String,
    ) {
        val ctx = "État : ${state.describe()}. $context"
        assertFalse("$case — CA-10 : l'attente doit se terminer sur un échec. $ctx", state.isRefreshing)
        assertTrue("$case — CA-10 : échec de lecture exposé. $ctx", state.loadFailed)
        assertEquals("$case — CA-10 : type conservé. $ctx", TransactionType.EXPENSE, state.type)
        assertEquals("$case — CA-10 : période conservée. $ctx", period, state.period)
        assertEquals("$case — CA-10 : statut conservé. $ctx", filter, state.filter)
        assertEquals("$case — CA-10 : catégorie conservée. $ctx", categoryId, state.selectedCategoryId)
    }

    // ==========================================================================================
    // M-07 — CA-09 : départage des choix à montant égal
    // ==========================================================================================

    @Test
    fun `M-07 - given deux categories a montant egal recues Zulu puis Alpha when les lectures repondent then choix exactement Alpha puis Zulu`() =
        runTest {
            val case = "M-07"
            val rows = listOf(TTZ, TTA)
            val obs = observe(newVm("EXPENSE"))
            val state = open(case, obs, rows, rows, syncRow = TTZ)
            assertState(case, "CA-09", state, Expected(
                period = MARCH, type = TransactionType.EXPENSE, filter = PaidFilter.ALL, categoryId = null,
                total = -2_000, rows = rows,
                days = listOf(DayGroup(MARCH_21, -1_000, listOf(TTZ)), DayGroup(MARCH_22, -1_000, listOf(TTA))),
                breakdown = listOf(group(TZ, 1_000, 0.5), group(TA, 1_000, 0.5)),
                choices = listOf(group(TA, 1_000, 0.5), group(TZ, 1_000, 0.5)),
            ))
            verifyBoundaryReads()
        }

    // ==========================================================================================
    // Doublure de recherche
    // ==========================================================================================

    private data class SearchCall(
        val query: String,
        val accountId: Long?,
        val categoryId: Long?,
        val startDate: Long?,
        val endDate: Long?,
        val type: TransactionType?,
        val status: TransactionStatus?,
        val tagName: String?,
    )

    /** Une lecture : l'appel reçu et le canal que seul le test remplit. */
    private class Read(val call: SearchCall) {
        val answers = Channel<List<TransactionWithRelations>>(Channel.UNLIMITED)

        @Volatile var taken = false

        /** Vrai quand le ViewModel a cessé de lire ce flux (annulé ou en échec). */
        @Volatile var ended = false

        fun flow(): Flow<List<TransactionWithRelations>> = flow {
            try {
                emitAll(answers)
            } finally {
                ended = true
            }
        }

        fun answer(rows: List<TransactionWithRelations>) {
            check(answers.trySend(rows).isSuccess) { "Montage : lecture déjà abandonnée, réponse impossible : $call" }
        }

        fun fail() {
            answers.close(IOException("QA-40 lecture interrompue"))
        }
    }

    /**
     * La première lecture non encore prise par le test qui demande [categoryId], [status] et [type].
     * Le reste de l'appel (texte, compte, tag, bornes) est vérifié par les cas, pas ici.
     */
    private suspend fun awaitRead(
        case: String,
        categoryId: Long?,
        status: TransactionStatus?,
        type: TransactionType = TransactionType.EXPENSE,
    ): Read {
        while (true) {
            reads.firstOrNull {
                !it.taken && it.call.categoryId == categoryId && it.call.status == status && it.call.type == type
            }?.let {
                it.taken = true
                return it
            }
            withContext(Dispatchers.Default) { withTimeoutOrNull(TIMEOUT_MS) { readEvents.receive() } }
                ?: throw AssertionError(
                    "$case — aucune recherche (catégorie=$categoryId, statut=$status, type=$type) en " +
                        "${TIMEOUT_MS / 1000} s. Lectures reçues : ${readLog()}"
                )
        }
    }

    private fun readLog(): String = reads.joinToString(prefix = "[", postfix = "]") { r ->
        val c = r.call
        "(cat=${c.categoryId}, statut=${c.status}, type=${c.type}, début=${c.startDate?.let(Instant::ofEpochMilli)}, " +
            "fin=${c.endDate?.let(Instant::ofEpochMilli)}, texte='${c.query}', compte=${c.accountId}, tag=${c.tagName}" +
            (if (r.ended) ", terminée" else "") + ")"
    }

    /** Clé d'un appel hors bornes de fin : texte, compte, catégorie, type, statut, tag et début. */
    private fun key(categoryId: Long?, status: TransactionStatus?, start: Long = MARCH_START) =
        SearchCall("", null, categoryId, start, endDate = null, TransactionType.EXPENSE, status, null)

    private fun assertCalls(case: String, what: String, expected: List<SearchCall>) {
        val actual = reads.map { it.call.copy(endDate = null) }
        assertEquals(
            "$case — $what. Lectures : ${readLog()}",
            expected.sortedBy { it.toString() },
            actual.sortedBy { it.toString() },
        )
    }

    // ==========================================================================================
    // Modèle et observation
    // ==========================================================================================

    private fun newVm(type: String): MonthlyTransactionsViewModel {
        val handle = SavedStateHandle(mapOf("type" to type, "ym" to "2026-03", "mode" to "ANALYTICS"))
        val factory = viewModelFactory {
            initializer { MonthlyTransactionsViewModel(handle, accountRepo, observeCategories, search, settings) }
        }
        return ViewModelProvider(store, factory)["vm-${vmCount++}", MonthlyTransactionsViewModel::class.java]
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

    /** Le prochain état reçu qui satisfait [accept] ; échec diagnostiqué après 10 s réelles. */
    private suspend fun Observation.await(
        case: String,
        what: String,
        accept: (MonthlyTransactionsUiState) -> Boolean,
    ): MonthlyTransactionsUiState {
        while (true) {
            val next = withContext(Dispatchers.Default) { withTimeoutOrNull(TIMEOUT_MS) { states.receive() } }
                ?: throw AssertionError(
                    "$case — $what : état attendu non atteint en ${TIMEOUT_MS / 1000} s. Dernier état : " +
                        "${trace.lastOrNull()?.describe()}. Lectures : ${readLog()}"
                )
            if (accept(next)) return next
        }
    }

    /**
     * Ouverture : les deux premières lectures (arguments identiques) reçoivent [list] et [choices].
     * Synchronisation par la seule identité de [syncRow] ; l'oracle complet appartient au cas.
     */
    private suspend fun open(
        case: String,
        obs: Observation,
        list: List<TransactionWithRelations>,
        choices: List<TransactionWithRelations>,
        type: TransactionType = TransactionType.EXPENSE,
        syncRow: TransactionWithRelations = list.first(),
    ): MonthlyTransactionsUiState {
        awaitRead(case, null, null, type).answer(list)
        awaitRead(case, null, null, type).answer(choices)
        return obs.await(case, "ouverture") { st -> st.ready() && st.transactions.any { it.transaction.id == syncRow.transaction.id } }
    }

    private fun MonthlyTransactionsUiState.ready() = !isRefreshing && !loadFailed

    private fun verifyBoundaryReads() {
        verify(exactly = vmCount) { accountRepo.observeAll() }
        verify(exactly = vmCount) { observeCategories() }
        verify(exactly = vmCount) { settings.currency }
        confirmVerified(accountRepo, observeCategories, settings)
    }

    // ==========================================================================================
    // Oracles
    // ==========================================================================================

    /** Groupe attendu. [color] nul : couleur non spécifiée par l'US (groupe « Sans catégorie »). */
    private data class Group(val categoryId: Long?, val name: String, val color: Int?, val total: Long, val share: Double)

    private fun group(category: CategoryEntity, total: Long, share: Double) =
        Group(category.id, category.name, category.colorArgb, total, share)

    private data class Expected(
        val period: AnalysisPeriod,
        val type: TransactionType,
        val filter: PaidFilter,
        val categoryId: Long?,
        val total: Long,
        val rows: List<TransactionWithRelations>,
        val days: List<DayGroup>,
        /** Ensemble des portions de l'anneau : leur ordre n'est pas spécifié. */
        val breakdown: List<Group>,
        /** Choix de catégories, dans l'ordre exigé par CA-09. */
        val choices: List<Group>,
        val refreshing: Boolean = false,
        val failed: Boolean = false,
    )

    private fun expectedAll() = Expected(
        period = MARCH, type = TransactionType.EXPENSE, filter = PaidFilter.ALL, categoryId = null,
        total = -10_000, rows = F_ALL_ROWS,
        days = listOf(
            DayGroup(MARCH_15, -3_000, listOf(A1)),
            DayGroup(MARCH_16, -1_000, listOf(A2)),
            DayGroup(MARCH_20, -6_000, listOf(B1)),
        ),
        breakdown = listOf(group(B, 6_000, 0.6), group(A, 4_000, 0.4)),
        choices = CHOICES_ALL,
    )

    /** Recopie littérale d'un état déjà vérifié, pour prouver qu'il n'a pas bougé. */
    private fun stateFields(s: MonthlyTransactionsUiState) = Expected(
        period = s.period, type = s.type!!, filter = s.filter, categoryId = s.selectedCategoryId, total = s.total,
        rows = s.transactions, days = s.dayGroups,
        breakdown = s.breakdown.map { Group(it.categoryId, it.name, it.colorArgb, it.total, it.share) },
        choices = s.categoryChoices.map { Group(it.categoryId, it.name, it.colorArgb, it.total, it.share) },
        refreshing = s.isRefreshing, failed = s.loadFailed,
    )

    private fun assertState(case: String, ca: String, state: MonthlyTransactionsUiState, exp: Expected) {
        val ctx = "État : ${state.describe()}. Lectures : ${readLog()}"
        assertEquals("$case — $ca : actualisation. $ctx", exp.refreshing, state.isRefreshing)
        assertEquals("$case — $ca : échec. $ctx", exp.failed, state.loadFailed)
        assertEquals("$case — $ca : période. $ctx", exp.period, state.period)
        assertEquals("$case — $ca : type. $ctx", exp.type, state.type)
        assertEquals("$case — $ca : statut. $ctx", exp.filter, state.filter)
        assertEquals("$case — $ca : catégorie active. $ctx", exp.categoryId, state.selectedCategoryId)
        assertEquals("$case — $ca : aucun compte filtré. $ctx", null, state.selectedAccountId)
        assertEquals("$case — $ca : recherche vide. $ctx", "", state.searchQuery)
        assertTrue("$case — $ca : mode analyse. $ctx", state.isAnalyticsMode)
        assertEquals("$case — $ca : devise. $ctx", EUR, state.currency)
        assertEquals("$case — $ca : total signé. $ctx", exp.total, state.total)
        assertEquals(
            "$case — $ca : lignes (ensemble, ordre, champs). $ctx",
            exp.rows,
            state.transactions,
        )
        assertEquals("$case — $ca : groupes de jours (date, total signé, lignes). $ctx", exp.days, state.dayGroups)
        assertGroups(case, "$ca : répartition de l'anneau", exp.breakdown.sortedBy { it.categoryId }, state.breakdown.sortedBy { it.categoryId }, ctx)
        assertGroups(case, "$ca : choix de catégories (ordre CA-09)", exp.choices, state.categoryChoices, ctx)
    }

    private fun assertGroups(case: String, what: String, expected: List<Group>, actual: List<CategoryBreakdown>, ctx: String) {
        assertEquals(
            "$case — $what : identifiants, dans l'ordre. $ctx",
            expected.map { it.categoryId },
            actual.map { it.categoryId },
        )
        expected.zip(actual).forEach { (e, a) ->
            assertEquals("$case — $what : nom du groupe ${e.categoryId}. $ctx", e.name, a.name)
            e.color?.let { assertEquals("$case — $what : couleur du groupe ${e.categoryId}. $ctx", it, a.colorArgb) }
            assertEquals("$case — $what : montant du groupe ${e.categoryId}. $ctx", e.total, a.total)
            assertEquals("$case — $what : part du groupe ${e.categoryId}. $ctx", e.share, a.share, 1e-12)
        }
    }

    /**
     * I-3 sur toute la trace : un état qui se déclare prêt présente exactement la liste et les choix
     * des critères qu'il affiche. [expectedFor] rend `null` pour des critères hors du cas.
     */
    private fun assertNoMixedReadyState(
        case: String,
        trace: List<MonthlyTransactionsUiState>,
        expectedFor: (PaidFilter) -> Pair<List<TransactionWithRelations>, List<Pair<Long?, Long>>>?,
    ) {
        trace.filter { it.ready() }.forEach { st ->
            val (rows, choices) = expectedFor(st.filter) ?: return@forEach
            val actualChoices = st.categoryChoices.map { it.categoryId to it.total }
            assertTrue(
                "$case — I-3 : un état prêt mélange deux sélections : ${st.describe()}. " +
                    "Attendu pour ${st.filter} : lignes ${rows.map { it.transaction.id }}, choix $choices. " +
                    "Trace : ${trace.map { it.describe() }}",
                st.transactions == rows && actualChoices == choices,
            )
        }
    }

    private fun expectedNoCategory(filter: PaidFilter) = when (filter) {
        PaidFilter.ALL -> F_ALL_ROWS to listOf(B.id to 6_000L, A.id to 4_000L)
        PaidFilter.PAID -> listOf(A1, B1) to listOf(B.id to 6_000L, A.id to 3_000L)
        PaidFilter.PLANNED -> listOf(A2) to listOf(A.id to 1_000L)
    }

    private fun expectedWithA(filter: PaidFilter) = when (filter) {
        PaidFilter.ALL -> null // l'ouverture se fait sans catégorie : ses états sont hors du sujet
        PaidFilter.PAID -> listOf(A1) to listOf(B.id to 6_000L, A.id to 3_000L)
        PaidFilter.PLANNED -> listOf(A2) to listOf(A.id to 1_000L)
    }

    private fun MonthlyTransactionsUiState.describe() =
        "{période=${period.start}..${period.end}, type=$type, statut=$filter, cat=$selectedCategoryId, " +
            "total=$total, lignes=${transactions.map { it.transaction.id }}, " +
            "anneau=${breakdown.map { "${it.categoryId}:${it.total}" }}, " +
            "choix=${categoryChoices.map { "${it.categoryId}:${it.total}" }}, " +
            "actualisation=$isRefreshing, échec=$loadFailed}"

    private companion object {
        const val EUR = "EUR"

        /** Délai d'échec de chaque attente, en temps réel ; déclaré ici, il borne cette classe seule. */
        const val TIMEOUT_MS = 10_000L

        val MARCH_1: LocalDate = LocalDate.parse("2026-03-01")
        val MARCH_15: LocalDate = LocalDate.parse("2026-03-15")
        val MARCH_16: LocalDate = LocalDate.parse("2026-03-16")
        val MARCH_20: LocalDate = LocalDate.parse("2026-03-20")
        val MARCH_21: LocalDate = LocalDate.parse("2026-03-21")
        val MARCH_22: LocalDate = LocalDate.parse("2026-03-22")
        val MARCH_30: LocalDate = LocalDate.parse("2026-03-30")
        val MARCH_31: LocalDate = LocalDate.parse("2026-03-31")
        val APRIL_1: LocalDate = LocalDate.parse("2026-04-01")
        val MARCH = AnalysisPeriod(MARCH_1, MARCH_31)

        // Bornes littérales en Europe/Paris, changement d'heure du 29 mars compris (fiche, M-01).
        val MARCH_START = ms("2026-02-28T23:00:00.000Z")
        val MARCH_30_START = ms("2026-03-29T22:00:00.000Z")
        val MARCH_31_START = ms("2026-03-30T22:00:00.000Z")
        val MARCH_END = ms("2026-03-31T21:59:59.999Z")
        val MARCH_15_START = ms("2026-03-14T23:00:00.000Z")
        val APRIL_1_START = ms("2026-03-31T22:00:00.000Z")

        fun ms(instant: String) = Instant.parse(instant).toEpochMilli()

        fun category(id: Long, name: String, type: TransactionType, color: Long, icon: String, parent: Long? = null) =
            CategoryEntity(id = id, name = name, type = type, colorArgb = color.toInt(), icon = icon, parentCategoryId = parent)

        val A = category(11, "QA-40 A", TransactionType.EXPENSE, 0xFF1565C0, "wallet")
        val B = category(12, "QA-40 B", TransactionType.EXPENSE, 0xFFE64A19, "cart")
        val RA = category(41, "QA-40 Revenu A", TransactionType.INCOME, 0xFF1565C0, "wallet")
        val RB = category(42, "QA-40 Revenu B", TransactionType.INCOME, 0xFFE64A19, "cart")
        val H1 = category(21, "QA-40 Transport", TransactionType.EXPENSE, 0xFF00897B, "train", parent = 201)
        val H2 = category(22, "QA-40 Transport", TransactionType.EXPENSE, 0xFF8E24AA, "train", parent = 202)
        val TA = category(31, "QA-40 Alpha tie", TransactionType.EXPENSE, 0xFF1565C0, "wallet")
        val TZ = category(32, "QA-40 Zulu tie", TransactionType.EXPENSE, 0xFFE64A19, "wallet")
        val CATEGORIES = listOf(A, B, RA, RB, H1, H2, TA, TZ)

        fun row(
            id: Long,
            title: String,
            amount: Long,
            type: TransactionType,
            status: TransactionStatus,
            date: String,
            category: CategoryEntity?,
        ): TransactionWithRelations {
            val at = ms(date)
            return TransactionWithRelations(
                transaction = TransactionEntity(
                    id = id, title = title, amount = amount, type = type, status = status,
                    kind = TransactionKind.STANDARD, date = at, accountId = NO_ACCOUNT_ID,
                    categoryId = category?.id ?: NO_CATEGORY_ID, note = null,
                    paidAt = if (status == TransactionStatus.PAID) at else null,
                    seriesId = null, seriesDate = null, isException = false,
                    linkedGoalId = null, linkedLoanId = null, cardId = null, deleted = false,
                ),
                category = category,
                account = null,
                tags = emptyList(),
            )
        }

        private val E = TransactionType.EXPENSE
        private val I = TransactionType.INCOME
        private val PAID = TransactionStatus.PAID
        private val PLANNED = TransactionStatus.PLANNED

        val A1 = row(111, "QA-40 A payée", 3_000, E, PAID, "2026-03-15T09:00:00Z", A)
        val A2 = row(112, "QA-40 A planifiée", 1_000, E, PLANNED, "2026-03-16T09:00:00Z", A)
        val B1 = row(113, "QA-40 B payée", 6_000, E, PAID, "2026-03-20T09:00:00Z", B)
        val B_AVRIL = row(114, "QA-40 B avril", 7_000, E, PAID, "2026-04-01T08:00:00Z", B)
        val N = row(115, "QA-40 Non classée", 1_000, E, PAID, "2026-03-21T09:00:00Z", null)
        val RA1 = row(411, "QA-40 Revenu A payée", 3_000, I, PAID, "2026-03-15T09:00:00Z", RA)
        val RA2 = row(412, "QA-40 Revenu A planifiée", 1_000, I, PLANNED, "2026-03-16T09:00:00Z", RA)
        val RB1 = row(413, "QA-40 Revenu B payée", 6_000, I, PAID, "2026-03-20T09:00:00Z", RB)
        val TH1 = row(121, "QA-40 Nord", 2_000, E, PAID, "2026-03-15T09:00:00Z", H1)
        val TH2 = row(122, "QA-40 Sud", 3_000, E, PAID, "2026-03-16T09:00:00Z", H2)
        val TTA = row(131, "QA-40 Tie Alpha", 1_000, E, PAID, "2026-03-22T09:00:00Z", TA)
        val TTZ = row(132, "QA-40 Tie Zulu", 1_000, E, PAID, "2026-03-21T09:00:00Z", TZ)

        val F_ALL_ROWS = listOf(A1, A2, B1)

        // Choix de base, montant décroissant (CA-09) ; parts brutes.
        val CHOICES_ALL = listOf(
            Group(B.id, B.name, B.colorArgb, 6_000, 0.6),
            Group(A.id, A.name, A.colorArgb, 4_000, 0.4),
        )
        val CHOICES_PAID = listOf(
            Group(B.id, B.name, B.colorArgb, 6_000, 2.0 / 3),
            Group(A.id, A.name, A.colorArgb, 3_000, 1.0 / 3),
        )
        val CHOICES_PLANNED = listOf(Group(A.id, A.name, A.colorArgb, 1_000, 1.0))
    }
}

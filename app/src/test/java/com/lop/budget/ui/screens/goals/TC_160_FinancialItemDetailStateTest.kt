package com.lop.budget.ui.screens.goals

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.data.repository.SettingsRepository
import com.lop.budget.domain.model.FinancialItemKind
import com.lop.budget.domain.model.FinancialItemKind.DEBT
import com.lop.budget.domain.model.FinancialItemKind.GOAL
import com.lop.budget.domain.model.FinancialItemKind.RECEIVABLE
import com.lop.budget.domain.model.ItemStatus
import com.lop.budget.domain.model.NO_CATEGORY_ID
import com.lop.budget.domain.model.ProgressView
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.domain.usecase.financial.FinancialItemDetail
import com.lop.budget.domain.usecase.financial.ObserveFinancialItemDetailUseCase
import com.lop.budget.ui.screens.goals.FinancialItemDetailUiState.Error
import com.lop.budget.ui.screens.goals.FinancialItemDetailUiState.Loaded
import com.lop.budget.ui.screens.goals.FinancialItemDetailUiState.Loading
import com.lop.budget.ui.screens.goals.FinancialItemDetailUiState.NotFound
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flowOf
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
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * TC-160 — Détail d'un objectif, d'une dette ou d'une créance : états, identité de route et
 * réessai (US LOP-203).
 *
 * ## Niveau et chaîne exercée
 * ViewModel, JVM. Système testé : [FinancialItemDetailViewModel] réel, avec un `SavedStateHandle`
 * réel construit avec les arguments de chaque cas. Doublures strictes, sans `relaxed` :
 * [ObserveFinancialItemDetailUseCase], stubbé à arguments exacts (un appel avec d'autres arguments
 * n'a pas de réponse), et `SettingsRepository.currency`, qui rend `flowOf("EUR")`.
 *
 * Chaque invocation du use case reçoit un flux neuf, piloté par un canal : émettre, échouer ou se
 * taire. Une relecture se distingue ainsi d'un réabonnement.
 *
 * ## Traçabilité — cas → CA / invariant → production
 * ```
 * V-01      CA-07, I-3        uiState : stateIn(Loading) + onStart(Loading), puis Loaded
 * V-02 ×2   CA-07, I-1, I-3   catch { Error } ; retry() → retries.flatMapLatest → nouvelle invocation
 * V-03 ×6   CA-02, I-2        kind / id lus dans SavedStateHandle ; kind illisible → NotFound sans lecture
 * V-04      CA-06, CA-02      combine(observeDetail, currency) sur la même collecte, null → NotFound
 * ```
 *
 * ## Lecture des termes de la fiche (mise à jour du 30 octobre 2026)
 * Le use case livré rend `Flow<FinancialItemDetail?>` : « le flux émet Found(X) » se lit « émet X »,
 * « émet NotFound » se lit « émet null ». `Loaded` prend le détail puis la devise.
 *
 * ## Oracles
 * - Trace complète des états, collecte ouverte avant la première émission et gardée jusqu'à la fin
 *   (`WhileSubscribed` arrêterait sinon le partage). Après chaque action : `advanceUntilIdle()` puis
 *   `runCurrent()`, sans lequel le collecteur de fond ne tourne pas.
 * - `Loaded.detail` égal en entier à l'objet émis : le ViewModel ne transforme rien.
 * - Invocations exactes du use case, puis `confirmVerified`. `any()` n'apparaît que dans
 *   `verify(exactly = 0)`, pour prouver qu'aucune lecture n'est tentée (`app/src/test/AGENTS.md` §4).
 *   Le nombre de lectures de la devise n'est pas un contrat : `settings` n'est pas soumis à
 *   `confirmVerified`.
 *
 * ## ANO connues
 * **LOP-221** — un identifiant absent est remplacé par 0 et lu (`FinancialItemDetailViewModel.kt`
 * l. 49) : `observeDetail(GOAL, 0)` est invoqué au lieu de conclure introuvable sans lecture (I-2).
 * V-03 « id absent » était **rouge** sur `cc89a660`. Le cas n'est pas joignable par la navigation
 * (la route exige un id numérique) ; la fiche le maintient pour CA-02. La trace restait à `Loading`
 * parce que la doublure stricte n'a pas de réponse pour `(GOAL, 0)` : l'oracle est la lecture
 * tentée, pas l'état. **Corrigée le 30 octobre 2026** (identifiant nullable, introuvable sans
 * lecture) : V-03 « id absent » vert, TC-160 10 sur 10, suite JVM 871 sur 871, TC-161 16 sur 16.
 *
 * ## Résultats — 30 octobre 2026, JVM, production de `cc89a660`
 * 9 verts, 1 rouge métier (V-03 id absent, LOP-221). Preuves de sensibilité des verts, séries à cas
 * disjoints retirées aussitôt (`git checkout`), puis retour à 9 verts et le même rouge :
 * ```
 * N1   résumé à zéro émis au démarrage (onStart)      → V-01, V-02 ×2, V-03 lisibles ×3, V-04
 * N2   retry() sans effet (retries.take(1))           → V-02 ×2
 * N6a  null filtré : Loaded conservé après introuvable → V-03 lisibles ×3, V-04
 * N3   type codé en dur à GOAL                        → V-03 DEBT, V-03 RECEIVABLE
 * N4   type illisible replié sur GOAL                 → V-03 kind absent, V-03 kind SAVINGS
 * N5   première valeur seulement (take(1))            → V-02 échec après succès, V-04
 * N6b  catch sans Error : Loaded conservé après échec → V-02 ×2
 * ```
 * Séries : {N3, N4}, {N1}, {N2, N6a}, {N5}, {N6b}.
 *
 * ## Hors périmètre
 * Lecture en base, sélection et déclencheurs (TC-159) ; rendu, navigation, barre globale (TC-161) ;
 * CA-08 (aucune fiche).
 *
 * ## Exécution
 * ```
 * export TMP='C:\gtmp' TEMP='C:\gtmp'
 * ./gradlew :app:testDebugUnitTest --tests "*FinancialItemDetailStateTest"
 * ```
 */
class FinancialItemDetailStateTest {

    private val mainDispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()

    private lateinit var observeDetail: ObserveFinancialItemDetailUseCase
    private lateinit var settings: SettingsRepository
    private lateinit var vm: FinancialItemDetailViewModel

    /** Un canal par invocation du use case, dans l'ordre des invocations. */
    private val sources = mutableListOf<Channel<FinancialItemDetail?>>()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        observeDetail = mockk()
        settings = mockk()
        every { settings.currency } returns flowOf(EUR)
    }

    @After
    fun tearDown() {
        store.clear()
        sources.forEach { it.close() }
        Dispatchers.resetMain()
    }

    // ==============================================================================================
    // V-01 — CA-07, I-3 : chargement sans donnée, puis le détail reçu tel quel
    // ==============================================================================================

    @Test
    fun `V-01 - given GOAL 7 et un flux muet when il emet DETAIL-A then Loading jusque-la puis Loaded DETAIL-A EUR`() = runTest {
        val case = "V-01"
        stub(GOAL, 7L, invocations = 1)
        val trace = openCollection(GOAL.name, 7L)

        assertOnlyLoading(case, trace)

        sources[0].send(DETAIL_A)
        settle()
        assertEquals("$case — CA-07 : états successifs.", listOf(Loading, Loaded(detail = DETAIL_A, currency = EUR)), trace)

        verifyReads(case, trace, kind = GOAL, id = 7L, count = 1)
    }

    // ==============================================================================================
    // V-02 — CA-07, I-1, I-3 : erreur sans donnée périmée ; Réessayer = une relecture
    // ==============================================================================================

    @Test
    fun `V-02 echec initial - given GOAL 7 when le flux echoue puis retry puis DETAIL-A then Error, jamais Loaded avant le nouveau resultat`() =
        runTest {
            val case = "V-02 échec initial"
            stub(GOAL, 7L, invocations = 2)
            val trace = openCollection(GOAL.name, 7L)

            sources[0].close(ERR)
            settle()
            assertEquals("$case — CA-07, I-3 : état après l'échec. Trace : $trace", Error, trace.last())

            val afterRetry = retryAndWatch(case, trace)

            sources[1].send(DETAIL_A)
            settle()
            assertEquals("$case — CA-07 : état après la relecture. Trace : $trace", Loaded(detail = DETAIL_A, currency = EUR), trace.last())
            assertEquals(
                "$case — I-3 : aucun Loaded avant le premier résultat. Trace : $trace",
                listOf(Loaded(detail = DETAIL_A, currency = EUR)),
                trace.filterIsInstance<Loaded>(),
            )
            assertNoLoadedBetween(case, trace, afterRetry)
            verifyReads(case, trace, kind = GOAL, id = 7L, count = 2)
        }

    @Test
    fun `V-02 echec apres succes - given GOAL 7 charge when le flux echoue puis retry then Error remplace Loaded, puis relecture`() =
        runTest {
            val case = "V-02 échec après succès"
            stub(GOAL, 7L, invocations = 2)
            val trace = openCollection(GOAL.name, 7L)

            sources[0].send(DETAIL_A)
            settle()
            assertEquals("$case — montage : premier résultat. Trace : $trace", Loaded(detail = DETAIL_A, currency = EUR), trace.last())

            sources[0].close(ERR)
            settle()
            assertEquals(
                "$case — CA-07, I-3 : après l'échec, l'ancien Loaded ne doit pas rester présenté comme à jour. Trace : $trace",
                Error,
                trace.last(),
            )

            val afterRetry = retryAndWatch(case, trace)

            sources[1].send(DETAIL_A)
            settle()
            assertEquals("$case — CA-07 : état après la relecture. Trace : $trace", Loaded(detail = DETAIL_A, currency = EUR), trace.last())
            assertNoLoadedBetween(case, trace, afterRetry)
            verifyReads(case, trace, kind = GOAL, id = 7L, count = 2)
        }

    // ==============================================================================================
    // V-03 — CA-02, I-2 : arguments transmis tels quels ; illisibles sans lecture de repli
    // ==============================================================================================

    @Test
    fun `V-03 GOAL - given les arguments GOAL 7 when le flux rend null then une lecture GOAL 7 puis NotFound`() =
        runTest { readableNotFound("V-03 GOAL", GOAL) }

    @Test
    fun `V-03 DEBT - given les arguments DEBT 7 when le flux rend null then une lecture DEBT 7 puis NotFound`() =
        runTest { readableNotFound("V-03 DEBT", DEBT) }

    @Test
    fun `V-03 RECEIVABLE - given les arguments RECEIVABLE 7 when le flux rend null then une lecture RECEIVABLE 7 puis NotFound`() =
        runTest { readableNotFound("V-03 RECEIVABLE", RECEIVABLE) }

    @Test
    fun `V-03 id absent - given kind GOAL sans id when le ViewModel demarre then NotFound sans aucune lecture`() =
        runTest { unreadableNotFound("V-03 id absent", kind = GOAL.name, id = null) }

    @Test
    fun `V-03 kind absent - given id 7 sans kind when le ViewModel demarre then NotFound sans aucune lecture`() =
        runTest { unreadableNotFound("V-03 kind absent", kind = null, id = 7L) }

    @Test
    fun `V-03 kind SAVINGS - given le kind inconnu SAVINGS et id 7 when le ViewModel demarre then NotFound sans aucune lecture`() =
        runTest { unreadableNotFound("V-03 kind SAVINGS", kind = "SAVINGS", id = 7L) }

    // ==============================================================================================
    // V-04 — CA-06, CA-02 : un nouveau résultat remplace l'état, sans réabonnement
    // ==============================================================================================

    @Test
    fun `V-04 - given GOAL 7 charge when le meme flux emet DETAIL-A2 puis null then Loaded A, Loaded A2, NotFound sans Loading ni relecture`() =
        runTest {
            val case = "V-04"
            stub(GOAL, 7L, invocations = 1)
            val trace = openCollection(GOAL.name, 7L)

            sources[0].send(DETAIL_A)
            settle()
            sources[0].send(DETAIL_A2)
            settle()
            sources[0].send(null)
            settle()

            assertEquals(
                "$case — CA-06, CA-02 : états successifs après le chargement initial (aucun Loading intercalé).",
                listOf(Loaded(detail = DETAIL_A, currency = EUR), Loaded(detail = DETAIL_A2, currency = EUR), NotFound),
                trace.dropWhile { it == Loading },
            )
            assertEquals("$case — CA-02 : l'état final ne garde aucune donnée de DETAIL-A2.", NotFound, vm.uiState.value)
            verifyReads(case, trace, kind = GOAL, id = 7L, count = 1)
        }

    // ==============================================================================================
    // Déroulés communs
    // ==============================================================================================

    private suspend fun TestScope.readableNotFound(case: String, kind: FinancialItemKind) {
        stub(kind, 7L, invocations = 1)
        val trace = openCollection(kind.name, 7L)
        sources[0].send(null)
        settle()
        assertEquals("$case — CA-02 : état final. Trace : $trace", NotFound, trace.last())
        assertEquals("$case — I-3 : aucun Loaded. Trace : $trace", emptyList<Loaded>(), trace.filterIsInstance<Loaded>())
        verifyReads(case, trace, kind = kind, id = 7L, count = 1)
    }

    /** Arguments illisibles : aucune lecture, d'aucun élément, et le chargement se termine (I-2). */
    private fun TestScope.unreadableNotFound(case: String, kind: String?, id: Long?) {
        val trace = openCollection(kind, id)
        try {
            verify(exactly = 0) { observeDetail(any(), any()) }
            confirmVerified(observeDetail)
        } catch (e: AssertionError) {
            throw AssertionError("$case — CA-02, I-2 : une lecture a été tentée pour une identité illisible. Trace : $trace", e)
        }
        assertEquals("$case — CA-02 : état final (le chargement doit se terminer). Trace : $trace", NotFound, vm.uiState.value)
        assertEquals("$case — I-3 : aucun Loaded. Trace : $trace", emptyList<Loaded>(), trace.filterIsInstance<Loaded>())
    }

    /** `retry()` puis vidage ; rend l'index de la trace où commence l'après-réessai. */
    private fun TestScope.retryAndWatch(case: String, trace: List<FinancialItemDetailUiState>): Int {
        val index = trace.size
        vm.retry()
        settle()
        assertEquals("$case — CA-07 : Réessayer doit relancer une lecture. Trace : $trace", 2, invoked)
        return index
    }

    private fun assertNoLoadedBetween(case: String, trace: List<FinancialItemDetailUiState>, from: Int) {
        val window = trace.subList(from, trace.lastIndex)
        assertEquals(
            "$case — CA-07, I-3 : entre retry() et le nouveau résultat, seuls Loading ou Error sont admis. Trace : $trace",
            emptyList<FinancialItemDetailUiState>(),
            window.filter { it != Loading && it != Error },
        )
    }

    private fun assertOnlyLoading(case: String, trace: List<FinancialItemDetailUiState>) {
        assertEquals("$case — I-3 : avant tout résultat, l'état est Loading et rien d'autre.", listOf(Loading), trace)
    }

    private fun verifyReads(case: String, trace: List<FinancialItemDetailUiState>, kind: FinancialItemKind, id: Long, count: Int) {
        try {
            verify(exactly = count) { observeDetail(kind, id) }
            confirmVerified(observeDetail)
        } catch (e: AssertionError) {
            throw AssertionError("$case — I-1, I-2 : lectures attendues : $count × ($kind, $id), rien d'autre. Trace : $trace", e)
        }
    }

    /**
     * Stub à arguments exacts : l'invocation n° k reçoit le flux du canal `sources[k]`. Une
     * invocation au-delà de [invocations] n'a pas de canal et fait échouer le cas.
     */
    private fun stub(kind: FinancialItemKind, id: Long, invocations: Int) {
        repeat(invocations) { sources += Channel(Channel.UNLIMITED) }
        every { observeDetail(kind, id) } answers { sources[invoked++].receiveAsFlow() }
    }

    /** Invocations reçues par la doublure du use case. */
    private var invoked = 0

    private fun TestScope.openCollection(kind: String?, id: Long?): MutableList<FinancialItemDetailUiState> {
        val args = buildMap<String, Any> {
            kind?.let { put(FinancialItemDetailViewModel.ARG_KIND, it) }
            id?.let { put(FinancialItemDetailViewModel.ARG_ID, it) }
        }
        vm = ViewModelProvider(
            store,
            viewModelFactory { initializer { FinancialItemDetailViewModel(SavedStateHandle(args), observeDetail, settings) } },
        )[FinancialItemDetailViewModel::class.java]
        val trace = mutableListOf<FinancialItemDetailUiState>()
        backgroundScope.launch { vm.uiState.collect { trace += it } }
        settle()
        return trace
    }

    private fun TestScope.settle() {
        advanceUntilIdle()
        runCurrent()
    }

    private companion object {
        const val EUR = "EUR"

        val D1: Long = Instant.parse("2026-03-10T10:00:00Z").toEpochMilli()
        val ECH: Long = Instant.parse("2026-12-31T11:00:00Z").toEpochMilli()

        val ERR = IllegalStateException("QA-203 échec contrôlé")

        /** G1 de TC-159, valeurs de doublure : id 7, une seule ligne liée, TX-1 d'ID 101. */
        val DETAIL_A = FinancialItemDetail(
            kind = GOAL, id = 7L, name = "QA-203 Vacances", icon = "savings", colorArgb = 0xFF4CAF50.toInt(),
            targetCents = 100_000, startingCents = 10_000, progress = ProgressView(30_000, 70_000, 30),
            status = ItemStatus.ACTIVE, dueDate = ECH, accountId = 3L, accountName = "QA-203 Compte",
            comment = "QA-203 Note vacances", counterparty = null, debtType = null, interestRate = null,
            transactions = listOf(
                TransactionWithRelations(
                    transaction = TransactionEntity(
                        id = 101L, title = "QA-203 Versement mars", amount = 20_000, type = TransactionType.INCOME,
                        status = TransactionStatus.PAID, date = D1, paidAt = D1, accountId = 3L,
                        categoryId = NO_CATEGORY_ID, linkedGoalId = 7L,
                    ),
                    category = null,
                    account = null,
                    tags = emptyList(),
                )
            ),
        )

        val DETAIL_A2 = DETAIL_A.copy(name = "QA-203 Vacances été")
    }
}

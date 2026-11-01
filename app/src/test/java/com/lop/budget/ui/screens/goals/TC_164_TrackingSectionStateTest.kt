package com.lop.budget.ui.screens.goals

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lop.budget.data.repository.SettingsRepository
import com.lop.budget.domain.model.FinancialItemKind
import com.lop.budget.domain.model.FinancialItemKind.DEBT
import com.lop.budget.domain.model.FinancialItemKind.GOAL
import com.lop.budget.domain.model.FinancialItemKind.RECEIVABLE
import com.lop.budget.domain.model.ItemStatus.ACTIVE
import com.lop.budget.domain.model.ProgressView
import com.lop.budget.domain.usecase.financial.FinancialItemSummary
import com.lop.budget.domain.usecase.financial.ObserveFinancialItemListUseCase
import com.lop.budget.ui.screens.goals.ListLoad.Error
import com.lop.budget.ui.screens.goals.ListLoad.Loaded
import com.lop.budget.ui.screens.goals.ListLoad.Loading
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
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
 * TC-164 — Suivi : états de lecture, réessai et reprise de collecte (ViewModel, US LOP-22).
 *
 * ## Niveau et chaîne exercée
 * ViewModel, JVM. Système testé : [GoalsViewModel] réel. Doublures strictes, sans `relaxed` :
 * [ObserveFinancialItemListUseCase], stubbé à arguments exacts pour chacun des trois types (son
 * contrat est prouvé par TC-163), et `SettingsRepository.currency`, qui rend `flowOf("EUR")`.
 * Chaque invocation du use case reçoit un flux neuf, piloté par un canal : émettre, échouer ou se
 * taire. Une relecture se distingue ainsi d'un réabonnement.
 *
 * ## Traçabilité — cas → CA / invariant → production
 * ```
 * V-01        CA-06, I-3   stateIn(GoalsUiState()) + asListLoad : onStart(Loading), puis Loaded
 * V-03 ×3     CA-06, I-3   une liste par lecture : observeList(kind) → goals / debts / receivables
 * V-04 ×2     CA-06, I-3   catch { Error } ; retry() → retries.flatMapLatest → nouvelle invocation
 * V-04 P-4    CA-06 (P-4)  catch par liste dans combine : les deux autres listes gardent leur résultat
 * V-05        CA-07, I-3   même collecte : chaque émission remplace la liste, sans relecture
 * V-07        CA-01        reprise après WhileSubscribed(5000) : aucun état intermédiaire
 * ```
 *
 * ## Écarts à la livraison (fiche mise à jour le 31 octobre 2026)
 * - Le ViewModel expose trois listes, chacune en [ListLoad] (« FinancialListState » de la fiche) :
 *   `goals`, `debts`, `receivables`. `retry()` relance les trois lectures ; le décompte n'est asserté
 *   que pour la liste en échec, comme la fiche le prévoit.
 * - Il ne porte pas l'onglet (tenu par `LopNavHost`) ni le retour de création (`GoalsScreen`) :
 *   V-02 et V-06 sont reportés dans TC-162 (U-02, U-01 b3, U-01 e, U-09).
 *
 * ## Oracles
 * - Trace complète des états, collecte ouverte avant la première émission. Après chaque action :
 *   `advanceUntilIdle()` puis `runCurrent()`, sans lequel le collecteur de fond ne tourne pas.
 * - `Loading` et `Error` sont des objets sans données : type exact asserté. Les éléments de
 *   `Loaded` sont égaux à ceux émis (le ViewModel ne transforme rien) ; l'ordre n'est pas asserté.
 * - Invocations comptées par la doublure. Aucun `any()`.
 *
 * ## ANO connues
 * **LOP-222** — au retour après plus de 5 s, `asListLoad` rejoue `onStart { emit(Loading) }` : les
 * trois listes repassent par le chargement entre l'ancienne et la nouvelle liste (CA-01).
 * V-07 est **rouge** tant que l'anomalie n'est pas corrigée ; l'oracle est conservé. Message réel :
 * `V-07 — CA-01 : chargement ou liste vide entre l'ancienne et la nouvelle liste. … but was:
 * <[{goals=Loading, debts=Loading, receivables=Loading}]>`. TC-162 U-01 b2 vise la même cause.
 * **Corrigée le 31 octobre 2026** (la lecture qui redémarre part de la liste déjà chargée) : V-07
 * rouge sur `c7e82123`, vert après correction ; TC-164 9 sur 9, suite JVM 898 sur 898.
 *
 * ## Résultats — 31 octobre 2026, JVM, production de `c7e82123`
 * 8 verts, 1 rouge métier (V-07, LOP-222). Preuves de sensibilité des verts : production copiée,
 * mutée, restaurée depuis la copie après chaque passage (aucun écart à HEAD), puis le même résultat.
 * ```
 * N2  retry() sans effet                            → V-04 ×2
 * N6  première valeur seulement sur GOAL            → V-05
 * N1  liste vide chargée au début de chaque lecture → V-01, V-03 ×3, V-04 ×2, V-05
 * N3  erreur avalée (catch vide)                    → V-04 ×2, V-04 P-4
 * N4  dettes et créances interverties               → V-03 DEBT, V-03 RECEIVABLE, V-04 ×2, V-04 P-4
 * N5  un seul catch pour les trois listes           → V-04 P-4
 * ```
 * Séries : {N2, N6}, puis chacune des autres seule. V-07 reste rouge dans chaque passage (LOP-222).
 *
 * ## Hors périmètre
 * Lecture en base, filtres et déclencheurs (TC-163) ; rendu, bouton d'ajout, navigation, onglet,
 * position de liste (TC-162) ; filtre Archives (LOP-206, trou assumé).
 *
 * ## Exécution
 * ```
 * export TMP='C:\gtmp' TEMP='C:\gtmp'
 * ./gradlew :app:testDebugUnitTest --tests "*TrackingSectionStateTest"
 * ```
 */
class TrackingSectionStateTest {

    private val mainDispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()

    private val observeList: ObserveFinancialItemListUseCase = mockk()
    private val settings: SettingsRepository = mockk()
    private lateinit var vm: GoalsViewModel

    /** Un canal par invocation du use case, par type, dans l'ordre des invocations. */
    private val sources = FinancialItemKind.entries.associateWith { mutableListOf<Channel<List<FinancialItemSummary>>>() }

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        every { settings.currency } returns flowOf("EUR")
        // Stub à arguments exacts, un par type : chaque invocation reçoit un flux neuf.
        FinancialItemKind.entries.forEach { kind ->
            every { observeList(kind) } answers {
                Channel<List<FinancialItemSummary>>(Channel.UNLIMITED).also { sources.getValue(kind) += it }.receiveAsFlow()
            }
        }
    }

    @After
    fun tearDown() {
        store.clear()
        sources.values.flatten().forEach { it.close() }
        Dispatchers.resetMain()
    }

    // ==============================================================================================
    // V-01 — CA-06, I-3 : chargement, puis la liste émise
    // ==============================================================================================

    @Test
    fun `V-01 - given aucune lecture rendue when le flux GOAL emet S-G1 et S-NC then Loading puis Loaded, une seule lecture GOAL`() = runTest {
        val case = "V-01"
        val trace = open()
        assertEquals("$case — CA-06, I-3 : avant toute émission, Objectifs est en chargement. ${show(trace)}", Loading, trace.last().goals)
        assertEquals(
            "$case — I-3 : aucune liste chargée (vide comprise) avant la première émission. ${show(trace)}",
            emptyList<String>(),
            trace.filter { it.goals is Loaded }.map(::describe),
        )

        send(GOAL, 0, listOf(S_G1, S_NC))
        settle()

        assertEquals("$case — CA-06 : éléments émis, égaux. ${show(trace)}", loaded(S_G1, S_NC), norm(trace.last().goals))
        assertEquals("$case — CA-06 : lectures GOAL. ${show(trace)}", 1, sources.getValue(GOAL).size)
    }

    // ==============================================================================================
    // V-03 — CA-06, I-3 : un vide réussi n'est ni un chargement ni une erreur, liste par liste
    // ==============================================================================================

    @Test
    fun `V-03 GOAL - given seul le flux GOAL emet une liste vide when on lit l'etat then goals vide charge, les deux autres en chargement`() =
        runTest { emptyCase("V-03 GOAL", GOAL) }

    @Test
    fun `V-03 DEBT - given seul le flux DEBT emet une liste vide when on lit l'etat then debts vide charge, les deux autres en chargement`() =
        runTest { emptyCase("V-03 DEBT", DEBT) }

    @Test
    fun `V-03 RECEIVABLE - given seul le flux RECEIVABLE emet une liste vide when on lit l'etat then receivables vide charge, les deux autres en chargement`() =
        runTest { emptyCase("V-03 RECEIVABLE", RECEIVABLE) }

    private fun TestScope.emptyCase(case: String, kind: FinancialItemKind) {
        val trace = open()
        val mark = trace.size
        send(kind, 0, emptyList())
        settle()

        assertEquals("$case — CA-06 : vide réussi. ${show(trace)}", Loaded(emptyList<FinancialItemSummary>()), trace.last().list(kind))
        assertEquals(
            "$case — CA-06, I-3 : après l'émission, ni chargement ni erreur pour $kind. ${show(trace)}",
            emptyList<String>(),
            trace.drop(mark).filter { it.list(kind) != Loaded(emptyList<FinancialItemSummary>()) }.map(::describe),
        )
        (FinancialItemKind.entries - kind).forEach { other ->
            assertEquals("$case — CA-06 : $other n'a rien reçu, il reste en chargement. ${show(trace)}", Loading, trace.last().list(other))
        }
    }

    // ==============================================================================================
    // V-04 — CA-06, I-3 : erreur sans liste périmée, Réessayer = exactement une relecture
    // ==============================================================================================

    @Test
    fun `V-04 echec avant resultat - given DEBT echoue when retry puis S-D1 emise then Error, jamais Loaded avant le nouveau resultat, une lecture de plus`() =
        runTest { retryCase("V-04 échec avant résultat", resultFirst = false) }

    @Test
    fun `V-04 echec apres resultat - given DEBT charge puis en echec when retry puis S-D1 emise then Error et non l'ancienne liste, une lecture de plus`() =
        runTest { retryCase("V-04 échec après résultat", resultFirst = true) }

    private fun TestScope.retryCase(case: String, resultFirst: Boolean) {
        val trace = open()
        if (resultFirst) {
            send(DEBT, 0, listOf(S_D1))
            settle()
            if (norm(trace.last().debts) != loaded(S_D1)) fail("$case — montage : Dettes devait être chargée avant l'échec. ${show(trace)}")
        }
        val failedAt = trace.size
        failRead(DEBT, 0)
        settle()
        assertEquals("$case — CA-06, I-3 : état après l'échec. ${show(trace)}", Error, trace.last().debts)
        assertEquals(
            "$case — I-3 : une liste chargée subsiste après l'échec. ${show(trace)}",
            emptyList<String>(),
            trace.drop(failedAt + 1).filter { it.debts !is Error }.map(::describe),
        )

        val readsBefore = sources.getValue(DEBT).size
        val retriedAt = trace.size
        vm.retry()
        settle()
        assertEquals(
            "$case — CA-06, I-3 : entre retry() et le nouveau résultat, seuls Loading ou Error sont admis. ${show(trace)}",
            emptyList<String>(),
            trace.drop(retriedAt).filter { it.debts != Loading && it.debts != Error }.map(::describe),
        )

        send(DEBT, sources.getValue(DEBT).lastIndex, listOf(S_D1))
        settle()
        assertEquals("$case — CA-06 : nouveau résultat. ${show(trace)}", loaded(S_D1), norm(trace.last().debts))
        assertEquals(
            "$case — CA-06 : lectures DEBT ajoutées par retry() (ni 0, ni 2). ${show(trace)}",
            1,
            sources.getValue(DEBT).size - readsBefore,
        )
    }

    @Test
    fun `V-04 P-4 - given Objectifs et Creances charges when la lecture DEBT echoue then seules les dettes sont en erreur`() = runTest {
        val case = "V-04 P-4"
        val trace = open()
        send(GOAL, 0, listOf(S_G1))
        send(RECEIVABLE, 0, listOf(S_R1))
        settle()
        if (norm(trace.last().goals) != loaded(S_G1) || norm(trace.last().receivables) != loaded(S_R1)) {
            fail("$case — montage : Objectifs et Créances devaient être chargés avant l'échec. ${show(trace)}")
        }
        val failedAt = trace.size
        failRead(DEBT, 0)
        settle()

        assertEquals("$case — CA-06 : Dettes en erreur. ${show(trace)}", Error, trace.last().debts)
        assertEquals(
            "$case — CA-06, P-4 : Objectifs et Créances ont quitté leur résultat après l'échec des dettes. ${show(trace)}",
            emptyList<String>(),
            trace.drop(failedAt).filter { norm(it.goals) != loaded(S_G1) || norm(it.receivables) != loaded(S_R1) }.map(::describe),
        )
    }

    // ==============================================================================================
    // V-05 — CA-07, I-3 : un résultat actualisé remplace la liste sans repasser par le chargement
    // ==============================================================================================

    @Test
    fun `V-05 - given Objectifs observe when le meme flux emet quatre listes then quatre Loaded successifs, sans Loading ni relecture`() = runTest {
        val case = "V-05"
        val trace = open()
        val emissions = listOf(listOf(S_G1), listOf(S_G1B), listOf(S_G1B, S_NC), listOf(S_NC))
        emissions.forEach {
            send(GOAL, 0, it)
            settle()
        }
        assertEquals(
            "$case — CA-07, I-3 : états successifs d'Objectifs après le premier résultat. ${show(trace)}",
            emissions.map { loaded(*it.toTypedArray()) },
            trace.map { norm(it.goals) }.dropWhile { it !is Loaded }.fold(emptyList<ListLoad<FinancialItemSummary>>()) { acc, s ->
                if (acc.lastOrNull() == s) acc else acc + s
            },
        )
        assertEquals("$case — CA-07 : aucune relecture GOAL. ${show(trace)}", 1, sources.getValue(GOAL).size)
    }

    // ==============================================================================================
    // V-07 — CA-01 : reprise de collecte sans chargement rejoué ni liste vide
    // ==============================================================================================

    @Test
    fun `V-07 - given Objectifs charge when la collecte s'arrete 6 s puis reprend then aucun Loading ni vide entre l'ancienne et la nouvelle liste`() = runTest {
        val case = "V-07"
        val first = mutableListOf<GoalsUiState>()
        vm = newViewModel()
        val job = backgroundScope.launch { vm.uiState.collect { first += it } }
        settle()
        send(GOAL, 0, listOf(S_G1))
        settle()
        if (norm(first.last().goals) != loaded(S_G1)) fail("$case — montage : Objectifs devait être chargé. ${show(first)}")

        job.cancel()
        runCurrent()
        advanceTimeBy(6_000)
        runCurrent()

        val resumed = mutableListOf<GoalsUiState>()
        backgroundScope.launch { vm.uiState.collect { resumed += it } }
        runCurrent()
        if (sources.getValue(GOAL).size != 2) {
            fail("$case — montage : la reprise devait relancer la lecture GOAL (WhileSubscribed expiré) ; lectures : ${sources.getValue(GOAL).size}.")
        }
        send(GOAL, 1, listOf(S_G1))
        settle()

        assertEquals("$case — CA-01 : premier état reçu à la reprise. ${show(resumed)}", loaded(S_G1), norm(resumed.first().goals))
        assertEquals(
            "$case — CA-01 : chargement ou liste vide entre l'ancienne et la nouvelle liste. ${show(resumed)}",
            emptyList<String>(),
            resumed.filter { it.goals == Loading || it.goals == Loaded(emptyList<FinancialItemSummary>()) }.map(::describe),
        )
    }

    // ==============================================================================================
    // Montage
    // ==============================================================================================

    private fun newViewModel(): GoalsViewModel = ViewModelProvider(
        store,
        viewModelFactory { initializer { GoalsViewModel(observeList, settings) } },
    )[GoalsViewModel::class.java]

    private fun TestScope.open(): MutableList<GoalsUiState> {
        vm = newViewModel()
        val trace = mutableListOf<GoalsUiState>()
        backgroundScope.launch { vm.uiState.collect { trace += it } }
        settle()
        return trace
    }

    private fun TestScope.settle() {
        advanceUntilIdle()
        runCurrent()
    }

    private fun send(kind: FinancialItemKind, invocation: Int, items: List<FinancialItemSummary>) {
        channel(kind, invocation).trySend(items)
    }

    /** La lecture n° [invocation] de [kind] échoue avec [ERR]. */
    private fun failRead(kind: FinancialItemKind, invocation: Int) {
        channel(kind, invocation).close(ERR)
    }

    private fun channel(kind: FinancialItemKind, invocation: Int) =
        checkNotNull(sources.getValue(kind).getOrNull(invocation)) {
            "Montage : aucune invocation n° $invocation de observeList($kind) ; reçues : ${sources.getValue(kind).size}."
        }

    private fun GoalsUiState.list(kind: FinancialItemKind) = when (kind) {
        GOAL -> goals
        DEBT -> debts
        RECEIVABLE -> receivables
    }

    /** L'ordre des lignes n'est pas fixé par l'US : on compare des listes triées. */
    private fun norm(load: ListLoad<FinancialItemSummary>) =
        if (load is Loaded) Loaded(load.items.sortedWith(compareBy({ it.id }, { it.name }))) else load

    private fun loaded(vararg items: FinancialItemSummary) = norm(Loaded(items.toList()))

    private fun show(trace: List<GoalsUiState>) =
        "Trace : ${trace.map(::describe)} ; lectures : ${sources.mapValues { it.value.size }}"

    private companion object {
        val ERR = IllegalStateException("QA-22 échec contrôlé")

        // Valeurs de doublure, pas des IDs Room. S-G1 et S-D1 partagent l'ID 7.
        val S_G1 = FinancialItemSummary(GOAL, 7, "QA-22 Vacances", "savings", 0xFF4CAF50.toInt(), 100_000, ProgressView(30_000, 70_000, 30), ACTIVE)
        val S_G1B = S_G1.copy(name = "QA-22 Vacances été")
        val S_NC = FinancialItemSummary(GOAL, 9, "QA-22 Sans cible", "home", 0xFF795548.toInt(), 0, ProgressView(2_000, null, null), ACTIVE)
        val S_D1 = FinancialItemSummary(DEBT, 7, "QA-22 Prêt auto", "car", 0xFFF44336.toInt(), 200_000, ProgressView(60_000, 140_000, 30), ACTIVE)
        val S_R1 = FinancialItemSummary(RECEIVABLE, 8, "QA-22 Prêt à Sam", "person", 0xFF2196F3.toInt(), 30_000, ProgressView(1_000, 29_000, 3), ACTIVE)

        fun describe(load: ListLoad<FinancialItemSummary>): String = when (load) {
            Loading -> "Loading"
            Error -> "Error"
            is Loaded -> "Loaded${load.items.map { "${it.kind}#${it.id}« ${it.name} »" }}"
        }

        fun describe(s: GoalsUiState) = "{goals=${describe(s.goals)}, debts=${describe(s.debts)}, receivables=${describe(s.receivables)}}"
    }
}

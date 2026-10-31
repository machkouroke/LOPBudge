package com.lop.budget.domain.usecase.financial

import android.app.Application
import android.database.Cursor
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.Event
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import app.cash.turbine.turbineScope
import com.lop.budget.data.local.LopDatabase
import com.lop.budget.data.local.entity.GoalEntity
import com.lop.budget.data.local.entity.LoanEntity
import com.lop.budget.data.repository.GoalRepository
import com.lop.budget.data.repository.LoanRepository
import com.lop.budget.domain.model.DebtType
import com.lop.budget.domain.model.FinancialItemKind
import com.lop.budget.domain.model.FinancialItemKind.DEBT
import com.lop.budget.domain.model.FinancialItemKind.GOAL
import com.lop.budget.domain.model.FinancialItemKind.RECEIVABLE
import com.lop.budget.domain.model.ItemStatus
import com.lop.budget.domain.model.ItemStatus.ACTIVE
import com.lop.budget.domain.model.ItemStatus.COMPLETED
import com.lop.budget.domain.model.LoanDirection
import com.lop.budget.domain.model.LoanDirection.BORROWED
import com.lop.budget.domain.model.LoanDirection.LENT
import com.lop.budget.domain.model.ProgressView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.seconds

/**
 * TC-163 — Suivi : listes par type, valeurs des lignes et réactivité Room, sans écriture (US LOP-22).
 *
 * ## Niveau et chaîne exercée
 * Intégration Room sous Robolectric (SDK 33). Système testé : [ObserveFinancialItemListUseCase],
 * sur vrais `GoalRepository` et `LoanRepository`, vrais `GoalDao` et `LoanDao`, `LopDatabase` en
 * mémoire. Aucune doublure.
 *
 * ## Traçabilité — cas → CA / invariant → production
 * ```
 * I-01 ×3   CA-02, I-1, I-3   invoke(kind) : GoalDao.observeActive / LoanDao.observeActiveByDirection
 *                             (filtre ARCHIVED, filtre de direction) ; 1re émission = jeu complet
 * I-02 ×9   CA-03, I-3        invoke(kind) → FinancialItemSummary (champs persistés, progressView)
 * I-03 ×6   CA-07, I-3, I-1   même collecte après create / update / delete / updateProgress / archive
 * ```
 *
 * ## Hypothèses levées
 * - Le use case n'existait pas à la livraison de LOP-22 (`f4df1969`) : il est livré par l'étape 0
 *   de P-10 (31 octobre 2026), refonte de forme à comportement constant, avec la signature de la
 *   fiche. Seul appelant : `GoalsViewModel`.
 * - Contrôle de montage `*SyncProgressRoomTest` : vert dans la suite complète du 31 octobre 2026.
 * - Préconditions assertées au montage : avancement et statut relus en base, `G1.id == D1.id`,
 *   archivés relus ARCHIVED, témoin positif du journal d'écriture.
 *
 * ## Oracles
 * - Lignes attendues écrites en clair, calculées à la main d'après les règles de TC-154 ; jamais
 *   par `progressView` ni par le use case. L'ordre des lignes n'est pas asserté (non fixé par l'US).
 * - I-1 : journal par triggers SQL permanents sur `goals`, `loans`, `transactions` (un UPDATE à
 *   valeur égale ne change aucun instantané, seul le journal le voit), puis instantanés SQL bruts
 *   des trois tables égaux à la référence.
 * - Une collecte par phase, délai réel de 10 s déclaré ici, toutes les émissions enregistrées.
 *   Barrière : l'exécuteur de requêtes Room est un fil unique possédé par le test.
 *
 * ## ANO connues
 * Aucune.
 *
 * ## Résultats — 31 octobre 2026, JVM (Robolectric, SDK 33), production de `c7e82123`
 * 18 verts sur 18 du premier coup. Preuves de sensibilité : production copiée, mutée, puis restaurée
 * depuis la copie après chaque passage (aucun écart à HEAD), et 18 sur 18 après restauration.
 * ```
 * M1  dettes lues par observeActive(), sans filtre de direction → I-01 DEBT ; I-03 création créance, suppression
 * M2  filtre ARCHIVED retiré de GoalDao.observeActive            → I-01 GOAL ; I-03 création objectif, modification, archivage
 * M9  créances lues en BORROWED                                 → I-01 RECEIVABLE ; I-02 R1, R2 ; I-03 création créance, terminé
 * M3  non calculable remplacé par 0                             → I-02 G-NC, D-NC ; I-03 ×5 (listes qui les contiennent)
 * M4  valeur absolue de l'avancement                            → I-02 G-NEG ; I-03 création objectif, modification, archivage
 * M5  première valeur seulement (take(1))                       → les 6 I-03
 * M6  liste vide émise avant le résultat (onStart)              → les 18 cas
 * M7  réécriture à valeur égale au début de la lecture GOAL     → I-01 GOAL, I-02 G1, G2, G-NEG, G-NC, I-03 GOAL ×3 (journal seul)
 * M8  lignes accumulées d'une émission à l'autre                → I-03 modification, archivage
 * ```
 * Séries : {M1, M2}, puis chacune des autres seule. M7 rougit sans changer aucune cellule : c'est
 * le journal, pas l'instantané, qui voit une réécriture à valeur égale.
 *
 * ## Hors périmètre
 * Textes, unités, « — », libellés, onglets, bouton d'ajout et navigation (TC-162) ; chargement,
 * erreur et réessai (TC-164) ; calcul de l'avancement (LOP-79, TC-154, TC-155) ; filtre Archives
 * et création ouverte depuis les archives (LOP-206, trou assumé) ; ordre des lignes.
 *
 * ## Exécution
 * ```
 * export TMP='C:\gtmp' TEMP='C:\gtmp'
 * ./gradlew :app:testDebugUnitTest --tests "*FinancialItemListRoomTest"
 * ```
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class FinancialItemListRoomTest {

    private lateinit var queryExecutor: ExecutorService
    private lateinit var db: LopDatabase
    private lateinit var goalRepo: GoalRepository
    private lateinit var loanRepo: LoanRepository
    private lateinit var observe: ObserveFinancialItemListUseCase

    // Identifiants alloués par Room, conservés sous leurs noms symboliques.
    private var g1 = 0L
    private var d1 = 0L
    private var r1 = 0L
    private var g2 = 0L
    private var d2 = 0L
    private var r2 = 0L
    private var gNeg = 0L
    private var gNc = 0L
    private var dNc = 0L
    private var gArch = 0L
    private var dArch = 0L
    private var rArch = 0L

    @Before
    fun setUp() = runTest {
        queryExecutor = Executors.newSingleThreadExecutor()
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Application>(),
            LopDatabase::class.java,
        )
            .allowMainThreadQueries()
            .setQueryExecutor(queryExecutor)
            .build()
        goalRepo = GoalRepository(db.goalDao())
        loanRepo = LoanRepository(db.loanDao())
        observe = ObserveFinancialItemListUseCase(goalRepo, loanRepo)

        installWriteLog()
        seed()

        // Préconditions de montage : sans elles, aucun cas ne prouve ce qu'il annonce.
        if (g1 != d1) fail("Montage : G1 ($g1) et D1 ($d1) devaient partager l'identifiant. Échec de montage.")
        listOf("goals" to gArch, "loans" to dArch, "loans" to rArch).forEach { (table, id) ->
            if (stored(table, id).second != "ARCHIVED") fail("Montage : $table#$id devait être relu ARCHIVED, lu ${stored(table, id)}.")
        }

        // Témoin positif du journal : un UPDATE à valeur égale doit y laisser exactement une ligne.
        clearWriteLog()
        goalRepo.updateProgress(g1, 30_000, ACTIVE)
        assertEquals(
            "Montage : le journal d'écriture ne voit pas un UPDATE à valeur égale. Échec de montage.",
            listOf(W("goals", "UPDATE", g1)),
            writeLog(),
        )
        clearWriteLog()
    }

    @After
    fun tearDown() {
        db.close()
        queryExecutor.shutdownNow()
    }

    // ==============================================================================================
    // I-01 — CA-02, I-1, I-3 : ensemble exact par type, première émission comprise
    // ==============================================================================================

    @Test
    fun `I-01 GOAL - given le jeu complet when on observe GOAL then G1, G2, G-NEG et G-NC exactement, sans archive ni pret`() =
        runTest { assertSet("I-01 GOAL", GOAL, setOf(g1, g2, gNeg, gNc)) }

    @Test
    fun `I-01 DEBT - given le jeu complet when on observe DEBT then D1, D2 et D-NC exactement, aucune creance`() =
        runTest { assertSet("I-01 DEBT", DEBT, setOf(d1, d2, dNc)) }

    @Test
    fun `I-01 RECEIVABLE - given le jeu complet when on observe RECEIVABLE then R1 et R2 exactement, aucune dette`() =
        runTest { assertSet("I-01 RECEIVABLE", RECEIVABLE, setOf(r1, r2)) }

    // ==============================================================================================
    // I-02 — CA-03, I-3 : tous les champs de chaque ligne
    // ==============================================================================================

    @Test
    fun `I-02 G1 - given G1 a 30000 sur 100000 when on lit GOAL then 30000, 70000, 30 pourcent, ACTIVE`() =
        runTest { assertLine("I-02 G1", expectedG1()) }

    @Test
    fun `I-02 G2 - given G2 atteint when on lit GOAL then 10000, 0, 100 pourcent, COMPLETED`() =
        runTest { assertLine("I-02 G2", expectedG2()) }

    @Test
    fun `I-02 G-NEG - given G-NEG a moins 5000 when on lit GOAL then avancement negatif conserve, 55000, 0 pourcent`() =
        runTest { assertLine("I-02 G-NEG", expectedGNeg()) }

    @Test
    fun `I-02 G-NC - given G-NC sans cible when on lit GOAL then restant et pourcentage non calculables`() =
        runTest { assertLine("I-02 G-NC", expectedGNc()) }

    @Test
    fun `I-02 D1 - given D1 a 60000 sur 200000 when on lit DEBT then 60000, 140000, 30 pourcent, ACTIVE`() =
        runTest { assertLine("I-02 D1", expectedD1()) }

    @Test
    fun `I-02 D2 - given D2 remboursee when on lit DEBT then 10000, 0, 100 pourcent, COMPLETED`() =
        runTest { assertLine("I-02 D2", expectedD2()) }

    @Test
    fun `I-02 D-NC - given D-NC sans montant initial when on lit DEBT then restant et pourcentage non calculables`() =
        runTest { assertLine("I-02 D-NC", expectedDNc()) }

    @Test
    fun `I-02 R1 - given R1 a 1000 sur 30000 when on lit RECEIVABLE then 1000, 29000, 3 pourcent, ACTIVE`() =
        runTest { assertLine("I-02 R1", expectedR1()) }

    @Test
    fun `I-02 R2 - given R2 recuperee when on lit RECEIVABLE then 20000, 0, 100 pourcent, COMPLETED`() =
        runTest { assertLine("I-02 R2", expectedR2()) }

    // ==============================================================================================
    // I-03 — CA-07, I-3, I-1 : écritures réelles pendant l'observation, même collecte
    // ==============================================================================================

    @Test
    fun `I-03 creation objectif - given GOAL observe when G-NEUF est cree then G-NEUF a 4000 sur 40000 ajoute une fois`() =
        change(
            case = "I-03 création objectif", kind = GOAL,
            initial = { listOf(expectedG1(), expectedG2(), expectedGNeg(), expectedGNc()) },
            write = {
                goalRepo.create(
                    GoalEntity(
                        name = "QA-22 Voyage", targetAmountCents = 40_000, startingBalanceCents = 4_000,
                        icon = "savings", colorArgb = CYAN,
                    )
                )
            },
            final = { gNeuf ->
                listOf(
                    expectedG1(), expectedG2(), expectedGNeg(), expectedGNc(),
                    FinancialItemSummary(GOAL, gNeuf, "QA-22 Voyage", "savings", CYAN, 40_000, ProgressView(4_000, 36_000, 10), ACTIVE),
                )
            },
            writtenRow = { gNeuf -> "goals" to gNeuf },
        )

    @Test
    fun `I-03 creation creance - given RECEIVABLE et DEBT observes when R-NEUF est creee then R-NEUF sous RECEIVABLE, jamais sous DEBT`() =
        change(
            case = "I-03 création créance", kind = RECEIVABLE,
            initial = { listOf(expectedR1(), expectedR2()) },
            write = {
                loanRepo.create(
                    LoanEntity(
                        name = "QA-22 Prêt à Zoé", counterpartyName = "QA-22 Zoé", direction = LENT, debtType = DebtType.OTHER,
                        totalAmountCents = 5_000, startingBalanceCents = 0, interestRate = 0.0, icon = "person", colorArgb = AMBRE,
                    )
                )
            },
            final = { rNeuf ->
                listOf(
                    expectedR1(), expectedR2(),
                    FinancialItemSummary(RECEIVABLE, rNeuf, "QA-22 Prêt à Zoé", "person", AMBRE, 5_000, ProgressView(0, 5_000, 0), ACTIVE),
                )
            },
            writtenRow = { rNeuf -> "loans" to rNeuf },
            parallel = DEBT to { listOf(expectedD1(), expectedD2(), expectedDNc()) },
        )

    @Test
    fun `I-03 modification - given GOAL observe when G1 est renomme then une seule ligne G1 renommee, avancement conserve`() =
        change(
            case = "I-03 modification", kind = GOAL,
            initial = { listOf(expectedG1(), expectedG2(), expectedGNeg(), expectedGNc()) },
            write = {
                val current = checkNotNull(goalRepo.getById(g1)) { "Préparation : G1 introuvable" }
                goalRepo.update(current.copy(name = "QA-22 Vacances été"))
                g1
            },
            final = { listOf(expectedG1().copy(name = "QA-22 Vacances été"), expectedG2(), expectedGNeg(), expectedGNc()) },
            writtenRow = { "goals" to g1 },
        )

    @Test
    fun `I-03 suppression - given DEBT observe when D2 est supprimee then D1 et D-NC seulement`() =
        change(
            case = "I-03 suppression", kind = DEBT,
            initial = { listOf(expectedD1(), expectedD2(), expectedDNc()) },
            write = { loanRepo.delete(d2); d2 },
            final = { listOf(expectedD1(), expectedDNc()) },
            writtenRow = { "loans" to d2 },
        )

    @Test
    fun `I-03 termine - given RECEIVABLE observe when R1 est recuperee then R1 COMPLETED a 100 pourcent, toujours listee`() =
        change(
            case = "I-03 terminé", kind = RECEIVABLE,
            initial = { listOf(expectedR1(), expectedR2()) },
            write = { loanRepo.updateProgress(r1, 30_000, COMPLETED); r1 },
            final = { listOf(expectedR1().copy(progress = ProgressView(30_000, 0, 100), status = COMPLETED), expectedR2()) },
            writtenRow = { "loans" to r1 },
        )

    @Test
    fun `I-03 archivage - given GOAL observe when G2 est archive then G1, G-NEG et G-NC seulement`() =
        change(
            case = "I-03 archivage", kind = GOAL,
            initial = { listOf(expectedG1(), expectedG2(), expectedGNeg(), expectedGNc()) },
            write = { goalRepo.archive(g2); g2 },
            final = { listOf(expectedG1(), expectedGNeg(), expectedGNc()) },
            writtenRow = { "goals" to g2 },
        )

    // ==============================================================================================
    // Phases de lecture et d'écriture
    // ==============================================================================================

    private suspend fun TestScope.assertSet(case: String, kind: FinancialItemKind, expectedIds: Set<Long>) {
        val read = readPhase(case, kind)
        val expected = expectedIds.map { kind to it }.toSet()
        read.all.forEachIndexed { index, emission ->
            val pairs = emission.map { it.kind to it.id }
            val label = if (index == 0) "première émission (point de synchronisation, I-3)" else "émission ${index + 1}"
            assertEquals(
                "$case — CA-02 : $label. Manquants ${expected - pairs.toSet()}, intrus ${pairs.toSet() - expected}. " +
                    "Trace : ${read.trace()}",
                expected,
                pairs.toSet(),
            )
            assertEquals("$case — CA-02 : doublon dans la $label. Trace : ${read.trace()}", expected.size, pairs.size)
        }
    }

    private suspend fun TestScope.assertLine(case: String, expected: FinancialItemSummary) {
        val read = readPhase(case, expected.kind)
        val matches = read.first.filter { it.id == expected.id }
        assertEquals("$case — CA-03 : nombre de lignes ${expected.kind}#${expected.id}. Trace : ${read.trace()}", 1, matches.size)
        val line = matches.single()
        if (expected.progress.remainingCents == null) {
            assertNull("$case — CA-03, I-3 : restant non calculable devenu une valeur. Ligne : ${describe(line)}", line.progress.remainingCents)
            assertNull("$case — CA-03, I-3 : pourcentage non calculable devenu une valeur. Ligne : ${describe(line)}", line.progress.percent)
        }
        assertEquals(
            "$case — CA-03 : avancement, signe compris. Ligne : ${describe(line)}",
            expected.progress.reachedCents,
            line.progress.reachedCents,
        )
        assertEquals("$case — CA-03 : tous les champs de la ligne.", expected, line)
    }

    private class Read(val first: List<FinancialItemSummary>, val later: List<List<FinancialItemSummary>>) {
        val all get() = listOf(first) + later
        fun trace() = all.map { it.map(::describe) }
    }

    /**
     * Une phase de lecture : collecte ouverte, première émission, barrière, émissions restantes.
     * Puis I-1 : journal vide, tables identiques à la référence prise avant.
     */
    private suspend fun TestScope.readPhase(case: String, kind: FinancialItemKind): Read {
        val reference = snapshot()
        var read: Read? = null
        observe(kind).test(timeout = TIMEOUT) {
            val first = next(this, case, "première émission de $kind")
            barrier(this@readPhase)
            val later = cancelAndConsumeRemainingEvents()
                .filterIsInstance<Event.Item<List<FinancialItemSummary>>>().map { it.value }
            read = Read(first, later)
        }
        assertEquals("$case — I-1 : écritures pendant la lecture de $kind.", emptyList<W>(), writeLog())
        assertEquals("$case — I-1 : tables modifiées par la lecture de $kind.", reference, snapshot())
        return checkNotNull(read)
    }

    /**
     * I-03 : observation ouverte, point de synchronisation sur l'état initial exact, écriture réelle,
     * attente de l'état final exact sur la **même** collecte, barrière, puis contrôles.
     *
     * Chaque émission doit être l'état initial ou l'état final exact (I-3) ; après l'état final,
     * aucune émission n'en diffère. [parallel] : un second type observé en même temps, dont chaque
     * émission doit rester égale à son état attendu. [writtenRow] : seule ligne que le journal admet.
     */
    private fun change(
        case: String,
        kind: FinancialItemKind,
        initial: () -> List<FinancialItemSummary>,
        write: suspend () -> Long,
        final: (Long) -> List<FinancialItemSummary>,
        writtenRow: (Long) -> Pair<String, Long>,
        parallel: Pair<FinancialItemKind, () -> List<FinancialItemSummary>>? = null,
    ) = runTest {
        val start = initial().sortedBy { it.id }
        val trace = mutableListOf<List<FinancialItemSummary>>()
        val sideTrace = mutableListOf<List<FinancialItemSummary>>()
        var syncWrites = emptyList<W>()
        var stepWrites = emptyList<W>()
        lateinit var reference: Map<String, List<Map<String, Any?>>>
        var want = emptyList<FinancialItemSummary>()
        var row = "" to 0L
        var timedOut: String? = null

        turbineScope(timeout = TIMEOUT) {
            val main = observe(kind).testIn(backgroundScope)
            val side = parallel?.first?.let { observe(it).testIn(backgroundScope) }
            try {
                trace += main.awaitItem()
                side?.let { sideTrace += it.awaitItem() }
                syncWrites = writeLog()

                val created = write()
                stepWrites = writeLog()
                reference = snapshot()
                want = final(created).sortedBy { it.id }
                row = writtenRow(created)

                while (trace.last().sortedBy { it.id } != want) trace += main.awaitItem()
            } catch (e: AssertionError) {
                timedOut = e.message
            }
            barrier(this@runTest)
            trace += main.cancelAndConsumeRemainingEvents().filterIsInstance<Event.Item<List<FinancialItemSummary>>>().map { it.value }
            side?.let { s ->
                sideTrace += s.cancelAndConsumeRemainingEvents().filterIsInstance<Event.Item<List<FinancialItemSummary>>>().map { it.value }
            }
        }

        val context = "Trace : ${trace.map { it.map(::describe) }}. Journal : ${writeLog()}"
        if (trace.isEmpty()) fail("$case — I-3 : aucune émission en $TIMEOUT (point de synchronisation). $context ($timedOut)")
        assertEquals(
            "$case — I-3 : la première émission doit déjà être l'état initial exact (point de synchronisation). $context",
            start.map(::describe),
            trace.first().sortedBy { it.id }.map(::describe),
        )
        timedOut?.let { fail("$case — CA-07 : état final non reçu en $TIMEOUT ; attendu ${want.map(::describe)}. $context ($it)") }
        assertEquals("$case — I-1 : écritures pendant la synchronisation. $context", emptyList<W>(), syncWrites)

        val finalIndex = trace.indexOfFirst { it.sortedBy { s -> s.id } == want }
        assertEquals(
            "$case — CA-07, I-3 : émission ni initiale ni finale (doublon, élément supprimé ou mélange). $context",
            emptyList<List<String>>(),
            trace.filter { val n = it.sortedBy { s -> s.id }; n != start && n != want }.map { it.map(::describe) },
        )
        assertEquals(
            "$case — CA-07 : retour arrière après l'état final. $context",
            emptyList<List<String>>(),
            trace.drop(finalIndex + 1).filter { it.sortedBy { s -> s.id } != want }.map { it.map(::describe) },
        )
        parallel?.let { (sideKind, sideExpected) ->
            val expectedSide = sideExpected().sortedBy { it.id }
            assertEquals(
                "$case — CA-02 : $sideKind observé en parallèle a changé (R-NEUF ne doit jamais y paraître). " +
                    "Trace $sideKind : ${sideTrace.map { it.map(::describe) }}",
                emptyList<List<String>>(),
                sideTrace.filter { it.sortedBy { s -> s.id } != expectedSide }.map { it.map(::describe) },
            )
            assertEquals("$case — montage : aucune émission reçue pour $sideKind.", true, sideTrace.isNotEmpty())
        }

        val (table, id) = row
        assertEquals("$case — montage : l'écriture de préparation n'a laissé aucune trace au journal.", true, stepWrites.isNotEmpty())
        assertEquals(
            "$case — I-1 : écritures hors de la ligne visée ($table#$id). $context",
            emptyList<W>(),
            stepWrites.filter { it.table != table || it.id != id },
        )
        assertEquals("$case — I-1 : écritures pendant la lecture, après la préparation. $context", stepWrites, writeLog())
        assertEquals("$case — I-1 : tables modifiées après la préparation. $context", reference, snapshot())
    }

    /** Prochaine émission ; un délai expiré devient un échec métier diagnostiqué. */
    private suspend fun next(turbine: ReceiveTurbine<List<FinancialItemSummary>>, case: String, what: String) =
        try {
            turbine.awaitItem()
        } catch (e: AssertionError) {
            throw AssertionError("$case — I-3 : $what non reçue en $TIMEOUT. Journal : ${writeLog()}", e)
        }

    /**
     * Les relectures Room déclenchées par l'invalidation passent sur l'exécuteur à fil unique du
     * test : deux tâches vides successives garantissent qu'elles sont terminées. Puis l'ordonnanceur
     * de test est vidé.
     */
    private suspend fun barrier(scope: TestScope) {
        repeat(2) { withContext(Dispatchers.IO) { queryExecutor.submit {}.get() } }
        scope.advanceUntilIdle()
        scope.runCurrent()
    }

    // ==============================================================================================
    // Jeu de données — fiche TC-163, créé dans l'ordre prescrit
    // ==============================================================================================

    private suspend fun seed() {
        // `create` remplace l'avancement proposé par le départ : il est posé ensuite par `updateProgress`.
        g1 = goal("QA-22 Vacances", 100_000, "savings", VERT, 30_000, ACTIVE)
        d1 = loan("QA-22 Prêt auto", "QA-22 Banque", BORROWED, 200_000, "car", ROUGE, 60_000, ACTIVE)
        r1 = loan("QA-22 Prêt à Sam", "QA-22 Sam", LENT, 30_000, "person", BLEU, 1_000, ACTIVE)
        g2 = goal("QA-22 Vélo", 10_000, "bike", ORANGE, 10_000, COMPLETED)
        d2 = loan("QA-22 Dette Léa", null, BORROWED, 10_000, "payments", GRIS, 10_000, COMPLETED)
        r2 = loan("QA-22 Avance Tom", "QA-22 Tom", LENT, 20_000, "star", VIOLET, 20_000, COMPLETED)
        gNeg = goal("QA-22 Épargne entamée", 50_000, "flag", SARCELLE, -5_000, ACTIVE)
        gNc = goal("QA-22 Sans cible", 0, "home", BRUN, 2_000, ACTIVE)
        dNc = loan("QA-22 Dette sans montant", null, BORROWED, 0, "account_balance", INDIGO, 0, ACTIVE)
        gArch = goal("QA-22 Ancien projet", 20_000, "home", VERT_CLAIR, 5_000, ACTIVE)
        goalRepo.archive(gArch)
        dArch = loan("QA-22 Ancienne dette", null, BORROWED, 30_000, "car", ROSE, 30_000, COMPLETED)
        loanRepo.archive(dArch)
        rArch = loan("QA-22 Ancienne créance", "QA-22 Max", LENT, 15_000, "person", LIME, 0, ACTIVE)
        loanRepo.archive(rArch)
    }

    private suspend fun goal(name: String, target: Long, icon: String, color: Int, progress: Long, status: ItemStatus): Long {
        val id = goalRepo.create(GoalEntity(name = name, targetAmountCents = target, icon = icon, colorArgb = color))
        goalRepo.updateProgress(id, progress, status)
        checkStored("goals", id, progress, status)
        return id
    }

    private suspend fun loan(
        name: String,
        counterparty: String?,
        direction: LoanDirection,
        total: Long,
        icon: String,
        color: Int,
        progress: Long,
        status: ItemStatus,
    ): Long {
        val id = loanRepo.create(
            LoanEntity(
                name = name, counterpartyName = counterparty, direction = direction,
                debtType = if (direction == BORROWED) DebtType.LOAN else DebtType.OTHER,
                totalAmountCents = total, interestRate = 0.0, icon = icon, colorArgb = color,
            )
        )
        loanRepo.updateProgress(id, progress, status)
        checkStored("loans", id, progress, status)
        return id
    }

    private fun checkStored(table: String, id: Long, progress: Long, status: ItemStatus) {
        val read = stored(table, id)
        if (read != (progress to status.name)) fail("Montage : $table#$id relu $read, attendu ${progress to status.name}. Échec de montage.")
    }

    /** Avancement et statut relus par requête brute : aucune API de production ajoutée pour le test. */
    private fun stored(table: String, id: Long): Pair<Long, String> {
        val column = if (table == "goals") "savedAmountCents" else "repaidAmountCents"
        return db.openHelper.readableDatabase.query("SELECT $column, status FROM $table WHERE id = $id").use { c ->
            c.moveToFirst()
            c.getLong(0) to c.getString(1)
        }
    }

    // ==============================================================================================
    // Lignes attendues — écrites en clair d'après le tableau « Lignes attendues » de la fiche
    // ==============================================================================================

    private fun expectedG1() = FinancialItemSummary(GOAL, g1, "QA-22 Vacances", "savings", VERT, 100_000, ProgressView(30_000, 70_000, 30), ACTIVE)
    private fun expectedG2() = FinancialItemSummary(GOAL, g2, "QA-22 Vélo", "bike", ORANGE, 10_000, ProgressView(10_000, 0, 100), COMPLETED)
    private fun expectedGNeg() = FinancialItemSummary(GOAL, gNeg, "QA-22 Épargne entamée", "flag", SARCELLE, 50_000, ProgressView(-5_000, 55_000, 0), ACTIVE)
    private fun expectedGNc() = FinancialItemSummary(GOAL, gNc, "QA-22 Sans cible", "home", BRUN, 0, ProgressView(2_000, null, null), ACTIVE)
    private fun expectedD1() = FinancialItemSummary(DEBT, d1, "QA-22 Prêt auto", "car", ROUGE, 200_000, ProgressView(60_000, 140_000, 30), ACTIVE)
    private fun expectedD2() = FinancialItemSummary(DEBT, d2, "QA-22 Dette Léa", "payments", GRIS, 10_000, ProgressView(10_000, 0, 100), COMPLETED)
    private fun expectedDNc() = FinancialItemSummary(DEBT, dNc, "QA-22 Dette sans montant", "account_balance", INDIGO, 0, ProgressView(0, null, null), ACTIVE)
    private fun expectedR1() = FinancialItemSummary(RECEIVABLE, r1, "QA-22 Prêt à Sam", "person", BLEU, 30_000, ProgressView(1_000, 29_000, 3), ACTIVE)
    private fun expectedR2() = FinancialItemSummary(RECEIVABLE, r2, "QA-22 Avance Tom", "star", VIOLET, 20_000, ProgressView(20_000, 0, 100), COMPLETED)

    // ==============================================================================================
    // Instantanés et journal d'écriture
    // ==============================================================================================

    private data class W(val table: String, val op: String, val id: Long)

    /**
     * Triggers permanents (pas `TEMP`, propre à une connexion), créés après l'ouverture de Room : ils
     * voient toute écriture, y compris un UPDATE à valeur égale qu'aucun instantané ne distingue.
     */
    private fun installWriteLog() {
        val sql = db.openHelper.writableDatabase
        sql.execSQL("CREATE TABLE qa_write_log (seq INTEGER PRIMARY KEY AUTOINCREMENT, tbl TEXT NOT NULL, op TEXT NOT NULL, row_id INTEGER NOT NULL)")
        for (table in listOf("goals", "loans", "transactions")) {
            sql.execSQL("CREATE TRIGGER qa_${table}_ins AFTER INSERT ON $table BEGIN INSERT INTO qa_write_log (tbl, op, row_id) VALUES ('$table', 'INSERT', NEW.id); END")
            sql.execSQL("CREATE TRIGGER qa_${table}_upd AFTER UPDATE ON $table BEGIN INSERT INTO qa_write_log (tbl, op, row_id) VALUES ('$table', 'UPDATE', NEW.id); END")
            sql.execSQL("CREATE TRIGGER qa_${table}_del AFTER DELETE ON $table BEGIN INSERT INTO qa_write_log (tbl, op, row_id) VALUES ('$table', 'DELETE', OLD.id); END")
        }
    }

    private fun writeLog(): List<W> = buildList {
        db.openHelper.readableDatabase.query("SELECT tbl, op, row_id FROM qa_write_log ORDER BY seq").use { c ->
            while (c.moveToNext()) add(W(c.getString(0), c.getString(1), c.getLong(2)))
        }
    }

    private fun clearWriteLog() = db.openHelper.writableDatabase.execSQL("DELETE FROM qa_write_log")

    private fun snapshot(): Map<String, List<Map<String, Any?>>> =
        listOf("goals", "loans", "transactions").associateWith { table ->
            buildList {
                db.openHelper.readableDatabase.query("SELECT * FROM $table ORDER BY id").use { c ->
                    while (c.moveToNext()) add((0 until c.columnCount).associate { i -> c.getColumnName(i) to c.typed(i) })
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
        /** Délai déclaré ici : il borne chaque attente de cette classe, en temps réel. */
        val TIMEOUT = 10.seconds

        val VERT = 0xFF4CAF50.toInt()
        val ROUGE = 0xFFF44336.toInt()
        val BLEU = 0xFF2196F3.toInt()
        val ORANGE = 0xFFFF9800.toInt()
        val GRIS = 0xFF607D8B.toInt()
        val VIOLET = 0xFF9C27B0.toInt()
        val SARCELLE = 0xFF009688.toInt()
        val BRUN = 0xFF795548.toInt()
        val INDIGO = 0xFF3F51B5.toInt()
        val VERT_CLAIR = 0xFF8BC34A.toInt()
        val ROSE = 0xFFE91E63.toInt()
        val LIME = 0xFFCDDC39.toInt()
        val CYAN = 0xFF00BCD4.toInt()
        val AMBRE = 0xFFFFC107.toInt()

        fun describe(s: FinancialItemSummary) =
            "${s.kind}#${s.id} « ${s.name} » ${s.progress.reachedCents}/${s.progress.remainingCents}/${s.progress.percent} ${s.status}"
    }
}

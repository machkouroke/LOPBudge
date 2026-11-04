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
import com.lop.budget.domain.model.ItemStatus.ARCHIVED
import com.lop.budget.domain.model.ItemStatus.COMPLETED
import com.lop.budget.domain.model.LoanDirection
import com.lop.budget.domain.model.LoanDirection.BORROWED
import com.lop.budget.domain.model.LoanDirection.LENT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.seconds

/**
 * TC-176 — Archives financières : listes par type et réactivité Room (US LOP-206).
 *
 * ## Niveau et chaîne exercée
 * Intégration Room, JVM/Robolectric SDK 33. Système testé : [ObserveFinancialItemListUseCase] réel
 * — `invoke(kind)` (liste normale) et `archived(kind)` (Archives) — sur vrais [GoalRepository] et
 * [LoanRepository], vrais `GoalDao` et `LoanDao`, `LopDatabase` en mémoire, neuve par cas. Aucune
 * doublure. Les écritures de I-02 et I-03 passent par les repositories : elles préparent le
 * changement observé et ne prouvent ni l'opération métier, ni le recalcul de restauration (TC-178).
 *
 * ## Traçabilité — cas → CA → production
 * ```
 * I-01 ×3   CA-03         invoke : GoalDao.observeActive / LoanDao.observeActiveByDirection ;
 *                         archived : GoalDao.observeArchived / LoanDao.observeArchivedByDirection
 * I-02 ×3   CA-03         les six flux sur les mêmes collectes ; repo.archive → updateStatus(ARCHIVED)
 * I-03 ×3   CA-03, CA-04  les six flux sur les mêmes collectes ; repo.restore → updateStatus(ACTIVE)
 * ```
 *
 * ## Oracles
 * - Chaque liste se compare en couples (type, identifiant) avec nom et statut, triés : ensemble et
 *   nombre exacts, doublons compris. L'ordre d'affichage n'est pas asserté (non fixé par l'US). Une
 *   liste vide est attendue explicitement.
 * - Lectures sans écriture : journal par déclencheurs SQL sur `goals`, `loans`, `transactions` (un
 *   UPDATE à valeur égale n'y échappe pas ; témoin positif vérifié au montage), puis instantanés SQL
 *   bruts de ces tables égaux à la référence.
 * - Transitions : les six flux (normal et archives des trois types) sont collectés une seule fois
 *   chacun. Point de synchronisation : la première émission de chacun est l'état initial exact. Les
 *   deux listes du type visé n'émettent que l'état initial ou l'état final, sans retour en arrière
 *   après l'état final ; les quatre autres n'émettent que leur état initial. Répétitions identiques
 *   tolérées. Seule la ligne cible est écrite.
 * - Délai déclaré ici, en temps réel (Turbine) : 10 s. Barrière : l'exécuteur de requêtes Room est un
 *   fil unique possédé par le test.
 *
 * ## Données (JDD de la fiche, créées dans l'ordre)
 * G (actif, 20 €), GA (archivé, 30 €), GF (terminé, 100 €) ; D, DA (dettes) ; C, CA (créances) ; cible
 * ou initial 100 €, compte, échéance et commentaire nuls, icône `star`, couleur bleue. GF est terminé
 * par `updateProgress(GF, 10000, COMPLETED)`, GA, DA et CA archivés par les repositories ; statut et
 * avancement relus avant tout cas. G et D partagent l'identifiant 1.
 *
 * ## ANO connues
 * Aucune.
 *
 * ## Résultats — 4 novembre 2026, JVM/Robolectric SDK 33, production de `65a69e6c`
 * 9 verts sur 9 au premier passage ; contrôle de montage TC-163 : 18 sur 18. Preuves de sensibilité,
 * une mutation par passage, restaurée par copie puis vérifiée identique à HEAD ; retour à 9 sur 9 :
 * ```
 * L1  GoalDao.observeArchived lit les non-archivés     → I-01 GOAL ; les 6 transitions (état initial)
 * L2  archived(DEBT) lit la direction LENT             → I-01 DEBT ; les 6 transitions (état initial)
 * L3  archived(kind) figé à sa première valeur         → I-02 ×3, I-03 ×3 (état final non reçu)
 * L4  lignes d'archives accumulées d'une émission à l'autre → I-03 ×3 (la restaurée reste en Archives)
 * L5  LoanDao.observeArchivedByDirection sans filtre de statut → I-01 DEBT, I-01 RECEIVABLE ; 6 transitions
 * ```
 * Les transitions observent toujours les six listes : un défaut de lecture d'un type y apparaît dès
 * le point de synchronisation, quel que soit le type visé.
 *
 * ## Hors périmètre
 * Conservation des champs, transactions, séries et rollback (TC-178) ; filtre à l'écran, ouverture
 * du détail, maintien du filtre, retour après création et rééligibilité des choix (TC-177) ; calcul
 * de progression (TC-154, TC-155) ; sélecteurs `linkable`.
 *
 * ## Exécution
 * ```
 * export TMP='C:\gtmp' TEMP='C:\gtmp'
 * ./gradlew :app:testDebugUnitTest --tests "*ArchivedFinancialListRoomTest"
 * ```
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ArchivedFinancialListRoomTest {

    private lateinit var defaultTimeZone: TimeZone
    private lateinit var defaultLocale: Locale
    private lateinit var queryExecutor: ExecutorService
    private lateinit var db: LopDatabase
    private lateinit var goalRepo: GoalRepository
    private lateinit var loanRepo: LoanRepository
    private lateinit var observe: ObserveFinancialItemListUseCase

    // Identifiants alloués par Room, conservés sous les noms de la fiche.
    private var g = 0L
    private var ga = 0L
    private var gf = 0L
    private var d = 0L
    private var da = 0L
    private var c = 0L
    private var ca = 0L

    @Before
    fun setUp() = runTest {
        defaultTimeZone = TimeZone.getDefault()
        defaultLocale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Paris"))
        Locale.setDefault(Locale.FRANCE)

        queryExecutor = Executors.newSingleThreadExecutor()
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), LopDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(queryExecutor)
            .build()
        goalRepo = GoalRepository(db.goalDao())
        loanRepo = LoanRepository(db.loanDao())
        observe = ObserveFinancialItemListUseCase(goalRepo, loanRepo)

        installWriteLog()
        seed()

        if (g != d) fail("Montage : G ($g) et D ($d) devaient partager l'identifiant. Échec de montage.")

        // Témoin positif du journal : un UPDATE à valeur égale doit y laisser exactement une ligne.
        clearWriteLog()
        goalRepo.updateProgress(g, 2_000, ACTIVE)
        assertEquals(
            "Montage : le journal d'écriture ne voit pas un UPDATE à valeur égale. Échec de montage.",
            listOf(W("goals", "UPDATE", g)),
            writeLog(),
        )
        clearWriteLog()
    }

    @After
    fun tearDown() {
        db.close()
        queryExecutor.shutdownNow()
        TimeZone.setDefault(defaultTimeZone)
        Locale.setDefault(defaultLocale)
    }

    // ==============================================================================================
    // I-01 — CA-03 : liste normale et Archives, ensembles exacts par type
    // ==============================================================================================

    @Test
    fun `I-01 GOAL - given le jeu complet when on lit GOAL then normal G et GF, archives GA seul`() = runTest {
        readExact("I-01 GOAL", Key(GOAL, archived = false), listOf(gLine(), gfLine()))
        readExact("I-01 GOAL", Key(GOAL, archived = true), listOf(gaLine(ARCHIVED)))
    }

    @Test
    fun `I-01 DEBT - given le jeu complet when on lit DEBT then normal D seule, archives DA seule`() = runTest {
        readExact("I-01 DEBT", Key(DEBT, archived = false), listOf(dLine(ACTIVE)))
        readExact("I-01 DEBT", Key(DEBT, archived = true), listOf(daLine(ARCHIVED)))
    }

    @Test
    fun `I-01 RECEIVABLE - given le jeu complet when on lit RECEIVABLE then normal C seule, archives CA seule`() = runTest {
        readExact("I-01 RECEIVABLE", Key(RECEIVABLE, archived = false), listOf(cLine(ACTIVE)))
        readExact("I-01 RECEIVABLE", Key(RECEIVABLE, archived = true), listOf(caLine(ARCHIVED)))
    }

    // ==============================================================================================
    // I-02 — CA-03 : archiver fait passer la cible de la liste normale aux Archives, sur les mêmes collectes
    // ==============================================================================================

    @Test
    fun `I-02 GOAL - given les six listes observees when G est archive then normal GF, archives GA et G, autres types inchanges`() =
        transition(
            case = "I-02 GOAL", ca = "CA-03", kind = GOAL,
            write = { goalRepo.archive(g) }, row = { "goals" to g },
            finalNormal = { listOf(gfLine()) },
            finalArchives = { listOf(gLine().copy(status = ARCHIVED), gaLine(ARCHIVED)) },
        )

    @Test
    fun `I-02 DEBT - given les six listes observees when D est archivee then normal vide, archives DA et D, autres types inchanges`() =
        transition(
            case = "I-02 DEBT", ca = "CA-03", kind = DEBT,
            write = { loanRepo.archive(d) }, row = { "loans" to d },
            finalNormal = { emptyList() },
            finalArchives = { listOf(dLine(ARCHIVED), daLine(ARCHIVED)) },
        )

    @Test
    fun `I-02 RECEIVABLE - given les six listes observees when C est archivee then normal vide, archives CA et C, autres types inchanges`() =
        transition(
            case = "I-02 RECEIVABLE", ca = "CA-03", kind = RECEIVABLE,
            write = { loanRepo.archive(c) }, row = { "loans" to c },
            finalNormal = { emptyList() },
            finalArchives = { listOf(cLine(ARCHIVED), caLine(ARCHIVED)) },
        )

    // ==============================================================================================
    // I-03 — CA-03, CA-04 : restaurer ramène la cible dans la liste normale de son type
    // ==============================================================================================

    @Test
    fun `I-03 GOAL - given les six listes observees when GA est restaure then normal G, GF et GA ACTIVE, archives vide`() =
        transition(
            case = "I-03 GOAL", ca = "CA-03/CA-04", kind = GOAL,
            write = { goalRepo.restore(ga) }, row = { "goals" to ga },
            finalNormal = { listOf(gLine(), gaLine(ACTIVE), gfLine()) },
            finalArchives = { emptyList() },
            storedStatus = { "goals" to ga },
        )

    @Test
    fun `I-03 DEBT - given les six listes observees when DA est restauree then normal D et DA ACTIVE, archives vide`() =
        transition(
            case = "I-03 DEBT", ca = "CA-03/CA-04", kind = DEBT,
            write = { loanRepo.restore(da) }, row = { "loans" to da },
            finalNormal = { listOf(dLine(ACTIVE), daLine(ACTIVE)) },
            finalArchives = { emptyList() },
            storedStatus = { "loans" to da },
        )

    @Test
    fun `I-03 RECEIVABLE - given les six listes observees when CA est restauree then normal C et CA ACTIVE, archives vide`() =
        transition(
            case = "I-03 RECEIVABLE", ca = "CA-03/CA-04", kind = RECEIVABLE,
            write = { loanRepo.restore(ca) }, row = { "loans" to ca },
            finalNormal = { listOf(cLine(ACTIVE), caLine(ACTIVE)) },
            finalArchives = { emptyList() },
            storedStatus = { "loans" to ca },
        )

    // ==============================================================================================
    // Phases de lecture et de transition
    // ==============================================================================================

    /** Une des six listes : liste normale ou Archives d'un type. */
    private data class Key(val kind: FinancialItemKind, val archived: Boolean) {
        override fun toString() = "$kind ${if (archived) "Archives" else "normale"}"
    }

    private val keys = FinancialItemKind.entries.flatMap { listOf(Key(it, archived = false), Key(it, archived = true)) }

    private fun flowFor(key: Key): Flow<List<FinancialItemSummary>> =
        if (key.archived) observe.archived(key.kind) else observe(key.kind)

    /** Une ligne lue : ce que la fiche fait comparer (type, identifiant, nom, statut). */
    private data class Line(val kind: FinancialItemKind, val id: Long, val name: String, val status: ItemStatus) {
        override fun toString() = "$kind#$id « $name » $status"
    }

    private fun lines(list: List<FinancialItemSummary>) = list.map { Line(it.kind, it.id, it.name, it.status) }.sorted()

    private fun List<Line>.sorted() = sortedWith(compareBy({ it.kind }, { it.id }))

    /** États initiaux des six listes, d'après le JDD. */
    private fun initialSets(): Map<Key, List<Line>> = mapOf(
        Key(GOAL, false) to listOf(gLine(), gfLine()),
        Key(GOAL, true) to listOf(gaLine(ARCHIVED)),
        Key(DEBT, false) to listOf(dLine(ACTIVE)),
        Key(DEBT, true) to listOf(daLine(ARCHIVED)),
        Key(RECEIVABLE, false) to listOf(cLine(ACTIVE)),
        Key(RECEIVABLE, true) to listOf(caLine(ARCHIVED)),
    ).mapValues { it.value.sorted() }

    /**
     * I-01 : une collecte, première émission, barrière, émissions restantes. Chaque émission est
     * l'ensemble attendu ; aucune écriture pendant la lecture.
     */
    private suspend fun TestScope.readExact(case: String, key: Key, expected: List<Line>) {
        val want = expected.sorted()
        val reference = snapshot()
        val emissions = mutableListOf<List<Line>>()
        flowFor(key).test(timeout = TIMEOUT) {
            emissions += lines(next(this, case, "première émission de $key"))
            barrier(this@readExact)
            emissions += cancelAndConsumeRemainingEvents().filterIsInstance<Event.Item<List<FinancialItemSummary>>>().map { lines(it.value) }
        }
        emissions.forEachIndexed { index, emission ->
            assertEquals(
                "$case — CA-03 : $key, émission ${index + 1} sur ${emissions.size}. Manquants ${want - emission.toSet()}, " +
                    "intrus ${emission - want.toSet()}. Émissions : $emissions",
                want,
                emission,
            )
        }
        assertEquals("$case — CA-03 : écritures pendant la lecture de $key.", emptyList<W>(), writeLog())
        assertEquals("$case — CA-03 : tables modifiées par la lecture de $key.", reference, snapshot())
    }

    /**
     * I-02 / I-03 : six collectes ouvertes, point de synchronisation sur les six états initiaux,
     * écriture de préparation, attente des deux états finaux du type visé sur les **mêmes** collectes,
     * barrière, puis contrôles.
     */
    private fun transition(
        case: String,
        ca: String,
        kind: FinancialItemKind,
        write: suspend () -> Unit,
        row: () -> Pair<String, Long>,
        finalNormal: () -> List<Line>,
        finalArchives: () -> List<Line>,
        storedStatus: (() -> Pair<String, Long>)? = null,
    ) = runTest {
        val initial = initialSets()
        val final = initial + mapOf(Key(kind, false) to finalNormal().sorted(), Key(kind, true) to finalArchives().sorted())
        val targets = setOf(Key(kind, false), Key(kind, true))
        val traces = keys.associateWith { mutableListOf<List<Line>>() }
        var syncWrites = emptyList<W>()
        var stepWrites = emptyList<W>()
        var reference = emptyMap<String, List<Map<String, Any?>>>()
        var timedOut: String? = null

        turbineScope(timeout = TIMEOUT) {
            val turbines = keys.associateWith { flowFor(it).testIn(backgroundScope) }
            try {
                for (key in keys) traces.getValue(key) += lines(turbines.getValue(key).awaitItem())
                syncWrites = writeLog()

                write()
                stepWrites = writeLog()
                reference = snapshot()

                for (key in targets) {
                    val trace = traces.getValue(key)
                    while (trace.last() != final.getValue(key)) trace += lines(turbines.getValue(key).awaitItem())
                }
            } catch (e: AssertionError) {
                timedOut = e.message
            }
            barrier(this@runTest)
            for (key in keys) {
                traces.getValue(key) += turbines.getValue(key).cancelAndConsumeRemainingEvents()
                    .filterIsInstance<Event.Item<List<FinancialItemSummary>>>().map { lines(it.value) }
            }
        }

        val (table, id) = row()
        val context = "Émissions : ${traces.map { (k, t) -> "$k → $t" }}. Journal : ${writeLog()}"

        // Point de synchronisation : chaque liste a émis, et d'abord son état initial exact.
        for (key in keys) {
            val trace = traces.getValue(key)
            if (trace.isEmpty()) fail("$case — $ca : $key n'a rien émis en $TIMEOUT (point de synchronisation). $context ($timedOut)")
            assertEquals("$case — $ca : $key, première émission ≠ état initial. $context", initial.getValue(key), trace.first())
        }
        timedOut?.let {
            fail("$case — $ca : état final non reçu en $TIMEOUT ; attendu ${targets.associateWith { k -> final.getValue(k) }}. $context ($it)")
        }
        assertEquals("$case — $ca : écritures pendant la synchronisation. $context", emptyList<W>(), syncWrites)

        // Listes du type visé : état initial ou final seulement, jamais de retour après l'état final.
        for (key in targets) {
            val trace = traces.getValue(key)
            val start = initial.getValue(key)
            val end = final.getValue(key)
            assertEquals(
                "$case — $ca : $key, émission ni initiale ni finale (doublon, intrus ou mélange) ; attendu $end. $context",
                emptyList<List<Line>>(),
                trace.filter { it != start && it != end },
            )
            val reached = trace.indexOfFirst { it == end }
            assertEquals(
                "$case — $ca : $key, retour à l'ancien état après l'état final $end. $context",
                emptyList<List<Line>>(),
                trace.drop(reached + 1).filter { it != end },
            )
            assertEquals("$case — $ca : $key, dernier état. $context", end, trace.last())
        }

        // Témoins : les quatre listes des autres types n'émettent que leur état initial.
        for (key in keys - targets) {
            assertEquals(
                "$case — $ca : $key, témoin d'un autre type modifié (attendu ${initial.getValue(key)}). $context",
                emptyList<List<Line>>(),
                traces.getValue(key).filter { it != initial.getValue(key) },
            )
        }

        // Écritures : la seule ligne cible, puis plus rien pendant l'observation.
        assertEquals("$case — montage : l'écriture de préparation n'a laissé aucune trace au journal.", true, stepWrites.isNotEmpty())
        assertEquals(
            "$case — $ca : écritures hors de la ligne cible ($table#$id). $context",
            emptyList<W>(),
            stepWrites.filter { it.table != table || it.id != id },
        )
        assertEquals("$case — $ca : écritures pendant l'observation, après la préparation. $context", stepWrites, writeLog())
        assertEquals("$case — $ca : tables modifiées après la préparation. $context", reference, snapshot())

        storedStatus?.invoke()?.let { (t, i) ->
            assertEquals("$case — CA-04 : statut lu en base de $t#$i après restauration.", ACTIVE.name, stored(t, i).second)
        }
    }

    /** Prochaine émission ; un délai expiré devient un échec métier diagnostiqué. */
    private suspend fun next(turbine: ReceiveTurbine<List<FinancialItemSummary>>, case: String, what: String) =
        try {
            turbine.awaitItem()
        } catch (e: AssertionError) {
            throw AssertionError("$case — CA-03 : $what non reçue en $TIMEOUT. Journal : ${writeLog()}", e)
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
    // Jeu de données — fiche TC-176, créé dans l'ordre prescrit
    // ==============================================================================================

    private suspend fun seed() {
        g = goalRepo.create(goal("QA-206 G", 2_000))
        ga = goalRepo.create(goal("QA-206 GA", 3_000))
        gf = goalRepo.create(goal("QA-206 GF", 10_000))
        d = loanRepo.create(loan("QA-206 D", BORROWED, 2_000))
        da = loanRepo.create(loan("QA-206 DA", BORROWED, 3_000))
        c = loanRepo.create(loan("QA-206 C", LENT, 2_000))
        ca = loanRepo.create(loan("QA-206 CA", LENT, 3_000))

        goalRepo.updateProgress(gf, 10_000, COMPLETED)
        goalRepo.archive(ga)
        loanRepo.archive(da)
        loanRepo.archive(ca)

        // Valeurs relues : sans elles, aucun cas ne prouve ce qu'il annonce.
        checkStored("goals", g, 2_000, ACTIVE)
        checkStored("goals", ga, 3_000, ARCHIVED)
        checkStored("goals", gf, 10_000, COMPLETED)
        checkStored("loans", d, 2_000, ACTIVE)
        checkStored("loans", da, 3_000, ARCHIVED)
        checkStored("loans", c, 2_000, ACTIVE)
        checkStored("loans", ca, 3_000, ARCHIVED)
    }

    private fun goal(name: String, starting: Long) = GoalEntity(
        name = name, targetAmountCents = 10_000, startingBalanceCents = starting, icon = ICON, colorArgb = BLEU,
    )

    private fun loan(name: String, direction: LoanDirection, starting: Long) = LoanEntity(
        name = name, counterpartyName = "QA-206 Ami", direction = direction, debtType = DebtType.OTHER,
        totalAmountCents = 10_000, startingBalanceCents = starting, interestRate = 0.0, icon = ICON, colorArgb = BLEU,
    )

    private fun checkStored(table: String, id: Long, progress: Long, status: ItemStatus) {
        val read = stored(table, id)
        if (read != (progress to status.name)) fail("Montage : $table#$id relu $read, attendu ${progress to status.name}. Échec de montage.")
    }

    /** Avancement et statut relus par requête brute : aucune API de production ajoutée pour le test. */
    private fun stored(table: String, id: Long): Pair<Long, String> {
        val column = if (table == "goals") "savedAmountCents" else "repaidAmountCents"
        return db.openHelper.readableDatabase.query("SELECT $column, status FROM $table WHERE id = $id").use { cur ->
            check(cur.moveToFirst()) { "$table#$id absent" }
            cur.getLong(0) to cur.getString(1)
        }
    }

    // Lignes attendues, écrites en clair d'après le JDD.
    private fun gLine() = Line(GOAL, g, "QA-206 G", ACTIVE)
    private fun gaLine(status: ItemStatus) = Line(GOAL, ga, "QA-206 GA", status)
    private fun gfLine() = Line(GOAL, gf, "QA-206 GF", COMPLETED)
    private fun dLine(status: ItemStatus) = Line(DEBT, d, "QA-206 D", status)
    private fun daLine(status: ItemStatus) = Line(DEBT, da, "QA-206 DA", status)
    private fun cLine(status: ItemStatus) = Line(RECEIVABLE, c, "QA-206 C", status)
    private fun caLine(status: ItemStatus) = Line(RECEIVABLE, ca, "QA-206 CA", status)

    // ==============================================================================================
    // Instantanés et journal d'écriture
    // ==============================================================================================

    private data class W(val table: String, val op: String, val id: Long)

    /**
     * Déclencheurs permanents (pas `TEMP`, propre à une connexion), créés après l'ouverture de Room :
     * ils voient toute écriture, y compris un UPDATE à valeur égale qu'aucun instantané ne distingue.
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
        db.openHelper.readableDatabase.query("SELECT tbl, op, row_id FROM qa_write_log ORDER BY seq").use { cur ->
            while (cur.moveToNext()) add(W(cur.getString(0), cur.getString(1), cur.getLong(2)))
        }
    }

    private fun clearWriteLog() = db.openHelper.writableDatabase.execSQL("DELETE FROM qa_write_log")

    private fun snapshot(): Map<String, List<Map<String, Any?>>> =
        listOf("goals", "loans", "transactions").associateWith { table ->
            buildList {
                db.openHelper.readableDatabase.query("SELECT * FROM $table ORDER BY id").use { cur ->
                    while (cur.moveToNext()) add((0 until cur.columnCount).associate { i -> cur.getColumnName(i) to cur.typed(i) })
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

        val BLEU = 0xFF2196F3.toInt()
        const val ICON = "star"
    }
}

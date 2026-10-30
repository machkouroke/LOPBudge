package com.lop.budget.domain.usecase.financial

import android.app.Application
import android.database.Cursor
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.Event
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import com.lop.budget.data.local.LopDatabase
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.GoalEntity
import com.lop.budget.data.local.entity.LoanEntity
import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.data.repository.AccountRepository
import com.lop.budget.data.repository.GoalRepository
import com.lop.budget.data.repository.LoanRepository
import com.lop.budget.data.repository.TransactionRepository
import com.lop.budget.domain.model.AccountType
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
import com.lop.budget.domain.model.NO_CATEGORY_ID
import com.lop.budget.domain.model.ProgressView
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionStatus.PAID
import com.lop.budget.domain.model.TransactionStatus.PLANNED
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.domain.model.TransactionType.EXPENSE
import com.lop.budget.domain.model.TransactionType.INCOME
import com.lop.budget.domain.usecase.SyncProgressUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.seconds

/**
 * TC-159 — Détail d'un objectif, d'une dette ou d'une créance : identité, contenu lu et
 * réactivité Room, sans écriture (US LOP-203).
 *
 * ## Niveau et chaîne exercée
 * Intégration Room sous Robolectric (SDK 33). Système testé : [ObserveFinancialItemDetailUseCase],
 * sur vrais `GoalRepository`, `LoanRepository`, `AccountRepository`, `TransactionRepository`,
 * vrais DAO et `LopDatabase` en mémoire. Aucune doublure. `SyncProgressUseCase` réel sert
 * **uniquement** à préparer la progression enregistrée ; il n'est jamais appelé pendant une lecture.
 *
 * ## Traçabilité — cas → CA / invariant → production
 * ```
 * I-01 ×7   CA-03, CA-04, I-3, I-1   invoke → FinancialItemDetail (champs, compte, type et taux de dette)
 * I-02 ×7   CA-05, I-1               TransactionDao.observeByGoal / observeByLoan (sélection, tri)
 * I-03 ×5   CA-02, I-2               invoke : branche par type, garde de direction du prêt
 * I-04 ×7   CA-06, CA-02, I-1, I-3   combine(observeById, observeByX, observeAll) sans réabonnement
 * ```
 *
 * ## Lecture des termes de la fiche (mise à jour du 30 octobre 2026)
 * Le code livré au commit `cc89a660` rend `Flow<FinancialItemDetail?>` : « Found » se lit détail
 * non nul, « NotFound » se lit `null`. Compte « Available » = `accountId` et `accountName`
 * renseignés ; « Unavailable » = `accountId` renseigné, `accountName` nul. Une ligne liée se lit
 * dans `TransactionWithRelations.transaction`.
 *
 * ## Oracles
 * - Détails attendus et montants écrits en clair, calculés à la main d'après les règles du moteur ;
 *   jamais par `progressView`, `recalculatedStatus` ou le use case.
 * - I-1 : journal synchrone des requêtes (`setQueryCallback`) — zéro `INSERT`/`UPDATE`/`DELETE`/
 *   `REPLACE` sur `goals`, `loans`, `accounts`, `transactions` pendant une lecture, ce qui voit une
 *   réécriture à valeur égale ; puis instantanés SQL bruts des quatre tables, lignes supprimées
 *   comprises, égaux à la référence prise après la dernière écriture de préparation.
 * - Une seule collecte Turbine par phase, délai réel de 10 s déclaré ici, toutes les émissions
 *   enregistrées. Barrière : l'exécuteur de requêtes Room est un fil unique possédé par le test.
 *
 * ## Hypothèses levées
 * - Contrôle de montage `*SyncProgressRoomTest` exécuté avant l'écriture.
 * - Préconditions assertées au montage : `G1.id == L-DET.id`, `TX-4.id > TX-3.id`, et le journal
 *   voit les écritures de progression du semis (témoin du contrôle I-1).
 *
 * ## ANO connues
 * Aucune.
 *
 * ## Résultats — 30 octobre 2026, JVM (Robolectric, SDK 33), production de `cc89a660`
 * 26 verts sur 26 du premier coup. Preuves de sensibilité en quatre séries à cas disjoints, chaque
 * série retirée aussitôt (`git checkout`), puis 26 sur 26 après restauration.
 * ```
 * M1  tri id ASC à date égale (observeByGoal)            → I-02 G1 ; I-04 contribution, retrait, archivage, modification
 * M3  repli sur l'objectif quand le prêt est introuvable → I-03 (RECEIVABLE, L-DET), (DEBT, L-CRE) ; I-04 suppression créance
 * M4  type et taux exposés pour une créance              → I-01 L-CRE
 * M2  observeById d'objectif filtré sur les non-archivés → I-01 G-ARCH, I-02 G-ARCH, I-04 archivage
 * M5  UPDATE goals à valeur égale au début de la lecture → 14 lectures d'objectif, par le journal seul
 * M6  première valeur seulement (take(1)) côté objectif  → les 5 sous-variantes I-04 d'objectif
 * ```
 * M1, M3 et M4 forment une seule série : leurs rouges ne se recouvrent pas. M5 rougit sans changer
 * aucune cellule : c'est le journal, pas l'instantané, qui voit une réécriture à valeur égale.
 *
 * ## Hors périmètre
 * Navigation, rendu conditionnel, libellés, barre globale (TC-161) ; chargement, erreur, réessai et
 * arguments de route (TC-160) ; calcul de l'avancement (LOP-79) ; compte associé archivé, cible non
 * calculable, ajustement de solde rattaché (non tranchés par l'US) ; CA-08 (aucune fiche).
 *
 * ## Exécution
 * ```
 * export TMP='C:\gtmp' TEMP='C:\gtmp'
 * ./gradlew :app:testDebugUnitTest --tests "*FinancialItemDetailRoomTest"
 * ```
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class FinancialItemDetailRoomTest {

    private val sqlLog = CopyOnWriteArrayList<String>()
    private lateinit var queryExecutor: ExecutorService

    private lateinit var db: LopDatabase
    private lateinit var goalRepo: GoalRepository
    private lateinit var loanRepo: LoanRepository
    private lateinit var sync: SyncProgressUseCase
    private lateinit var observe: ObserveFinancialItemDetailUseCase

    private lateinit var previousTimeZone: TimeZone
    private lateinit var previousLocale: Locale

    // Identifiants alloués par Room, conservés sous leurs noms symboliques.
    private var acc = 0L
    private var accSuppr = 0L
    private var g1 = 0L
    private var g2 = 0L
    private var gAtt = 0L
    private var gArch = 0L
    private var lDet = 0L
    private var lCre = 0L
    private var lDet0 = 0L
    private var txMixte = 0L
    private var tx1 = 0L
    private var tx3 = 0L
    private var tx4 = 0L
    private var tx2 = 0L
    private var txDette = 0L
    private var txAssur = 0L

    @Before
    fun setUp() = runTest {
        previousTimeZone = TimeZone.getDefault()
        previousLocale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Paris"))
        Locale.setDefault(Locale.FRANCE)

        queryExecutor = Executors.newSingleThreadExecutor()
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Application>(),
            LopDatabase::class.java,
        )
            .allowMainThreadQueries()
            .setQueryExecutor(queryExecutor)
            .setQueryCallback({ sql, _ -> sqlLog += sql }, Executor { it.run() })
            .build()
        goalRepo = GoalRepository(db.goalDao())
        loanRepo = LoanRepository(db.loanDao())
        val accountRepo = AccountRepository(db.accountDao())
        val transactionRepo = TransactionRepository(db.transactionDao(), db.recurringSeriesDao())
        sync = SyncProgressUseCase(transactionRepo, goalRepo, loanRepo)
        observe = ObserveFinancialItemDetailUseCase(goalRepo, loanRepo, transactionRepo, accountRepo)

        seed(accountRepo)

        // Préconditions de montage : sans elles, aucun cas ne prouve ce qu'il annonce.
        if (g1 != lDet) {
            fail("Montage : G1 ($g1) et L-DET ($lDet) devaient partager l'identifiant. Échec de montage, pas une preuve métier.")
        }
        if (tx4 <= tx3) {
            fail("Montage : TX-4 ($tx4) devait avoir un identifiant supérieur à TX-3 ($tx3). Échec de montage.")
        }
        // Témoin anti-oracle vacant du contrôle I-1 : le journal doit voir les écritures de
        // progression du semis (archivage de G-ARCH et trois recalculs d'objectif, trois de prêt).
        assertEquals(
            "Montage : le journal ne voit pas les UPDATE goals du semis. ${writesSince(0)}",
            4,
            writesSince(0).count { UPDATE_GOALS.containsMatchIn(it) },
        )
        assertEquals(
            "Montage : le journal ne voit pas les UPDATE loans du semis. ${writesSince(0)}",
            3,
            writesSince(0).count { UPDATE_LOANS.containsMatchIn(it) },
        )
    }

    @After
    fun tearDown() {
        db.close()
        queryExecutor.shutdownNow()
        TimeZone.setDefault(previousTimeZone)
        Locale.setDefault(previousLocale)
    }

    // ==============================================================================================
    // I-01 — CA-03, CA-04, I-3 : tous les champs de l'élément demandé, zéro écriture
    // ==============================================================================================

    @Test
    fun `I-01 G1 - given l'objectif complet G1 when on observe GOAL G1 then detail entier conforme et zero ecriture`() =
        runTest { assertFields("I-01 G1", GOAL, g1, expectedG1()) }

    @Test
    fun `I-01 G2 - given l'objectif G2 sans optionnels when on observe GOAL G2 then optionnels nuls et zero ecriture`() =
        runTest { assertFields("I-01 G2", GOAL, g2, expectedG2()) }

    @Test
    fun `I-01 G-ATT - given l'objectif atteint when on observe GOAL G-ATT then statut COMPLETED, 100 pourcent et zero ecriture`() =
        runTest { assertFields("I-01 G-ATT", GOAL, gAtt, expectedGAtt()) }

    @Test
    fun `I-01 G-ARCH - given l'objectif archive when on observe GOAL G-ARCH then detail ARCHIVED lisible et zero ecriture`() =
        runTest { assertFields("I-01 G-ARCH", GOAL, gArch, expectedGArch()) }

    @Test
    fun `I-01 L-DET - given la dette au compte supprime when on observe DEBT L-DET then compte indisponible, type LOAN, taux 3,5 et zero ecriture`() =
        runTest { assertFields("I-01 L-DET", DEBT, lDet, expectedLDet()) }

    @Test
    fun `I-01 L-CRE - given la creance stockee OTHER a 0 when on observe RECEIVABLE L-CRE then ni type ni taux et zero ecriture`() =
        runTest { assertFields("I-01 L-CRE", RECEIVABLE, lCre, expectedLCre()) }

    @Test
    fun `I-01 L-DET0 - given la dette a taux nul when on observe DEBT L-DET0 then taux 0,0 present, contrepartie nulle et zero ecriture`() =
        runTest { assertFields("I-01 L-DET0", DEBT, lDet0, expectedLDet0()) }

    // ==============================================================================================
    // I-02 — CA-05 : transactions liées exactes, ordonnées, supprimées et étrangères exclues
    // ==============================================================================================

    @Test
    fun `I-02 G1 - given G1 et ses six liens dont un supprime when on observe GOAL G1 then TX-2, TX-4, TX-3, TX-1, TX-MIXTE dans cet ordre`() =
        runTest { assertRows("I-02 G1", GOAL, g1, listOf(rowTx2(), rowTx4(), rowTx3(), rowTx1(), rowTxMixte())) }

    @Test
    fun `I-02 G2 - given G2 sans lien when on observe GOAL G2 then aucune ligne, G1 non vide en temoin`() =
        runTest { assertRows("I-02 G2", GOAL, g2, emptyList()) }

    @Test
    fun `I-02 G-ATT - given G-ATT sans lien when on observe GOAL G-ATT then aucune ligne, G1 non vide en temoin`() =
        runTest { assertRows("I-02 G-ATT", GOAL, gAtt, emptyList()) }

    @Test
    fun `I-02 G-ARCH - given G-ARCH sans lien when on observe GOAL G-ARCH then aucune ligne, G1 non vide en temoin`() =
        runTest { assertRows("I-02 G-ARCH", GOAL, gArch, emptyList()) }

    @Test
    fun `I-02 L-DET - given L-DET et deux liens when on observe DEBT L-DET then TX-ASSUR puis TX-DETTE`() =
        runTest { assertRows("I-02 L-DET", DEBT, lDet, listOf(rowTxAssur(), rowTxDette())) }

    @Test
    fun `I-02 L-CRE - given TX-MIXTE liee a G1 et L-CRE when on observe RECEIVABLE L-CRE then TX-MIXTE seule`() =
        runTest { assertRows("I-02 L-CRE", RECEIVABLE, lCre, listOf(rowTxMixte())) }

    @Test
    fun `I-02 L-DET0 - given L-DET0 sans lien when on observe DEBT L-DET0 then aucune ligne, G1 non vide en temoin`() =
        runTest { assertRows("I-02 L-DET0", DEBT, lDet0, emptyList()) }

    // ==============================================================================================
    // I-03 — CA-02, I-2 : identité par type, identifiant et direction ; jamais de repli
    // ==============================================================================================

    @Test
    fun `I-03 DEBT G1 - given G1 et L-DET de meme identifiant when on observe DEBT G1 then L-DET, jamais G1`() = runTest {
        val case = "I-03 (DEBT, G1.id)"
        val read = readPhase(case, DEBT, g1)
        val detail = read.first
            ?: throw AssertionError("$case — CA-02, I-2 : attendu L-DET, reçu introuvable. Trace : ${read.trace()}")
        assertEquals("$case — I-2 : type du détail. Trace : ${read.trace()}", DEBT, detail.kind)
        assertEquals("$case — I-2 : identifiant du détail. Trace : ${read.trace()}", lDet, detail.id)
        assertEquals("$case — CA-02 : nom du détail. Trace : ${read.trace()}", "QA-203 Prêt auto", detail.name)
        assertTrue(
            "$case — I-2 : l'objectif G1 s'est substitué à la dette. Trace : ${read.trace()}",
            read.all.none { it?.name == "QA-203 Vacances" },
        )
    }

    @Test
    fun `I-03 RECEIVABLE L-DET - given une dette when on observe RECEIVABLE L-DET then introuvable, sans repli`() = runTest {
        assertNotFound("I-03 (RECEIVABLE, L-DET.id)", RECEIVABLE, lDet)
    }

    @Test
    fun `I-03 DEBT L-CRE - given une creance when on observe DEBT L-CRE then introuvable, sans repli`() = runTest {
        assertNotFound("I-03 (DEBT, L-CRE.id)", DEBT, lCre)
    }

    @Test
    fun `I-03 GOAL ABSENT - given un identifiant absent des deux tables when on observe GOAL ABSENT then introuvable`() = runTest {
        assertNotFound("I-03 (GOAL, ABSENT)", GOAL, absentId())
    }

    @Test
    fun `I-03 RECEIVABLE ABSENT - given un identifiant absent des deux tables when on observe RECEIVABLE ABSENT then introuvable`() =
        runTest { assertNotFound("I-03 (RECEIVABLE, ABSENT)", RECEIVABLE, absentId()) }

    // ==============================================================================================
    // I-04 — CA-06 : écritures réelles pendant la consultation, même collecte
    // ==============================================================================================

    @Test
    fun `I-04 contribution - given G1 observe when TX-NEUF de 70000 est versee puis G1 recalcule then G1 atteint avec TX-NEUF en tete`() =
        change(
            case = "I-04 contribution", kind = GOAL, id = { g1 },
            coherent = setOf(P_G1 to ACTIVE, ProgressView(100_000, 0, 100) to COMPLETED),
            prepare = {
                val txNeuf = db.transactionDao().upsert(
                    tx("QA-203 Versement juin", 70_000, INCOME, PAID, D4, goal = g1)
                )
                sync.recalculateGoalProgress(g1)
                txNeuf
            },
            expected = { txNeuf ->
                Expected(
                    expectedG1().copy(progress = ProgressView(100_000, 0, 100), status = COMPLETED),
                    listOf(
                        Row(txNeuf, "QA-203 Versement juin", 70_000, INCOME, D4, PAID),
                        rowTx2(), rowTx4(), rowTx3(), rowTx1(), rowTxMixte(),
                    ),
                )
            },
        )

    @Test
    fun `I-04 retrait de lien - given G1 observe when TX-1 perd son lien puis G1 recalcule then 10000 sur 100000 sans TX-1`() =
        change(
            case = "I-04 retrait de lien", kind = GOAL, id = { g1 },
            coherent = setOf(P_G1 to ACTIVE, ProgressView(10_000, 90_000, 10) to ACTIVE),
            prepare = {
                val current = checkNotNull(db.transactionDao().getById(tx1)) { "Préparation : TX-1 introuvable" }.transaction
                db.transactionDao().upsert(current.copy(linkedGoalId = null))
                sync.recalculateGoalProgress(g1)
                0L
            },
            expected = {
                Expected(
                    expectedG1().copy(progress = ProgressView(10_000, 90_000, 10)),
                    listOf(rowTx2(), rowTx4(), rowTx3(), rowTxMixte()),
                )
            },
        )

    @Test
    fun `I-04 archivage - given G1 observe when G1 est archive then detail toujours lisible au statut ARCHIVED`() =
        change(
            case = "I-04 archivage", kind = GOAL, id = { g1 },
            coherent = setOf(P_G1 to ACTIVE, P_G1 to ARCHIVED),
            prepare = { goalRepo.archive(g1); 0L },
            expected = { Expected(expectedG1().copy(status = ARCHIVED), rowsG1()) },
        )

    @Test
    fun `I-04 modification - given G1 observe when G1 est renomme then nouveau nom, tout le reste inchange`() =
        change(
            case = "I-04 modification", kind = GOAL, id = { g1 },
            coherent = setOf(P_G1 to ACTIVE),
            prepare = {
                val current = checkNotNull(goalRepo.getById(g1)) { "Préparation : G1 introuvable" }
                goalRepo.update(current.copy(name = "QA-203 Vacances été"))
                0L
            },
            expected = { Expected(expectedG1().copy(name = "QA-203 Vacances été"), rowsG1()) },
        )

    @Test
    fun `I-04 suppression objectif - given G1 observe when G1 est supprime then introuvable et plus aucun detail ensuite`() =
        change(
            case = "I-04 suppression objectif", kind = GOAL, id = { g1 },
            coherent = setOf(P_G1 to ACTIVE),
            prepare = { goalRepo.delete(g1); 0L },
            expected = { null },
        )

    @Test
    fun `I-04 remboursement dette - given L-DET observee when TX-DETTE2 de 20000 est payee puis L-DET recalculee then 80000 sur 200000`() =
        change(
            case = "I-04 remboursement dette", kind = DEBT, id = { lDet },
            coherent = setOf(P_LDET to ACTIVE, ProgressView(80_000, 120_000, 40) to ACTIVE),
            prepare = {
                val txDette2 = db.transactionDao().upsert(
                    tx("QA-203 Mensualité mai", 20_000, EXPENSE, PAID, D3, loan = lDet)
                )
                sync.recalculateLoanProgress(lDet)
                txDette2
            },
            expected = { txDette2 ->
                Expected(
                    expectedLDet().copy(progress = ProgressView(80_000, 120_000, 40)),
                    listOf(Row(txDette2, "QA-203 Mensualité mai", 20_000, EXPENSE, D3, PAID), rowTxAssur(), rowTxDette()),
                )
            },
        )

    @Test
    fun `I-04 suppression creance - given L-CRE observee when L-CRE est supprimee then introuvable et plus aucun detail ensuite`() =
        change(
            case = "I-04 suppression créance", kind = RECEIVABLE, id = { lCre },
            coherent = setOf(ProgressView(1_000, 29_000, 3) to ACTIVE),
            prepare = { loanRepo.delete(lCre); 0L },
            expected = { null },
        )

    // ==============================================================================================
    // Déroulés communs
    // ==============================================================================================

    /** Première émission : détail entier hors transactions, champs absents compris (I-01). */
    private suspend fun TestScope.assertFields(case: String, kind: FinancialItemKind, id: Long, expected: FinancialItemDetail) {
        val read = readPhase(case, kind, id)
        val detail = read.first
            ?: throw AssertionError("$case — CA-03 : attendu ${expected.name}, reçu introuvable. Trace : ${read.trace()}")
        assertEquals(
            "$case — CA-03, CA-04, I-3 : détail lu (hors transactions, couvertes par I-02). Trace : ${read.trace()}",
            expected,
            detail.copy(transactions = emptyList()),
        )
    }

    /** Liste exacte et ordonnée des lignes liées ; un vide est confronté au témoin G1 (I-02). */
    private suspend fun TestScope.assertRows(case: String, kind: FinancialItemKind, id: Long, expected: List<Row>) {
        val read = readPhase(case, kind, id)
        val detail = read.first
            ?: throw AssertionError("$case — CA-05 : attendu un détail, reçu introuvable. Trace : ${read.trace()}")
        val rows = detail.transactions.map { it.row() }
        assertEquals(
            "$case — CA-05 : identifiants des lignes liées, ordre compris (date puis id décroissants).",
            expected.map { it.id },
            rows.map { it.id },
        )
        assertEquals("$case — CA-05 : libellé, montant, type, date et statut de chaque ligne.", expected, rows)
        if (expected.isEmpty()) {
            val witness = readPhase("$case témoin G1", GOAL, g1).first
            assertEquals(
                "$case — témoin : G1 doit rendre ses cinq lignes dans le même cas, sinon le vide ne prouve rien.",
                5,
                witness?.transactions?.size,
            )
        }
    }

    private suspend fun TestScope.assertNotFound(case: String, kind: FinancialItemKind, id: Long) {
        val read = readPhase(case, kind, id)
        assertEquals("$case — CA-02, I-2 : première émission. Trace : ${read.trace()}", null, read.first)
        assertEquals(
            "$case — I-2 : un détail est apparu avant la barrière de fin. Trace : ${read.trace()}",
            emptyList<FinancialItemDetail>(),
            read.all.filterNotNull(),
        )
    }

    private class Read(val first: FinancialItemDetail?, val later: List<FinancialItemDetail?>) {
        val all get() = listOf(first) + later
        fun trace() = all.map(::describe)
    }

    /**
     * Une phase de lecture : collecte ouverte, première émission, barrière, émissions restantes.
     * Puis I-1 : aucune écriture journalisée, tables identiques à la référence prise avant.
     */
    private suspend fun TestScope.readPhase(case: String, kind: FinancialItemKind, id: Long): Read {
        val reference = snapshot()
        val start = sqlLog.size
        var read: Read? = null
        observe(kind, id).test(timeout = TIMEOUT) {
            val first = next(this, case, "première émission de ($kind, $id)", emptyList())
            barrier(this@readPhase)
            val later = cancelAndConsumeRemainingEvents().filterIsInstance<Event.Item<FinancialItemDetail?>>().map { it.value }
            read = Read(first, later)
        }
        assertEquals("$case — I-1 : écritures pendant la lecture de ($kind, $id).", emptyList<String>(), writesSince(start))
        assertEquals("$case — I-1 : tables modifiées par la lecture de ($kind, $id).", reference, snapshot())
        return checkNotNull(read)
    }

    private data class Expected(val detail: FinancialItemDetail, val rows: List<Row>)

    private fun view(detail: FinancialItemDetail?): Expected? =
        detail?.let { Expected(it.copy(transactions = emptyList()), it.transactions.map { tx -> tx.row() }) }

    /**
     * I-04 : observation ouverte, point de synchronisation sur l'identité, écriture de préparation,
     * attente de l'état final exact sur la **même** collecte, barrière, puis contrôles.
     *
     * [coherent] liste les couples (avancement, statut) admis : une émission qui associe
     * l'avancement d'un résultat au statut d'un autre est un échec (I-3). [prepare] rend l'ID
     * qu'il crée, s'il en crée un, pour l'attendu.
     */
    private fun change(
        case: String,
        kind: FinancialItemKind,
        id: () -> Long,
        coherent: Set<Pair<ProgressView, ItemStatus>>,
        prepare: suspend () -> Long,
        expected: (createdId: Long) -> Expected?,
    ) = runTest {
        val target = id()
        val trace = mutableListOf<FinancialItemDetail?>()
        val start = sqlLog.size
        var syncWrites = emptyList<String>()
        lateinit var reference: Map<String, List<Map<String, Any?>>>
        var prepEnd = 0
        var want: Expected? = null
        var finalIndex = -1

        fun checkCoherent(detail: FinancialItemDetail?) {
            if (detail != null && (detail.progress to detail.status) !in coherent) {
                fail(
                    "$case — CA-06, I-3 : émission incohérente ${detail.progress} / ${detail.status}, " +
                        "admis : $coherent. Trace : ${trace.map(::describe)}"
                )
            }
        }

        observe(kind, target).test(timeout = TIMEOUT) {
            val first = next(this, case, "point de synchronisation", trace)
            trace += first
            if (first == null || first.kind != kind || first.id != target) {
                fail("$case — montage : la collecte devait d'abord rendre ($kind, $target). Trace : ${trace.map(::describe)}")
            }
            checkCoherent(first)
            syncWrites = writesSince(start)

            val created = prepare()
            reference = snapshot()
            prepEnd = sqlLog.size
            want = expected(created)

            while (finalIndex < 0) {
                val item = next(this, case, "état final non reçu ; attendu ${want?.let { describe(it) } ?: "introuvable"}", trace)
                trace += item
                checkCoherent(item)
                if (view(item) == want) finalIndex = trace.lastIndex
            }
            barrier(this@runTest)
            trace += cancelAndConsumeRemainingEvents().filterIsInstance<Event.Item<FinancialItemDetail?>>().map { it.value }
        }
        trace.forEach(::checkCoherent)

        val context = "Trace : ${trace.map(::describe)}"
        assertEquals("$case — I-1 : écritures pendant la synchronisation. $context", emptyList<String>(), syncWrites)
        assertEquals(
            "$case — CA-06 : après l'état final, une émission en diffère (retour arrière ou détail réapparu). $context",
            emptyList<String>(),
            trace.drop(finalIndex + 1).filter { view(it) != want }.map(::describe),
        )
        assertEquals("$case — I-1 : écritures hors préparation. $context", emptyList<String>(), writesSince(prepEnd))
        assertEquals("$case — I-1 : tables modifiées après la préparation. $context", reference, snapshot())
    }

    /** Prochaine émission ; un délai expiré devient un échec métier diagnostiqué. */
    private suspend fun next(
        turbine: ReceiveTurbine<FinancialItemDetail?>,
        case: String,
        what: String,
        trace: List<FinancialItemDetail?>,
    ): FinancialItemDetail? = try {
        turbine.awaitItem()
    } catch (e: AssertionError) {
        throw AssertionError(
            "$case — $what en $TIMEOUT. Dernier état : ${trace.lastOrNull()?.let(::describe)}. " +
                "Trace : ${trace.map(::describe)}. Écritures journalisées : ${writesSince(0)}",
            e,
        )
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
    // Jeu de données — fiche TC-159, créé dans l'ordre prescrit
    // ==============================================================================================

    private suspend fun seed(accountRepo: AccountRepository) {
        acc = accountRepo.upsert(account("QA-203 Compte"))
        accSuppr = accountRepo.upsert(account("QA-203 Compte fermé"))

        g1 = goalRepo.create(
            GoalEntity(
                name = "QA-203 Vacances", targetAmountCents = 100_000, startingBalanceCents = 10_000,
                icon = "savings", colorArgb = VERT, dueDate = ECH, accountId = acc, comment = "QA-203 Note vacances",
            )
        )
        g2 = goalRepo.create(
            GoalEntity(name = "QA-203 Fonds", targetAmountCents = 50_000, startingBalanceCents = 0, icon = "star", colorArgb = VIOLET)
        )
        gAtt = goalRepo.create(
            GoalEntity(name = "QA-203 Vélo", targetAmountCents = 10_000, startingBalanceCents = 10_000, icon = "bike", colorArgb = ORANGE)
        )
        gArch = goalRepo.create(
            GoalEntity(name = "QA-203 Ancien projet", targetAmountCents = 20_000, startingBalanceCents = 5_000, icon = "home", colorArgb = BRUN)
        )
        goalRepo.archive(gArch)

        lDet = loanRepo.create(
            LoanEntity(
                name = "QA-203 Prêt auto", counterpartyName = "QA-203 Banque Crédit", direction = LoanDirection.BORROWED,
                debtType = DebtType.LOAN, totalAmountCents = 200_000, startingBalanceCents = 50_000, interestRate = 3.5,
                icon = "car", colorArgb = ROUGE, accountId = accSuppr,
            )
        )
        // Une créance est stockée OTHER à 0 % : valeurs discriminantes, que le détail ne doit pas exposer.
        lCre = loanRepo.create(
            LoanEntity(
                name = "QA-203 Prêt à Sam", counterpartyName = "QA-203 Sam", direction = LoanDirection.LENT,
                debtType = DebtType.OTHER, totalAmountCents = 30_000, startingBalanceCents = 0, interestRate = 0.0,
                icon = "person", colorArgb = BLEU,
            )
        )
        lDet0 = loanRepo.create(
            LoanEntity(
                name = "QA-203 Dette Léa", counterpartyName = null, direction = LoanDirection.BORROWED,
                debtType = DebtType.PERSONAL, totalAmountCents = 10_000, startingBalanceCents = 0, interestRate = 0.0,
                icon = "payments", colorArgb = GRIS,
            )
        )

        val dao = db.transactionDao()
        txMixte = dao.upsert(tx("QA-203 Remboursement Sam", 1_000, INCOME, PAID, D0, goal = g1, loan = lCre))
        tx1 = dao.upsert(tx("QA-203 Versement mars", 20_000, INCOME, PAID, D1, goal = g1))
        tx3 = dao.upsert(tx("QA-203 Retrait avril", 3_000, EXPENSE, PAID, D2, goal = g1))
        tx4 = dao.upsert(tx("QA-203 Versement avril", 2_000, INCOME, PAID, D2, goal = g1))
        tx2 = dao.upsert(tx("QA-203 Versement mai", 5_000, INCOME, PLANNED, D3, goal = g1))
        dao.upsert(tx("QA-203 Versement supprimé", 9_900, INCOME, PAID, D1, goal = g1).copy(deleted = true))
        txDette = dao.upsert(tx("QA-203 Mensualité mars", 10_000, EXPENSE, PAID, D1, loan = lDet))
        txAssur = dao.upsert(tx("QA-203 Remboursement assurance", 4_000, INCOME, PAID, D2, loan = lDet))
        dao.upsert(tx("QA-203 Courses", 1_500, EXPENSE, PAID, D2))

        // G-ARCH garde la progression posée à sa création.
        listOf(g1, g2, gAtt).forEach { sync.recalculateGoalProgress(it) }
        listOf(lDet, lCre, lDet0).forEach { sync.recalculateLoanProgress(it) }

        // Requête brute : aucune API de production n'est ajoutée pour le test.
        db.openHelper.writableDatabase.execSQL("DELETE FROM accounts WHERE id = ?", arrayOf<Any>(accSuppr))
    }

    private fun account(name: String) = AccountEntity(
        name = name, type = AccountType.CHECKING, initialBalance = 0, balanceUpdatedAt = D0,
        colorArgb = BLEU, icon = "account_balance", bankName = null, includeInTotal = true, archived = false,
    )

    private fun tx(
        title: String,
        amount: Long,
        type: TransactionType,
        status: TransactionStatus,
        date: Long,
        goal: Long? = null,
        loan: Long? = null,
    ) = TransactionEntity(
        title = title, amount = amount, type = type, status = status, date = date,
        paidAt = if (status == PAID) date else null, accountId = acc, categoryId = NO_CATEGORY_ID,
        linkedGoalId = goal, linkedLoanId = loan,
    )

    /** Plus grand identifiant de `goals` et de `loans`, plus 1 000. */
    private fun absentId(): Long =
        db.openHelper.readableDatabase.query(
            "SELECT MAX((SELECT MAX(id) FROM goals), (SELECT MAX(id) FROM loans))"
        ).use { c -> c.moveToFirst(); c.getLong(0) } + 1_000

    // ==============================================================================================
    // Attendus — écrits en clair, d'après le tableau « Détail attendu » de la fiche
    // ==============================================================================================

    private fun expectedG1() = FinancialItemDetail(
        kind = GOAL, id = g1, name = "QA-203 Vacances", icon = "savings", colorArgb = VERT,
        targetCents = 100_000, startingCents = 10_000, progress = P_G1, status = ACTIVE,
        dueDate = ECH, accountId = acc, accountName = "QA-203 Compte", comment = "QA-203 Note vacances",
        counterparty = null, debtType = null, interestRate = null, transactions = emptyList(),
    )

    private fun expectedG2() = FinancialItemDetail(
        kind = GOAL, id = g2, name = "QA-203 Fonds", icon = "star", colorArgb = VIOLET,
        targetCents = 50_000, startingCents = 0, progress = ProgressView(0, 50_000, 0), status = ACTIVE,
        dueDate = null, accountId = null, accountName = null, comment = null,
        counterparty = null, debtType = null, interestRate = null, transactions = emptyList(),
    )

    private fun expectedGAtt() = FinancialItemDetail(
        kind = GOAL, id = gAtt, name = "QA-203 Vélo", icon = "bike", colorArgb = ORANGE,
        targetCents = 10_000, startingCents = 10_000, progress = ProgressView(10_000, 0, 100), status = COMPLETED,
        dueDate = null, accountId = null, accountName = null, comment = null,
        counterparty = null, debtType = null, interestRate = null, transactions = emptyList(),
    )

    private fun expectedGArch() = FinancialItemDetail(
        kind = GOAL, id = gArch, name = "QA-203 Ancien projet", icon = "home", colorArgb = BRUN,
        targetCents = 20_000, startingCents = 5_000, progress = ProgressView(5_000, 15_000, 25), status = ARCHIVED,
        dueDate = null, accountId = null, accountName = null, comment = null,
        counterparty = null, debtType = null, interestRate = null, transactions = emptyList(),
    )

    private fun expectedLDet() = FinancialItemDetail(
        kind = DEBT, id = lDet, name = "QA-203 Prêt auto", icon = "car", colorArgb = ROUGE,
        targetCents = 200_000, startingCents = 50_000, progress = P_LDET, status = ACTIVE,
        dueDate = null, accountId = accSuppr, accountName = null, comment = null,
        counterparty = "QA-203 Banque Crédit", debtType = DebtType.LOAN, interestRate = 3.5, transactions = emptyList(),
    )

    private fun expectedLCre() = FinancialItemDetail(
        kind = RECEIVABLE, id = lCre, name = "QA-203 Prêt à Sam", icon = "person", colorArgb = BLEU,
        targetCents = 30_000, startingCents = 0, progress = ProgressView(1_000, 29_000, 3), status = ACTIVE,
        dueDate = null, accountId = null, accountName = null, comment = null,
        counterparty = "QA-203 Sam", debtType = null, interestRate = null, transactions = emptyList(),
    )

    private fun expectedLDet0() = FinancialItemDetail(
        kind = DEBT, id = lDet0, name = "QA-203 Dette Léa", icon = "payments", colorArgb = GRIS,
        targetCents = 10_000, startingCents = 0, progress = ProgressView(0, 10_000, 0), status = ACTIVE,
        dueDate = null, accountId = null, accountName = null, comment = null,
        counterparty = null, debtType = DebtType.PERSONAL, interestRate = 0.0, transactions = emptyList(),
    )

    private data class Row(
        val id: Long,
        val title: String,
        val amount: Long,
        val type: TransactionType,
        val date: Long,
        val status: TransactionStatus,
    )

    private fun TransactionWithRelations.row() =
        Row(transaction.id, transaction.title, transaction.amount, transaction.type, transaction.date, transaction.status)

    private fun rowTxMixte() = Row(txMixte, "QA-203 Remboursement Sam", 1_000, INCOME, D0, PAID)
    private fun rowTx1() = Row(tx1, "QA-203 Versement mars", 20_000, INCOME, D1, PAID)
    private fun rowTx3() = Row(tx3, "QA-203 Retrait avril", 3_000, EXPENSE, D2, PAID)
    private fun rowTx4() = Row(tx4, "QA-203 Versement avril", 2_000, INCOME, D2, PAID)
    private fun rowTx2() = Row(tx2, "QA-203 Versement mai", 5_000, INCOME, D3, PLANNED)
    private fun rowTxDette() = Row(txDette, "QA-203 Mensualité mars", 10_000, EXPENSE, D1, PAID)
    private fun rowTxAssur() = Row(txAssur, "QA-203 Remboursement assurance", 4_000, INCOME, D2, PAID)
    private fun rowsG1() = listOf(rowTx2(), rowTx4(), rowTx3(), rowTx1(), rowTxMixte())

    // ==============================================================================================
    // Instantanés et journal
    // ==============================================================================================

    private fun writesSince(index: Int): List<String> =
        sqlLog.drop(index).filter { WRITE_ON_OBSERVED_TABLES.containsMatchIn(it) }

    private fun snapshot(): Map<String, List<Map<String, Any?>>> =
        listOf("goals", "loans", "accounts", "transactions").associateWith { table ->
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

        val D0: Long = Instant.parse("2026-02-10T10:00:00Z").toEpochMilli()
        val D1: Long = Instant.parse("2026-03-10T10:00:00Z").toEpochMilli()
        val D2: Long = Instant.parse("2026-04-10T10:00:00Z").toEpochMilli()
        val D3: Long = Instant.parse("2026-05-10T10:00:00Z").toEpochMilli()
        val D4: Long = Instant.parse("2026-06-10T10:00:00Z").toEpochMilli()
        val ECH: Long = Instant.parse("2026-12-31T11:00:00Z").toEpochMilli()

        val VERT = 0xFF4CAF50.toInt()
        val VIOLET = 0xFF9C27B0.toInt()
        val ORANGE = 0xFFFF9800.toInt()
        val BRUN = 0xFF795548.toInt()
        val ROUGE = 0xFFF44336.toInt()
        val BLEU = 0xFF2196F3.toInt()
        val GRIS = 0xFF607D8B.toInt()

        /** Avancements de référence, calculés à la main : G1 = 10 000 + 1 000 + 20 000 − 3 000 + 2 000. */
        val P_G1 = ProgressView(30_000, 70_000, 30)

        /** L-DET = 50 000 + 10 000 de dépense ; le revenu TX-ASSUR ne compte pas pour une dette. */
        val P_LDET = ProgressView(60_000, 140_000, 30)

        val WRITE_ON_OBSERVED_TABLES = Regex(
            """^\s*(INSERT|UPDATE|DELETE|REPLACE)\b.*\b(goals|loans|accounts|transactions)\b""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        val UPDATE_GOALS = Regex("""^\s*UPDATE\b.*\bgoals\b""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val UPDATE_LOANS = Regex("""^\s*UPDATE\b.*\bloans\b""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

        fun describe(d: FinancialItemDetail?): String = d?.let {
            "${it.kind}#${it.id} « ${it.name} » ${it.progress.reachedCents}/${it.progress.remainingCents}/" +
                "${it.progress.percent} ${it.status} tx=${it.transactions.map { t -> t.transaction.id }}"
        } ?: "introuvable"

        fun describe(e: Expected): String =
            "« ${e.detail.name} » ${e.detail.progress} ${e.detail.status} tx=${e.rows.map { it.id }}"
    }
}

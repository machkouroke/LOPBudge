package com.lop.budget.domain.usecase.financial

import android.app.Application
import android.database.Cursor
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.lop.budget.data.local.LopDatabase
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.GoalEntity
import com.lop.budget.data.local.entity.LoanEntity
import com.lop.budget.data.local.entity.RecurringSeriesEntity
import com.lop.budget.data.local.entity.TransactionEntity
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
import com.lop.budget.domain.model.LoanDirection
import com.lop.budget.domain.model.NO_CATEGORY_ID
import com.lop.budget.domain.model.RecurrenceFrequency
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.domain.usecase.SyncProgressUseCase
import com.lop.budget.domain.usecase.transaction.AtomicWriter
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ErrorCollector
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone

/**
 * TC-178 — Archivage financier : conservation, recalcul et atomicité Room (US LOP-206).
 *
 * ## Niveau et chaîne exercée
 * Intégration sur base réelle, JVM/Robolectric SDK 33. Système testé : [ArchiveFinancialItemUseCase]
 * réel (`archive(kind, id)`, `restore(kind, id)`) → [GoalRepository], [LoanRepository],
 * [SyncProgressUseCase], [TransactionRepository] → DAO → `LopDatabase` en mémoire, neuve par
 * variante. L'[AtomicWriter] est `db.withTransaction`, la liaison même d'`AppModule`. Aucune
 * doublure. Seule exception, déclarée : le témoin non transactionnel de R-04, qui n'est pas un oracle
 * métier mais la preuve que l'oracle de rollback n'est pas vide.
 *
 * ## Traçabilité — cas → CA / invariant → production
 * ```
 * R-01 ×3, terminé ×3   CA-02 (LOP-80 I-2, I-4, I-6)   archive : status ∈ {ACTIVE, COMPLETED} → repo.archive → updateStatus
 * R-02 ×3 (×5 cibles)   CA-04, P-6 (LOP-79 CA-07)      restore : repo.restore puis recalculate*Progress → recalculatedStatus
 * R-03 ×3               CA-04 (LOP-79 I-2)             updateProgress garde ARCHIVED ; restore repart de l'avancement en base
 * R-04 ×3               CA-05 (LOP-80 I-4)             restore dans atomicWriter.atomically (db.withTransaction)
 * R-05 ×3               CA-05 (LOP-80 I-4, I-5, I-6)   status(kind, id) : famille et direction de la route, no-op sinon
 * ```
 *
 * ## Oracles
 * - Snapshot (copie de référence) de toutes les tables applicatives en SQL brut, colonnes typées,
 *   pris après toute préparation. Seule la ligne cible peut différer, et elle devient exactement la
 *   ligne attendue, écrite en clair colonne par colonne : identité, nom, départ, cible ou initial,
 *   direction, contrepartie, type, taux, échéance, compte, icône, couleur, commentaire, progression,
 *   statut. Aucune ligne ajoutée ni retirée dans aucune table (transactions, séries, comptes).
 * - Rien n'est recalculé par le code testé pour fabriquer l'attendu.
 * - Les cas à plusieurs variantes passent par un `ErrorCollector` : chaque variante a sa base et son
 *   message, un rouge ne masque pas les suivants.
 *
 * ## Données (JDD de la fiche)
 * Comptes A et B ; objectif G (cible 100 €, départ 20 €, compte A), dette D (BORROWED, LOAN, 3,5 %,
 * « QA-206 Banque ») et créance C (LENT, OTHER, 0 %, « QA-206 Sam »), même échéance, couleur et
 * commentaire ; une transaction payée de 10 € par élément sur B (TG et TC en revenu, TD en dépense) ;
 * la série S liée à G et D. G et D, premiers de leur table, partagent l'identifiant 1. Après
 * synchronisation, G, D et C valent 30 € et sont ACTIVE : vérifié avant tout snapshot.
 *
 * ## Hypothèses
 * `AtomicWriter` lié à `db.withTransaction` en production (`AppModule`, relu). Les cibles nulles ou
 * négatives sont posées en SQL brut : les formulaires actuels les refusent.
 *
 * ## ANO connues
 * Aucune.
 *
 * ## Résultats — 4 novembre 2026, JVM/Robolectric SDK 33, production de `65a69e6c`
 * 18 verts sur 18 au premier passage ; contrôle de montage TC-171 : 23 sur 23. Preuves de
 * sensibilité, une mutation par passage, restaurée par copie puis vérifiée identique à HEAD ; retour
 * à 18 sur 18 :
 * ```
 * W1  restore hors atomically                         → R-04 ×3 (statut ACTIVE resté après l'échec)
 * W2  direction ignorée par la lecture du statut      → R-05 D, R-05 C (mauvaise direction archivée)
 * W3  seuil >= remplacé par > (recalculatedStatus)    → R-02 ×3, cible 30 € ; montage des variantes
 *                                                        « terminé » (R-01, R-05), préparées par ce calcul
 * W4  restauration sans recalcul                      → R-02 ×3, cible 30 € ; R-04 ×3 arrêté par son
 *                                                        témoin (plus de recalcul à faire échouer)
 * W5  archiver remet l'avancement de l'objectif à 0   → R-01 G actif, R-01 G terminé (+ montages G)
 * W7  archiver écrit dans les deux familles           → R-01 G ×2, R-01 D ×2 (G et D partagent l'ID 1)
 * ```
 *
 * ## Hors périmètre
 * Rendu, navigation, boutons et sélecteurs (TC-177) ; états du ViewModel (TC-175) ; listes (TC-176) ;
 * suppression définitive, modification des champs, détachement de l'historique, archivage
 * automatique.
 *
 * ## Exécution
 * ```
 * export TMP='C:\gtmp' TEMP='C:\gtmp'
 * ./gradlew :app:testDebugUnitTest --tests "*FinancialItemArchiveRoomTest"
 * ```
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class FinancialItemArchiveRoomTest {

    @get:Rule
    val errors = ErrorCollector()

    private lateinit var defaultTimeZone: TimeZone
    private lateinit var defaultLocale: Locale
    private val opened = mutableListOf<LopDatabase>()

    @Before
    fun setUp() {
        defaultTimeZone = TimeZone.getDefault()
        defaultLocale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(PARIS))
        Locale.setDefault(Locale.FRANCE)
    }

    @After
    fun tearDown() {
        opened.forEach { it.close() }
        TimeZone.setDefault(defaultTimeZone)
        Locale.setDefault(defaultLocale)
    }

    // ==============================================================================================
    // R-01 — CA-02 : archiver ne change que le statut
    // ==============================================================================================

    @Test
    fun `R-01 G actif - given G ACTIVE a 30 euros when archive GOAL G then seul le statut devient ARCHIVED, transactions et serie conservees`() =
        runTest { archiveKeepsEverything("R-01 G actif", GOAL, completed = false) }

    @Test
    fun `R-01 D actif - given D ACTIVE a 30 euros when archive DEBT D then seul le statut devient ARCHIVED, transactions et serie conservees`() =
        runTest { archiveKeepsEverything("R-01 D actif", DEBT, completed = false) }

    @Test
    fun `R-01 C actif - given C ACTIVE a 30 euros when archive RECEIVABLE C then seul le statut devient ARCHIVED, transactions et serie conservees`() =
        runTest { archiveKeepsEverything("R-01 C actif", RECEIVABLE, completed = false) }

    @Test
    fun `R-01 G termine - given G COMPLETED cible 30 euros when archive GOAL G then seul le statut devient ARCHIVED`() =
        runTest { archiveKeepsEverything("R-01 G terminé", GOAL, completed = true) }

    @Test
    fun `R-01 D termine - given D COMPLETED initial 30 euros when archive DEBT D then seul le statut devient ARCHIVED`() =
        runTest { archiveKeepsEverything("R-01 D terminé", DEBT, completed = true) }

    @Test
    fun `R-01 C termine - given C COMPLETED initial 30 euros when archive RECEIVABLE C then seul le statut devient ARCHIVED`() =
        runTest { archiveKeepsEverything("R-01 C terminé", RECEIVABLE, completed = true) }

    private suspend fun archiveKeepsEverything(case: String, kind: FinancialItemKind, completed: Boolean) {
        val b = banc()
        val id = b.idOf(kind)
        val target = if (completed) 3_000L else 10_000L
        if (completed) {
            b.setTarget(kind, id, 3_000)
            b.sync(kind, id)
            b.expectStored("$case, montage", kind, id, progress = 3_000, status = "COMPLETED", target = 3_000)
        }
        val s0 = b.snapshot()

        b.useCase.archive(kind, id)

        b.expectOnlyRowChanged("$case, CA-02", s0, kind, b.expectedRow(kind, target = target, progress = 3_000, status = "ARCHIVED"))
    }

    // ==============================================================================================
    // R-02 — CA-04, P-6 : le statut restauré suit l'avancement ; cible nulle ou négative → actif
    // ==============================================================================================

    @Test
    fun `R-02 G - given G archive a 30 euros when la cible vaut 100, 30, 29,99, 0 ou -0,01 euro puis restore then ACTIVE, COMPLETED, COMPLETED, ACTIVE, ACTIVE`() =
        runTest { restoreFollowsProgress("R-02 G", GOAL) }

    @Test
    fun `R-02 D - given D archivee a 30 euros when l initial vaut 100, 30, 29,99, 0 ou -0,01 euro puis restore then ACTIVE, COMPLETED, COMPLETED, ACTIVE, ACTIVE`() =
        runTest { restoreFollowsProgress("R-02 D", DEBT) }

    @Test
    fun `R-02 C - given C archivee a 30 euros when l initial vaut 100, 30, 29,99, 0 ou -0,01 euro puis restore then ACTIVE, COMPLETED, COMPLETED, ACTIVE, ACTIVE`() =
        runTest { restoreFollowsProgress("R-02 C", RECEIVABLE) }

    private suspend fun restoreFollowsProgress(case: String, kind: FinancialItemKind) {
        // Cible ou initial posé en base → statut attendu après restauration, avancement 30 € inchangé.
        val variants = listOf(
            10_000L to "ACTIVE",
            3_000L to "COMPLETED",
            2_999L to "COMPLETED",
            0L to "ACTIVE",
            -1L to "ACTIVE",
        )
        for ((target, expected) in variants) variant("$case cible $target") {
            val b = banc()
            val id = b.idOf(kind)
            b.useCase.archive(kind, id)
            b.setTarget(kind, id, target)
            b.expectStored("$case cible $target, montage", kind, id, progress = 3_000, status = "ARCHIVED", target = target)
            val sArch = b.snapshot()

            b.useCase.restore(kind, id)

            b.expectOnlyRowChanged(
                "$case cible $target, CA-04/P-6", sArch, kind,
                b.expectedRow(kind, target = target, progress = 3_000, status = expected),
            )
        }
    }

    // ==============================================================================================
    // R-03 — CA-04 : un recalcul pendant l'archivage garde ARCHIVED ; la restauration repart de la base
    // ==============================================================================================

    @Test
    fun `R-03 G - given G archive when TG passe a 20 euros et synchro puis restore then 40 euros ARCHIVED, puis 40 euros ACTIVE et rien d autre`() =
        runTest { recalcWhileArchived("R-03 G", GOAL) }

    @Test
    fun `R-03 D - given D archivee when TD passe a 20 euros et synchro puis restore then 40 euros ARCHIVED, puis 40 euros ACTIVE et rien d autre`() =
        runTest { recalcWhileArchived("R-03 D", DEBT) }

    @Test
    fun `R-03 C - given C archivee when TC passe a 20 euros et synchro puis restore then 40 euros ARCHIVED, puis 40 euros ACTIVE et rien d autre`() =
        runTest { recalcWhileArchived("R-03 C", RECEIVABLE) }

    private suspend fun recalcWhileArchived(case: String, kind: FinancialItemKind) {
        val b = banc()
        val id = b.idOf(kind)
        b.useCase.archive(kind, id)
        val linked = b.linkedTx(kind)
        b.transactionRepo.upsert(linked.copy(amount = 2_000))
        b.sync(kind, id)
        b.expectStored("$case, CA-04 avant restauration", kind, id, progress = 4_000, status = "ARCHIVED", target = 10_000)
        val sArch = b.snapshot()

        b.useCase.restore(kind, id)

        b.expectOnlyRowChanged("$case, CA-04", sArch, kind, b.expectedRow(kind, target = 10_000, progress = 4_000, status = "ACTIVE"))
    }

    // ==============================================================================================
    // R-04 — CA-05 : un échec du recalcul de restauration annule tout ; le réessai réussit
    // ==============================================================================================

    @Test
    fun `R-04 G - given G archive et le recalcul de G bloque when restore then exception et base egale au snapshot archive, puis reessai ACTIVE 30 euros`() =
        runTest { rollbackThenRetry("R-04 G", GOAL) }

    @Test
    fun `R-04 D - given D archivee et le recalcul de D bloque when restore then exception et base egale au snapshot archive, puis reessai ACTIVE 30 euros`() =
        runTest { rollbackThenRetry("R-04 D", DEBT) }

    @Test
    fun `R-04 C - given C archivee et le recalcul de C bloque when restore then exception et base egale au snapshot archive, puis reessai ACTIVE 30 euros`() =
        runTest { rollbackThenRetry("R-04 C", RECEIVABLE) }

    private suspend fun rollbackThenRetry(case: String, kind: FinancialItemKind) {
        // Témoin, sur une base neuve sans transaction : la restauration écrit bien le statut avant le
        // recalcul bloqué. Sans lui, l'égalité au snapshot ci-dessous pourrait venir d'une restauration
        // qui n'écrit rien.
        val w = banc(transactional = false)
        val wId = w.idOf(kind)
        w.useCase.archive(kind, wId)
        w.blockRecalc(kind, wId)
        val wError = runCatching { w.useCase.restore(kind, wId) }.exceptionOrNull()
        check(wError?.message.orEmpty().contains(TRIGGER_MESSAGE)) { "$case, témoin : échec attendu « $TRIGGER_MESSAGE », reçu $wError" }
        check(w.row(kind, wId)["status"] == "ACTIVE") {
            "$case, témoin : sans transaction, la première écriture (statut ACTIVE) doit rester : ${w.row(kind, wId)}"
        }

        // given
        val b = banc()
        val id = b.idOf(kind)
        b.useCase.archive(kind, id)
        b.blockRecalc(kind, id)
        val sArch = b.snapshot()

        // when
        val error = runCatching { b.useCase.restore(kind, id) }.exceptionOrNull()

        // then
        assertTrue(
            "$case, CA-05 : l'échec du recalcul doit remonter à l'appelant ; reçu $error",
            error?.message.orEmpty().contains(TRIGGER_MESSAGE),
        )
        b.expectUnchanged("$case, CA-05 : juste après l'échec, la base doit être égale au snapshot archivé", sArch)

        // when — déclencheur retiré, réessai
        b.unblockRecalc()
        b.useCase.restore(kind, id)

        b.expectOnlyRowChanged("$case, CA-05 réessai", sArch, kind, b.expectedRow(kind, target = 10_000, progress = 3_000, status = "ACTIVE"))
    }

    // ==============================================================================================
    // R-05 — CA-05 : opération répétée, identité inconnue, mauvaise direction, autre famille → rien
    // ==============================================================================================

    @Test
    fun `R-05 G - given G when archive repete, restore d un actif ou d un termine, id inconnu ou id de l autre famille then aucune ligne changee`() =
        runTest {
            val case = "R-05 G"
            noOpOnSameState(case, GOAL)
            unknownIdentity(case, GOAL)

            // C (prêt n° 2) n'a pas d'homologue parmi les objectifs : ni l'archiver, ni le restaurer.
            variant("$case, id d'un prêt seulement") {
                val b = banc()
                check(b.c !in b.ids("goals")) { "montage : l'objectif ${b.c} ne doit pas exister" }
                expectNoOp(b, "$case, archive(GOAL, C) sur C actif") { b.useCase.archive(GOAL, b.c) }
                b.useCase.archive(RECEIVABLE, b.c)
                expectNoOp(b, "$case, restore(GOAL, C) sur C archivée") { b.useCase.restore(GOAL, b.c) }
            }
            // Base où l'identité n'existe que parmi les prêts : la dette seule.
            variant("$case, base sans objectif") {
                val b = bareBanc()
                val loan = b.loanRepo.create(debtEntity(b.a))
                expectNoOp(b, "$case, archive(GOAL, $loan) sur la seule dette active") { b.useCase.archive(GOAL, loan) }
                b.useCase.archive(DEBT, loan)
                expectNoOp(b, "$case, restore(GOAL, $loan) sur la seule dette archivée") { b.useCase.restore(GOAL, loan) }
            }
        }

    @Test
    fun `R-05 D - given D when archive repete, restore d un actif ou d un termine, id inconnu, creance C ou objectif seul then aucune ligne changee`() =
        runTest {
            val case = "R-05 D"
            noOpOnSameState(case, DEBT)
            unknownIdentity(case, DEBT)

            // C est une créance (LENT) : la route DEBT ne doit ni l'archiver, ni la restaurer.
            variant("$case, mauvaise direction") {
                val b = banc()
                expectNoOp(b, "$case, archive(DEBT, C) sur C active") { b.useCase.archive(DEBT, b.c) }
                b.useCase.archive(RECEIVABLE, b.c)
                expectNoOp(b, "$case, restore(DEBT, C) sur C archivée") { b.useCase.restore(DEBT, b.c) }
            }
            otherFamilyGoalOnly(case, DEBT)
        }

    @Test
    fun `R-05 C - given C when archive repete, restore d un actif ou d un termine, id inconnu, dette D ou objectif seul then aucune ligne changee`() =
        runTest {
            val case = "R-05 C"
            noOpOnSameState(case, RECEIVABLE)
            unknownIdentity(case, RECEIVABLE)

            // D est une dette (BORROWED) : la route RECEIVABLE ne doit ni l'archiver, ni la restaurer.
            variant("$case, mauvaise direction") {
                val b = banc()
                expectNoOp(b, "$case, archive(RECEIVABLE, D) sur D active") { b.useCase.archive(RECEIVABLE, b.d) }
                b.useCase.archive(DEBT, b.d)
                expectNoOp(b, "$case, restore(RECEIVABLE, D) sur D archivée") { b.useCase.restore(RECEIVABLE, b.d) }
            }
            otherFamilyGoalOnly(case, RECEIVABLE)
        }

    /** Archiver un archivé, restaurer un actif, restaurer un terminé : aucun changement. */
    private suspend fun noOpOnSameState(case: String, kind: FinancialItemKind) {
        variant("$case, restore d'un actif") {
            val b = banc()
            expectNoOp(b, "$case, restore sur ACTIVE") { b.useCase.restore(kind, b.idOf(kind)) }
        }
        variant("$case, restore d'un terminé") {
            val b = banc()
            val id = b.idOf(kind)
            b.setTarget(kind, id, 3_000)
            b.sync(kind, id)
            b.expectStored("$case, montage", kind, id, progress = 3_000, status = "COMPLETED", target = 3_000)
            expectNoOp(b, "$case, restore sur COMPLETED") { b.useCase.restore(kind, id) }
        }
        variant("$case, archive répété") {
            val b = banc()
            val id = b.idOf(kind)
            b.useCase.archive(kind, id)
            b.expectStored("$case, montage", kind, id, progress = 3_000, status = "ARCHIVED", target = 10_000)
            expectNoOp(b, "$case, second archive") { b.useCase.archive(kind, id) }
        }
    }

    private suspend fun unknownIdentity(case: String, kind: FinancialItemKind) = variant("$case, identité inconnue") {
        val b = banc()
        check(ABSENT !in b.ids("goals") && ABSENT !in b.ids("loans")) { "montage : $ABSENT existe déjà" }
        expectNoOp(b, "$case, archive($kind, $ABSENT)") { b.useCase.archive(kind, ABSENT) }
        expectNoOp(b, "$case, restore($kind, $ABSENT)") { b.useCase.restore(kind, ABSENT) }
    }

    /** Base où l'identité n'existe que parmi les objectifs : un seul objectif, actif puis archivé. */
    private suspend fun otherFamilyGoalOnly(case: String, kind: FinancialItemKind) = variant("$case, base sans prêt") {
        val b = bareBanc()
        val goal = b.goalRepo.create(goalEntity(b.a))
        expectNoOp(b, "$case, archive($kind, $goal) sur le seul objectif actif") { b.useCase.archive(kind, goal) }
        b.useCase.archive(GOAL, goal)
        expectNoOp(b, "$case, restore($kind, $goal) sur le seul objectif archivé") { b.useCase.restore(kind, goal) }
    }

    private suspend fun expectNoOp(b: Banc, label: String, call: suspend () -> Unit) {
        val s = b.snapshot()
        call()
        b.expectUnchanged("$label, CA-05 : aucune ligne changée, créée ni supprimée", s)
    }

    /** Une variante : base propre, et un échec consigné sans interrompre les suivantes. */
    private suspend fun variant(label: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            errors.addError(AssertionError("$label — ${e.message}", e))
        }
    }

    // ==============================================================================================
    // Banc : une base neuve par variante, semée selon le JDD de la fiche
    // ==============================================================================================

    private suspend fun banc(transactional: Boolean = true): Banc = Banc(transactional).also { it.seed() }

    /** Base aux seuls comptes A et B, pour les variantes « autre famille ». */
    private suspend fun bareBanc(): Banc = Banc(transactional = true).also { it.seedAccounts() }

    private inner class Banc(transactional: Boolean) {
        val db: LopDatabase = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Application>(),
            LopDatabase::class.java,
        ).allowMainThreadQueries().build().also { opened += it }

        val goalRepo = GoalRepository(db.goalDao())
        val loanRepo = LoanRepository(db.loanDao())
        private val accountRepo = AccountRepository(db.accountDao())
        val transactionRepo = TransactionRepository(db.transactionDao(), db.recurringSeriesDao())
        private val syncProgress = SyncProgressUseCase(transactionRepo, goalRepo, loanRepo)
        private val writer = if (transactional) {
            object : AtomicWriter {
                override suspend fun <T> atomically(block: suspend () -> T): T = db.withTransaction(block)
            }
        } else {
            object : AtomicWriter {
                override suspend fun <T> atomically(block: suspend () -> T): T = block()
            }
        }
        val useCase = ArchiveFinancialItemUseCase(goalRepo, loanRepo, syncProgress, writer)

        var a = 0L; var b = 0L
        var g = 0L; var d = 0L; var c = 0L
        private val linked = mutableMapOf<FinancialItemKind, TransactionEntity>()

        suspend fun seedAccounts() {
            a = accountRepo.upsert(account("QA-206 A"))
            b = accountRepo.upsert(account("QA-206 B"))
            check(a != b) { "montage : comptes A et B confondus" }
        }

        suspend fun seed() {
            seedAccounts()
            // G et D d'abord : premiers de leur table, ils partagent l'identifiant.
            g = goalRepo.create(goalEntity(a))
            d = loanRepo.create(debtEntity(a))
            c = loanRepo.create(
                LoanEntity(
                    name = "QA-206 C", direction = LoanDirection.LENT, debtType = DebtType.OTHER,
                    totalAmountCents = 10_000, startingBalanceCents = 2_000, interestRate = 0.0,
                    counterpartyName = "QA-206 Sam", accountId = a, icon = "payments", colorArgb = COUL,
                    dueDate = ECH, comment = NOTE,
                )
            )
            check(g == d) { "montage : G ($g) et D ($d) doivent partager l'identifiant" }

            for ((kind, tx) in listOf(
                GOAL to tx("QA-206 TG", TransactionType.INCOME, goal = g),
                DEBT to tx("QA-206 TD", TransactionType.EXPENSE, loan = d),
                RECEIVABLE to tx("QA-206 TC", TransactionType.INCOME, loan = c),
            )) {
                linked[kind] = tx.copy(id = transactionRepo.upsert(tx))
            }
            transactionRepo.upsertSeries(
                RecurringSeriesEntity(
                    title = "QA-206 SER", amount = 2_500, type = TransactionType.EXPENSE,
                    categoryId = NO_CATEGORY_ID, accountId = b, frequency = RecurrenceFrequency.MONTHLY,
                    startDate = DATE, linkedGoalId = g, linkedLoanId = d,
                )
            )

            sync(GOAL, g); sync(DEBT, d); sync(RECEIVABLE, c)

            // Précondition de la fiche : 20 € de départ + 10 € payés = 30 €, ACTIVE ; liens en place.
            for (kind in FinancialItemKind.entries) {
                expectStored("montage", kind, idOf(kind), progress = 3_000, status = "ACTIVE", target = 10_000)
            }
            val txs = snapshot().getValue("transactions")
            check(txs.size == 3) { "montage : 3 transactions attendues, $txs" }
            check(txs.map { it["linkedGoalId"] to it["linkedLoanId"] }.toSet() == setOf(g to null, null to d, null to c)) {
                "montage : chaque transaction liée à son seul élément, $txs"
            }
            val series = snapshot().getValue("recurring_series").single()
            check(series["linkedGoalId"] == g && series["linkedLoanId"] == d) { "montage : S liée à G et D, $series" }
        }

        fun idOf(kind: FinancialItemKind) = when (kind) {
            GOAL -> g
            DEBT -> d
            RECEIVABLE -> c
        }

        fun linkedTx(kind: FinancialItemKind) = linked.getValue(kind)

        suspend fun sync(kind: FinancialItemKind, id: Long) =
            if (kind == GOAL) syncProgress.recalculateGoalProgress(id) else syncProgress.recalculateLoanProgress(id)

        /** Cible ou initial posé en SQL brut : les anciennes données que les formulaires refusent. */
        fun setTarget(kind: FinancialItemKind, id: Long, cents: Long) =
            if (kind == GOAL) sql("UPDATE goals SET targetAmountCents = $cents WHERE id = $id")
            else sql("UPDATE loans SET totalAmountCents = $cents WHERE id = $id")

        /** Bloque l'écriture de l'avancement de la seule ligne cible ; le statut, lui, s'écrit. */
        fun blockRecalc(kind: FinancialItemKind, id: Long) = sql(
            if (kind == GOAL) "CREATE TRIGGER qa206_recalcul BEFORE UPDATE OF savedAmountCents ON goals " +
                "WHEN OLD.id = $id BEGIN SELECT RAISE(ABORT, '$TRIGGER_MESSAGE'); END"
            else "CREATE TRIGGER qa206_recalcul BEFORE UPDATE OF repaidAmountCents ON loans " +
                "WHEN OLD.id = $id BEGIN SELECT RAISE(ABORT, '$TRIGGER_MESSAGE'); END"
        )

        fun unblockRecalc() = sql("DROP TRIGGER qa206_recalcul")

        /** Ligne attendue de [kind] : le JDD, sauf cible, avancement et statut. */
        fun expectedRow(kind: FinancialItemKind, target: Long, progress: Long, status: String): Map<String, Any?> = when (kind) {
            GOAL -> mapOf(
                "id" to g, "name" to "QA-206 G", "targetAmountCents" to target, "startingBalanceCents" to 2_000L,
                "savedAmountCents" to progress, "status" to status, "dueDate" to ECH, "accountId" to a,
                "comment" to NOTE, "colorArgb" to COUL.toLong(), "icon" to "savings",
            )
            DEBT -> loanRow(
                id = d, name = "QA-206 D", counterparty = "QA-206 Banque", direction = "BORROWED", debtType = "LOAN",
                total = target, repaid = progress, rate = 3.5, status = status,
            )
            RECEIVABLE -> loanRow(
                id = c, name = "QA-206 C", counterparty = "QA-206 Sam", direction = "LENT", debtType = "OTHER",
                total = target, repaid = progress, rate = 0.0, status = status,
            )
        }

        private fun loanRow(
            id: Long, name: String, counterparty: String, direction: String, debtType: String,
            total: Long, repaid: Long, rate: Double, status: String,
        ): Map<String, Any?> = mapOf(
            "id" to id, "name" to name, "counterpartyName" to counterparty, "direction" to direction,
            "debtType" to debtType, "totalAmountCents" to total, "startingBalanceCents" to 2_000L,
            "repaidAmountCents" to repaid, "interestRate" to rate, "status" to status, "dueDate" to ECH,
            "accountId" to a, "comment" to NOTE, "colorArgb" to COUL.toLong(), "icon" to "payments",
        )

        fun table(kind: FinancialItemKind) = if (kind == GOAL) "goals" else "loans"

        fun row(kind: FinancialItemKind, id: Long): Map<String, Any?> =
            snapshot().getValue(table(kind)).singleOrNull { it["id"] == id } ?: error("${table(kind)}#$id absent")

        fun ids(table: String): Set<Any?> = snapshot().getValue(table).map { it["id"] }.toSet()

        /** Contrôle de montage : avancement, statut et cible relus en base. */
        fun expectStored(label: String, kind: FinancialItemKind, id: Long, progress: Long, status: String, target: Long) {
            val row = row(kind, id)
            val stored = Triple(row["savedAmountCents"] ?: row["repaidAmountCents"], row["status"], row["targetAmountCents"] ?: row["totalAmountCents"])
            assertEquals("$label : ${table(kind)}#$id (avancement, statut, cible) ; ligne $row", Triple(progress, status, target), stored)
        }

        fun sql(statement: String) = db.openHelper.writableDatabase.execSQL(statement)

        fun snapshot(): Map<String, List<Map<String, Any?>>> {
            val sql = db.openHelper.readableDatabase
            val tables = sql.query(
                "SELECT name FROM sqlite_master WHERE type = 'table' " +
                    "AND name NOT IN ('android_metadata', 'room_master_table', 'sqlite_sequence') ORDER BY name"
            ).use { cur -> buildList { while (cur.moveToNext()) add(cur.getString(0)) } }
            return tables.associateWith { table ->
                sql.query("SELECT * FROM `$table` ORDER BY rowid").use { cur ->
                    buildList {
                        while (cur.moveToNext()) add(cur.columnNames.indices.associate { cur.getColumnName(it) to cur.value(it) })
                    }
                }
            }
        }

        fun diff(before: Map<String, List<Map<String, Any?>>>, after: Map<String, List<Map<String, Any?>>>): Diff {
            val tables = before.keys + after.keys
            fun delta(from: Map<String, List<Map<String, Any?>>>, to: Map<String, List<Map<String, Any?>>>) =
                tables.associateWith { t -> to[t].orEmpty().filter { it !in from[t].orEmpty() } }.filterValues { it.isNotEmpty() }
            return Diff(added = delta(before, after), removed = delta(after, before))
        }

        fun expectUnchanged(label: String, s: Map<String, List<Map<String, Any?>>>) {
            val diff = diff(s, snapshot())
            assertTrue("$label ; différences :\n$diff", diff.isEmpty())
        }

        /**
         * Seule la ligne de [kind] change, et elle devient exactement [expected] : aucune ligne créée
         * ni supprimée ; transactions, série, comptes et autres éléments identiques à [s].
         */
        fun expectOnlyRowChanged(label: String, s: Map<String, List<Map<String, Any?>>>, kind: FinancialItemKind, expected: Map<String, Any?>) {
            val id = expected.getValue("id") as Long
            val table = table(kind)
            assertEquals("$label : ligne $table#$id", expected, row(kind, id))
            val before = s.getValue(table).single { it["id"] == id }
            val diff = diff(s, snapshot())
            assertEquals(
                "$label : seule $table#$id peut différer du snapshot",
                Diff(added = mapOf(table to listOf(expected)), removed = mapOf(table to listOf(before))),
                diff,
            )
        }

        private fun Cursor.value(i: Int): Any? = when (getType(i)) {
            Cursor.FIELD_TYPE_NULL -> null
            Cursor.FIELD_TYPE_INTEGER -> getLong(i)
            Cursor.FIELD_TYPE_FLOAT -> getDouble(i)
            Cursor.FIELD_TYPE_BLOB -> getBlob(i).toList()
            else -> getString(i)
        }

        private fun account(name: String) = AccountEntity(
            name = name, type = AccountType.CHECKING, initialBalance = 150_000, balanceUpdatedAt = DATE,
            colorArgb = COUL, icon = "account_balance", archived = false,
        )

        private fun tx(title: String, type: TransactionType, goal: Long? = null, loan: Long? = null) = TransactionEntity(
            title = title, amount = 1_000, type = type, status = TransactionStatus.PAID, date = DATE,
            paidAt = DATE, accountId = b, categoryId = NO_CATEGORY_ID, linkedGoalId = goal, linkedLoanId = loan,
        )
    }

    /** Lignes ajoutées et retirées par table ; une ligne modifiée apparaît dans les deux. */
    private data class Diff(
        val added: Map<String, List<Map<String, Any?>>>,
        val removed: Map<String, List<Map<String, Any?>>>,
    ) {
        fun isEmpty() = added.isEmpty() && removed.isEmpty()
        override fun toString() =
            (added.map { (t, rows) -> "+ $t : $rows" } + removed.map { (t, rows) -> "- $t : $rows" }).joinToString("\n")
    }

    private fun goalEntity(account: Long) = GoalEntity(
        name = "QA-206 G", targetAmountCents = 10_000, startingBalanceCents = 2_000, accountId = account,
        icon = "savings", colorArgb = COUL, dueDate = ECH, comment = NOTE,
    )

    private fun debtEntity(account: Long) = LoanEntity(
        name = "QA-206 D", direction = LoanDirection.BORROWED, debtType = DebtType.LOAN,
        totalAmountCents = 10_000, startingBalanceCents = 2_000, interestRate = 3.5,
        counterpartyName = "QA-206 Banque", accountId = account, icon = "payments", colorArgb = COUL,
        dueDate = ECH, comment = NOTE,
    )

    private companion object {
        val PARIS: ZoneId = ZoneId.of("Europe/Paris")

        /** Date fixe de la fiche : 10 mars 2024 à 00:00, Paris (transactions, série, comptes). */
        val DATE: Long = LocalDate.of(2024, 3, 10).atStartOfDay(PARIS).toInstant().toEpochMilli()

        /** Échéance de la fiche : 15 mars 2024 à 00:00, Paris. */
        val ECH: Long = LocalDate.of(2024, 3, 15).atStartOfDay(PARIS).toInstant().toEpochMilli()

        /** 0xFF8E24AA. */
        val COUL: Int = 0xFF8E24AA.toInt()

        const val NOTE = "QA-206 note"
        const val ABSENT = 9_999_999L
        const val TRIGGER_MESSAGE = "QA-206 recalcul"
    }
}

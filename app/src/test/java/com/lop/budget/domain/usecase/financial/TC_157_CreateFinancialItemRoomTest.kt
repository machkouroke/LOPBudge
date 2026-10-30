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
import com.lop.budget.domain.model.ItemStatus
import com.lop.budget.domain.model.LoanDirection
import com.lop.budget.domain.model.NO_CATEGORY_ID
import com.lop.budget.domain.model.RecurrenceFrequency
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.domain.usecase.SyncProgressUseCase
import com.lop.budget.domain.usecase.financial.FinancialItemRefusal.AccountUnavailable
import com.lop.budget.domain.usecase.financial.FinancialItemRefusal.BlankName
import com.lop.budget.domain.usecase.financial.FinancialItemRefusal.InvalidRate
import com.lop.budget.domain.usecase.financial.FinancialItemRefusal.InvalidStarting
import com.lop.budget.domain.usecase.financial.FinancialItemRefusal.InvalidTarget
import com.lop.budget.domain.usecase.financial.FinancialItemRefusal.StartingAboveTarget
import com.lop.budget.domain.usecase.transaction.AtomicWriter
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone

/**
 * TC-157 — Création d'objectif, dette ou créance : refus métier, écriture exacte et atomicité sur
 * Room (US LOP-202).
 *
 * ## Niveau et chaîne exercée
 * Intégration sur vraie base Room en mémoire, sous Robolectric. Le système testé est
 * [CreateFinancialItemUseCase], appelé directement : c'est la « soumission directe à la création
 * métier » de CA-04. Tout le reste est réel : `GoalRepository`, `LoanRepository`,
 * `AccountRepository`, `SyncProgressUseCase`, `TransactionRepository`, et un [AtomicWriter] sur
 * `db.withTransaction`, la liaison même de `AppModule.provideAtomicWriter`. Aucune doublure : les
 * échecs viennent de la base, par déclencheur SQL.
 *
 * ## Traçabilité — cas → CA / invariant → production
 * ```
 * C-01        CA-03, I-2, P-3   invoke → GoalRepository.create → recalculateGoalProgress (trim l.87)
 * C-02        CA-03, I-2        invoke → LoanRepository.create → recalculateLoanProgress
 * C-03 a/b    CA-03, CA-04      branche créance : sens LENT, type et taux neutralisés (l.95, l.131)
 * C-04        CA-04 (arrondi)   Format.centsOrNull, HALF_UP (Format.kt l.94)
 * R-01..R-08  CA-04, I-1        refus avant toute écriture (l.87–l.109)
 * témoin R-05 CA-03, P-3        borne départ = cible (l.105) et statut Terminé dès la création
 * A-01 a/b/c  CA-08, I-1        atomically { create + recalculate } : annulation complète
 * ```
 *
 * ## Oracles
 * - Snapshot (copie intégrale du contenu de la base) pris juste avant l'appel, comparé après table
 *   par table : une création ajoute exactement une ligne, dans `goals` ou `loans`, et rien d'autre ;
 *   un refus ou un échec ne change rien. Les lignes témoins (compte, transaction, série, objectif,
 *   prêt) rendent cette preuve non vacante (I-2).
 * - La ligne nouvelle est comparée en entier à une entité écrite en clair dans le test. Avancement
 *   et statut sont lus en base, jamais recalculés ici. Le repository pose déjà un avancement égal au
 *   départ à l'insertion : seul un statut COMPLETED (C-03, témoin R-05) prouve que l'initialisation
 *   du moteur a eu lieu.
 * - L'identifiant rendu n'est pas prédit : il doit être celui de l'unique ligne nouvelle, et aucun
 *   des éléments existants.
 *
 * ## ANO connues
 * LOP-216 (nom enregistré sans trim) et LOP-218 (montant illisible pris pour 0), corrigées par la
 * mise en conformité du 28 octobre 2026 (P-5) ; C-01 et R-04 a en portent la preuve par mutation.
 *
 * ## Résultats — 30 octobre 2026, JVM (Robolectric, SDK 33), production de `d1b186d0`
 * 23 verts sur 23 du premier coup. Essai de montage concluant : le déclencheur fait échouer
 * l'écriture avec « QA-202 » dans l'erreur. Une mutation à la fois, retirée aussitôt ; 23 sur 23
 * après restauration.
 * ```
 * M1  trim retiré, l.87                          → C-01 (nom non trimé), R-01, R-08 a/b
 * M2  départ illisible pris pour 0, l.92         → R-04a
 * M3  création hors transaction, l.86            → A-01 b/c (ligne insérée restée en base)
 * M4  `>` remplacé par `>=`, l.105               → C-03 a/b, témoin R-05
 * M5  arrondi vers le bas, Format.kt l.94        → C-04 (1 050), témoin R-05
 * M6  archivage du compte ignoré, l.99           → R-07a, R-08 a/b
 * M7  type et taux gardés pour une créance       → C-03 a (LOAN, 7,5), C-03 b (taux refusé)
 * ```
 *
 * ## Hors périmètre
 * Formulaire, verrou anti double appui, restauration et navigation : TC-156 et TC-158. Formats que
 * l'US ne définit pas (notation scientifique, très grands montants) et trim du commentaire ou de la
 * contrepartie. Calcul de l'avancement après la création (LOP-79).
 *
 * ## Exécution
 * ```
 * export TMP='C:\gtmp' TEMP='C:\gtmp'
 * ./gradlew :app:testDebugUnitTest --tests "*CreateFinancialItemRoomTest"
 * ```
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class CreateFinancialItemRoomTest {

    private lateinit var db: LopDatabase
    private lateinit var goalRepo: GoalRepository
    private lateinit var loanRepo: LoanRepository
    private lateinit var create: CreateFinancialItemUseCase

    private lateinit var defaultTimeZone: TimeZone
    private lateinit var defaultLocale: Locale

    // JDD commun, IDs alloués par Room.
    private var cpt = 0L
    private var arch = 0L
    private var objEx = 0L
    private var pretEx = 0L

    @Before
    fun setUp() = runTest {
        defaultTimeZone = TimeZone.getDefault()
        defaultLocale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(PARIS))
        Locale.setDefault(Locale.FRANCE)

        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Application>(),
            LopDatabase::class.java,
        ).allowMainThreadQueries().build()

        goalRepo = GoalRepository(db.goalDao())
        loanRepo = LoanRepository(db.loanDao())
        val accountRepo = AccountRepository(db.accountDao())
        val transactionRepo = TransactionRepository(db.transactionDao(), db.recurringSeriesDao())
        val atomicWriter = object : AtomicWriter {
            override suspend fun <T> atomically(block: suspend () -> T): T = db.withTransaction(block)
        }
        create = CreateFinancialItemUseCase(
            goalRepo, loanRepo, accountRepo,
            SyncProgressUseCase(transactionRepo, goalRepo, loanRepo),
            atomicWriter,
        )

        cpt = accountRepo.upsert(account("QA-202 Compte courant", archived = false))
        arch = accountRepo.upsert(account("QA-202 Compte clos", archived = true))
        transactionRepo.upsert(
            TransactionEntity(
                title = "QA-202 Dépense témoin",
                amount = 4_200,
                type = TransactionType.EXPENSE,
                status = TransactionStatus.PAID,
                date = WITNESS_DATE,
                paidAt = WITNESS_DATE,
                accountId = cpt,
                categoryId = NO_CATEGORY_ID,
            )
        )
        transactionRepo.upsertSeries(
            RecurringSeriesEntity(
                title = "QA-202 Série témoin",
                amount = 999,
                type = TransactionType.EXPENSE,
                categoryId = NO_CATEGORY_ID,
                accountId = cpt,
                frequency = RecurrenceFrequency.MONTHLY,
                startDate = WITNESS_DATE,
            )
        )
        objEx = goalRepo.create(
            GoalEntity(
                name = "QA-202 Objectif existant",
                targetAmountCents = 100_000,
                startingBalanceCents = 10_000,
                colorArgb = COUL,
                icon = "savings",
            )
        )
        pretEx = loanRepo.create(
            LoanEntity(
                name = "QA-202 Prêt existant",
                direction = LoanDirection.BORROWED,
                totalAmountCents = 50_000,
                startingBalanceCents = 0,
                colorArgb = COUL,
                icon = "payments",
            )
        )
    }

    @After
    fun tearDown() {
        db.close()
        TimeZone.setDefault(defaultTimeZone)
        Locale.setDefault(defaultLocale)
    }

    // ==============================================================================================
    // C — créations valides : exactement une ligne, valeurs converties, initialisation (CA-03)
    // ==============================================================================================

    @Test
    fun c01_given_complete_goal_with_padded_name_when_created_then_one_trimmed_goal_row() = runTest {
        val draft = FinancialItemDraft(
            kind = FinancialItemKind.GOAL,
            name = "  QA-202 Vacances  ",
            targetInput = "12,50",
            startingInput = "2,5",
            dueDate = ECH,
            accountId = cpt,
            icon = "flight",
            colorArgb = COUL,
            comment = "QA-202 note",
        )
        expectGoalCreated("C-01", draft) { id ->
            GoalEntity(
                id = id,
                name = "QA-202 Vacances",
                targetAmountCents = 1_250,
                startingBalanceCents = 250,
                savedAmountCents = 250,
                status = ItemStatus.ACTIVE,
                dueDate = ECH,
                accountId = cpt,
                comment = "QA-202 note",
                colorArgb = COUL,
                icon = "flight",
            )
        }
    }

    @Test
    fun c02_given_complete_debt_when_created_then_one_borrowed_loan_row() = runTest {
        val draft = FinancialItemDraft(
            kind = FinancialItemKind.DEBT,
            name = "QA-202 Crédit auto",
            targetInput = "1500,50",
            startingInput = "200",
            dueDate = null,
            accountId = null,
            icon = "payments",
            colorArgb = COUL,
            comment = "",
            counterparty = "QA-202 Banque",
            debtType = DebtType.LOAN,
            rateInput = "3,5",
        )
        expectLoanCreated("C-02", draft) { id ->
            LoanEntity(
                id = id,
                name = "QA-202 Crédit auto",
                counterpartyName = "QA-202 Banque",
                direction = LoanDirection.BORROWED,
                debtType = DebtType.LOAN,
                totalAmountCents = 150_050,
                startingBalanceCents = 20_000,
                repaidAmountCents = 20_000,
                interestRate = 3.5,
                status = ItemStatus.ACTIVE,
                dueDate = null,
                accountId = null,
                comment = null,
                colorArgb = COUL,
                icon = "payments",
            )
        }
    }

    @Test
    fun c03a_given_receivable_with_debt_type_and_rate_when_created_then_neutral_values_and_completed() =
        runTest { receivable("C-03a", rateInput = "7,5") }

    @Test
    fun c03b_given_receivable_with_unreadable_rate_when_created_then_not_refused_and_neutral_values() =
        runTest { receivable("C-03b", rateInput = "abc") }

    private suspend fun receivable(case: String, rateInput: String) {
        val draft = FinancialItemDraft(
            kind = FinancialItemKind.RECEIVABLE,
            name = "QA-202 Avance Karim",
            targetInput = "80",
            startingInput = "80",
            dueDate = null,
            accountId = cpt,
            icon = "volunteer_activism",
            colorArgb = COUL,
            comment = "",
            counterparty = "QA-202 Karim",
            debtType = DebtType.LOAN,
            rateInput = rateInput,
        )
        expectLoanCreated(case, draft) { id ->
            LoanEntity(
                id = id,
                name = "QA-202 Avance Karim",
                counterpartyName = "QA-202 Karim",
                direction = LoanDirection.LENT,
                debtType = DebtType.OTHER,
                totalAmountCents = 8_000,
                startingBalanceCents = 8_000,
                repaidAmountCents = 8_000,
                interestRate = 0.0,
                status = ItemStatus.COMPLETED,
                dueDate = null,
                accountId = cpt,
                comment = null,
                colorArgb = COUL,
                icon = "volunteer_activism",
            )
        }
    }

    @Test
    fun c04_given_minimal_debt_of_10_505_when_created_then_rounded_half_up_to_1051() = runTest {
        val draft = FinancialItemDraft(
            kind = FinancialItemKind.DEBT,
            name = "QA-202 Dette minimale",
            targetInput = "10,505",
            startingInput = "",
            dueDate = null,
            accountId = null,
            icon = "payments",
            colorArgb = COUL,
            comment = "",
        )
        expectLoanCreated("C-04", draft) { id ->
            LoanEntity(
                id = id,
                name = "QA-202 Dette minimale",
                counterpartyName = null,
                direction = LoanDirection.BORROWED,
                debtType = DebtType.OTHER,
                totalAmountCents = 1_051,
                startingBalanceCents = 0,
                repaidAmountCents = 0,
                interestRate = 0.0,
                status = ItemStatus.ACTIVE,
                dueDate = null,
                accountId = null,
                comment = null,
                colorArgb = COUL,
                icon = "payments",
            )
        }
    }

    // ==============================================================================================
    // R — refus : ensemble exact, aucune écriture (CA-04, I-1)
    // ==============================================================================================

    @Test fun r01_given_blank_name_when_created_then_refused_blank_name() = runTest {
        expectRefused("R-01", BASE_OBJ.copy(name = "   "), BlankName)
    }

    @Test fun r02a_given_unreadable_target_when_created_then_refused_invalid_target() = runTest {
        expectRefused("R-02a", BASE_OBJ.copy(targetInput = "abc"), InvalidTarget)
    }

    @Test fun r02b_given_empty_target_when_created_then_refused_invalid_target() = runTest {
        expectRefused("R-02b", BASE_OBJ.copy(targetInput = ""), InvalidTarget)
    }

    @Test fun r03a_given_target_null_after_rounding_when_created_then_refused_invalid_target() = runTest {
        expectRefused("R-03a", BASE_OBJ.copy(targetInput = "0,004"), InvalidTarget)
    }

    @Test fun r03b_given_negative_target_when_created_then_refused_invalid_target() = runTest {
        expectRefused("R-03b", BASE_OBJ.copy(targetInput = "-5"), InvalidTarget)
    }

    @Test fun r04a_given_unreadable_starting_when_created_then_refused_not_taken_as_zero() = runTest {
        expectRefused("R-04a", BASE_OBJ.copy(startingInput = "abc"), InvalidStarting)
    }

    @Test fun r04b_given_negative_starting_when_created_then_refused_invalid_starting() = runTest {
        expectRefused("R-04b", BASE_OBJ.copy(startingInput = "-0,01"), InvalidStarting)
    }

    @Test fun r05_given_starting_above_target_when_created_then_refused_starting_above_target() = runTest {
        expectRefused("R-05", BASE_OBJ.copy(targetInput = "50", startingInput = "50,01"), StartingAboveTarget)
    }

    /** Bornes comprises (P-3) : 10,005 et 10,009 valent tous deux 1 001 centimes une fois arrondis. */
    @Test fun r05_witness_given_starting_equal_to_target_after_rounding_when_created_then_completed() = runTest {
        expectGoalCreated("R-05 témoin", BASE_OBJ.copy(targetInput = "10,005", startingInput = "10,009")) { id ->
            GoalEntity(
                id = id,
                name = "QA-202 Base",
                targetAmountCents = 1_001,
                startingBalanceCents = 1_001,
                savedAmountCents = 1_001,
                status = ItemStatus.COMPLETED,
                dueDate = null,
                accountId = null,
                comment = null,
                colorArgb = COUL,
                icon = "savings",
            )
        }
    }

    @Test fun r06a_given_negative_debt_rate_when_created_then_refused_invalid_rate() = runTest {
        expectRefused("R-06a", BASE_DET.copy(rateInput = "-0,5"), InvalidRate)
    }

    @Test fun r06b_given_unreadable_debt_rate_when_created_then_refused_invalid_rate() = runTest {
        expectRefused("R-06b", BASE_DET.copy(rateInput = "abc"), InvalidRate)
    }

    @Test fun r07a_given_archived_account_when_created_then_refused_account_unavailable() = runTest {
        expectRefused("R-07a", BASE_OBJ.copy(accountId = arch), AccountUnavailable)
    }

    @Test fun r07b_given_missing_account_when_created_then_refused_account_unavailable() = runTest {
        expectRefused("R-07b", BASE_OBJ.copy(accountId = INEX), AccountUnavailable)
    }

    @Test fun r08a_given_goal_wrong_everywhere_when_created_then_every_refusal_at_once() = runTest {
        expectRefused(
            "R-08a",
            BASE_OBJ.copy(name = " ", targetInput = "abc", startingInput = "-1", accountId = arch),
            BlankName, InvalidTarget, InvalidStarting, AccountUnavailable,
        )
    }

    @Test fun r08b_given_debt_wrong_everywhere_when_created_then_every_refusal_at_once() = runTest {
        expectRefused(
            "R-08b",
            BASE_DET.copy(name = " ", targetInput = "abc", startingInput = "-1", accountId = arch, rateInput = "-0,5"),
            BlankName, InvalidTarget, InvalidStarting, AccountUnavailable, InvalidRate,
        )
    }

    // ==============================================================================================
    // A — échec en cours de création : tout est annulé (CA-08, I-1)
    // ==============================================================================================

    @Test fun a01a_given_goal_insert_fails_when_created_then_nothing_written() = runTest {
        expectRolledBack("A-01a", "BEFORE INSERT ON goals", BASE_OBJ, "goals")
    }

    /** L'insertion a déjà eu lieu quand l'initialisation échoue : seule une annulation la retire. */
    @Test fun a01b_given_goal_initialisation_fails_when_created_then_insert_rolled_back() = runTest {
        expectRolledBack("A-01b", "BEFORE UPDATE ON goals", BASE_OBJ, "goals")
    }

    @Test fun a01c_given_debt_initialisation_fails_when_created_then_insert_rolled_back() = runTest {
        expectRolledBack("A-01c", "BEFORE UPDATE ON loans", BASE_DET, "loans")
    }

    // ==============================================================================================
    // Oracles
    // ==============================================================================================

    private suspend fun expectGoalCreated(case: String, draft: FinancialItemDraft, expected: (Long) -> GoalEntity) {
        val id = expectSingleRowCreated(case, draft, "goals")
        assertEquals("$case CA-03 : objectif écrit", expected(id), goalRepo.getById(id))
    }

    private suspend fun expectLoanCreated(case: String, draft: FinancialItemDraft, expected: (Long) -> LoanEntity) {
        val id = expectSingleRowCreated(case, draft, "loans")
        assertEquals("$case CA-03 : prêt écrit", expected(id), loanRepo.getById(id))
    }

    /** Créé, du type demandé, et exactement une ligne nouvelle dans [table], rien d'autre (CA-03, I-2). */
    private suspend fun expectSingleRowCreated(case: String, draft: FinancialItemDraft, table: String): Long {
        val before = snapshot()
        val result = create(draft)
        val diff = diff(before, snapshot())

        val created = result as? FinancialItemCreateResult.Created
            ?: throw AssertionError("$case CA-03 : attendu Created(${draft.kind}), reçu $result")
        assertEquals("$case CA-03 : type créé", draft.kind, created.kind)

        val added = diff.added[table].orEmpty()
        val others = diff.copy(added = diff.added - table)
        assertTrue("$case CA-03/I-2 : seule $table reçoit une ligne, rien d'autre ne change ; écart :\n$diff", others.isEmpty())
        assertEquals("$case CA-03 : exactement une ligne nouvelle dans $table ; écart :\n$diff", 1, added.size)

        val newId = added.single().getValue("id") as Long
        assertEquals("$case CA-03 : l'identifiant rendu est celui de la ligne écrite", newId, created.id)
        assertTrue("$case CA-03 : identifiant propre, aucun élément existant", newId != objEx && newId != pretEx)
        return newId
    }

    private suspend fun expectRefused(case: String, draft: FinancialItemDraft, vararg refusals: FinancialItemRefusal) {
        val before = snapshot()
        val result = create(draft)
        val diff = diff(before, snapshot())
        assertEquals("$case CA-04 : refus exacts", FinancialItemCreateResult.Refused(refusals.toSet()), result)
        assertTrue("$case I-1 : aucune écriture après un refus ; écart :\n$diff", diff.isEmpty())
    }

    /**
     * Un déclencheur fait échouer une écriture ; son message ne contient ni « unique » ni « 1555 » :
     * l'upsert Room changerait sinon l'échec en mise à jour silencieuse.
     */
    private suspend fun expectRolledBack(case: String, timing: String, draft: FinancialItemDraft, table: String) {
        val sql = db.openHelper.writableDatabase
        sql.execSQL("CREATE TRIGGER qa202_echec $timing BEGIN SELECT RAISE(ABORT, 'QA-202 échec contrôlé'); END")
        val before = snapshot()

        val outcome = runCatching { create(draft) }
        val error = outcome.exceptionOrNull()
            ?: throw AssertionError("$case CA-08 : l'appel devait échouer, il a rendu ${outcome.getOrNull()}")
        assertTrue(
            "$case montage : l'erreur doit venir du déclencheur (« QA-202 » dans le message ou une cause), reçu $error",
            generateSequence(error) { it.cause }.any { it.message.orEmpty().contains("QA-202") },
        )
        val diff = diff(before, snapshot())
        assertTrue("$case CA-08/I-1 : base inchangée après l'échec ; écart :\n$diff", diff.isEmpty())

        // Témoin : même base, même brouillon, sans le déclencheur.
        sql.execSQL("DROP TRIGGER qa202_echec")
        expectSingleRowCreated("$case témoin", draft, table)
    }

    // ==============================================================================================
    // Snapshot : toutes les tables, lues en SQL brut
    // ==============================================================================================

    private fun snapshot(): Map<String, List<Map<String, Any?>>> {
        val sql = db.openHelper.readableDatabase
        val tables = sql.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' " +
                "AND name NOT IN ('android_metadata', 'room_master_table', 'sqlite_sequence') ORDER BY name"
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        return tables.associateWith { table ->
            sql.query("SELECT * FROM `$table` ORDER BY rowid").use { c ->
                buildList { while (c.moveToNext()) add(c.columnNames.indices.associate { c.getColumnName(it) to c.value(it) }) }
            }
        }
    }

    private fun Cursor.value(i: Int): Any? = when (getType(i)) {
        Cursor.FIELD_TYPE_NULL -> null
        Cursor.FIELD_TYPE_INTEGER -> getLong(i)
        Cursor.FIELD_TYPE_FLOAT -> getDouble(i)
        Cursor.FIELD_TYPE_BLOB -> getBlob(i).toList()
        else -> getString(i)
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

    private fun diff(
        before: Map<String, List<Map<String, Any?>>>,
        after: Map<String, List<Map<String, Any?>>>,
    ): Diff {
        val tables = before.keys + after.keys
        fun delta(from: Map<String, List<Map<String, Any?>>>, to: Map<String, List<Map<String, Any?>>>) =
            tables.associateWith { t -> to[t].orEmpty().filter { it !in from[t].orEmpty() } }.filterValues { it.isNotEmpty() }
        return Diff(added = delta(before, after), removed = delta(after, before))
    }

    private fun account(name: String, archived: Boolean) = AccountEntity(
        name = name,
        type = AccountType.CHECKING,
        initialBalance = 150_000,
        balanceUpdatedAt = WITNESS_DATE,
        colorArgb = COUL,
        icon = "account_balance",
        archived = archived,
    )

    private companion object {
        val PARIS: ZoneId = ZoneId.of("Europe/Paris")

        /** Échéance passée, autorisée par P-3 : 15 mars 2024 à 00:00, Paris (1 710 457 200 000 ms). */
        val ECH: Long = LocalDate.of(2024, 3, 15).atStartOfDay(PARIS).toInstant().toEpochMilli()

        /** Date des lignes témoins, sans rôle dans les oracles. */
        val WITNESS_DATE: Long = LocalDate.of(2024, 3, 1).atStartOfDay(PARIS).toInstant().toEpochMilli()

        /** 0xFF8E24AA, soit −7 461 718. */
        val COUL: Int = 0xFF8E24AA.toInt()

        /** Identifiant de compte absent de la base. */
        const val INEX = 9_999_999L

        val BASE_OBJ = FinancialItemDraft(
            kind = FinancialItemKind.GOAL,
            name = "QA-202 Base",
            targetInput = "100",
            startingInput = "",
            dueDate = null,
            accountId = null,
            icon = "savings",
            colorArgb = COUL,
            comment = "",
        )

        val BASE_DET = FinancialItemDraft(
            kind = FinancialItemKind.DEBT,
            name = "QA-202 Base dette",
            targetInput = "100",
            startingInput = "",
            dueDate = null,
            accountId = null,
            icon = "payments",
            colorArgb = COUL,
            comment = "",
            counterparty = "",
            debtType = DebtType.OTHER,
            rateInput = "",
        )
    }
}

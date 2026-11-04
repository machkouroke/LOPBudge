package com.lop.budget.domain.usecase.transaction

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.lop.budget.data.local.LopDatabase
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.CategoryEntity
import com.lop.budget.data.local.entity.GoalEntity
import com.lop.budget.data.local.entity.LoanEntity
import com.lop.budget.data.local.entity.RecurringSeriesEntity
import com.lop.budget.data.local.entity.TagEntity
import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.data.local.entity.TransactionTagCrossRef
import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.data.repository.AccountRepository
import com.lop.budget.data.repository.CategoryRepository
import com.lop.budget.data.repository.GoalRepository
import com.lop.budget.data.repository.LoanRepository
import com.lop.budget.data.repository.TransactionRepository
import com.lop.budget.domain.model.AccountType
import com.lop.budget.domain.model.EditScope
import com.lop.budget.domain.model.LoanDirection
import com.lop.budget.domain.model.MissingDayBehavior
import com.lop.budget.domain.model.RecurrenceFrequency
import com.lop.budget.domain.model.TransactionEdition
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.domain.usecase.SyncProgressUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
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
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone
import kotlin.time.Duration.Companion.seconds

/**
 * TC-165 — Rattachement transaction : liens écrits, retrait d'un lien et propagation par portée
 * dans Room (LOP-5).
 *
 * ## Niveau et chaîne exercée
 * Intégration sur base réelle : Room en mémoire sous Robolectric, clés étrangères actives.
 * [CreateTransactionUseCase] / [EditTransactionWithScopeUseCase] → [SaveTransactionUseCase] →
 * [TransactionRepository] → DAO, avec [SyncProgressUseCase] réel pour le recalcul. Aucun mock,
 * aucun fake, aucun spy. Les lignes sont relues par SQL brut dans le test ; les listes liées par
 * `observeByGoal` / `observeByLoan` (première émission, celles du détail LOP-203).
 *
 * ## Traçabilité — cas → CA/invariant → production
 * | Cas      | CA / invariant      | Production                                                        |
 * |----------|---------------------|-------------------------------------------------------------------|
 * | I-01 a-d | CA-03, I-1          | `CreateTransactionUseCase.invoke` → `saveSimple`, `toTransactionEntity` |
 * | I-02 a-b | CA-04, I-1          | `EditTransactionWithScopeUseCase.editSingle` (ponctuelle)         |
 * | I-03     | CA-06, CA-03        | `editSingle` + `materializeOccurrence`, `saveSimple`              |
 * | I-04     | CA-06, CA-03, I-1   | `editFuture`, `editionOverlay`, `rewrite`                         |
 * | I-05     | CA-06, P-4          | `editFuture` (lien comparé à la série avant édition)              |
 * | I-06     | CA-06, CA-03, I-1   | `editAll`, `editionOverlay`, `rewrite`                            |
 *
 * ## Hypothèses
 * - Chaque édition reproduit ce que le formulaire envoie pour la portée ouverte (prouvé par
 *   TC-166) : en SINGLE et FUTURE les valeurs de l'occurrence, en ALL les valeurs de base de la
 *   série, date de début comprise. Seul le champ nommé par le cas diffère.
 * - Objectifs et prêts vivent dans deux tables : G1 et D1 reçoivent le même identifiant, vérifié
 *   au montage. Les sous-variantes à un seul lien détectent ainsi une inversion objectif / prêt.
 * - Avancements de référence après préparation, en centimes : G1 = 4 600, G2 = 9 900,
 *   G3 = 30 000, D1 = 11 100, D2 = 2 000 (vérifiés au montage des cas de série).
 *
 * ## Anomalies connues
 * Aucune à l'écriture du fichier.
 *
 * ## Hors périmètre
 * - Règles de somme LOP-79 (signe, statut payé, sens du prêt) et recalcul d'une écriture simple :
 *   fiche « transaction modifiée pas à pas par les vrais use cases » de LOP-79.
 * - CA-05 (nouveau choix indisponible) : règle portée par le ViewModel seul, TC-166.
 * - Choix proposés, préremplissage, affichage, navigation : TC-166, TC-167.
 * - I-2 (annuler n'écrit rien) : aucun use case n'est appelé ; TC-166 V-07, TC-167 U-02 et U-07.
 * - Règles de portée hors liens (coupure de série, dates) : LOP-52, TC_74 et TC_76.
 *
 * ## Exécution
 * ```
 * export TMP='C:\gtmp' TEMP='C:\gtmp'
 * ./gradlew :app:testDebugUnitTest --tests "*TransactionLinksRoomTest"
 * ```
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class TransactionLinksRoomTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext<Application>()

    private lateinit var db: LopDatabase
    private lateinit var transactionRepo: TransactionRepository
    private lateinit var sync: SyncProgressUseCase
    private lateinit var create: CreateTransactionUseCase
    private lateinit var edit: EditTransactionWithScopeUseCase
    private lateinit var observe: ObserveTransactionsUseCase

    private lateinit var defaultTimeZone: TimeZone
    private lateinit var defaultLocale: Locale

    // Identifiants alloués par Room, conservés sous leurs noms symboliques.
    private var a1 = 0L
    private var a2 = 0L
    private var catDep = 0L
    private var catRev = 0L
    private var t1 = 0L
    private var t2 = 0L
    private var g1 = 0L
    private var g2 = 0L
    private var g3 = 0L
    private var d1 = 0L
    private var d2 = 0L
    private var c1 = 0L
    private var txP = 0L
    private var s = 0L
    private var x1 = 0L
    private var x3 = 0L
    private var x4 = 0L

    /** Lignes complètes des cinq tables relevées après la préparation : la référence « avant ». */
    private lateinit var before: Map<String, List<String>>

    @Before
    fun setUp() {
        defaultTimeZone = TimeZone.getDefault()
        defaultLocale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(PARIS))
        Locale.setDefault(Locale.FRANCE)
        db = Room.inMemoryDatabaseBuilder(context, LopDatabase::class.java).allowMainThreadQueries().build()
        transactionRepo = TransactionRepository(db.transactionDao(), db.recurringSeriesDao())
        val goalRepo = GoalRepository(db.goalDao())
        val loanRepo = LoanRepository(db.loanDao())
        sync = SyncProgressUseCase(transactionRepo, goalRepo, loanRepo)
        val save = SaveTransactionUseCase(transactionRepo, sync)
        create = CreateTransactionUseCase(transactionRepo, save)
        edit = EditTransactionWithScopeUseCase(transactionRepo, save, sync)
        observe = ObserveTransactionsUseCase(transactionRepo, AccountRepository(db.accountDao()), CategoryRepository(db.categoryDao()))
    }

    @After
    fun tearDown() {
        if (db.isOpen) db.close()
        TimeZone.setDefault(defaultTimeZone)
        Locale.setDefault(defaultLocale)
    }

    // =============================================================================================
    // I-01 — CA-03, I-1 : création ponctuelle, liens écrits exactement
    // =============================================================================================

    @Test
    fun `I-01 a - given G1 D1 C1, when revenu cree avec G1 et C1, then une transaction G1 C1 listee une fois par G1 et C1`() =
        runTest { assertCreated("I-01 a", 2_500, TransactionType.INCOME, { catRev }, goal = { g1 }, loan = { c1 }) }

    @Test
    fun `I-01 b - given G1 D1 C1, when depense creee avec D1 seul, then aucun objectif et D1, listee par D1 seul`() =
        runTest { assertCreated("I-01 b", 1_200, TransactionType.EXPENSE, { catDep }, goal = { null }, loan = { d1 }) }

    @Test
    fun `I-01 c - given G1 D1 C1, when revenu cree avec G1 seul, then G1 et aucun pret, listee par G1 seul`() =
        runTest { assertCreated("I-01 c", 700, TransactionType.INCOME, { catRev }, goal = { g1 }, loan = { null }) }

    @Test
    fun `I-01 d - given G1 D1 C1, when depense creee sans rattachement, then aucun lien et toutes les listes vides`() =
        runTest { assertCreated("I-01 d", 900, TransactionType.EXPENSE, { catDep }, goal = { null }, loan = { null }) }

    private suspend fun assertCreated(
        label: String,
        amount: Long,
        type: TransactionType,
        category: () -> Long,
        goal: () -> Long?,
        loan: () -> Long?,
    ) {
        seedOneOff(withTxP = false)
        val goalId = goal()
        val loanId = loan()
        val submitted = TransactionEdition(
            title = "QA-5 Saisie", amount = amount, type = type, date = MAR10, accountId = a1,
            categoryId = category(), note = null, status = TransactionStatus.PAID,
            frequency = RecurrenceFrequency.NONE, interval = 1, daysOfWeek = emptySet(), endDate = null,
            maxOccurrences = null, missingDayBehavior = MissingDayBehavior.LAST_VALID_DAY,
            linkedGoalId = goalId, linkedLoanId = loanId, tagIds = emptyList(),
        )

        val newId = read(label, "création") { create(submitted) }

        assertEquals("$label / CA-03 — exactement une transaction de plus, celle créée", listOf(newId), newIds("transactions"))
        assertEquals("$label / CA-03 — aucune série créée", emptyList<Long>(), newIds("recurring_series"))
        val row = rowOf("transactions", newId)
        assertEquals("$label / CA-03 — genre standard ; ligne $row", "STANDARD", row["kind"])
        assertLinks("$label / CA-03, I-1 — liens soumis", goalId, loanId, row)

        val expectedFor = { item: Long?, linked: Long? -> if (item == linked) listOf(newId) else emptyList() }
        assertEquals("$label / CA-03 — liste de G1", expectedFor(g1, goalId), linkedIds(label, "G1", transactionRepo.observeByGoal(g1)))
        assertEquals("$label / CA-03 — liste de D1", expectedFor(d1, loanId), linkedIds(label, "D1", transactionRepo.observeByLoan(d1)))
        assertEquals("$label / CA-03 — liste de C1", expectedFor(c1, loanId), linkedIds(label, "C1", transactionRepo.observeByLoan(c1)))
    }

    // =============================================================================================
    // I-02 — CA-04, I-1 : retirer un seul lien, rien d'autre ne bouge
    // =============================================================================================

    @Test
    fun `I-02 a - given TX-P liee a G1 et D1, when objectif retire en Cette occurrence, then D1 garde et sept champs inchanges`() =
        runTest { assertOneLinkRemoved("I-02 a", goal = { null }, loan = { d1 }) }

    @Test
    fun `I-02 b - given TX-P liee a G1 et D1, when pret retire en Cette occurrence, then G1 garde et sept champs inchanges`() =
        runTest { assertOneLinkRemoved("I-02 b", goal = { g1 }, loan = { null }) }

    private suspend fun assertOneLinkRemoved(label: String, goal: () -> Long?, loan: () -> Long?) {
        seedOneOff(withTxP = true)
        val goalId = goal()
        val loanId = loan()
        val submitted = TransactionEdition(
            title = "QA-5 Courses", amount = 4_321, type = TransactionType.EXPENSE, date = MAR05,
            accountId = a2, categoryId = catDep, note = "note QA", status = TransactionStatus.PAID,
            frequency = RecurrenceFrequency.NONE, interval = 1, daysOfWeek = emptySet(), endDate = null,
            maxOccurrences = null, missingDayBehavior = MissingDayBehavior.LAST_VALID_DAY,
            linkedGoalId = goalId, linkedLoanId = loanId, tagIds = listOf(t1, t2),
        )

        read(label, "édition SINGLE de TX-P") { edit(txP, null, null, submitted, EditScope.SINGLE) }

        assertEquals("$label / CA-04 — toujours une seule transaction ; ids ${ids()}", listOf(txP), ids())
        assertEquals("$label — aucune série créée", emptyList<Long>(), newIds("recurring_series"))
        val row = rowOf("transactions", txP)
        assertLinks("$label / CA-04, I-1 — un seul lien retiré", goalId, loanId, row)
        assertEquals(
            "$label / CA-04 — montant, type, date, compte, statut, catégorie identiques à l'avant ; ligne $row",
            listOf("4321", "EXPENSE", MAR05.toString(), a2.toString(), "PAID", catDep.toString()),
            listOf(row["amount"], row["type"], row["date"], row["accountId"], row["status"], row["categoryId"]),
        )
        assertEquals(
            "$label / CA-04 — tags exactement T1 et T2",
            listOf(t1, t2).sorted(),
            longs("SELECT tagId FROM transaction_tags WHERE transactionId = $txP ORDER BY tagId"),
        )
    }

    // =============================================================================================
    // I-03 — CA-06 : « Cette occurrence » ne touche ni la série ni les autres exceptions
    // =============================================================================================

    @Test
    fun `I-03 - given OCC-0315 virtuelle, when G1 remplace par G3 en Cette occurrence et payee, then une exception G3 D1 et S X1 X3 X4 intacts`() =
        runTest {
            val label = "I-03"
            val occ = seedSeriesAndCaptureOccurrence(label)

            read(label, "édition SINGLE") {
                edit(occ, s, MAR15, occurrenceEdition(goalId = g3, loanId = d1, status = TransactionStatus.PAID, frequency = RecurrenceFrequency.NONE), EditScope.SINGLE)
            }

            val added = newIds("transactions")
            assertEquals("$label / CA-06 — exactement une transaction de plus ; nouvelles $added", 1, added.size)
            assertEquals("$label / CA-06 — aucune série de plus", emptyList<Long>(), newIds("recurring_series"))
            val exception = rowOf("transactions", added.single())
            assertEquals(
                "$label / CA-06 — l'exception du 15 mars, payée, rattachée à S ; ligne $exception",
                listOf(s.toString(), MAR15.toString(), MAR15.toString(), "PAID", "1"),
                listOf(exception["seriesId"], exception["seriesDate"], exception["date"], exception["status"], exception["isException"]),
            )
            assertLinks("$label / CA-06 — liens de l'exception", g3, d1, exception)
            assertUntouched(label, "recurring_series", s)
            assertUntouched(label, "transactions", x1, x3, x4)
            assertProgress(label, goals = mapOf("G3" to (g3 to 25_000L), "G1" to (g1 to 4_600L)), loans = mapOf("D1" to (d1 to 16_100L)))
        }

    // =============================================================================================
    // I-04 — CA-06, I-1 : « suivantes », seul le lien modifié est propagé
    // =============================================================================================

    @Test
    fun `I-04 - given OCC-0315, when G1 remplace par G3 en Cette occurrence et les suivantes, then G3 propage seul et X4 garde aucun pret`() =
        runTest {
            val label = "I-04"
            val occ = seedSeriesAndCaptureOccurrence(label)

            read(label, "édition FUTURE") {
                edit(occ, s, MAR15, occurrenceEdition(goalId = g3, loanId = d1, status = TransactionStatus.PLANNED, frequency = RecurrenceFrequency.MONTHLY), EditScope.FUTURE)
            }

            val newSeries = newIds("recurring_series")
            assertEquals("$label / CA-06 — exactement une série de plus ; nouvelles $newSeries", 1, newSeries.size)
            val added = newIds("transactions")
            assertEquals("$label / CA-06 — exactement une transaction de plus ; nouvelles $added", 1, added.size)
            assertLinks("$label / CA-06 — S garde ses liens", g1, d1, rowOf("recurring_series", s))
            assertLinks("$label / CA-06 — la nouvelle série porte G3 et D1", g3, d1, rowOf("recurring_series", newSeries.single()))
            val occurrence = rowOf("transactions", added.single())
            assertEquals("$label / CA-06 — l'occurrence du 15 mars est sur la nouvelle série ; ligne $occurrence", listOf(newSeries.single().toString(), MAR15.toString()), listOf(occurrence["seriesId"], occurrence["date"]))
            assertLinks("$label / CA-06 — l'occurrence du 15 mars porte G3 et D1", g3, d1, occurrence)
            assertException("$label / I-1 — X3 passe à G3, garde D1", x3, g3, d1, 5_000, APR15)
            assertException("$label / I-1 — X4 passe à G3, garde « aucun prêt »", x4, g3, null, 5_400, MAY15)
            assertUntouched(label, "transactions", x1)
            assertProgress(
                label,
                goals = mapOf("G1" to (g1 to 10_000L), "G2" to (g2 to 14_900L), "G3" to (g3 to 19_600L)),
                loans = mapOf("D1" to (d1 to 11_100L), "D2" to (d2 to 2_000L)),
            )
        }

    // =============================================================================================
    // I-05 — CA-06, P-4 : le lien propre à l'occurrence ouverte compte comme modifié
    // =============================================================================================

    @Test
    fun `I-05 - given X3 liee a G2, when Cette occurrence et les suivantes sans toucher aux liens, then nouvelle serie G2 D1 et X4 passe a G2`() =
        runTest {
            val label = "I-05"
            seedSeries(label)

            read(label, "édition FUTURE de X3") {
                edit(x3, s, APR15, x3Edition(), EditScope.FUTURE)
            }

            val newSeries = newIds("recurring_series")
            assertEquals("$label / P-4 — exactement une série de plus ; nouvelles $newSeries", 1, newSeries.size)
            assertEquals("$label / P-4 — aucune transaction de plus ; ids ${ids()}", emptyList<Long>(), newIds("transactions"))
            assertLinks("$label / P-4 — la nouvelle série porte G2 et D1", g2, d1, rowOf("recurring_series", newSeries.single()))
            assertException("$label / P-4 — X3 garde G2 et D1", x3, g2, d1, 5_000, APR15)
            assertException("$label / P-4 — X4 passe à G2, garde « aucun prêt »", x4, g2, null, 5_400, MAY15)
            assertLinks("$label / CA-06 — S garde G1 et D1", g1, d1, rowOf("recurring_series", s))
            assertUntouched(label, "transactions", x1)
            assertProgress(label, goals = mapOf("G1" to (g1 to 10_000L), "G2" to (g2 to 4_500L)), loans = mapOf("D1" to (d1 to 11_100L)))
        }

    // =============================================================================================
    // I-06 — CA-06, CA-03, I-1 : « Toutes », le prêt modifié est propagé, l'objectif personnalisé reste
    // =============================================================================================

    @Test
    fun `I-06 - given OCC-0315, when D1 remplace par D2 en Toutes les occurrences, then S et les exceptions passent a D2 et gardent leur objectif`() =
        runTest {
            val label = "I-06"
            val occ = seedSeriesAndCaptureOccurrence(label)

            read(label, "édition ALL") { edit(occ, s, MAR15, seriesEdition(loanId = d2), EditScope.ALL) }

            assertEquals("$label / CA-06 — aucune transaction de plus ; ids ${ids()}", emptyList<Long>(), newIds("transactions"))
            assertEquals("$label / CA-06 — aucune série de plus", emptyList<Long>(), newIds("recurring_series"))
            assertLinks("$label / CA-06 — S porte G1 et D2", g1, d2, rowOf("recurring_series", s))
            assertException("$label / I-1 — X1 passe à D2, garde G2", x1, g2, d2, 5_100, FEB15)
            assertException("$label / I-1 — X3 passe à D2, garde G2", x3, g2, d2, 5_000, APR15)
            assertException("$label / I-1 — X4 passe à D2, garde G1", x4, g1, d2, 5_400, MAY15)
            assertProgress(
                label,
                goals = mapOf("G1" to (g1 to 4_600L), "G2" to (g2 to 9_900L)),
                loans = mapOf("D1" to (d1 to 1_000L), "D2" to (d2 to 17_500L)),
            )
        }

    // =============================================================================================
    // Éditions envoyées par le formulaire (TC-166)
    // =============================================================================================

    /** SINGLE et FUTURE sur OCC-0315 : valeurs de l'occurrence ; FUTURE y ajoute la règle de S. */
    private fun occurrenceEdition(goalId: Long, loanId: Long, status: TransactionStatus, frequency: RecurrenceFrequency) =
        TransactionEdition(
            title = SERIES_TITLE, amount = 5_000, type = TransactionType.EXPENSE, date = MAR15, accountId = a1,
            categoryId = catDep, note = null, status = status, frequency = frequency, interval = 1,
            daysOfWeek = emptySet(), endDate = null, maxOccurrences = null,
            missingDayBehavior = MissingDayBehavior.LAST_VALID_DAY,
            linkedGoalId = goalId, linkedLoanId = loanId, tagIds = emptyList(),
        )

    /** FUTURE sur X3 : valeurs de X3, règle de S, liens non touchés. */
    private fun x3Edition() = TransactionEdition(
        title = SERIES_TITLE, amount = 5_000, type = TransactionType.EXPENSE, date = APR15, accountId = a1,
        categoryId = catDep, note = null, status = TransactionStatus.PAID, frequency = RecurrenceFrequency.MONTHLY,
        interval = 1, daysOfWeek = emptySet(), endDate = null, maxOccurrences = null,
        missingDayBehavior = MissingDayBehavior.LAST_VALID_DAY,
        linkedGoalId = g2, linkedLoanId = d1, tagIds = emptyList(),
    )

    /** ALL : valeurs de base de S, date de début comprise ; statut de l'occurrence ouverte. */
    private fun seriesEdition(loanId: Long) = TransactionEdition(
        title = SERIES_TITLE, amount = 5_000, type = TransactionType.EXPENSE, date = JAN15, accountId = a1,
        categoryId = catDep, note = null, status = TransactionStatus.PLANNED, frequency = RecurrenceFrequency.MONTHLY,
        interval = 1, daysOfWeek = emptySet(), endDate = null, maxOccurrences = null,
        missingDayBehavior = MissingDayBehavior.LAST_VALID_DAY,
        linkedGoalId = g1, linkedLoanId = loanId, tagIds = emptyList(),
    )

    // =============================================================================================
    // Préparation — vrais DAO et repositories, puis un recalcul, puis la référence « avant »
    // =============================================================================================

    private suspend fun seedOneOff(withTxP: Boolean) {
        assertForeignKeysOn()
        a1 = account("QA-5 Courant")
        if (withTxP) a2 = account("QA-5 Livret")
        catDep = category("QA-5 Courses", TransactionType.EXPENSE)
        catRev = category("QA-5 Salaire", TransactionType.INCOME)
        if (withTxP) {
            t1 = db.tagDao().upsert(TagEntity(name = "qa5-a", colorArgb = 0))
            t2 = db.tagDao().upsert(TagEntity(name = "qa5-b", colorArgb = 0))
        }
        g1 = goal("QA-5 Épargne série", 10_000)
        d1 = loan("QA-5 Dette série", LoanDirection.BORROWED, 1_000)
        c1 = loan("QA-5 Prêt à Sam", LoanDirection.LENT, 3_000)
        assertDiscriminant()
        if (withTxP) {
            txP = insertTx(
                TransactionEntity(
                    title = "QA-5 Courses", amount = 4_321, type = TransactionType.EXPENSE,
                    status = TransactionStatus.PAID, date = MAR05, accountId = a2, categoryId = catDep,
                    note = "note QA", paidAt = MAR05, linkedGoalId = g1, linkedLoanId = d1,
                ),
                t1, t2,
            )
        }
        sync.recalculateAll()
        before = snapshot()
    }

    private suspend fun seedSeries(label: String) {
        assertForeignKeysOn()
        a1 = account("QA-5 Courant")
        catDep = category("QA-5 Courses", TransactionType.EXPENSE)
        g1 = goal("QA-5 Épargne série", 10_000)
        g2 = goal("QA-5 Perso", 20_000)
        g3 = goal("QA-5 Nouveau", 30_000)
        d1 = loan("QA-5 Dette série", LoanDirection.BORROWED, 1_000)
        d2 = loan("QA-5 Dette nouvelle", LoanDirection.BORROWED, 2_000)
        assertDiscriminant()
        s = db.recurringSeriesDao().upsertSeries(
            RecurringSeriesEntity(
                title = SERIES_TITLE, amount = 5_000, type = TransactionType.EXPENSE, categoryId = catDep,
                accountId = a1, frequency = RecurrenceFrequency.MONTHLY, interval = 1, startDate = JAN15,
                linkedGoalId = g1, linkedLoanId = d1,
            ),
        )
        x1 = insertTx(exception(FEB15, 5_100, g2, d1))
        x3 = insertTx(exception(APR15, 5_000, g2, d1))
        x4 = insertTx(exception(MAY15, 5_400, g1, null))
        sync.recalculateAll()
        before = snapshot()
        assertProgress(
            "$label / montage — avancements de référence",
            goals = mapOf("G1" to (g1 to 4_600L), "G2" to (g2 to 9_900L), "G3" to (g3 to 30_000L)),
            loans = mapOf("D1" to (d1 to 11_100L), "D2" to (d2 to 2_000L)),
        )
    }

    /** Identifiant virtuel d'OCC-0315, lu par le vrai `ObserveTransactionsUseCase` : jamais calculé ici. */
    private suspend fun seedSeriesAndCaptureOccurrence(label: String): Long {
        seedSeries(label)
        val march = awaitFirst(label, "liste de mars", observe(startOf(2026, 3, 1), endOf(2026, 3, 31)))
        val matches = march.filter { it.transaction.seriesId == s && it.transaction.seriesDate == MAR15 }
        assertEquals("$label / montage — une seule occurrence de S au 15 mars ; observé ${march.map { it.transaction.id }}", 1, matches.size)
        val id = matches.single().transaction.id
        assertTrue("$label / montage — OCC-0315 est virtuelle ; observé $id", id < 0)
        assertEquals("$label / montage — la lecture n'a rien écrit", before, snapshot())
        return id
    }

    private fun exception(slot: Long, amount: Long, goalId: Long?, loanId: Long?) = TransactionEntity(
        title = SERIES_TITLE, amount = amount, type = TransactionType.EXPENSE, status = TransactionStatus.PAID,
        date = slot, accountId = a1, categoryId = catDep, paidAt = slot, seriesId = s, seriesDate = slot,
        isException = true, linkedGoalId = goalId, linkedLoanId = loanId,
    )

    private suspend fun account(name: String): Long = db.accountDao().upsert(
        AccountEntity(name = name, type = AccountType.CHECKING, initialBalance = 100_000, colorArgb = 0, icon = "wallet"),
    )

    private suspend fun category(name: String, type: TransactionType): Long = db.categoryDao().upsert(
        CategoryEntity(name = name, type = type, colorArgb = 0, icon = "category", parentCategoryId = null),
    )

    private suspend fun goal(name: String, startCents: Long): Long = GoalRepository(db.goalDao()).create(
        GoalEntity(name = name, targetAmountCents = 100_000, startingBalanceCents = startCents, colorArgb = 0, icon = "savings"),
    )

    private suspend fun loan(name: String, direction: LoanDirection, startCents: Long): Long = LoanRepository(db.loanDao()).create(
        LoanEntity(name = name, direction = direction, totalAmountCents = 100_000, startingBalanceCents = startCents, colorArgb = 0, icon = "debt"),
    )

    private suspend fun insertTx(row: TransactionEntity, vararg tagIds: Long): Long = db.withTransaction {
        val id = db.transactionDao().upsert(row)
        tagIds.forEach { db.transactionDao().addTagCrossRef(TransactionTagCrossRef(id, it)) }
        id
    }

    private fun assertForeignKeysOn() =
        assertEquals("montage — clés étrangères du schéma actives", listOf(1L), longs("PRAGMA foreign_keys"))

    /** Sans cette égalité, une inversion objectif / prêt passerait inaperçue : échec de montage. */
    private fun assertDiscriminant() =
        assertEquals("montage — G1 et D1 partagent le même identifiant", g1, d1)

    // =============================================================================================
    // Oracles
    // =============================================================================================

    private fun assertLinks(label: String, goalId: Long?, loanId: Long?, row: Map<String, String?>) =
        assertEquals("$label — (objectif, prêt) ; ligne $row", goalId?.toString() to loanId?.toString(), row["linkedGoalId"] to row["linkedLoanId"])

    /** Exception réécrite : ses deux liens, et son montant et sa date jamais touchés par la propagation. */
    private fun assertException(label: String, id: Long, goalId: Long?, loanId: Long?, amount: Long, date: Long) {
        val row = rowOf("transactions", id)
        assertLinks(label, goalId, loanId, row)
        assertEquals("$label — montant et date conservés ; ligne $row", listOf(amount.toString(), date.toString()), listOf(row["amount"], row["date"]))
    }

    /** Ligne témoin : identique colonne à colonne au snapshot « avant ». */
    private fun assertUntouched(label: String, table: String, vararg ids: Long) = ids.forEach { id ->
        val previous = before.getValue(table).filter { it.startsWith("$id|") }
        assertEquals("$label / CA-06 — $table $id intacte", previous, rawRows("SELECT * FROM $table WHERE id = $id"))
    }

    /** Avancement relu en base, égal à la valeur écrite en clair : jamais recalculé par le test. */
    private fun assertProgress(label: String, goals: Map<String, Pair<Long, Long>>, loans: Map<String, Pair<Long, Long>>) {
        val expected = goals.mapValues { it.value.second } + loans.mapValues { it.value.second }
        val actual = goals.mapValues { (_, v) -> longs("SELECT savedAmountCents FROM goals WHERE id = ${v.first}").single() } +
            loans.mapValues { (_, v) -> longs("SELECT repaidAmountCents FROM loans WHERE id = ${v.first}").single() }
        assertEquals("$label / CA-03 — avancements en centimes", expected, actual)
    }

    // =============================================================================================
    // Lectures SQL brutes, lignes supprimées comprises
    // =============================================================================================

    private fun snapshot(): Map<String, List<String>> =
        listOf("transactions", "transaction_tags", "recurring_series", "goals", "loans")
            .associateWith { rawRows("SELECT * FROM $it ORDER BY 1, 2") }

    private fun newIds(table: String): List<Long> {
        val previous = before.getValue(table).map { it.substringBefore('|').toLong() }.toSet()
        return longs("SELECT id FROM $table ORDER BY id").filter { it !in previous }
    }

    private fun ids(): List<Long> = longs("SELECT id FROM transactions ORDER BY id")

    private fun longs(sql: String): List<Long> = buildList {
        db.query(sql, emptyArray<Any?>()).use { c -> while (c.moveToNext()) add(c.getLong(0)) }
    }

    private fun rawRows(sql: String): List<String> = buildList {
        db.query(sql, emptyArray<Any?>()).use { c ->
            while (c.moveToNext()) add((0 until c.columnCount).joinToString("|") { if (c.isNull(it)) "null" else c.getString(it) })
        }
    }

    private fun rowOf(table: String, id: Long): Map<String, String?> =
        db.query("SELECT * FROM $table WHERE id = $id", emptyArray<Any?>()).use { c ->
            assertTrue("ligne $table $id introuvable", c.moveToFirst())
            (0 until c.columnCount).associate { c.getColumnName(it) to if (c.isNull(it)) null else c.getString(it) }
        }

    // =============================================================================================
    // Attentes bornées en temps réel : Room émet depuis ses propres fils
    // =============================================================================================

    private class Got<T>(val value: T)

    /** [block] borné à [TIMEOUT] réel : un `withTimeout` dans `runTest` compterait du temps virtuel. */
    private suspend fun <T> read(label: String, expectation: String, block: suspend () -> T): T {
        val got = withContext(Dispatchers.Default) { withTimeoutOrNull(TIMEOUT) { Got(block()) } }
            ?: throw AssertionError("$label — aucune réponse en $TIMEOUT ; attendu : $expectation")
        return got.value
    }

    private suspend fun <T> awaitFirst(label: String, expectation: String, flow: Flow<T>): T = read(label, expectation) { flow.first() }

    private suspend fun linkedIds(label: String, item: String, flow: Flow<List<TransactionWithRelations>>): List<Long> =
        awaitFirst(label, "transactions liées de $item", flow).map { it.transaction.id }

    private companion object {
        val PARIS: ZoneId = ZoneId.of("Europe/Paris")
        val TIMEOUT = 5.seconds
        const val SERIES_TITLE = "QA-5 Loyer"

        fun at(year: Int, month: Int, day: Int): Long =
            LocalDateTime.of(year, month, day, 9, 0).atZone(PARIS).toInstant().toEpochMilli()

        fun startOf(year: Int, month: Int, day: Int): Long =
            LocalDate.of(year, month, day).atStartOfDay(PARIS).toInstant().toEpochMilli()

        fun endOf(year: Int, month: Int, day: Int): Long =
            LocalDate.of(year, month, day).atTime(23, 59, 59, 999_000_000).atZone(PARIS).toInstant().toEpochMilli()

        // Instants à 09:00 Paris : le 15 de chaque mois est un slot exact de S.
        val JAN15 = at(2026, 1, 15)
        val FEB15 = at(2026, 2, 15)
        val MAR05 = at(2026, 3, 5)
        val MAR10 = at(2026, 3, 10)
        val MAR15 = at(2026, 3, 15)
        val APR15 = at(2026, 4, 15)
        val MAY15 = at(2026, 5, 15)
    }
}

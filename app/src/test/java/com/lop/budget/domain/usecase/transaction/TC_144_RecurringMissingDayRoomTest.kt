package com.lop.budget.domain.usecase.transaction

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.lop.budget.data.local.LopDatabase
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.CategoryEntity
import com.lop.budget.data.local.entity.RecurringSeriesEntity
import com.lop.budget.data.local.entity.SeriesTagCrossRef
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
import com.lop.budget.domain.model.MissingDay
import com.lop.budget.domain.model.MissingDayBehavior
import com.lop.budget.domain.model.MissingDayBehavior.LAST_VALID_DAY
import com.lop.budget.domain.model.MissingDayBehavior.SKIP_PERIOD
import com.lop.budget.domain.model.RecurrenceFrequency
import com.lop.budget.domain.model.TransactionEdition
import com.lop.budget.domain.model.TransactionKind
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.domain.usecase.SyncProgressUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone
import kotlin.time.Duration.Companion.seconds

/**
 * TC-144 — Jour absent : sauvegarde des portées et lectures sans écriture (LOP-88).
 *
 * Fiche : https://app.notion.com/p/e5a737d43e004f6ca586f0ce30090a79
 * US : https://app.notion.com/p/6233b396328445f4924475e394af6e79 — CA-01, CA-04, CA-05, CA-06,
 * CA-07, CA-09, CA-11 ; I-1, I-2, P-1, P-4. Code lu au commit d7d7c49.
 *
 * ## Niveau
 * Intégration hors UI sur Room réel (Robolectric SDK 33, `Application` neutre). Aucun mock, fake
 * ni spy sur la chaîne.
 *
 * ## Chaîne exercée
 * `CreateTransactionUseCase` / `EditTransactionWithScopeUseCase` (écriture et prélecture)
 *   → `SaveTransactionUseCase` (+ `SyncProgressUseCase`, repositories Goal/Loan réels)
 *   → `TransactionRepository` → DAO → `LopDatabase` ;
 * `ObserveTransactionsUseCase` / `ObserveTransactionDetailUseCase` (lecture)
 *   → `TransactionRepository`, `AccountRepository`, `CategoryRepository` → DAO,
 * avec le vrai `RecurrenceEngine`. Chaque accès est exercé séparément dans une même base réelle.
 *
 * ## Correspondance cas → CA / invariant → fonction de production
 * ```
 * R-01   CA-06            CreateTransactionUseCase.invoke, base fichier rouverte
 * R-02   CA-01/06         CreateTransactionUseCase.firstMissingDay, EditTransactionWithScopeUseCase.firstMissingDay (FUTURE, SINGLE)
 * R-03   CA-06/11, P-4    EditTransactionWithScopeUseCase.invoke FUTURE, date inchangée → ObserveTransactionsUseCase
 * R-04   CA-11            idem, date modifiée au 2 mars ; prélecture
 * R-05   CA-06/11         EditTransactionWithScopeUseCase.invoke ALL sur SC → ObserveTransactionsUseCase
 * R-06   CA-06            EditTransactionWithScopeUseCase.invoke SINGLE, choix SKIP transmis
 * R-07   CA-07, I-2       ALL vers SKIP sur S31 avec exceptions → ObserveTransactionsUseCase, ObserveTransactionDetailUseCase.getById
 * R-08   CA-07            ObserveTransactionsUseCase : observation ouverte (a) / neuve (b)
 * R-09   CA-07            ObserveTransactionDetailUseCase : observation ouverte (a) / getById (b)
 * R-10   CA-05/07/09, P-1 ObserveTransactionsUseCase : sauter + maxOccurrences=4
 * ```
 * Chaque groupe de lectures se termine par la comparaison du snapshot des sept tables, lignes
 * supprimées comprises : une lecture n'écrit rien.
 *
 * ## Décision appliquée
 * Le 5 octobre 2026, l'utilisateur a tranché : après « cette occurrence et les suivantes », les
 * occurrences de la nouvelle série portent le tag de la série d'origine (R-03, R-04).
 *
 * ## Hypothèses levées
 * - Montage validé le 5 octobre 2026 par `RecurringOccurrencesContractRoomTest`.
 * - Les éditions reproduisent ce que le formulaire enverrait : valeurs de l'occurrence et règle de
 *   la série (FUTURE), valeurs de base de la série (ALL), fréquence NONE (SINGLE).
 * - R-05 édite SC depuis son occurrence de départ (28 février). Les calendriers sont lus sur des
 *   fenêtres de mois entiers, déclarées dans chaque cas.
 * - R-02 SINGLE transmet une règle mensuelle qui rencontrerait un jour absent : sans la garde
 *   SINGLE, la prélecture rendrait une valeur, et le cas le verrait.
 * - Les IDs virtuels sont capturés par une lecture de liste, jamais recalculés.
 *
 * ## ANO connues
 * - **LOP-192** — https://app.notion.com/p/3f050f34a8c581898cc9e96a2fa27433
 *   « Cette occurrence et les suivantes » écrit la nouvelle série sans ses tags
 *   (`EditTransactionWithScopeUseCase.editFuture` → `upsertSeries`). R-03 ×2 et R-04 sont **rouges
 *   attendus** tant qu'elle n'est pas traitée : seul le champ `tags` du 2e élément diffère,
 *   dates, ordre et autres champs sont conformes. Cause antérieure à LOP-88 (découpage de LOP-52).
 *
 * ## Résultats (5 octobre 2026)
 * 15 verts, 3 rouges attendus (LOP-192) sur 18.
 *
 * ## Preuves de sensibilité
 * Une mutation de production à la fois, retirée aussitôt. Les rouges de R-03/R-04 déjà dus à
 * LOP-192 ne sont pas recomptés ; une mutation qui les touche est citée si le message change.
 * ```
 * FUTURE sans ancrage reporté                     → R-02 FUTURE, R-03 ×2 (ligne de série)
 * ALL perd l'ancrage reporté                      → R-05 date inchangée
 * ALL ignore le choix soumis                      → R-05 date inchangée, R-07
 * ALL réécrit les exceptions                      → R-07 (colonnes de E-FEV)
 * Liste qui ignore les lignes supprimées          → R-07 (le 31 mars réapparaît)
 * Détail qui ignore le choix                      → R-09a (attente expirée, dernier état joint), R-09b
 * Création toujours en « dernier jour »           → R-01 sauter, R-03 sauter (ligne de série)
 * Prélecture SINGLE non nulle                     → R-02 SINGLE
 * Coupure FUTURE au pivot au lieu de pivot − 1    → R-03 ×2, R-04 (ancienne série)
 * Liste qui ignore le choix                       → R-05 date inchangée, R-07, R-08a, R-08b, R-10 ×2
 * Liste figée sur la 1re liste de séries          → R-08a seul ; R-08b reste vert (lecture neuve)
 * Détail figé sur la 1re liste de séries          → R-09a
 * SINGLE écrit le choix sur la série              → R-06
 * Choix relu toujours « dernier jour »            → R-01 sauter, R-05, R-07, R-08a/b, R-09a/b, R-10 ×2
 * ```
 *
 * ## Hors périmètre
 * Migration et ancien schéma (TC-145, à la corbeille), état de formulaire et dialogues (TC-143,
 * TC-141), notifications, actions rapides, propagation de champs ordinaires aux exceptions.
 *
 * ## Exécution
 * ```
 * ./gradlew :app:testDebugUnitTest --tests "*RecurringMissingDayRoomTest"
 * ./gradlew :app:testDebugUnitTest
 * ```
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class RecurringMissingDayRoomTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext<Application>()

    private lateinit var db: LopDatabase
    private lateinit var transactionRepo: TransactionRepository
    private lateinit var create: CreateTransactionUseCase
    private lateinit var edit: EditTransactionWithScopeUseCase
    private lateinit var observe: ObserveTransactionsUseCase
    private lateinit var detail: ObserveTransactionDetailUseCase

    private lateinit var defaultTimeZone: TimeZone
    private lateinit var defaultLocale: Locale
    private var fileDatabaseName: String? = null

    // Parents, IDs alloués par Room.
    private lateinit var accA: AccountEntity
    private lateinit var catC: CategoryEntity
    private lateinit var tagT: TagEntity

    @Before
    fun setUp() {
        defaultTimeZone = TimeZone.getDefault()
        defaultLocale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(PARIS))
        Locale.setDefault(Locale.FRANCE)
        wire(
            Room.inMemoryDatabaseBuilder(context, LopDatabase::class.java)
                .allowMainThreadQueries()
                .build(),
        )
    }

    @After
    fun tearDown() {
        if (db.isOpen) db.close()
        fileDatabaseName?.let { context.deleteDatabase(it) }
        TimeZone.setDefault(defaultTimeZone)
        Locale.setDefault(defaultLocale)
    }

    private fun wire(database: LopDatabase) {
        db = database
        transactionRepo = TransactionRepository(db.transactionDao(), db.recurringSeriesDao())
        val accountRepo = AccountRepository(db.accountDao())
        val categoryRepo = CategoryRepository(db.categoryDao())
        val save = SaveTransactionUseCase(
            transactionRepo,
            SyncProgressUseCase(transactionRepo, GoalRepository(db.goalDao()), LoanRepository(db.loanDao())),
        )
        create = CreateTransactionUseCase(transactionRepo, save)
        edit = EditTransactionWithScopeUseCase(transactionRepo, save)
        observe = ObserveTransactionsUseCase(transactionRepo, accountRepo, categoryRepo)
        detail = ObserveTransactionDetailUseCase(transactionRepo, accountRepo, categoryRepo)
    }

    private fun fileDatabase(name: String): LopDatabase =
        Room.databaseBuilder(context, LopDatabase::class.java, name)
            .allowMainThreadQueries()
            .build()

    // =============================================================================================
    // R-01 — CA-06 : le choix est persisté avec la série et relu après réouverture
    // =============================================================================================

    @Test
    fun `R-01 dernier jour - given S31 creee avec tag T, when base fermee puis rouverte, then une serie LAST_VALID_DAY, un lien de tag, zero transaction`() =
        runTest { assertRuleCreatedAndReopened("R-01 dernier jour", LAST_VALID_DAY) }

    @Test
    fun `R-01 sauter - given S31 creee avec tag T, when base fermee puis rouverte, then une serie SKIP_PERIOD, un lien de tag, zero transaction`() =
        runTest { assertRuleCreatedAndReopened("R-01 sauter", SKIP_PERIOD) }

    private suspend fun assertRuleCreatedAndReopened(label: String, choice: MissingDayBehavior) {
        db.close()
        val name = "tc144_r01_${choice.name.lowercase()}.db"
        context.deleteDatabase(name)
        fileDatabaseName = name
        wire(fileDatabase(name))
        seedParents()

        read(label, "création de S31") { create(edition(date = JAN31, behavior = choice)) }

        val seriesId = assertSingleCreatedRule("$label / CA-06 — avant fermeture", choice)
        val beforeClose = snapshot()

        db.close()
        wire(fileDatabase(name))

        assertEquals(
            "$label / CA-06 — après réouverture, la série relue est celle écrite",
            expectedCreatedRule(seriesId, choice),
            read(label, "série relue") { transactionRepo.getSeriesById(seriesId) },
        )
        assertEquals("$label / CA-06 — après réouverture, les 7 tables sont identiques", beforeClose, snapshot())
    }

    private suspend fun assertSingleCreatedRule(label: String, choice: MissingDayBehavior): Long {
        val seriesIds = longs("SELECT id FROM recurring_series ORDER BY id")
        assertEquals("$label — exactement une série ; observé $seriesIds", 1, seriesIds.size)
        val seriesId = seriesIds.single()
        assertEquals(
            "$label — série complète, choix soumis et ancrage reporté null",
            expectedCreatedRule(seriesId, choice),
            read(label, "série écrite") { transactionRepo.getSeriesById(seriesId) },
        )
        assertEquals(
            "$label — exactement un lien série-tag, vers T",
            listOf("$seriesId|${tagT.id}"),
            rawRows("SELECT seriesId, tagId FROM series_tags ORDER BY seriesId, tagId"),
        )
        assertEquals("$label — aucune transaction écrite", 0L, count("transactions"))
        assertEquals("$label — aucun lien transaction-tag écrit", 0L, count("transaction_tags"))
        return seriesId
    }

    private fun expectedCreatedRule(seriesId: Long, choice: MissingDayBehavior) =
        s31Row(behavior = choice).copy(id = seriesId)

    // =============================================================================================
    // R-02 — CA-01 / CA-06 : prélectures sans écriture
    // =============================================================================================

    @Test
    fun `R-02 creation - given regle S31, when prelecture Create, then ancrage 31 et exemple 28 fevrier, rien ecrit`() =
        runTest {
            seedParents()
            insertSeries(s31Row(behavior = LAST_VALID_DAY), tagT)
            val reference = snapshot()

            val missing = read("R-02 création", "prélecture") {
                create.firstMissingDay(edition(date = JAN31, behavior = LAST_VALID_DAY))
            }

            assertEquals("R-02 création / CA-01", MissingDay(anchorDay = 31, date = FEB28), missing)
            assertNoWrite("R-02 création", reference)
        }

    @Test
    fun `R-02 FUTURE - given virtuel du 28 fevrier de S31, when prelecture FUTURE date inchangee, then ancrage 31 et exemple 30 avril, rien ecrit`() =
        runTest {
            seedParents()
            val s31 = insertSeries(s31Row(behavior = LAST_VALID_DAY), tagT)
            val feb28 = captureVirtual("R-02 FUTURE", s31, FEB28)
            val reference = snapshot()

            val missing = read("R-02 FUTURE", "prélecture") {
                edit.firstMissingDay(feb28, s31, FEB28, edition(date = FEB28, behavior = LAST_VALID_DAY), EditScope.FUTURE)
            }

            assertEquals(
                "R-02 FUTURE / CA-01, P-4 — la nouvelle série garde le 31 : 31 mars produit, 30 avril rabattu",
                MissingDay(anchorDay = 31, date = APR30),
                missing,
            )
            assertNoWrite("R-02 FUTURE", reference)
        }

    @Test
    fun `R-02 SINGLE - given virtuel du 31 mars de S31, when prelecture SINGLE, then null, rien ecrit`() =
        runTest {
            seedParents()
            val s31 = insertSeries(s31Row(behavior = LAST_VALID_DAY), tagT)
            val mar31 = captureVirtual("R-02 SINGLE", s31, MAR31)
            val reference = snapshot()

            val missing = read("R-02 SINGLE", "prélecture") {
                edit.firstMissingDay(mar31, s31, MAR31, edition(date = MAR31, behavior = LAST_VALID_DAY), EditScope.SINGLE)
            }

            assertNull("R-02 SINGLE / CA-06 — une occurrence de série ne touche jamais la règle ; observé $missing", missing)
            assertNoWrite("R-02 SINGLE", reference)
        }

    // =============================================================================================
    // R-03 / R-04 — CA-06 / CA-11 : FUTURE sur une occurrence rabattue
    // =============================================================================================

    @Test
    fun `R-03 dernier jour - given S31, when FUTURE sur le 28 fevrier date inchangee, then nouvelle serie ancree au 31, calendrier 28 fevrier, 31 mars, 30 avril, 31 mai`() =
        runTest { assertFutureOnClampedOccurrence("R-03 dernier jour", LAST_VALID_DAY, listOf(MAR31, APR30, MAY31)) }

    @Test
    fun `R-03 sauter - given S31, when FUTURE sur le 28 fevrier date inchangee, then nouvelle serie ancree au 31, calendrier 28 fevrier, 31 mars, 31 mai`() =
        runTest { assertFutureOnClampedOccurrence("R-03 sauter", SKIP_PERIOD, listOf(MAR31, MAY31)) }

    private suspend fun assertFutureOnClampedOccurrence(
        label: String,
        choice: MissingDayBehavior,
        expectedVirtualSlots: List<Long>,
    ) {
        seedParents()
        val s31 = insertSeries(s31Row(behavior = LAST_VALID_DAY), tagT)
        val feb28 = captureVirtual(label, s31, FEB28)
        val oldSeries = read(label, "S31 avant édition") { transactionRepo.getSeriesById(s31) }!!

        read(label, "édition FUTURE") {
            edit(feb28, s31, FEB28, edition(date = FEB28, behavior = choice), EditScope.FUTURE)
        }

        val newSeries = assertSplit(label, s31, oldSeries)
        assertEquals(
            "$label / CA-06, CA-11, P-4 — nouvelle série : début 28 février, ancrage 31, choix confirmé",
            s31Row(behavior = choice).copy(id = newSeries, startDate = FEB28, anchorDayOfMonth = 31),
            read(label, "nouvelle série") { transactionRepo.getSeriesById(newSeries) },
        )
        val edited = assertSingleException(label, newSeries, slot = FEB28)

        val from = startOf(2026, 2, 1)
        val to = endOf(2026, 5, 31)
        assertCalendar(
            "$label / CA-04/05, CA-11 — nouvelle série, février → mai",
            from,
            to,
            listOf(physical(edited, newSeries, slot = FEB28, date = FEB28)) +
                expectedVirtualSlots.map { virtual(newSeries, it) },
        )
    }

    @Test
    fun `R-04 - given S31, when FUTURE sur le 28 fevrier avec date saisie 2 mars, then nouvelle serie au 2 sans ancrage, 2 mars, 2 avril, 2 mai`() =
        runTest {
            val label = "R-04"
            seedParents()
            val s31 = insertSeries(s31Row(behavior = LAST_VALID_DAY), tagT)
            val feb28 = captureVirtual(label, s31, FEB28)
            val oldSeries = read(label, "S31 avant édition") { transactionRepo.getSeriesById(s31) }!!
            val submitted = edition(date = MAR02, behavior = LAST_VALID_DAY)
            val reference = snapshot()

            val missing = read(label, "prélecture") { edit.firstMissingDay(feb28, s31, FEB28, submitted, EditScope.FUTURE) }
            assertNull("$label / CA-01, CA-11 — une série au 2 ne rencontre aucun jour absent ; observé $missing", missing)
            assertNoWrite("$label prélecture", reference)

            read(label, "édition FUTURE") { edit(feb28, s31, FEB28, submitted, EditScope.FUTURE) }

            val newSeries = assertSplit(label, s31, oldSeries)
            assertEquals(
                "$label / CA-11 — date modifiée : elle devient l'ancrage, aucun ancrage reporté",
                s31Row(behavior = LAST_VALID_DAY).copy(id = newSeries, startDate = MAR02, anchorDayOfMonth = null),
                read(label, "nouvelle série") { transactionRepo.getSeriesById(newSeries) },
            )
            val edited = assertSingleException(label, newSeries, slot = MAR02)

            val from = startOf(2026, 2, 1)
            val to = endOf(2026, 5, 31)
            assertCalendar(
                "$label / CA-11 — février → mai : plus rien le 28 février",
                from,
                to,
                listOf(physical(edited, newSeries, slot = MAR02, date = MAR02), virtual(newSeries, APR02), virtual(newSeries, MAY02)),
            )
        }

    /** Deux séries exactement ; l'ancienne s'arrête 1 ms avant le slot du 28 février, sans autre changement. */
    private suspend fun assertSplit(label: String, oldId: Long, oldSeries: RecurringSeriesEntity): Long {
        val seriesIds = longs("SELECT id FROM recurring_series ORDER BY id")
        assertEquals("$label / CA-06 — exactement deux séries ; observé $seriesIds", 2, seriesIds.size)
        assertEquals(
            "$label / CA-06 — ancienne série : fin au 28 février 09:00 moins 1 ms, choix inchangé",
            oldSeries.copy(endDate = FEB28 - 1),
            read(label, "ancienne série") { transactionRepo.getSeriesById(oldId) },
        )
        return seriesIds.single { it != oldId }
    }

    /** Une seule ligne de transaction dans toute la base : l'occurrence éditée, sur la nouvelle série. */
    private fun assertSingleException(label: String, seriesId: Long, slot: Long): Long {
        val rows = rawRows("SELECT id, seriesId, seriesDate, isException, deleted FROM transactions ORDER BY id")
        assertEquals("$label / CA-06 — une seule ligne matérialisée ; observé $rows", 1, rows.size)
        val id = rows.single().substringBefore('|').toLong()
        assertEquals(
            "$label / CA-06 — exception de la nouvelle série au slot édité, non supprimée",
            listOf("$id|$seriesId|$slot|1|0"),
            rows,
        )
        return id
    }

    // =============================================================================================
    // R-05 — CA-06 / CA-11 : ALL sur une série déjà ancrée
    // =============================================================================================

    @Test
    fun `R-05 date inchangee - given SC ancree au 31, when ALL vers sauter, then une serie ancree au 31 et 28 fevrier, 31 mars, 31 mai`() =
        runTest {
            val label = "R-05 date inchangée"
            seedParents()
            val sc = insertSeries(scRow())
            val feb28 = captureVirtual(label, sc, FEB28)

            read(label, "édition ALL") {
                edit(feb28, sc, FEB28, edition(date = FEB28, behavior = SKIP_PERIOD, tagIds = emptyList()), EditScope.ALL)
            }

            assertEquals("$label / CA-06 — une seule série", listOf(sc), longs("SELECT id FROM recurring_series"))
            assertEquals(
                "$label / CA-06, CA-11 — seul le choix change, l'ancrage reporté survit",
                scRow().copy(id = sc, missingDayBehavior = SKIP_PERIOD),
                read(label, "série") { transactionRepo.getSeriesById(sc) },
            )
            assertEquals("$label / I-2 — ALL ne matérialise pas la virtuelle", 0L, count("transactions"))

            val from = startOf(2026, 2, 1)
            val to = endOf(2026, 5, 31)
            assertCalendar(
                "$label / CA-05, CA-11",
                from,
                to,
                listOf(FEB28, MAR31, MAY31).map { virtual(sc, it, tags = emptySet()) },
            )
        }

    @Test
    fun `R-05 debut modifie - given SC ancree au 31, when ALL avec debut au 2 mars, then une serie sans ancrage et 2 mars, 2 avril, 2 mai`() =
        runTest {
            val label = "R-05 début modifié"
            seedParents()
            val sc = insertSeries(scRow())
            val feb28 = captureVirtual(label, sc, FEB28)

            read(label, "édition ALL") {
                edit(feb28, sc, FEB28, edition(date = MAR02, behavior = LAST_VALID_DAY, tagIds = emptyList()), EditScope.ALL)
            }

            assertEquals("$label / CA-06 — une seule série", listOf(sc), longs("SELECT id FROM recurring_series"))
            assertEquals(
                "$label / CA-11 — début modifié : l'ancrage reporté disparaît",
                scRow().copy(id = sc, startDate = MAR02, anchorDayOfMonth = null),
                read(label, "série") { transactionRepo.getSeriesById(sc) },
            )
            assertEquals("$label / I-2 — ALL ne matérialise pas la virtuelle", 0L, count("transactions"))

            val from = startOf(2026, 2, 1)
            val to = endOf(2026, 5, 31)
            assertCalendar(
                "$label / CA-11",
                from,
                to,
                listOf(MAR02, APR02, MAY02).map { virtual(sc, it, tags = emptySet()) },
            )
        }

    // =============================================================================================
    // R-06 — CA-06 : SINGLE ne modifie jamais le choix de la série
    // =============================================================================================

    @Test
    fun `R-06 - given S31 dernier jour, when SINGLE sur le 31 mars avec choix sauter transmis, then serie strictement inchangee et une exception au 31 mars`() =
        runTest {
            val label = "R-06"
            seedParents()
            val s31 = insertSeries(s31Row(behavior = LAST_VALID_DAY), tagT)
            val mar31 = captureVirtual(label, s31, MAR31)
            val seriesBefore = rawRows("SELECT * FROM recurring_series ORDER BY id")
            val seriesTagsBefore = rawRows("SELECT * FROM series_tags ORDER BY seriesId, tagId")

            read(label, "édition SINGLE") {
                edit(
                    mar31, s31, MAR31,
                    edition(date = MAR31, frequency = RecurrenceFrequency.NONE, behavior = SKIP_PERIOD),
                    EditScope.SINGLE,
                )
            }

            assertEquals("$label / CA-06 — ligne de série strictement inchangée", seriesBefore, rawRows("SELECT * FROM recurring_series ORDER BY id"))
            assertEquals("$label / CA-06 — liens de tags de série inchangés", seriesTagsBefore, rawRows("SELECT * FROM series_tags ORDER BY seriesId, tagId"))
            assertEquals(
                "$label / CA-06 — le choix de la règle reste LAST_VALID_DAY",
                LAST_VALID_DAY,
                read(label, "série") { transactionRepo.getSeriesById(s31) }!!.missingDayBehavior,
            )
            val exceptionId = assertSingleException(label, s31, slot = MAR31)
            assertOcc(
                "$label / CA-06 — l'exception porte l'édition",
                physical(exceptionId, s31, slot = MAR31, date = MAR31),
                read(label, "exception") { detail.getById(exceptionId) }.let { present(label, it) },
            )
        }

    // =============================================================================================
    // R-07 — CA-07 / I-2 : exceptions persistées prioritaires et intactes
    // =============================================================================================

    @Test
    fun `R-07 - given S31 avec E-FEV et X-MAR, when ALL vers sauter, then exceptions intactes, liste 31 janvier, 27 fevrier, 31 mai, details resolus`() =
        runTest {
            val label = "R-07"
            seedParents()
            val s31 = insertSeries(s31Row(behavior = LAST_VALID_DAY), tagT)
            // ID virtuel du slot du 28 février, capturé avant que E-FEV ne l'occupe.
            val feb28Virtual = captureVirtual(label, s31, FEB28)
            val jan31 = captureVirtual(label, s31, JAN31)
            val eFevRow = eFevRow(s31)
            val eFev = insertTx(eFevRow, tagT)
            val xMar = insertTx(xMarRow(s31))
            val exceptionsBefore = exceptionRows(eFev, xMar)

            read(label, "édition ALL") {
                edit(jan31, s31, JAN31, edition(date = JAN31, behavior = SKIP_PERIOD), EditScope.ALL)
            }

            assertEquals(
                "$label / I-2 — E-FEV et X-MAR identiques colonne par colonne, liens de tags compris",
                exceptionsBefore,
                exceptionRows(eFev, xMar),
            )
            assertEquals(
                "$label / CA-06 — seule la règle change de choix",
                s31Row(behavior = SKIP_PERIOD).copy(id = s31),
                read(label, "série") { transactionRepo.getSeriesById(s31) },
            )

            val expectedEFev = occOf(eFev, eFevRow, tagT)
            val from = startOf(2026, 1, 1)
            val to = endOf(2026, 5, 31)
            assertCalendar(
                "$label / CA-07 — janvier → mai : rien en mars (supprimé) ni en avril (sauté)",
                from,
                to,
                listOf(virtual(s31, JAN31), expectedEFev, virtual(s31, MAY31)),
            )

            val reference = snapshot()
            assertOcc(
                "$label / CA-07 — l'ancien ID virtuel du 28 février rend E-FEV",
                expectedEFev,
                present(label, read(label, "détail par l'ancien ID virtuel") { detail.getById(feb28Virtual) }),
            )
            assertOcc(
                "$label / CA-07 — l'ID physique de E-FEV rend E-FEV",
                expectedEFev,
                present(label, read(label, "détail par l'ID de E-FEV") { detail.getById(eFev) }),
            )
            val deleted = read(label, "détail par l'ID de X-MAR") { detail.getById(xMar) }
            assertNull("$label / CA-07 — l'ID de X-MAR rend null ; observé ${deleted?.let(::describe)}", deleted)
            assertNoWrite(label, reference)
        }

    // =============================================================================================
    // R-08 — CA-07 : la liste de période applique le choix enregistré
    // =============================================================================================

    @Test
    fun `R-08a - given liste ouverte sur S31 dernier jour, when choix passe a sauter, then le meme abonnement rend 31 janvier, 31 mars, 31 mai`() =
        runTest {
            val label = "R-08a"
            seedParents()
            val s31 = insertSeries(s31Row(behavior = LAST_VALID_DAY), tagT)
            val from = startOf(2026, 1, 1)
            val to = endOf(2026, 5, 31)
            val list = Observation("liste janvier → mai", backgroundScope, observe(from, to))
            try {
                val initial = list.next(label, "calendrier dernier jour")
                assertOccs(
                    "$label — état initial",
                    listOf(JAN31, FEB28, MAR31, APR30, MAY31).map { virtual(s31, it) },
                    initial,
                )

                read(label, "mutation") { transactionRepo.updateSeries(s31Row(behavior = SKIP_PERIOD).copy(id = s31)) }
                val reference = snapshot()

                val emissions = list.awaitChange(label, "calendrier sauter", initial)
                emissions.forEachIndexed { index, emission ->
                    assertOccs(
                        "$label / CA-07 — même abonnement, émission ${index + 1}/${emissions.size}",
                        listOf(JAN31, MAR31, MAY31).map { virtual(s31, it) },
                        emission,
                    )
                }
                assertNoWrite(label, reference)
            } finally {
                list.stop()
            }
        }

    @Test
    fun `R-08b - given S31 passee a sauter avant toute lecture, when nouvelle liste, then 31 janvier, 31 mars, 31 mai`() =
        runTest {
            val label = "R-08b"
            seedParents()
            val s31 = insertSeries(s31Row(behavior = LAST_VALID_DAY), tagT)
            read(label, "mutation") { transactionRepo.updateSeries(s31Row(behavior = SKIP_PERIOD).copy(id = s31)) }
            val reference = snapshot()

            val from = startOf(2026, 1, 1)
            val to = endOf(2026, 5, 31)
            assertCalendar("$label / CA-07", from, to, listOf(JAN31, MAR31, MAY31).map { virtual(s31, it) })
            assertNoWrite(label, reference)
        }

    // =============================================================================================
    // R-09 — CA-07 : l'occurrence individuelle applique le choix enregistré
    // =============================================================================================

    @Test
    fun `R-09a - given detail ouvert sur le virtuel du 28 fevrier, when choix passe a sauter, then le meme abonnement rend null et le 31 mars reste consultable`() =
        runTest {
            val label = "R-09a"
            seedParents()
            val s31 = insertSeries(s31Row(behavior = LAST_VALID_DAY), tagT)
            val feb28 = captureVirtual(label, s31, FEB28)
            val mar31 = captureVirtual(label, s31, MAR31)
            val opened = Observation("détail du 28 février", backgroundScope, detail(feb28))
            try {
                val initial = opened.next(label, "virtuel du 28 février")
                assertOcc("$label — état initial", virtual(s31, FEB28), present(label, initial))

                read(label, "mutation") { transactionRepo.updateSeries(s31Row(behavior = SKIP_PERIOD).copy(id = s31)) }
                val reference = snapshot()

                val emissions = opened.awaitChange(label, "null", initial)
                emissions.forEachIndexed { index, emission ->
                    assertNull(
                        "$label / CA-07 — même abonnement, émission ${index + 1}/${emissions.size} : null attendu ; observé ${emission?.let(::describe)}",
                        emission,
                    )
                }
                assertOcc(
                    "$label / CA-07 — témoin : le 31 mars reste consultable",
                    virtual(s31, MAR31),
                    present(label, read(label, "témoin du 31 mars") { detail.getById(mar31) }),
                )
                assertNoWrite(label, reference)
            } finally {
                opened.stop()
            }
        }

    @Test
    fun `R-09b - given choix passe a sauter, when getById du virtuel du 28 fevrier, then null sans ecriture`() =
        runTest {
            val label = "R-09b"
            seedParents()
            val s31 = insertSeries(s31Row(behavior = LAST_VALID_DAY), tagT)
            val feb28 = captureVirtual(label, s31, FEB28)
            read(label, "mutation") { transactionRepo.updateSeries(s31Row(behavior = SKIP_PERIOD).copy(id = s31)) }
            val reference = snapshot()

            val resolved = read(label, "getById du 28 février") { detail.getById(feb28) }

            assertNull("$label / CA-07 — slot sauté : null attendu ; observé ${resolved?.let(::describe)}", resolved)
            assertNoWrite(label, reference)
        }

    // =============================================================================================
    // R-10 — CA-05 / CA-07 / CA-09 : limite comptée sur les périodes visées
    // =============================================================================================

    @Test
    fun `R-10 janvier a mai - given S31 sauter limite 4, when liste, then 31 janvier et 31 mars, rien en base`() =
        runTest {
            val label = "R-10 janvier → mai"
            seedParents()
            val s31 = insertSeries(s31Row(behavior = SKIP_PERIOD, maxOccurrences = 4), tagT)
            val reference = snapshot()

            val from = startOf(2026, 1, 1)
            val to = endOf(2026, 5, 31)
            assertCalendar("$label / CA-05, CA-09", from, to, listOf(JAN31, MAR31).map { virtual(s31, it) })
            assertEquals("$label / CA-07 — aucune transaction générée en base", 0L, count("transactions"))
            assertNoWrite(label, reference)
        }

    @Test
    fun `R-10 mars a mai - given S31 sauter limite 4, when liste, then 31 mars seul, rien en base`() =
        runTest {
            val label = "R-10 mars → mai"
            seedParents()
            val s31 = insertSeries(s31Row(behavior = SKIP_PERIOD, maxOccurrences = 4), tagT)
            val reference = snapshot()

            val from = startOf(2026, 3, 1)
            val to = endOf(2026, 5, 31)
            assertCalendar("$label / CA-09 — la limite reste celle de la série", from, to, listOf(virtual(s31, MAR31)))
            assertEquals("$label / CA-07 — aucune transaction générée en base", 0L, count("transactions"))
            assertNoWrite(label, reference)
        }

    // =============================================================================================
    // Jeu de données
    // =============================================================================================

    private suspend fun seedParents() {
        val account = AccountEntity(
            name = "ZZ_L88_compte",
            type = AccountType.CHECKING,
            initialBalance = 100_000,
            balanceUpdatedAt = 0L,
            colorArgb = 0xFF2196F3.toInt(),
            icon = "wallet",
            archived = false,
        )
        accA = account.copy(id = db.accountDao().upsert(account))
        val category = CategoryEntity(
            name = "ZZ_L88_categorie",
            type = TransactionType.EXPENSE,
            colorArgb = 0xFF4CAF50.toInt(),
            icon = "home",
            parentCategoryId = null,
        )
        catC = category.copy(id = db.categoryDao().upsert(category))
        val tag = TagEntity(name = "ZZ_L88_tag", colorArgb = 0xFF455A64.toInt())
        tagT = tag.copy(id = db.tagDao().upsert(tag))
    }

    /** S31 : mensuelle depuis le 31 janvier 2026, ancrage reporté null. */
    private fun s31Row(behavior: MissingDayBehavior, maxOccurrences: Int? = null) = RecurringSeriesEntity(
        title = TITLE,
        amount = AMOUNT,
        type = TransactionType.EXPENSE,
        categoryId = catC.id,
        accountId = accA.id,
        frequency = RecurrenceFrequency.MONTHLY,
        interval = 1,
        startDate = JAN31,
        endDate = null,
        maxOccurrences = maxOccurrences,
        daysOfWeek = null,
        isCancelled = false,
        note = NOTE,
        linkedGoalId = null,
        linkedLoanId = null,
        missingDayBehavior = behavior,
        anchorDayOfMonth = null,
    )

    /** SC : mensuelle depuis le 28 février 2026, dernier jour, ancrage reporté 31, sans tag ni exception. */
    private fun scRow() = s31Row(behavior = LAST_VALID_DAY).copy(startDate = FEB28, anchorDayOfMonth = 31)

    /** E-FEV : exception de S31, slot du 28 février affiché le 27, personnalisée. */
    private fun eFevRow(s31: Long) = TransactionEntity(
        title = "ZZ_L88_exception",
        amount = 73_000,
        type = TransactionType.EXPENSE,
        status = TransactionStatus.PLANNED,
        kind = TransactionKind.STANDARD,
        date = FEB27,
        accountId = accA.id,
        categoryId = catC.id,
        note = "personnalisée",
        paidAt = null,
        seriesId = s31,
        seriesDate = FEB28,
        isException = true,
        deleted = false,
    )

    /** X-MAR : ligne supprimée de S31 au slot du 31 mars. */
    private fun xMarRow(s31: Long) = TransactionEntity(
        title = TITLE,
        amount = AMOUNT,
        type = TransactionType.EXPENSE,
        status = TransactionStatus.PLANNED,
        kind = TransactionKind.STANDARD,
        date = MAR31,
        accountId = accA.id,
        categoryId = catC.id,
        note = NOTE,
        paidAt = null,
        seriesId = s31,
        seriesDate = MAR31,
        isException = true,
        deleted = true,
    )

    /** Ce que le formulaire enverrait : valeurs de S31, statut PLANNED, tag T. */
    private fun edition(
        date: Long,
        behavior: MissingDayBehavior,
        frequency: RecurrenceFrequency = RecurrenceFrequency.MONTHLY,
        tagIds: List<Long> = listOf(tagT.id),
    ) = TransactionEdition(
        title = TITLE,
        amount = AMOUNT,
        type = TransactionType.EXPENSE,
        date = date,
        accountId = accA.id,
        categoryId = catC.id,
        note = NOTE,
        status = TransactionStatus.PLANNED,
        frequency = frequency,
        interval = 1,
        daysOfWeek = emptySet(),
        endDate = null,
        maxOccurrences = null,
        missingDayBehavior = behavior,
        linkedGoalId = null,
        linkedLoanId = null,
        tagIds = tagIds,
    )

    /** Série et ses tags en une seule transaction. */
    private suspend fun insertSeries(series: RecurringSeriesEntity, vararg tags: TagEntity): Long =
        db.withTransaction {
            val id = db.recurringSeriesDao().upsertSeries(series)
            tags.forEach { db.recurringSeriesDao().addSeriesTagCrossRef(SeriesTagCrossRef(id, it.id)) }
            id
        }

    /** Ligne et ses tags en une seule transaction : pas d'état « ligne sans tag » observable. */
    private suspend fun insertTx(row: TransactionEntity, vararg tags: TagEntity): Long = db.withTransaction {
        val id = db.transactionDao().upsert(row)
        tags.forEach { db.transactionDao().addTagCrossRef(TransactionTagCrossRef(id, it.id)) }
        id
    }

    /**
     * ID virtuel du slot [slot] de [seriesId], lu dans la liste janvier → mai : exactement une
     * occurrence virtuelle à cette date, sinon la préparation est fausse.
     */
    private suspend fun captureVirtual(label: String, seriesId: Long, slot: Long): Long {
        val from = startOf(2026, 1, 1)
        val to = endOf(2026, 5, 31)
        val list = awaitFirst(label, "liste de capture", observe(from, to))
        val matches = list.filter { it.transaction.seriesId == seriesId && it.transaction.seriesDate == slot }
        assertEquals("$label — préparation : un seul virtuel au ${text(slot)} ; observé ${describe(list)}", 1, matches.size)
        val id = matches.single().transaction.id
        assertTrue("$label — préparation : l'occurrence capturée est virtuelle ; observé $id", id < 0)
        return id
    }

    // =============================================================================================
    // Attendus, écrits en clair : jamais recalculés par le code testé
    // =============================================================================================

    /** Ce que le test attend d'une occurrence, champ par champ. `id = null` : virtuelle (ID négatif). */
    private data class Occ(
        val id: Long?,
        val seriesId: Long?,
        val seriesDate: Long?,
        val date: Long,
        val title: String,
        val amount: Long,
        val type: TransactionType,
        val status: TransactionStatus,
        val paidAt: Long?,
        val isException: Boolean,
        val accountId: Long,
        val account: AccountEntity?,
        val categoryId: Long,
        val category: CategoryEntity?,
        val note: String?,
        val tags: Set<TagEntity>,
        val kind: TransactionKind = TransactionKind.STANDARD,
        val deleted: Boolean = false,
        val linkedGoalId: Long? = null,
        val linkedLoanId: Long? = null,
        val cardId: Long? = null,
    )

    /** Virtuel attendu de [seriesId] à [date] : valeurs de S31, PLANNED, non exception. */
    private fun virtual(seriesId: Long, date: Long, tags: Set<TagEntity> = setOf(tagT)) = Occ(
        id = null,
        seriesId = seriesId,
        seriesDate = date,
        date = date,
        title = TITLE,
        amount = AMOUNT,
        type = TransactionType.EXPENSE,
        status = TransactionStatus.PLANNED,
        paidAt = null,
        isException = false,
        accountId = accA.id,
        account = accA,
        categoryId = catC.id,
        category = catC,
        note = NOTE,
        tags = tags,
    )

    /** Occurrence matérialisée par l'édition, aux valeurs de l'édition soumise. */
    private fun physical(id: Long, seriesId: Long, slot: Long, date: Long) =
        virtual(seriesId, date).copy(id = id, seriesDate = slot, isException = true)

    /** Ligne physique attendue telle que le test l'a écrite. */
    private fun occOf(id: Long, row: TransactionEntity, vararg tags: TagEntity) = Occ(
        id = id,
        seriesId = row.seriesId,
        seriesDate = row.seriesDate,
        date = row.date,
        title = row.title,
        amount = row.amount,
        type = row.type,
        status = row.status,
        paidAt = row.paidAt,
        isException = row.isException,
        accountId = row.accountId,
        account = accA.takeIf { it.id == row.accountId },
        categoryId = row.categoryId,
        category = catC.takeIf { it.id == row.categoryId },
        note = row.note,
        tags = tags.toSet(),
        deleted = row.deleted,
    )

    private fun seen(occurrence: TransactionWithRelations): Occ {
        val tx = occurrence.transaction
        return Occ(
            id = tx.id.takeIf { it > 0 },
            seriesId = tx.seriesId,
            seriesDate = tx.seriesDate,
            date = tx.date,
            title = tx.title,
            amount = tx.amount,
            type = tx.type,
            status = tx.status,
            paidAt = tx.paidAt,
            isException = tx.isException,
            accountId = tx.accountId,
            account = occurrence.account,
            categoryId = tx.categoryId,
            category = occurrence.category,
            note = tx.note,
            tags = occurrence.tags.toSet(),
            kind = tx.kind,
            deleted = tx.deleted,
            linkedGoalId = tx.linkedGoalId,
            linkedLoanId = tx.linkedLoanId,
            cardId = tx.cardId,
        )
    }

    // =============================================================================================
    // Oracles
    // =============================================================================================

    private fun <T : Any> present(label: String, value: T?): T {
        assertNotNull("$label — valeur attendue, observé null", value)
        return value!!
    }

    private fun assertOcc(label: String, expected: Occ, actual: TransactionWithRelations) {
        val id = actual.transaction.id
        if (expected.id == null) {
            assertTrue("$label — occurrence virtuelle attendue : ID négatif ; observé ${describe(actual)}", id < 0)
        } else {
            assertEquals("$label — ID physique ; observé ${describe(actual)}", expected.id, id)
        }
        assertEquals("$label — tous les champs de l'occurrence", expected, seen(actual))
    }

    private fun assertOccs(label: String, expected: List<Occ>, actual: List<TransactionWithRelations>?) {
        assertNotNull("$label — liste attendue, observé null", actual)
        assertEquals("$label — cardinalité exacte ; observé : ${describe(actual!!)}", expected.size, actual.size)
        assertEquals(
            "$label — slots (série, slot, date affichée), dans l'ordre",
            expected.map { Triple(it.seriesId, it.seriesDate?.let(::text), text(it.date)) },
            actual.map { Triple(it.transaction.seriesId, it.transaction.seriesDate?.let(::text), text(it.transaction.date)) },
        )
        expected.zip(actual).forEachIndexed { index, (e, a) -> assertOcc("$label [${index + 1}]", e, a) }
        val virtualIds = actual.map { it.transaction.id }.filter { it < 0 }
        assertEquals("$label — IDs virtuels distincts : $virtualIds", virtualIds.size, virtualIds.toSet().size)
    }

    /** Liste exacte, deux lectures neuves aux IDs identiques, et aucune écriture. */
    private suspend fun assertCalendar(label: String, from: Long, to: Long, expected: List<Occ>) {
        val reference = snapshot()
        val first = awaitFirst(label, "liste", observe(from, to))
        assertOccs(label, expected, first)
        val second = awaitFirst(label, "seconde liste", observe(from, to))
        assertEquals(
            "$label — IDs stables entre deux lectures",
            first.map { it.transaction.id },
            second.map { it.transaction.id },
        )
        assertNoWrite(label, reference)
    }

    // =============================================================================================
    // Snapshots — SELECT de contrôle, lignes supprimées comprises
    // =============================================================================================

    private fun snapshot(): Map<String, List<String>> =
        SNAPSHOT_TABLES.associate { (table, keys) -> table to rawRows("SELECT * FROM $table ORDER BY $keys") }

    private fun assertNoWrite(label: String, reference: Map<String, List<String>>) = assertEquals(
        "$label — la lecture n'écrit rien : 7 tables identiques à la référence",
        reference,
        snapshot(),
    )

    private fun exceptionRows(vararg ids: Long): List<String> {
        val list = ids.joinToString(",")
        return rawRows("SELECT * FROM transactions WHERE id IN ($list) ORDER BY id") +
            rawRows("SELECT * FROM transaction_tags WHERE transactionId IN ($list) ORDER BY transactionId, tagId")
    }

    private fun count(table: String): Long = longs("SELECT COUNT(*) FROM $table").single()

    private fun longs(sql: String): List<Long> = buildList {
        db.query(sql, emptyArray<Any?>()).use { cursor -> while (cursor.moveToNext()) add(cursor.getLong(0)) }
    }

    private fun rawRows(sql: String): List<String> = buildList {
        db.query(sql, emptyArray<Any?>()).use { cursor ->
            while (cursor.moveToNext()) {
                add((0 until cursor.columnCount).joinToString("|") { if (cursor.isNull(it)) "null" else cursor.getString(it) })
            }
        }
    }

    // =============================================================================================
    // Attentes bornées en temps réel : Room émet depuis ses propres fils
    // =============================================================================================

    private class Got<T>(val value: T)

    /**
     * [block] borné à [TIMEOUT] **réel** : un `withTimeout` dans `runTest` compterait du temps
     * virtuel et expirerait avant l'émission de Room.
     */
    private suspend fun <T> read(label: String, expectation: String, block: suspend () -> T): T {
        val got = withContext(Dispatchers.Default) { withTimeoutOrNull(TIMEOUT) { Got(block()) } }
            ?: throw AssertionError("$label — aucune réponse en $TIMEOUT ; attendu : $expectation")
        return got.value
    }

    private suspend fun <T> awaitFirst(label: String, expectation: String, flow: Flow<T>): T =
        read(label, expectation) { flow.first() }

    /** Observation laissée ouverte. Chaque émission est conservée, dans l'ordre, sans filtre. */
    private class Observation<T>(val name: String, scope: CoroutineScope, flow: Flow<T>) {
        private val received = Channel<Got<T>>(Channel.UNLIMITED)
        private var last: String = "aucune émission"
        private val job = scope.launch(Dispatchers.Default) { flow.collect { received.send(Got(it)) } }

        /** Arrête la collecte et attend sa fin, avant la fermeture de la base. */
        suspend fun stop() = job.cancelAndJoin()

        suspend fun next(label: String, expectation: String): T {
            val item = withContext(Dispatchers.Default) { withTimeoutOrNull(TIMEOUT) { received.receive() } }
                ?: throw AssertionError("$label — $name : aucune émission en $TIMEOUT ; attendu : $expectation ; dernier état : $last")
            last = item.value.toString()
            return item.value
        }

        private fun drain(): List<T> = generateSequence { received.tryReceive().getOrNull() }.map { it.value }.toList()
            .also { if (it.isNotEmpty()) last = it.last().toString() }

        /**
         * Les répétitions de [previous] qui précèdent le changement sont tolérées (relectures de
         * Room). À partir du premier état différent, **chaque** émission reçue est rendue.
         */
        suspend fun awaitChange(label: String, expectation: String, previous: T): List<T> {
            var item = next(label, expectation)
            while (item == previous) item = next(label, expectation)
            return listOf(item) + drain()
        }
    }

    // =============================================================================================
    // Lisibilité des messages
    // =============================================================================================

    private fun text(millis: Long): String = Instant.ofEpochMilli(millis).atZone(PARIS).toLocalDateTime().toString()

    private fun describe(occurrence: TransactionWithRelations): String = occurrence.transaction.let {
        "{id=${it.id}, série=${it.seriesId}, slot=${it.seriesDate?.let(::text)}, date=${text(it.date)}, " +
            "${it.title}, ${it.amount}, ${it.status}, tags=${occurrence.tags.map { tag -> tag.name }}, supprimée=${it.deleted}}"
    }

    private fun describe(list: List<TransactionWithRelations>): String = list.joinToString(prefix = "[", postfix = "]") { describe(it) }

    private companion object {
        val PARIS: ZoneId = ZoneId.of("Europe/Paris")
        val TIMEOUT = 5.seconds
        const val TITLE = "ZZ_L88_room"
        const val AMOUNT = 12_345L
        const val NOTE = "note série"

        /** Tables relevées par le snapshot, avec leur clé de tri. */
        val SNAPSHOT_TABLES = listOf(
            "accounts" to "id",
            "categories" to "id",
            "tags" to "id",
            "recurring_series" to "id",
            "series_tags" to "seriesId, tagId",
            "transactions" to "id",
            "transaction_tags" to "transactionId, tagId",
        )

        fun at(year: Int, month: Int, day: Int): Long =
            LocalDateTime.of(year, month, day, 9, 0).atZone(PARIS).toInstant().toEpochMilli()

        fun startOf(year: Int, month: Int, day: Int): Long =
            LocalDate.of(year, month, day).atStartOfDay(PARIS).toInstant().toEpochMilli()

        fun endOf(year: Int, month: Int, day: Int): Long =
            LocalDate.of(year, month, day).atTime(23, 59, 59, 999_000_000).atZone(PARIS).toInstant().toEpochMilli()

        // Instants à 09:00 Paris.
        val JAN31 = at(2026, 1, 31)
        val FEB27 = at(2026, 2, 27)
        val FEB28 = at(2026, 2, 28)
        val MAR02 = at(2026, 3, 2)
        val MAR31 = at(2026, 3, 31)
        val APR02 = at(2026, 4, 2)
        val APR30 = at(2026, 4, 30)
        val MAY02 = at(2026, 5, 2)
        val MAY31 = at(2026, 5, 31)
    }
}

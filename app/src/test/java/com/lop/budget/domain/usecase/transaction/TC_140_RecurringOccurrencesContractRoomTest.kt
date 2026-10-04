package com.lop.budget.domain.usecase.transaction

import android.app.Application
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
import com.lop.budget.data.repository.TransactionRepository
import com.lop.budget.domain.model.AccountType
import com.lop.budget.domain.model.NO_ACCOUNT_ID
import com.lop.budget.domain.model.NO_CATEGORY_ID
import com.lop.budget.domain.model.RecurrenceFrequency
import com.lop.budget.domain.model.TransactionKind
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
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
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import java.util.TimeZone
import kotlin.time.Duration.Companion.seconds

/**
 * TC-140 — Calendrier récurrent : lectures, sélection et résolution sur Room (LOP-7).
 *
 * Fiche : https://app.notion.com/p/738c5dea8e7a4ee78762793c9abfa25d
 * US : https://app.notion.com/p/38550f34a8c58167861ec6e2eb86cb20 — CA-01 à CA-07, I-1 à I-3.
 * Commit visé : 70396a76fee364839ca0d168d13d710238969d6a.
 *
 * ## Niveau
 * Intégration hors UI sur Room réel. Aucun mock, fake ni spy sur la chaîne.
 *
 * ## Chaîne exercée
 * `ObserveRecurringOccurrencesUseCase` (système testé)
 *   → `ObserveTransactionsUseCase` / `ObserveTransactionDetailUseCase`
 *   → `TransactionRepository`, `AccountRepository`, `CategoryRepository` → DAO → `LopDatabase` en mémoire,
 *   avec le vrai `RecurrenceEngine`.
 *
 * ## Correspondance cas → CA / invariant → fonction de production
 * ```
 * R-01  CA-01, I-2        observeUpcoming : 3 / 2 / 1 / 0 échéances, null pour une ponctuelle
 * R-02  CA-01, I-2        observeUpcoming : complétude après masquage et après fin de génération
 * R-03  CA-02, I-2        observeMonth : bornes du mois, fuseau, série, date affichée
 * R-04  CA-04             selectDay sur un mois réellement lu : vide / unique / multiple
 * R-05  CA-02/03/05       observeContext (+ observeUpcoming, resolveTarget pour la série lointaine)
 * R-06  CA-04/05, I-3     resolveTarget + ObserveTransactionDetailUseCase.getById
 * R-07  CA-06, I-3        observeContext : ancre conservée dans le flux déjà ouvert
 * R-08  CA-07, I-2        observeUpcoming + observeMonth ouverts pendant quatre mutations
 * R-09  CA-07             mêmes états finaux que R-08, lus par des observations neuves
 * ```
 * Chaque cas se termine par I-1 : les 7 tables, lignes supprimées comprises, sont identiques à la
 * référence prise après la dernière écriture de préparation.
 *
 * ## Hypothèses levées
 * - Montage validé le 4 octobre 2026 par `TC_88_ObserveUpcomingOccurrencesRoomTest` (4/4).
 * - « Zone Paris / UTC » (R-03) : seul le paramètre `zone` de `observeMonth` varie. Le fuseau par
 *   défaut reste Paris, car le moteur de récurrence le lit pour placer les slots.
 * - Les écritures de préparation (ligne + tags) sont faites dans une seule transaction : un état
 *   « ligne sans tag » observé serait un artefact du montage, pas un défaut.
 * - Les IDs virtuels sont jugés sur leurs propriétés (négatifs, distincts, stables entre deux
 *   lectures), jamais recalculés par `RecurrenceEngine`.
 *
 * ## ANO connues
 * Aucune : les 23 cas sont verts au premier passage, le 4 octobre 2026.
 *
 * ## Preuves de sensibilité
 * Une mutation de production à la fois, retirée aussitôt ; chaque rouge porte sur une assertion
 * métier, jamais sur le montage.
 * ```
 * Filtre de série retiré (observeMonth)       → R-03a/b, R-04a, R-06a–d (préparation), R-08, R-09a/b
 * Borne de fin du mois incluse                → R-03a
 * Aperçu limité à 2 échéances                 → R-01a, R-02a, R-05c, R-08, R-09a/b
 * Ponctuelle rendue en liste vide             → R-01e
 * Dernière exception ignorée dans l'horizon   → R-02b
 * Ouverture toujours sur le départ            → R-05a, R-05c, R-07
 * Revalidation court-circuitée                → R-06b, R-06c, R-06d
 * Branche « c'est le départ » retirée         → R-06e
 * Ancre du départ non conservée               → R-07
 * Flux du mois figé à sa 1re émission         → R-08 ; R-09 reste vert (lecture neuve)
 * Occurrence unique traitée comme multiple    → R-04a
 * ```
 *
 * ## Hors périmètre
 * Rendu, accessibilité, erreurs affichées, pile de navigation et recréation d'écran (TC-138) ;
 * état d'écran et demandes concurrentes (TC-139) ; règles de génération du socle et commandes
 * d'écriture existantes. R-07 ne prouve pas la conservation de l'ancre après destruction du
 * ViewModel : seul le flux déjà ouvert est observé ici.
 *
 * ## Exécution
 * ```
 * ./gradlew :app:testDebugUnitTest --tests "*RecurringOccurrencesContractRoomTest"
 * ./gradlew :app:testDebugUnitTest
 * ```
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class RecurringOccurrencesContractRoomTest {

    private lateinit var db: LopDatabase
    private lateinit var detail: ObserveTransactionDetailUseCase
    private lateinit var occurrences: ObserveRecurringOccurrencesUseCase

    private lateinit var defaultTimeZone: TimeZone
    private lateinit var defaultLocale: Locale

    // Parents et séries, IDs alloués par Room.
    private lateinit var accA: AccountEntity
    private lateinit var accB: AccountEntity
    private lateinit var c1: CategoryEntity
    private lateinit var c2: CategoryEntity
    private lateinit var c3: CategoryEntity
    private lateinit var ta: TagEntity
    private lateinit var tb: TagEntity
    private lateinit var te1: TagEntity
    private lateinit var te2: TagEntity
    private var seriesA = 0L
    private var seriesB = 0L
    private var startId = 0L
    private var punctualId = 0L

    @Before
    fun setUp() {
        defaultTimeZone = TimeZone.getDefault()
        defaultLocale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(PARIS))
        Locale.setDefault(Locale.FRANCE)

        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Application>(),
            LopDatabase::class.java,
        ).allowMainThreadQueries().build()

        val transactionRepo = TransactionRepository(db.transactionDao(), db.recurringSeriesDao())
        val accountRepo = AccountRepository(db.accountDao())
        val categoryRepo = CategoryRepository(db.categoryDao())
        detail = ObserveTransactionDetailUseCase(transactionRepo, accountRepo, categoryRepo)
        occurrences = ObserveRecurringOccurrencesUseCase(
            ObserveTransactionsUseCase(transactionRepo, accountRepo, categoryRepo),
            detail,
        )
    }

    @After
    fun tearDown() {
        db.close()
        TimeZone.setDefault(defaultTimeZone)
        Locale.setDefault(defaultLocale)
    }

    // =============================================================================================
    // R-01 — CA-01 : exactement les trois prochaines échéances, ou toutes celles qui restent
    // =============================================================================================

    @Test
    fun `R-01a - given A sans fin, when apercu depuis T0, then exactement 28 fevrier, 31 mars et 30 avril`() =
        runTest {
            seedBase()
            val reference = snapshot()
            val t0 = read("R-01a", "T0") { detail.getById(startId) }!!

            val upcoming = awaitFirst("R-01a", "trois échéances", occurrences.observeUpcoming(t0))
            val again = awaitFirst("R-01a", "seconde lecture", occurrences.observeUpcoming(t0))

            assertOccs("R-01a / CA-01", listOf(vA(FEB28), vA(MAR31), vA(APR30)), upcoming)
            assertEquals(
                "R-01a / CA-01 — un ID virtuel est stable entre deux lectures",
                upcoming!!.map { it.transaction.id },
                again!!.map { it.transaction.id },
            )
            assertNoWrite("R-01a", reference)
        }

    @Test
    fun `R-01b - given A finie le 31 mars, when apercu depuis T0, then exactement 28 fevrier et 31 mars`() =
        runTest {
            seedBase(endA = END_MAR31)
            assertUpcomingFromStart("R-01b", listOf(vA(FEB28), vA(MAR31)))
        }

    @Test
    fun `R-01c - given A finie le 28 fevrier, when apercu depuis T0, then exactement 28 fevrier`() = runTest {
        seedBase(endA = END_FEB28)
        assertUpcomingFromStart("R-01c", listOf(vA(FEB28)))
    }

    @Test
    fun `R-01d - given A finie le 31 janvier, when apercu depuis T0, then liste vide et non null`() = runTest {
        seedBase(endA = END_JAN31)
        assertUpcomingFromStart("R-01d", emptyList())
    }

    @Test
    fun `R-01e - given la ponctuelle P, when apercu, then null et pas une liste vide`() = runTest {
        seedBase()
        val reference = snapshot()
        val p = read("R-01e", "P") { detail.getById(punctualId) }!!

        val upcoming = awaitFirst("R-01e", "null", occurrences.observeUpcoming(p))

        assertNull("R-01e / CA-01 — une ponctuelle n'a ni aperçu ni calendrier ; observé : $upcoming", upcoming)
        assertNoWrite("R-01e", reference)
    }

    // =============================================================================================
    // R-02 — CA-01 : complétude après masquage et après fin de génération
    // =============================================================================================

    @Test
    fun `R-02a - given 28 fevrier et 31 mars supprimes, when apercu, then 30 avril, 31 mai et 30 juin`() =
        runTest {
            seedBase()
            insertTx(aRow(FEB28, deleted = true), ta)
            insertTx(aRow(MAR31, deleted = true), ta)
            assertUpcomingFromStart("R-02a", listOf(vA(APR30), vA(MAY31), vA(JUN30)))
        }

    @Test
    fun `R-02b - given A finie le 31 janvier et E1 au 2 mars, when apercu, then exactement E1 sans virtuel`() =
        runTest {
            seedBase(endA = END_JAN31)
            val e1 = insertTx(e1Row(), te1)
            assertUpcomingFromStart("R-02b", listOf(occOf(e1, e1Row(), te1)))
        }

    // =============================================================================================
    // R-03 — CA-02 : mois borné, série seule, date affichée, fuseau
    // =============================================================================================

    @Test
    fun `R-03a - given quatre exceptions aux bornes, when mars a Paris, then b1 le 1er et b2 le 31`() = runTest {
        seedBase()
        val b = seedBorderExceptions()
        val reference = snapshot()

        val march = awaitFirst("R-03a", "mars 2026", occurrences.observeMonth(seriesA, MARCH, PARIS))

        assertMonth("R-03a / CA-02 Paris", MARCH, mapOf(1 to listOf(b[0]), 31 to listOf(b[1])), march)
        assertNoWrite("R-03a", reference)
    }

    @Test
    fun `R-03b - given les memes instants, when mars en UTC, then b2 puis b4 le 31`() = runTest {
        seedBase()
        val b = seedBorderExceptions()
        val reference = snapshot()

        val march = awaitFirst("R-03b", "mars 2026 UTC", occurrences.observeMonth(seriesA, MARCH, ZoneOffset.UTC))

        assertMonth("R-03b / CA-02 UTC", MARCH, mapOf(31 to listOf(b[1], b[3])), march)
        assertNoWrite("R-03b", reference)
    }

    // =============================================================================================
    // R-04 — CA-04 : décision vide / unique / multiple sur un mois réellement lu
    // =============================================================================================

    @Test
    fun `R-04a - given la base commune, when jours 1 et 31 mars, then Empty puis Single du virtuel`() = runTest {
        seedBase()
        val reference = snapshot()
        val march = awaitFirst("R-04a", "mars 2026", occurrences.observeMonth(seriesA, MARCH, PARIS))

        val first = occurrences.selectDay(march, LocalDate.of(2026, 3, 1))
        val last = occurrences.selectDay(march, LocalDate.of(2026, 3, 31))

        assertEquals("R-04a / CA-04 — le 1er mars est vide", DaySelection.Empty, first)
        assertTrue("R-04a / CA-04 — le 31 mars porte une seule occurrence ; observé : $last", last is DaySelection.Single)
        assertOcc("R-04a / CA-04 — 31 mars", vA(MAR31), (last as DaySelection.Single).occurrence)
        assertNoWrite("R-04a", reference)
    }

    @Test
    fun `R-04b - given E1 et E2 au 2 mars, when jour 2, then Multiple E1 puis E2, deux slots distincts`() =
        runTest {
            seedBase()
            val e1 = insertTx(e1Row(), te1)
            val e2 = insertTx(e2Row(), te2)
            val reference = snapshot()
            val march = awaitFirst("R-04b", "mars 2026", occurrences.observeMonth(seriesA, MARCH, PARIS))

            val selection = occurrences.selectDay(march, LocalDate.of(2026, 3, 2))

            assertTrue("R-04b / CA-04 — choix multiple attendu ; observé : $selection", selection is DaySelection.Multiple)
            assertOccs(
                "R-04b / CA-04, I-2",
                listOf(occOf(e1, e1Row(), te1), occOf(e2, e2Row(), te2)),
                (selection as DaySelection.Multiple).occurrences,
            )
            assertNoWrite("R-04b", reference)
        }

    // =============================================================================================
    // R-05 — CA-02/03/05 : contexte d'ouverture, série sans futur, série lointaine
    // =============================================================================================

    @Test
    fun `R-05a - given T0, when contexte, then serie A et ouverture sur le 28 fevrier`() = runTest {
        seedBase()
        val reference = snapshot()

        val context = awaitFirst("R-05a", "contexte de T0", occurrences.observeContext(startId, PARIS))

        assertNotNull("R-05a / CA-02 — T0 est une occurrence de série visible", context)
        assertEquals("R-05a / CA-02 — occurrence de départ", OccurrenceRef(startId, seriesA, JAN31), context!!.start)
        assertEquals("R-05a / I-2 — série explorée", seriesA, context.seriesId)
        assertOcc("R-05a / CA-03 — prochaine échéance", vA(FEB28), present("R-05a — prochaine échéance", context.nextDue))
        assertEquals("R-05a / CA-03 — jour de la prochaine échéance", LocalDate.of(2026, 2, 28), context.nextDueDay)
        assertEquals("R-05a / CA-02 — jour d'ouverture", LocalDate.of(2026, 2, 28), context.openingDay)
        assertNoWrite("R-05a", reference)
    }

    @Test
    fun `R-05b - given A finie le 31 janvier, when contexte, then ouverture sur le depart sans prochaine echeance`() =
        runTest {
            seedBase(endA = END_JAN31)
            val reference = snapshot()

            val context = awaitFirst("R-05b", "contexte sans futur", occurrences.observeContext(startId, PARIS))

            assertNotNull("R-05b / CA-02 — le calendrier reste accessible sans échéance future", context)
            assertEquals("R-05b — occurrence de départ", OccurrenceRef(startId, seriesA, JAN31), context!!.start)
            assertNull("R-05b / CA-03 — aucune prochaine échéance ; observé : ${context.nextDue}", context.nextDue)
            assertNull("R-05b / CA-03 — aucun jour de prochaine échéance", context.nextDueDay)
            assertEquals("R-05b / CA-02 — ouverture sur le mois du départ", LocalDate.of(2026, 1, 31), context.openingDay)
            assertNoWrite("R-05b", reference)
        }

    @Test
    fun `R-05c - given la serie decennale N, when contexte, apercu et resolution, then 2036 ouvrable`() = runTest {
        seedBase()
        val seriesN = insertSeries(seriesNRow(), ta)
        val n0Row = n0Row(seriesN)
        val n0 = insertTx(n0Row, ta)
        val reference = snapshot()

        val context = awaitFirst("R-05c", "contexte de N", occurrences.observeContext(n0, PARIS))
        val n0Read = read("R-05c", "départ de N") { detail.getById(n0) }!!
        val upcoming = awaitFirst("R-05c", "aperçu de N", occurrences.observeUpcoming(n0Read))!!
        val resolution = read("R-05c", "résolution") { occurrences.resolveTarget(upcoming.first()) }

        assertEquals("R-05c — occurrence de départ", OccurrenceRef(n0, seriesN, N_START), context!!.start)
        assertOcc("R-05c / CA-03 — prochaine échéance lointaine", vN(seriesN, N_2036), present("R-05c — prochaine échéance", context.nextDue))
        assertEquals("R-05c / CA-03 — jour de prochaine échéance", LocalDate.of(2036, 2, 10), context.nextDueDay)
        assertEquals("R-05c / CA-02 — jour d'ouverture", LocalDate.of(2036, 2, 10), context.openingDay)
        assertOccs(
            "R-05c / CA-01",
            listOf(vN(seriesN, N_2036), vN(seriesN, N_2046), vN(seriesN, N_2056)),
            upcoming,
        )
        assertEquals(
            "R-05c / CA-05 — une occurrence lointaine reste ouvrable",
            TargetResolution.Available(upcoming.first().transaction.id),
            resolution,
        )
        val opened = read("R-05c", "détail") { detail.getById(upcoming.first().transaction.id) }
        assertOcc("R-05c / CA-05 — détail ouvert", vN(seriesN, N_2036), present("R-05c — détail", opened))
        assertNoWrite("R-05c", reference)
    }

    // =============================================================================================
    // R-06 — CA-04/05, I-3 : revalidation de la cible capturée
    // =============================================================================================

    @Test
    fun `R-06a - given le virtuel du 28 fevrier capture, when resolution sans ecriture, then Available du meme ID`() =
        runTest {
            seedBase()
            val captured = captureFebruaryVirtual("R-06a")
            val reference = snapshot()

            val resolution = read("R-06a", "résolution") { occurrences.resolveTarget(captured, startRef()) }

            assertEquals("R-06a / CA-05", TargetResolution.Available(captured.transaction.id), resolution)
            val opened = read("R-06a", "détail") { detail.getById(captured.transaction.id) }
            assertOcc("R-06a / CA-05 — détail du virtuel", vA(FEB28), present("R-06a — détail", opened))
            assertNoWrite("R-06a", reference)
        }

    @Test
    fun `R-06b - given E1 materialisee apres capture, when resolution, then Available de E1 sur le meme slot`() =
        runTest {
            seedBase()
            val captured = captureFebruaryVirtual("R-06b")
            val e1 = insertTx(e1Row(), te1)
            val reference = snapshot()

            val resolution = read("R-06b", "résolution") { occurrences.resolveTarget(captured, startRef()) }

            assertEquals("R-06b / CA-05 — le réel prévaut sur le virtuel", TargetResolution.Available(e1), resolution)
            val opened = read("R-06b", "détail") { detail.getById(e1) }
            assertOcc("R-06b / CA-05 — détail courant de E1, slot d'origine conservé", occOf(e1, e1Row(), te1), present("R-06b — détail", opened))
            assertNoWrite("R-06b", reference)
        }

    @Test
    fun `R-06c - given E1 materialisee puis supprimee, when resolution, then Unavailable`() = runTest {
        seedBase()
        val captured = captureFebruaryVirtual("R-06c")
        val e1 = insertTx(e1Row(), te1)
        db.transactionDao().softDeleteTransaction(e1)
        val reference = snapshot()

        val resolution = read("R-06c", "résolution") { occurrences.resolveTarget(captured, startRef()) }

        assertEquals("R-06c / CA-05, I-3 — cible disparue", TargetResolution.Unavailable, resolution)
        assertNoWrite("R-06c", reference)
    }

    @Test
    fun `R-06d - given E2 affichee le 28 fevrier, when resolution du virtuel capture, then Unavailable`() =
        runTest {
            seedBase()
            val captured = captureFebruaryVirtual("R-06d")
            insertTx(e2Row(date = FEB28), te2)
            val reference = snapshot()

            val resolution = read("R-06d", "résolution") { occurrences.resolveTarget(captured, startRef()) }

            assertEquals(
                "R-06d / I-3 — un autre slot affiché le même jour ne remplace jamais la cible",
                TargetResolution.Unavailable,
                resolution,
            )
            assertNoWrite("R-06d", reference)
        }

    @Test
    fun `R-06e - given T0 comme cible et comme depart, when resolution, then Start`() = runTest {
        seedBase()
        val reference = snapshot()
        val t0 = read("R-06e", "T0") { detail.getById(startId) }!!

        val resolution = read("R-06e", "résolution") { occurrences.resolveTarget(t0, startRef()) }

        assertEquals("R-06e / CA-04 — pas de second détail du départ", TargetResolution.Start, resolution)
        assertNoWrite("R-06e", reference)
    }

    // =============================================================================================
    // R-07 — CA-06, I-3 : l'ancre du flux ouvert survit à la suppression du départ
    // =============================================================================================

    @Test
    fun `R-07 - given le contexte ouvert, when T0 supprimee puis 28 fevrier a 99000, then ancre T0 et serie A`() =
        runTest {
            seedBase()
            val context = Observation("contexte", backgroundScope, occurrences.observeContext(startId, PARIS))
            try {
                val initial = context.next("R-07", "contexte initial de T0")
                assertEquals("R-07 — synchronisation : départ T0", OccurrenceRef(startId, seriesA, JAN31), initial?.start)

                db.transactionDao().softDeleteTransaction(startId)
                val changed = aRow(FEB28, amount = 99_000)
                val changedId = insertTx(changed, ta)
                val reference = snapshot()

                val states = context.awaitChange("R-07", "échéance du 28 février à 99000", initial)
                states.forEachIndexed { i, state ->
                    val label = "R-07 / CA-06 — émission ${i + 1}/${states.size}"
                    assertNotNull("$label : la suppression du départ ne fait pas perdre le contexte ouvert", state)
                    assertEquals("$label — ancre conservée", OccurrenceRef(startId, seriesA, JAN31), state!!.start)
                    assertEquals("$label — I-2 : toujours la série A, jamais B", seriesA, state.seriesId)
                    assertOcc("$label — prochaine échéance modifiée", occOf(changedId, changed, ta), present("$label — prochaine échéance", state.nextDue))
                    assertEquals("$label — jour de prochaine échéance", LocalDate.of(2026, 2, 28), state.nextDueDay)
                    assertEquals("$label — jour d'ouverture", LocalDate.of(2026, 2, 28), state.openingDay)
                }
                assertNoWrite("R-07", reference)
            } finally {
                context.stop()
            }
        }

    // =============================================================================================
    // R-08 — CA-07 : observations ouvertes pendant matérialisation, paiement, déplacement, suppression
    // =============================================================================================

    @Test
    fun `R-08 - given apercu fevrier et mars ouverts, when E1 materialisee payee deplacee supprimee, then chaque etat exact`() =
        runTest {
            seedBase()
            val t0 = read("R-08", "T0") { detail.getById(startId) }!!
            val preview = Observation("aperçu", backgroundScope, occurrences.observeUpcoming(t0))
            val february = Observation("février", backgroundScope, occurrences.observeMonth(seriesA, FEBRUARY, PARIS))
            val march = Observation("mars", backgroundScope, occurrences.observeMonth(seriesA, MARCH, PARIS))
            try {

                // Synchronisation : seule la présence du slot A du 28 février est exigée ici.
                var previewState = preview.next("R-08", "aperçu initial")!!
                var februaryState = february.next("R-08", "février initial")
                var marchState = march.next("R-08", "mars initial")
                assertTrue(
                    "R-08 — synchronisation : slot A du 28 février dans l'aperçu ; observé : ${describe(previewState)}",
                    previewState.any { it.transaction.seriesId == seriesA && it.transaction.seriesDate == FEB28 },
                )
                assertTrue(
                    "R-08 — synchronisation : slot A du 28 février en février ; observé : ${describe(februaryState)}",
                    februaryState.byDay[LocalDate.of(2026, 2, 28)].orEmpty().any { it.transaction.seriesDate == FEB28 },
                )

                // Matérialisation : PLANNED, paidAt null, autres valeurs de E1, à la date de son slot.
                val planned = e1Row(date = FEB28, status = TransactionStatus.PLANNED, paidAt = null)
                val e1 = insertTx(planned, te1)
                var reference = snapshot()
                previewState = preview.expect("R-08 matérialisation", previewState, changes = true) {
                    assertOccs(it, listOf(occOf(e1, planned, te1), vA(MAR31), vA(APR30)), this)
                }!!
                februaryState = february.expect("R-08 matérialisation", februaryState, changes = true) {
                    assertMonth(it, FEBRUARY, mapOf(28 to listOf(occOf(e1, planned, te1))), this)
                }
                marchState = march.expect("R-08 matérialisation", marchState, changes = false) {
                    assertMonth(it, MARCH, mapOf(31 to listOf(vA(MAR31))), this)
                }
                assertNoWrite("R-08 matérialisation", reference)

                // Paiement de la même ligne.
                val paid = planned.copy(id = e1, status = TransactionStatus.PAID, paidAt = FEB28)
                db.transactionDao().upsert(paid)
                reference = snapshot()
                previewState = preview.expect("R-08 paiement", previewState, changes = true) {
                    assertOccs(it, listOf(occOf(e1, paid, te1), vA(MAR31), vA(APR30)), this)
                }!!
                februaryState = february.expect("R-08 paiement", februaryState, changes = true) {
                    assertMonth(it, FEBRUARY, mapOf(28 to listOf(occOf(e1, paid, te1))), this)
                }
                marchState = march.expect("R-08 paiement", marchState, changes = false) {
                    assertMonth(it, MARCH, mapOf(31 to listOf(vA(MAR31))), this)
                }
                assertNoWrite("R-08 paiement", reference)

                // Déplacement au 2 mars 09:00.
                val moved = paid.copy(date = MAR02)
                db.transactionDao().upsert(moved)
                reference = snapshot()
                previewState = preview.expect("R-08 déplacement", previewState, changes = true) {
                    assertOccs(it, listOf(occOf(e1, moved, te1), vA(MAR31), vA(APR30)), this)
                }!!
                februaryState = february.expect("R-08 déplacement", februaryState, changes = true) {
                    assertMonth(it, FEBRUARY, emptyMap(), this)
                }
                marchState = march.expect("R-08 déplacement", marchState, changes = true) {
                    assertMonth(it, MARCH, mapOf(2 to listOf(occOf(e1, moved, te1)), 31 to listOf(vA(MAR31))), this)
                }
                assertNoWrite("R-08 déplacement", reference)

                // Suppression.
                db.transactionDao().softDeleteTransaction(e1)
                reference = snapshot()
                preview.expect("R-08 suppression", previewState, changes = true) {
                    assertOccs(it, listOf(vA(MAR31), vA(APR30), vA(MAY31)), this)
                }
                february.expect("R-08 suppression", februaryState, changes = false) {
                    assertMonth(it, FEBRUARY, emptyMap(), this)
                }
                march.expect("R-08 suppression", marchState, changes = true) {
                    assertMonth(it, MARCH, mapOf(31 to listOf(vA(MAR31))), this)
                }
                assertNoWrite("R-08 suppression", reference)
            } finally {
                listOf(preview, february, march).forEach { it.stop() }
            }
        }

    // =============================================================================================
    // R-09 — CA-07 : les états finaux de R-08, lus par des observations neuves
    // =============================================================================================

    @Test
    fun `R-09a - given E1 deja deplacee au 2 mars, when lectures neuves, then memes ensembles que R-08`() =
        runTest {
            seedBase()
            val moved = e1Row(date = MAR02, status = TransactionStatus.PAID, paidAt = FEB28)
            val e1 = insertTx(moved, te1)
            val reference = snapshot()

            val (preview, february, march) = freshReads("R-09a")

            assertOccs("R-09a / CA-07 aperçu", listOf(occOf(e1, moved, te1), vA(MAR31), vA(APR30)), preview)
            assertMonth("R-09a / CA-07 février", FEBRUARY, emptyMap(), february)
            assertMonth(
                "R-09a / CA-07 mars",
                MARCH,
                mapOf(2 to listOf(occOf(e1, moved, te1)), 31 to listOf(vA(MAR31))),
                march,
            )
            assertNoWrite("R-09a", reference)
        }

    @Test
    fun `R-09b - given E1 deja supprimee, when lectures neuves, then memes ensembles que R-08`() = runTest {
        seedBase()
        insertTx(e1Row(date = MAR02, status = TransactionStatus.PAID, paidAt = FEB28).copy(deleted = true), te1)
        val reference = snapshot()

        val (preview, february, march) = freshReads("R-09b")

        assertOccs("R-09b / CA-07 aperçu", listOf(vA(MAR31), vA(APR30), vA(MAY31)), preview)
        assertMonth("R-09b / CA-07 février", FEBRUARY, emptyMap(), february)
        assertMonth("R-09b / CA-07 mars", MARCH, mapOf(31 to listOf(vA(MAR31))), march)
        assertNoWrite("R-09b", reference)
    }

    // =============================================================================================
    // Jeu de données
    // =============================================================================================

    /** Base commune : parents, séries A et B, départ T0 et ponctuelle P. Aucune occurrence future. */
    private suspend fun seedBase(endA: Long? = null) {
        accA = insertAccount("ZZ_TC7_compte_A", 0xFF2196F3)
        accB = insertAccount("ZZ_TC7_compte_B", 0xFF455A64)
        c1 = insertCategory("ZZ_TC7_logement", TransactionType.EXPENSE, "home", 0xFF4CAF50)
        c2 = insertCategory("ZZ_TC7_ajustement", TransactionType.EXPENSE, "shopping_cart", 0xFF6D4C41)
        c3 = insertCategory("ZZ_TC7_revenu", TransactionType.INCOME, "work", 0xFF1565C0)
        ta = insertTag("ZZ_TC7_fixe", 0xFF4CAF50)
        tb = insertTag("ZZ_TC7_controle", 0xFF1565C0)
        te1 = insertTag("ZZ_TC7_exception_1", 0xFF6D4C41)
        te2 = insertTag("ZZ_TC7_exception_2", 0xFF7B1FA2)

        seriesA = insertSeries(
            RecurringSeriesEntity(
                title = BASE_TITLE,
                amount = 82_000,
                type = TransactionType.EXPENSE,
                categoryId = c1.id,
                accountId = accA.id,
                frequency = RecurrenceFrequency.MONTHLY,
                interval = 1,
                startDate = JAN31,
                endDate = endA,
                maxOccurrences = null,
                daysOfWeek = null,
                isCancelled = false,
                note = "contrat A",
                linkedGoalId = null,
                linkedLoanId = null,
            ),
            ta,
        )
        // Témoin d'exclusion : même calendrier que A, valeurs toutes différentes.
        seriesB = insertSeries(
            RecurringSeriesEntity(
                title = "ZZ_TC7_autre_serie",
                amount = 260_000,
                type = TransactionType.INCOME,
                categoryId = c3.id,
                accountId = accB.id,
                frequency = RecurrenceFrequency.MONTHLY,
                interval = 1,
                startDate = JAN31,
                endDate = null,
                maxOccurrences = null,
                daysOfWeek = null,
                isCancelled = false,
                note = "contrat B",
                linkedGoalId = null,
                linkedLoanId = null,
            ),
            tb,
        )
        startId = insertTx(aRow(JAN31), ta)
        punctualId = insertTx(
            TransactionEntity(
                title = "ZZ_TC7_ponctuelle",
                amount = 4_500,
                type = TransactionType.EXPENSE,
                status = TransactionStatus.PLANNED,
                kind = TransactionKind.STANDARD,
                date = MAR15,
                accountId = accA.id,
                categoryId = c1.id,
                note = "ponctuelle",
            ),
        )
    }

    /** R-03 : b1 à b4, sur les bornes de mars à Paris et autour du changement d'heure. */
    private suspend fun seedBorderExceptions(): List<Occ> = listOf(
        FEB28 to instant("2026-03-01T00:00:00+01:00"),
        MAR31 to instant("2026-03-31T23:59:59.999+02:00"),
        APR30 to instant("2026-02-28T23:59:59.999+01:00"),
        MAY31 to instant("2026-04-01T00:00:00+02:00"),
    ).mapIndexed { index, (slot, shown) ->
        val row = e1Row(slot = slot, date = shown, paidAt = shown, title = "ZZ_TC7_b${index + 1}")
        occOf(insertTx(row, te1), row, te1)
    }

    /** Ligne de la série A aux valeurs de T0, sur un slot non déplacé (`date == seriesDate`). */
    private fun aRow(slot: Long, amount: Long = 82_000, deleted: Boolean = false) = TransactionEntity(
        title = BASE_TITLE,
        amount = amount,
        type = TransactionType.EXPENSE,
        status = TransactionStatus.PLANNED,
        kind = TransactionKind.STANDARD,
        date = slot,
        accountId = accA.id,
        categoryId = c1.id,
        note = "contrat A",
        paidAt = null,
        seriesId = seriesA,
        seriesDate = slot,
        isException = true,
        deleted = deleted,
    )

    /** E1 : slot A du 28 février affiché le 2 mars, payée, compte B, catégorie C2. */
    private fun e1Row(
        slot: Long = FEB28,
        date: Long = MAR02,
        status: TransactionStatus = TransactionStatus.PAID,
        paidAt: Long? = date,
        title: String = "ZZ_TC7_exception_1",
    ) = TransactionEntity(
        title = title,
        amount = 91_000,
        type = TransactionType.EXPENSE,
        status = status,
        kind = TransactionKind.STANDARD,
        date = date,
        accountId = accB.id,
        categoryId = c2.id,
        note = "exception 1",
        paidAt = paidAt,
        seriesId = seriesA,
        seriesDate = slot,
        isException = true,
    )

    /** E2 : slot A du 31 mars affiché le 2 mars à 10:00, sans compte ni catégorie. */
    private fun e2Row(date: Long = MAR02_10) = TransactionEntity(
        title = "ZZ_TC7_exception_2",
        amount = 73_000,
        type = TransactionType.EXPENSE,
        status = TransactionStatus.PLANNED,
        kind = TransactionKind.STANDARD,
        date = date,
        accountId = NO_ACCOUNT_ID,
        categoryId = NO_CATEGORY_ID,
        note = "exception 2",
        paidAt = null,
        seriesId = seriesA,
        seriesDate = MAR31,
        isException = true,
    )

    private fun seriesNRow() = RecurringSeriesEntity(
        title = N_TITLE,
        amount = 12_000,
        type = TransactionType.EXPENSE,
        categoryId = c1.id,
        accountId = accA.id,
        frequency = RecurrenceFrequency.YEARLY,
        interval = 10,
        startDate = N_START,
        endDate = null,
        maxOccurrences = null,
        daysOfWeek = null,
        isCancelled = false,
        note = null,
        linkedGoalId = null,
        linkedLoanId = null,
    )

    private fun n0Row(seriesN: Long) = TransactionEntity(
        title = N_TITLE,
        amount = 12_000,
        type = TransactionType.EXPENSE,
        status = TransactionStatus.PLANNED,
        kind = TransactionKind.STANDARD,
        date = N_START,
        accountId = accA.id,
        categoryId = c1.id,
        note = null,
        paidAt = null,
        seriesId = seriesN,
        seriesDate = N_START,
        isException = true,
    )

    private suspend fun insertAccount(name: String, color: Long): AccountEntity {
        val account = AccountEntity(
            name = name,
            type = AccountType.CHECKING,
            initialBalance = 100_000,
            balanceUpdatedAt = 0L,
            colorArgb = color.toInt(),
            icon = "wallet",
            archived = false,
        )
        return account.copy(id = db.accountDao().upsert(account))
    }

    private suspend fun insertCategory(name: String, type: TransactionType, icon: String, color: Long): CategoryEntity {
        val category = CategoryEntity(name = name, type = type, colorArgb = color.toInt(), icon = icon, parentCategoryId = null)
        return category.copy(id = db.categoryDao().upsert(category))
    }

    private suspend fun insertTag(name: String, color: Long): TagEntity {
        val tag = TagEntity(name = name, colorArgb = color.toInt())
        return tag.copy(id = db.tagDao().upsert(tag))
    }

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

    private fun startRef() = OccurrenceRef(startId, seriesA, JAN31)

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

    /** Virtuel attendu de la série A à [date] : valeurs de la série, PLANNED, non exception. */
    private fun vA(date: Long) = Occ(
        id = null,
        seriesId = seriesA,
        seriesDate = date,
        date = date,
        title = BASE_TITLE,
        amount = 82_000,
        type = TransactionType.EXPENSE,
        status = TransactionStatus.PLANNED,
        paidAt = null,
        isException = false,
        accountId = accA.id,
        account = accA,
        categoryId = c1.id,
        category = c1,
        note = "contrat A",
        tags = setOf(ta),
    )

    private fun vN(seriesN: Long, date: Long) = Occ(
        id = null,
        seriesId = seriesN,
        seriesDate = date,
        date = date,
        title = N_TITLE,
        amount = 12_000,
        type = TransactionType.EXPENSE,
        status = TransactionStatus.PLANNED,
        paidAt = null,
        isException = false,
        accountId = accA.id,
        account = accA,
        categoryId = c1.id,
        category = c1,
        note = null,
        tags = setOf(ta),
    )

    /** Ligne physique attendue telle que le test l'a écrite. Un compte ou une catégorie à 0 joint null. */
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
        account = listOf(accA, accB).find { it.id == row.accountId },
        categoryId = row.categoryId,
        category = listOf(c1, c2, c3).find { it.id == row.categoryId },
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
            assertEquals("$label — ID physique courant ; observé ${describe(actual)}", expected.id, id)
        }
        assertEquals("$label — tous les champs de l'occurrence", expected, seen(actual))
    }

    private fun assertOccs(label: String, expected: List<Occ>, actual: List<TransactionWithRelations>?) {
        assertNotNull("$label — liste attendue, observé null", actual)
        assertEquals("$label — cardinalité exacte ; observé : ${describe(actual!!)}", expected.size, actual.size)
        assertEquals(
            "$label — slots (seriesId, seriesDate, date affichée), dans l'ordre chronologique",
            expected.map { Triple(it.seriesId, it.seriesDate?.let(::text), text(it.date)) },
            actual.map { Triple(it.transaction.seriesId, it.transaction.seriesDate?.let(::text), text(it.transaction.date)) },
        )
        expected.zip(actual).forEachIndexed { index, (e, a) -> assertOcc("$label [${index + 1}]", e, a) }
        val virtualIds = actual.map { it.transaction.id }.filter { it < 0 }
        assertEquals("$label — I-2 : IDs virtuels distincts : $virtualIds", virtualIds.size, virtualIds.toSet().size)
    }

    /** Chaque jour du mois, premier et dernier compris : nombre exact, puis lignes exactes. */
    private fun assertMonth(label: String, month: YearMonth, expected: Map<Int, List<Occ>>, actual: MonthOccurrences) {
        assertEquals("$label — mois rendu", month, actual.month)
        val days = 1..month.lengthOfMonth()
        assertEquals(
            "$label — nombre d'occurrences par jour ; observé : ${describe(actual)}",
            days.associateWith { expected[it].orEmpty().size },
            days.associateWith { actual.byDay[month.atDay(it)].orEmpty().size },
        )
        assertEquals(
            "$label — aucun jour hors du mois demandé",
            emptySet<LocalDate>(),
            actual.byDay.keys.filterNot { YearMonth.from(it) == month }.toSet(),
        )
        expected.forEach { (day, occs) -> assertOccs("$label — jour $day", occs, actual.byDay[month.atDay(day)]) }
    }

    private suspend fun assertUpcomingFromStart(label: String, expected: List<Occ>) {
        val reference = snapshot()
        val t0 = read(label, "T0") { detail.getById(startId) }!!
        val upcoming = awaitFirst(label, "${expected.size} échéance(s)", occurrences.observeUpcoming(t0))
        assertOccs("$label / CA-01", expected, upcoming)
        assertNoWrite(label, reference)
    }

    private suspend fun captureFebruaryVirtual(label: String): TransactionWithRelations {
        val february = awaitFirst(label, "février 2026", occurrences.observeMonth(seriesA, FEBRUARY, PARIS))
        val captured = february.byDay[LocalDate.of(2026, 2, 28)].orEmpty()
        assertEquals("$label — préparation : une seule occurrence le 28 février ; observé : ${describe(february)}", 1, captured.size)
        assertOcc("$label — préparation : V(A, 28 février) capturé", vA(FEB28), captured.single())
        return captured.single()
    }

    private suspend fun freshReads(label: String): Triple<List<TransactionWithRelations>?, MonthOccurrences, MonthOccurrences> {
        val t0 = read(label, "T0") { detail.getById(startId) }!!
        return Triple(
            awaitFirst(label, "aperçu", occurrences.observeUpcoming(t0)),
            awaitFirst(label, "février", occurrences.observeMonth(seriesA, FEBRUARY, PARIS)),
            awaitFirst(label, "mars", occurrences.observeMonth(seriesA, MARCH, PARIS)),
        )
    }

    // =============================================================================================
    // Snapshots — SELECT de contrôle, lignes supprimées comprises
    // =============================================================================================

    private fun snapshot(): Map<String, List<String>> =
        SNAPSHOT_TABLES.associate { (table, keys) -> table to rawRows("SELECT * FROM $table ORDER BY $keys") }

    private fun assertNoWrite(label: String, reference: Map<String, List<String>>) = assertEquals(
        "$label / I-1 — la consultation n'écrit rien : 7 tables identiques à la référence post-préparation",
        reference,
        snapshot(),
    )

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

    /**
     * Observation laissée ouverte. Chaque émission est conservée, dans l'ordre : aucune n'est
     * filtrée pour n'attendre qu'un état favorable.
     */
    private class Observation<T>(val name: String, scope: CoroutineScope, flow: Flow<T>) {
        private val received = Channel<Got<T>>(Channel.UNLIMITED)
        private var last: String = "aucune émission"

        private val job = scope.launch(Dispatchers.Default) { flow.collect { received.send(Got(it)) } }

        /**
         * Arrête la collecte **et attend sa fin**, avant la fermeture de la base : une requête encore
         * en vol échouerait sur la base fermée et serait imputée au cas suivant.
         */
        suspend fun stop() = job.cancelAndJoin()

        suspend fun next(label: String, expectation: String): T {
            val item = withContext(Dispatchers.Default) { withTimeoutOrNull(TIMEOUT) { received.receive() } }
                ?: throw AssertionError("$label — $name : aucune émission en $TIMEOUT ; attendu : $expectation ; dernier état : $last")
            last = item.value.toString()
            return item.value
        }

        /** Émissions déjà reçues, sans attendre. */
        fun drain(): List<T> = generateSequence { received.tryReceive().getOrNull() }.map { it.value }.toList()
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

        /**
         * Phase de R-08 : si le flux doit changer, attend le changement ; sinon relève ce qui est
         * déjà arrivé. Chaque émission relevée doit satisfaire [oracle] ; rend le dernier état.
         */
        suspend fun expect(label: String, previous: T, changes: Boolean, oracle: T.(String) -> Unit): T {
            val items = if (changes) awaitChange(label, "nouvel état de $name", previous) else drain()
            items.forEachIndexed { index, item -> item.oracle("$label / CA-07 — $name, émission ${index + 1}/${items.size}") }
            return items.lastOrNull() ?: previous
        }
    }

    // =============================================================================================
    // Lisibilité des messages
    // =============================================================================================

    private fun text(millis: Long): String = Instant.ofEpochMilli(millis).atZone(PARIS).toLocalDateTime().toString()

    private fun describe(occurrence: TransactionWithRelations): String = occurrence.transaction.let {
        "{id=${it.id}, série=${it.seriesId}, slot=${it.seriesDate?.let(::text)}, date=${text(it.date)}, " +
            "${it.title}, ${it.amount}, ${it.status}, supprimée=${it.deleted}}"
    }

    private fun describe(list: List<TransactionWithRelations>): String = list.joinToString(prefix = "[", postfix = "]") { describe(it) }

    private fun describe(month: MonthOccurrences): String =
        "${month.month} " + month.byDay.entries.joinToString(prefix = "{", postfix = "}") { (day, rows) -> "$day=${describe(rows)}" }

    private companion object {
        val PARIS: ZoneId = ZoneId.of("Europe/Paris")
        val TIMEOUT = 10.seconds

        const val BASE_TITLE = "ZZ_TC7_base"
        const val N_TITLE = "ZZ_TC7_longue"

        val FEBRUARY: YearMonth = YearMonth.of(2026, 2)
        val MARCH: YearMonth = YearMonth.of(2026, 3)

        /** Tables relevées par le snapshot, avec leur clé de tri. */
        val SNAPSHOT_TABLES = listOf(
            "transactions" to "id",
            "recurring_series" to "id",
            "transaction_tags" to "transactionId, tagId",
            "series_tags" to "seriesId, tagId",
            "accounts" to "id",
            "categories" to "id",
            "tags" to "id",
        )

        fun at(year: Int, month: Int, day: Int, hour: Int = 9): Long =
            LocalDateTime.of(year, month, day, hour, 0).atZone(PARIS).toInstant().toEpochMilli()

        fun endOf(year: Int, month: Int, day: Int): Long =
            LocalDate.of(year, month, day).atTime(23, 59, 59, 999_000_000).atZone(PARIS).toInstant().toEpochMilli()

        fun instant(iso: String): Long = OffsetDateTime.parse(iso).toInstant().toEpochMilli()

        // Slots de la série A (mensuelle au 31, ancrage conservé), toujours à 09:00 Paris.
        val JAN31 = at(2026, 1, 31)
        val FEB28 = at(2026, 2, 28)
        val MAR31 = at(2026, 3, 31)
        val APR30 = at(2026, 4, 30)
        val MAY31 = at(2026, 5, 31)
        val JUN30 = at(2026, 6, 30)

        val MAR02 = at(2026, 3, 2)
        val MAR02_10 = at(2026, 3, 2, hour = 10)
        val MAR15 = at(2026, 3, 15)

        val END_JAN31 = endOf(2026, 1, 31)
        val END_FEB28 = endOf(2026, 2, 28)
        val END_MAR31 = endOf(2026, 3, 31)

        val N_START = at(2026, 2, 10)
        val N_2036 = at(2036, 2, 10)
        val N_2046 = at(2046, 2, 10)
        val N_2056 = at(2056, 2, 10)
    }
}

package com.lop.budget.domain.usecase

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import app.cash.turbine.testIn
import app.cash.turbine.turbineScope
import com.lop.budget.data.local.LopDatabase
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.CategoryEntity
import com.lop.budget.data.local.entity.DebtEntity
import com.lop.budget.data.local.entity.GoalEntity
import com.lop.budget.data.local.entity.RecurringSeriesEntity
import com.lop.budget.data.local.entity.SeriesTagCrossRef
import com.lop.budget.data.local.entity.TagEntity
import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.data.repository.AccountRepository
import com.lop.budget.data.repository.CategoryRepository
import com.lop.budget.data.repository.TransactionRepository
import com.lop.budget.domain.model.AccountType
import com.lop.budget.domain.model.RecurrenceFrequency
import com.lop.budget.domain.model.TransactionKind
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import java.util.TimeZone
import kotlin.time.Duration.Companion.seconds

/**
 * TC-86 — Occurrences réellement affichables sur une période (US LOP-49).
 *
 * ## Niveau
 * Intégration applicative de bout en bout, hors UI : vrai `ObserveTransactionsUseCase`, vrais
 * repositories, vrais DAO, vrai `RecurrenceEngine`, vraie base Room en mémoire (Robolectric,
 * SDK 33, application Android neutre — ni seeder ni services). Aucun mock, aucun spy, aucun
 * `flowOf` préparé, aucune base ni DAO simplifiés.
 *
 * ## Chaîne réellement exercée
 * `ObserveTransactionsUseCase.invoke(start, end)`
 *   → `TransactionRepository.observeBetween` / `observeActiveSeries` /
 *     `observeOccupiedSeriesSlots` / `observeAllSeriesTags`
 *   → `AccountRepository.observeAll`, `CategoryRepository.observeAll`
 *   → `TransactionDao` / `RecurringSeriesDao` → Room (SQLite natif Robolectric)
 *   → `mergeRealAndVirtual` / `visibleOccurrencesOf` → `RecurrenceEngine.generateOccurrences`
 *
 * ## Correspondance cas → CA / invariant → fonction de production
 * ```
 * L-01     CA-02, CA-03, CA-09, CA-13   observeBetween + generateOccurrences + mergeRealAndVirtual
 * L-02     CA-04, CA-07, I-3            observeOccupiedSeriesSlots + visibleOccurrencesOf
 * L-03     CA-04, CA-07, I-3            idem, exception déplacée hors fenêtre
 * L-04     CA-07, I-3                   idem, exception déplacée sur un autre slot de la série
 * L-05     CA-02, CA-07, I-3            masquage cloisonné par série
 * L-06     CA-08, CA-10, I-5            observeBetween (deleted = 0) + slot occupé par tombstone
 * L-07     CA-09, CA-12                 observeActiveSeries (isCancelled = 0)
 * L-08     CA-02, I-5                   bornes de génération vs exception persistée visible
 * L-09     CA-03, CA-05, CA-13          réactivité des flux Room sur observation ouverte
 * L-10a-d  CA-02, CA-03, CA-05          bornes inclusives, chemin Room + génération virtuelle
 * (tous)   CA-05, I-1                   aucune écriture provoquée par une lecture
 * ```
 *
 * ## Hypothèses levées (le ticket ne les tranche pas ; validées le 8 septembre 2026)
 * 1. Le tag « Exception » est créé avec les autres tags du JDD : la fiche le pose sur la ligne E
 *    (« le seul tag Exception ») mais l'omet de la liste des tags à créer. Il est le discriminant
 *    qui rend L-09 §4 assertable. `isException` (colonne) et le tag « Exception » sont deux choses
 *    distinctes, et le ticket demande les deux.
 * 2. L-06 est joué en séquence sur la **même base** : phase 1 tombstone seul, phase 2 tombstone +
 *    P soft-deleted. Le second temps prouve en plus qu'une suppression en cours de vie de la base
 *    ne ressuscite aucun virtuel.
 * 3. `endDate` de L-08 = 2026-01-31 23:59:59.999, par cohérence avec la convention de fin de série
 *    du JDD. Le moteur coupe sur `slot > endDate` : le résultat est insensible à l'heure retenue,
 *    la valeur est fixée pour le déterminisme.
 * 4. Oracle des IDs virtuels : signe négatif, stabilité entre lectures (y compris sur fenêtres
 *    chevauchantes) et unicité entre slots — soit exactement l'énoncé de CA-03. Jamais de valeur
 *    littérale (elle dérive d'un `id` Room `autoGenerate`, inconnu à l'écriture) et jamais d'appel
 *    à `calculateVirtualId`, qui produirait un oracle tautologique.
 * 5. Aucun ordre n'est imposé entre deux lignes de même date : la fiche l'exclut pour L-05, et
 *    l'ordre rendu par la production à date égale (réelles avant virtuelles, par stabilité de
 *    `sortedBy`) n'est spécifié par aucun CA. Oracle = ensemble exact + chronologie globale.
 *
 * ## Anomalies connues
 * - **ANO L-04 / CA-07 / I-3 — rouge légitime attendu.** `observeOccupiedSeriesSlots` sélectionne
 *   les slots par `seriesDate BETWEEN :start AND :end` (TransactionDao.kt:214) alors que le
 *   masquage compare la **date d'affichage** du virtuel (`(series.id to it.date) !in occupiedSlots`,
 *   ObserveTransactionsUseCase.kt:88). Une exception dont le `seriesDate` est hors de la fenêtre
 *   observée n'est donc pas remontée, et le virtuel situé à sa date d'affichage n'est pas masqué.
 *   I-3 exige le masquage « même `seriesDate` **ou** même `date` ». L'oracle n'est pas assoupli.
 *
 * ## Hors périmètre (ne pas revendiquer couvert par ce fichier)
 * - CA-01 et CA-06 : écritures de création et de matérialisation. Les écritures faites ici ne
 *   servent qu'à préparer ou faire évoluer l'état observé.
 * - CA-11 : grille calendaire du moteur — couvert par TC-84 et TC-85.
 * - CA-14 : `ObserveTransactionUseCase` / `getById` (lecture individuelle).
 * - CA-13, volet « aucun calcul de récurrence dans les consommateurs » : un test de résultat ne
 *   prouve pas l'absence de logique dupliquée. Porté par `RecurrenceCentralizationTest`.
 * - Parcours UI, Maestro, portées d'édition SINGLE / FUTURE / ALL.
 *
 * ## Exécution
 * `./gradlew :app:testDebugUnitTest --tests "*ObserveTransactionsRoomTest"`
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ObserveTransactionsRoomTest {

    // --- Harnais ------------------------------------------------------------------------------

    private lateinit var db: LopDatabase
    private lateinit var transactionRepo: TransactionRepository
    private lateinit var accountRepo: AccountRepository
    private lateinit var categoryRepo: CategoryRepository
    private lateinit var useCase: ObserveTransactionsUseCase

    private lateinit var defaultTimeZone: TimeZone
    private lateinit var defaultLocale: Locale

    @Before
    fun setUp() {
        defaultTimeZone = TimeZone.getDefault()
        defaultLocale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(ZONE_ID))
        Locale.setDefault(Locale.FRANCE)

        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Application>(),
            LopDatabase::class.java,
        ).allowMainThreadQueries().build()

        transactionRepo = TransactionRepository(db.transactionDao(), db.recurringSeriesDao())
        accountRepo = AccountRepository(db.accountDao())
        categoryRepo = CategoryRepository(db.categoryDao())
        useCase = ObserveTransactionsUseCase(transactionRepo, accountRepo, categoryRepo)
    }

    @After
    fun tearDown() {
        // Les collectes de L-09 vivent dans le `backgroundScope` de `runTest`, annulé à la fin du
        // corps de test, donc avant cette fermeture.
        db.close()
        TimeZone.setDefault(defaultTimeZone)
        Locale.setDefault(defaultLocale)
    }

    // --- Cas nominaux -------------------------------------------------------------------------

    /**
     * L-01 — Given une base A/B/P sans exception, When on observe janvier puis février,
     * Then chaque période rend exactement ses occurrences, chacune portant les valeurs de sa
     * propre série, et les IDs virtuels sont négatifs, stables et distincts (CA-02, CA-03, CA-09).
     */
    @Test
    fun `L-01 - deux periodes consecutives sans exception rendent exactement leurs occurrences`() =
        runTest {
            seedBase()
            val before = snapshot()

            val january = observe(JANUARY_START, JANUARY_END)
            assertContent(
                "L-01 janvier (CA-02/CA-09)",
                listOf(virtualOfA(JAN_10), punctualP(), virtualOfB(JAN_20)),
                january,
            )

            val february = observe(FEBRUARY_START, FEBRUARY_END)
            assertContent(
                "L-01 février (CA-02)",
                listOf(virtualOfA(FEB_10), virtualOfB(FEB_20)),
                february,
            )

            // CA-03 : fenêtre chevauchant les deux mois, les IDs des mêmes slots ne doivent pas bouger.
            val overlapping = observe(JAN_10, FEB_20)
            assertContent(
                "L-01 fenêtre chevauchante (CA-02)",
                listOf(virtualOfA(JAN_10), punctualP(), virtualOfB(JAN_20), virtualOfA(FEB_10), virtualOfB(FEB_20)),
                overlapping,
            )

            val januarySlots = listOf(seriesAId to JAN_10, seriesBId to JAN_20)
            val februarySlots = listOf(seriesAId to FEB_10, seriesBId to FEB_20)
            val slots = januarySlots + februarySlots
            val firstRead = januarySlots.map { (series, date) -> virtualIdOf(january, series, date) } +
                februarySlots.map { (series, date) -> virtualIdOf(february, series, date) }
            val secondRead = slots.map { (series, date) -> virtualIdOf(overlapping, series, date) }

            firstRead.forEachIndexed { index, id ->
                assertTrue("CA-03/I-2 : l'ID virtuel du slot ${slots[index]} doit être négatif (obtenu $id)", id < 0)
            }
            assertEquals(
                "CA-03 : le même slot doit conserver son ID entre deux lectures, y compris sur des fenêtres qui se chevauchent",
                firstRead,
                secondRead,
            )
            assertEquals(
                "CA-03 : les IDs virtuels doivent être distincts entre slots",
                firstRead.size,
                firstRead.toSet().size,
            )

            assertNoWrite("L-01", before)
        }

    /**
     * L-02 — Given une exception A sur le slot du 10 janvier non déplacée, When on observe janvier
     * puis février, Then l'exception remplace son virtuel en janvier et février reste intact
     * (CA-04, CA-07, I-3).
     */
    @Test
    fun `L-02 - une exception non deplacee remplace le virtuel de son slot`() = runTest {
        seedBase()
        insertExceptionOfA(slot = JAN_10, displayDate = JAN_10)
        val before = snapshot()

        val january = observe(JANUARY_START, JANUARY_END)
        assertContent(
            "L-02 janvier (CA-07/I-3)",
            listOf(exceptionOfA(slot = JAN_10, displayDate = JAN_10), punctualP(), virtualOfB(JAN_20)),
            january,
        )
        assertTrue(
            "CA-06/I-2 : l'exception matérialisée doit porter un ID positif réellement alloué par Room",
            january.single { it.transaction.isException }.transaction.id > 0,
        )

        val february = observe(FEBRUARY_START, FEBRUARY_END)
        assertContent(
            "L-02 février (CA-02) : A reste à 820.0 et conserve les tags de sa série",
            listOf(virtualOfA(FEB_10), virtualOfB(FEB_20)),
            february,
        )

        assertNoWrite("L-02", before)
    }

    /**
     * L-03 — Given une exception A du slot du 10 janvier affichée le 12 février, When on observe
     * janvier puis février puis à nouveau janvier, Then le virtuel d'origine ne réapparaît jamais
     * bien que l'exception soit hors de la fenêtre de janvier (CA-07, I-3).
     */
    @Test
    fun `L-03 - une exception deplacee hors fenetre ne ressuscite pas son virtuel d'origine`() =
        runTest {
            seedBase()
            insertExceptionOfA(slot = JAN_10, displayDate = FEB_12)
            val before = snapshot()

            val january = observe(JANUARY_START, JANUARY_END)
            assertContent(
                "L-03 janvier (CA-07/I-3) : aucun virtuel ressuscité au 10 janvier",
                listOf(punctualP(), virtualOfB(JAN_20)),
                january,
            )

            val february = observe(FEBRUARY_START, FEBRUARY_END)
            assertContent(
                "L-03 février (CA-04) : l'exception conserve son seriesDate du 10 janvier",
                listOf(virtualOfA(FEB_10), exceptionOfA(slot = JAN_10, displayDate = FEB_12), virtualOfB(FEB_20)),
                february,
            )

            val januaryAgain = observe(JANUARY_START, JANUARY_END)
            assertContent(
                "L-03 relecture de janvier (CA-07) : résultat identique",
                listOf(punctualP(), virtualOfB(JAN_20)),
                januaryAgain,
            )

            assertNoWrite("L-03", before)
        }

    /**
     * L-04 — Given une exception A du slot du 10 janvier affichée exactement le 10 février,
     * When on observe février, Then le virtuel du 10 février est masqué par la règle de fusion sur
     * la date d'affichage de la même série (CA-07, I-3).
     *
     * Rouge légitime attendu : voir l'ANO L-04 en tête de fichier. L'oracle reste celui de la
     * spécification.
     */
    @Test
    fun `L-04 - une exception deplacee sur un autre slot de la serie masque aussi ce slot`() =
        runTest {
            seedBase()
            insertExceptionOfA(slot = JAN_10, displayDate = FEB_10)
            val before = snapshot()

            val january = observe(JANUARY_START, JANUARY_END)
            assertContent(
                "L-04 janvier (CA-07/I-3)",
                listOf(punctualP(), virtualOfB(JAN_20)),
                january,
            )

            val february = observe(FEBRUARY_START, FEBRUARY_END)
            assertContent(
                "L-04 février (CA-07/I-3) : le virtuel A du 10 février est masqué par l'exception " +
                    "affichée à la même date pour la même série — un seul slot A visible",
                listOf(exceptionOfA(slot = JAN_10, displayDate = FEB_10), virtualOfB(FEB_20)),
                february,
            )

            assertNoWrite("L-04", before)
        }

    /**
     * L-05 — Given B déplacée au 10 janvier et une exception A sur ce même instant, When on observe
     * janvier, Then les deux séries coexistent : le masquage est cloisonné par série (CA-07, I-3).
     */
    @Test
    fun `L-05 - une exception ne masque jamais le virtuel d'une autre serie a la meme date`() =
        runTest {
            seedBase()
            val seriesB = requireNotNull(transactionRepo.getSeriesById(seriesBId))
            transactionRepo.updateSeries(seriesB.copy(startDate = JAN_10))
            insertExceptionOfA(slot = JAN_10, displayDate = JAN_10)
            val before = snapshot()

            val january = observe(JANUARY_START, JANUARY_END)
            assertContent(
                "L-05 janvier (CA-07/I-3) : E(A) et V(B) coexistent au 10 janvier",
                listOf(
                    exceptionOfA(slot = JAN_10, displayDate = JAN_10),
                    virtualOfB(JAN_10),
                    punctualP(),
                ),
                january,
            )

            assertNoWrite("L-05", before)
        }

    // --- Suppressions -------------------------------------------------------------------------

    /**
     * L-06 — Given un tombstone A sur le slot du 10 janvier, When on observe, Then le slot reste
     * masqué sans régénérer son virtuel ; puis, P soft-deleté sur la même base, janvier ne contient
     * plus que le virtuel de B (CA-08, CA-10, I-5).
     */
    @Test
    fun `L-06 - un tombstone masque son slot sans le regenerer, et un ponctuel supprime disparait`() =
        runTest {
            seedBase()
            val tombstoneId = insertExceptionOfA(slot = JAN_10, displayDate = JAN_10, deleted = true)
            val beforePhase1 = snapshot()

            // Phase 1 : tombstone seul.
            assertContent(
                "L-06 phase 1, janvier (CA-08/I-5) : ni réel supprimé, ni virtuel A régénéré",
                listOf(punctualP(), virtualOfB(JAN_20)),
                observe(JANUARY_START, JANUARY_END),
            )
            assertContent(
                "L-06 phase 1, février (CA-08) : les autres occurrences restent disponibles",
                listOf(virtualOfA(FEB_10), virtualOfB(FEB_20)),
                observe(FEBRUARY_START, FEBRUARY_END),
            )
            assertNoWrite("L-06 phase 1", beforePhase1)
            assertEquals(
                "I-5 : le tombstone doit rester réellement présent en base après les lectures",
                1,
                rawRows(
                    "SELECT id FROM transactions WHERE id = $tombstoneId AND deleted = 1",
                ).size,
            )

            // Phase 2 : P également soft-deleté, sur la même base.
            transactionRepo.softDeleteTransaction(punctualId)
            val beforePhase2 = snapshot()

            assertContent(
                "L-06 phase 2, janvier (CA-10) : le ponctuel supprimé est exclu, rien n'est ressuscité",
                listOf(virtualOfB(JAN_20)),
                observe(JANUARY_START, JANUARY_END),
            )
            assertContent(
                "L-06 phase 2, février (CA-10) : février est inchangé",
                listOf(virtualOfA(FEB_10), virtualOfB(FEB_20)),
                observe(FEBRUARY_START, FEBRUARY_END),
            )
            assertNoWrite("L-06 phase 2", beforePhase2)
        }

    /**
     * L-07 — Given la série A annulée et son exception payée conservée au 10 janvier, When on
     * observe, Then plus aucun virtuel A n'est produit mais l'exception persistée reste visible,
     * et B comme P sont intacts (CA-09, CA-12).
     */
    @Test
    fun `L-07 - une serie annulee ne produit plus de virtuel mais son exception reste visible`() =
        runTest {
            seedBase()
            insertExceptionOfA(slot = JAN_10, displayDate = JAN_10)
            transactionRepo.updateSeriesCancelled(seriesAId, true)
            val before = snapshot()

            assertContent(
                "L-07 janvier (CA-12) : aucun virtuel A, l'exception persistée survit",
                listOf(exceptionOfA(slot = JAN_10, displayDate = JAN_10), punctualP(), virtualOfB(JAN_20)),
                observe(JANUARY_START, JANUARY_END),
            )
            assertContent(
                "L-07 février (CA-12) : l'annulation n'affecte pas les autres séries",
                listOf(virtualOfB(FEB_20)),
                observe(FEBRUARY_START, FEBRUARY_END),
            )

            assertNoWrite("L-07", before)
        }

    /**
     * L-08 — Given A terminée au 31 janvier mais porteuse d'une exception sur le slot du 10 février
     * affichée le 12, When on observe février, Then l'exception reste visible sans qu'aucun virtuel
     * ne soit généré : la borne limite les virtuels, pas les exceptions persistées (CA-02, I-5).
     */
    @Test
    fun `L-08 - la borne de fin de serie ne fait pas disparaitre une exception encore visible`() =
        runTest {
            seedBase()
            val seriesA = requireNotNull(transactionRepo.getSeriesById(seriesAId))
            transactionRepo.updateSeries(seriesA.copy(endDate = JANUARY_END))
            insertExceptionOfA(slot = FEB_10, displayDate = FEB_12)
            val before = snapshot()

            assertContent(
                "L-08 février (I-5) : E(A) visible, aucun virtuel A au-delà de la borne",
                listOf(exceptionOfA(slot = FEB_10, displayDate = FEB_12), virtualOfB(FEB_20)),
                observe(FEBRUARY_START, FEBRUARY_END),
            )

            assertNoWrite("L-08", before)
        }

    // --- Réactivité ---------------------------------------------------------------------------

    /**
     * L-09 — Given deux observations ouvertes sur janvier et février, When on écrit successivement
     * dans Room (exception, montant de série, tags de série, suppression), Then chaque observation
     * s'actualise sans réabonnement et l'état métier attendu est atteint à chaque étape (CA-13,
     * CA-03, CA-05).
     *
     * Le nombre d'émissions n'est jamais figé — `combine` peut réémettre à l'identique. L'unicité
     * des slots est assertée sur **chaque** émission traversée : aucune émission fautive ne peut
     * être silencieusement filtrée.
     */
    @Test
    fun `L-09 - les ecritures Room actualisent les observations ouvertes sans reabonnement`() =
        runTest {
            seedBase()
            val revisedTagId = db.tagDao().upsert(TagEntity(name = "Révisé", colorArgb = 0xFF00BCD4.toInt()))

            // `testIn` exige un TurbineContext : les deux observations restent ouvertes en parallèle
            // pendant toute la séquence d'écritures, ce que `test { }` (une seule collecte à la fois)
            // ne permet pas. Le timeout est porté par le scope et vaut pour les deux turbines.
            turbineScope(timeout = TIMEOUT) {
                val january = useCase(JANUARY_START, JANUARY_END).testIn(backgroundScope)
                val february = useCase(FEBRUARY_START, FEBRUARY_END).testIn(backgroundScope)

                // 1 · État initial, vérifié avant toute écriture.
                val beforeAnyWrite = snapshot()
                val januaryBase = listOf(virtualOfA(JAN_10), punctualP(), virtualOfB(JAN_20))
                val februaryBase = listOf(virtualOfA(FEB_10), virtualOfB(FEB_20))
                assertContent("L-09 §1 janvier (CA-13)", januaryBase, january.awaitState("L-09 §1 janvier", januaryBase))
                val februaryInitial = february.awaitState("L-09 §1 février", februaryBase)
                assertContent("L-09 §1 février (CA-13)", februaryBase, februaryInitial)
                val virtualIdOfAInFebruary = virtualIdOf(februaryInitial, seriesAId, FEB_10)
                assertNoWrite("L-09 §1", beforeAnyWrite)

                // 2 · Insertion de l'exception : janvier s'actualise seul.
                insertExceptionOfA(slot = JAN_10, displayDate = JAN_10)
                val afterStep2 = snapshot()
                val januaryWithException =
                    listOf(exceptionOfA(slot = JAN_10, displayDate = JAN_10), punctualP(), virtualOfB(JAN_20))
                assertContent(
                    "L-09 §2 janvier (CA-13) : l'exception apparaît sans réabonnement",
                    januaryWithException,
                    january.awaitState("L-09 §2 janvier", januaryWithException),
                )
                assertNoWrite("L-09 §2", afterStep2)

                // 3 · Modification du seul montant de A : février suit, l'exception de janvier ne bouge pas.
                val seriesA = requireNotNull(transactionRepo.getSeriesById(seriesAId))
                transactionRepo.updateSeries(seriesA.copy(amount = 850.0))
                val afterStep3 = snapshot()
                val februaryRepriced = listOf(virtualOfA(FEB_10, amount = 850.0), virtualOfB(FEB_20))
                val februaryAfterAmount = february.awaitState("L-09 §3 février", februaryRepriced)
                assertContent("L-09 §3 février (CA-13)", februaryRepriced, februaryAfterAmount)
                assertContent(
                    "L-09 §3 janvier (I-2) : l'exception persistée reste à 900.0",
                    januaryWithException,
                    january.awaitState("L-09 §3 janvier", januaryWithException),
                )
                assertEquals(
                    "CA-03 : changer le montant d'une série ne doit pas changer l'ID de ses slots virtuels",
                    virtualIdOfAInFebruary,
                    virtualIdOf(februaryAfterAmount, seriesAId, FEB_10),
                )
                assertNoWrite("L-09 §3", afterStep3)

                // 4 · Remplacement des seuls liens series_tags de A, sans réécrire la série.
                db.recurringSeriesDao().clearSeriesTags(seriesAId)
                db.recurringSeriesDao().addSeriesTagCrossRef(SeriesTagCrossRef(seriesAId, revisedTagId))
                val afterStep4 = snapshot()
                val februaryRetagged =
                    listOf(virtualOfA(FEB_10, amount = 850.0, tags = listOf("Révisé")), virtualOfB(FEB_20))
                assertContent(
                    "L-09 §4 février (CA-05) : le virtuel porte exactement Révisé",
                    februaryRetagged,
                    february.awaitState("L-09 §4 février", februaryRetagged),
                )
                assertContent(
                    "L-09 §4 janvier (CA-05) : E conserve exactement Exception, B et P sont inchangés",
                    januaryWithException,
                    january.awaitState("L-09 §4 janvier", januaryWithException),
                )
                assertNoWrite("L-09 §4", afterStep4)

                // 5 · Soft-delete de l'exception : le slot reste masqué, aucun virtuel ne repousse.
                transactionRepo.softDeleteTransaction(rawIdOfExceptionOfA(slot = JAN_10))
                val afterStep5 = snapshot()
                val januaryAfterDelete = listOf(punctualP(), virtualOfB(JAN_20))
                assertContent(
                    "L-09 §5 janvier (I-5) : aucun virtuel A au slot supprimé",
                    januaryAfterDelete,
                    january.awaitState("L-09 §5 janvier", januaryAfterDelete),
                )
                assertContent(
                    "L-09 §5 février (CA-08) : février conserve ses deux virtuels",
                    februaryRetagged,
                    february.awaitState("L-09 §5 février", februaryRetagged),
                )
                assertNoWrite("L-09 §5", afterStep5)

                // 6 · Annulation propre des collectes ; le use case n'a jamais été reconstruit.
                january.cancelAndIgnoreRemainingEvents()
                february.cancelAndIgnoreRemainingEvents()
            }
        }

    // --- Bornes -------------------------------------------------------------------------------

    /**
     * L-10a — Given la base initiale, When on observe du 10 janvier 09:00 au 15 janvier 09:00,
     * Then la borne de début (virtuelle) et la borne de fin (physique) sont incluses (CA-02).
     */
    @Test
    fun `L-10a - bornes incluses, debut virtuel et fin physique`() = runTest {
        seedBase()
        val before = snapshot()
        assertContent(
            "L-10a (CA-02) : B au 20 est hors fenêtre",
            listOf(virtualOfA(JAN_10), punctualP()),
            observe(JAN_10, JAN_15),
        )
        assertNoWrite("L-10a", before)
    }

    /**
     * L-10b — Given la base initiale, When on observe du 15 janvier 09:00 au 20 janvier 09:00,
     * Then la borne de début (physique) et la borne de fin (virtuelle) sont incluses (CA-02).
     */
    @Test
    fun `L-10b - bornes incluses, debut physique et fin virtuelle`() = runTest {
        seedBase()
        val before = snapshot()
        assertContent(
            "L-10b (CA-02) : A au 10 est hors fenêtre",
            listOf(punctualP(), virtualOfB(JAN_20)),
            observe(JAN_15, JAN_20),
        )
        assertNoWrite("L-10b", before)
    }

    /**
     * L-10c — Given la base initiale, When la fenêtre est réduite à l'instant du 15 janvier 09:00,
     * Then seule la transaction physique de cet instant est rendue, avec son ID positif (CA-02).
     */
    @Test
    fun `L-10c - fenetre reduite a un instant sur une transaction physique`() = runTest {
        seedBase()
        val before = snapshot()
        val result = observe(JAN_15, JAN_15)
        assertContent("L-10c (CA-02)", listOf(punctualP()), result)
        assertEquals(
            "CA-02/I-2 : la ligne rendue doit être la transaction physique P, avec son ID réel",
            punctualId,
            result.single().transaction.id,
        )
        assertNoWrite("L-10c", before)
    }

    /**
     * L-10d — Given la base initiale, When la fenêtre est réduite à l'instant du 10 janvier 09:00,
     * Then seule l'occurrence virtuelle de cet instant est rendue, avec un ID négatif (CA-02, CA-03).
     */
    @Test
    fun `L-10d - fenetre reduite a un instant sur une occurrence virtuelle`() = runTest {
        seedBase()
        val before = snapshot()
        val result = observe(JAN_10, JAN_10)
        assertContent("L-10d (CA-02)", listOf(virtualOfA(JAN_10)), result)
        assertTrue(
            "CA-03/I-2 : l'occurrence rendue doit être virtuelle, donc porter un ID négatif",
            result.single().transaction.id < 0,
        )
        assertNoWrite("L-10d", before)
    }

    // ==========================================================================================
    // Jeu de données
    // ==========================================================================================

    private var accountAId = 0L
    private var accountBId = 0L
    private var categoryLogementId = 0L
    private var categoryCoursesId = 0L
    private var categoryRevenusId = 0L
    private var tagFixeId = 0L
    private var tagLogementId = 0L
    private var tagRevenuId = 0L
    private var tagCoursesId = 0L
    private var tagExceptionId = 0L
    private var goalId = 0L
    private var debtId = 0L
    private var seriesAId = 0L
    private var seriesBId = 0L
    private var punctualId = 0L

    /**
     * Base initiale imposée par le ticket : 2 séries, 1 transaction physique P, aucune exception.
     * Les six occurrences virtuelles de janvier à mars ne sont jamais pré-insérées.
     */
    private suspend fun seedBase() {
        accountAId = db.accountDao().upsert(account("Compte courant test", 0xFF2196F3.toInt()))
        accountBId = db.accountDao().upsert(account("Compte contrôle", 0xFF9C27B0.toInt()))

        categoryLogementId = db.categoryDao()
            .upsert(category("Logement", TransactionType.EXPENSE, 0xFF4CAF50.toInt(), "home"))
        categoryCoursesId = db.categoryDao()
            .upsert(category("Courses", TransactionType.EXPENSE, 0xFFFF9800.toInt(), "cart"))
        categoryRevenusId = db.categoryDao()
            .upsert(category("Revenus", TransactionType.INCOME, 0xFF3F51B5.toInt(), "wallet"))

        tagFixeId = db.tagDao().upsert(TagEntity(name = "Fixe", colorArgb = 0xFF607D8B.toInt()))
        tagLogementId = db.tagDao().upsert(TagEntity(name = "Logement", colorArgb = 0xFF795548.toInt()))
        tagRevenuId = db.tagDao().upsert(TagEntity(name = "Revenu", colorArgb = 0xFF009688.toInt()))
        tagCoursesId = db.tagDao().upsert(TagEntity(name = "Courses", colorArgb = 0xFFE91E63.toInt()))
        // Hypothèse 1 : absent de la liste des tags du ticket, mais exigé sur la ligne E.
        tagExceptionId = db.tagDao().upsert(TagEntity(name = "Exception", colorArgb = 0xFF8BC34A.toInt()))

        // Dette et objectif créés avant les séries qui les référencent.
        debtId = db.debtDao().upsert(
            DebtEntity(
                name = "Dette contrôle",
                totalAmount = 5_000.0,
                repaidAmount = 0.0,
                colorArgb = 0xFFF44336.toInt(),
                icon = "debt",
            ),
        )
        goalId = db.goalDao().upsert(
            GoalEntity(
                name = "Objectif contrôle",
                targetAmount = 10_000.0,
                savedAmount = 0.0,
                colorArgb = 0xFFFFEB3B.toInt(),
                icon = "goal",
            ),
        )

        seriesAId = transactionRepo.saveSeriesWithTags(
            RecurringSeriesEntity(
                title = TITLE_A,
                amount = 820.0,
                type = TransactionType.EXPENSE,
                categoryId = categoryLogementId,
                accountId = accountAId,
                frequency = RecurrenceFrequency.MONTHLY,
                interval = 1,
                startDate = JAN_10,
                endDate = SERIES_END,
                maxOccurrences = null,
                daysOfWeek = null,
                isCancelled = false,
                note = NOTE_A,
                linkedGoalId = null,
                linkedDebtId = debtId,
            ),
            listOf(tagFixeId, tagLogementId),
        )

        seriesBId = transactionRepo.saveSeriesWithTags(
            RecurringSeriesEntity(
                title = TITLE_B,
                amount = 2_600.0,
                type = TransactionType.INCOME,
                categoryId = categoryRevenusId,
                accountId = accountBId,
                frequency = RecurrenceFrequency.MONTHLY,
                interval = 1,
                startDate = JAN_20,
                endDate = SERIES_END,
                maxOccurrences = null,
                daysOfWeek = null,
                isCancelled = false,
                note = null,
                linkedGoalId = goalId,
                linkedDebtId = null,
            ),
            listOf(tagRevenuId),
        )

        punctualId = transactionRepo.saveWithTags(
            TransactionEntity(
                title = TITLE_P,
                amount = 42.5,
                type = TransactionType.EXPENSE,
                status = TransactionStatus.PAID,
                kind = TransactionKind.STANDARD,
                date = JAN_15,
                accountId = accountAId,
                categoryId = categoryCoursesId,
                note = null,
                paidAt = JAN_15,
                seriesId = null,
                seriesDate = null,
                isException = false,
                linkedGoalId = null,
                linkedDebtId = null,
                deleted = false,
            ),
            listOf(tagCoursesId),
        )
    }

    private fun account(name: String, color: Int) = AccountEntity(
        name = name,
        type = AccountType.CHECKING,
        initialBalance = 1_000.0,
        balanceUpdatedAt = 0L,
        colorArgb = color,
        icon = "wallet",
        archived = false,
    )

    private fun category(name: String, type: TransactionType, color: Int, icon: String) =
        CategoryEntity(name = name, type = type, colorArgb = color, icon = icon)

    /**
     * Ligne d'exception de la série A, conforme à la recette du ticket. `id = 0` : Room alloue un
     * ID positif, on ne force jamais un ID négatif (I-2).
     */
    private suspend fun insertExceptionOfA(
        slot: Long,
        displayDate: Long,
        deleted: Boolean = false,
    ): Long = transactionRepo.saveWithTags(
        TransactionEntity(
            title = TITLE_EXCEPTION,
            amount = 900.0,
            type = TransactionType.EXPENSE,
            status = TransactionStatus.PAID,
            kind = TransactionKind.STANDARD,
            date = displayDate,
            accountId = accountAId,
            categoryId = categoryLogementId,
            note = NOTE_EXCEPTION,
            paidAt = displayDate,
            seriesId = seriesAId,
            seriesDate = slot,
            isException = true,
            linkedGoalId = null,
            linkedDebtId = debtId,
            deleted = deleted,
        ),
        listOf(tagExceptionId),
    )

    // ==========================================================================================
    // Oracles
    // ==========================================================================================

    /**
     * Projection comparable d'une ligne rendue. [kind] est dérivé du **signe** de l'ID : c'est
     * l'oracle I-2 « virtuel = négatif, exception = positif » sans jamais écrire de valeur
     * littérale ni appeler `calculateVirtualId`.
     */
    private data class Row(
        val kind: String,
        val seriesId: Long?,
        val seriesDate: Long?,
        val date: Long,
        val title: String,
        val amount: Double,
        val type: TransactionType,
        val status: TransactionStatus,
        val isException: Boolean,
        val account: String?,
        val category: String?,
        val note: String?,
        val linkedGoalId: Long?,
        val linkedDebtId: Long?,
        val tags: List<String>,
    )

    private fun TransactionWithRelations.row() = Row(
        kind = if (transaction.id < 0) "VIRTUEL" else "REEL",
        seriesId = transaction.seriesId,
        seriesDate = transaction.seriesDate,
        date = transaction.date,
        title = transaction.title,
        amount = transaction.amount,
        type = transaction.type,
        status = transaction.status,
        isException = transaction.isException,
        account = account?.name,
        category = category?.name,
        note = transaction.note,
        linkedGoalId = transaction.linkedGoalId,
        linkedDebtId = transaction.linkedDebtId,
        // Le ticket interdit de supposer un ordre contractuel des tags.
        tags = tags.map { it.name }.sorted(),
    )

    private fun virtualOfA(
        date: Long,
        amount: Double = 820.0,
        tags: List<String> = listOf("Fixe", "Logement"),
    ) = Row(
        kind = "VIRTUEL",
        seriesId = seriesAId,
        seriesDate = date,
        date = date,
        title = TITLE_A,
        amount = amount,
        type = TransactionType.EXPENSE,
        status = TransactionStatus.PLANNED,
        isException = false,
        account = "Compte courant test",
        category = "Logement",
        note = NOTE_A,
        linkedGoalId = null,
        linkedDebtId = debtId,
        tags = tags.sorted(),
    )

    private fun virtualOfB(date: Long) = Row(
        kind = "VIRTUEL",
        seriesId = seriesBId,
        seriesDate = date,
        date = date,
        title = TITLE_B,
        amount = 2_600.0,
        type = TransactionType.INCOME,
        status = TransactionStatus.PLANNED,
        isException = false,
        account = "Compte contrôle",
        category = "Revenus",
        note = null,
        linkedGoalId = goalId,
        linkedDebtId = null,
        tags = listOf("Revenu"),
    )

    private fun punctualP() = Row(
        kind = "REEL",
        seriesId = null,
        seriesDate = null,
        date = JAN_15,
        title = TITLE_P,
        amount = 42.5,
        type = TransactionType.EXPENSE,
        status = TransactionStatus.PAID,
        isException = false,
        account = "Compte courant test",
        category = "Courses",
        note = null,
        linkedGoalId = null,
        linkedDebtId = null,
        tags = listOf("Courses"),
    )

    private fun exceptionOfA(slot: Long, displayDate: Long) = Row(
        kind = "REEL",
        seriesId = seriesAId,
        seriesDate = slot,
        date = displayDate,
        title = TITLE_EXCEPTION,
        amount = 900.0,
        type = TransactionType.EXPENSE,
        status = TransactionStatus.PAID,
        isException = true,
        account = "Compte courant test",
        category = "Logement",
        note = NOTE_EXCEPTION,
        linkedGoalId = null,
        linkedDebtId = debtId,
        tags = listOf("Exception"),
    )

    /**
     * Oracle central : cardinalité exacte, ensemble exact des lignes (tous les champs nommés par le
     * ticket), chronologie globale croissante et unicité des slots. Aucun ordre n'est imposé entre
     * deux lignes de même date (hypothèse 5).
     */
    private fun assertContent(
        label: String,
        expected: List<Row>,
        actual: List<TransactionWithRelations>,
    ) {
        val actualRows = actual.map { it.row() }
        assertEquals("$label — cardinalité exacte", expected.size, actualRows.size)
        assertEquals(
            "$label — contenu exact (identité, valeurs, compte, catégorie, note, objectif/dette, tags)",
            expected.sortedWith(ROW_ORDER),
            actualRows.sortedWith(ROW_ORDER),
        )
        val dates = actual.map { it.transaction.date }
        assertEquals("$label — ordre chronologique global", dates.sorted(), dates)
        assertSlotUniqueness(actual, label)
    }

    /** I-3 / CA-07 : une seule représentation par slot `seriesId + seriesDate`. */
    private fun assertSlotUniqueness(rows: List<TransactionWithRelations>, label: String) {
        rows.mapNotNull { row ->
            val series = row.transaction.seriesId ?: return@mapNotNull null
            val slot = row.transaction.seriesDate ?: return@mapNotNull null
            series to slot
        }.groupingBy { it }.eachCount().forEach { (slot, count) ->
            assertEquals(
                "$label — CA-07/I-3 : doublon sur le slot $slot (seriesId + seriesDate)",
                1,
                count,
            )
        }
    }

    private fun virtualIdOf(rows: List<TransactionWithRelations>, seriesId: Long, date: Long): Long =
        rows.single {
            it.transaction.seriesId == seriesId && it.transaction.date == date && it.transaction.id < 0
        }.transaction.id

    private fun rawIdOfExceptionOfA(slot: Long): Long =
        rawRows("SELECT id FROM transactions WHERE seriesId = $seriesAId AND seriesDate = $slot")
            .single()
            .toLong()

    // ==========================================================================================
    // Observation
    // ==========================================================================================

    private suspend fun observe(start: Long, end: Long): List<TransactionWithRelations> {
        lateinit var emission: List<TransactionWithRelations>
        useCase(start, end).test(timeout = TIMEOUT) {
            emission = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        return emission
    }

    /**
     * Consomme les émissions jusqu'à l'état métier attendu, en assertant l'unicité des slots sur
     * **chaque** émission traversée : contrairement à un `filter`, aucune émission contenant un
     * doublon ne peut disparaître silencieusement.
     */
    private suspend fun ReceiveTurbine<List<TransactionWithRelations>>.awaitState(
        label: String,
        expected: List<Row>,
    ): List<TransactionWithRelations> {
        val target = expected.sortedWith(ROW_ORDER)
        repeat(MAX_EMISSIONS) {
            val emission = awaitItem()
            assertSlotUniqueness(emission, "$label — émission intermédiaire #$it")
            if (emission.map { row -> row.row() }.sortedWith(ROW_ORDER) == target) return emission
        }
        fail("$label — état attendu non atteint après $MAX_EMISSIONS émissions")
        error("unreachable")
    }

    // ==========================================================================================
    // Snapshots (SELECT de contrôle : inclut les tombstones, que les DAO masquent)
    // ==========================================================================================

    private data class Snapshot(
        val transactions: List<String>,
        val series: List<String>,
        val transactionTags: List<String>,
        val seriesTags: List<String>,
    )

    private fun snapshot() = Snapshot(
        transactions = rawRows(
            "SELECT id, title, amount, type, status, kind, date, paidAt, accountId, categoryId, " +
                "note, seriesId, seriesDate, isException, linkedGoalId, linkedDebtId, deleted " +
                "FROM transactions ORDER BY id",
        ),
        series = rawRows(
            "SELECT id, title, amount, type, categoryId, accountId, frequency, interval, " +
                "startDate, endDate, maxOccurrences, daysOfWeek, isCancelled, note, " +
                "linkedGoalId, linkedDebtId FROM recurring_series ORDER BY id",
        ),
        transactionTags = rawRows("SELECT transactionId, tagId FROM transaction_tags ORDER BY transactionId, tagId"),
        seriesTags = rawRows("SELECT seriesId, tagId FROM series_tags ORDER BY seriesId, tagId"),
    )

    private fun rawRows(sql: String): List<String> = buildList {
        db.query(sql, emptyArray<Any?>()).use { cursor ->
            while (cursor.moveToNext()) {
                add(
                    (0 until cursor.columnCount).joinToString("|") { column ->
                        if (cursor.isNull(column)) "null" else cursor.getString(column)
                    },
                )
            }
        }
    }

    /** CA-05 / I-1 : observer une période ne persiste ni ne modifie rien. */
    private fun assertNoWrite(label: String, before: Snapshot) {
        assertEquals("$label — CA-05/I-1 : une lecture ne doit rien écrire ni modifier", before, snapshot())
    }

    private companion object {
        const val ZONE_ID = "Europe/Paris"
        val ZONE: ZoneId = ZoneId.of(ZONE_ID)
        val TIMEOUT = 10.seconds
        const val MAX_EMISSIONS = 20

        const val TITLE_A = "Loyer A"
        const val TITLE_B = "Salaire B"
        const val TITLE_P = "Courses contrôle"
        const val TITLE_EXCEPTION = "Loyer ajusté"
        const val NOTE_A = "Contrat A"
        const val NOTE_EXCEPTION = "Ajustement A"

        /**
         * Dates et fenêtres déclarées **localement à ce fichier** : les cardinalités exactes de la
         * matrice en dépendent et ne doivent pas pouvoir être modifiées par un autre ticket via un
         * fixture partagé.
         */
        val JAN_10 = at09(2026, 1, 10)
        val JAN_15 = at09(2026, 1, 15)
        val JAN_20 = at09(2026, 1, 20)
        val FEB_10 = at09(2026, 2, 10)
        val FEB_12 = at09(2026, 2, 12)
        val FEB_20 = at09(2026, 2, 20)
        val SERIES_END = endOfMonth(2026, 3)

        val JANUARY_START = startOfMonth(2026, 1)
        val JANUARY_END = endOfMonth(2026, 1)
        val FEBRUARY_START = startOfMonth(2026, 2)
        val FEBRUARY_END = endOfMonth(2026, 2)

        /** Ordre de comparaison propre au test — jamais celui de la production. */
        val ROW_ORDER: Comparator<Row> = compareBy({ it.date }, { it.seriesId ?: -1L }, { it.kind }, { it.title })

        fun at09(year: Int, month: Int, day: Int): Long =
            LocalDate.of(year, month, day).atTime(9, 0).atZone(ZONE).toInstant().toEpochMilli()

        fun startOfMonth(year: Int, month: Int): Long =
            LocalDate.of(year, month, 1).atStartOfDay(ZONE).toInstant().toEpochMilli()

        fun endOfMonth(year: Int, month: Int): Long =
            LocalDate.of(year, month, 1)
                .with(TemporalAdjusters.lastDayOfMonth())
                .atTime(23, 59, 59, 999_000_000)
                .atZone(ZONE)
                .toInstant()
                .toEpochMilli()
    }
}

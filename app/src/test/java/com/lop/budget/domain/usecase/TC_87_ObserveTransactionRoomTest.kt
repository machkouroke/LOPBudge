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
import org.junit.Assert.assertNotEquals
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
 * TC-87 — Cohérence entre la liste et l'occurrence individuelle (US LOP-49).
 *
 * ## Niveau
 * Intégration applicative de bout en bout, hors UI : vrai `ObserveTransactionUseCase`, vrai
 * `ObserveTransactionsUseCase` comme collaborateur, vrais repositories, vrais DAO, vrai
 * `RecurrenceEngine`, vraie base Room en mémoire (Robolectric SDK 33, application Android neutre —
 * ni seeder ni services). Aucun mock, aucun fake, aucun spy, aucun `flowOf` préparé.
 *
 * ## Chaîne réellement exercée
 * `ObserveTransactionUseCase.invoke(id)` / `getById(id)`
 *   → `TransactionRepository.observeById` / `observeSlotsAt` / `observeActiveSeries` /
 *     `observeAllSeriesTags`
 *   → `AccountRepository.observeAll` / `getById`, `CategoryRepository.observeAll` / `getById`
 *   → `TransactionDao` / `RecurringSeriesDao` → Room (SQLite natif Robolectric)
 *   → `RecurrenceEngine.generateOccurrences` / `calculateVirtualId`
 *
 * `ObserveTransactionsUseCase.invoke(start, end)` sert à obtenir l'ID **effectivement fourni à une
 * liste** et à comparer les deux vues. Il ne remplace jamais les attendus explicites : chaque
 * occurrence est aussi comparée aux valeurs littérales de la fixture, sinon un même défaut présent
 * dans les deux chemins passerait inaperçu.
 *
 * ## Correspondance cas → CA / invariant → fonction de production
 * ```
 * D-01   CA-03, CA-05, CA-14, I-1      getById(négatif) + invoke(négatif) + construction du virtuel
 * D-02   CA-07, CA-14, I-3             observeById(positif) + realMatch par seriesDate
 * D-03   CA-07, CA-13, CA-14, I-3      priorité de l'exception persistée sur le virtuel
 * D-04   CA-08, CA-10, CA-14, I-5      tombstone vs régénération du virtuel de secours
 * D-05   CA-12, CA-14                  observeActiveSeries + bornes de série
 * D-06   CA-14, I-5                    bornes de génération vs exception persistée visible
 * D-07   CA-03, CA-14, I-2             résolution d'ID par série, sans « première trouvée »
 * D-08   CA-09, CA-14                  ponctuelle (seriesId null) et IDs absents
 * D-09   CA-14                          absence d'horizon calendaire supplémentaire
 * D-10   CA-05, CA-13, CA-14           actualisation des données d'affichage (tags, compte, catégorie)
 * (tous) CA-05, I-1                    aucune écriture provoquée par une lecture
 * ```
 *
 * ## Hypothèses levées (le ticket ne les tranche pas ; arrêtées le 9 septembre 2026)
 * 1. La catégorie « Courses » de la ponctuelle P n'est pas listée dans les données à créer alors que
 *    P la référence : elle est créée comme catégorie réelle distincte de la catégorie A.
 * 2. Tags nommés faute de nom dans la fiche : « Contrôle » pour la seconde série de D-07,
 *    « Assurance » pour la série lointaine de D-09. Les assertions exigent un ensemble **exact**,
 *    un tag anonyme n'est pas assertable.
 * 3. La seconde série de D-07 reçoit un compte et une catégorie **distincts** de ceux de A. Avec des
 *    parents partagés, une confusion entre séries ne serait détectable que sur le titre et le
 *    montant ; ici elle l'est sur six champs.
 * 4. D-10 §3 dit à la fois « variantes indépendantes » et « le compte **puis** la catégorie » :
 *    retenu comme deux variantes indépendantes (D-10b et D-10c), pour savoir laquelle casse.
 * 5. Champs non répétés par D-01 mais exigés par les assertions obligatoires : un virtuel porte
 *    `paidAt = null`, `isException = false`, `deleted = false`, `kind = STANDARD`.
 * 6. Toutes les occurrences sont à 09:00 et les fenêtres couvrent des mois entiers : l'application
 *    ne permet pas de choisir une heure, aucun oracle ne dépend de l'heure retenue.
 * 7. Oracle des IDs : signe et identité de slot (`seriesId + seriesDate`), jamais une valeur
 *    littérale et jamais un appel à `calculateVirtualId`, qui produirait un oracle tautologique.
 *
 * ## Anomalies attendues (analyse statique du 9 septembre 2026, à confirmer à l'exécution)
 * Les quatre défauts ci-dessous ont été identifiés par lecture du code **avant** écriture des tests.
 * Ce ne sont pas des résultats RED déjà obtenus. Les oracles ne sont pas assouplis pour les
 * contourner.
 * - **ANO — horizon calendaire de `getById`** — cible D-09.
 *   https://app.notion.com/p/3d650f34a8c58128a028c00057c7d1d0
 *   `ObserveTransactionUseCase.kt:85-86` borne la recherche d'un ID négatif à
 *   `Calendar.getInstance()` moins un an / plus deux ans. CA-14 exige une consultation « sans
 *   horizon calendaire supplémentaire », et le résultat ne doit pas dépendre du jour d'exécution.
 *   `invoke` étant amorcé par `getById` (ligne 29), l'observation hérite du même défaut.
 * - **ANO — `getById` négatif ignore l'exception persistée** — cible D-03, D-06.
 *   https://app.notion.com/p/3d650f34a8c5812ebd1eed4509dec8dd
 *   `ObserveTransactionUseCase.kt:83-91` parcourt les séries actives et régénère un virtuel sans
 *   jamais interroger `transactions`. I-3 et CA-14 imposent que l'exception prévale.
 * - **ANO — résurrection d'un slot supprimé côté détail** — cible D-04.
 *   https://app.notion.com/p/3d650f34a8c58117b183eee2fb6635b5
 *   `realMatch` est cherché dans `observeSeries` (`TransactionDao.kt:86`), qui filtre `deleted = 0` :
 *   le tombstone est invisible et le code retombe sur `generateOccurrences`. C'est le jumeau de
 *   LOP-99, corrigée côté liste (`observeForMerge` ne filtre volontairement pas `deleted`) et jamais
 *   portée sur le détail. Violation de I-5, CA-08, CA-10.
 * - **ANO — tags absents et non observés côté détail** — cible D-01, D-10.
 *   https://app.notion.com/p/3d650f34a8c581fd9230c868c1fa974a
 *   Les virtuels du détail sont construits avec `emptyList()` (lignes 46, 72, 89) et le `combine`
 *   ne s'abonne pas à `observeAllSeriesTags` (lignes 34-38, 60-64). La liste, elle, fournit les tags
 *   de série. CA-14 exige « les mêmes valeurs, compte, catégorie et tags que la liste ».
 *
 * ## Résultats (9 septembre 2026, seconde exécution)
 * **15 tests : 5 verts, 10 rouges.** Tous les rouges portent un message métier rattaché à un CA ;
 * aucun échec de montage ni de fixture. Les **quatre** ANO ci-dessus sont confirmées.
 * ```
 * VERTS   D-02, D-05a, D-05b, D-08a, D-08b
 * ROUGES  D-01, D-03, D-04, D-06, D-07, D-09a, D-09b, D-10a, D-10b, D-10c
 * ```
 * Preuves relevées :
 * ```
 * D-01, D-07   tags=[] au lieu de [Fixe, Logement]                     → ANO tags
 * D-09a, D-09b invoke(id) = null au lieu de l'occurrence                → ANO horizon calendaire
 * D-03         getById(ancien ID virtuel) rend le virtuel, pas E        → ANO getById vs exception
 * D-06         invoke(ancien ID virtuel) = null au lieu de E            → ANO getById vs exception
 * D-04         après soft-delete, l'observation ouverte rend à nouveau
 *              le VIRTUEL à 820,00 au lieu de null                      → ANO résurrection
 * D-10a        après changement de `series_tags` : « 0 émission »       → ANO tags, volet
 *              côté détail alors que la liste, elle, réémet                réactivité
 * ```
 * Lectures fines qui précisent le périmètre de chaque défaut :
 * - **D-04** est la preuve directe de la résurrection : l'émission observée après la suppression est
 *   le virtuel régénéré, tags vides, et plus rien n'est émis ensuite.
 * - **D-10a** échoue avec **zéro émission** : le détail n'est pas seulement dépourvu de tags, il
 *   n'est **pas abonné** à `series_tags`. Les deux volets de l'ANO sont donc démontrés séparément.
 * - **D-10b / D-10c** montrent `account=Compte courant renommé` dans l'état observé : le renommage
 *   se propage correctement au détail. Ces deux tests ne sont rouges **que** sur les tags ; ils
 *   passeront au vert avec la seule correction de l'ANO tags.
 * - **D-03** : l'observation ouverte bascule bien vers E et l'ID devient positif. Seul
 *   `getById(ancien ID virtuel)` échoue. Le défaut est circonscrit à la lecture ponctuelle.
 * - **D-05a / D-05b verts** : annulation de série et réduction de `endDate` retirent correctement le
 *   virtuel des trois accès, et P reste consultable.
 *
 * D-07 était pronostiqué vert : il échoue sur les tags, **pas** sur la résolution de série — chaque
 * ID résout bien sa propre série. Le pronostic était incomplet, pas le test.
 *
 * La première exécution comptait sept rouges supplémentaires en `TurbineAssertionError: No value
 * produced`, sans lien avec un CA : les synchronisations initiales réassertaient le contenu complet
 * du virtuel, jamais atteint à cause du défaut de tags, et l'attente mourait sur un délai. Corrigé
 * par [awaitSlot] et par la conversion de l'absence d'émission en échec métier dans [awaitEmission].
 *
 * ## Hors périmètre (ne pas revendiquer couvert par ce fichier)
 * - CA-01 et CA-06 : écritures de création et de matérialisation. Les écritures faites ici ne
 *   servent qu'à préparer ou faire évoluer l'état observé.
 * - CA-02, CA-11 : grille calendaire et bornes de fenêtre — couverts par TC-84, TC-85, TC-86.
 * - CA-13, volet « aucun calcul de récurrence dans les consommateurs » : un test de résultat ne
 *   prouve pas l'absence de logique dupliquée. Porté par `RecurrenceCentralizationTest`.
 * - Masquage du détail sur `seriesDate` **ou** `date` (I-3) : `realMatch` ne compare que
 *   `seriesDate` (lignes 40 et 66), jumeau non corrigé de LOP-117. Absent de la matrice D-01→D-10 du
 *   ticket, donc non testé ici ; à porter par un ticket dédié.
 * - Parcours UI, Maestro, portées d'édition SINGLE / FUTURE / ALL.
 *
 * ## Exécution
 * `./gradlew :app:testDebugUnitTest --tests "*ObserveTransactionRoomTest"`
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ObserveTransactionRoomTest {

    // --- Harnais ------------------------------------------------------------------------------

    private lateinit var db: LopDatabase
    private lateinit var transactionRepo: TransactionRepository
    private lateinit var accountRepo: AccountRepository
    private lateinit var categoryRepo: CategoryRepository
    private lateinit var useCase: ObserveTransactionUseCase
    private lateinit var listUseCase: ObserveTransactionsUseCase

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
        useCase = ObserveTransactionUseCase(transactionRepo, accountRepo, categoryRepo)
        listUseCase = ObserveTransactionsUseCase(transactionRepo, accountRepo, categoryRepo)
    }

    @After
    fun tearDown() {
        // Les collectes ouvertes vivent dans le `backgroundScope` de `runTest`, annulé à la fin du
        // corps de test, donc avant cette fermeture.
        db.close()
        TimeZone.setDefault(defaultTimeZone)
        Locale.setDefault(defaultLocale)
    }

    // ==========================================================================================
    // D-01 — Le virtuel d'une liste est consultable individuellement, à l'identique
    // ==========================================================================================

    /**
     * D-01 — Given la base A + P sans exception, When on lit le virtuel de février par `invoke` puis
     * par `getById`, Then les deux restituent la même occurrence que la liste, avec ses tags de
     * série, et aucune transaction n'est créée (CA-03, CA-05, CA-14, I-1).
     */
    @Test
    fun `D-01 - le virtuel de fevrier est consultable par invoke et getById avec les valeurs de la liste`() =
        runTest {
            seedBase()
            val before = snapshot()

            val february = observeList(FEBRUARY_START, FEBRUARY_END)
            assertListContent("D-01 — liste de février", listOf(virtualOfA(FEB_10)), february)

            val virtualFebruaryId = february.single().transaction.id
            assertTrue(
                "D-01 — I-2 : l'ID d'une occurrence virtuelle doit être négatif, obtenu $virtualFebruaryId",
                virtualFebruaryId < 0L,
            )

            val observed = observeDetail(virtualFebruaryId)
            assertDetail("D-01 — invoke(virtualFebruaryId)", virtualOfA(FEB_10), observed)

            val fetched = useCase.getById(virtualFebruaryId)
            assertDetail("D-01 — getById(virtualFebruaryId)", virtualOfA(FEB_10), fetched)

            assertEquals(
                "D-01 — CA-14 : le détail doit être identique à la ligne de liste",
                february.single().row(),
                fetched?.row(),
            )
            assertEquals(
                "D-01 — CA-14 : l'ID rendu par le détail doit être celui fourni par la liste",
                virtualFebruaryId,
                fetched?.transaction?.id,
            )

            assertNoWrite("D-01", before)
        }

    // ==========================================================================================
    // D-02 — L'exception persistée est consultable par son ID physique, avec ses propres valeurs
    // ==========================================================================================

    /**
     * D-02 — Given l'exception E préparée avant la lecture, When on lit son ID physique,
     * Then `invoke` et `getById` restituent E (900,00 payée le 12 février, slot du 10, tag TE) et
     * jamais les valeurs génériques de la série (CA-07, CA-14, I-3).
     */
    @Test
    fun `D-02 - l'exception persistee est lue avec ses propres valeurs et non celles de la serie`() =
        runTest {
            seedBase()
            val exceptionId = insertExceptionE()
            val before = snapshot()

            val february = observeList(FEBRUARY_START, FEBRUARY_END)
            assertListContent("D-02 — liste de février", listOf(exceptionE()), february)
            assertEquals(
                "D-02 — la liste doit exposer l'ID physique de E",
                exceptionId,
                february.single().transaction.id,
            )

            assertDetail("D-02 — invoke(exceptionId)", exceptionE(), observeDetail(exceptionId))
            assertDetail("D-02 — getById(exceptionId)", exceptionE(), useCase.getById(exceptionId))

            assertNoWrite("D-02", before)
        }

    // ==========================================================================================
    // D-03 — L'état persisté prime, y compris pour une observation déjà ouverte
    // ==========================================================================================

    /**
     * D-03 — Given une observation ouverte sur l'ID virtuel de février, When l'exception E est
     * insérée avec les vrais DAO, Then l'observation bascule vers E sans réabonnement, et une
     * nouvelle observation comme `getById` sur l'**ancien ID virtuel** résolvent aussi E
     * (CA-07, CA-13, CA-14, I-3).
     */
    @Test
    fun `D-03 - l'insertion d'une exception fait basculer l'ancien ID virtuel vers l'etat persiste`() =
        runTest {
            seedBase()
            val virtualFebruaryId =
                observeList(FEBRUARY_START, FEBRUARY_END).single().transaction.id

            turbineScope {
                val openDetail = useCase(virtualFebruaryId).testIn(backgroundScope)
                awaitSlot(openDetail, "D-03 — état initial de l'observation ouverte", virtualFebruaryId)

                val exceptionId = insertExceptionE()

                val switched = awaitDetail(
                    openDetail,
                    "D-03 — observation ouverte après insertion de E",
                    exceptionE(),
                )
                assertEquals(
                    "D-03 — I-2 : après matérialisation, l'observation doit porter l'ID physique",
                    exceptionId,
                    switched?.transaction?.id,
                )
                assertTrue(
                    "D-03 — I-2 : l'ID rendu doit être positif après matérialisation",
                    (switched?.transaction?.id ?: 0L) > 0L,
                )
                assertEquals(
                    "D-03 — I-2 : le slot d'origine (seriesId) doit être conservé",
                    seriesAId,
                    switched?.transaction?.seriesId,
                )
                assertEquals(
                    "D-03 — I-2 : le slot d'origine (seriesDate) doit être conservé",
                    FEB_10,
                    switched?.transaction?.seriesDate,
                )

                assertDetail(
                    "D-03 — nouvelle observation sur l'ancien ID virtuel",
                    exceptionE(),
                    observeDetail(virtualFebruaryId),
                )
                assertDetail(
                    "D-03 — getById(ancien ID virtuel)",
                    exceptionE(),
                    useCase.getById(virtualFebruaryId),
                )

                assertListContent(
                    "D-03 — liste de février : un seul représentant du slot",
                    listOf(exceptionE()),
                    observeList(FEBRUARY_START, FEBRUARY_END),
                )

                openDetail.cancelAndIgnoreRemainingEvents()
            }
        }

    // ==========================================================================================
    // D-04 — Un slot supprimé ne ressuscite jamais, par aucun des quatre accès
    // ==========================================================================================

    /**
     * D-04 — Given l'exception E de D-03 puis son soft-delete, When on lit le slot par les quatre
     * accès distincts (observation négative déjà ouverte, nouvelle observation négative, observation
     * par ID physique, les deux `getById`), Then tous rendent `null`, la liste de février est vide,
     * le tombstone reste en base et une mutation sans rapport ne ressuscite rien
     * (CA-08, CA-10, CA-14, I-5).
     */
    @Test
    fun `D-04 - un slot soft-delete reste introuvable par les quatre acces et ne ressuscite pas`() =
        runTest {
            seedBase()
            val virtualFebruaryId =
                observeList(FEBRUARY_START, FEBRUARY_END).single().transaction.id

            turbineScope {
                val openNegative = useCase(virtualFebruaryId).testIn(backgroundScope)
                awaitSlot(openNegative, "D-04 — état initial (virtuel)", virtualFebruaryId)

                val exceptionId = insertExceptionE()
                awaitSlot(openNegative, "D-04 — bascule vers E", exceptionId)

                val openPhysical = useCase(exceptionId).testIn(backgroundScope)
                awaitSlot(openPhysical, "D-04 — état initial (physique)", exceptionId)

                transactionRepo.softDeleteTransaction(exceptionId)
                val afterDelete = snapshot()

                awaitDetail(openNegative, "D-04 — observation négative déjà ouverte", null)
                awaitDetail(openPhysical, "D-04 — observation par ID physique déjà ouverte", null)
                assertDetail(
                    "D-04 — nouvelle observation négative",
                    null,
                    observeDetail(virtualFebruaryId),
                )
                assertDetail("D-04 — getById(ancien ID virtuel)", null, useCase.getById(virtualFebruaryId))
                assertDetail("D-04 — getById(ID physique)", null, useCase.getById(exceptionId))

                assertListContent(
                    "D-04 — liste de février après suppression",
                    emptyList(),
                    observeList(FEBRUARY_START, FEBRUARY_END),
                )

                assertEquals(
                    "D-04 — I-5 : le tombstone du slot doit rester en base",
                    listOf("$exceptionId|$seriesAId|$FEB_10|1"),
                    rawRows(
                        "SELECT id, seriesId, seriesDate, deleted FROM transactions " +
                            "WHERE id = $exceptionId",
                    ),
                )

                // Mutation sans rapport : elle ne doit pas rouvrir le slot supprimé.
                val account = requireNotNull(accountRepo.getById(accountAId))
                db.accountDao().upsert(account.copy(name = "Compte renommé"))

                assertDetail(
                    "D-04 — getById(ancien ID virtuel) après renommage du compte",
                    null,
                    useCase.getById(virtualFebruaryId),
                )
                assertDetail(
                    "D-04 — nouvelle observation négative après renommage du compte",
                    null,
                    observeDetail(virtualFebruaryId),
                )
                assertListContent(
                    "D-04 — liste de février après renommage du compte",
                    emptyList(),
                    observeList(FEBRUARY_START, FEBRUARY_END),
                )

                assertEquals(
                    "D-04 — CA-05/I-1 : les lectures postérieures à la suppression n'écrivent " +
                        "rien dans les tables métier",
                    afterDelete.copy(),
                    snapshot(),
                )

                openNegative.cancelAndIgnoreRemainingEvents()
                openPhysical.cancelAndIgnoreRemainingEvents()
            }
        }

    // ==========================================================================================
    // D-05 — Une série qui cesse de produire le slot rend le détail introuvable
    // ==========================================================================================

    /**
     * D-05a — Given une observation ouverte sur le virtuel de février et aucune exception, When la
     * série A est annulée, Then l'observation ouverte, une nouvelle observation et `getById`
     * rendent `null`, la liste de février est vide et P reste consultable (CA-12, CA-14).
     */
    @Test
    fun `D-05a - l'annulation de la serie rend son virtuel introuvable sans toucher au ponctuel`() =
        runTest {
            seedBase()
            val virtualFebruaryId =
                observeList(FEBRUARY_START, FEBRUARY_END).single().transaction.id

            turbineScope {
                val openDetail = useCase(virtualFebruaryId).testIn(backgroundScope)
                awaitSlot(openDetail, "D-05a — état initial", virtualFebruaryId)

                transactionRepo.updateSeriesCancelled(seriesAId, true)

                awaitDetail(openDetail, "D-05a — observation ouverte après annulation", null)
                assertDetail("D-05a — nouvelle observation", null, observeDetail(virtualFebruaryId))
                assertDetail("D-05a — getById", null, useCase.getById(virtualFebruaryId))
                assertListContent(
                    "D-05a — liste de février",
                    emptyList(),
                    observeList(FEBRUARY_START, FEBRUARY_END),
                )

                assertDetail("D-05a — P reste consultable", punctualP(), useCase.getById(punctualId))

                openDetail.cancelAndIgnoreRemainingEvents()
            }
        }

    /**
     * D-05b — Variante indépendante : même scénario que D-05a, mais la fin de la série A est ramenée
     * au 31 janvier au lieu d'une annulation (CA-12, CA-14).
     */
    @Test
    fun `D-05b - ramener la fin de serie au 31 janvier rend le virtuel de fevrier introuvable`() =
        runTest {
            seedBase()
            val virtualFebruaryId =
                observeList(FEBRUARY_START, FEBRUARY_END).single().transaction.id

            turbineScope {
                val openDetail = useCase(virtualFebruaryId).testIn(backgroundScope)
                awaitSlot(openDetail, "D-05b — état initial", virtualFebruaryId)

                shortenSeriesAToJanuary()

                awaitDetail(openDetail, "D-05b — observation ouverte après réduction de la série", null)
                assertDetail("D-05b — nouvelle observation", null, observeDetail(virtualFebruaryId))
                assertDetail("D-05b — getById", null, useCase.getById(virtualFebruaryId))
                assertListContent(
                    "D-05b — liste de février",
                    emptyList(),
                    observeList(FEBRUARY_START, FEBRUARY_END),
                )

                assertDetail("D-05b — P reste consultable", punctualP(), useCase.getById(punctualId))

                openDetail.cancelAndIgnoreRemainingEvents()
            }
        }

    // ==========================================================================================
    // D-06 — La borne de série retire le virtuel, pas l'exception persistée du même slot
    // ==========================================================================================

    /**
     * D-06 — Given l'ID virtuel de février obtenu avant préparation, quand E existe et n'est pas
     * supprimée et que la fin de A est ramenée au 31 janvier, Then E reste consultable par son ID
     * physique **et** par l'ancien ID virtuel, via `invoke` comme via `getById`, et reste dans la
     * liste de février (CA-14, I-5).
     */
    @Test
    fun `D-06 - la borne de fin de serie ne rend pas introuvable l'exception persistee du slot`() =
        runTest {
            seedBase()
            val virtualFebruaryId =
                observeList(FEBRUARY_START, FEBRUARY_END).single().transaction.id

            val exceptionId = insertExceptionE()
            shortenSeriesAToJanuary()
            val before = snapshot()

            assertListContent(
                "D-06 — liste de février",
                listOf(exceptionE()),
                observeList(FEBRUARY_START, FEBRUARY_END),
            )

            assertDetail("D-06 — invoke(ID physique)", exceptionE(), observeDetail(exceptionId))
            assertDetail("D-06 — getById(ID physique)", exceptionE(), useCase.getById(exceptionId))
            assertDetail(
                "D-06 — invoke(ancien ID virtuel)",
                exceptionE(),
                observeDetail(virtualFebruaryId),
            )
            assertDetail(
                "D-06 — getById(ancien ID virtuel)",
                exceptionE(),
                useCase.getById(virtualFebruaryId),
            )

            assertNoWrite("D-06", before)
        }

    // ==========================================================================================
    // D-07 — Chaque ID résout sa propre série
    // ==========================================================================================

    /**
     * D-07 — Given deux séries produisant un slot à la même date, When on lit séparément les deux IDs
     * de février, Then chacun résout sa propre série et ses propres valeurs, et la liste comporte
     * deux slots distincts sans ordre imposé (CA-03, CA-14, I-2).
     */
    @Test
    fun `D-07 - deux series au meme slot resolvent chacune ses propres valeurs`() = runTest {
        seedBase()
        seedSecondSeries()
        val before = snapshot()

        val february = observeList(FEBRUARY_START, FEBRUARY_END)
        assertListContent(
            "D-07 — liste de février",
            listOf(virtualOfA(FEB_10), virtualOfSecond(FEB_10)),
            february,
        )

        val idOfA = february.single { it.transaction.seriesId == seriesAId }.transaction.id
        val idOfSecond = february.single { it.transaction.seriesId == seriesCId }.transaction.id
        assertNotEquals(
            "D-07 — CA-03 : deux slots distincts doivent porter des IDs distincts",
            idOfA,
            idOfSecond,
        )

        assertDetail("D-07 — invoke(ID série A)", virtualOfA(FEB_10), observeDetail(idOfA))
        assertDetail("D-07 — getById(ID série A)", virtualOfA(FEB_10), useCase.getById(idOfA))
        assertDetail(
            "D-07 — invoke(ID seconde série)",
            virtualOfSecond(FEB_10),
            observeDetail(idOfSecond),
        )
        assertDetail(
            "D-07 — getById(ID seconde série)",
            virtualOfSecond(FEB_10),
            useCase.getById(idOfSecond),
        )

        assertNoWrite("D-07", before)
    }

    // ==========================================================================================
    // D-08 — Ponctuelle et identifiants absents
    // ==========================================================================================

    /**
     * D-08a — Given la ponctuelle P, When on la lit par son ID positif, Then elle restitue ses
     * propres valeurs et conserve `seriesId = null` (CA-09, CA-14).
     */
    @Test
    fun `D-08a - une transaction ponctuelle est lue avec ses propres valeurs et sans serie`() =
        runTest {
            seedBase()
            val before = snapshot()

            val observed = observeDetail(punctualId)
            assertDetail("D-08a — invoke(punctualId)", punctualP(), observed)
            assertEquals(
                "D-08a — CA-09/I-2 : une ponctuelle n'a pas de série",
                null,
                observed?.transaction?.seriesId,
            )

            val fetched = useCase.getById(punctualId)
            assertDetail("D-08a — getById(punctualId)", punctualP(), fetched)
            assertEquals(
                "D-08a — CA-09/I-2 : une ponctuelle n'a pas de série",
                null,
                fetched?.transaction?.seriesId,
            )

            assertNoWrite("D-08a", before)
        }

    /**
     * D-08b — Variante base vide : `invoke` et `getById` rendent `null` pour un ID positif et un ID
     * négatif absents, sans crash ni création (CA-14, I-1).
     */
    @Test
    fun `D-08b - sur une base vide les identifiants absents rendent null sans rien creer`() =
        runTest {
            val before = snapshot()

            assertDetail("D-08b — invoke(1001)", null, observeDetail(ABSENT_POSITIVE_ID))
            assertDetail("D-08b — getById(1001)", null, useCase.getById(ABSENT_POSITIVE_ID))
            assertDetail("D-08b — invoke(-1001)", null, observeDetail(ABSENT_NEGATIVE_ID))
            assertDetail("D-08b — getById(-1001)", null, useCase.getById(ABSENT_NEGATIVE_ID))

            assertNoWrite("D-08b", before)
        }

    // ==========================================================================================
    // D-09 — Occurrence distante, sans horizon supplémentaire
    // ==========================================================================================

    /**
     * D-09a — Given une série annuelle de 2000 à 2101 dans une base séparée, When on observe février
     * 2000 puis qu'on lit l'ID de son unique occurrence, Then `invoke` et `getById` restituent la
     * même occurrence, jamais `null` (CA-14).
     *
     * Les dates sont volontairement fixes et très éloignées : le contrat est indépendant du jour
     * d'exécution, et les deux variantes ne peuvent pas appartenir toutes deux à une fenêtre de trois
     * ans. Elles ne doivent pas être rapprochées pour faire passer le test.
     */
    @Test
    fun `D-09a - une occurrence de fevrier 2000 est consultable individuellement`() = runTest {
        seedDistantSeries()
        assertDistantOccurrenceReadable(
            "D-09a",
            startOfMonth(2000, 2),
            endOfMonth(2000, 2),
            at09(2000, 2, 10),
        )
    }

    /**
     * D-09b — Variante future : même procédure en février 2100 (CA-14).
     */
    @Test
    fun `D-09b - une occurrence de fevrier 2100 est consultable individuellement`() = runTest {
        seedDistantSeries()
        assertDistantOccurrenceReadable(
            "D-09b",
            startOfMonth(2100, 2),
            endOfMonth(2100, 2),
            at09(2100, 2, 10),
        )
    }

    // ==========================================================================================
    // D-10 — Actualisation des données d'affichage
    // ==========================================================================================

    /**
     * D-10a — Given les observations de liste et de détail ouvertes sur le virtuel de février, When
     * seuls les liens `series_tags` sont remplacés (T1/T2 → TE) sans réécrire la série, Then les
     * deux vues restituent uniquement TE, toutes les autres valeurs restant identiques, et un nouvel
     * appel `getById` restitue aussi TE (CA-05, CA-13, CA-14).
     */
    @Test
    fun `D-10a - remplacer les tags de la serie actualise la liste et le detail deja ouverts`() =
        runTest {
            seedBase()
            val virtualFebruaryId =
                observeList(FEBRUARY_START, FEBRUARY_END).single().transaction.id

            turbineScope {
                val openList = listUseCase(FEBRUARY_START, FEBRUARY_END).testIn(backgroundScope)
                val openDetail = useCase(virtualFebruaryId).testIn(backgroundScope)

                awaitList(openList, "D-10a — liste initiale", listOf(virtualOfA(FEB_10)))
                awaitSlot(openDetail, "D-10a — détail initial", virtualFebruaryId)

                db.recurringSeriesDao().clearSeriesTags(seriesAId)
                db.recurringSeriesDao().addSeriesTagCrossRef(SeriesTagCrossRef(seriesAId, tagExceptionId))

                val retagged = virtualOfA(FEB_10, tags = listOf(TAG_EXCEPTION))
                awaitList(openList, "D-10a — liste après changement de tags", listOf(retagged))
                val detail = awaitDetail(openDetail, "D-10a — détail après changement de tags", retagged)

                assertEquals(
                    "D-10a — CA-14 : l'ID virtuel du slot ne doit pas changer",
                    virtualFebruaryId,
                    detail?.transaction?.id,
                )
                assertDetail(
                    "D-10a — getById après changement de tags",
                    retagged,
                    useCase.getById(virtualFebruaryId),
                )

                openList.cancelAndIgnoreRemainingEvents()
                openDetail.cancelAndIgnoreRemainingEvents()
            }
        }

    /**
     * D-10b — Variante indépendante : renommer le compte via son vrai DAO, en conservant son ID,
     * actualise l'objet associé du détail et le garde cohérent avec la liste (CA-13, CA-14).
     */
    @Test
    fun `D-10b - renommer le compte actualise le detail et le garde coherent avec la liste`() =
        runTest {
            seedBase()
            val virtualFebruaryId =
                observeList(FEBRUARY_START, FEBRUARY_END).single().transaction.id

            turbineScope {
                val openList = listUseCase(FEBRUARY_START, FEBRUARY_END).testIn(backgroundScope)
                val openDetail = useCase(virtualFebruaryId).testIn(backgroundScope)
                awaitList(openList, "D-10b — liste initiale", listOf(virtualOfA(FEB_10)))
                awaitSlot(openDetail, "D-10b — détail initial", virtualFebruaryId)

                val account = requireNotNull(accountRepo.getById(accountAId))
                db.accountDao().upsert(account.copy(name = RENAMED_ACCOUNT))

                val renamed = virtualOfA(FEB_10, account = RENAMED_ACCOUNT)
                awaitList(openList, "D-10b — liste après renommage du compte", listOf(renamed))
                val detail = awaitDetail(openDetail, "D-10b — détail après renommage du compte", renamed)

                assertEquals(
                    "D-10b — l'ID du compte doit être conservé",
                    accountAId,
                    detail?.account?.id,
                )
                assertDetail(
                    "D-10b — getById après renommage du compte",
                    renamed,
                    useCase.getById(virtualFebruaryId),
                )

                openList.cancelAndIgnoreRemainingEvents()
                openDetail.cancelAndIgnoreRemainingEvents()
            }
        }

    /**
     * D-10c — Variante indépendante : renommer la catégorie via son vrai DAO, en conservant son ID
     * (CA-13, CA-14).
     */
    @Test
    fun `D-10c - renommer la categorie actualise le detail et le garde coherent avec la liste`() =
        runTest {
            seedBase()
            val virtualFebruaryId =
                observeList(FEBRUARY_START, FEBRUARY_END).single().transaction.id

            turbineScope {
                val openList = listUseCase(FEBRUARY_START, FEBRUARY_END).testIn(backgroundScope)
                val openDetail = useCase(virtualFebruaryId).testIn(backgroundScope)
                awaitList(openList, "D-10c — liste initiale", listOf(virtualOfA(FEB_10)))
                awaitSlot(openDetail, "D-10c — détail initial", virtualFebruaryId)

                val category = requireNotNull(categoryRepo.getById(categoryLogementId))
                db.categoryDao().upsert(category.copy(name = RENAMED_CATEGORY))

                val renamed = virtualOfA(FEB_10, category = RENAMED_CATEGORY)
                awaitList(openList, "D-10c — liste après renommage de la catégorie", listOf(renamed))
                val detail =
                    awaitDetail(openDetail, "D-10c — détail après renommage de la catégorie", renamed)

                assertEquals(
                    "D-10c — l'ID de la catégorie doit être conservé",
                    categoryLogementId,
                    detail?.category?.id,
                )
                assertDetail(
                    "D-10c — getById après renommage de la catégorie",
                    renamed,
                    useCase.getById(virtualFebruaryId),
                )

                openList.cancelAndIgnoreRemainingEvents()
                openDetail.cancelAndIgnoreRemainingEvents()
            }
        }

    // ==========================================================================================
    // Jeu de données
    // ==========================================================================================

    private var accountAId = 0L
    private var accountControlId = 0L
    private var categoryLogementId = 0L
    private var categoryCoursesId = 0L
    private var categoryControlId = 0L
    private var tagFixeId = 0L
    private var tagLogementId = 0L
    private var tagCoursesId = 0L
    private var tagExceptionId = 0L
    private var tagControlId = 0L
    private var debtId = 0L
    private var seriesAId = 0L
    private var seriesCId = 0L
    private var punctualId = 0L

    private var distantAccountId = 0L
    private var distantCategoryId = 0L
    private var distantTagId = 0L
    private var distantSeriesId = 0L

    /**
     * État de base imposé par le ticket : compte A, catégorie A, tags T1/T2/TE, dette D, série A et
     * ponctuelle P. **Aucune exception.** Les occurrences virtuelles ne sont jamais pré-insérées.
     *
     * Hypothèse 1 : la catégorie « Courses » de P n'est pas listée par le ticket mais P la référence.
     */
    private suspend fun seedBase() {
        accountAId = db.accountDao().upsert(account("Compte courant test", 0xFF2196F3.toInt()))

        categoryLogementId = db.categoryDao()
            .upsert(category("Logement", TransactionType.EXPENSE, 0xFF4CAF50.toInt(), "home"))
        categoryCoursesId = db.categoryDao()
            .upsert(category("Courses", TransactionType.EXPENSE, 0xFFFF9800.toInt(), "cart"))

        tagFixeId = db.tagDao().upsert(TagEntity(name = "Fixe", colorArgb = 0xFF607D8B.toInt()))
        tagLogementId = db.tagDao().upsert(TagEntity(name = "Logement", colorArgb = 0xFF795548.toInt()))
        tagCoursesId = db.tagDao().upsert(TagEntity(name = "Courses", colorArgb = 0xFFE91E63.toInt()))
        tagExceptionId =
            db.tagDao().upsert(TagEntity(name = TAG_EXCEPTION, colorArgb = 0xFF8BC34A.toInt()))

        debtId = db.debtDao().upsert(
            DebtEntity(
                name = "Dette contrôle",
                totalAmount = 5_000.0,
                repaidAmount = 0.0,
                colorArgb = 0xFFF44336.toInt(),
                icon = "debt",
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

    /**
     * Seconde série de D-07 : mêmes dates que A, tout le reste distinct (hypothèses 2 et 3), pour que
     * la confusion entre deux séries soit détectable sur six champs et pas seulement sur le montant.
     */
    private suspend fun seedSecondSeries() {
        accountControlId = db.accountDao().upsert(account("Compte contrôle", 0xFF9C27B0.toInt()))
        categoryControlId = db.categoryDao()
            .upsert(category("Abonnements", TransactionType.EXPENSE, 0xFF3F51B5.toInt(), "wallet"))
        tagControlId =
            db.tagDao().upsert(TagEntity(name = TAG_CONTROL, colorArgb = 0xFF009688.toInt()))

        seriesCId = transactionRepo.saveSeriesWithTags(
            RecurringSeriesEntity(
                title = TITLE_C,
                amount = 1_250.0,
                type = TransactionType.EXPENSE,
                categoryId = categoryControlId,
                accountId = accountControlId,
                frequency = RecurrenceFrequency.MONTHLY,
                interval = 1,
                startDate = JAN_10,
                endDate = SERIES_END,
                maxOccurrences = null,
                daysOfWeek = null,
                isCancelled = false,
                note = NOTE_C,
                linkedGoalId = null,
                linkedDebtId = null,
            ),
            listOf(tagControlId),
        )
    }

    /** Base séparée de D-09 : série annuelle couvrant 2000 → 2101, aucune exception. */
    private suspend fun seedDistantSeries() {
        distantAccountId = db.accountDao().upsert(account("Compte longue durée", 0xFF00ACC1.toInt()))
        distantCategoryId = db.categoryDao()
            .upsert(category("Assurances", TransactionType.EXPENSE, 0xFF6D4C41.toInt(), "shield"))
        distantTagId =
            db.tagDao().upsert(TagEntity(name = TAG_DISTANT, colorArgb = 0xFF827717.toInt()))

        distantSeriesId = transactionRepo.saveSeriesWithTags(
            RecurringSeriesEntity(
                title = TITLE_DISTANT,
                amount = 120.0,
                type = TransactionType.EXPENSE,
                categoryId = distantCategoryId,
                accountId = distantAccountId,
                frequency = RecurrenceFrequency.YEARLY,
                interval = 1,
                startDate = at09(2000, 2, 10),
                endDate = LocalDate.of(2101, 2, 9)
                    .atTime(23, 59, 59, 999_000_000)
                    .atZone(ZONE)
                    .toInstant()
                    .toEpochMilli(),
                maxOccurrences = null,
                daysOfWeek = null,
                isCancelled = false,
                note = NOTE_DISTANT,
                linkedGoalId = null,
                linkedDebtId = null,
            ),
            listOf(distantTagId),
        )
    }

    /**
     * Exception E de la série A : slot du 10 février, affichée le 12, valeurs personnalisées et seul
     * tag TE. `id = 0` : Room alloue l'ID positif, on ne force jamais un ID négatif (I-2).
     */
    private suspend fun insertExceptionE(): Long = transactionRepo.saveWithTags(
        TransactionEntity(
            title = TITLE_EXCEPTION,
            amount = 900.0,
            type = TransactionType.EXPENSE,
            status = TransactionStatus.PAID,
            kind = TransactionKind.STANDARD,
            date = FEB_12,
            accountId = accountAId,
            categoryId = categoryLogementId,
            note = NOTE_EXCEPTION,
            paidAt = FEB_12,
            seriesId = seriesAId,
            seriesDate = FEB_10,
            isException = true,
            linkedGoalId = null,
            linkedDebtId = debtId,
            deleted = false,
        ),
        listOf(tagExceptionId),
    )

    /** Ramène la fin de la série A au 31 janvier, sans toucher au reste de sa définition. */
    private suspend fun shortenSeriesAToJanuary() {
        val series = requireNotNull(transactionRepo.getSeriesById(seriesAId))
        transactionRepo.updateSeries(series.copy(endDate = endOfMonth(2026, 1)))
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

    // ==========================================================================================
    // Oracles
    // ==========================================================================================

    /**
     * Projection comparable d'une occurrence rendue : tous les champs nommés par les assertions
     * obligatoires du ticket. [kind] est dérivé du **signe** de l'ID — c'est l'oracle I-2
     * « virtuel = négatif, exception = positif » sans écrire de valeur littérale ni appeler
     * `calculateVirtualId`, qui produirait un oracle tautologique.
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
        val paidAt: Long?,
        val isException: Boolean,
        val deleted: Boolean,
        val transactionKind: TransactionKind,
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
        paidAt = transaction.paidAt,
        isException = transaction.isException,
        deleted = transaction.deleted,
        transactionKind = transaction.kind,
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
        tags: List<String> = listOf("Fixe", "Logement"),
        account: String = "Compte courant test",
        category: String = "Logement",
    ) = Row(
        kind = "VIRTUEL",
        seriesId = seriesAId,
        seriesDate = date,
        date = date,
        title = TITLE_A,
        amount = 820.0,
        type = TransactionType.EXPENSE,
        status = TransactionStatus.PLANNED,
        paidAt = null,
        isException = false,
        deleted = false,
        transactionKind = TransactionKind.STANDARD,
        account = account,
        category = category,
        note = NOTE_A,
        linkedGoalId = null,
        linkedDebtId = debtId,
        tags = tags.sorted(),
    )

    private fun virtualOfSecond(date: Long) = Row(
        kind = "VIRTUEL",
        seriesId = seriesCId,
        seriesDate = date,
        date = date,
        title = TITLE_C,
        amount = 1_250.0,
        type = TransactionType.EXPENSE,
        status = TransactionStatus.PLANNED,
        paidAt = null,
        isException = false,
        deleted = false,
        transactionKind = TransactionKind.STANDARD,
        account = "Compte contrôle",
        category = "Abonnements",
        note = NOTE_C,
        linkedGoalId = null,
        linkedDebtId = null,
        tags = listOf(TAG_CONTROL),
    )

    private fun virtualOfDistant(date: Long) = Row(
        kind = "VIRTUEL",
        seriesId = distantSeriesId,
        seriesDate = date,
        date = date,
        title = TITLE_DISTANT,
        amount = 120.0,
        type = TransactionType.EXPENSE,
        status = TransactionStatus.PLANNED,
        paidAt = null,
        isException = false,
        deleted = false,
        transactionKind = TransactionKind.STANDARD,
        account = "Compte longue durée",
        category = "Assurances",
        note = NOTE_DISTANT,
        linkedGoalId = null,
        linkedDebtId = null,
        tags = listOf(TAG_DISTANT),
    )

    private fun exceptionE() = Row(
        kind = "REEL",
        seriesId = seriesAId,
        seriesDate = FEB_10,
        date = FEB_12,
        title = TITLE_EXCEPTION,
        amount = 900.0,
        type = TransactionType.EXPENSE,
        status = TransactionStatus.PAID,
        paidAt = FEB_12,
        isException = true,
        deleted = false,
        transactionKind = TransactionKind.STANDARD,
        account = "Compte courant test",
        category = "Logement",
        note = NOTE_EXCEPTION,
        linkedGoalId = null,
        linkedDebtId = debtId,
        tags = listOf(TAG_EXCEPTION),
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
        paidAt = JAN_15,
        isException = false,
        deleted = false,
        transactionKind = TransactionKind.STANDARD,
        account = "Compte courant test",
        category = "Courses",
        note = null,
        linkedGoalId = null,
        linkedDebtId = null,
        tags = listOf("Courses"),
    )

    /**
     * Oracle du détail : `null` attendu, ou l'occurrence exacte sur tous les champs nommés par le
     * ticket. Un `null` inattendu et une mauvaise occurrence échouent tous deux ici.
     */
    private fun assertDetail(label: String, expected: Row?, actual: TransactionWithRelations?) {
        assertEquals(
            "$label — CA-14 : occurrence exacte attendue (identité, slot, valeurs, compte, " +
                "catégorie, note, objectif/dette, drapeaux et ensemble exact des tags)",
            expected,
            actual?.row(),
        )
    }

    /** Oracle de la liste : cardinalité exacte, contenu exact, unicité des slots. */
    private fun assertListContent(
        label: String,
        expected: List<Row>,
        actual: List<TransactionWithRelations>,
    ) {
        val actualRows = actual.map { it.row() }
        assertEquals("$label — cardinalité exacte", expected.size, actualRows.size)
        assertEquals(
            "$label — contenu exact de la liste",
            expected.sortedWith(ROW_ORDER),
            actualRows.sortedWith(ROW_ORDER),
        )
        assertSlotUniqueness(actual, label)
    }

    /** I-3 / CA-07 : une seule représentation par slot `seriesId + seriesDate`. */
    private fun assertSlotUniqueness(rows: List<TransactionWithRelations>, label: String) {
        rows.filter { it.transaction.seriesId != null && it.transaction.seriesDate != null }
            .groupBy { it.transaction.seriesId!! to it.transaction.seriesDate!! }
            .forEach { (slot, colliding) ->
                assertEquals(
                    "$label — CA-07/I-3 : doublon sur le slot (seriesId=${slot.first}, " +
                        "seriesDate=${slot.second}). Lignes en collision : " +
                        colliding.joinToString { row ->
                            val tx = row.transaction
                            "${if (tx.id < 0) "VIRTUEL" else "REEL"}(id=${tx.id}, date=${tx.date}, " +
                                "titre='${tx.title}', montant=${tx.amount})"
                        },
                    1,
                    colliding.size,
                )
            }
    }

    /**
     * D-09 : la liste d'une fenêtre lointaine fournit un ID, et cet ID doit être consultable
     * individuellement. Les attendus viennent du calendrier de la série et de la fenêtre demandée,
     * jamais des bornes internes du lookup ni de `Calendar.getInstance()`.
     */
    private suspend fun assertDistantOccurrenceReadable(
        label: String,
        windowStart: Long,
        windowEnd: Long,
        expectedDate: Long,
    ) {
        val before = snapshot()

        val window = observeList(windowStart, windowEnd)
        assertListContent("$label — liste de la fenêtre", listOf(virtualOfDistant(expectedDate)), window)

        val id = window.single().transaction.id
        assertTrue("$label — I-2 : ID virtuel négatif attendu, obtenu $id", id < 0L)

        assertDetail("$label — invoke(id)", virtualOfDistant(expectedDate), observeDetail(id))
        assertDetail("$label — getById(id)", virtualOfDistant(expectedDate), useCase.getById(id))

        assertNoWrite(label, before)
    }

    // ==========================================================================================
    // Observation
    // ==========================================================================================

    private suspend fun observeDetail(id: Long): TransactionWithRelations? {
        var emission: TransactionWithRelations? = null
        useCase(id).test(timeout = TIMEOUT) {
            emission = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        return emission
    }

    private suspend fun observeList(start: Long, end: Long): List<TransactionWithRelations> {
        lateinit var emission: List<TransactionWithRelations>
        listUseCase(start, end).test(timeout = TIMEOUT) {
            emission = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        return emission
    }

    /**
     * Point de **synchronisation** : attend que l'observation ait émis l'occurrence du slot visé,
     * sans réasserter son contenu.
     *
     * Le contenu de l'émission initiale est déjà l'oracle de D-01. Le réasserter ici ferait dépendre
     * le sujet de chaque test d'un défaut couvert ailleurs : un seul manque (les tags) suffirait à
     * bloquer six tests avant qu'ils n'atteignent ce qu'ils prouvent. Les oracles de sujet, eux,
     * restent complets.
     */
    private suspend fun awaitSlot(
        turbine: ReceiveTurbine<TransactionWithRelations?>,
        label: String,
        expectedId: Long,
    ): TransactionWithRelations = requireNotNull(
        awaitEmission(turbine, "$label — synchronisation sur l'ID $expectedId") {
            it?.transaction?.id == expectedId
        },
    ) { "$label — synchronisation : occurrence non nulle attendue pour l'ID $expectedId" }

    /**
     * Consomme les émissions jusqu'à l'état métier attendu. Les émissions identiques répétées sont
     * tolérées, les mauvais états finaux ne le sont pas : l'échec cite le dernier état traversé.
     */
    private suspend fun awaitDetail(
        turbine: ReceiveTurbine<TransactionWithRelations?>,
        label: String,
        expected: Row?,
    ): TransactionWithRelations? = awaitEmission(turbine, label, expected) { it?.row() == expected }

    /**
     * Attente commune. L'absence de nouvelle émission est convertie en échec **métier** citant
     * l'attendu et le dernier état observé : sans cela, un état jamais atteint remonte comme un
     * `TurbineAssertionError: No value produced`, illisible et impossible à rattacher à un CA.
     */
    private suspend fun awaitEmission(
        turbine: ReceiveTurbine<TransactionWithRelations?>,
        label: String,
        expected: Any? = null,
        matches: (TransactionWithRelations?) -> Boolean,
    ): TransactionWithRelations? {
        var last: Row? = null
        var seen = 0
        repeat(MAX_EMISSIONS) {
            val emission = try {
                turbine.awaitItem()
            } catch (noEmission: AssertionError) {
                fail(
                    "$label — CA-14 : état attendu jamais atteint. Attendu : $expected. " +
                        "Dernier état observé après $seen émission(s) : $last. " +
                        "Aucune nouvelle émission (${noEmission.message})",
                )
                error("unreachable")
            }
            seen++
            last = emission?.row()
            if (matches(emission)) return emission
        }
        fail(
            "$label — CA-14 : état attendu non atteint après $MAX_EMISSIONS émissions. " +
                "Attendu : $expected. Dernier état observé : $last",
        )
        error("unreachable")
    }

    /**
     * Idem pour la liste, en assertant l'unicité des slots sur **chaque** émission traversée :
     * contrairement à un `filter`, aucune émission contenant un doublon ne disparaît silencieusement.
     */
    private suspend fun awaitList(
        turbine: ReceiveTurbine<List<TransactionWithRelations>>,
        label: String,
        expected: List<Row>,
    ): List<TransactionWithRelations> {
        val target = expected.sortedWith(ROW_ORDER)
        var last: List<Row> = emptyList()
        var seen = 0
        repeat(MAX_EMISSIONS) {
            val emission = try {
                turbine.awaitItem()
            } catch (noEmission: AssertionError) {
                fail(
                    "$label — état attendu jamais atteint. Attendu : $target. " +
                        "Dernier état observé après $seen émission(s) : $last. " +
                        "Aucune nouvelle émission (${noEmission.message})",
                )
                error("unreachable")
            }
            seen++
            assertSlotUniqueness(emission, "$label — émission intermédiaire #$it")
            last = emission.map { row -> row.row() }.sortedWith(ROW_ORDER)
            if (last == target) return emission
        }
        fail(
            "$label — état attendu non atteint après $MAX_EMISSIONS émissions. " +
                "Attendu : $target. Dernier état observé : $last",
        )
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
        transactionTags = rawRows(
            "SELECT transactionId, tagId FROM transaction_tags ORDER BY transactionId, tagId",
        ),
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

    /** CA-05 / I-1 : lire une occurrence ne persiste ni ne modifie rien. */
    private fun assertNoWrite(label: String, before: Snapshot) {
        assertEquals(
            "$label — CA-05/I-1 : une lecture ne doit rien écrire ni modifier",
            before,
            snapshot(),
        )
    }

    private companion object {
        const val ZONE_ID = "Europe/Paris"
        val ZONE: ZoneId = ZoneId.of(ZONE_ID)
        val TIMEOUT = 10.seconds
        const val MAX_EMISSIONS = 20

        const val TITLE_A = "Loyer A"
        const val TITLE_C = "Assurance habitation C"
        const val TITLE_P = "Courses contrôle"
        const val TITLE_EXCEPTION = "Loyer ajusté"
        const val TITLE_DISTANT = "Assurance longue"
        const val NOTE_A = "Contrat A"
        const val NOTE_C = "Contrat C"
        const val NOTE_EXCEPTION = "Ajustement A"
        const val NOTE_DISTANT = "Contrat centenaire"

        const val TAG_EXCEPTION = "Exception"
        const val TAG_CONTROL = "Contrôle"
        const val TAG_DISTANT = "Assurance"

        const val RENAMED_ACCOUNT = "Compte courant renommé"
        const val RENAMED_CATEGORY = "Logement renommé"

        /** IDs volontairement absents de la base (D-08b). */
        const val ABSENT_POSITIVE_ID = 1001L
        const val ABSENT_NEGATIVE_ID = -1001L

        /**
         * Dates et fenêtres déclarées **localement à ce fichier** : les cardinalités exactes de la
         * matrice en dépendent et ne doivent pas pouvoir être modifiées par un autre ticket via un
         * fixture partagé.
         */
        val JAN_10 = at09(2026, 1, 10)
        val JAN_15 = at09(2026, 1, 15)
        val FEB_10 = at09(2026, 2, 10)
        val FEB_12 = at09(2026, 2, 12)
        val SERIES_END = endOfMonth(2026, 3)

        val FEBRUARY_START = startOfMonth(2026, 2)
        val FEBRUARY_END = endOfMonth(2026, 2)

        /** Ordre de comparaison propre au test — jamais celui de la production. */
        val ROW_ORDER: Comparator<Row> =
            compareBy({ it.date }, { it.seriesId ?: -1L }, { it.kind }, { it.title })

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

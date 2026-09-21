package com.lop.budget.data.repository

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import app.cash.turbine.turbineScope
import com.lop.budget.data.local.LopDatabase
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.CategoryEntity
import com.lop.budget.data.local.entity.SeriesTagCrossRef
import com.lop.budget.data.local.entity.TagEntity
import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.domain.model.AccountType
import com.lop.budget.domain.model.EditScope
import com.lop.budget.domain.model.RecurrenceFrequency
import com.lop.budget.domain.model.TransactionEdition
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.domain.usecase.SyncProgressUseCase
import com.lop.budget.domain.usecase.transaction.CreateTransactionUseCase
import com.lop.budget.domain.usecase.transaction.EditOutcome
import com.lop.budget.domain.usecase.transaction.EditTransactionWithScopeUseCase
import com.lop.budget.domain.usecase.transaction.ObserveTransactionDetailUseCase
import com.lop.budget.domain.usecase.transaction.ObserveTransactionsUseCase
import com.lop.budget.domain.usecase.transaction.SaveTransactionUseCase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone
import kotlin.time.Duration.Companion.seconds

/**
 * TC-122 — Tags persistés : jointures transaction, tags de série et occurrences virtuelles
 * (US LOP-3, réf. 3).
 *
 * ## Niveau
 * Intégration sur base Room réelle, en mémoire (Robolectric SDK 33, application Android neutre).
 * **Aucun mock, aucun fake, aucun spy sur la chaîne écriture-lecture.** Ce ticket porte sur des
 * lignes écrites, des cardinalités et des jointures : une doublure ne voit ni les `INSERT`, ni les
 * `DELETE` de `clearTags`, ni la clé primaire composite qui interdit le doublon.
 *
 * ## Chaîne réellement exercée
 * ```
 * Écriture ponctuelle   CreateTransactionUseCase(edition) → SaveTransactionUseCase.saveSimple
 *                         → TransactionDao.saveWithTags
 * Écriture de série     TransactionRepository.saveSeriesWithTags → RecurringSeriesDao
 *                         (upsertSeries · clearSeriesTags · addSeriesTagCrossRef)
 * Édition d'occurrence  EditTransactionWithScopeUseCase(..., scope = SINGLE)
 * Lecture ponctuelle    ObserveTransactionDetailUseCase.getById(id)
 * Lecture observée      ObserveTransactionDetailUseCase.invoke(id) et ObserveTransactionsUseCase
 * Tags de série         RecurringSeriesDao.observeAllSeriesTags()
 * ```
 * Les cas passent par les points d'entrée que l'application utilise, jamais par le DAO
 * directement — sauf pour **fabriquer le jeu de données** et pour **constater l'état**.
 *
 * ## Traçabilité — cas → CA / invariant → fonction de production
 * ```
 * T-01    CA-07          CreateTransactionUseCase (branche ponctuelle) → saveWithTags
 * T-02    CA-07, I-3     EditTransactionWithScopeUseCase SINGLE, ré-enregistrement idempotent
 * T-03    CA-08          CreateTransactionUseCase avec tagIds vide
 * T-04    CA-10, I-4     EditTransactionWithScopeUseCase SINGLE, remplacement de sélection
 * T-05a   CA-12          ObserveTransactionDetailUseCase.getById (ID réel)
 * T-05b   CA-12          ObserveTransactionDetailUseCase.getById (ID positif absent)
 * T-06    CA-15, I-7     CreateTransactionUseCase (branche récurrente) → saveSeriesWithTags
 * T-07    CA-15, I-3     TransactionRepository.saveSeriesWithTags, ré-enregistrement idempotent
 * T-08    CA-16, I-7/I-8 ObserveTransactionsUseCase + resolveSlot (filtre par série)
 * T-09    CA-16          ObserveTransactionDetailUseCase.invoke (ID virtuel)
 * T-10    CA-17, I-8     clearSeriesTags + addSeriesTagCrossRef sur observations ouvertes
 * T-11    CA-16, CA-17   EditTransactionWithScopeUseCase SINGLE sur occurrence virtuelle
 * ```
 * Aucun cas sans CA, aucun CA de ce périmètre sans cas.
 *
 * ## Fixtures discriminantes
 * - **TX-TEMOIN** — seconde ponctuelle liée à TAG-SANTE, qu'aucun cas ne modifie. Elle prouve
 *   qu'une écriture reste locale à sa transaction (I-4).
 * - **SERIE-B** — seconde série mensuelle portant **TAG-VACANCES seul**. Sans elle, supprimer le
 *   filtre `seriesTags.filter { it.seriesId == series.id }` de `resolveSlot` ne casserait rien :
 *   `observeAllSeriesTags()` rend **toutes** les lignes de `series_tags`, sans filtre de série.
 * - **Montants tous distincts** entre TX-PONCT, TX-TEMOIN, la ponctuelle sans tag, SERIE-M et
 *   SERIE-B : aucune confusion de ligne ne peut tomber juste.
 * - Les tags sont identifiés par les **identifiants rendus par Room**, jamais par leur nom :
 *   `tags` n'a aucune contrainte d'unicité sur `name` et `TagDao.getByName` compare en exact.
 *
 * ## Points de montage qui ne sont PAS des oracles
 * - Base neuve par cas, fermée en `@After` ; aucun état réutilisé d'un cas à l'autre.
 * - `Europe/Paris` et `Locale.FRANCE` forcés puis restaurés ; instants construits avec `java.time`
 *   et un `ZoneId` explicite, nommés (`marsSlot`, `avrilSlot`). Aucun appel à l'horloge courante.
 * - Fenêtre d'observation d'avril déclarée **localement** dans les cas qui en dépendent : une
 *   cardinalité « exactement N » ne doit pas être suspendue à un helper qu'un autre ticket
 *   pourrait faire évoluer.
 * - Observation par Turbine, attentes bornées. Aucun `Thread.sleep`, aucun délai arbitraire,
 *   **aucun réabonnement** dans T-10, qui prouve précisément une réémission.
 * - [snapshot] lit `transactions`, `transaction_tags` et `series_tags` par SQL brut, triés. Une
 *   requête de lecture limitée au test est autorisée par `app/src/test/AGENTS.md` §6 ; aucune API
 *   de production n'a été ajoutée pour ce fichier.
 *
 * ## Écarts spec / code relevés à la lecture (aucun n'ouvre d'oracle ici)
 * - `TransactionDao.saveWithTags` remplace **intégralement** les liens (`clearTags` puis
 *   réinsertion), et `RecurringSeriesDao.saveSeriesWithTags` fait de même pour les séries.
 *   L'idempotence de T-02 et T-07 est donc portée par la clé primaire composite **et** par la
 *   liste transmise, pas par une déduplication.
 * - `SaveTransactionUseCase.saveSimple` déclare `tagIds: List<Long> = emptyList()` : tout appelant
 *   qui sauvegarde une ligne existante sans repasser la sélection efface ses liens. Les deux
 *   appelants actuels transmettent bien `edition.tagIds`. Aucun oracle ici — cela relèvera d'une
 *   décision d'US si un troisième appelant apparaît.
 * - `EditTransactionWithScopeUseCase` en portées `FUTURE` et `ALL` écrit par
 *   `transactionRepo.upsert`, qui ne touche pas aux jointures, là où l'occurrence consultée passe
 *   par `saveSimple(..., edition.tagIds)`, qui les réécrit. Cette asymétrie rend tout oracle de
 *   tags non trivial sur ces portées : **hors périmètre**.
 * - `TagDao.getByName` compare en exact et `tags` n'a aucun index unique sur `name` : I-2 n'est
 *   porté ni par le schéma ni par la lecture. Conséquence pour ce niveau uniquement : identifier
 *   les tags par leur identifiant. L'anomalie elle-même est portée par **TC-124** (ANO-2).
 * - La branche récurrente de `CreateTransactionUseCase` retourne
 *   `RecurrenceEngine.calculateVirtualId(newSeriesId, edition.date)`, **négatif**.
 *   `TransactionDao.getById` filtre `deleted = 0` et ne connaît pas ces identifiants : T-05b
 *   utilise donc un identifiant **positif** absent, sans quoi il testerait une autre branche.
 *
 * ## Anomalies
 * Aucune ANO ouverte par ce fichier à ce jour. Les oracles ne sont jamais assouplis : un rouge est
 * remonté tel quel, avec son message réel et une ANO par cause racine.
 *
 * ## Correction apportée à la table de mutations du ticket
 * La fiche attribue à **T-08** la détection de « supprimer le filtre
 * `seriesTags.filter { it.seriesId == series.id }` dans `resolveSlot` ». C'est inexact, vérifié par
 * mutation le 20 septembre 2026 : `resolveSlot` appartient à `ObserveTransactionDetailUseCase`, que
 * T-08 ne traverse pas — la liste groupe ses tags par série dans
 * `ObserveTransactionsUseCase.mergeRealAndVirtual` (`tagsBySeriesId = seriesTags.groupBy { … }`).
 * La mutation de `resolveSlot` est détectée par T-09 et T-10. La sensibilité de T-08 au filtre par
 * série a donc été prouvée sur le code qu'il exerce réellement, en neutralisant ce `groupBy` :
 * T-08 rougit alors, l'occurrence de SERIE-M portant les trois tags au lieu de deux. Le besoin de
 * SERIE-B comme témoin est confirmé dans les deux cas.
 *
 * ## Hors périmètre (ne pas revendiquer couvert par ce fichier)
 * - Sélection, désélection, pré-sélection (CA-01, CA-02, CA-09) → **TC-123**.
 * - Création rapide, trim, nom vide, nom déjà existant (CA-04, CA-05, CA-06) → **TC-124**.
 * - Rendu du détail et survie à la rotation (CA-13, CA-14) → **TC-125**.
 * - Écrans de gestion des tags (renommage, suppression, couleurs) → US dont LOP-3 dépend.
 * - Portées `FUTURE` et `ALL` de l'édition, et la propagation aux exceptions.
 * - Réactivité liste/détail déjà prouvée par TC-86 et TC-87 : T-10 ne les redouble pas, il fixe
 *   l'état final après remplacement.
 *
 * ## Exécution
 * `./gradlew :app:testDebugUnitTest --tests "*TransactionTagsPersistenceTest*"`
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class TransactionTagsPersistenceTest {

    // --- Composants réels -----------------------------------------------------------------------

    private lateinit var db: LopDatabase
    private lateinit var transactionRepo: TransactionRepository
    private lateinit var accountRepo: AccountRepository
    private lateinit var categoryRepo: CategoryRepository
    private lateinit var tagRepo: TagRepository
    private lateinit var goalRepo: GoalRepository
    private lateinit var loanRepo: LoanRepository
    private lateinit var createTransaction: CreateTransactionUseCase
    private lateinit var editWithScope: EditTransactionWithScopeUseCase
    private lateinit var detailUseCase: ObserveTransactionDetailUseCase
    private lateinit var listUseCase: ObserveTransactionsUseCase

    private lateinit var defaultTimeZone: TimeZone
    private lateinit var defaultLocale: Locale

    // --- Identifiants du jeu de données, tous rendus par Room -----------------------------------

    private var accountId = 0L
    private var categoryId = 0L
    private var tagSanteId = 0L
    private var tagProId = 0L
    private var tagVacancesId = 0L
    private var txTemoinId = 0L
    private var serieBId = 0L

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
        tagRepo = TagRepository(db.tagDao())
        goalRepo = GoalRepository(db.goalDao())
        loanRepo = LoanRepository(db.loanDao())

        val syncProgress = SyncProgressUseCase(transactionRepo, goalRepo, loanRepo)
        val saveTransaction = SaveTransactionUseCase(transactionRepo, syncProgress)
        createTransaction = CreateTransactionUseCase(transactionRepo, saveTransaction)
        editWithScope = EditTransactionWithScopeUseCase(transactionRepo, saveTransaction)
        detailUseCase = ObserveTransactionDetailUseCase(transactionRepo, accountRepo, categoryRepo)
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

    // ==============================================================================================
    // T-01 — Une ponctuelle à deux tags écrit exactement deux lignes de jointure
    // ==============================================================================================

    /**
     * T-01 — Given le jeu de données de base, When on crée TX-PONCT avec TAG-SANTE et TAG-PRO par
     * `CreateTransactionUseCase`, Then la jointure contient exactement deux lignes pour cette
     * transaction, les sept champs nommés par le CA sont relus tels que soumis, et `series_tags`
     * reste vide (CA-07).
     */
    @Test
    fun `T-01 - Given le jeu de donnees de base - When on cree une ponctuelle avec deux tags - Then exactement deux lignes de jointure et les sept champs persistes (CA-07)`() =
        runTest {
            seedBase()
            // Le socle contient déjà SERIE-B et son lien : l'oracle porte donc sur l'absence de
            // lien de série AJOUTÉ par la création d'une ponctuelle, pas sur une table vide.
            val seriesTagsBefore = rawRows(SQL_SERIES_TAGS)

            val txId = createTxPonct(listOf(tagSanteId, tagProId))

            assertEquals(
                "T-01 — CA-07 : exactement une ligne de `transactions` pour cet identifiant ; " +
                    "transactions = ${rawRows(SQL_TRANSACTIONS)}",
                1,
                rawRows("SELECT id FROM transactions WHERE id = $txId").size,
            )
            assertEquals(
                "T-01 — CA-07 : la jointure devait contenir exactement deux lignes pour TX-PONCT ; " +
                    "transaction_tags = ${rawRows(SQL_TRANSACTION_TAGS)}",
                setOf(tagSanteId, tagProId),
                transactionTagIdsOf(txId),
            )
            assertEquals(
                "T-01 — CA-07 : les sept champs nommés par le critère doivent être relus tels que " +
                    "soumis (type, montant en centimes, libellé, date, catégorie, compte, statut)",
                PersistedFields(
                    type = TransactionType.EXPENSE,
                    amountCents = TX_PONCT_AMOUNT,
                    title = TX_PONCT_TITLE,
                    date = marsSlot,
                    categoryId = categoryId,
                    accountId = accountId,
                    status = TransactionStatus.PLANNED,
                ),
                persistedFieldsOf(txId),
            )
            assertEquals(
                "T-01 — CA-15 : une ponctuelle n'écrit aucun lien de série ; `series_tags` devait " +
                    "rester à l'identique (seule SERIE-B du socle y figure) ; " +
                    "avant = $seriesTagsBefore, après = ${rawRows(SQL_SERIES_TAGS)}",
                seriesTagsBefore,
                rawRows(SQL_SERIES_TAGS),
            )
            assertEquals(
                "T-01 — CA-15 : aucun lien de série n'a été ajouté par cette création ; " +
                    "series_tags = ${rawRows(SQL_SERIES_TAGS)}",
                setOf(tagVacancesId),
                seriesTagIdsOf(serieBId),
            )

            assertTemoinIntact("T-01")
        }

    // ==============================================================================================
    // T-02 — Ré-enregistrer la même sélection ne crée aucun doublon
    // ==============================================================================================

    /**
     * T-02 — Given TX-PONCT enregistrée avec deux tags, When on la ré-enregistre en portée SINGLE
     * avec la même sélection et sans autre modification, Then la jointure contient toujours
     * exactement les deux mêmes lignes, le total de `transaction_tags` est inchangé et TX-TEMOIN
     * est intacte (CA-07, I-3).
     */
    @Test
    fun `T-02 - Given une ponctuelle deja enregistree avec deux tags - When on la re-enregistre a l'identique - Then aucun doublon de jointure (CA-07, I-3)`() =
        runTest {
            seedBase()
            val txId = createTxPonct(listOf(tagSanteId, tagProId))
            val totalJoinsBefore = rawRows(SQL_TRANSACTION_TAGS).size

            val outcome = editWithScope(
                editingId = txId,
                seriesId = null,
                seriesDate = null,
                edition = edition(
                    title = TX_PONCT_TITLE,
                    amount = TX_PONCT_AMOUNT,
                    date = marsSlot,
                    tagIds = listOf(tagSanteId, tagProId),
                ),
                scope = EditScope.SINGLE,
            )

            assertEquals(
                "T-02 — le ré-enregistrement devait s'appliquer à la même ligne",
                EditOutcome.Applied(txId),
                outcome,
            )
            assertEquals(
                "T-02 — I-3 : une même paire (transaction, tag) ne peut pas être présente deux " +
                    "fois ; transaction_tags = ${rawRows(SQL_TRANSACTION_TAGS)}",
                2,
                rawRows("SELECT tagId FROM transaction_tags WHERE transactionId = $txId").size,
            )
            assertEquals(
                "T-02 — CA-07 : l'ensemble des tags liés devait rester identique ; " +
                    "transaction_tags = ${rawRows(SQL_TRANSACTION_TAGS)}",
                setOf(tagSanteId, tagProId),
                transactionTagIdsOf(txId),
            )
            assertEquals(
                "T-02 — I-3 : le nombre total de lignes de jointure devait rester identique ; " +
                    "transaction_tags = ${rawRows(SQL_TRANSACTION_TAGS)}",
                totalJoinsBefore,
                rawRows(SQL_TRANSACTION_TAGS).size,
            )

            assertTemoinIntact("T-02")
        }

    // ==============================================================================================
    // T-03 — Zéro tag est un état final valide
    // ==============================================================================================

    /**
     * T-03 — Given le jeu de données de base, When on crée une dépense ponctuelle sans aucun tag,
     * Then la ligne est persistée, la jointure ne contient aucune ligne pour cet identifiant,
     * aucune exception n'est levée et TX-TEMOIN conserve son unique lien (CA-08).
     */
    @Test
    fun `T-03 - Given le jeu de donnees de base - When on cree une ponctuelle sans aucun tag - Then la ligne est persistee avec zero jointure et sans erreur (CA-08)`() =
        runTest {
            seedBase()

            val txId = createTransaction(
                edition(
                    title = TX_SANS_TAG_TITLE,
                    amount = TX_SANS_TAG_AMOUNT,
                    date = marsSlot,
                    tagIds = emptyList(),
                ),
            )

            assertNotNull(
                "T-03 — CA-08 : la transaction devait être persistée malgré l'absence de tag",
                persistedFieldsOf(txId),
            )
            assertEquals(
                "T-03 — CA-08 : la jointure devait contenir exactement zéro ligne pour cette " +
                    "transaction ; transaction_tags = ${rawRows(SQL_TRANSACTION_TAGS)}",
                emptySet<Long>(),
                transactionTagIdsOf(txId),
            )

            assertTemoinIntact("T-03")
        }

    // ==============================================================================================
    // T-04 — Retirer un tag d'une transaction ne le supprime pas du référentiel
    // ==============================================================================================

    /**
     * T-04 — Given TX-PONCT liée à TAG-SANTE et TAG-PRO, When on l'édite en portée SINGLE en
     * retirant TAG-SANTE et en ajoutant TAG-VACANCES, Then sa jointure vaut exactement
     * {TAG-PRO, TAG-VACANCES}, la table `tags` compte toujours trois lignes dont TAG-SANTE, et
     * TX-TEMOIN conserve son lien vers TAG-SANTE (CA-10, I-4).
     */
    @Test
    fun `T-04 - Given une ponctuelle liee a Sante et Pro - When on retire Sante et ajoute Vacances - Then la jointure vaut exactement Pro et Vacances (CA-10, I-4)`() =
        runTest {
            seedBase()
            val txId = createTxPonct(listOf(tagSanteId, tagProId))

            editWithScope(
                editingId = txId,
                seriesId = null,
                seriesDate = null,
                edition = edition(
                    title = TX_PONCT_TITLE,
                    amount = TX_PONCT_AMOUNT,
                    date = marsSlot,
                    tagIds = listOf(tagProId, tagVacancesId),
                ),
                scope = EditScope.SINGLE,
            )

            assertEquals(
                "T-04 — CA-10 : la jointure de TX-PONCT devait valoir exactement {PRO, VACANCES} ; " +
                    "transaction_tags = ${rawRows(SQL_TRANSACTION_TAGS)}",
                setOf(tagProId, tagVacancesId),
                transactionTagIdsOf(txId),
            )
            assertEquals(
                "T-04 — I-4 : retirer un tag d'une transaction ne supprime jamais le tag lui-même ; " +
                    "tags = ${rawRows(SQL_TAGS)}",
                setOf(tagSanteId, tagProId, tagVacancesId),
                rawRows("SELECT id FROM tags ORDER BY id").map { it.toLong() }.toSet(),
            )

            assertTemoinIntact("T-04")
        }

    // ==============================================================================================
    // T-05a / T-05b — Lecture par identifiant
    // ==============================================================================================

    /**
     * T-05a — Given TX-PONCT liée à deux tags, When on la lit par son identifiant, Then la
     * transaction relue porte exactement ses tags, ni plus ni moins (CA-12).
     */
    @Test
    fun `T-05a - Given une ponctuelle liee a deux tags - When getById sur son identifiant - Then elle porte exactement ses tags (CA-12)`() =
        runTest {
            seedBase()
            val txId = createTxPonct(listOf(tagSanteId, tagProId))

            val read = detailUseCase.getById(txId)

            assertNotNull("T-05a — CA-12 : la transaction devait être retrouvée par son ID", read)
            assertEquals(
                "T-05a — CA-12 : l'identifiant relu devait être celui demandé",
                txId,
                read?.transaction?.id,
            )
            assertEquals(
                "T-05a — CA-12 : la lecture devait rendre exactement les tags de cette " +
                    "transaction ; obtenu ${read?.tags?.map { it.id }}",
                setOf(tagSanteId, tagProId),
                read?.tags?.map { it.id }?.toSet(),
            )
            assertEquals(
                "T-05a — CA-12 : les noms relus devaient être ceux du référentiel ; " +
                    "obtenu ${read?.tags?.map { it.name }}",
                setOf(TAG_SANTE_NAME, TAG_PRO_NAME),
                read?.tags?.map { it.name }?.toSet(),
            )
        }

    /**
     * T-05b — Given le jeu de données de base, When on lit un identifiant **positif** absent de la
     * base, Then le résultat est `null`, sans ligne rendue par défaut ni exception (CA-12).
     *
     * L'identifiant doit être positif : `getById` résout les identifiants négatifs par une autre
     * branche (slot virtuel), et un négatif testerait donc autre chose que ce que vise CA-12.
     */
    @Test
    fun `T-05b - Given le jeu de donnees de base - When getById sur un identifiant positif absent - Then le resultat est null (CA-12)`() =
        runTest {
            seedBase()
            createTxPonct(listOf(tagSanteId, tagProId))

            val absentId = rawRows("SELECT MAX(id) FROM transactions").single().toLong() + 1_000L
            assertTrue(
                "T-05b — montage : l'identifiant sondé doit être strictement positif ($absentId)",
                absentId > 0L,
            )

            assertNull(
                "T-05b — CA-12 : un identifiant inexistant doit rendre null, jamais une autre " +
                    "transaction ; obtenu ${detailUseCase.getById(absentId)?.transaction}",
                detailUseCase.getById(absentId),
            )
        }

    // ==============================================================================================
    // T-06 — Les tags d'une récurrente appartiennent à la série
    // ==============================================================================================

    /**
     * T-06 — Given le jeu de données de base, When on crée SERIE-M (mensuelle) avec TAG-SANTE et
     * TAG-PRO, Then `series_tags` contient exactement deux lignes pour cette série, aucune ligne
     * n'est écrite dans `transaction_tags`, aucune occurrence n'est matérialisée, et la valeur
     * retournée est négative (CA-15, I-7).
     */
    @Test
    fun `T-06 - Given le jeu de donnees de base - When on cree une serie mensuelle avec deux tags - Then deux liens de serie et aucune occurrence materialisee (CA-15, I-7)`() =
        runTest {
            seedBase()

            val anchorId = createTransaction(
                edition(
                    title = SERIE_M_TITLE,
                    amount = SERIE_M_AMOUNT,
                    date = marsSlot,
                    tagIds = listOf(tagSanteId, tagProId),
                    frequency = RecurrenceFrequency.MONTHLY,
                ),
            )
            val serieMId = seriesIdOf(SERIE_M_TITLE)

            assertEquals(
                "T-06 — CA-15 : la série devait porter exactement deux liens de tag ; " +
                    "series_tags = ${rawRows(SQL_SERIES_TAGS)}",
                setOf(tagSanteId, tagProId),
                seriesTagIdsOf(serieMId),
            )
            assertEquals(
                "T-06 — CA-15 : aucune ligne de `transaction_tags` ne doit être écrite pour une " +
                    "série ; transaction_tags = ${rawRows(SQL_TRANSACTION_TAGS)}",
                emptyList<String>(),
                rawRows("SELECT transactionId, tagId FROM transaction_tags WHERE transactionId IN " +
                    "(SELECT id FROM transactions WHERE seriesId = $serieMId)"),
            )
            assertEquals(
                "T-06 — I-7 : aucune occurrence de la série ne doit être matérialisée à la " +
                    "création ; transactions = ${rawRows(SQL_TRANSACTIONS)}",
                emptyList<String>(),
                rawRows("SELECT id FROM transactions WHERE seriesId = $serieMId"),
            )
            assertTrue(
                "T-06 — la valeur retournée est l'occurrence virtuelle d'ancrage, donc négative ; " +
                    "obtenu $anchorId",
                anchorId < 0L,
            )

            assertTemoinIntact("T-06")
        }

    // ==============================================================================================
    // T-07 — Ré-enregistrer une série à l'identique ne duplique pas ses liens
    // ==============================================================================================

    /**
     * T-07 — Given SERIE-M portant deux tags, When on la ré-enregistre avec la même sélection,
     * Then `series_tags` contient toujours exactement les deux mêmes lignes pour cette série et
     * les lignes de SERIE-B sont inchangées (CA-15, I-3).
     */
    @Test
    fun `T-07 - Given une serie portant deux tags - When on la re-enregistre a l'identique - Then aucun doublon de lien de serie (CA-15, I-3)`() =
        runTest {
            seedBase()
            val serieMId = createSerieM()
            val serieBTagsBefore = seriesTagIdsOf(serieBId)

            val existingSeries = requireNotNull(transactionRepo.getSeriesById(serieMId)) {
                "T-07 — montage : SERIE-M introuvable après sa création"
            }
            transactionRepo.saveSeriesWithTags(existingSeries, listOf(tagSanteId, tagProId))

            assertEquals(
                "T-07 — I-3 : une même paire (série, tag) ne peut pas être présente deux fois ; " +
                    "series_tags = ${rawRows(SQL_SERIES_TAGS)}",
                2,
                rawRows("SELECT tagId FROM series_tags WHERE seriesId = $serieMId").size,
            )
            assertEquals(
                "T-07 — CA-15 : l'ensemble des tags de SERIE-M devait rester identique ; " +
                    "series_tags = ${rawRows(SQL_SERIES_TAGS)}",
                setOf(tagSanteId, tagProId),
                seriesTagIdsOf(serieMId),
            )
            assertEquals(
                "T-07 — témoin : les liens de SERIE-B ne doivent pas bouger ; " +
                    "series_tags = ${rawRows(SQL_SERIES_TAGS)}",
                serieBTagsBefore,
                seriesTagIdsOf(serieBId),
            )
        }

    // ==============================================================================================
    // T-08 — Une occurrence virtuelle expose les tags de SA série, sans rien écrire
    // ==============================================================================================

    /**
     * T-08 — Given SERIE-M et SERIE-B taguées différemment et aucune occurrence matérialisée,
     * When on observe la liste sur une fenêtre contenant `avrilSlot`, Then l'occurrence virtuelle
     * de SERIE-M porte exactement {SANTE, PRO} et celle de SERIE-B exactement {VACANCES}, et
     * l'état des trois tables est identique avant et après (CA-16, I-7, I-8).
     */
    @Test
    fun `T-08 - Given deux series taguees differemment - When on observe la liste d'avril - Then chaque occurrence virtuelle porte les tags de sa serie sans rien ecrire (CA-16, I-7, I-8)`() =
        runTest {
            seedBase()
            val serieMId = createSerieM()
            // Fenêtre déclarée localement : la cardinalité « exactement deux » en dépend.
            val windowStart = aprilWindowStart
            val windowEnd = aprilWindowEnd
            val before = snapshot()

            val april = observeList(windowStart, windowEnd)

            assertEquals(
                "T-08 — la fenêtre d'avril devait contenir exactement une occurrence par série ; " +
                    "obtenu ${april.map { it.transaction.title }}",
                2,
                april.size,
            )
            assertEquals(
                "T-08 — I-8 : l'occurrence virtuelle de SERIE-M devait porter exactement " +
                    "{SANTE, PRO} ; obtenu ${tagIdsOfOccurrence(april, serieMId)}",
                setOf(tagSanteId, tagProId),
                tagIdsOfOccurrence(april, serieMId),
            )
            assertEquals(
                "T-08 — I-8 : l'occurrence virtuelle de SERIE-B devait porter exactement " +
                    "{VACANCES} ; obtenu ${tagIdsOfOccurrence(april, serieBId)}",
                setOf(tagVacancesId),
                tagIdsOfOccurrence(april, serieBId),
            )

            assertNoWrite("T-08", before)
        }

    // ==============================================================================================
    // T-09 — Le détail d'une occurrence virtuelle rend le même ensemble que la liste
    // ==============================================================================================

    /**
     * T-09 — Given l'occurrence virtuelle d'avril de SERIE-M, When on lit son détail par son
     * identifiant virtuel, Then elle porte les mêmes tags qu'en liste, avec les mêmes cardinalités,
     * et l'état des trois tables est inchangé (CA-16, I-7, I-8).
     */
    @Test
    fun `T-09 - Given l'occurrence virtuelle d'avril - When on lit son detail - Then elle porte les memes tags que la liste sans rien ecrire (CA-16, I-7, I-8)`() =
        runTest {
            seedBase()
            val serieMId = createSerieM()
            val virtualId = virtualIdOfApril(serieMId)
            val before = snapshot()

            val detail = detailUseCase(virtualId).let { flow ->
                var emission: TransactionWithRelations? = null
                flow.test(timeout = TIMEOUT) {
                    emission = awaitItem()
                    cancelAndIgnoreRemainingEvents()
                }
                emission
            }

            assertNotNull(
                "T-09 — CA-16 : l'occurrence virtuelle devait être consultable individuellement",
                detail,
            )
            assertEquals(
                "T-09 — I-8 : le détail devait porter exactement les tags de SERIE-M ; " +
                    "obtenu ${detail?.tags?.map { it.id }}",
                setOf(tagSanteId, tagProId),
                detail?.tags?.map { it.id }?.toSet(),
            )
            assertEquals(
                "T-09 — CA-16 : liste et détail doivent rendre le même ensemble pour le même slot",
                tagIdsOfOccurrence(observeList(aprilWindowStart, aprilWindowEnd), serieMId),
                detail?.tags?.map { it.id }?.toSet(),
            )

            assertNoWrite("T-09", before)
        }

    // ==============================================================================================
    // T-10 — Remplacer les liens d'une série fait réémettre liste ET détail déjà ouverts
    // ==============================================================================================

    /**
     * T-10 — Given une liste et un détail **déjà observés** sur l'occurrence d'avril de SERIE-M,
     * When on remplace les liens de la série par TAG-VACANCES seul, Then les deux observations
     * réémettent sans réabonnement et l'occurrence porte exactement {VACANCES} dans l'une comme
     * dans l'autre (CA-17, I-8).
     */
    @Test
    fun `T-10 - Given une liste et un detail deja observes - When on remplace les liens de la serie - Then les deux reemettent exactement le nouveau tag (CA-17, I-8)`() =
        runTest {
            seedBase()
            val serieMId = createSerieM()
            val virtualId = virtualIdOfApril(serieMId)

            turbineScope {
                val listTurbine = listUseCase(aprilWindowStart, aprilWindowEnd).testIn(backgroundScope)
                val detailTurbine = detailUseCase(virtualId).testIn(backgroundScope)

                // Synchronisation sur l'état initial, sans réasserter son contenu : c'est déjà
                // l'oracle de T-08 et T-09.
                awaitListTags(listTurbine, "T-10 — état initial de la liste", serieMId, setOf(tagSanteId, tagProId))
                awaitDetailTags(detailTurbine, "T-10 — état initial du détail", setOf(tagSanteId, tagProId))

                db.recurringSeriesDao().clearSeriesTags(serieMId)
                db.recurringSeriesDao().addSeriesTagCrossRef(SeriesTagCrossRef(serieMId, tagVacancesId))

                awaitListTags(
                    listTurbine,
                    "T-10 — CA-17 : la liste déjà ouverte doit réémettre après remplacement",
                    serieMId,
                    setOf(tagVacancesId),
                )
                awaitDetailTags(
                    detailTurbine,
                    "T-10 — CA-17 : le détail déjà ouvert doit réémettre après remplacement",
                    setOf(tagVacancesId),
                )

                listTurbine.cancelAndIgnoreRemainingEvents()
                detailTurbine.cancelAndIgnoreRemainingEvents()
            }

            assertEquals(
                "T-10 — I-8 : l'état final des liens de SERIE-M doit valoir exactement {VACANCES} ; " +
                    "series_tags = ${rawRows(SQL_SERIES_TAGS)}",
                setOf(tagVacancesId),
                seriesTagIdsOf(serieMId),
            )
        }

    // ==============================================================================================
    // T-11 — Le passage du virtuel au réel ne perd aucun tag
    // ==============================================================================================

    /**
     * T-11 — Given l'occurrence virtuelle d'avril de SERIE-M, When on l'édite en portée SINGLE avec
     * la sélection préremplie par les tags de la série, Then la ligne matérialisée existe en
     * exactement un exemplaire pour ce slot et porte exactement ces tags, `series_tags` de SERIE-M
     * est inchangée et SERIE-B est intacte (CA-16, CA-17).
     */
    @Test
    fun `T-11 - Given l'occurrence virtuelle d'avril - When on l'edite en portee SINGLE avec les tags de la serie - Then la ligne materialisee porte exactement ces tags (CA-16, CA-17)`() =
        runTest {
            seedBase()
            val serieMId = createSerieM()
            val beforeRead = snapshot()
            val virtualId = virtualIdOfApril(serieMId)
            assertNoWrite("T-11 — phase de lecture", beforeRead)

            val serieMTagsBefore = seriesTagIdsOf(serieMId)
            val serieBTagsBefore = seriesTagIdsOf(serieBId)

            val outcome = editWithScope(
                editingId = virtualId,
                seriesId = serieMId,
                seriesDate = avrilSlot,
                // Le formulaire s'ouvre prérempli avec les tags de la série (CA-17).
                edition = edition(
                    title = SERIE_M_TITLE,
                    amount = SERIE_M_AMOUNT,
                    date = avrilSlot,
                    tagIds = listOf(tagSanteId, tagProId),
                ),
                scope = EditScope.SINGLE,
            )

            val materializedIds =
                rawRows("SELECT id FROM transactions WHERE seriesId = $serieMId AND seriesDate = $avrilSlot")
            assertEquals(
                "T-11 — CA-16 : le slot d'avril devait être matérialisé en exactement un " +
                    "exemplaire ; transactions = ${rawRows(SQL_TRANSACTIONS)}",
                1,
                materializedIds.size,
            )

            val materializedId = materializedIds.single().toLong()
            assertEquals(
                "T-11 — la matérialisation devait porter sur la ligne rendue par le use case",
                EditOutcome.Applied(materializedId),
                outcome,
            )
            assertEquals(
                "T-11 — CA-17 : aucun tag ne doit être perdu au passage du virtuel au réel ; " +
                    "transaction_tags = ${rawRows(SQL_TRANSACTION_TAGS)}",
                setOf(tagSanteId, tagProId),
                transactionTagIdsOf(materializedId),
            )
            assertEquals(
                "T-11 — CA-17 : les liens de SERIE-M ne doivent pas bouger ; " +
                    "series_tags = ${rawRows(SQL_SERIES_TAGS)}",
                serieMTagsBefore,
                seriesTagIdsOf(serieMId),
            )
            assertEquals(
                "T-11 — témoin : les liens de SERIE-B doivent rester intacts ; " +
                    "series_tags = ${rawRows(SQL_SERIES_TAGS)}",
                serieBTagsBefore,
                seriesTagIdsOf(serieBId),
            )
        }

    // ==============================================================================================
    // Jeu de données
    // ==============================================================================================

    /**
     * Crée le socle commun : un compte, une catégorie, trois tags, TX-TEMOIN et SERIE-B.
     *
     * TX-TEMOIN et SERIE-B ne sont le sujet d'aucun cas : ce sont les témoins qui rendent
     * démontrables la localité des écritures (I-4) et le filtre par série de `resolveSlot`.
     */
    private suspend fun seedBase() {
        accountId = db.accountDao().upsert(
            AccountEntity(
                name = "Compte courant",
                type = AccountType.CHECKING,
                initialBalance = 250_000L,
                balanceUpdatedAt = 0L,
                colorArgb = 0,
                icon = "wallet",
            ),
        )
        categoryId = db.categoryDao().upsert(
            CategoryEntity(
                name = "Dépenses",
                type = TransactionType.EXPENSE,
                colorArgb = 0,
                icon = "category",
                parentCategoryId = null,
            ),
        )

        tagSanteId = db.tagDao().upsert(TagEntity(name = TAG_SANTE_NAME, colorArgb = 0x11))
        tagProId = db.tagDao().upsert(TagEntity(name = TAG_PRO_NAME, colorArgb = 0x22))
        tagVacancesId = db.tagDao().upsert(TagEntity(name = TAG_VACANCES_NAME, colorArgb = 0x33))

        txTemoinId = createTransaction(
            edition(
                title = TX_TEMOIN_TITLE,
                amount = TX_TEMOIN_AMOUNT,
                date = marsSlot,
                tagIds = listOf(tagSanteId),
            ),
        )

        createTransaction(
            edition(
                title = SERIE_B_TITLE,
                amount = SERIE_B_AMOUNT,
                date = marsSlot,
                tagIds = listOf(tagVacancesId),
                frequency = RecurrenceFrequency.MONTHLY,
            ),
        )
        serieBId = seriesIdOf(SERIE_B_TITLE)
    }

    private suspend fun createTxPonct(tagIds: List<Long>): Long = createTransaction(
        edition(
            title = TX_PONCT_TITLE,
            amount = TX_PONCT_AMOUNT,
            date = marsSlot,
            tagIds = tagIds,
        ),
    )

    private suspend fun createSerieM(): Long {
        createTransaction(
            edition(
                title = SERIE_M_TITLE,
                amount = SERIE_M_AMOUNT,
                date = marsSlot,
                tagIds = listOf(tagSanteId, tagProId),
                frequency = RecurrenceFrequency.MONTHLY,
            ),
        )
        return seriesIdOf(SERIE_M_TITLE)
    }

    private fun edition(
        title: String,
        amount: Long,
        date: Long,
        tagIds: List<Long>,
        frequency: RecurrenceFrequency = RecurrenceFrequency.NONE,
    ) = TransactionEdition(
        title = title,
        amount = amount,
        type = TransactionType.EXPENSE,
        date = date,
        accountId = accountId,
        categoryId = categoryId,
        note = null,
        status = TransactionStatus.PLANNED,
        frequency = frequency,
        interval = 1,
        daysOfWeek = emptySet(),
        endDate = null,
        maxOccurrences = null,
        linkedGoalId = null,
        linkedLoanId = null,
        tagIds = tagIds,
    )

    private suspend fun seriesIdOf(title: String): Long = requireNotNull(
        db.recurringSeriesDao().getByTitle(title)?.id,
    ) { "Montage — série « $title » introuvable après sa création" }

    // ==============================================================================================
    // Constats d'état (SQL brut, limité au test)
    // ==============================================================================================

    private data class PersistedFields(
        val type: TransactionType,
        val amountCents: Long,
        val title: String,
        val date: Long,
        val categoryId: Long,
        val accountId: Long,
        val status: TransactionStatus,
    )

    private suspend fun persistedFieldsOf(txId: Long): PersistedFields? =
        transactionRepo.getById(txId)?.transaction?.let {
            PersistedFields(
                type = it.type,
                amountCents = it.amount,
                title = it.title,
                date = it.date,
                categoryId = it.categoryId,
                accountId = it.accountId,
                status = it.status,
            )
        }

    private fun transactionTagIdsOf(txId: Long): Set<Long> =
        rawRows("SELECT tagId FROM transaction_tags WHERE transactionId = $txId ORDER BY tagId")
            .map { it.toLong() }.toSet()

    private fun seriesTagIdsOf(seriesId: Long): Set<Long> =
        rawRows("SELECT tagId FROM series_tags WHERE seriesId = $seriesId ORDER BY tagId")
            .map { it.toLong() }.toSet()

    /** I-4 — une écriture reste locale à sa transaction : TX-TEMOIN n'est jamais touchée. */
    private fun assertTemoinIntact(label: String) {
        assertEquals(
            "$label — I-4 : TX-TEMOIN devait conserver exactement son lien vers TAG-SANTE ; " +
                "transaction_tags = ${rawRows(SQL_TRANSACTION_TAGS)}",
            setOf(tagSanteId),
            transactionTagIdsOf(txTemoinId),
        )
    }

    private data class Snapshot(
        val transactions: List<String>,
        val transactionTags: List<String>,
        val seriesTags: List<String>,
    )

    private fun snapshot() = Snapshot(
        transactions = rawRows(SQL_TRANSACTIONS),
        transactionTags = rawRows(SQL_TRANSACTION_TAGS),
        seriesTags = rawRows(SQL_SERIES_TAGS),
    )

    /** I-7 — lire ou afficher les tags d'une occurrence virtuelle n'écrit jamais en base. */
    private fun assertNoWrite(label: String, before: Snapshot) {
        assertEquals(
            "$label — I-7 : une lecture ne doit rien écrire ni modifier dans transactions, " +
                "transaction_tags ni series_tags",
            before,
            snapshot(),
        )
    }

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

    // ==============================================================================================
    // Observation
    // ==============================================================================================

    private suspend fun observeList(start: Long, end: Long): List<TransactionWithRelations> {
        lateinit var emission: List<TransactionWithRelations>
        listUseCase(start, end).test(timeout = TIMEOUT) {
            emission = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        return emission
    }

    private fun tagIdsOfOccurrence(
        rows: List<TransactionWithRelations>,
        seriesId: Long,
    ): Set<Long>? = rows.firstOrNull { it.transaction.seriesId == seriesId }
        ?.tags?.map { it.id }?.toSet()

    /**
     * Identifiant virtuel de l'occurrence d'avril, **tel que la liste le fournit** à l'application.
     *
     * Il n'est jamais recalculé par `RecurrenceEngine.calculateVirtualId` : appeler la fonction
     * testée pour fabriquer l'attendu produirait un oracle tautologique.
     */
    private suspend fun virtualIdOfApril(seriesId: Long): Long {
        val occurrence = observeList(aprilWindowStart, aprilWindowEnd)
            .firstOrNull { it.transaction.seriesId == seriesId }
        assertNotNull(
            "Montage — l'occurrence virtuelle d'avril de la série $seriesId est introuvable en liste",
            occurrence,
        )
        val id = occurrence!!.transaction.id
        assertTrue(
            "Montage — l'identifiant d'une occurrence virtuelle doit être négatif ; obtenu $id",
            id < 0L,
        )
        return id
    }

    /**
     * Consomme les émissions jusqu'à l'ensemble de tags attendu.
     *
     * Les émissions identiques intermédiaires sont tolérées ; un état final faux ne l'est pas.
     * L'absence d'émission est convertie en **échec métier** citant l'attendu et le dernier état
     * observé : sans cela, un état jamais atteint remonterait en `TurbineAssertionError`
     * impossible à rattacher à un CA.
     */
    private suspend fun awaitDetailTags(
        turbine: ReceiveTurbine<TransactionWithRelations?>,
        label: String,
        expected: Set<Long>,
    ) {
        var last: Any? = "aucune émission"
        repeat(MAX_EMISSIONS) {
            val emission = try {
                turbine.awaitItem()
            } catch (noEmission: AssertionError) {
                fail(
                    "$label — état attendu jamais atteint. Attendu : $expected. " +
                        "Dernier état observé : $last. (${noEmission.message})",
                )
                error("unreachable")
            }
            last = emission?.tags?.map { it.id }?.toSet()
            if (last == expected) return
        }
        fail(
            "$label — état attendu jamais atteint après $MAX_EMISSIONS émissions. " +
                "Attendu : $expected. Dernier état observé : $last.",
        )
    }

    private suspend fun awaitListTags(
        turbine: ReceiveTurbine<List<TransactionWithRelations>>,
        label: String,
        seriesId: Long,
        expected: Set<Long>,
    ) {
        var last: Any? = "aucune émission"
        repeat(MAX_EMISSIONS) {
            val emission = try {
                turbine.awaitItem()
            } catch (noEmission: AssertionError) {
                fail(
                    "$label — état attendu jamais atteint. Attendu : $expected. " +
                        "Dernier état observé : $last. (${noEmission.message})",
                )
                error("unreachable")
            }
            last = tagIdsOfOccurrence(emission, seriesId)
            if (last == expected) return
        }
        fail(
            "$label — état attendu jamais atteint après $MAX_EMISSIONS émissions. " +
                "Attendu : $expected. Dernier état observé : $last.",
        )
    }

    // ==============================================================================================
    // Dates
    // ==============================================================================================

    private val marsSlot: Long = instantAt(2026, 3, 10)
    private val avrilSlot: Long = instantAt(2026, 4, 10)

    /** Fenêtre d'avril, bornes locales : la cardinalité « exactement deux » de T-08 en dépend. */
    private val aprilWindowStart: Long =
        LocalDate.of(2026, 4, 1).atStartOfDay(ZONE).toInstant().toEpochMilli()
    private val aprilWindowEnd: Long =
        LocalDate.of(2026, 4, 30).atTime(23, 59, 59).atZone(ZONE).toInstant().toEpochMilli()

    private fun instantAt(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day).atTime(9, 0).atZone(ZONE).toInstant().toEpochMilli()

    private companion object {
        const val ZONE_ID = "Europe/Paris"
        val ZONE: ZoneId = ZoneId.of(ZONE_ID)
        val TIMEOUT = 10.seconds
        const val MAX_EMISSIONS = 20

        const val TAG_SANTE_NAME = "Santé"
        const val TAG_PRO_NAME = "Pro"
        const val TAG_VACANCES_NAME = "Vacances"

        /** Libellés et montants tous distincts : aucune confusion de ligne ne peut tomber juste. */
        const val TX_PONCT_TITLE = "Consultation ponctuelle"
        const val TX_PONCT_AMOUNT = 4_550L
        const val TX_TEMOIN_TITLE = "Pharmacie témoin"
        const val TX_TEMOIN_AMOUNT = 7_320L
        const val TX_SANS_TAG_TITLE = "Course sans tag"
        const val TX_SANS_TAG_AMOUNT = 9_990L
        const val SERIE_M_TITLE = "Mutuelle mensuelle"
        const val SERIE_M_AMOUNT = 12_900L
        const val SERIE_B_TITLE = "Abonnement voyage"
        const val SERIE_B_AMOUNT = 3_140L

        const val SQL_TRANSACTIONS =
            "SELECT id, title, amount, seriesId, seriesDate, isException, deleted " +
                "FROM transactions ORDER BY id"
        const val SQL_TRANSACTION_TAGS =
            "SELECT transactionId, tagId FROM transaction_tags ORDER BY transactionId, tagId"
        const val SQL_SERIES_TAGS =
            "SELECT seriesId, tagId FROM series_tags ORDER BY seriesId, tagId"
        const val SQL_TAGS = "SELECT id, name FROM tags ORDER BY id"
    }
}

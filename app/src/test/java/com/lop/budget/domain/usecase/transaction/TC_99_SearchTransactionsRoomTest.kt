package com.lop.budget.domain.usecase.transaction

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.lop.budget.data.local.LopDatabase
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.CategoryEntity
import com.lop.budget.data.local.entity.RecurringSeriesEntity
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone
import kotlin.time.Duration.Companion.seconds

/**
 * TC-99 — Recherche : occurrences virtuelles, tombstones et lecture sans écriture (US LOP-70).
 *
 * ## Niveau
 * Intégration sur base réelle, hors UI. Un mock ne verrait pas les `INSERT` : c'est précisément
 * leur absence qu'il faut prouver (I-1). Aucun mock, fake ou spy sur la chaîne.
 *
 * ## Chaîne réellement exercée
 * `SearchTransactionsUseCase.invoke(query, …, startDate, endDate)`
 *   → `ObserveTransactionsUseCase.invoke(start, end)` (réel)
 *   → `TransactionRepository.observeForMerge` / `observeActiveSeries` / `observeAllSeriesTags`
 *   → `AccountRepository.observeAll`, `CategoryRepository.observeAll`
 *   → `TransactionDao` / `RecurringSeriesDao` → Room en mémoire (SQLite natif Robolectric)
 *   → `mergeRealAndVirtual` → `RecurrenceEngine.generateOccurrences`
 *
 * ## Correspondance cas → CA / invariant → fonction de production
 * ```
 * N-01   CA-14 (I-1, I-2)   generateOccurrences + mergeRealAndVirtual — virtuelles non persistées
 * N-02   CA-18 (I-2, I-6)   occupiedSlots — l'exception remplace la virtuelle de son slot
 * N-03   CA-15 (I-3)        visibleReal — `!deleted`, sur une ligne dont le titre matche
 * N-04   CA-15, CA-18       occupiedSlots (tombstone inclus) + visibleReal — slot ni rendu ni régénéré
 * N-05   CA-14, CA-15 (I-1) toute la chaîne — deux lectures, zéro écriture
 * N-06   CA-01 (I-1, I-4)   SearchTransactionsUseCase — court-circuit de la requête vide
 * ```
 *
 * ## Hypothèses levées (la fiche ne les tranche pas ; arbitrées le 12 septembre 2026)
 * 1. **N-06 est joué sans dates** (`startDate = endDate = null`). La fiche écrit « requête vide
 *    **sur P** », mais CA-01 définit la requête vide comme « texte blank, aucun filtre, **dates
 *    absentes** », et la période est un filtre au sens du périmètre de l'US. Lu littéralement,
 *    N-06 exigerait qu'un texte blank assorti de bornes rende une liste vide — ce qui viderait le
 *    relevé mensuel, `MonthlyTransactionsViewModel` appelant le moteur avec `query = ""` et les
 *    bornes du mois (MonthlyTransactionsViewModel.kt:178). Oubli de rédaction confirmé côté
 *    produit : c'est CA-01 qui fait foi.
 * 2. **EXC n'est pas déplacée** : `date = seriesDate = 2026-03-05 09:00`. La fiche donne le slot,
 *    pas la date d'affichage ; une exception déplacée relève du ticket 49.
 * 3. **Heure des slots = 09:00**, héritée de `SER.startDate`. EXC et TOMB portent exactement
 *    l'instant du slot généré : à la milliseconde près, sinon ils ne masquent rien et N-02/N-04
 *    échoueraient pour une mauvaise raison.
 * 4. **TOMB est titrée « Netflix »**. Avec un titre non matchant, N-04 serait un oracle vacant :
 *    l'absence prouverait le filtre texte, pas l'exclusion du tombstone.
 * 5. **SUP et TOMB** partagent le compte, la catégorie, le type et le montant de SER — la fiche ne
 *    les donne pas et aucun cas n'en dépend.
 * 6. **Un `@Test` par cas, base neuve, semis cumulatif** (« base neuve par variante » + « Ajouter
 *    EXC / SUP / TOMB ») : N-03 sème SER+EXC+SUP, N-04 et N-05 sèment tout.
 * 7. **Cardinalités de N-03 et N-04** déduites de la matrice : 4 lignes pour N-03 (EXC + 3
 *    virtuelles), 3 pour N-04 (EXC de mars + virtuelles de février et mai).
 * 8. L'unicité de slot est assertée sur **tous** les cas, pas seulement N-02 et N-04 : c'est le
 *    même invariant I-6, et l'asserter partout ne peut produire aucun faux vert.
 *
 * ## Sensibilité prouvée par mutation (12 septembre 2026)
 * Les six cas sont verts du premier coup : un vert non falsifiable ne prouverait rien. Cinq
 * mutations temporaires de la production ont été appliquées **puis retirées** (arbre de production
 * vérifié propre après coup).
 * ```
 * M1  `visibleReal` sans la garde `!deleted`        → N-03, N-04, N-05 rouges
 * M2  `occupiedSlots` ignore les lignes supprimées  → N-04, N-05 rouges (la virtuelle d'avril repousse)
 * M3  plus aucun masquage de slot occupé           → N-02, N-03, N-04, N-05 rouges
 * M4  court-circuit de la requête vide désactivé   → N-06 rouge (la fenêtre par défaut est rendue)
 * M5  une écriture provoquée par la lecture        → N-01 à N-05 rouges sur `assertNoWrite`
 * ```
 * M5 laisse **N-06 vert, et c'est correct** : la requête vide court-circuite avant toute lecture de
 * la source, donc aucune écriture n'y est atteignable. Son `assertNoWrite` documente CA-01 sans
 * pouvoir échouer — c'est la seule assertion non falsifiable du fichier, et elle est assumée telle.
 *
 * ## Anomalies connues
 * Aucune à l'écriture. En cas de rouge :
 * - N-04 → la fusion est en cause (`mergeRealAndVirtual`), ANO à rattacher au **ticket 49**, pas à
 *   la recherche.
 * - Doublon de slot sur une émission transitoire de N-02 ou N-05 → même cause racine que
 *   **LOP-118** (`combine` sur deux requêtes Room notifiées séparément), pas une nouvelle ANO.
 * Dans les deux cas l'oracle reste celui de la spécification : il n'est pas assoupli.
 *
 * ## Hors périmètre (ne pas revendiquer couvert par ce fichier)
 * - Accents (CA-04), montant (CA-05), filtres compte / catégorie / type / statut / tag
 *   (CA-06 à CA-11), fenêtre par défaut (CA-13) : fiches JVM TC-97 et TC-98.
 * - Tri par date croissante et départage à date égale (CA-16) : TC-98. Ce fichier compare des
 *   **ensembles**, il n'impose aucun ordre.
 * - Grille de récurrence, bornes de série, exceptions déplacées : ticket 49.
 * - Masquage undo `pendingDeletes` : ticket 51 et l'écran.
 * - Performance et volumétrie.
 *
 * ## Exécution
 * `./gradlew :app:testDebugUnitTest --tests "*SearchTransactionsRoomTest"`
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class SearchTransactionsRoomTest {

    // --- Harnais ------------------------------------------------------------------------------

    private lateinit var db: LopDatabase
    private lateinit var useCase: SearchTransactionsUseCase

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

        val transactionRepo = TransactionRepository(db.transactionDao(), db.recurringSeriesDao())
        useCase = SearchTransactionsUseCase(
            ObserveTransactionsUseCase(
                transactionRepo,
                AccountRepository(db.accountDao()),
                CategoryRepository(db.categoryDao()),
            ),
            // `invoke` lit l'horloge à chaque appel, y compris quand les deux bornes sont fournies
            // (SearchTransactionsUseCase.kt:72). Figée pour que N-06, seul cas sans bornes, ait une
            // fenêtre par défaut reproductible.
            Clock.fixed(Instant.parse("2026-03-15T12:00:00Z"), ZONE),
        )
    }

    @After
    fun tearDown() {
        db.close()
        TimeZone.setDefault(defaultTimeZone)
        Locale.setDefault(defaultLocale)
    }

    // --- Occurrences virtuelles ---------------------------------------------------------------

    /**
     * N-01 — Given SER seule en base, When on cherche « netflix » sur P, Then les quatre échéances
     * de février à mai sont trouvables sans avoir jamais été enregistrées (CA-14, I-1, I-2).
     */
    @Test
    fun `N-01 - une serie non materialisee est trouvable en quatre occurrences virtuelles`() =
        runTest {
            seedSeries()
            val before = snapshot()

            val result = search("netflix")

            assertRows(
                "N-01 (CA-14)",
                listOf(virtual(FEB_05), virtual(MAR_05), virtual(APR_05), virtual(MAY_05)),
                result,
            )
            val ids = result.map { it.transaction.id }
            ids.forEach { id ->
                assertTrue(
                    "N-01 — CA-14 : une occurrence virtuelle doit porter un ID strictement négatif " +
                        "(obtenu $id sur ${ids.joinToString()})",
                    id < 0,
                )
            }
            assertEquals(
                "N-01 — CA-14 : les quatre slots doivent porter des ID distincts (obtenus ${ids.joinToString()})",
                ids.size,
                ids.toSet().size,
            )
            assertNoWrite("N-01", before)
        }

    /**
     * N-02 — Given EXC matérialisée sur le slot de mars, When on cherche « netflix » sur P, Then la
     * ligne persistée remplace sa virtuelle et aucun slot n'est rendu deux fois (CA-18, I-2, I-6).
     */
    @Test
    fun `N-02 - une exception materialisee remplace la virtuelle de son slot sans la doubler`() =
        runTest {
            seedSeries()
            val exceptionId = seedException()
            val before = snapshot()

            val result = search("netflix")

            assertRows(
                "N-02 (CA-18)",
                listOf(virtual(FEB_05), exception(), virtual(APR_05), virtual(MAY_05)),
                result,
            )
            assertEquals(
                "N-02 — CA-18 : la ligne de mars doit être la ligne persistée EXC, avec son ID réel",
                exceptionId,
                result.single { it.transaction.seriesDate == MAR_05 }.transaction.id,
            )
            assertNoWrite("N-02", before)
        }

    // --- Suppressions -------------------------------------------------------------------------

    /**
     * N-03 — Given SUP soft-deletée dont le titre matche, When on cherche « netflix » sur P, Then
     * elle est absente des résultats alors qu'elle existe toujours en base (CA-15, I-3).
     */
    @Test
    fun `N-03 - une ligne soft-deletee dont le titre matche reste absente des resultats`() =
        runTest {
            seedSeries()
            seedException()
            val deletedId = seedSoftDeleted()
            val before = snapshot()

            val result = search("netflix")

            assertRows(
                "N-03 (CA-15)",
                listOf(virtual(FEB_05), exception(), virtual(APR_05), virtual(MAY_05)),
                result,
            )
            assertEquals(
                "N-03 — CA-15/I-3 : « ${TITLE_DELETED} » matche « netflix » et doit pourtant rester " +
                    "hors résultats",
                emptyList<Long>(),
                result.map { it.transaction.id }.filter { it == deletedId },
            )
            assertEquals(
                "N-03 — I-3 : la ligne soft-deletée doit rester réellement présente en base après la recherche",
                1,
                rawRows("SELECT id FROM transactions WHERE id = $deletedId AND deleted = 1").size,
            )
            assertNoWrite("N-03", before)
        }

    /**
     * N-04 — Given TOMB, tombstone du slot d'avril, When on cherche « netflix » sur P, Then avril
     * ne rend aucune ligne : ni la ligne supprimée, ni une virtuelle régénérée (CA-15, CA-18).
     */
    @Test
    fun `N-04 - un slot supprime ne rend ni sa ligne ni une virtuelle regeneree`() = runTest {
        seedSeries()
        seedException()
        seedSoftDeleted()
        val tombstoneId = seedTombstone()
        val before = snapshot()

        val result = search("netflix")

        assertRows(
            "N-04 (CA-15/CA-18)",
            listOf(virtual(FEB_05), exception(), virtual(MAY_05)),
            result,
        )
        assertEquals(
            "N-04 — CA-15/CA-18 : avril ne doit rendre aucune ligne, ni tombstone ni virtuelle " +
                "régénérée. Lignes d'avril obtenues : " + describe(result.filter { it.transaction.date in APRIL }),
            0,
            result.count { it.transaction.date in APRIL },
        )
        assertEquals(
            "N-04 — CA-15/I-3 : le tombstone lui-même ne doit jamais figurer dans les résultats",
            emptyList<Long>(),
            result.map { it.transaction.id }.filter { it == tombstoneId },
        )
        assertNoWrite("N-04", before)
    }

    // --- Lecture sans écriture ------------------------------------------------------------------

    /**
     * N-05 — Given l'état complet, When on cherche deux fois de suite sans écriture intermédiaire,
     * Then le même ensemble est rendu et aucune des deux tables n'a bougé (CA-14, CA-15, I-1).
     */
    @Test
    fun `N-05 - deux recherches successives rendent le meme ensemble et n'ecrivent rien`() =
        runTest {
            seedSeries()
            seedException()
            seedSoftDeleted()
            seedTombstone()
            val before = snapshot()

            val first = search("netflix")
            val afterFirst = snapshot()
            val second = search("netflix")

            val expected = listOf(virtual(FEB_05), exception(), virtual(MAY_05))
            assertRows("N-05 première recherche (CA-14/CA-15)", expected, first)
            assertRows("N-05 seconde recherche (CA-14/CA-15)", expected, second)
            assertEquals(
                "N-05 — I-1 : deux recherches successives doivent rendre exactement le même ensemble",
                first.map { it.row() }.sortedWith(ROW_ORDER),
                second.map { it.row() }.sortedWith(ROW_ORDER),
            )
            assertNoWrite("N-05 après la première recherche", before)
            assertNoWrite("N-05 après la seconde recherche", afterFirst)
        }

    /**
     * N-06 — Given l'état complet, When la requête est vide au sens de CA-01 (texte blank, aucun
     * filtre, dates absentes), Then la liste est vide sans être `null` et rien n'est écrit
     * (CA-01, I-1, I-4). Voir l'hypothèse 1 pour l'écart avec le libellé « sur P » de la fiche.
     */
    @Test
    fun `N-06 - une requete vide rend une liste vide sans rien ecrire`() = runTest {
        seedSeries()
        seedException()
        seedSoftDeleted()
        seedTombstone()
        val before = snapshot()

        val result = search(query = "", startDate = null, endDate = null)

        assertEquals(
            "N-06 — CA-01 : une requête vide doit rendre une liste vide, pas le contenu de la " +
                "fenêtre par défaut. Obtenu : " + describe(result),
            0,
            result.size,
        )
        assertNoWrite("N-06", before)
    }

    // ==========================================================================================
    // Jeu de données — ids alloués par Room, conservés sous noms symboliques
    // ==========================================================================================

    private var accountId = 0L
    private var categoryId = 0L
    private var seriesId = 0L

    /** CPT + SER : le compte, la catégorie et la série mensuelle « Netflix » sans fin. */
    private suspend fun seedSeries() {
        accountId = db.accountDao().upsert(
            AccountEntity(
                name = ACCOUNT_NAME,
                type = AccountType.CHECKING,
                initialBalance = 100_000,
                balanceUpdatedAt = 0L,
                colorArgb = 0xFF2196F3.toInt(),
                icon = "wallet",
                archived = false,
            ),
        )
        categoryId = db.categoryDao().upsert(
            CategoryEntity(
                name = CATEGORY_NAME,
                type = TransactionType.EXPENSE,
                colorArgb = 0xFF4CAF50.toInt(),
                icon = "tv",
            ),
        )
        seriesId = db.recurringSeriesDao().upsertSeries(
            RecurringSeriesEntity(
                title = TITLE,
                amount = AMOUNT,
                type = TransactionType.EXPENSE,
                categoryId = categoryId,
                accountId = accountId,
                frequency = RecurrenceFrequency.MONTHLY,
                interval = 1,
                startDate = JAN_05,
                endDate = null,
                maxOccurrences = null,
                daysOfWeek = null,
                isCancelled = false,
                note = null,
            ),
        )
    }

    /** EXC : exception matérialisée du slot de mars, payée, non supprimée. */
    private suspend fun seedException(): Long = insert(
        title = TITLE,
        date = MAR_05,
        status = TransactionStatus.PAID,
        seriesDate = MAR_05,
        isException = true,
    )

    /** SUP : ponctuelle payée puis soft-deletée, dont le titre matche « netflix ». */
    private suspend fun seedSoftDeleted(): Long = insert(
        title = TITLE_DELETED,
        date = MAR_12,
        status = TransactionStatus.PAID,
        seriesDate = null,
        isException = false,
        deleted = true,
    )

    /** TOMB : exception du slot d'avril, supprimée — le slot reste occupé (hypothèse 4). */
    private suspend fun seedTombstone(): Long = insert(
        title = TITLE,
        date = APR_05,
        status = TransactionStatus.PAID,
        seriesDate = APR_05,
        isException = true,
        deleted = true,
    )

    private suspend fun insert(
        title: String,
        date: Long,
        status: TransactionStatus,
        seriesDate: Long?,
        isException: Boolean,
        deleted: Boolean = false,
    ): Long = db.transactionDao().upsert(
        // `id = 0` : Room alloue un ID positif. On ne force jamais un ID négatif, c'est l'oracle.
        TransactionEntity(
            title = title,
            amount = AMOUNT,
            type = TransactionType.EXPENSE,
            status = status,
            kind = TransactionKind.STANDARD,
            date = date,
            accountId = accountId,
            categoryId = categoryId,
            note = null,
            paidAt = if (status == TransactionStatus.PAID) date else null,
            seriesId = if (seriesDate == null) null else seriesId,
            seriesDate = seriesDate,
            isException = isException,
            deleted = deleted,
        ),
    )

    // ==========================================================================================
    // Oracles
    // ==========================================================================================

    /**
     * Projection comparable d'une ligne rendue. [kind] est dérivé du **signe** de l'ID : c'est
     * l'oracle « virtuelle = négatif, persistée = positif » de CA-14, sans jamais écrire de valeur
     * littérale ni appeler `RecurrenceEngine.calculateVirtualId`, qui rendrait l'oracle tautologique.
     */
    private data class Row(
        val kind: String,
        val seriesId: Long?,
        val seriesDate: Long?,
        val date: Long,
        val title: String,
        val amount: Long,
        val type: TransactionType,
        val status: TransactionStatus,
        val isException: Boolean,
        val account: String?,
        val category: String?,
    )

    private fun TransactionWithRelations.row() = Row(
        kind = if (transaction.id < 0) "VIRTUELLE" else "PERSISTEE",
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
    )

    private fun virtual(slot: Long) = Row(
        kind = "VIRTUELLE",
        seriesId = seriesId,
        seriesDate = slot,
        date = slot,
        title = TITLE,
        amount = AMOUNT,
        type = TransactionType.EXPENSE,
        status = TransactionStatus.PLANNED,
        isException = false,
        account = ACCOUNT_NAME,
        category = CATEGORY_NAME,
    )

    private fun exception() = Row(
        kind = "PERSISTEE",
        seriesId = seriesId,
        seriesDate = MAR_05,
        date = MAR_05,
        title = TITLE,
        amount = AMOUNT,
        type = TransactionType.EXPENSE,
        status = TransactionStatus.PAID,
        isException = true,
        account = ACCOUNT_NAME,
        category = CATEGORY_NAME,
    )

    /**
     * Oracle central : unicité du slot `seriesId + seriesDate`, cardinalité exacte, ensemble exact
     * des lignes. Aucun ordre n'est imposé — CA-16 relève de TC-98.
     *
     * L'unicité est vérifiée **en premier** : un doublon de slot fait aussi rater la cardinalité,
     * et c'est le message CA-18 qui nomme la cause, pas le décompte.
     */
    private fun assertRows(
        label: String,
        expected: List<Row>,
        actual: List<TransactionWithRelations>,
    ) {
        assertSlotUniqueness(label, actual)
        assertEquals("$label — cardinalité exacte. Obtenu : ${describe(actual)}", expected.size, actual.size)
        assertEquals(
            "$label — ensemble exact (nature, slot, date, titre, montant, type, statut, compte, catégorie)",
            expected.sortedWith(ROW_ORDER),
            actual.map { it.row() }.sortedWith(ROW_ORDER),
        )
    }

    /** CA-18 / I-6 : une seule représentation par slot `seriesId + seriesDate`. */
    private fun assertSlotUniqueness(label: String, rows: List<TransactionWithRelations>) {
        rows.filter { it.transaction.seriesId != null && it.transaction.seriesDate != null }
            .groupBy { it.transaction.seriesId!! to it.transaction.seriesDate!! }
            .forEach { (slot, colliding) ->
                assertEquals(
                    "$label — CA-18/I-6 : doublon sur le slot (seriesId=${slot.first}, " +
                        "seriesDate=${slot.second}). Lignes en collision : ${describe(colliding)}",
                    1,
                    colliding.size,
                )
            }
    }

    private fun describe(rows: List<TransactionWithRelations>): String =
        rows.joinToString(prefix = "[", postfix = "]") {
            val tx = it.transaction
            "${if (tx.id < 0) "VIRTUELLE" else "PERSISTEE"}(id=${tx.id}, date=${tx.date}, " +
                "seriesDate=${tx.seriesDate}, titre='${tx.title}', statut=${tx.status})"
        }

    // ==========================================================================================
    // Recherche
    // ==========================================================================================

    private suspend fun search(
        query: String,
        startDate: Long? = PERIOD_START,
        endDate: Long? = PERIOD_END,
    ): List<TransactionWithRelations> {
        lateinit var emission: List<TransactionWithRelations>
        // Délai d'échec borné, sans pause fixe ni réabonnement : une absence d'émission doit faire
        // échouer le cas, jamais être rattrapée.
        useCase(query, null, null, startDate, endDate).test(timeout = TIMEOUT) {
            emission = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        return emission
    }

    // ==========================================================================================
    // Snapshots — SELECT de contrôle : inclut les lignes supprimées, que les DAO masquent
    // ==========================================================================================

    private data class Snapshot(val transactions: List<String>, val series: List<String>)

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

    /** I-1 : chercher n'insère, ne matérialise, ne modifie et ne supprime aucune transaction. */
    private fun assertNoWrite(label: String, before: Snapshot) {
        val after = snapshot()
        assertEquals(
            "$label — I-1 : une recherche ne doit rien écrire. Diff transactions — avant : " +
                "${before.transactions - after.transactions.toSet()}, après : " +
                "${after.transactions - before.transactions.toSet()}",
            before.transactions,
            after.transactions,
        )
        assertEquals(
            "$label — I-1 : une recherche ne doit pas toucher aux séries. Diff recurring_series — " +
                "avant : ${before.series - after.series.toSet()}, après : " +
                "${after.series - before.series.toSet()}",
            before.series,
            after.series,
        )
    }

    private companion object {
        const val ZONE_ID = "Europe/Paris"
        val ZONE: ZoneId = ZoneId.of(ZONE_ID)
        val TIMEOUT = 10.seconds

        const val TITLE = "Netflix"
        const val TITLE_DELETED = "Netflix remboursé"
        const val AMOUNT = 1_399L
        const val ACCOUNT_NAME = "Compte courant test"
        const val CATEGORY_NAME = "Abonnements"

        /**
         * Dates et période déclarées **localement** : les cardinalités « exactement 4 / 3 / 0 » en
         * dépendent et ne doivent pas pouvoir changer via un fixture partagé par un autre ticket.
         */
        val JAN_05 = at09(2026, 1, 5)
        val FEB_05 = at09(2026, 2, 5)
        val MAR_05 = at09(2026, 3, 5)
        val MAR_12 = at09(2026, 3, 12)
        val APR_05 = at09(2026, 4, 5)
        val MAY_05 = at09(2026, 5, 5)

        val PERIOD_START = startOfDay(2026, 2, 1)
        val PERIOD_END = endOfDay(2026, 5, 31)
        val APRIL = startOfDay(2026, 4, 1)..endOfDay(2026, 4, 30)

        /** Ordre de comparaison propre au test — jamais celui de la production (CA-16 : TC-98). */
        val ROW_ORDER: Comparator<Row> = compareBy({ it.date }, { it.kind }, { it.title })

        fun at09(year: Int, month: Int, day: Int): Long =
            LocalDate.of(year, month, day).atTime(9, 0).atZone(ZONE).toInstant().toEpochMilli()

        fun startOfDay(year: Int, month: Int, day: Int): Long =
            LocalDate.of(year, month, day).atStartOfDay(ZONE).toInstant().toEpochMilli()

        fun endOfDay(year: Int, month: Int, day: Int): Long =
            LocalDate.of(year, month, day)
                .atTime(23, 59, 59, 999_000_000)
                .atZone(ZONE)
                .toInstant()
                .toEpochMilli()
    }
}

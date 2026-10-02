package com.lop.budget.domain.usecase.transaction

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.ReceiveTurbine
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
import com.lop.budget.domain.RecurrenceEngine
import com.lop.budget.domain.model.AccountType
import com.lop.budget.domain.model.RecurrenceFrequency
import com.lop.budget.domain.model.TransactionKind
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
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
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone
import kotlin.time.Duration.Companion.seconds

/**
 * LOP-7 — Consulter les occurrences récurrentes dans un calendrier interactif.
 * https://app.notion.com/p/38550f34a8c58167861ec6e2eb86cb20
 *
 * ## Niveau
 * Intégration hors UI, sur Room réel : vrai `ObserveRecurringOccurrencesUseCase`, vrais
 * `ObserveTransactionsUseCase` et `ObserveTransactionDetailUseCase`, vrais repositories et DAO, vrai
 * `RecurrenceEngine`. Aucun mock : les notes techniques de l'US exigent la chaîne réelle de lecture
 * pour CA-01, CA-02, CA-04, CA-05 et CA-07.
 *
 * ## Correspondance cas → critère → fonction
 * ```
 * R-01  CA-01 complétude après suppressions      observeUpcoming → ObserveTransactionsUseCase.observeUpcoming
 * R-02  CA-01 valeurs propres de chaque ligne      observeUpcoming
 * R-03  CA-01 exception au-delà de la fin de série observeUpcoming (horizon étendu)
 * R-04  CA-01 ponctuelle / série épuisée           observeUpcoming (null / liste vide)
 * R-05  CA-02 date affichée, isolation de série    observeMonth
 * R-06  CA-02/CA-04 bornes du mois, sélection      observeMonth + selectDay
 * R-07  CA-05 / I-3 revalidation de la cible       resolveTarget
 * R-08  CA-07 mise à jour sans rafraîchissement    observeMonth (flux)
 * R-09  CA-03/CA-06 contexte, ancre conservée      observeContext
 * R-10  I-1 lecture seule                          toutes les lectures
 * ```
 *
 * Les dates attendues sont écrites en clair ; le moteur n'est appelé que pour fabriquer l'ID
 * virtuel d'entrée, jamais un attendu.
 *
 * ## Exécution
 * `./gradlew :app:testDebugUnitTest --tests "*ObserveRecurringOccurrencesRoomTest"`
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ObserveRecurringOccurrencesRoomTest {

    private lateinit var db: LopDatabase
    private lateinit var transactionRepo: TransactionRepository
    private lateinit var detailUseCase: ObserveTransactionDetailUseCase
    private lateinit var useCase: ObserveRecurringOccurrencesUseCase

    private lateinit var defaultTimeZone: TimeZone
    private lateinit var defaultLocale: Locale

    private var accountId = 0L
    private var categoryId = 0L
    private var rentSeriesId = 0L

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
        val accountRepo = AccountRepository(db.accountDao())
        val categoryRepo = CategoryRepository(db.categoryDao())
        detailUseCase = ObserveTransactionDetailUseCase(transactionRepo, accountRepo, categoryRepo)
        useCase = ObserveRecurringOccurrencesUseCase(
            ObserveTransactionsUseCase(transactionRepo, accountRepo, categoryRepo),
            detailUseCase,
        )
    }

    @After
    fun tearDown() {
        db.close()
        TimeZone.setDefault(defaultTimeZone)
        Locale.setDefault(defaultLocale)
    }

    /**
     * R-01 — Given deux slots supprimés juste après l'occurrence de départ, When on demande l'aperçu,
     * Then il contient exactement les trois échéances visibles suivantes, pas une seule.
     */
    @Test
    fun `R-01 - l'apercu reste complet apres la suppression de slots`() = runTest {
        seedRent(endDate = null)
        insertRow(slot = FEB_28, displayDate = FEB_28, deleted = true)
        insertRow(slot = MAR_31, displayDate = MAR_31, deleted = true)

        val upcoming = first(useCase.observeUpcoming(occurrence(JAN_31)))

        assertEquals(
            "R-01 / CA-01 — trois prochaines échéances visibles, slots supprimés exclus",
            listOf(APR_30, MAY_31, JUN_30),
            upcoming?.map { it.transaction.date },
        )
    }

    /**
     * R-02 — Given une exception payée au montant modifié, When on demande l'aperçu, Then chaque
     * ligne porte ses propres valeurs, pas celles de l'occurrence de départ.
     */
    @Test
    fun `R-02 - chaque ligne de l'apercu porte ses propres valeurs`() = runTest {
        seedRent(endDate = null)
        val exceptionId = insertRow(
            slot = MAR_31,
            displayDate = MAR_31,
            amount = 95_000,
            status = TransactionStatus.PAID,
        )

        val upcoming = checkNotNull(first(useCase.observeUpcoming(occurrence(JAN_31))))

        assertEquals("R-02 — dates", listOf(FEB_28, MAR_31, APR_30), upcoming.map { it.transaction.date })
        assertEquals(
            "R-02 — montants propres",
            listOf(80_000L, 95_000L, 80_000L),
            upcoming.map { it.transaction.amount },
        )
        assertEquals(
            "R-02 — statuts propres",
            listOf(TransactionStatus.PLANNED, TransactionStatus.PAID, TransactionStatus.PLANNED),
            upcoming.map { it.transaction.status },
        )
        assertEquals("R-02 — l'exception garde son identité", exceptionId, upcoming[1].transaction.id)
        assertEquals("R-02 — catégorie restituée", "Logement", upcoming[0].category?.name)
    }

    /**
     * R-03 — Given une série finie dont le dernier slot est déplacé après sa fin, When on demande
     * l'aperçu depuis l'avant-dernier slot, Then l'exception déplacée reste une échéance.
     */
    @Test
    fun `R-03 - une exception deplacee au-dela de la fin de serie reste une echeance`() = runTest {
        seedRent(endDate = MAR_31)
        insertRow(slot = MAR_31, displayDate = APR_15)

        val upcoming = first(useCase.observeUpcoming(occurrence(FEB_28)))

        assertEquals("R-03 / CA-01", listOf(APR_15), upcoming?.map { it.transaction.date })
    }

    /**
     * R-04 — Given une transaction ponctuelle puis une série épuisée, When on demande l'aperçu,
     * Then la ponctuelle n'en a pas (`null`) et la série épuisée en a un vide, qui garde le calendrier.
     */
    @Test
    fun `R-04 - ponctuelle sans apercu, serie epuisee avec apercu vide`() = runTest {
        seedRent(endDate = MAR_31)
        val oneOffId = transactionRepo.upsert(
            TransactionEntity(
                title = "Courses",
                amount = 4_200,
                type = TransactionType.EXPENSE,
                status = TransactionStatus.PAID,
                date = FEB_28,
                accountId = accountId,
                categoryId = categoryId,
            ),
        )
        val oneOff = checkNotNull(detailUseCase.getById(oneOffId))

        assertNull("R-04 / CA-01 — une ponctuelle n'a pas d'aperçu", first(useCase.observeUpcoming(oneOff)))
        assertEquals(
            "R-04 / CA-01 — dernier slot : aperçu vide, pas absent",
            emptyList<Long>(),
            first(useCase.observeUpcoming(occurrence(MAR_31)))?.map { it.transaction.date },
        )
    }

    /**
     * R-05 — Given l'exception du 31 janvier déplacée au 2 février et une autre série le 2 février,
     * When on lit janvier puis février, Then l'exception figure le 2 février seulement, et l'autre
     * série n'apparaît jamais (I-2).
     */
    @Test
    fun `R-05 - une exception deplacee figure a sa date affichee, sans autre serie`() = runTest {
        seedRent(endDate = null)
        val otherSeriesId = insertSeries("Abonnement", FEB_02, amount = 1_500)
        val movedId = insertRow(slot = JAN_31, displayDate = FEB_02)

        val january = first(useCase.observeMonth(rentSeriesId, YearMonth.of(2027, 1), ZONE))
        val february = first(useCase.observeMonth(rentSeriesId, YearMonth.of(2027, 2), ZONE))

        assertEquals("R-05 / CA-02 — janvier vide", emptyMap<LocalDate, Int>(), counts(january))
        assertEquals(
            "R-05 / CA-02 — février : le 2 (exception) et le 28 (virtuelle)",
            mapOf(LocalDate.of(2027, 2, 2) to 1, LocalDate.of(2027, 2, 28) to 1),
            counts(february),
        )
        val onFeb2 = february.byDay.getValue(LocalDate.of(2027, 2, 2)).single()
        assertEquals("R-05 — la ligne du 2 est l'exception déplacée", movedId, onFeb2.transaction.id)
        assertTrue(
            "R-05 / I-2 — aucune occurrence de l'autre série",
            february.byDay.values.flatten().none { it.transaction.seriesId == otherSeriesId },
        )
    }

    /**
     * R-06 — Given une exception au premier instant du mois et une autre au dernier, When on lit le
     * mois et qu'on sélectionne des jours, Then les deux bornes sont incluses et la sélection rend
     * vide / unique / plusieurs, deux slots du même jour restant deux choix distincts (CA-04).
     */
    @Test
    fun `R-06 - bornes du mois incluses et selection d'un jour`() = runTest {
        seedRent(endDate = null)
        insertRow(slot = FEB_28, displayDate = MAR_01_MIDNIGHT)
        val lateId = insertRow(slot = APR_30, displayDate = MAR_31_LAST_MS)

        val march = first(useCase.observeMonth(rentSeriesId, YearMonth.of(2027, 3), ZONE))

        assertEquals(
            "R-06 / CA-02 — premier et dernier jours inclus",
            mapOf(LocalDate.of(2027, 3, 1) to 1, LocalDate.of(2027, 3, 31) to 2),
            counts(march),
        )
        assertEquals(
            "R-06 / CA-04 — jour sans occurrence",
            DaySelection.Empty,
            useCase.selectDay(march, LocalDate.of(2027, 3, 15)),
        )
        val onFirstDay = useCase.selectDay(march, LocalDate.of(2027, 3, 1))
        assertTrue("R-06 / CA-04 — une seule occurrence, obtenu $onFirstDay", onFirstDay is DaySelection.Single)
        val onLastDay = useCase.selectDay(march, LocalDate.of(2027, 3, 31))
        assertTrue(
            "R-06 / CA-04 — deux occurrences : choix explicite, pas d'ouverture directe ; obtenu $onLastDay",
            onLastDay is DaySelection.Multiple,
        )
        assertEquals(
            "R-06 / CA-04 — deux slots distincts le même jour",
            setOf(RecurrenceEngine.calculateVirtualId(rentSeriesId, MAR_31), lateId),
            (onLastDay as DaySelection.Multiple).occurrences.map { it.transaction.id }.toSet(),
        )
    }

    /**
     * R-07 — Given une cible affichée, When elle est matérialisée, supprimée, ou que son jour est
     * occupé par une autre exception avant le clic, Then la résolution rend respectivement sa version
     * persistée, « indisponible », et « indisponible » sans substitution (CA-05, I-3).
     */
    @Test
    fun `R-07 - la cible est revalidee au clic sans substitution`() = runTest {
        seedRent(endDate = null)
        val displayed = first(useCase.observeMonth(rentSeriesId, YearMonth.of(2027, 2), ZONE))
            .byDay.getValue(LocalDate.of(2027, 2, 28)).single()
        val start = checkNotNull(OccurrenceRef.of(occurrence(JAN_31)))

        val materializedId = transactionRepo.materializeOccurrence(rentSeriesId, FEB_28)
        assertEquals(
            "R-07a / CA-05 — la version matérialisée prévaut",
            TargetResolution.Available(materializedId),
            useCase.resolveTarget(displayed, start),
        )

        transactionRepo.softDeleteTransaction(materializedId)
        assertEquals(
            "R-07b / CA-05 — cible supprimée",
            TargetResolution.Unavailable,
            useCase.resolveTarget(displayed, start),
        )

        val april = occurrence(APR_30)
        insertRow(slot = MAY_31, displayDate = APR_30)
        assertEquals(
            "R-07c / I-3 — le jour est pris par un autre slot : pas de substitution",
            TargetResolution.Unavailable,
            useCase.resolveTarget(april, start),
        )

        assertEquals(
            "R-07d / CA-04 — l'occurrence de départ n'ouvre pas un second détail",
            TargetResolution.Start,
            useCase.resolveTarget(occurrence(JAN_31), start),
        )
    }

    /**
     * R-08 — Given un mois observé, When une exception change de montant puis est supprimée
     * ailleurs, Then le flux republie ces états sans rafraîchissement manuel (CA-07).
     */
    @Test
    fun `R-08 - le mois observe suit les modifications validees ailleurs`() = runTest {
        seedRent(endDate = null)
        val exceptionId = insertRow(slot = MAR_31, displayDate = MAR_31)
        val day = LocalDate.of(2027, 3, 31)

        useCase.observeMonth(rentSeriesId, YearMonth.of(2027, 3), ZONE).test(timeout = TIMEOUT) {
            awaitUntil { it.byDay[day]?.single()?.transaction?.amount == 80_000L }

            val current = checkNotNull(transactionRepo.getById(exceptionId)).transaction
            transactionRepo.upsert(current.copy(amount = 81_000))
            awaitUntil { it.byDay[day]?.single()?.transaction?.amount == 81_000L }

            transactionRepo.softDeleteTransaction(exceptionId)
            val afterDelete = awaitUntil { it.byDay[day].isNullOrEmpty() }
            assertEquals(
                "R-08 / CA-07 — slot supprimé absent, sans virtuel régénéré",
                0,
                afterDelete.byDay[day].orEmpty().size,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * R-09 — Given l'occurrence de départ du 31 janvier, When on observe le contexte puis qu'elle et
     * la prochaine échéance sont supprimées, Then le calendrier s'ouvre sur la prochaine échéance,
     * puis garde sa série et recalcule l'échéance suivante depuis la même ancre (CA-03, CA-06).
     *
     * La suppression du 28 février est le témoin : sans elle, rien ne distinguerait « ancre gardée »
     * d'« aucune nouvelle émission ».
     */
    @Test
    fun `R-09 - contexte ouvert sur la prochaine echeance et conserve apres disparition du depart`() =
        runTest {
            seedRent(endDate = null)
            val startId = RecurrenceEngine.calculateVirtualId(rentSeriesId, JAN_31)

            useCase.observeContext(startId, ZONE).test(timeout = TIMEOUT) {
                val opened = checkNotNull(awaitItem())
                assertEquals("R-09 / CA-03 — prochaine échéance", FEB_28, opened.nextDue?.transaction?.date)
                assertEquals("R-09 — ouverture sur son jour", LocalDate.of(2027, 2, 28), opened.openingDay)

                insertRow(slot = JAN_31, displayDate = JAN_31, deleted = true)
                insertRow(slot = FEB_28, displayDate = FEB_28, deleted = true)
                val kept = awaitUntil { context ->
                    assertNotNull("R-09 / CA-06 — la disparition du départ ne ferme pas le contexte", context)
                    context?.nextDue?.transaction?.date == MAR_31
                }
                assertEquals("R-09 / CA-06 — même série explorée", rentSeriesId, kept?.seriesId)
                assertEquals("R-09 — ouverture recalculée", LocalDate.of(2027, 3, 31), kept?.openingDay)
                cancelAndIgnoreRemainingEvents()
            }
        }

    /**
     * R-10 — Given une série et une exception, When on enchaîne toutes les lectures du parcours,
     * Then aucune ligne n'est créée ni modifiée (I-1).
     */
    @Test
    fun `R-10 - les lectures du parcours n'ecrivent rien`() = runTest {
        seedRent(endDate = null)
        insertRow(slot = MAR_31, displayDate = APR_15, amount = 90_000)
        val before = snapshot()

        val start = occurrence(JAN_31)
        first(useCase.observeUpcoming(start))
        first(useCase.observeContext(start.transaction.id, ZONE))
        val february = first(useCase.observeMonth(rentSeriesId, YearMonth.of(2027, 2), ZONE))
        useCase.selectDay(february, LocalDate.of(2027, 2, 28))
        useCase.resolveTarget(february.byDay.getValue(LocalDate.of(2027, 2, 28)).single(), OccurrenceRef.of(start))

        assertEquals("R-10 / I-1 — table des transactions inchangée", before, snapshot())
    }

    // --- Jeu de données -------------------------------------------------------------------------

    private suspend fun seedRent(endDate: Long?) {
        accountId = db.accountDao().upsert(
            AccountEntity(
                name = "Compte courant test",
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
                name = "Logement",
                type = TransactionType.EXPENSE,
                colorArgb = 0xFF6D4C41.toInt(),
                icon = "home",
            ),
        )
        rentSeriesId = insertSeries(RENT_TITLE, JAN_31, amount = 80_000, endDate = endDate)
    }

    private suspend fun insertSeries(title: String, start: Long, amount: Long, endDate: Long? = null): Long =
        transactionRepo.upsertSeries(
            RecurringSeriesEntity(
                title = title,
                amount = amount,
                type = TransactionType.EXPENSE,
                categoryId = categoryId,
                accountId = accountId,
                frequency = RecurrenceFrequency.MONTHLY,
                interval = 1,
                startDate = start,
                endDate = endDate,
                maxOccurrences = null,
                daysOfWeek = null,
                isCancelled = false,
                note = null,
                linkedGoalId = null,
                linkedLoanId = null,
            ),
        )

    /** Ligne physique occupant un slot du loyer. `id = 0` : Room alloue l'ID positif. */
    private suspend fun insertRow(
        slot: Long,
        displayDate: Long,
        deleted: Boolean = false,
        amount: Long = 80_000,
        status: TransactionStatus = TransactionStatus.PLANNED,
    ): Long = transactionRepo.upsert(
        TransactionEntity(
            title = RENT_TITLE,
            amount = amount,
            type = TransactionType.EXPENSE,
            status = status,
            kind = TransactionKind.STANDARD,
            date = displayDate,
            accountId = accountId,
            categoryId = categoryId,
            seriesId = rentSeriesId,
            seriesDate = slot,
            isException = true,
            deleted = deleted,
            paidAt = if (status == TransactionStatus.PAID) displayDate else null,
        ),
    )

    /** Occurrence visible du loyer à son slot, telle que le détail la résout. */
    private suspend fun occurrence(slot: Long): TransactionWithRelations =
        checkNotNull(detailUseCase.getById(RecurrenceEngine.calculateVirtualId(rentSeriesId, slot))) {
            "slot $slot introuvable"
        }

    private fun snapshot(): List<String> = db.query(
        "SELECT id, amount, date, seriesDate, status, deleted FROM transactions ORDER BY id",
        null,
    ).use { c ->
        buildList {
            while (c.moveToNext()) add((0 until c.columnCount).joinToString("|") { c.getString(it) ?: "null" })
        }
    }

    private fun counts(month: MonthOccurrences): Map<LocalDate, Int> = month.byDay.mapValues { it.value.size }

    // --- Observation ----------------------------------------------------------------------------

    private suspend fun <T> first(flow: Flow<T>): T {
        var emission: Any? = NONE
        flow.test(timeout = TIMEOUT) {
            emission = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        @Suppress("UNCHECKED_CAST")
        return emission as T
    }

    private suspend fun <T> ReceiveTurbine<T>.awaitUntil(predicate: (T) -> Boolean): T {
        while (true) {
            val item = awaitItem()
            if (predicate(item)) return item
        }
    }

    private companion object {
        const val ZONE_ID = "Europe/Paris"
        val ZONE: ZoneId = ZoneId.of(ZONE_ID)
        val TIMEOUT = 10.seconds
        val NONE = Any()

        const val RENT_TITLE = "Loyer"

        fun at09(year: Int, month: Int, day: Int): Long =
            LocalDate.of(year, month, day).atTime(9, 0).atZone(ZONE).toInstant().toEpochMilli()

        /** Slots de la série mensuelle ancrée au 31 : rabattement au dernier jour des mois courts. */
        val JAN_31 = at09(2027, 1, 31)
        val FEB_28 = at09(2027, 2, 28)
        val MAR_31 = at09(2027, 3, 31)
        val APR_30 = at09(2027, 4, 30)
        val MAY_31 = at09(2027, 5, 31)
        val JUN_30 = at09(2027, 6, 30)

        val FEB_02 = at09(2027, 2, 2)
        val APR_15 = at09(2027, 4, 15)
        val MAR_01_MIDNIGHT: Long =
            LocalDate.of(2027, 3, 1).atStartOfDay(ZONE).toInstant().toEpochMilli()
        val MAR_31_LAST_MS: Long =
            LocalDate.of(2027, 3, 31).atTime(LocalTime.MAX).atZone(ZONE).toInstant().toEpochMilli()
    }
}

package com.lop.budget.domain.usecase

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
 * TC-88 — Prochaines occurrences d'une série, sans horizon calendaire arbitraire.
 *
 * ## Origine
 * ANO « Détail : horizon de 5 ans codé en dur pour les prochaines occurrences »
 * https://app.notion.com/p/3d650f34a8c5816ea14cc7cd28f0cbd9 — relevée lors de la revue
 * exploratoire de LOP-49, rattachée à LOP-53 (consulter le détail d'une transaction).
 *
 * ## Niveau
 * Intégration hors UI, sur Room réel : vrai `ObserveTransactionsUseCase`, vrais repositories, vrais
 * DAO, vrai `RecurrenceEngine`. Aucun mock ni fake.
 *
 * ## Chaîne exercée
 * `ObserveTransactionsUseCase.observeUpcoming(seriesId, after, count)`
 *   → `RecurrenceEngine.nextOccurrences` (borne par un **nombre**, pas par une fenêtre)
 *   → `ObserveTransactionsUseCase.invoke` → `observeForMerge` → Room
 *
 * ## Correspondance cas → règle → fonction de production
 * ```
 * U-01  horizon dérivé du calendrier    nextOccurrences + observeUpcoming
 * U-02  I-5 : slot supprimé exclu       mergeRealAndVirtual (tombstone occupant)
 * U-03  I-3 : exception déplacée        mergeRealAndVirtual (slot occupé aux deux clés)
 * U-04  bornes de série respectées      validSlots (endDate)
 * ```
 *
 * ## Oracle discriminant
 * U-01 porte sur une série **annuelle** dont la 6ᵉ échéance tombe six ans plus tard. L'ancienne
 * implémentation demandait une fenêtre de `5 * 365` jours et n'en rendait donc que cinq. Les dates
 * attendues sont écrites en clair, jamais recalculées par le moteur testé.
 *
 * ## Hors périmètre
 * Rendu de la section dans `TransactionDetailScreen`, et cardinalité choisie par le ViewModel
 * (`UPCOMING_COUNT`) : ce fichier teste le contrat du domaine, pas la valeur retenue par l'écran.
 *
 * ## Exécution
 * `./gradlew :app:testDebugUnitTest --tests "*ObserveUpcomingOccurrencesRoomTest"`
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ObserveUpcomingOccurrencesRoomTest {

    private lateinit var db: LopDatabase
    private lateinit var transactionRepo: TransactionRepository
    private lateinit var useCase: ObserveTransactionsUseCase

    private lateinit var defaultTimeZone: TimeZone
    private lateinit var defaultLocale: Locale

    private var accountId = 0L
    private var categoryId = 0L
    private var seriesId = 0L

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
        useCase = ObserveTransactionsUseCase(
            transactionRepo,
            AccountRepository(db.accountDao()),
            CategoryRepository(db.categoryDao()),
        )
    }

    @After
    fun tearDown() {
        db.close()
        TimeZone.setDefault(defaultTimeZone)
        Locale.setDefault(defaultLocale)
    }

    /**
     * U-01 — Given une série annuelle dont la 6ᵉ échéance tombe six ans plus tard, When on demande
     * les 6 prochaines occurrences, Then les six sont rendues, dans l'ordre, aux dates du calendrier
     * de la série.
     *
     * C'est l'oracle de l'ANO : une fenêtre de cinq ans en aurait perdu au moins une.
     */
    @Test
    fun `U-01 - les six prochaines occurrences d'une serie annuelle sont rendues au-dela de cinq ans`() =
        runTest {
            seedYearlySeries(endDate = null)

            assertUpcomingDates(
                label = "U-01",
                after = FIRST_OCCURRENCE,
                count = 6,
                expected = listOf(
                    at09(2027, 2, 10),
                    at09(2028, 2, 10),
                    at09(2029, 2, 10),
                    at09(2030, 2, 10),
                    at09(2031, 2, 10),
                    at09(2032, 2, 10),
                ),
            )
        }

    /**
     * U-02 — Given un slot supprimé parmi les prochaines échéances, When on les demande, Then il est
     * exclu et ne se régénère pas en occurrence virtuelle (I-5).
     *
     * L'horizon reste calé sur la 6ᵉ occurrence **candidate** : masquer un slot rend donc cinq
     * lignes, pas six. C'est le comportement voulu — l'horizon décrit le calendrier de la série, pas
     * un quota à remplir coûte que coûte.
     */
    @Test
    fun `U-02 - un slot supprime est exclu des prochaines occurrences sans se regenerer`() = runTest {
        seedYearlySeries(endDate = null)
        insertSlotRow(slot = at09(2029, 2, 10), displayDate = at09(2029, 2, 10), deleted = true)

        assertUpcomingDates(
            label = "U-02",
            after = FIRST_OCCURRENCE,
            count = 6,
            expected = listOf(
                at09(2027, 2, 10),
                at09(2028, 2, 10),
                at09(2030, 2, 10),
                at09(2031, 2, 10),
                at09(2032, 2, 10),
            ),
        )
    }

    /**
     * U-03 — Given une exception déplacée dans le temps, When on demande les prochaines occurrences,
     * Then elle apparaît à sa date d'affichage avec ses propres valeurs, et le virtuel de son slot
     * d'origine ne réapparaît pas (I-3).
     */
    @Test
    fun `U-03 - une exception deplacee apparait a sa date d'affichage et masque son slot d'origine`() =
        runTest {
            seedYearlySeries(endDate = null)
            insertSlotRow(
                slot = at09(2029, 2, 10),
                displayDate = at09(2029, 3, 15),
                deleted = false,
                amount = 999.0,
                title = MOVED_TITLE,
            )

            val upcoming = collectUpcoming(after = FIRST_OCCURRENCE, count = 6)

            assertEquals(
                "U-03 — dates exactes attendues, l'exception déplacée remplaçant son slot d'origine",
                listOf(
                    at09(2027, 2, 10),
                    at09(2028, 2, 10),
                    at09(2029, 3, 15),
                    at09(2030, 2, 10),
                    at09(2031, 2, 10),
                    at09(2032, 2, 10),
                ),
                upcoming.map { it.transaction.date },
            )

            val moved = upcoming.single { it.transaction.date == at09(2029, 3, 15) }
            assertEquals("U-03 — l'exception porte ses propres valeurs", MOVED_TITLE, moved.transaction.title)
            assertEquals("U-03 — montant de l'exception", 999.0, moved.transaction.amount, 0.0)
            assertEquals(
                "U-03 — I-2 : le slot d'origine est conservé",
                at09(2029, 2, 10),
                moved.transaction.seriesDate,
            )
        }

    /**
     * U-04 — Given une série qui s'arrête avant d'atteindre le nombre demandé, When on demande six
     * occurrences, Then seules celles réellement produites par la série sont rendues, sans erreur.
     */
    @Test
    fun `U-04 - la fin de serie borne le nombre de prochaines occurrences rendues`() = runTest {
        seedYearlySeries(endDate = at09(2028, 12, 31))

        assertUpcomingDates(
            label = "U-04",
            after = FIRST_OCCURRENCE,
            count = 6,
            expected = listOf(at09(2027, 2, 10), at09(2028, 2, 10)),
        )
    }

    // --- Jeu de données -------------------------------------------------------------------------

    private suspend fun seedYearlySeries(endDate: Long?) {
        accountId = db.accountDao().upsert(
            AccountEntity(
                name = "Compte courant test",
                type = AccountType.CHECKING,
                initialBalance = 1_000.0,
                balanceUpdatedAt = 0L,
                colorArgb = 0xFF2196F3.toInt(),
                icon = "wallet",
                archived = false,
            ),
        )
        categoryId = db.categoryDao().upsert(
            CategoryEntity(
                name = "Assurances",
                type = TransactionType.EXPENSE,
                colorArgb = 0xFF6D4C41.toInt(),
                icon = "shield",
            ),
        )
        seriesId = transactionRepo.upsertSeries(
            RecurringSeriesEntity(
                title = SERIES_TITLE,
                amount = 120.0,
                type = TransactionType.EXPENSE,
                categoryId = categoryId,
                accountId = accountId,
                frequency = RecurrenceFrequency.YEARLY,
                interval = 1,
                startDate = FIRST_OCCURRENCE,
                endDate = endDate,
                maxOccurrences = null,
                daysOfWeek = null,
                isCancelled = false,
                note = null,
                linkedGoalId = null,
                linkedDebtId = null,
            ),
        )
    }

    /** Ligne physique occupant un slot de la série. `id = 0` : Room alloue l'ID positif. */
    private suspend fun insertSlotRow(
        slot: Long,
        displayDate: Long,
        deleted: Boolean,
        amount: Double = 120.0,
        title: String = SERIES_TITLE,
    ): Long = transactionRepo.upsert(
        TransactionEntity(
            title = title,
            amount = amount,
            type = TransactionType.EXPENSE,
            status = TransactionStatus.PLANNED,
            kind = TransactionKind.STANDARD,
            date = displayDate,
            accountId = accountId,
            categoryId = categoryId,
            seriesId = seriesId,
            seriesDate = slot,
            isException = true,
            deleted = deleted,
        ),
    )

    // --- Observation et oracles -----------------------------------------------------------------

    private suspend fun collectUpcoming(after: Long, count: Int): List<TransactionWithRelations> {
        lateinit var emission: List<TransactionWithRelations>
        useCase.observeUpcoming(seriesId, after, count).test(timeout = TIMEOUT) {
            emission = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        return emission
    }

    private suspend fun assertUpcomingDates(
        label: String,
        after: Long,
        count: Int,
        expected: List<Long>,
    ) {
        val actual = collectUpcoming(after, count).map { it.transaction.date }
        assertEquals("$label — cardinalité exacte", expected.size, actual.size)
        assertEquals("$label — dates exactes, dans l'ordre chronologique", expected, actual)
    }

    private companion object {
        const val ZONE_ID = "Europe/Paris"
        val ZONE: ZoneId = ZoneId.of(ZONE_ID)
        val TIMEOUT = 10.seconds

        const val SERIES_TITLE = "Assurance annuelle"
        const val MOVED_TITLE = "Assurance ajustée"

        /**
         * Dates fixes déclarées localement : les cardinalités exactes en dépendent et ne doivent pas
         * pouvoir être modifiées par un autre ticket via un fixture partagé.
         */
        val FIRST_OCCURRENCE = at09(2026, 2, 10)

        fun at09(year: Int, month: Int, day: Int): Long =
            LocalDate.of(year, month, day).atTime(9, 0).atZone(ZONE).toInstant().toEpochMilli()
    }
}

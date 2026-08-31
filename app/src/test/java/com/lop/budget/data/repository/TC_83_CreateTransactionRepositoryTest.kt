package com.lop.budget.data.repository

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lop.budget.data.local.LopDatabase
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.CategoryEntity
import com.lop.budget.data.local.entity.TagEntity
import com.lop.budget.domain.model.AccountType
import com.lop.budget.domain.model.RecurrenceFrequency
import com.lop.budget.domain.model.TransactionEdition
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.domain.usecase.CreateTransactionUseCase
import com.lop.budget.domain.usecase.ObserveTransactionsUseCase
import com.lop.budget.domain.usecase.SaveTransactionUseCase
import com.lop.budget.domain.usecase.SyncProgressUseCase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone

/**
 * Room - creation ponctuelle vs recurrente : persistance et liste fusionnee (ticket ref. 83).
 *
 * Niveau : test composant JVM (Robolectric + Room en memoire).
 * Systeme teste : CreateTransactionUseCase -> repos/DAO -> Room -> ObserveTransactionsUseCase.observeBetween.
 * Aucun mock, aucun spy : seuls des composants reels sont instancies.
 *
 * Correspondance R-xx -> CA -> fonction de production :
 *  R-01  CA-06       CreateTransactionUseCase (frequence NONE) -> SaveTransactionUseCase.saveSimple
 *  R-02  CA-07       CreateTransactionUseCase (MONTHLY) -> upsertSeries + observeBetween/merge
 *  R-03  CA-08 I-5   SaveTransactionUseCase.saveSimple (regle de coherence PAID -> paidAt)
 *  R-04a CA-08       Serie recurrente PLANNED : aucune ligne persistee payee pour la serie
 *  R-04b CA-08 I-5   Ponctuel PLANNED : statut persiste et paidAt null (oracle non vacant)
 *  R-05  CA-05       TransactionDao.saveWithTags (note + cross-ref tag)
 *
 * R-04a devient vacant si I-4 est respecte (plus aucune ligne pour la serie) : c'est voulu, il
 * n'est qu'un garde-fou de non-regression. L'oracle CA-08 reellement porte par Room vit dans
 * R-04b (ponctuel PLANNED), que le ticket autorise explicitement ("recurrent (ou ponctuel)").
 *
 * Fenetre d'observation declaree localement (windowStart/windowEnd) plutot qu'heritee : les
 * cardinalites "exactement 3" de R-02 ne doivent pas dependre d'un helper partage modifiable
 * par un autre ticket.
 *
 * ANO connue (non corrigee par ce ticket) : CreateTransactionUseCase appelle
 * transactionRepo.materializeOccurrence(...) pour toute serie recurrente, ce qui insere une
 * TransactionEntity reelle des la creation et viole I-4/CA-07 ("aucune occurrence materialisee
 * a la creation"). R-02 est ecrit avec l'oracle strict du ticket : il est attendu rouge tant que
 * cette anomalie n'est pas corrigee. L'oracle n'est pas assoupli.
 *
 * Hors de ce ticket : portees d'edition/suppression (TC_32, TC_76), mocks UseCase (ticket 82),
 * Maestro/ViewModel. I-6 (masquage du toggle paye en mode recurrent) est une regle de
 * formulaire/ViewModel : elle n'a pas d'equivalent testable au niveau Room et n'est pas couverte ici.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class CreateTransactionRepositoryTest : RepositoryTestInfrastructure {

    override lateinit var db: LopDatabase
    override lateinit var transactionRepo: TransactionRepository
    override lateinit var accountRepo: AccountRepository
    override lateinit var categoryRepo: CategoryRepository
    private lateinit var goalRepo: GoalRepository
    private lateinit var debtRepo: DebtRepository
    private lateinit var syncProgressUseCase: SyncProgressUseCase
    private lateinit var saveTransactionUseCase: SaveTransactionUseCase
    private lateinit var createTransaction: CreateTransactionUseCase
    private lateinit var getTransactions: ObserveTransactionsUseCase

    override val zone: ZoneId = ZoneId.of("Europe/Paris")
    private lateinit var previousTimeZone: TimeZone

    // --- Identifiants du JDD -----------------------------------------------------------------
    override var accountId = 0L
    override var categoryId = 0L

    // Non utilises par ce JDD (JDD dedie, distinct du jeu canonique A/B/punctual des autres tickets).
    override var seriesAId = 0L
    override var seriesBId = 0L
    override var punctualId = 0L

    private var tagId = 0L

    // --- Dates du JDD (ticket 83) ------------------------------------------------------------
    private val punctualDateTime: Long
        get() = LocalDate.of(2024, 1, 15).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    /** Fenetre du ticket : 1 jan -> 31 mars 2024, declaree ici et non heritee. */
    private val windowStart: Long get() = startOfDay(2024, 1, 1)
    private val windowEnd: Long get() = endOfDay(2024, 3, 31)

    private suspend fun observeWindow() = getTransactions(windowStart, windowEnd).first()

    @Before
    fun setUp() {
        previousTimeZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(zone))

        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            LopDatabase::class.java,
        ).allowMainThreadQueries().build()

        transactionRepo = TransactionRepository(db.transactionDao(), db.recurringSeriesDao())
        accountRepo = AccountRepository(db.accountDao())
        categoryRepo = CategoryRepository(db.categoryDao())
        goalRepo = GoalRepository(db.goalDao())
        debtRepo = DebtRepository(db.debtDao())
        syncProgressUseCase = SyncProgressUseCase(transactionRepo, goalRepo, debtRepo)
        saveTransactionUseCase = SaveTransactionUseCase(transactionRepo, syncProgressUseCase)
        createTransaction = CreateTransactionUseCase(transactionRepo, saveTransactionUseCase)
        getTransactions = ObserveTransactionsUseCase(transactionRepo, accountRepo, categoryRepo)
    }

    @After
    fun tearDown() {
        db.close()
        TimeZone.setDefault(previousTimeZone)
    }

    /** Compte + categorie (+ tag) inseres avant chaque scenario ; IDs issus des upsert. */
    private suspend fun seedAccountCategoryAndTag() {
        accountId = db.accountDao().upsert(
            AccountEntity(
                name = "Compte courant",
                type = AccountType.CHECKING,
                initialBalance = 1_000.0,
                colorArgb = 0xFF2196F3.toInt(),
                icon = "wallet",
            ),
        )
        categoryId = db.categoryDao().upsert(
            CategoryEntity(
                name = "Logement",
                type = TransactionType.EXPENSE,
                colorArgb = 0xFF4CAF50.toInt(),
                icon = "home",
            ),
        )
        tagId = db.tagDao().upsert(TagEntity(name = "Perso", colorArgb = 0xFF9C27B0.toInt()))
    }

    // --- JDD : editions ------------------------------------------------------------------------

    private fun punctualEdition(status: TransactionStatus?, tagIds: List<Long> = emptyList()) =
        TransactionEdition(
            title = "TC-create-p",
            amount = 42.5,
            type = TransactionType.EXPENSE,
            date = punctualDateTime,
            accountId = accountId,
            categoryId = categoryId,
            note = "n",
            status = status,
            frequency = RecurrenceFrequency.NONE,
            interval = 1,
            daysOfWeek = emptySet(),
            endDate = null,
            maxOccurrences = null,
            linkedGoalId = null,
            linkedDebtId = null,
            tagIds = tagIds,
        )

    private fun recurringEdition(status: TransactionStatus? = TransactionStatus.PLANNED) =
        TransactionEdition(
            title = "TC-create-r",
            amount = 800.0,
            type = TransactionType.EXPENSE,
            date = januarySlot, // ancrage = date du formulaire = 1 jan 2024
            accountId = accountId,
            categoryId = categoryId,
            note = null,
            status = status,
            frequency = RecurrenceFrequency.MONTHLY,
            interval = 1,
            daysOfWeek = emptySet(),
            endDate = marchSlot, // 1 mars 2024
            maxOccurrences = null,
            linkedGoalId = null,
            linkedDebtId = null,
            tagIds = emptyList(),
        )

    // --- Lecture des cross-ref tags (pas d'API dediee dans TransactionOperations) --------------

    private fun crossRefTransactionIds(tagId: Long): List<Long> {
        val ids = mutableListOf<Long>()
        db.query(
            "SELECT transactionId FROM transaction_tags WHERE tagId = ?",
            arrayOf<Any?>(tagId),
        ).use { cursor ->
            while (cursor.moveToNext()) ids += cursor.getLong(0)
        }
        return ids
    }

    // =======================================================================================
    // R-01
    // =======================================================================================

    @Test
    fun `R-01 - Given the punctual dataset with frequency NONE, When CreateTransactionUseCase runs, Then exactly one transaction row is written without series link`() =
        runTest {
            seedAccountCategoryAndTag()
            val createdId = createTransaction(punctualEdition(status = TransactionStatus.PLANNED))

            val rows = persistedTransactions().filter { it.title == "TC-create-p" }
            assertEquals("Une seule ligne transaction attendue pour ce titre", 1, rows.size)
            val row = rows.single()
            assertNull("Aucun lien serie sur une transaction ponctuelle", row.seriesId)
            assertNull(row.seriesDate)
            assertFalse(row.deleted)

            // CA-06 : type, montant, libelle, date, categorie, compte et statut persistes tels que saisis.
            val created = requireNotNull(transactionRepo.getById(createdId)).transaction
            assertEquals("TC-create-p", created.title)
            assertEquals(42.5, created.amount, 0.0)
            assertEquals(TransactionType.EXPENSE, created.type)
            assertEquals(punctualDateTime, created.date)
            assertEquals(categoryId, created.categoryId)
            assertEquals(accountId, created.accountId)
            assertEquals(TransactionStatus.PLANNED, created.status)
            assertNull("PLANNED => paidAt null (I-5)", created.paidAt)
            assertFalse(created.isException)

            val seriesWithThisTitle = transactionRepo.observeActiveSeries().first()
                .count { it.title == "TC-create-p" }
            assertEquals("Aucune serie ne doit etre creee pour un ponctuel", 0, seriesWithThisTitle)

            val visible = observeWindow()
            assertEquals(1, visible.count { it.transaction.id == createdId })
            assertTrue(
                "Aucun id virtuel ne doit exister pour ce titre",
                visible.none { it.transaction.title == "TC-create-p" && it.transaction.id < 0 },
            )
        }

    // =======================================================================================
    // R-02
    // =======================================================================================

    @Test
    fun `R-02 - Given the recurring dataset MONTHLY interval 1, When CreateTransactionUseCase runs, Then a series is created without materialized transaction and observeBetween exposes 3 virtual occurrences`() =
        runTest {
            seedAccountCategoryAndTag()
            createTransaction(recurringEdition())

            val series = transactionRepo.observeActiveSeries().first().single { it.title == "TC-create-r" }
            assertEquals(januarySlot, series.startDate)
            assertEquals(RecurrenceFrequency.MONTHLY, series.frequency)
            assertEquals(1, series.interval)
            assertFalse(series.isCancelled)

            val materializedRows = persistedTransactions().filter { it.seriesId == series.id }
            assertEquals(
                "I-4/CA-07 : aucune occurrence ne doit etre materialisee a la creation " +
                    "(ANO ouverte si non vide - voir CreateTransactionUseCase.materializeOccurrence)",
                0,
                materializedRows.size,
            )

            val visibleOfSeries = observeWindow()
                .filter { it.transaction.seriesId == series.id }
            assertEquals(3, visibleOfSeries.size)
            assertEquals(
                listOf(januarySlot, februarySlot, marchSlot),
                visibleOfSeries.mapNotNull { it.transaction.seriesDate }.sorted(),
            )
            assertTrue(
                "Les occurrences doivent etre virtuelles (id negatif)",
                visibleOfSeries.all { it.transaction.id < 0 },
            )

            val duplicatedSlots = visibleOfSeries
                .groupBy { it.transaction.seriesId to it.transaction.seriesDate }
                .filterValues { it.size > 1 }
            assertTrue("Slots dupliques detectes : $duplicatedSlots", duplicatedSlots.isEmpty())
        }

    // =======================================================================================
    // R-03
    // =======================================================================================

    @Test
    fun `R-03 - Given the punctual dataset with status PAID, When CreateTransactionUseCase runs, Then the row is persisted as PAID with paidAt set`() =
        runTest {
            seedAccountCategoryAndTag()
            val createdId = createTransaction(punctualEdition(status = TransactionStatus.PAID))

            val created = requireNotNull(transactionRepo.getById(createdId)).transaction
            assertEquals(TransactionStatus.PAID, created.status)
            assertNotNull(created.paidAt)
        }

    // =======================================================================================
    // R-04
    // =======================================================================================

    /**
     * Garde-fou de non-regression : si I-4 est respecte, aucune ligne n'existe pour la serie et
     * l'oracle devient vacant par construction. L'oracle CA-08 porte par Room vit dans R-04b.
     */
    @Test
    fun `R-04a - Given the recurring dataset with status PLANNED, When CreateTransactionUseCase runs, Then the series exists and no persisted row of it is PAID`() =
        runTest {
            seedAccountCategoryAndTag()
            createTransaction(recurringEdition(status = TransactionStatus.PLANNED))

            val series = transactionRepo.observeActiveSeries().first().single { it.title == "TC-create-r" }
            assertFalse(series.isCancelled)

            val rows = persistedTransactions().filter { it.seriesId == series.id }
            rows.forEach { row ->
                val entity = requireNotNull(transactionRepo.getById(row.id)).transaction
                assertFalse("La ligne ${row.id} ne doit pas etre PAID", entity.status == TransactionStatus.PAID)
                assertNull("La ligne ${row.id} ne doit pas avoir paidAt renseigne", entity.paidAt)
            }
        }

    @Test
    fun `R-04b - Given the punctual dataset with status PLANNED, When CreateTransactionUseCase runs, Then the row is persisted as PLANNED with paidAt null`() =
        runTest {
            seedAccountCategoryAndTag()
            val createdId = createTransaction(punctualEdition(status = TransactionStatus.PLANNED))

            val created = requireNotNull(transactionRepo.getById(createdId)).transaction
            assertEquals(TransactionStatus.PLANNED, created.status)
            assertNull("PLANNED => paidAt null (I-5)", created.paidAt)
        }

    // =======================================================================================
    // R-05
    // =======================================================================================

    @Test
    fun `R-05 - Given the punctual dataset with one tag and a note, When CreateTransactionUseCase runs, Then the note is persisted and exactly one cross-ref targets the real id`() =
        runTest {
            seedAccountCategoryAndTag()
            val createdId = createTransaction(
                punctualEdition(status = TransactionStatus.PLANNED, tagIds = listOf(tagId)),
            )

            val row = persistedRow(createdId)
            assertEquals("n", row.note)

            val crossRefs = crossRefTransactionIds(tagId)
            assertEquals(1, crossRefs.size)
            assertTrue("L'id reference doit etre l'id reel (positif), pas -1", createdId > 0)
            assertEquals(createdId, crossRefs.single())
        }

    @Test
    fun `R-05b - Given the punctual dataset without any tag, When CreateTransactionUseCase runs, Then zero cross-ref is persisted and the save still succeeds`() =
        runTest {
            seedAccountCategoryAndTag()
            val createdId = createTransaction(
                punctualEdition(status = TransactionStatus.PLANNED, tagIds = emptyList()),
            )

            assertEquals(0, crossRefTransactionIds(tagId).size)
            assertTrue(persistedTransactions().any { it.id == createdId })
        }
}

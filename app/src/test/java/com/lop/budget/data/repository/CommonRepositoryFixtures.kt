package com.lop.budget.data.repository

import com.lop.budget.data.local.LopDatabase
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.CategoryEntity
import com.lop.budget.data.local.entity.RecurringSeriesEntity
import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.domain.model.AccountType
import com.lop.budget.domain.model.RecurrenceFrequency
import com.lop.budget.domain.model.TransactionEdition
import com.lop.budget.domain.model.TransactionKind
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.domain.model.toDaysOfWeekSet
import com.lop.budget.domain.usecase.ObserveTransactionsUseCase
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertTrue
import java.time.LocalDate
import java.time.ZoneId

/**
 * Infrastructure partagée pour les tests de repository impliquant Room et les UseCases.
 * Centralise le JDD canonique, l'observation et la lecture bas niveau (tombstones).
 */
interface RepositoryTestInfrastructure {
    val db: LopDatabase
    val transactionRepo: TransactionRepository
    val accountRepo: AccountRepository
    val categoryRepo: CategoryRepository
    val zone: ZoneId

    // --- Identifiants du JDD ---------------------------------------------------------------
    var accountId: Long
    var categoryId: Long
    var seriesAId: Long
    var seriesBId: Long
    var punctualId: Long

    // --- Dates Canoniques ------------------------------------------------------------------
    val januarySlot: Long get() = startOfDay(2024, 1, 1)
    val februarySlot: Long get() = startOfDay(2024, 2, 1)
    val marchSlot: Long get() = startOfDay(2024, 3, 1)

    val seriesBJanuarySlot: Long get() = startOfDay(2024, 1, 15)
    val seriesBFebruarySlot: Long get() = startOfDay(2024, 2, 15)
    val seriesBMarchSlot: Long get() = startOfDay(2024, 3, 15)

    val punctualDate: Long get() = startOfDay(2024, 1, 20)

    val periodStart: Long get() = startOfDay(2024, 1, 1)
    val periodEnd: Long get() = endOfDay(2024, 3, 31)

    // --- JDD Setup -------------------------------------------------------------------------
    /** Simule le formulaire ALL conforme CA-08 : prérempli avec les valeurs de BASE de la série. */
    suspend fun allEditionFrom(
        seriesId: Long,
        title: String? = null,
        amount: Double? = null,
        date: Long? = null,
        endDate: Long? = null,
    ): TransactionEdition {
        val s = requireNotNull(transactionRepo.getSeriesById(seriesId))
        return TransactionEdition(
            title = title ?: s.title,
            amount = amount ?: s.amount,
            type = s.type,
            date = date ?: s.startDate, // CA-08/CA-09 : en ALL, date == startDate
            accountId = s.accountId,
            categoryId = s.categoryId,
            note = s.note,
            status = null,
            frequency = s.frequency,
            interval = s.interval,
            daysOfWeek = s.daysOfWeek.toDaysOfWeekSet(),
            endDate = endDate,
            maxOccurrences = s.maxOccurrences,
            linkedGoalId = s.linkedGoalId,
            linkedDebtId = s.linkedDebtId,
            tagIds = emptyList(),
        )
    }
    suspend fun seedCanonicalDataSet() {
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

        seriesAId = transactionRepo.upsertSeries(
            monthlySeries(title = "Loyer", amount = 800.0, startDate = januarySlot),
        )
        seriesBId = transactionRepo.upsertSeries(
            monthlySeries(title = "Abonnement", amount = 12.0, startDate = seriesBJanuarySlot),
        )

        punctualId = transactionRepo.upsert(
            TransactionEntity(
                title = "Courses",
                amount = 45.0,
                type = TransactionType.EXPENSE,
                status = TransactionStatus.PLANNED,
                kind = TransactionKind.STANDARD,
                date = punctualDate,
                accountId = accountId,
                categoryId = categoryId,
            ),
        )
    }

    fun monthlySeries(title: String, amount: Double, startDate: Long) =
        RecurringSeriesEntity(
            title = title,
            amount = amount,
            type = TransactionType.EXPENSE,
            categoryId = categoryId,
            accountId = accountId,
            frequency = RecurrenceFrequency.MONTHLY,
            interval = 1,
            startDate = startDate,
        )

    suspend fun insertException(seriesId: Long, slot: Long, displayDate: Long): Long =
        transactionRepo.upsert(
            TransactionEntity(
                title = "Loyer",
                amount = 800.0,
                type = TransactionType.EXPENSE,
                status = TransactionStatus.PLANNED,
                kind = TransactionKind.STANDARD,
                date = displayDate,
                accountId = accountId,
                categoryId = categoryId,
                seriesId = seriesId,
                seriesDate = slot,
                isException = true,
            ),
        )

    // --- Observation & Fusion --------------------------------------------------------------

    suspend fun observeVisibleTransactions(
        useCase: ObserveTransactionsUseCase,
    ): List<TransactionWithRelations> = useCase(periodStart, periodEnd).first()

    suspend fun observeSlotsOf(
        seriesId: Long,
        useCase: ObserveTransactionsUseCase,
    ): List<Long> = slotsOf(observeVisibleTransactions(useCase), seriesId)

    fun slotsOf(
        transactions: List<TransactionWithRelations>,
        seriesId: Long,
    ): List<Long> = transactions
        .filter { it.transaction.seriesId == seriesId }
        .map { it.transaction.seriesDate ?: it.transaction.date }
        .sorted()

    // --- Lecture Persistante (Tombstones) --------------------------------------------------

    data class PersistedTx(
        val id: Long,
        val seriesId: Long?,
        val seriesDate: Long?,
        val date: Long,
        val title: String,
        val amount: Double,
        val note: String?,
        val isException: Boolean,
        val deleted: Boolean,
    )

    fun persistedTransactions(): List<PersistedTx> {
        val rows = mutableListOf<PersistedTx>()
        db.query(
            "SELECT id, seriesId, seriesDate, date, title, amount, note, isException, deleted FROM transactions ORDER BY id",
            emptyArray<Any?>(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                rows += PersistedTx(
                    id = cursor.getLong(0),
                    seriesId = if (cursor.isNull(1)) null else cursor.getLong(1),
                    seriesDate = if (cursor.isNull(2)) null else cursor.getLong(2),
                    date = cursor.getLong(3),
                    title = cursor.getString(4),
                    amount = cursor.getDouble(5),
                    note = cursor.getString(6),
                    isException = cursor.getInt(7) == 1,
                    deleted = cursor.getInt(8) == 1,
                )
            }
        }
        return rows
    }

    fun persistedRow(id: Long): PersistedTx =
        persistedTransactions().single { it.id == id }

    fun persistedRowsForSlot(seriesId: Long, slot: Long): List<PersistedTx> =
        persistedTransactions().filter { it.seriesId == seriesId && it.seriesDate == slot }

    // --- Isolation -------------------------------------------------------------------------

    data class ControlState(
        val seriesB: RecurringSeriesEntity?,
        val persistedRowsOfB: List<PersistedTx>,
        val punctualRow: PersistedTx?,
        val visibleSlotsOfB: List<Long>,
        val punctualVisible: Boolean,
    )

    suspend fun controlState(useCase: ObserveTransactionsUseCase): ControlState {
        val visible = observeVisibleTransactions(useCase)
        val persisted = persistedTransactions()
        return ControlState(
            seriesB = transactionRepo.getSeriesById(seriesBId),
            persistedRowsOfB = persisted.filter { it.seriesId == seriesBId },
            punctualRow = persisted.firstOrNull { it.id == punctualId },
            visibleSlotsOfB = slotsOf(visible, seriesBId),
            punctualVisible = visible.any { it.transaction.id == punctualId },
        )
    }

    // --- Dates -----------------------------------------------------------------------------

    fun startOfDay(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day).atStartOfDay(zone).toInstant().toEpochMilli()

    fun endOfDay(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day)
            .atTime(23, 59, 59, 999_000_000)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
}

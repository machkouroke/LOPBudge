package com.lop.budget.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.lop.budget.data.local.entity.RecurringSeriesEntity
import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.data.local.entity.TransactionTagCrossRef
import com.lop.budget.data.local.entity.TransactionWithRelations
import kotlinx.coroutines.flow.Flow

interface TransactionOperations {
    fun observeAll(): Flow<List<TransactionWithRelations>>
    fun observeByAccount(accountId: Long): Flow<List<TransactionWithRelations>>
    fun observePaidByAccount(accountId: Long): Flow<List<TransactionWithRelations>>
    fun observePlannedByAccount(accountId: Long): Flow<List<TransactionWithRelations>>

    fun observeById(id: Long): Flow<TransactionWithRelations?>
    fun observeSlotsAt(slotDates: List<Long>): Flow<List<TransactionWithRelations>>
    suspend fun getById(id: Long): TransactionWithRelations?
    suspend fun upsert(tx: TransactionEntity): Long
    suspend fun softDeleteTransaction(id: Long)
    suspend fun hardDelete(id: Long)
    suspend fun clearTags(txId: Long)
    suspend fun addTagCrossRef(crossRef: TransactionTagCrossRef)
    suspend fun saveWithTags(tx: TransactionEntity, tagIds: List<Long>): Long
    suspend fun getExceptionsBySeries(seriesId: Long): List<TransactionEntity>
    suspend fun getSumForGoal(goalId: Long): Double
    suspend fun getSumForDebt(debtId: Long): Double
    suspend fun softDeleteTransactionsBySeries(seriesId: Long)
    suspend fun softDeleteTransactionsBySeriesFrom(seriesId: Long, fromDate: Long)

    fun observeForMerge(start: Long, end: Long): Flow<List<TransactionWithRelations>>
}

@Dao
interface TransactionDao : TransactionOperations {
    @Transaction
    @Query("SELECT * FROM transactions WHERE deleted = 0 ORDER BY date DESC")
    override fun observeAll(): Flow<List<TransactionWithRelations>>

    @Transaction
    @Query(
        """
        SELECT * FROM transactions 
        WHERE accountId = :accountId AND deleted = 0 
        ORDER BY date DESC
    """
    )
    override fun observeByAccount(accountId: Long): Flow<List<TransactionWithRelations>>

    @Transaction
    @Query(
        """
        SELECT * FROM transactions 
        WHERE accountId = :accountId AND status = 'PAID' AND deleted = 0 
        ORDER BY date DESC
    """
    )
    override fun observePaidByAccount(accountId: Long): Flow<List<TransactionWithRelations>>

    @Transaction
    @Query(
        """
        SELECT * FROM transactions 
        WHERE accountId = :accountId AND status = 'PLANNED' AND deleted = 0 
        ORDER BY date DESC
    """
    )
    override fun observePlannedByAccount(accountId: Long): Flow<List<TransactionWithRelations>>



    @Transaction
    @Query(
        """
        SELECT * FROM transactions 
        WHERE id = :id AND deleted = 0
    """
    )
    override fun observeById(id: Long): Flow<TransactionWithRelations?>

    /**
     * Lignes occupant l'un des slots demandés, **tombstones compris**.
     *
     * `deleted` n'est volontairement pas filtré, pour la même raison que [observeForMerge] : un slot
     * supprimé continue d'occuper sa place et ne doit pas se régénérer en occurrence virtuelle
     * (I-5). C'est l'appelant qui décide de la visibilité. L'ancien `observeSeries` filtrait
     * `deleted = 0` et rendait le tombstone invisible au détail, qui reconstruisait alors le virtuel
     * du slot supprimé.
     *
     * Les deux clés sont interrogées, comme dans [observeForMerge] : une ligne occupe son slot
     * d'origine (`seriesDate`) **et** la date où elle s'affiche (`date`). Sans `date`, une exception
     * déplacée sur un autre slot de sa série laisserait le détail résoudre un virtuel que la liste
     * masque déjà (I-3).
     */
    @Transaction
    @Query(
        """
        SELECT * FROM transactions
        WHERE seriesId IS NOT NULL
          AND (seriesDate IN (:slotDates) OR date IN (:slotDates))
    """
    )
    override fun observeSlotsAt(slotDates: List<Long>): Flow<List<TransactionWithRelations>>

    @Transaction
    @Query(
        """
        SELECT * FROM transactions 
        WHERE id = :id AND deleted = 0
    """
    )
    override suspend fun getById(id: Long): TransactionWithRelations?

    @Query(
        """
        SELECT * FROM transactions 
        WHERE title = :title AND date = :date AND deleted = 0 
        LIMIT 1
    """
    )
    suspend fun getByTitleAndDate(title: String, date: Long): TransactionEntity?

    @Upsert
    override suspend fun upsert(tx: TransactionEntity): Long

    @Query(
        """
        UPDATE transactions 
        SET deleted = 1 
        WHERE id = :id
    """
    )
    override suspend fun softDeleteTransaction(id: Long)

    @Query(
        """
        DELETE FROM transactions 
        WHERE id = :id
    """
    )
    override suspend fun hardDelete(id: Long)

    @Query(
        """
        SELECT SUM(amount) FROM transactions 
        WHERE linkedGoalId = :goalId AND deleted = 0 AND status = 'PAID'
    """
    )
    override suspend fun getSumForGoal(goalId: Long): Double

    @Query(
        """
        SELECT SUM(amount) FROM transactions 
        WHERE linkedDebtId = :debtId AND deleted = 0 AND status = 'PAID'
    """
    )
    override suspend fun getSumForDebt(debtId: Long): Double

    @Query(
        """
        DELETE FROM transaction_tags 
        WHERE transactionId = :txId
    """
    )
    override suspend fun clearTags(txId: Long)

    @Upsert
    override suspend fun addTagCrossRef(crossRef: TransactionTagCrossRef)

    @Transaction
    override suspend fun saveWithTags(tx: TransactionEntity, tagIds: List<Long>): Long {
        val upsertId = upsert(tx)
        val finalId = if (tx.id > 0) tx.id else upsertId
        clearTags(finalId)
        tagIds.forEach { addTagCrossRef(TransactionTagCrossRef(finalId, it)) }
        return finalId
    }

    @Transaction
    suspend fun getOrCreateException(
        seriesId: Long,
        seriesDate: Long,
        series: RecurringSeriesEntity
    ): Long {
        val existing = getBySeriesSlot(seriesId, seriesDate)
        if (existing != null) return existing.id

        return upsert(
            TransactionEntity(
                title = series.title,
                amount = series.amount,
                type = series.type,
                status = com.lop.budget.domain.model.TransactionStatus.PLANNED,
                kind = com.lop.budget.domain.model.TransactionKind.STANDARD,
                date = seriesDate,
                accountId = series.accountId,
                categoryId = series.categoryId,
                seriesId = seriesId,
                seriesDate = seriesDate,
                isException = true,
                note = series.note,
                linkedGoalId = series.linkedGoalId,
                linkedDebtId = series.linkedDebtId
            )
        )
    }

    @Query(
        """
        SELECT * FROM transactions 
        WHERE seriesId = :seriesId AND seriesDate = :seriesDate
        LIMIT 1
    """
    )
    suspend fun getBySeriesSlot(seriesId: Long, seriesDate: Long): TransactionEntity?



    /**
     * Source unique de la fusion : toutes les lignes dont la date d'affichage **ou** le slot
     * d'origine intersecte la fenêtre, tombstones compris.
     *
     * Les lignes visibles et les slots occupés en sont dérivés ensemble, donc toujours dans le même
     * état : deux requêtes séparées étaient notifiées indépendamment par l'InvalidationTracker et
     * laissaient passer une émission en doublon (LOP-118).
     *
     * `OR seriesDate` ramène les exceptions déplacées hors de la fenêtre observée ; sans elles,
     * le virtuel de leur date d'affichage n'était masqué par rien (LOP-117).
     *
     * `deleted` n'est volontairement pas filtré ici : un tombstone continue d'occuper son slot
     * (I-5). C'est `ObserveTransactionsUseCase` qui décide de l'affichage.
     */
    @Transaction
    @Query(
        """
        SELECT * FROM transactions
        WHERE date BETWEEN :start AND :end
           OR (seriesId IS NOT NULL AND seriesDate BETWEEN :start AND :end)
        ORDER BY date ASC
    """
    )
    override fun observeForMerge(start: Long, end: Long): Flow<List<TransactionWithRelations>>

    @Query(
        """
        UPDATE transactions 
        SET deleted = 1 
        WHERE seriesId = :seriesId
    """
    )
    override suspend fun softDeleteTransactionsBySeries(seriesId: Long)

    @Query(
        """
        UPDATE transactions 
        SET deleted = 1 
        WHERE seriesId = :seriesId AND date >= :fromDate
    """
    )
    override suspend fun softDeleteTransactionsBySeriesFrom(seriesId: Long, fromDate: Long)

    @Query(
        """
        DELETE FROM transactions
    """
    )
    fun deleteAll()

    @Query(
        """
    SELECT * FROM transactions 
    WHERE seriesId = :seriesId AND isException = 1 AND deleted = 0
"""
    )
    override suspend fun getExceptionsBySeries(seriesId: Long): List<TransactionEntity>
}

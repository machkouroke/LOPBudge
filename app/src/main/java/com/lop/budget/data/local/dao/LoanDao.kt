package com.lop.budget.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.lop.budget.data.local.entity.LoanEntity
import com.lop.budget.domain.model.ItemStatus
import com.lop.budget.domain.model.LoanDirection
import kotlinx.coroutines.flow.Flow

/**
 * Opérations sur les prêts, dettes et créances confondues.
 *
 * Il n'existe pas de lecture « tout compris » : archiver retire l'élément des listes sans rien
 * supprimer (I-4), donc la lecture de référence est [observeActive]. Une inspection exhaustive
 * appartient au test, en SQL, pas à une API de production.
 */
interface LoanOperations {
    /** Tous les prêts non archivés, `COMPLETED` inclus (CA-03). */
    fun observeActive(): Flow<List<LoanEntity>>

    /** Les prêts non archivés d'une seule direction (CA-12). */
    fun observeActiveByDirection(direction: LoanDirection): Flow<List<LoanEntity>>

    suspend fun getById(id: Long): LoanEntity?

    /** Crée le prêt et rend son identifiant. La progression fournie est ignorée (I-2). */
    suspend fun create(loan: LoanEntity): Long

    /** Met à jour le prêt. Tout changement de direction est refusé (I-6). */
    suspend fun update(loan: LoanEntity)

    suspend fun archive(id: Long)
    suspend fun delete(id: Long)
    suspend fun updateRepaidAmountCents(id: Long, cents: Long)
}

@Dao
interface LoanDao {
    @Query("SELECT * FROM loans WHERE status != 'ARCHIVED' ORDER BY id")
    fun observeActive(): Flow<List<LoanEntity>>

    @Query("SELECT * FROM loans WHERE status != 'ARCHIVED' AND direction = :direction ORDER BY id")
    fun observeActiveByDirection(direction: LoanDirection): Flow<List<LoanEntity>>

    @Query("SELECT * FROM loans WHERE id = :id")
    suspend fun getById(id: Long): LoanEntity?

    @Upsert suspend fun upsert(loan: LoanEntity): Long

    @Query("UPDATE loans SET repaidAmountCents = :cents WHERE id = :id")
    suspend fun updateRepaidAmountCents(id: Long, cents: Long)

    @Query("UPDATE loans SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: ItemStatus)

    @Query("DELETE FROM loans WHERE id = :id") suspend fun delete(id: Long)

    @Query("DELETE FROM loans") fun deleteAll()
}

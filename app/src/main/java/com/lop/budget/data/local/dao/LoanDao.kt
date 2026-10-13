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
    /** Avancement et statut d'un même recalcul, écrits ensemble (I-3 de LOP-79). */
    suspend fun updateProgress(id: Long, cents: Long, status: ItemStatus)
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

    /** Même contrat que `GoalDao.updateProgress` (I-2, I-3, CA-08 de LOP-79). */
    @Query(
        """
        UPDATE loans SET repaidAmountCents = :cents,
            status = CASE WHEN status = 'ARCHIVED' THEN status ELSE :status END
        WHERE id = :id
    """
    )
    suspend fun updateProgress(id: Long, cents: Long, status: ItemStatus)

    @Query("UPDATE loans SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: ItemStatus)

    @Query("DELETE FROM loans WHERE id = :id") suspend fun delete(id: Long)

    @Query("DELETE FROM loans") fun deleteAll()
}

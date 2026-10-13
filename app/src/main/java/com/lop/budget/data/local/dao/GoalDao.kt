package com.lop.budget.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.lop.budget.data.local.entity.GoalEntity
import com.lop.budget.domain.model.ItemStatus
import kotlinx.coroutines.flow.Flow

/**
 * Opérations sur les objectifs d'épargne.
 *
 * Comme pour les prêts, il n'existe pas de lecture « tout compris » : archiver retire l'objectif
 * des listes sans rien supprimer (I-4), donc la lecture de référence est [observeActive].
 */
interface GoalOperations {
    /** Tous les objectifs non archivés, `COMPLETED` inclus (CA-03). */
    fun observeActive(): Flow<List<GoalEntity>>

    suspend fun getById(id: Long): GoalEntity?

    /** Crée l'objectif et rend son identifiant. La progression fournie est ignorée (I-2). */
    suspend fun create(goal: GoalEntity): Long

    suspend fun update(goal: GoalEntity)

    suspend fun archive(id: Long)
    suspend fun delete(id: Long)
    /** Avancement et statut d'un même recalcul, écrits ensemble (I-3 de LOP-79). */
    suspend fun updateProgress(id: Long, cents: Long, status: ItemStatus)
}

@Dao
interface GoalDao {
    @Query("SELECT * FROM goals WHERE status != 'ARCHIVED' ORDER BY id")
    fun observeActive(): Flow<List<GoalEntity>>

    @Query("SELECT * FROM goals WHERE id = :id")
    suspend fun getById(id: Long): GoalEntity?

    @Query("SELECT * FROM goals WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): GoalEntity?

    @Upsert suspend fun upsert(goal: GoalEntity): Long

    /**
     * Une seule requête : jamais un avancement neuf avec un ancien statut (I-3, CA-08 de LOP-79).
     * Un archivage posé entre la lecture et l'écriture du recalcul est conservé (I-2).
     */
    @Query(
        """
        UPDATE goals SET savedAmountCents = :cents,
            status = CASE WHEN status = 'ARCHIVED' THEN status ELSE :status END
        WHERE id = :id
    """
    )
    suspend fun updateProgress(id: Long, cents: Long, status: ItemStatus)

    @Query("UPDATE goals SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: ItemStatus)

    @Query("DELETE FROM goals WHERE id = :id") suspend fun delete(id: Long)

    @Query("DELETE FROM goals") fun deleteAll()
}

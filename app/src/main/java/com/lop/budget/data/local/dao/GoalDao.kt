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
    suspend fun updateSavedAmountCents(id: Long, cents: Long)
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

    @Query("UPDATE goals SET savedAmountCents = :cents WHERE id = :id")
    suspend fun updateSavedAmountCents(id: Long, cents: Long)

    @Query("UPDATE goals SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: ItemStatus)

    @Query("DELETE FROM goals WHERE id = :id") suspend fun delete(id: Long)

    @Query("DELETE FROM goals") fun deleteAll()
}

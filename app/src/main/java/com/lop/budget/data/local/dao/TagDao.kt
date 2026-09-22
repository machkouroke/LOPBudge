package com.lop.budget.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.lop.budget.data.local.entity.TagEntity
import kotlinx.coroutines.flow.Flow

interface TagOperations {
    fun observeAll(): Flow<List<TagEntity>>
    suspend fun upsert(tag: TagEntity): Long
    suspend fun delete(id: Long)
}

@Dao
interface TagDao : TagOperations {
    @Query("SELECT * FROM tags ORDER BY name")
    override fun observeAll(): Flow<List<TagEntity>>

    @Query("SELECT * FROM tags WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): TagEntity?

    // `countUsages` retiré (LOP-21, P-1) : aucun appelant, et un tag utilisé se supprime désormais
    // sans garde d'usage. Le laisser là invitait à rebrancher le blocage que P-1 a tranché — et il
    // ne comptait que `transaction_tags`, jamais `series_tags`.

    @Upsert override suspend fun upsert(tag: TagEntity): Long

    @Query("DELETE FROM tags WHERE id = :id") override suspend fun delete(id: Long)

    @Query("DELETE FROM tags") fun deleteAll()
}

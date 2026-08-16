package com.lop.budget.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.lop.budget.data.local.entity.TagEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TagDao {
    @Query("SELECT * FROM tags ORDER BY name")
    fun observeAll(): Flow<List<TagEntity>>
    @Query("SELECT * FROM tags WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): TagEntity?

    @Query("SELECT COUNT(*) FROM transaction_tags WHERE tagId = :tagId")
    suspend fun countUsages(tagId: Long): Int

    @Upsert suspend fun upsert(tag: TagEntity): Long
    @Query("DELETE FROM tags WHERE id = :id") suspend fun delete(id: Long)
    @Query("DELETE FROM tags") fun deleteAll()
}

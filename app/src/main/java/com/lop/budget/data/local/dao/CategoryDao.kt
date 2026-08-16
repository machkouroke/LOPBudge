package com.lop.budget.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.lop.budget.data.local.entity.CategoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY name")
    fun observeAll(): Flow<List<CategoryEntity>>
    @Query("SELECT * FROM categories WHERE name = :name AND parentCategoryId IS :parentId LIMIT 1")
    suspend fun getByNameAndParent(name: String, parentId: Long?): CategoryEntity?
    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun getById(id: Long): CategoryEntity?
    @Query("SELECT * FROM categories WHERE type = :type ORDER BY name")
    fun observeByType(type: String): Flow<List<CategoryEntity>>
    @Upsert suspend fun upsert(category: CategoryEntity): Long
    @Query("DELETE FROM categories WHERE id = :id") suspend fun delete(id: Long)
    @Query("DELETE FROM categories") fun deleteAll()
}

package com.lop.budget.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.lop.budget.data.local.entity.CategoryEntity
import kotlinx.coroutines.flow.Flow

interface CategoryOperations {
    fun observeAll(): Flow<List<CategoryEntity>>
    fun observeByType(type: String): Flow<List<CategoryEntity>>
    fun observeRecentlyUsed(type: String, limit: Int): Flow<List<CategoryEntity>>
    suspend fun getById(id: Long): CategoryEntity?
    suspend fun upsert(category: CategoryEntity): Long
    suspend fun delete(id: Long)
    suspend fun countChildren(parentId: Long): Int
    suspend fun deleteChildren(parentId: Long)
}

@Dao
interface CategoryDao : CategoryOperations {
    @Query("SELECT * FROM categories ORDER BY name")
    override fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE name = :name AND parentCategoryId IS :parentId LIMIT 1")
    suspend fun getByNameAndParent(name: String, parentId: Long?): CategoryEntity?

    @Query("SELECT * FROM categories WHERE id = :id")
    override suspend fun getById(id: Long): CategoryEntity?

    @Query("SELECT * FROM categories WHERE type = :type ORDER BY name")
    override fun observeByType(type: String): Flow<List<CategoryEntity>>

    /**
     * LOP-227, P-7 : les [limit] catégories du [type] les plus récemment utilisées, la plus récente
     * d'abord. « Récente » suit l'ordre de saisie des transactions (identifiant décroissant) : aucune
     * date de création n'est stockée. Une ligne supprimée ne compte pas ; un ajustement de solde
     * (catégorie 0) ne joint aucune catégorie.
     */
    @Query(
        """
        SELECT c.* FROM categories c
        JOIN transactions t ON t.categoryId = c.id
        WHERE c.type = :type AND t.deleted = 0
        GROUP BY c.id
        ORDER BY MAX(t.id) DESC
        LIMIT :limit
        """
    )
    override fun observeRecentlyUsed(type: String, limit: Int): Flow<List<CategoryEntity>>

    @Upsert override suspend fun upsert(category: CategoryEntity): Long

    @Query("SELECT COUNT(*) FROM categories WHERE parentCategoryId = :parentId")
    override suspend fun countChildren(parentId: Long): Int

    @Query("DELETE FROM categories WHERE parentCategoryId = :parentId")
    override suspend fun deleteChildren(parentId: Long)

    @Query("DELETE FROM categories WHERE id = :id") override suspend fun delete(id: Long)

    @Query("DELETE FROM categories") fun deleteAll()
}

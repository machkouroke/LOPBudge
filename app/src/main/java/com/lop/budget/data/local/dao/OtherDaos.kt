package com.lop.budget.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.CategoryEntity
import com.lop.budget.data.local.entity.DebtEntity
import com.lop.budget.data.local.entity.GoalEntity
import com.lop.budget.data.local.entity.TagEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts ORDER BY id")
    fun observeAll(): Flow<List<AccountEntity>>
    @Upsert suspend fun upsert(account: AccountEntity): Long
    @Query("DELETE FROM accounts WHERE id = :id") suspend fun delete(id: Long)
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY name")
    fun observeAll(): Flow<List<CategoryEntity>>
    @Query("SELECT * FROM categories WHERE type = :type ORDER BY name")
    fun observeByType(type: String): Flow<List<CategoryEntity>>
    @Upsert suspend fun upsert(category: CategoryEntity): Long
    @Query("DELETE FROM categories WHERE id = :id") suspend fun delete(id: Long)
}

@Dao
interface TagDao {
    @Query("SELECT * FROM tags ORDER BY name")
    fun observeAll(): Flow<List<TagEntity>>
    @Upsert suspend fun upsert(tag: TagEntity): Long
    @Query("DELETE FROM tags WHERE id = :id") suspend fun delete(id: Long)
}

@Dao
interface GoalDao {
    @Query("SELECT * FROM goals ORDER BY id")
    fun observeAll(): Flow<List<GoalEntity>>
    @Upsert suspend fun upsert(goal: GoalEntity): Long
    @Query("DELETE FROM goals WHERE id = :id") suspend fun delete(id: Long)
}

@Dao
interface DebtDao {
    @Query("SELECT * FROM debts ORDER BY id")
    fun observeAll(): Flow<List<DebtEntity>>
    @Upsert suspend fun upsert(debt: DebtEntity): Long
    @Query("DELETE FROM debts WHERE id = :id") suspend fun delete(id: Long)
}

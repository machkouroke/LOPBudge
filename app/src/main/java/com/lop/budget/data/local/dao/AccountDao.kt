package com.lop.budget.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.lop.budget.data.local.entity.AccountEntity
import kotlinx.coroutines.flow.Flow

interface AccountOperations {
    fun observeAll(): Flow<List<AccountEntity>>
    suspend fun getById(id: Long): AccountEntity?
    suspend fun upsert(account: AccountEntity): Long
    suspend fun delete(id: Long)
}

@Dao
interface AccountDao : AccountOperations {
    @Query("SELECT * FROM accounts ORDER BY id")
    override fun observeAll(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): AccountEntity?

    @Query("SELECT * FROM accounts WHERE id = :id")
    override suspend fun getById(id: Long): AccountEntity?

    @Upsert override suspend fun upsert(account: AccountEntity): Long

    @Query("DELETE FROM accounts WHERE id = :id") override suspend fun delete(id: Long)

    @Query("DELETE FROM accounts") fun deleteAll()
}

package com.lop.budget.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.lop.budget.data.local.entity.RecurringSeriesEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RecurringSeriesDao {
    @Query("SELECT * FROM recurring_series WHERE status = 'ACTIVE'")
    fun observeActiveSeries(): Flow<List<RecurringSeriesEntity>>

    @Query("SELECT * FROM recurring_series WHERE id = :id")
    suspend fun getSeriesById(id: Long): RecurringSeriesEntity?

    @Query("SELECT * FROM recurring_series WHERE title = :title LIMIT 1")
    suspend fun getByTitle(title: String): RecurringSeriesEntity?

    @Upsert
    suspend fun upsert(series: RecurringSeriesEntity): Long

    @Update
    suspend fun update(series: RecurringSeriesEntity)

    @Query("UPDATE recurring_series SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String)
}

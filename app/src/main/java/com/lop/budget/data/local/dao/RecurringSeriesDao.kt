package com.lop.budget.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.lop.budget.data.local.entity.RecurringSeriesEntity
import kotlinx.coroutines.flow.Flow

interface RecurringSeriesOperations {
    fun observeActiveSeries(): Flow<List<RecurringSeriesEntity>>
    suspend fun getSeriesById(id: Long): RecurringSeriesEntity?
    suspend fun upsertSeries(series: RecurringSeriesEntity): Long
    suspend fun updateSeries(series: RecurringSeriesEntity)
    suspend fun updateSeriesCancelled(id: Long, cancelled: Boolean)
}

@Dao
interface RecurringSeriesDao : RecurringSeriesOperations {
    @Query("SELECT * FROM recurring_series WHERE isCancelled = 0")
    override fun observeActiveSeries(): Flow<List<RecurringSeriesEntity>>

    @Query("SELECT * FROM recurring_series WHERE id = :id")
    override suspend fun getSeriesById(id: Long): RecurringSeriesEntity?

    @Query("SELECT * FROM recurring_series WHERE title = :title LIMIT 1")
    suspend fun getByTitle(title: String): RecurringSeriesEntity?

    @Upsert override suspend fun upsertSeries(series: RecurringSeriesEntity): Long

    @Upsert suspend fun upsert(series: RecurringSeriesEntity): Long

    @Update override suspend fun updateSeries(series: RecurringSeriesEntity)

    @Query("UPDATE recurring_series SET isCancelled = :cancelled WHERE id = :id")
    override suspend fun updateSeriesCancelled(id: Long, cancelled: Boolean)

    @Query("DELETE FROM recurring_series") fun deleteAll()
}

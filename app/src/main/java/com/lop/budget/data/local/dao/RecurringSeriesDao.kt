package com.lop.budget.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import com.lop.budget.data.local.entity.RecurringSeriesEntity
import com.lop.budget.data.local.entity.SeriesTagCrossRef
import com.lop.budget.data.local.entity.TagEntity
import kotlinx.coroutines.flow.Flow

/** Projection d'un tag rattaché à une série, pour l'affichage des occurrences virtuelles. */
data class SeriesTag(
    val seriesId: Long,
    val id: Long,
    val name: String,
    val colorArgb: Int,
)

interface RecurringSeriesOperations {
    fun observeActiveSeries(): Flow<List<RecurringSeriesEntity>>
    suspend fun getSeriesById(id: Long): RecurringSeriesEntity?
    suspend fun upsertSeries(series: RecurringSeriesEntity): Long
    suspend fun updateSeries(series: RecurringSeriesEntity)
    suspend fun updateSeriesCancelled(id: Long, cancelled: Boolean)
    suspend fun saveSeriesWithTags(series: RecurringSeriesEntity, tagIds: List<Long>): Long
    suspend fun getTagsForSeries(seriesId: Long): List<TagEntity>
    fun observeAllSeriesTags(): Flow<List<SeriesTag>>
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

    // --- Tags de série (CA-05) ------------------------------------------------------------

    @Query("DELETE FROM series_tags WHERE seriesId = :seriesId")
    suspend fun clearSeriesTags(seriesId: Long)

    @Upsert
    suspend fun addSeriesTagCrossRef(crossRef: SeriesTagCrossRef)

    @Query(
        """
        SELECT tags.* FROM tags
        INNER JOIN series_tags ON tags.id = series_tags.tagId
        WHERE series_tags.seriesId = :seriesId
    """
    )
    override suspend fun getTagsForSeries(seriesId: Long): List<TagEntity>

    @Query(
        """
        SELECT series_tags.seriesId AS seriesId, tags.id AS id, tags.name AS name,
               tags.colorArgb AS colorArgb
        FROM tags
        INNER JOIN series_tags ON tags.id = series_tags.tagId
    """
    )
    override fun observeAllSeriesTags(): Flow<List<SeriesTag>>

    /** Calqué sur [com.lop.budget.data.local.dao.TransactionDao.saveWithTags]. */
    @Transaction
    override suspend fun saveSeriesWithTags(
        series: RecurringSeriesEntity,
        tagIds: List<Long>,
    ): Long {
        val upsertId = upsertSeries(series)
        val finalId = if (series.id > 0) series.id else upsertId
        clearSeriesTags(finalId)
        tagIds.forEach { addSeriesTagCrossRef(SeriesTagCrossRef(finalId, it)) }
        return finalId
    }

    @Query("DELETE FROM recurring_series") fun deleteAll()
}

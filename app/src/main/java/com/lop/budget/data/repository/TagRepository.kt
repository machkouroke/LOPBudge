package com.lop.budget.data.repository

import com.lop.budget.data.local.dao.TagDao
import com.lop.budget.data.local.entity.TagEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TagRepository @Inject constructor(
    private val tagDao: TagDao
) {
    fun observeAll(): Flow<List<TagEntity>> = tagDao.observeAll()
    
    suspend fun upsert(tag: TagEntity): Long = tagDao.upsert(tag)
    
    suspend fun delete(id: Long) = tagDao.delete(id)
}

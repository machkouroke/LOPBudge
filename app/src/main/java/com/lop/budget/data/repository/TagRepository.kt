package com.lop.budget.data.repository

import com.lop.budget.data.local.dao.TagDao
import com.lop.budget.data.local.dao.TagOperations
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TagRepository @Inject constructor(
    private val tagDao: TagDao
) : TagOperations by tagDao

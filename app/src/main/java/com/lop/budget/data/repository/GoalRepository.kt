package com.lop.budget.data.repository

import com.lop.budget.data.local.dao.GoalDao
import com.lop.budget.data.local.dao.GoalOperations
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GoalRepository @Inject constructor(
    private val goalDao: GoalDao
) : GoalOperations by goalDao

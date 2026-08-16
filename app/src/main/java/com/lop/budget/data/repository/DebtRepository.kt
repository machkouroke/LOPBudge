package com.lop.budget.data.repository

import com.lop.budget.data.local.dao.DebtDao
import com.lop.budget.data.local.dao.DebtOperations
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DebtRepository @Inject constructor(
    private val debtDao: DebtDao
) : DebtOperations by debtDao

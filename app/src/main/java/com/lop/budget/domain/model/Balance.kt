package com.lop.budget.domain.model

import com.lop.budget.data.local.entity.AccountEntity

data class AccountBalance(val account: AccountEntity, val balance: Double)

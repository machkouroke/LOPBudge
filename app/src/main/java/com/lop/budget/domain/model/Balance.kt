package com.lop.budget.domain.model

import com.lop.budget.data.local.entity.AccountEntity

/** [balance] est exprimé en centimes. */
data class AccountBalance(val account: AccountEntity, val balance: Long)

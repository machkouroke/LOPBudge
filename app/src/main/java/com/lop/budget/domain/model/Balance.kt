package com.lop.budget.domain.model

import com.lop.budget.data.local.entity.AccountEntity

/** [balance] est exprimé en centimes. */
data class AccountBalance(val account: AccountEntity, val balance: Long)

/**
 * Instantané cohérent des soldes, en centimes.
 *
 * [accounts] et [total] sont dérivés de la **même** liste de comptes : il est donc
 * structurellement impossible que le total porte sur un ensemble de comptes différent de
 * celui des soldes individuels, ou qu'un compte y contribue une valeur de repli (CA-13, I-6).
 */
data class AccountBalances(
    val accounts: List<AccountBalance> = emptyList(),
    val total: Long = 0L,
)

package com.lop.budget.data.repository

import com.lop.budget.data.local.dao.AccountDao
import com.lop.budget.data.local.dao.AccountOperations
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.domain.BalanceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AccountRepository @Inject constructor(
    private val accountDao: AccountDao
) : AccountOperations by accountDao {

    /**
     * Observe les soldes de tous les comptes en temps réel.
     */
    fun observeAccountBalances(transactionsFlow: Flow<List<TransactionEntity>>): Flow<Map<Long, Double>> =
        combine(
            observeAll(),
            transactionsFlow
        ) { accounts, transactions ->
            BalanceEngine.calculateBalances(accounts, transactions)
        }.flowOn(Dispatchers.IO)

    /**
     * Observe le solde total consolidé.
     */
    fun observeTotalBalance(balancesFlow: Flow<Map<Long, Double>>): Flow<Double> = combine(
        observeAll(),
        balancesFlow
    ) { accounts, balances ->
        BalanceEngine.calculateTotalBalance(accounts, balances)
    }.flowOn(Dispatchers.IO)
}

package com.lop.budget.domain.usecase

import com.lop.budget.data.repository.AccountRepository
import com.lop.budget.data.repository.TransactionRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GetAccountBalancesUseCase @Inject constructor(
    private val accountRepo: AccountRepository,
    private val transactionRepo: TransactionRepository
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeBalances(): Flow<Map<Long, Double>> {
        return accountRepo.observeAccountBalances(
            transactionRepo.observeAll().flatMapLatest { list ->
                flowOf(list.map { it.transaction })
            }
        )
    }

    fun observeTotalBalance(): Flow<Double> {
        return accountRepo.observeTotalBalance(observeBalances())
    }
}

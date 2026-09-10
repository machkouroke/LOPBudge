package com.lop.budget.domain.usecase

import com.lop.budget.data.repository.AccountRepository
import com.lop.budget.data.repository.TransactionRepository
import com.lop.budget.domain.model.AccountBalances
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GetAccountBalancesUseCase @Inject constructor(
    private val accountRepo: AccountRepository,
    private val transactionRepo: TransactionRepository
) {
    /**
     * Point d'entrée unique du domaine pour les soldes (I-6).
     *
     * Émet les soldes par compte et le solde total portés par une même émission, donc
     * toujours cohérents entre eux (CA-13). Les consommateurs qui affichent les deux
     * doivent lire ce flux, et non recombiner les projections ci-dessous.
     */
    fun observe(): Flow<AccountBalances> =
        accountRepo.observeBalances(
            transactionRepo.observeAll().map { list -> list.map { it.transaction } }
        )

    /** Soldes par compte, en centimes. Projection de [observe]. */
    fun observeBalances(): Flow<Map<Long, Long>> =
        observe().map { snapshot -> snapshot.accounts.associate { it.account.id to it.balance } }

    /** Solde total consolidé, en centimes. Projection de [observe]. */
    fun observeTotalBalance(): Flow<Long> = observe().map { it.total }
}

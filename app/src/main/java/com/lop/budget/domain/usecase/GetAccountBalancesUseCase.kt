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
        // `observeAllEntities` et non `observeAll().map { it.transaction }` : les relations
        // chargées par `observeAll` (catégorie, compte, tags de chaque ligne) étaient
        // intégralement jetées par ce `map`.
        accountRepo.observeBalances(transactionRepo.observeAllEntities())

    /** Soldes par compte, en centimes. Projection de [observe]. */
    fun observeBalances(): Flow<Map<Long, Long>> =
        observe().map { snapshot -> snapshot.accounts.associate { it.account.id to it.balance } }

    /** Solde total consolidé, en centimes. Projection de [observe]. */
    fun observeTotalBalance(): Flow<Long> = observe().map { it.total }
}

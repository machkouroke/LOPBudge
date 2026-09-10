package com.lop.budget.data.repository

import com.lop.budget.data.local.dao.AccountDao
import com.lop.budget.data.local.dao.AccountOperations
import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.domain.BalanceEngine
import com.lop.budget.domain.model.AccountBalance
import com.lop.budget.domain.model.AccountBalances
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
     * Observe les soldes par compte et le solde total consolidé, en centimes.
     *
     * Une **seule** souscription à [observeAll] alimente les deux calculs. Le total porte
     * donc toujours exactement sur la liste de comptes qui a servi aux soldes individuels :
     * aucun compte ne peut manquer à l'appel et retomber sur une valeur de repli (CA-13).
     *
     * Deux souscriptions recombinées en aval réintroduiraient l'incohérence : à la création
     * d'un compte, la liste émettrait avant les soldes et le total sous-évaluerait ce compte
     * le temps d'une émission.
     */
    fun observeBalances(transactionsFlow: Flow<List<TransactionEntity>>): Flow<AccountBalances> =
        combine(
            observeAll(),
            transactionsFlow
        ) { accounts, transactions ->
            val byId = BalanceEngine.calculateBalances(accounts, transactions)
            AccountBalances(
                // getValue et non `?: 0L` : calculateBalances est total sur `accounts`,
                // une clé absente serait un défaut du moteur, pas un cas à rattraper ici.
                accounts = accounts.map { AccountBalance(it, byId.getValue(it.id)) },
                total = BalanceEngine.calculateTotalBalance(accounts, byId)
            )
        }.flowOn(Dispatchers.IO)
}

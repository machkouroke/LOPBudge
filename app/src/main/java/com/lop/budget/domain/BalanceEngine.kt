package com.lop.budget.domain

import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType

object BalanceEngine {

    /**
     * Calcule les soldes actuels pour une liste de comptes donnés.
     * @param accounts La liste des comptes avec leur solde initial.
     * @param transactions La liste exhaustive des transactions physiques.
     * @return Une map associant l'ID du compte à son solde calculé.
     */
    fun calculateBalances(
        accounts: List<AccountEntity>,
        transactions: List<TransactionEntity>
    ): Map<Long, Double> {
        val balances = accounts.associate { it.id to it.initialBalance }.toMutableMap()

        transactions
            .filter { it.status == TransactionStatus.PAID && !it.deleted }
            .forEach { tx ->
                val current = balances[tx.accountId] ?: 0.0
                val amount = if (tx.type == TransactionType.INCOME) tx.amount else -tx.amount
                balances[tx.accountId] = current + amount
            }

        return balances
    }

    /**
     * Calcule le solde total consolidé (uniquement pour les comptes inclus dans le total).
     */
    fun calculateTotalBalance(
        accounts: List<AccountEntity>,
        calculatedBalances: Map<Long, Double>
    ): Double {
        return accounts
            .filter { it.includeInTotal && !it.archived }
            .sumOf { calculatedBalances[it.id] ?: 0.0 }
    }
}

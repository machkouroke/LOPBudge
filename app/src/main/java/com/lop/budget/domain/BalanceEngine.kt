package com.lop.budget.domain

import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType

object BalanceEngine {

    /**
     * Calcule les soldes actuels pour une liste de comptes donnés.
     * Utilise le solde de référence et les transactions payées après la date de mise à jour du solde.
     * @param accounts La liste des comptes avec leur solde de référence.
     * @param transactions La liste exhaustive des transactions physiques.
     * @return Une map associant l'ID du compte à son solde calculé.
     */
    fun calculateBalances(
        accounts: List<AccountEntity>,
        transactions: List<TransactionEntity>
    ): Map<Long, Double> {
        val result = mutableMapOf<Long, Double>()

        for (account in accounts) {
            var currentBalance = account.initialBalance // initialBalance est notre solde de référence
            
            // Calculer la somme des transactions payées APRÈS la dernière mise à jour du solde de ce compte
            transactions
                .filter { 
                    it.accountId == account.id && 
                    it.status == TransactionStatus.PAID && 
                    !it.deleted && 
                    it.paidAt != null && it.paidAt > account.balanceUpdatedAt 
                }
                .forEach { tx ->
                    val amount = if (tx.type == TransactionType.INCOME) tx.amount else -tx.amount
                    currentBalance += amount
                }
            
            result[account.id] = currentBalance
        }

        return result
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

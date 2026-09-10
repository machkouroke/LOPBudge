package com.lop.budget.domain

import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType

object BalanceEngine {

    /**
     * Calcule les soldes actuels pour une liste de comptes donnés.
     * Utilise le solde de référence et les transactions payées après la date de mise à jour du solde.
     *
     * Entrées, cumuls et sorties sont exprimés en centimes : le moteur ne convertit jamais
     * vers l'euro, cette conversion vit exclusivement à la frontière UI (`Format`).
     *
     * @param accounts La liste des comptes avec leur solde de référence.
     * @param transactions La liste exhaustive des transactions physiques.
     * @return Une map associant l'ID du compte à son solde calculé, en centimes.
     */
    fun calculateBalances(
        accounts: List<AccountEntity>,
        transactions: List<TransactionEntity>
    ): Map<Long, Long> {
        // Un seul passage sur chaque liste, au lieu d'un `filter` allouant une liste
        // intermédiaire par compte : la version précédente était en O(comptes x transactions).
        val result = LinkedHashMap<Long, Long>(accounts.size)
        for (account in accounts) {
            result[account.id] = account.initialBalance
        }

        // On somme TOUTES les transactions payées et non supprimées rattachées au compte.
        // On ignore désormais balanceUpdatedAt car on utilise les transactions compensatoires (ajustements).
        for (tx in transactions) {
            if (tx.status != TransactionStatus.PAID || tx.deleted) continue

            // `?: continue` et non `getOrPut` : une transaction rattachée à un compte absent de
            // [accounts] ne doit pas créer d'entrée. Le résultat reste total sur [accounts] et
            // seulement sur eux (CA-09).
            val current = result[tx.accountId] ?: continue
            result[tx.accountId] =
                current + if (tx.type == TransactionType.INCOME) tx.amount else -tx.amount
        }

        return result
    }

    /**
     * Calcule le solde total consolidé, en centimes
     * (uniquement pour les comptes inclus dans le total).
     */
    fun calculateTotalBalance(
        accounts: List<AccountEntity>,
        calculatedBalances: Map<Long, Long>
    ): Long {
        return accounts
            .filter { it.includeInTotal && !it.archived }
            .sumOf { calculatedBalances[it.id] ?: 0L }
    }
}

package com.lop.budget.domain.usecase.account

import com.lop.budget.data.repository.AccountRepository
import javax.inject.Inject
import javax.inject.Singleton

sealed interface AccountDeleteResult {
    data class Deleted(val detachedTransactions: Int, val deletedAdjustments: Int) : AccountDeleteResult
    data object NotFound : AccountDeleteResult
}

/**
 * Suppression d'un compte (LOP-20, CA-08, I-5).
 *
 * **Unique point d'entrée** de la suppression d'un compte. `AccountRepository.delete` n'est
 * appelé que d'ici : les deux écrans qui l'invoquaient directement — `AccountsManageViewModel`
 * et `AccountFormViewModel` — passent par ce use case.
 *
 * La règle est tranchée par P-4 : les ajustements du compte sont supprimés, ses transactions
 * métier détachées vers `NO_ACCOUNT_ID`, le tout en une seule transaction base.
 *
 * ÉCART CA-08 / I-5, **reconduit volontairement** : ce use case ne fait encore que supprimer la
 * ligne `accounts`. `TransactionEntity` ne déclare aucune clé étrangère vers `accounts` : les
 * transactions du compte gardent un `accountId` défunt et ses ajustements survivent. Les compteurs
 * de [AccountDeleteResult.Deleted] valent donc zéro par construction. Le corriger dans le même
 * geste que l'extraction masquerait ce que TC-136 doit révéler.
 */
@Singleton
class DeleteAccountUseCase @Inject constructor(
    private val accountRepo: AccountRepository,
) {
    suspend operator fun invoke(accountId: Long): AccountDeleteResult {
        accountRepo.getById(accountId) ?: return AccountDeleteResult.NotFound
        accountRepo.delete(accountId)
        return AccountDeleteResult.Deleted(detachedTransactions = 0, deletedAdjustments = 0)
    }
}

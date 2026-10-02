package com.lop.budget.domain.usecase.account

import com.lop.budget.data.repository.AccountRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Archivage et inclusion dans le solde total (LOP-20, CA-06, CA-07).
 *
 * Extrait à comportement constant de `AccountsManageViewModel.toggleArchive`, qui écrivait par
 * `accountRepo.upsert` depuis l'UI. Un paramètre nul laisse le drapeau tel qu'il est en base.
 */
@Singleton
class SetAccountFlagsUseCase @Inject constructor(
    private val accountRepo: AccountRepository,
) {
    suspend operator fun invoke(
        accountId: Long,
        archived: Boolean? = null,
        includeInTotal: Boolean? = null,
    ): AccountSaveResult {
        val account = accountRepo.getById(accountId)
            ?: return AccountSaveResult.Refused(AccountRefusal.NotFound)
        accountRepo.upsert(
            account.copy(
                archived = archived ?: account.archived,
                includeInTotal = includeInTotal ?: account.includeInTotal,
            )
        )
        return AccountSaveResult.Saved(accountId, AdjustOutcome.NoChange)
    }
}

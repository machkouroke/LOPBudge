package com.lop.budget.domain.usecase.account

import com.lop.budget.data.repository.AccountRepository
import com.lop.budget.data.repository.TransactionRepository
import com.lop.budget.domain.model.NO_ACCOUNT_ID
import com.lop.budget.domain.usecase.transaction.AtomicWriter
import javax.inject.Inject
import javax.inject.Singleton

sealed interface AccountDeleteResult {
    data class Deleted(val detachedTransactions: Int, val deletedAdjustments: Int) : AccountDeleteResult
    data object NotFound : AccountDeleteResult
}

/**
 * Suppression d'un compte (LOP-20, CA-08, I-5, P-4).
 *
 * **Unique point d'entrée** de la suppression d'un compte : `AccountRepository.delete` n'est
 * appelé que d'ici.
 *
 * Les ajustements de solde du compte sont supprimés : ils ne corrigent que son solde et n'ont plus
 * d'objet sans lui. Ses transactions métier, payées ou planifiées, sont détachées vers
 * [NO_ACCOUNT_ID] : elles quittent les soldes et les lectures par compte, mais restent dans les
 * lectures non filtrées. Ses séries récurrentes le sont de même (P-9) : elles produiraient sinon de
 * nouvelles occurrences rattachées à un compte disparu. Le tout dans une seule transaction base : un
 * échec au milieu laisserait des transactions pointées sur un compte disparu (LOP-180).
 */
@Singleton
class DeleteAccountUseCase @Inject constructor(
    private val accountRepo: AccountRepository,
    private val transactionRepo: TransactionRepository,
    private val atomicWriter: AtomicWriter,
) {
    suspend operator fun invoke(accountId: Long): AccountDeleteResult = atomicWriter.atomically {
        accountRepo.getById(accountId) ?: return@atomically AccountDeleteResult.NotFound
        val deletedAdjustments = transactionRepo.deleteAdjustmentsByAccount(accountId)
        val detached = transactionRepo.detachTransactionsFromAccount(accountId, NO_ACCOUNT_ID)
        transactionRepo.detachSeriesFromAccount(accountId, NO_ACCOUNT_ID)
        accountRepo.delete(accountId)
        AccountDeleteResult.Deleted(detachedTransactions = detached, deletedAdjustments = deletedAdjustments)
    }
}

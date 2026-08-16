package com.lop.budget.domain.usecase

import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.data.repository.AccountRepository
import com.lop.budget.data.repository.TransactionRepository
import com.lop.budget.domain.BalanceEngine
import com.lop.budget.domain.model.TransactionKind
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

@Singleton
class AdjustBalanceUseCase @Inject constructor(
    private val accountRepo: AccountRepository,
    private val transactionRepo: TransactionRepository
) {
    suspend fun adjust(accountId: Long, newTargetBalance: Double) {
        val account = accountRepo.getById(accountId) ?: return
        val allTransactions = transactionRepo.observeAll().first().map { it.transaction }
        
        val currentBalances = BalanceEngine.calculateBalances(listOf(account), allTransactions)
        val currentBalance = currentBalances[accountId] ?: account.initialBalance
        
        val delta = newTargetBalance - currentBalance
        if (delta == 0.0) return
        
        val type = if (delta > 0) TransactionType.INCOME else TransactionType.EXPENSE
        val adjustmentTx = TransactionEntity(
            title = "Ajustement de solde",
            amount = abs(delta),
            type = type,
            status = TransactionStatus.PAID,
            kind = TransactionKind.BALANCE_ADJUSTMENT,
            date = System.currentTimeMillis(),
            paidAt = System.currentTimeMillis(),
            accountId = accountId,
            categoryId = 0L,
            note = "Ajustement automatique du solde",
        )
        
        transactionRepo.upsert(adjustmentTx)
    }
}

package com.lop.budget.domain.usecase

import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.data.repository.AccountRepository
import com.lop.budget.data.repository.CategoryRepository
import com.lop.budget.data.repository.TransactionRepository
import com.lop.budget.domain.RecurrenceEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ObserveTransactionUseCase @Inject constructor(
    private val transactionRepo: TransactionRepository,
    private val accountRepo: AccountRepository,
    private val categoryRepo: CategoryRepository,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(id: Long): Flow<TransactionWithRelations?> {
        if (id < 0L) {
            return flow {
                val initial = getById(id)
                if (initial == null) { emit(null); return@flow }
                val sId = initial.transaction.seriesId
                val sDate = initial.transaction.seriesDate ?: initial.transaction.date
                if (sId == null) emit(initial) else {
                    emitAll(combine(
                        transactionRepo.observeSeries(sId),
                        transactionRepo.observeActiveSeries(),
                        accountRepo.observeAll(),
                        categoryRepo.observeAll()
                    ) { realTxs, allSeries, accounts, categories ->
                        val realMatch = realTxs.find { it.transaction.seriesDate == sDate }
                        if (realMatch != null) return@combine realMatch
                        val series = allSeries.find { it.id.toString() == sId }
                        if (series != null) {
                            val vMatch = RecurrenceEngine.generateOccurrences(series, sDate, sDate).find { it.seriesDate == sDate }
                            if (vMatch != null) {
                                return@combine TransactionWithRelations(vMatch, categories.find { it.id == series.categoryId }, accounts.find { it.id == series.accountId }, emptyList())
                            }
                        }
                        null
                    })
                }
            }
        }

        return transactionRepo.observeById(id).flatMapLatest { current ->
            if (current == null) return@flatMapLatest flowOf(null)
            val sId = current.transaction.seriesId
            val sDate = current.transaction.seriesDate ?: current.transaction.date
            if (sId == null) flowOf(current) else {
                combine(
                    transactionRepo.observeSeries(sId),
                    transactionRepo.observeActiveSeries(),
                    accountRepo.observeAll(),
                    categoryRepo.observeAll()
                ) { realTxs, allSeries, accounts, categories ->
                    val realMatch = realTxs.find { it.transaction.seriesDate == sDate }
                    if (realMatch != null) return@combine realMatch
                    val series = allSeries.find { it.id.toString() == sId }
                    if (series != null) {
                        val vMatch = RecurrenceEngine.generateOccurrences(series, sDate, sDate).find { it.seriesDate == sDate }
                        if (vMatch != null) {
                            return@combine TransactionWithRelations(vMatch, categories.find { it.id == series.categoryId }, accounts.find { it.id == series.accountId }, emptyList())
                        }
                    }
                    null
                }
            }
        }
    }

    suspend fun getById(id: Long): TransactionWithRelations? {
        if (id >= 0L) return transactionRepo.getById(id)
        val seriesList = transactionRepo.observeActiveSeries().first()
        for (series in seriesList) {
            val start = java.util.Calendar.getInstance().apply { add(java.util.Calendar.YEAR, -1) }.timeInMillis
            val end = java.util.Calendar.getInstance().apply { add(java.util.Calendar.YEAR, 2) }.timeInMillis
            val match = RecurrenceEngine.generateOccurrences(series, start, end).find { it.id == id }
            if (match != null) {
                return TransactionWithRelations(match, categoryRepo.getById(match.categoryId), accountRepo.getById(match.accountId), emptyList())
            }
        }
        return null
    }
}

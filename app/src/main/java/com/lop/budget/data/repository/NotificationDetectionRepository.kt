package com.lop.budget.data.repository

import com.lop.budget.data.local.dao.DetectedTransactionProposalDao
import com.lop.budget.data.local.entity.DetectedTransactionProposalEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationDetectionRepository @Inject constructor(
    private val dao: DetectedTransactionProposalDao,
) {
    fun observePending(): Flow<List<DetectedTransactionProposalEntity>> = dao.observePending()

    suspend fun upsertIfNotDuplicate(
        proposal: DetectedTransactionProposalEntity,
        dedupeWindowMs: Long,
    ): Long {
        val since = proposal.detectedAt - dedupeWindowMs
        val dup = dao.findRecentDuplicate(proposal.dedupeKey, since)
        if (dup != null) return -1L
        return dao.insert(proposal)
    }

    suspend fun ignore(id: Long) = dao.ignore(id)
}

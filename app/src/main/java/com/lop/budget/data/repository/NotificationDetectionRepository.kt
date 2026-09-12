package com.lop.budget.data.repository

import com.lop.budget.data.local.dao.DetectedTransactionProposalDao
import com.lop.budget.data.local.entity.toEntity
import com.lop.budget.data.local.entity.toProposal
import com.lop.budget.domain.model.Proposal
import com.lop.budget.domain.usecase.MergeResult
import com.lop.budget.domain.usecase.ProposalRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationDetectionRepository @Inject constructor(
    private val dao: DetectedTransactionProposalDao,
) : ProposalRepository {

    override fun observePending(): Flow<List<Proposal>> =
        dao.observePending().map { rows -> rows.map { it.toProposal() } }

    /**
     * ÉCART E-11 CONSERVÉ : un doublon est bien reconnu, mais **rien n'est incrémenté** — la
     * colonne `occurrences` reste à 1 et le second horodatage est perdu. L'événement écarté ne
     * laisse donc aucune trace sur la proposition conservée (I-7, CA-12). TC-107 T-05 et
     * TC-109 T-02 sont là pour le rendre visible.
     */
    override suspend fun upsertOrMerge(
        proposal: Proposal,
        windowMillis: Long,
        nowMillis: Long,
    ): MergeResult {
        val since = nowMillis - windowMillis
        val duplicate = dao.findRecentDuplicate(proposal.dedupeKey, since)
        if (duplicate != null) return MergeResult.Merged(duplicate.id, duplicate.occurrences)
        return MergeResult.Inserted(dao.insert(proposal.toEntity()))
    }

    override suspend fun refuse(proposalId: Long) = dao.ignore(proposalId)
}

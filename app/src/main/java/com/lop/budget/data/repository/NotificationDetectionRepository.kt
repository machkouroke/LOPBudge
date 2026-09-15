package com.lop.budget.data.repository

import com.lop.budget.data.local.dao.DetectedTransactionProposalDao
import com.lop.budget.data.local.entity.toEntity
import com.lop.budget.data.local.entity.toProposal
import com.lop.budget.domain.model.Proposal
import com.lop.budget.domain.usecase.detection.MergeResult
import com.lop.budget.domain.usecase.detection.ProposalRepository
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
     * Écrit la proposition, ou la regroupe avec une proposition de même clé reçue dans la fenêtre.
     *
     * Corrigé le 15 septembre 2026 (ANO-K) : le doublon était bien reconnu mais **rien n'était
     * incrémenté**, si bien que l'événement écarté disparaissait sans laisser de trace sur la
     * proposition conservée — ce que I-7 interdit. Le compteur rendu est **relu en base** après la
     * mise à jour, pour qu'il ne puisse pas s'écarter de ce qui est réellement stocké (CA-12).
     */
    override suspend fun upsertOrMerge(
        proposal: Proposal,
        windowMillis: Long,
        nowMillis: Long,
    ): MergeResult {
        val since = nowMillis - windowMillis
        val duplicate = dao.findRecentDuplicate(proposal.dedupeKey, since)
        if (duplicate != null) {
            dao.registerDuplicate(duplicate.id, proposal.detectedAt)
            val fusionnee = dao.getById(duplicate.id) ?: return MergeResult.Merged(duplicate.id, duplicate.occurrences)
            return MergeResult.Merged(fusionnee.id, fusionnee.occurrences)
        }
        return MergeResult.Inserted(dao.insert(proposal.toEntity()))
    }

    override suspend fun getById(proposalId: Long): Proposal? =
        dao.getById(proposalId)?.toProposal()

    override suspend fun refuse(proposalId: Long) = dao.ignore(proposalId)

    override suspend fun confirm(proposalId: Long, transactionId: Long) =
        dao.confirm(proposalId, transactionId)
}

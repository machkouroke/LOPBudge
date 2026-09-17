package com.lop.budget.data.repository

import com.lop.budget.data.local.dao.LoanDao
import com.lop.budget.data.local.dao.LoanOperations
import com.lop.budget.data.local.entity.LoanEntity
import com.lop.budget.domain.model.ItemStatus
import com.lop.budget.domain.model.LoanDirection
import com.lop.budget.domain.model.LoanDirectionImmutableException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Seul chemin d'écriture d'un prêt : aucun écran n'écrit un élément financier directement.
 *
 * Ce repository n'est pas un simple délégataire du DAO, contrairement à la plupart des autres :
 * deux invariants de LOP-80 ne peuvent vivre nulle part ailleurs.
 *
 * - **I-2** — la progression enregistrée ne se saisit jamais. À la création elle part du montant de
 *   départ ; à la mise à jour elle est reprise depuis la base. Seul [updateRepaidAmountCents],
 *   appelé par le moteur de calcul, la fait bouger.
 * - **I-6** — la direction est fixée à la création. [update] relit la ligne avant d'écrire et
 *   refuse tout changement de sens, sans rien persister.
 */
@Singleton
class LoanRepository @Inject constructor(
    private val loanDao: LoanDao,
) : LoanOperations {

    override fun observeActive() = loanDao.observeActive()

    override fun observeActiveByDirection(direction: LoanDirection) =
        loanDao.observeActiveByDirection(direction)

    override suspend fun getById(id: Long) = loanDao.getById(id)

    /**
     * La progression proposée à la création n'est écrite nulle part : elle est remplacée par le
     * montant de départ, seule valeur qu'un utilisateur saisit légitimement (I-2, P-3).
     */
    override suspend fun create(loan: LoanEntity): Long =
        loanDao.upsert(loan.copy(id = 0, repaidAmountCents = loan.startingBalanceCents))

    /**
     * Lire avant d'écrire, pour deux raisons distinctes : comparer la direction (I-6) et reprendre
     * la progression persistée plutôt que celle de l'objet reçu (I-2).
     *
     * Un identifiant inconnu est un no-op : rien à mettre à jour, rien à refuser.
     */
    override suspend fun update(loan: LoanEntity) {
        val current = loanDao.getById(loan.id) ?: return
        if (current.direction != loan.direction) {
            throw LoanDirectionImmutableException(loan.id, current.direction, loan.direction)
        }
        loanDao.upsert(loan.copy(repaidAmountCents = current.repaidAmountCents))
    }

    /** Archiver ne supprime rien et ne touche à aucun rattachement (I-4). */
    override suspend fun archive(id: Long) = loanDao.updateStatus(id, ItemStatus.ARCHIVED)

    /**
     * Supprimer le prêt ne supprime aucune transaction : les rattachements passent à `NULL` par la
     * clé étrangère `ON DELETE SET NULL` déclarée sur les colonnes enfants (I-5, P-6). Aucun
     * nettoyage applicatif ici — la suppression se traite à la source.
     */
    override suspend fun delete(id: Long) = loanDao.delete(id)

    /** Unique voie d'écriture de la progression, réservée au moteur de calcul (I-2). */
    override suspend fun updateRepaidAmountCents(id: Long, cents: Long) =
        loanDao.updateRepaidAmountCents(id, cents)
}

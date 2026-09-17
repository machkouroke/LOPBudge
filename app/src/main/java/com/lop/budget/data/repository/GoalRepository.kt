package com.lop.budget.data.repository

import com.lop.budget.data.local.dao.GoalDao
import com.lop.budget.data.local.dao.GoalOperations
import com.lop.budget.data.local.entity.GoalEntity
import com.lop.budget.domain.model.ItemStatus
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Seul chemin d'écriture d'un objectif : aucun écran n'écrit un élément financier directement.
 *
 * Comme [LoanRepository], ce repository porte **I-2** : la progression enregistrée ne se saisit
 * jamais. À la création elle part du montant de départ, à la mise à jour elle est reprise depuis la
 * base, et seul [updateSavedAmountCents] — appelé par le moteur de calcul — la fait bouger.
 *
 * Un objectif n'a pas de direction : il n'a donc rien d'équivalent à I-6.
 */
@Singleton
class GoalRepository @Inject constructor(
    private val goalDao: GoalDao,
) : GoalOperations {

    override fun observeActive() = goalDao.observeActive()

    override suspend fun getById(id: Long) = goalDao.getById(id)

    /**
     * La progression proposée à la création n'est écrite nulle part : elle est remplacée par le
     * montant de départ, seule valeur qu'un utilisateur saisit légitimement (I-2, P-3).
     */
    override suspend fun create(goal: GoalEntity): Long =
        goalDao.upsert(goal.copy(id = 0, savedAmountCents = goal.startingBalanceCents))

    /**
     * Relit la ligne pour reprendre la progression persistée plutôt que celle de l'objet reçu.
     * Un identifiant inconnu est un no-op.
     */
    override suspend fun update(goal: GoalEntity) {
        val current = goalDao.getById(goal.id) ?: return
        goalDao.upsert(goal.copy(savedAmountCents = current.savedAmountCents))
    }

    /** Archiver ne supprime rien et ne touche à aucun rattachement (I-4). */
    override suspend fun archive(id: Long) = goalDao.updateStatus(id, ItemStatus.ARCHIVED)

    /**
     * Supprimer l'objectif ne supprime aucune transaction : les rattachements passent à `NULL` par
     * la clé étrangère `ON DELETE SET NULL` déclarée sur les colonnes enfants (I-5, P-6).
     */
    override suspend fun delete(id: Long) = goalDao.delete(id)

    /** Unique voie d'écriture de la progression, réservée au moteur de calcul (I-2). */
    override suspend fun updateSavedAmountCents(id: Long, cents: Long) =
        goalDao.updateSavedAmountCents(id, cents)
}

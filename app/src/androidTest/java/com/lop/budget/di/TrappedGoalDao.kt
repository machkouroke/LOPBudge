package com.lop.budget.di

import com.lop.budget.data.local.dao.GoalDao
import com.lop.budget.domain.model.ItemStatus
import java.util.concurrent.atomic.AtomicInteger

/**
 * DAO d'objectif de la suite instrumentée : le vrai `GoalDao` de Room, sauf `updateStatus`, qu'un cas
 * peut faire échouer **une fois** pour un objectif et un statut donnés (TC-177, U-05). Désarmé par
 * défaut, il est transparent pour toutes les suites.
 *
 * Hilt le recrée pour chaque cas, comme [GatedAtomicWriter] : l'armer ne touche aucune autre classe.
 * Il rend une erreur visible ; il ne prouve aucun rollback.
 */
class TrappedGoalDao(private val real: GoalDao) : GoalDao by real {

    @Volatile
    private var trap: Pair<Long, ItemStatus>? = null

    /** Échecs effectivement provoqués depuis le début du cas. */
    val failures = AtomicInteger()

    fun armOnce(id: Long, status: ItemStatus) {
        trap = id to status
    }

    override suspend fun updateStatus(id: Long, status: ItemStatus) {
        if (trap == (id to status)) {
            trap = null
            failures.incrementAndGet()
            throw IllegalStateException("QA-206 échec contrôlé")
        }
        real.updateStatus(id, status)
    }
}

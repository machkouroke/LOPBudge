package com.lop.budget.di

import androidx.room.withTransaction
import com.lop.budget.data.local.LopDatabase
import com.lop.budget.domain.usecase.transaction.AtomicWriter
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicInteger

/**
 * Écrivain atomique de la suite instrumentée : le vrai `db.withTransaction` d'`AppModule`, précédé
 * d'une porte. Ouverte par défaut, elle est transparente pour toutes les suites. Un cas peut la
 * fermer pour retenir une écriture le temps d'observer l'écran (TC-168, U-04), puis la rouvrir.
 *
 * Hilt la recrée pour chaque cas, comme [TestClock] : la fermer ne touche aucune autre classe.
 */
class GatedAtomicWriter(private val db: LopDatabase) : AtomicWriter {

    @Volatile
    private var gate: CompletableDeferred<Unit>? = null

    /** Écritures parvenues à la porte depuis le début du cas. */
    val passages = AtomicInteger()

    fun close() {
        gate = CompletableDeferred()
    }

    fun open() {
        gate?.complete(Unit)
        gate = null
    }

    override suspend fun <T> atomically(block: suspend () -> T): T {
        passages.incrementAndGet()
        gate?.await()
        return db.withTransaction(block)
    }
}

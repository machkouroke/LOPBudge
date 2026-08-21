package com.lop.budget.domain.usecase

import com.lop.budget.data.local.entity.RecurringSeriesEntity
import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.data.repository.TransactionRepository
import com.lop.budget.domain.model.EditScope
import com.lop.budget.domain.model.RecurrenceFrequency
import com.lop.budget.domain.model.TransactionEdition
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.toDaysOfWeekCsv
import com.lop.budget.domain.model.toSeriesEntity
import com.lop.budget.domain.model.toTransactionEntity
import javax.inject.Inject

class EditTransactionWithScopeUseCase @Inject constructor(
    private val transactionRepo: TransactionRepository,
    private val saveTransactionUseCase: SaveTransactionUseCase,
) {
    /**
     * Applique une édition à une transaction selon la portée choisie (US « Édition contextuelle »).
     *
     * Contrat d'appel :
     * - Pour une occurrence VIRTUELLE (editingId < 0), l'appelant DOIT fournir seriesId et seriesDate
     *   (la résolution des occurrences virtuelles vit dans ObserveTransactionUseCase, pas ici).
     * - En portée ALL, le formulaire est prérempli avec les valeurs de base de la série (CA-08) :
     *   edition.date représente donc la date de début de série, pas la date de l'occurrence.
     * - I-6 : aucune matérialisation n'a lieu avant ce point d'entrée (validation de la sauvegarde).
     */
    suspend operator fun invoke(
        editingId: Long,
        seriesId: Long?,
        seriesDate: Long?,
        edition: TransactionEdition,
        scope: EditScope,
    ): Long {
        val current = transactionRepo.getById(editingId)
        val originalSeriesDate = seriesDate?.takeIf { it > 0L }
            ?: current?.transaction?.seriesDate
            ?: current?.transaction?.date
            ?: edition.date
        val status = edition.status ?: current?.transaction?.status ?: TransactionStatus.PLANNED

        return when (scope) {
            EditScope.SINGLE -> editSingle(
                editingId,
                seriesId,
                originalSeriesDate,
                edition,
                status,
                current
            )

            EditScope.FUTURE -> editFuture(
                editingId,
                seriesId,
                originalSeriesDate,
                edition,
                status,
                current
            )

            EditScope.ALL -> editAll(
                editingId,
                seriesId,
                edition,
                status,
                current
            )
        }
    }

    // ---------------------------------------------------------------- SINGLE

    private suspend fun editSingle(
        editingId: Long,
        seriesId: Long?,
        originalSeriesDate: Long,
        edition: TransactionEdition,
        status: TransactionStatus,
        current: TransactionWithRelations?,
    ): Long = when {
        // Garde-fou I-5 : SINGLE ne modifie jamais la série et ne détache jamais l'occurrence,
        // même si le formulaire retombe sur frequency == NONE (fallback UI).
        seriesId != null -> {
            // I-6 : matérialisation uniquement à la validation de la sauvegarde.
            val targetId =
                if (editingId < 0L) transactionRepo.materializeOccurrence(
                    seriesId,
                    originalSeriesDate
                )
                else editingId
            val existingPaidAt = (current ?: transactionRepo.getById(targetId))?.transaction?.paidAt
            saveTransactionUseCase.saveSimple(
                // CA-09 SINGLE : `date` = date du formulaire, `seriesDate` conservé (I-1).
                edition.toTransactionEntity(
                    id = targetId,
                    status = status,
                    paidAt = existingPaidAt,
                    seriesId = seriesId,
                    seriesDate = originalSeriesDate,
                    isException = true,
                ),
                edition.tagIds,
            )
        }

        // Ponctuelle restant ponctuelle.
        edition.frequency == RecurrenceFrequency.NONE -> saveTransactionUseCase.saveSimple(
            edition.toTransactionEntity(
                id = if (editingId > 0) editingId else 0L,
                status = status,
                paidAt = current?.transaction?.paidAt,
                seriesId = null,
                seriesDate = null,
                isException = false,
            ),
            edition.tagIds,
        )

        // Ponctuelle -> récurrente (CA-01 : pas de choix de portée pour une ponctuelle).
        else -> {
            transactionRepo.hardDelete(editingId)
            val newSeriesId = transactionRepo.upsertSeries(edition.toSeriesEntity())
            val newTxId = transactionRepo.materializeOccurrence(newSeriesId, edition.date)
            applyStatusAndTags(newTxId, edition.tagIds, status, current?.transaction?.paidAt)
        }
    }

    // ---------------------------------------------------------------- FUTURE

    private suspend fun editFuture(
        editingId: Long,
        seriesId: Long?,
        originalSeriesDate: Long,
        edition: TransactionEdition,
        status: TransactionStatus,
        current: TransactionWithRelations?,
    ): Long {
        if (seriesId == null) return editingId
        val oldSeries = transactionRepo.getSeriesById(seriesId) ?: return editingId
        val consultedId = current?.transaction?.id

        // Réf. 97 — NE PAS RÉGRESSER : aucun slot de l'ancienne grille ne doit survivre en doublon.
        // CA-03 — la coupure suit la date d'affichage : on prend le plus tôt des trois points pour
        // couvrir date reculée, date avancée et exception déplacée.
        val displayDate = current?.transaction?.date ?: originalSeriesDate
        val pivot = minOf(originalSeriesDate, displayDate, edition.date)

        // I-4 : on arrête seulement la génération des virtuels de l'ancienne série ;
        // aucune exception matérialisée n'est supprimée ni déplacée.
        transactionRepo.updateSeries(oldSeries.copy(endDate = pivot - 1))

        val overlay = editionOverlay(edition, oldSeries)

        // CA-10 — retrait de récurrence en FUTURE : le passé reste une série ; l'occurrence
        // consultée et les exceptions suivantes deviennent ponctuelles (diff uniquement, I-7).
        if (edition.frequency == RecurrenceFrequency.NONE) {
            transactionRepo.getExceptionsBySeries(seriesId)
                .filter { it.date >= pivot && it.id != consultedId }
                .forEach {
                    transactionRepo.upsert(
                        overlay(it).copy(seriesId = null, seriesDate = null, isException = false)
                    )
                }
            return saveTransactionUseCase.saveSimple(
                edition.toTransactionEntity(
                    id = if (current?.transaction?.seriesId == seriesId) current.transaction.id else 0L,
                    status = status,
                    paidAt = current?.transaction?.paidAt,
                    seriesId = null,
                    seriesDate = null,
                    isException = false,
                ),
                edition.tagIds,
            )
        }

        // Cas nominal : nouvelle série ancrée sur la date du formulaire (CA-09 FUTURE).
        val newSeriesId = transactionRepo.upsertSeries(edition.toSeriesEntity())

        // CA-03 / CA-05 : les exceptions à partir du pivot migrent vers la nouvelle série et ne
        // reçoivent que les champs réellement modifiés ; `date` et `seriesDate` conservés (I-1).
        transactionRepo.getExceptionsBySeries(seriesId)
            .filter { it.date >= pivot && it.id != consultedId }
            .forEach { transactionRepo.upsert(overlay(it).copy(seriesId = newSeriesId)) }

        // Occurrence consultée : ancrage de la nouvelle série, elle porte tout le formulaire.
        return if (current != null && current.transaction.seriesId == seriesId) {
            saveTransactionUseCase.saveSimple(
                edition.toTransactionEntity(
                    id = current.transaction.id,
                    status = status,
                    paidAt = current.transaction.paidAt,
                    seriesId = newSeriesId,
                    seriesDate = current.transaction.seriesDate, // I-1 : slot d'origine conservé
                    isException = true,
                ),
                edition.tagIds,
            )
        } else {
            // Virtuelle : matérialisée sur la nouvelle série au moment de la sauvegarde (I-6).
            val newTxId = transactionRepo.materializeOccurrence(newSeriesId, edition.date)
            applyStatusAndTags(newTxId, edition.tagIds, status, existingPaidAt = null)
        }
    }

    // ------------------------------------------------------------------- ALL

    private suspend fun editAll(
        editingId: Long,
        seriesId: Long?,
        edition: TransactionEdition,
        status: TransactionStatus,
        current: TransactionWithRelations?,
    ): Long {
        if (seriesId == null) return editingId
        val existing = transactionRepo.getSeriesById(seriesId) ?: return editingId
        val overlay = editionOverlay(edition, existing)
        val consultedId = current?.transaction?.id

        // CA-10 — retrait de récurrence en ALL : série annulée, tous les virtuels disparaissent,
        // chaque exception visible devient ponctuelle (I-4 : aucune n'est supprimée).
        if (edition.frequency == RecurrenceFrequency.NONE) {
            transactionRepo.updateSeriesCancelled(seriesId, true)
            transactionRepo.getExceptionsBySeries(seriesId)
                .filter { it.id != consultedId }
                .forEach {
                    transactionRepo.upsert(
                        overlay(it).copy(seriesId = null, seriesDate = null, isException = false)
                    )
                }
            return if (current != null && current.transaction.seriesId == seriesId) {
                saveTransactionUseCase.saveSimple(
                    overlay(current.transaction)
                        .copy(
                            seriesId = null,
                            seriesDate = null,
                            isException = false,
                            status = status
                        ),
                    edition.tagIds,
                )
            } else editingId // virtuelle : elle disparaît avec la série (CA-10)
        }

        // CA-08 / CA-09 ALL : le formulaire est prérempli avec les valeurs de base de la série ;
        // edition.date représente la date de début, réappliquée telle quelle.
        transactionRepo.updateSeries(
            existing.copy(
                title = edition.title,
                amount = edition.amount,
                type = edition.type,
                categoryId = edition.categoryId,
                accountId = edition.accountId,
                frequency = edition.frequency,
                interval = edition.interval,
                startDate = edition.date,
                endDate = edition.endDate,
                maxOccurrences = edition.maxOccurrences,
                daysOfWeek = edition.daysOfWeek.toDaysOfWeekCsv(),
                note = edition.note,
                linkedGoalId = edition.linkedGoalId,
                linkedDebtId = edition.linkedDebtId,
            )
        )

        // CA-05 / I-7 : propagation du seul diff ; `date` et `seriesDate` jamais réécrits (I-1, I-4).
        transactionRepo.getExceptionsBySeries(seriesId)
            .filter { it.id != consultedId }
            .forEach { exception ->
                val patched = overlay(exception)
                if (patched != exception) transactionRepo.upsert(patched)
            }

        return when {
            current != null && current.transaction.seriesId == seriesId ->
                // CA-05 : l'occurrence consultée suit la même règle de diff que les autres
                // exceptions ; seuls statut et tags du formulaire lui restent propres.
                saveTransactionUseCase.saveSimple(
                    overlay(current.transaction).copy(status = status),
                    edition.tagIds,
                )
            // Virtuelle : JAMAIS matérialisée en ALL (I-6) — elle reflétera la série mise à jour.
            // Un changement de statut/tags par occurrence passe par la portée SINGLE (CA-07).
            else -> editingId
        }
    }

    // --------------------------------------------------------------- Helpers

    /**
     * CA-05 / I-7 : patch ne portant que les champs réellement modifiés, calculés par différence
     * entre le formulaire soumis et la série AVANT édition. Les personnalisations antérieures des
     * exceptions sont conservées pour tout champ non modifié. `date` et `seriesDate` ne sont
     * jamais réécrits par la propagation (I-1).
     */
    private fun editionOverlay(
        edition: TransactionEdition,
        base: RecurringSeriesEntity,
    ): (TransactionEntity) -> TransactionEntity {
        val patches = buildList<(TransactionEntity) -> TransactionEntity> {
            if (edition.title != base.title) add { it.copy(title = edition.title) }
            if (edition.amount != base.amount) add { it.copy(amount = edition.amount) }
            if (edition.type != base.type) add { it.copy(type = edition.type) }
            if (edition.categoryId != base.categoryId) add { it.copy(categoryId = edition.categoryId) }
            if (edition.accountId != base.accountId) add { it.copy(accountId = edition.accountId) }
            if (edition.note != base.note) add { it.copy(note = edition.note) }
            // CA-12 : les rattachements suivent les mêmes règles de propagation que les autres champs.
            if (edition.linkedGoalId != base.linkedGoalId) add { it.copy(linkedGoalId = edition.linkedGoalId) }
            if (edition.linkedDebtId != base.linkedDebtId) add { it.copy(linkedDebtId = edition.linkedDebtId) }
        }
        return { tx -> patches.fold(tx) { acc, patch -> patch(acc) } }
    }

    /** Applique statut + tags à une occurrence fraîchement matérialisée (paidAt normalisé par saveSimple). */
    private suspend fun applyStatusAndTags(
        txId: Long,
        tagIds: List<Long>,
        status: TransactionStatus,
        existingPaidAt: Long?,
    ): Long {
        val tx = transactionRepo.getById(txId)?.transaction ?: return txId
        saveTransactionUseCase.saveSimple(
            tx.copy(
                status = status,
                paidAt = existingPaidAt ?: tx.paidAt
            ), tagIds
        )
        return txId
    }
}
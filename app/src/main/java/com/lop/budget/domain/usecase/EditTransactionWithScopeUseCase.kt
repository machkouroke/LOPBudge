package com.lop.budget.domain.usecase

import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.data.repository.TransactionRepository
import com.lop.budget.domain.model.EditScope
import com.lop.budget.domain.model.RecurrenceFrequency
import com.lop.budget.domain.model.SeriesCancelMode
import com.lop.budget.domain.model.TransactionEdition
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.toDaysOfWeekCsv
import com.lop.budget.domain.model.toSeriesEntity
import com.lop.budget.domain.model.toTransactionEntity
import java.util.Calendar
import javax.inject.Inject

class EditTransactionWithScopeUseCase @Inject constructor(
    private val transactionRepo: TransactionRepository,
    private val saveTransactionUseCase: SaveTransactionUseCase,
    private val cancelRecurringSeriesUseCase: CancelRecurringSeriesUseCase
) {
    /**
     * Applique une édition à une transaction selon la portée choisie.
     *
     * Contrat d'appel :
     * - Pour une occurrence VIRTUELLE (editingId < 0), l'appelant DOIT fournir seriesId et seriesDate
     *   (la résolution des occurrences virtuelles vit dans ObserveTransactionUseCase, pas ici).
     * - originalSeriesDate est résolu par priorité : seriesDate (argument) > slot persisté (seriesDate
     *   de la ligne) > date affichée > date du formulaire. Pour une exception matérialisée déplacée,
     *   le pivot d'édition FUTURE se base donc sur le SLOT, alors que la suppression FUTURE se base
     *   sur la date d'affichage : écart connu, décision produit à trancher par les tests de l'US
     *   « Édition contextuelle » — ne rien changer ici.
     */
    suspend operator fun invoke(
        editingId: Long,
        seriesId: Long?,
        seriesDate: Long?,
        edition: TransactionEdition,
        scope: EditScope
    ): Long {
        val initialTwr = transactionRepo.getById(editingId)
        val originalSeriesDate = seriesDate ?: initialTwr?.transaction?.seriesDate ?: initialTwr?.transaction?.date ?: edition.date

        var finalEditingId = editingId
        var currentTwr = initialTwr

        if (finalEditingId < 0L && scope != EditScope.ALL) {
            if (seriesId != null) {
                finalEditingId = transactionRepo.materializeOccurrence(seriesId, originalSeriesDate)
                currentTwr = transactionRepo.getById(finalEditingId)
            }
        }

        val finalStatus = edition.status ?: currentTwr?.transaction?.status ?: TransactionStatus.PLANNED

        return when (scope) {
            EditScope.SINGLE -> editSingle(finalEditingId, seriesId, originalSeriesDate, edition, finalStatus, currentTwr)
            EditScope.FUTURE -> editFuture(seriesId, originalSeriesDate, edition, finalStatus, currentTwr, finalEditingId)
            EditScope.ALL -> editAll(seriesId, originalSeriesDate, edition, finalStatus, currentTwr, finalEditingId)
        }
    }

    private suspend fun editSingle(
        finalEditingId: Long,
        seriesId: Long?,
        originalSeriesDate: Long,
        edition: TransactionEdition,
        finalStatus: TransactionStatus,
        currentTwr: TransactionWithRelations?
    ): Long {
        return when {
            // Garde-fou I-5 : SINGLE ne modifie jamais la série et ne détache jamais l'occurrence.
            seriesId != null -> saveSimpleEdition(
                finalEditingId, edition, finalStatus, currentTwr, seriesId, originalSeriesDate, true
            )
            edition.frequency == RecurrenceFrequency.NONE -> saveSimpleEdition(
                finalEditingId, edition, finalStatus, currentTwr, null, null, false
            )
            else -> {
                transactionRepo.hardDelete(finalEditingId)
                val newSeriesId = transactionRepo.upsertSeries(edition.toSeriesEntity())
                transactionRepo.materializeOccurrence(newSeriesId, edition.date)
            }
        }
    }

    private suspend fun editFuture(
        seriesId: Long?,
        originalSeriesDate: Long,
        edition: TransactionEdition,
        finalStatus: TransactionStatus,
        currentTwr: TransactionWithRelations?,
        finalEditingId: Long
    ): Long {
        if (seriesId != null) {
            // Réf. 97 — NE PAS RÉGRESSER.
            // La nouvelle série prend possession de la timeline à partir du plus tôt des deux points :
            // - date reculée (slot 15/02 -> 20/02) : pivot = slot, sinon l'ancien slot du 15/02 survit -> doublon ;
            // - date avancée (slot 22/02 -> 12/02, hebdo) : pivot = nouvelle date, sinon les anciens slots
            //   entre les deux dates survivent en doublon de la nouvelle série.
            val truncationPivot = minOf(originalSeriesDate, edition.date)
            cancelRecurringSeriesUseCase(seriesId, SeriesCancelMode.Future(truncationPivot))
            val newSeriesId = transactionRepo.upsertSeries(edition.toSeriesEntity())
            val newTxId = transactionRepo.materializeOccurrence(newSeriesId, edition.date)
            transactionRepo.getById(newTxId)?.let { matTwr ->
                saveTransactionUseCase.saveSimple(
                    matTwr.transaction.copy(
                        status = finalStatus,
                        paidAt = if (finalStatus == TransactionStatus.PAID) (currentTwr?.transaction?.paidAt ?: System.currentTimeMillis()) else null
                    ),
                    edition.tagIds
                )
            }
            return newTxId
        }
        return finalEditingId
    }

    private suspend fun editAll(
        seriesId: Long?,
        originalSeriesDate: Long,
        edition: TransactionEdition,
        finalStatus: TransactionStatus,
        currentTwr: TransactionWithRelations?,
        finalEditingId: Long
    ): Long {
        if (seriesId != null) {
            transactionRepo.getSeriesById(seriesId)?.let { existing ->
                val newStartDate = if (edition.date != originalSeriesDate) {
                    alignTimeOfDay(alignDayOfMonth(existing.startDate, edition.date), edition.date)
                } else {
                    existing.startDate
                }

                transactionRepo.updateSeries(
                    existing.copy(
                        title = edition.title,
                        amount = edition.amount,
                        type = edition.type,
                        categoryId = edition.categoryId,
                        accountId = edition.accountId,
                        frequency = edition.frequency,
                        interval = edition.interval,
                        startDate = newStartDate,
                        endDate = edition.endDate,
                        maxOccurrences = edition.maxOccurrences,
                        daysOfWeek = edition.daysOfWeek.toDaysOfWeekCsv(),
                        note = edition.note,
                        linkedGoalId = edition.linkedGoalId,
                        linkedDebtId = edition.linkedDebtId
                    )
                )

                val targetSlot = if (edition.date != originalSeriesDate) {
                    alignTimeOfDay(alignDayOfMonth(originalSeriesDate, edition.date), edition.date)
                } else {
                    originalSeriesDate
                }

                transactionRepo.updateSeriesExceptions(seriesId, edition.title, edition.amount, edition.type, edition.categoryId, edition.accountId, edition.note)

                if (currentTwr != null) {
                    return saveTransactionUseCase.saveSimple(
                        currentTwr.transaction.withEditionOverlay(edition, finalStatus, targetSlot, isException = true, existingPaidAt = currentTwr.transaction.paidAt),
                        edition.tagIds
                    )
                } else if (finalEditingId < 0L) {
                    val newTxId = transactionRepo.materializeOccurrence(seriesId, targetSlot)
                    transactionRepo.getById(newTxId)?.let { matTwr ->
                        saveTransactionUseCase.saveSimple(
                            matTwr.transaction.withEditionOverlay(edition, finalStatus, targetSlot, isException = matTwr.transaction.isException, existingPaidAt = null),
                            edition.tagIds
                        )
                    }
                    return newTxId
                }
            }
        }
        return finalEditingId
    }

    private suspend fun saveSimpleEdition(
        id: Long,
        edition: TransactionEdition,
        finalStatus: TransactionStatus,
        currentTwr: TransactionWithRelations?,
        seriesId: Long?,
        seriesDate: Long?,
        isException: Boolean,
    ): Long = saveTransactionUseCase.saveSimple(
        edition.toTransactionEntity(
            id = if (id > 0) id else 0L,
            status = finalStatus,
            paidAt = if (finalStatus == TransactionStatus.PAID) {
                currentTwr?.transaction?.paidAt ?: System.currentTimeMillis()
            } else null,
            seriesId = seriesId,
            seriesDate = seriesDate,
            isException = isException,
        ),
        edition.tagIds,
    )

    /** Reporte le jour-du-mois de [source] sur [base] (recalage du startDate de série). */
    private fun alignDayOfMonth(base: Long, source: Long): Long {
        val sourceCal = Calendar.getInstance().apply { timeInMillis = source }
        return Calendar.getInstance().apply {
            timeInMillis = base
            set(Calendar.DAY_OF_MONTH, sourceCal.get(Calendar.DAY_OF_MONTH))
        }.timeInMillis
    }

    /** Reporte l'heure/minute/seconde/milliseconde de [source] sur [base] (recalage du slot cible). */
    private fun alignTimeOfDay(base: Long, source: Long): Long {
        val sourceCal = Calendar.getInstance().apply { timeInMillis = source }
        return Calendar.getInstance().apply {
            timeInMillis = base
            set(Calendar.HOUR_OF_DAY, sourceCal.get(Calendar.HOUR_OF_DAY))
            set(Calendar.MINUTE, sourceCal.get(Calendar.MINUTE))
            set(Calendar.SECOND, sourceCal.get(Calendar.SECOND))
            set(Calendar.MILLISECOND, sourceCal.get(Calendar.MILLISECOND))
        }.timeInMillis
    }

    private fun TransactionEntity.withEditionOverlay(
        edition: TransactionEdition,
        finalStatus: TransactionStatus,
        targetSlot: Long,
        isException: Boolean,
        existingPaidAt: Long?,
    ): TransactionEntity = copy(
        title = edition.title,
        amount = edition.amount,
        type = edition.type,
        status = finalStatus,
        categoryId = edition.categoryId,
        accountId = edition.accountId,
        note = edition.note,
        date = edition.date,
        seriesDate = targetSlot,
        isException = isException,
        paidAt = if (finalStatus == TransactionStatus.PAID) {
            existingPaidAt ?: System.currentTimeMillis()
        } else null,
    )
}

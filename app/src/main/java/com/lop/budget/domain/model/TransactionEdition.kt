package com.lop.budget.domain.model

/**
 * Unique modèle d'écriture (write model) d'une transaction.
 *
 * Toute création ou modification de transaction dans l'app doit transiter par ce type :
 * UI (TransactionForm.toEdition) -> use cases (Create/EditTransactionWithScope) -> mappers -> entités Room.
 *
 * Règles :
 * - AUCUNE valeur par défaut : ajouter un champ force le compilateur à énumérer tous les points de construction.
 * - Aucun format de persistance ici : daysOfWeek est un Set<Int> métier, la conversion CSV Room
 *   vit exclusivement dans TransactionEditionMappers.kt.
 */
data class TransactionEdition(
    val title: String,
    val amount: Double,
    val type: TransactionType,
    val date: Long,
    val accountId: Long,
    val categoryId: Long,
    val note: String?,
    val status: TransactionStatus?,
    val frequency: RecurrenceFrequency,
    val interval: Int,
    val daysOfWeek: Set<Int>,
    val endDate: Long?,
    val maxOccurrences: Int?,
    val linkedGoalId: Long?,
    val linkedDebtId: Long?,
    val tagIds: List<Long>,
)

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
 * - Une transaction peut désigner un objectif, un prêt, ou les deux (P-1 de LOP-80) : ce sont deux
 *   suivis distincts, et un même mouvement peut faire avancer les deux. Aucune garde ici.
 */
data class TransactionEdition(
    val title: String,
    /** Montant en centimes : la conversion depuis les euros saisis se fait dans le ViewModel. */
    val amount: Long,
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
    val linkedLoanId: Long?,
    val tagIds: List<Long>,
)

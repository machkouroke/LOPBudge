package com.lop.budget.domain.model

/**
 * Proposition de transaction détectée via une notification (US LOP-54, vocabulaire « Proposition »).
 *
 * Modèle de domaine : aucun type Android, aucune annotation Room. C'est ce que manipulent les use
 * cases de détection, d'enregistrement et de refus, ce qui les rend invocables hors appareil (I-10).
 * Le montant est en **centimes entiers**, comme le reste du modèle d'écriture (P-1 / I-3).
 *
 * Une proposition n'est pas une transaction : elle n'entre dans aucun solde ni statistique (I-2).
 */
data class Proposal(
    val id: Long = 0,
    val sourcePackage: String,
    val amountCents: Long,
    val currency: String?,
    val label: String,
    val fullText: String = "",
    val cardName: String? = null,
    val detectedAt: Long,
    /** Clé normalisée du regroupement anti-doublon (P-2). */
    val dedupeKey: String,
    val status: ProposalStatus = ProposalStatus.PENDING,
    val confidence: Float = 1.0f,
    val suggestedCategoryId: Long? = null,
    /** Renseigné à la confirmation, pour garder le lien proposition -> transaction (I-6). */
    val createdTransactionId: Long? = null,
    /** Nombre de notifications regroupées sur cette proposition (P-2 / I-7). */
    val occurrences: Int = 1,
    /** Horodatage de la dernière notification regroupée. */
    val lastDetectedAt: Long = detectedAt,
)

enum class ProposalStatus { PENDING, UNCERTAIN, CONFIRMED, IGNORED }

/**
 * Pré-remplissage du formulaire d'édition à partir d'une proposition (P-10, fonction pure).
 *
 * Ni l'horloge ni les réglages ne sont lus ici : le compte et la catégorie par défaut sont des
 * paramètres, pour qu'aucun identifiant ne puisse être choisi par le code (I-8).
 *
 * ÉCART E-5 / P-4 conservé : le statut posé est [TransactionStatus.PLANNED] alors que le paiement a
 * déjà eu lieu au moment de la notification — la convention P-4 impose « réglé ». Le comportement
 * actuel est reconduit tel quel ; c'est à TC-110 T-02 de le rendre visible.
 */
fun buildEdition(
    proposal: Proposal,
    defaultAccountId: Long,
    defaultCategoryId: Long?,
): TransactionEdition = TransactionEdition(
    title = proposal.label.ifBlank { "Transaction" },
    amount = proposal.amountCents,
    type = TransactionType.EXPENSE,
    date = proposal.detectedAt,
    accountId = defaultAccountId,
    categoryId = proposal.suggestedCategoryId ?: defaultCategoryId ?: 0L,
    note = "Détecté via ${proposal.sourcePackage}",
    status = TransactionStatus.PLANNED,
    frequency = RecurrenceFrequency.NONE,
    interval = 1,
    daysOfWeek = emptySet(),
    endDate = null,
    maxOccurrences = null,
    linkedGoalId = null,
    linkedDebtId = null,
    tagIds = emptyList(),
)

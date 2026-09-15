package com.lop.budget.domain.usecase.transaction

import com.lop.budget.data.repository.AccountRepository
import com.lop.budget.domain.model.NO_ACCOUNT_ID
import com.lop.budget.domain.model.TransactionEdition
import com.lop.budget.domain.usecase.detection.ProposalRepository
import javax.inject.Inject

/**
 * Rend deux écritures indissociables.
 *
 * Port du domaine, implémenté dans `di/AppModule` par une transaction Room. Il existe pour que le
 * domaine puisse exiger l'atomicité **sans** recevoir la base : `AdjustBalanceUseCase` avait acté
 * qu'exposer `LopDatabase` au domaine était le prix à ne pas payer.
 */
interface AtomicWriter {
    suspend fun <T> atomically(block: suspend () -> T): T
}

/**
 * Enregistre la transaction issue d'une proposition, et solde la proposition (US LOP-54, P-10).
 *
 * C'est l'**unique** chemin d'écriture d'une proposition : accepter n'écrit rien, seule la
 * validation du formulaire passe ici (P-3). La proposition passe à **confirmée** en portant
 * l'identifiant de la transaction créée — jamais à ignorée, qui est réservé au refus explicite (I-6).
 *
 * Corrigé le 15 septembre 2026 (ANO-L), en deux volets :
 *
 * - **Validation avant toute écriture.** Un `accountId` non nul qui ne désigne aucun compte est une
 *   référence pendante : la table `transactions` n'ayant pas de clé étrangère, SQLite l'acceptait
 *   sans erreur et la dépense atterrissait sur un compte fantôme (I-8).
 * - **Atomicité.** La création et la confirmation partagent désormais une transaction de base : un
 *   échec entre les deux ne peut plus laisser une transaction sans confirmation, ni l'inverse (CA-16).
 *
 * L'absence de compte, elle, est **valide** : [NO_ACCOUNT_ID] signale qu'aucun compte n'est
 * rattaché, ce qui est autorisé depuis la décision produit du 15 septembre 2026.
 *
 * La catégorie n'est volontairement pas validée ici : `buildEdition` retombe légitimement sur
 * [com.lop.budget.domain.model.NO_CATEGORY_ID] quand aucune catégorie par défaut n'existe, et
 * durcir ce point changerait un comportement qu'aucun critère ne remet en cause.
 */
class SaveTransactionFromProposalUseCase @Inject constructor(
    private val createTransactionUseCase: CreateTransactionUseCase,
    private val proposals: ProposalRepository,
    private val accounts: AccountRepository,
    private val atomicWriter: AtomicWriter,
) {
    suspend operator fun invoke(proposalId: Long, edition: TransactionEdition): SaveResult {
        if (edition.accountId != NO_ACCOUNT_ID && accounts.getById(edition.accountId) == null) {
            return SaveResult.Failed("compte introuvable : ${edition.accountId}")
        }

        return runCatching {
            atomicWriter.atomically {
                val transactionId = createTransactionUseCase(edition)
                proposals.confirm(proposalId, transactionId)
                transactionId
            }
        }.fold(
            onSuccess = { SaveResult.Created(it) },
            onFailure = { SaveResult.Failed(it.message ?: it::class.java.simpleName) },
        )
    }
}

sealed interface SaveResult {
    data class Created(val transactionId: Long) : SaveResult
    data class Failed(val reason: String) : SaveResult
}

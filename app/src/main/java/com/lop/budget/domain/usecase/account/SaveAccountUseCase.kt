package com.lop.budget.domain.usecase.account

import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.repository.AccountRepository
import com.lop.budget.domain.model.AccountType
import com.lop.budget.util.Format
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/** Saisie du formulaire de compte, telle que l'utilisateur l'a faite (LOP-20). */
data class AccountDraft(
    val id: Long = 0,
    val name: String,
    val type: AccountType,
    /** Saisie utilisateur, en euros : la conversion en centimes appartient au use case. */
    val balanceInput: String,
    val colorArgb: Int,
    val iconName: String,
    val bankName: String?,
    val comment: String?,
    val includeInTotal: Boolean,
)

enum class AccountRefusal { BlankName, InvalidBalance, NotFound }

sealed interface AccountSaveResult {
    data class Saved(val accountId: Long, val adjustment: AdjustOutcome) : AccountSaveResult
    data class Refused(val reason: AccountRefusal) : AccountSaveResult
}

/**
 * Création et modification d'un compte (LOP-20, CA-02, CA-03, CA-05).
 *
 * Seul écrivain d'une ligne `accounts` hors archivage et suppression. Refuse **avant toute
 * écriture** un nom vide une fois les espaces de bord retirés et un solde illisible (I-3). Pose la
 * dernière correction de solde à la création, puis seulement quand une sauvegarde crée réellement
 * un ajustement (I-4) : l'utilisateur ne la saisit jamais.
 */
@Singleton
class SaveAccountUseCase @Inject constructor(
    private val accountRepo: AccountRepository,
    private val adjustBalanceUseCase: AdjustBalanceUseCase,
    private val clock: Clock,
) {
    suspend operator fun invoke(draft: AccountDraft): AccountSaveResult {
        val name = draft.name.trim()
        if (name.isEmpty()) return AccountSaveResult.Refused(AccountRefusal.BlankName)
        val balance = Format.centsOrNull(draft.balanceInput)
            ?: return AccountSaveResult.Refused(AccountRefusal.InvalidBalance)
        // Un établissement vide n'est pas un établissement : `null`, comme hors type Bancaire.
        val bankName = draft.bankName?.takeIf { draft.type == AccountType.CHECKING && it.isNotBlank() }
        val comment = draft.comment?.takeIf { it.isNotBlank() }

        if (draft.id == 0L) {
            val id = accountRepo.upsert(
                AccountEntity(
                    name = name,
                    type = draft.type,
                    initialBalance = balance,
                    balanceUpdatedAt = clock.millis(),
                    colorArgb = draft.colorArgb,
                    icon = draft.iconName,
                    bankName = bankName,
                    comment = comment,
                    includeInTotal = draft.includeInTotal,
                )
            )
            return AccountSaveResult.Saved(id, AdjustOutcome.NoChange)
        }

        val adjustment = adjustBalanceUseCase.adjust(draft.id, balance)
        val current = accountRepo.getById(draft.id)
            ?: return AccountSaveResult.Refused(AccountRefusal.NotFound)
        accountRepo.upsert(
            current.copy(
                name = name,
                type = draft.type,
                colorArgb = draft.colorArgb,
                icon = draft.iconName,
                bankName = bankName,
                comment = comment,
                includeInTotal = draft.includeInTotal,
                balanceUpdatedAt =
                    if (adjustment is AdjustOutcome.Created) clock.millis() else current.balanceUpdatedAt,
            )
        )
        return AccountSaveResult.Saved(draft.id, adjustment)
    }
}

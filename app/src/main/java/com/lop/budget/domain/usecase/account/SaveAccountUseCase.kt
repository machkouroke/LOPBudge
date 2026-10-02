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
 * **Extrait à comportement constant** de `AccountFormViewModel.save` : un ViewModel n'est pas un
 * lieu de règles, et CA-03 comme I-4 ne se testent pas sans passer par l'UI tant que l'écriture
 * y vit. Les écarts à l'US sont reconduits tels quels et nommés ci-dessous ; les corriger ici
 * rendrait verts, sans rien prouver, les cas de TC-135 et TC-136 qui doivent les révéler.
 */
@Singleton
class SaveAccountUseCase @Inject constructor(
    private val accountRepo: AccountRepository,
    private val adjustBalanceUseCase: AdjustBalanceUseCase,
    private val clock: Clock,
) {
    suspend operator fun invoke(draft: AccountDraft): AccountSaveResult {
        if (draft.name.isBlank()) return AccountSaveResult.Refused(AccountRefusal.BlankName)

        // ÉCART CA-03 / I-3 : un solde non numérique est ramené à 0 et écrit, au lieu d'un refus
        // InvalidBalance.
        val balance = Format.centsOrNull(draft.balanceInput) ?: 0L
        val bankName = if (draft.type == AccountType.CHECKING) draft.bankName else null
        val comment = draft.comment?.takeIf { it.isNotBlank() }

        if (draft.id == 0L) {
            val id = accountRepo.upsert(
                AccountEntity(
                    // ÉCART CA-02 : le nom est écrit sans suppression des espaces de bord.
                    name = draft.name,
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

        // ÉCART CA-05 : deux écritures hors transaction, et `balanceUpdatedAt` n'est jamais posé,
        // même quand la correction a créé un ajustement (I-4).
        val adjustment = adjustBalanceUseCase.adjust(draft.id, balance)
        val current = accountRepo.getById(draft.id)
            ?: return AccountSaveResult.Refused(AccountRefusal.NotFound)
        accountRepo.upsert(
            current.copy(
                name = draft.name,
                type = draft.type,
                colorArgb = draft.colorArgb,
                icon = draft.iconName,
                bankName = bankName,
                comment = comment,
                includeInTotal = draft.includeInTotal,
            )
        )
        return AccountSaveResult.Saved(draft.id, adjustment)
    }
}

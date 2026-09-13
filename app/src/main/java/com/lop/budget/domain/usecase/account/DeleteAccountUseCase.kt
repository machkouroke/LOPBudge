package com.lop.budget.domain.usecase.account

import com.lop.budget.data.repository.AccountRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Suppression d'un compte (LOP-20, section « Suppression / archivage »).
 *
 * **Unique point d'entrée** de la suppression d'un compte. `AccountRepository.delete` n'est
 * appelé que d'ici : les deux écrans qui l'invoquaient directement — `AccountsManageViewModel`
 * et `AccountFormViewModel` — passent désormais par ce use case. Un ViewModel n'est pas un lieu
 * de règles, et deux appelants directs du repository, c'est deux endroits où la règle se perdra
 * au prochain écran.
 *
 * **Comportement constant, volontairement.** Aujourd'hui ce use case ne fait que déléguer :
 * `AccountDao.delete` est un `DELETE` nu, `TransactionEntity` ne déclare aucune clé étrangère
 * vers `accounts`, et les transactions du compte survivent donc, orphelines — ce que l'interface
 * annonce d'ailleurs à l'utilisateur (« toutes les transactions liées seront orphelines »).
 * L'extraction est un **prérequis de forme** : corriger le comportement dans le même geste
 * masquerait ce que le test de non-régression doit pouvoir observer.
 *
 * Le sort des transactions d'un compte supprimé — suppression logique, archivage forcé ou refus
 * quand le compte est utilisé — appartient à LOP-20 et n'est pas tranché ici.
 */
@Singleton
class DeleteAccountUseCase @Inject constructor(
    private val accountRepo: AccountRepository,
) {
    suspend operator fun invoke(accountId: Long) {
        accountRepo.delete(accountId)
    }
}
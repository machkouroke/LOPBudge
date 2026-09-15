package com.lop.budget.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.lop.budget.R
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.domain.model.NO_ACCOUNT_ID
import com.lop.budget.util.IconMapper

/**
 * Sélecteur de compte — **l'unique** de l'application.
 *
 * Il remplace quatre implémentations qui faisaient le même travail : celle du formulaire de
 * transaction, celle du détail, et deux copies littérales d'une même `AccountList` dans les écrans
 * mensuel et recherche. Ce n'est qu'une configuration de [PickerBottomSheet] : la mécanique de
 * feuille, de coche et de défilement ne vit qu'à un seul endroit.
 *
 * ## La convention, et pourquoi elle tient dans les deux usages
 *
 * `selectedId` distingue **trois** états, et c'est ce qui permet au même composant de servir un
 * formulaire et un filtre :
 *
 * | Valeur          | Formulaire                   | Filtre                                   |
 * |-----------------|------------------------------|------------------------------------------|
 * | identifiant réel| la transaction porte ce compte | ne montrer que ce compte                |
 * | [NO_ACCOUNT_ID] | la transaction n'a pas de compte | ne montrer que les transactions sans compte |
 * | `null`          | *n'arrive pas*               | aucun filtre : tout est rendu             |
 *
 * Un formulaire n'a que deux états — un compte, ou pas de compte — puisque « rien de choisi » y
 * **est** l'absence de compte. Un filtre en a trois. D'où la nuance portée par `null`, et d'où le
 * fait que « Sans compte » ne puisse pas être déduit de « aucune ligne sélectionnée ».
 *
 * Côté données, rien à ajouter : le filtrage du domaine compare `tx.accountId == accountId`, et
 * `NO_ACCOUNT_ID` étant réellement stocké en base, filtrer dessus ramène exactement les transactions
 * sans compte. Aucun cas particulier nulle part.
 *
 * @param selectedId compte retenu, [NO_ACCOUNT_ID] pour « sans compte », `null` pour aucun choix.
 * @param onSelect reçoit toujours une valeur concrète — [NO_ACCOUNT_ID] quand « Sans compte » est
 *   choisi. À l'appelant de la retraduire dans sa propre convention s'il en a une autre.
 */
@Composable
fun AccountBottomSheet(
    title: String,
    accounts: List<AccountEntity>,
    selectedId: Long?,
    onSelect: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    PickerBottomSheet(
        title = title,
        items = accounts,
        isSelected = { it.id == selectedId },
        allowNone = true,
        noneLabel = stringResource(R.string.tx_no_account),
        // Et non « aucune ligne cochée » : dans un filtre, `null` ne coche rien non plus, et ne
        // doit pourtant pas se faire passer pour « Sans compte ».
        isNoneSelected = { selectedId == NO_ACCOUNT_ID },
        onSelect = { account -> onSelect(account?.id ?: NO_ACCOUNT_ID) },
        onDismiss = onDismiss,
        itemLabel = { it.name },
        itemIcon = { IconMapper.get(it.icon) },
        itemTint = { Color(it.colorArgb) },
        emptyText = stringResource(R.string.tx_no_accounts),
    )
}

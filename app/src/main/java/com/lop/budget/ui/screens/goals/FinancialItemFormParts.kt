package com.lop.budget.ui.screens.goals

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import com.lop.budget.R
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.domain.model.FinancialItemKind
import com.lop.budget.domain.model.NO_ACCOUNT_ID
import com.lop.budget.domain.usecase.financial.FinancialItemRefusal
import com.lop.budget.ui.common.TestTags
import com.lop.budget.ui.components.AccountBottomSheet
import com.lop.budget.ui.components.ConfirmDeleteSheet
import com.lop.budget.ui.components.FloatingCard
import com.lop.budget.ui.components.LopTextField
import com.lop.budget.ui.components.SelectorRow
import com.lop.budget.ui.components.TagColorPicker
import com.lop.budget.util.IconMapper

/** Icônes proposées pour un objectif, une dette ou une créance (CA-02 de LOP-202). */
private val FinancialItemIcons = listOf(
    "savings", "payments", "volunteer_activism", "home", "directions_car", "school",
    "flight", "beach_access", "shield", "celebration", "person", "group",
)

/** Icône proposée à l'ouverture, modifiable (CA-02). */
internal fun proposedIcon(kind: FinancialItemKind): String = when (kind) {
    FinancialItemKind.GOAL -> "savings"
    FinancialItemKind.DEBT -> "payments"
    FinancialItemKind.RECEIVABLE -> "volunteer_activism"
}

/** Couleur proposée à l'ouverture, prise dans la palette du sélecteur pour y apparaître cochée. */
internal fun proposedColor(kind: FinancialItemKind): Int = when (kind) {
    FinancialItemKind.GOAL -> 0xFF43A047
    FinancialItemKind.DEBT -> 0xFFE53935
    FinancialItemKind.RECEIVABLE -> 0xFF1E88E5
}.toInt()

/** Message du premier refus de [candidates] présent dans [refusals], à placer sous son champ. */
@Composable
internal fun refusalMessage(
    refusals: Set<FinancialItemRefusal>,
    vararg candidates: FinancialItemRefusal,
): String? = candidates.firstOrNull { it in refusals }?.let {
    stringResource(
        when (it) {
            FinancialItemRefusal.BlankName -> R.string.financial_error_name
            FinancialItemRefusal.InvalidTarget -> R.string.financial_error_target
            FinancialItemRefusal.InvalidStarting -> R.string.financial_error_starting
            FinancialItemRefusal.StartingAboveTarget -> R.string.financial_error_starting_above
            FinancialItemRefusal.InvalidRate -> R.string.financial_error_rate
            FinancialItemRefusal.AccountUnavailable -> R.string.financial_error_account
        }
    )
}

/**
 * Bouton Enregistrer (CA-05, CA-08) : indique l'enregistrement en cours et n'accepte alors aucun
 * nouvel appui ; un échec s'affiche dessous, le bouton redevient actif pour réessayer.
 */
@Composable
internal fun SaveButton(
    isSaving: Boolean,
    saveFailed: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    tagPrefix: String,
) {
    Column(Modifier.fillMaxWidth().padding(20.dp)) {
        Button(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().height(56.dp).testTag(TestTags.BTN_SAVE),
            shape = MaterialTheme.shapes.medium,
            enabled = enabled && !isSaving,
        ) {
            if (isSaving) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.financial_saving))
            } else {
                Text(stringResource(R.string.save))
            }
        }
        if (saveFailed) {
            Text(
                stringResource(R.string.financial_save_failed),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp).testTag("$tagPrefix.save.error"),
            )
        }
    }
}

/**
 * Sortie d'un brouillon (CA-06) : sans modification on quitte directement, sinon une feuille
 * propose Quitter sans enregistrer ou Continuer la saisie. Rend l'action à brancher sur la flèche
 * retour ; le geste retour du système est intercepté ici.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun rememberDraftExit(hasUnsavedChanges: Boolean, onBack: () -> Unit): () -> Unit {
    var confirming by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = hasUnsavedChanges) { confirming = true }
    if (confirming) {
        ConfirmDeleteSheet(
            title = stringResource(R.string.draft_leave_title),
            message = stringResource(R.string.draft_leave_message),
            confirmLabel = stringResource(R.string.draft_leave_quit),
            dismissLabel = stringResource(R.string.draft_leave_stay),
            confirmIcon = null,
            confirmTag = "form.leave.quit",
            dismissTag = "form.leave.stay",
            onConfirm = { confirming = false; onBack() },
            onDismiss = { confirming = false },
            // Feuille modale = racine de composition à part : ses tags ne sont pas hérités.
            modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag("form.leave"),
        )
    }
    return { if (hasUnsavedChanges) confirming = true else onBack() }
}

/** Compte associé, apparence et commentaire (CA-02) : proposés à la création seulement. */
internal fun LazyListScope.creationExtraFields(
    tagPrefix: String,
    accounts: List<AccountEntity>,
    accountId: Long?,
    refusals: Set<FinancialItemRefusal>,
    onAccount: (Long?) -> Unit,
    icon: String,
    onIcon: (String) -> Unit,
    colorArgb: Int,
    onColor: (Int) -> Unit,
    comment: String,
    onComment: (String) -> Unit,
) {
    item {
        var picking by rememberSaveable { mutableStateOf(false) }
        val label = stringResource(R.string.financial_account_label)
        if (picking) {
            AccountBottomSheet(
                title = label,
                accounts = accounts,
                selectedId = accountId ?: NO_ACCOUNT_ID,
                onSelect = { id -> onAccount(id.takeIf { it != NO_ACCOUNT_ID }); picking = false },
                onDismiss = { picking = false },
            )
        }
        SelectorRow(
            label = label,
            value = accounts.firstOrNull { it.id == accountId }?.name ?: stringResource(R.string.tx_no_account),
            icon = Icons.Default.AccountBalanceWallet,
            errorMessage = refusalMessage(refusals, FinancialItemRefusal.AccountUnavailable),
            onClick = { picking = true },
            modifier = Modifier.testTag("$tagPrefix.account"),
        )
    }
    item {
        FloatingCard {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.financial_appearance_label), style = MaterialTheme.typography.titleMedium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(FinancialItemIcons) { name ->
                        val selected = name == icon
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    MaterialTheme.shapes.small,
                                )
                                .clickable { onIcon(name) }
                                .testTag("$tagPrefix.icon.$name"),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                IconMapper.get(name) as ImageVector,
                                contentDescription = null,
                                tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                TagColorPicker(selected = Color(colorArgb), onSelect = { onColor(it.toArgb()) })
            }
        }
    }
    item {
        FloatingCard {
            LopTextField(
                label = stringResource(R.string.financial_comment_label),
                value = comment,
                onValueChange = onComment,
                minLines = 2,
                modifier = Modifier.testTag("$tagPrefix.comment"),
            )
        }
    }
}

package com.lop.budget.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lop.budget.R
import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.domain.usecase.account.AccountRowAction
import com.lop.budget.ui.common.TransactionActionViewModel
import com.lop.budget.util.Format

/**
 * Ligne d'une occurrence de série, dans l'aperçu du détail et la liste du calendrier (LOP-7, P-6).
 *
 * C'est la ligne de transaction de l'accueil, en **lecture seule** : ouverture uniquement, ni
 * glissement payer/supprimer ni aperçu rapide (I-1). La ligne ajoutée écrit la date complète, le
 * type et le statut en toutes lettres (CA-01, CA-08) : l'opacité d'une ligne payée ne suffit pas, et
 * une échéance lointaine a besoin de son année.
 */
@Composable
fun OccurrenceRow(
    occurrence: TransactionWithRelations,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** CA-02 : la liste du calendrier nomme aussi la catégorie. */
    showCategory: Boolean = false,
    actionVm: TransactionActionViewModel = hiltViewModel(LocalContext.current as ComponentActivity),
) {
    val currency by actionVm.previewCurrency.collectAsStateWithLifecycle()
    val tx = occurrence.transaction
    val category = occurrence.category?.name ?: stringResource(R.string.tx_detail_no_category)
    // La date a sa propre ligne : collée au reste, elle se coupait au milieu d'un séparateur.
    val summary = Format.fullDate(tx.date) + "\n" + listOfNotNull(
        stringResource(if (tx.type == TransactionType.INCOME) R.string.tx_type_income else R.string.tx_type_expense),
        stringResource(
            if (tx.status == TransactionStatus.PAID) R.string.occurrence_status_paid
            else R.string.occurrence_status_planned
        ),
        category.takeIf { showCategory },
    ).joinToString(" · ")

    TransactionRow(
        tx = occurrence,
        currency = currency,
        onOpenTransaction = { onClick() },
        modifier = modifier,
        allowedActions = setOf(AccountRowAction.OPEN),
        supportingText = summary,
        actionVm = actionVm,
    )
}

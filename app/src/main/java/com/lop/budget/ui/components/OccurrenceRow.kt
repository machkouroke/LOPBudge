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
import com.lop.budget.ui.common.TransactionActionViewModel
import com.lop.budget.util.Format

/**
 * Ligne d'une occurrence de série, dans l'aperçu du détail et la liste du calendrier (LOP-7).
 *
 * C'est la ligne de transaction de l'accueil, actions comprises : glissement payer/supprimer et
 * aperçu rapide (P-10). Elle n'y ajoute que la date complète, car une échéance lointaine a besoin
 * de son année. Le type se lit au montant, le statut à l'opacité, la catégorie à l'icône (P-9) ;
 * type et statut restent annoncés au lecteur d'écran (CA-08).
 */
@Composable
fun OccurrenceRow(
    occurrence: TransactionWithRelations,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    actionVm: TransactionActionViewModel = hiltViewModel(LocalContext.current as ComponentActivity),
) {
    val currency by actionVm.previewCurrency.collectAsStateWithLifecycle()
    val tx = occurrence.transaction
    val announced = listOf(
        stringResource(if (tx.type == TransactionType.INCOME) R.string.tx_type_income else R.string.tx_type_expense),
        stringResource(
            if (tx.status == TransactionStatus.PAID) R.string.occurrence_status_paid
            else R.string.occurrence_status_planned
        ),
    ).joinToString(", ")

    TransactionRow(
        tx = occurrence,
        currency = currency,
        onOpenTransaction = { onClick() },
        modifier = modifier,
        supportingText = Format.fullDate(tx.date),
        stateDescription = announced,
        actionVm = actionVm,
    )
}

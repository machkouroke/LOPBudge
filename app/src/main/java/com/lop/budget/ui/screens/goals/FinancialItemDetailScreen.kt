package com.lop.budget.ui.screens.goals

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lop.budget.R
import com.lop.budget.domain.model.FinancialItemKind
import com.lop.budget.domain.model.ItemStatus
import com.lop.budget.domain.usecase.account.AccountRowAction
import com.lop.budget.domain.usecase.financial.FinancialItemDetail
import com.lop.budget.ui.common.TestTags
import com.lop.budget.ui.components.CircleIcon
import com.lop.budget.ui.components.FloatingCard
import com.lop.budget.ui.components.LopScreenScaffold
import com.lop.budget.ui.components.TransactionRow
import com.lop.budget.ui.components.typeAndStatusAnnouncement
import com.lop.budget.util.Format
import com.lop.budget.util.IconMapper
import java.text.NumberFormat
import java.util.Locale

/**
 * Détail d'un objectif, d'une dette ou d'une créance (LOP-203). Consultation seule : aucun champ
 * n'est éditable (CA-03) ; « Modifier » ouvre le formulaire existant (P-4).
 */
@Composable
fun FinancialItemDetailScreen(
    onBack: () -> Unit,
    onEdit: (FinancialItemKind, Long) -> Unit,
    onOpenTransaction: (Long) -> Unit,
    vm: FinancialItemDetailViewModel = hiltViewModel(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val loaded = state as? FinancialItemDetailUiState.Loaded

    LopScreenScaffold(
        // Jamais le nom d'un élément qui n'est plus affiché (CA-02).
        title = loaded?.detail?.name
            ?: stringResource(vm.kind?.let(::kindLabel) ?: R.string.financial_detail_not_found),
        onBack = onBack,
        navigationIcon = Icons.AutoMirrored.Filled.ArrowBack,
        modifier = Modifier.testTag(TestTags.SCREEN_FINANCIAL_DETAIL),
    ) {
        when (val s = state) {
            FinancialItemDetailUiState.Loading -> item {
                Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.testTag(TestTags.FINANCIAL_DETAIL_LOADING))
                }
            }

            FinancialItemDetailUiState.Error -> item {
                Column(
                    Modifier.fillMaxWidth().testTag(TestTags.FINANCIAL_DETAIL_ERROR),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(stringResource(R.string.financial_detail_load_error), color = MaterialTheme.colorScheme.error)
                    TextButton(
                        onClick = vm::retry,
                        modifier = Modifier.heightIn(min = 48.dp).testTag(TestTags.FINANCIAL_DETAIL_RETRY),
                    ) { Text(stringResource(R.string.financial_detail_retry)) }
                }
            }

            FinancialItemDetailUiState.NotFound -> item {
                Text(
                    stringResource(R.string.financial_detail_not_found),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(48.dp).testTag(TestTags.FINANCIAL_DETAIL_NOT_FOUND),
                )
            }

            is FinancialItemDetailUiState.Loaded -> detailContent(s.detail, s.currency, onEdit, onOpenTransaction)
        }
    }
}

private fun LazyListScope.detailContent(
    d: FinancialItemDetail,
    currency: String,
    onEdit: (FinancialItemKind, Long) -> Unit,
    onOpenTransaction: (Long) -> Unit,
) {
    val color = Color(d.colorArgb)

    item {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            CircleIcon(IconMapper.get(d.icon), color, color.copy(alpha = 0.15f), size = 64.dp)
            Spacer(Modifier.height(12.dp))
            Text(
                d.name,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.testTag(field("name")),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(kindLabel(d.kind)),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(field("kind")),
                )
                Text("•", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    stringResource(statusLabel(d.kind, d.status)),
                    color = color,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.testTag(field("status")),
                )
            }
        }
    }

    item {
        Button(
            onClick = { onEdit(d.kind, d.id) },
            modifier = Modifier.fillMaxWidth().testTag(TestTags.FINANCIAL_DETAIL_EDIT),
            shape = MaterialTheme.shapes.medium,
        ) {
            Icon(Icons.Default.Edit, null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.edit))
        }
    }

    item {
        FloatingCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                InfoRow(stringResource(progressLabel(d.kind)), Format.money(d.progress.reachedCents, currency), "progress")
                InfoRow(
                    stringResource(R.string.financial_remaining),
                    d.progress.remainingCents?.let { Format.money(it, currency) } ?: "—",
                    "remaining",
                )
                InfoRow(stringResource(R.string.financial_percent), percentLabel(d.progress.percent), "percent")
                ProgressBar((d.progress.percent ?: 0) / 100f, color)
            }
        }
    }

    item {
        FloatingCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                InfoRow(
                    stringResource(if (d.kind == FinancialItemKind.GOAL) R.string.goal_target_amount_label else R.string.debt_total_amount_label),
                    Format.money(d.targetCents, currency),
                    "target",
                )
                InfoRow(stringResource(R.string.starting_balance_label), Format.money(d.startingCents, currency), "starting")
                d.counterparty?.let {
                    InfoRow(
                        stringResource(if (d.kind == FinancialItemKind.DEBT) R.string.financial_counterparty_debt else R.string.financial_counterparty_receivable),
                        it,
                        "counterparty",
                    )
                }
                d.debtType?.let { InfoRow(stringResource(R.string.debt_type_label), stringResource(it.labelRes), "debttype") }
                d.interestRate?.let {
                    InfoRow(stringResource(R.string.financial_rate_label), NumberFormat.getNumberInstance(Locale.FRANCE).format(it) + " %", "rate")
                }
                d.dueDate?.let { InfoRow(stringResource(R.string.due_date_label), Format.fullDate(it), "due") }
                if (d.accountId != null) {
                    InfoRow(
                        stringResource(R.string.financial_account_label),
                        d.accountName ?: stringResource(R.string.financial_account_unavailable),
                        "account",
                    )
                }
                d.comment?.let { InfoRow(stringResource(R.string.financial_comment_label), it, "comment") }
            }
        }
    }

    item {
        Text(
            stringResource(R.string.financial_linked_transactions),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 8.dp),
        )
    }

    if (d.transactions.isEmpty()) {
        item {
            Text(
                stringResource(R.string.financial_no_linked_transactions),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(TestTags.FINANCIAL_DETAIL_TX_EMPTY),
            )
        }
    } else {
        items(d.transactions, key = { it.transaction.id }) { row ->
            // P-5 : convention LOP-7 — date complète écrite, type au montant, statut à l'opacité,
            // type et statut annoncés. Lecture seule : tap vers le détail, ni glissement ni aperçu.
            TransactionRow(
                tx = row,
                currency = currency,
                onOpenTransaction = onOpenTransaction,
                modifier = Modifier.testTag("${TestTags.FINANCIAL_DETAIL_TX_ROW}_${row.transaction.id}"),
                allowedActions = setOf(AccountRowAction.OPEN),
                supportingText = Format.fullDate(row.transaction.date),
                stateDescription = typeAndStatusAnnouncement(row.transaction),
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, key: String) {
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.testTag(field(key)),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Text(value, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End)
    }
}

private fun field(key: String) = "${TestTags.FINANCIAL_DETAIL_FIELD}_$key"

@StringRes
private fun kindLabel(kind: FinancialItemKind) = when (kind) {
    FinancialItemKind.GOAL -> R.string.financial_kind_goal
    FinancialItemKind.DEBT -> R.string.financial_kind_debt
    FinancialItemKind.RECEIVABLE -> R.string.financial_kind_receivable
}

/** P-6 : libellés accordés au type ; terminé se lit Atteint, Remboursée ou Récupérée. */
@StringRes
internal fun statusLabel(kind: FinancialItemKind, status: ItemStatus): Int {
    val goal = kind == FinancialItemKind.GOAL
    return when (status) {
        ItemStatus.ACTIVE -> if (goal) R.string.financial_status_active_goal else R.string.financial_status_active_loan
        ItemStatus.ARCHIVED -> if (goal) R.string.financial_status_archived_goal else R.string.financial_status_archived_loan
        ItemStatus.COMPLETED -> when (kind) {
            FinancialItemKind.GOAL -> R.string.financial_status_reached
            FinancialItemKind.DEBT -> R.string.financial_status_repaid
            FinancialItemKind.RECEIVABLE -> R.string.financial_status_recovered
        }
    }
}

@StringRes
internal fun progressLabel(kind: FinancialItemKind) = when (kind) {
    FinancialItemKind.GOAL -> R.string.financial_progress_goal
    FinancialItemKind.DEBT -> R.string.financial_progress_debt
    FinancialItemKind.RECEIVABLE -> R.string.financial_progress_receivable
}

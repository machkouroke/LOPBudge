package com.lop.budget.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.lop.budget.R
import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.domain.model.DayGroup
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.domain.usecase.account.AccountRowAction
import com.lop.budget.ui.common.TestTags
import com.lop.budget.ui.common.TransactionActionViewModel
import com.lop.budget.ui.theme.LopTheme
import com.lop.budget.util.Format
import com.lop.budget.util.IconMapper
import java.time.format.TextStyle
import java.util.Locale

/**
 * Extension pour LazyListScope permettant d'afficher des groupes de transactions par jour.
 */
fun LazyListScope.transactionDayGroups(
    dayGroups: List<DayGroup>,
    currency: String,
    txVersions: Map<Long, Int>,
    onOpenTransaction: (Long) -> Unit
) {
    dayGroups.forEach { day ->
        item(key = "day_header_${day.date}", contentType = "day_header") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${day.date.dayOfMonth} ${day.date.month.getDisplayName(TextStyle.SHORT, Locale.FRANCE)}",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = Format.money(day.total, currency),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        items(
            items = day.transactions,
            key = { tx ->
                val id = tx.transaction.id
                if (id < 0L) {
                    "v_${tx.transaction.seriesId}_${tx.transaction.seriesDate}"
                } else {
                    if (txVersions.isEmpty()) id else "${id}_${txVersions[id] ?: 0}"
                }
            },
            contentType = { "transaction" }
        ) { tx ->
            Box(modifier = Modifier.animateItem().testTag(TestTags.TRANSACTION_ITEM)) {
                TransactionRow(
                    tx = tx,
                    currency = currency,
                    onOpenTransaction = onOpenTransaction
                )
            }
        }
    }
}

/**
 * Rend une ligne de transaction.
 *
 * Le composant **ne décide de rien** : il reçoit les actions autorisées et le marqueur visuel,
 * et se contente de les appliquer (LOP-87, I-12, CA-27). Les valeurs par défaut reconduisent le
 * comportement des surfaces où un ajustement n'apparaît jamais (I-2) : liste du mois, recherche,
 * accueil, groupes de série.
 *
 * @param isAdjustment marqueur **d'affichage** seul — icône, teinte, graisse. Décidé par le
 *   domaine, jamais déduit ici du type technique.
 * @param allowedActions actions que le domaine autorise sur cette ligne. Chaque geste et chaque
 *   rappel est conditionné à cet ensemble, et à rien d'autre.
 */
@Composable
fun TransactionRow(
    tx: TransactionWithRelations,
    currency: String,
    onOpenTransaction: (Long) -> Unit,
    modifier: Modifier = Modifier,
    showDate: Boolean = false,
    isAdjustment: Boolean = false,
    allowedActions: Set<AccountRowAction> = AccountRowAction.entries.toSet(),
    actionVm: TransactionActionViewModel = hiltViewModel(LocalContext.current as ComponentActivity),
) {
    val ext = LopTheme.extended
    val isIncome = tx.transaction.type == TransactionType.INCOME
    val amountColor = if (isIncome) ext.income else ext.expense
    val catColor = tx.category?.colorArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.primary
    val isPaid = tx.transaction.status == TransactionStatus.PAID

    val canOpen = AccountRowAction.OPEN in allowedActions
    val canTogglePaid = AccountRowAction.TOGGLE_PAID in allowedActions
    val canDelete = AccountRowAction.DELETE in allowedActions

    SwipeableTransactionRow(
        isPaid = isPaid,
        // Le geste reste actif tant qu'au moins une de ses deux actions est autorisée : sur un
        // ajustement, le glissement de suppression doit rester joignable (CA-21b, I-4b).
        enabled = canTogglePaid || canDelete,
        onTogglePaid = { if (canTogglePaid) actionVm.togglePaid(tx) },
        onDelete = {
            if (canDelete) {
                actionVm.requestDelete(tx)
            }
        },
        modifier = modifier
    ) {
        FloatingCard(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickableHaptic(
                    onClick = {
                        if (canOpen) {
                            onOpenTransaction(tx.transaction.id)
                        }
                    },
                    onLongClick = { if (canOpen) actionVm.showPreview(tx) }
                )
                .graphicsLayer {
                    alpha = if (isPaid && !isAdjustment) 0.5f else 1f
                },
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            contentPadding = PaddingValues(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircleIcon(
                    icon = IconMapper.get(if (isAdjustment) "sync" else (tx.category?.icon ?: "category")),
                    tint = if (isAdjustment) MaterialTheme.colorScheme.secondary else catColor,
                    background = if (isAdjustment) MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f) else catColor.copy(alpha = 0.18f),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = tx.transaction.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = if (isAdjustment) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.testTag(TestTags.TRANSACTION_ITEM_TITLE)
                        )
                        if (tx.transaction.seriesId != null) {
                            Spacer(Modifier.width(6.dp))
                            Icon(
                                Icons.Filled.Repeat,
                                stringResource(R.string.home_recurring_tag),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }
                    // Une transaction peut n'être rattachée à aucun compte (NO_ACCOUNT_ID) : la
                    // jointure rend alors `null`, et le libellé de repli le dit au lieu de laisser
                    // un blanc à la place du compte.
                    val accountLabel = tx.account?.name ?: stringResource(R.string.tx_no_account)
                    Text(
                        if (showDate) Format.dayMonth(tx.transaction.date) + " • " + accountLabel
                        else accountLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    if (tx.tags.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            tx.tags.forEach { tag ->
                                PillTag(
                                    text = tag.name,
                                    color = Color(tag.colorArgb).copy(alpha = 0.8f),
                                )
                            }
                        }
                    }
                }
                Text(
                    (if (isIncome) "+" else "−") + Format.money(tx.transaction.amount, currency),
                    style = MaterialTheme.typography.titleMedium,
                    color = amountColor,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.testTag(TestTags.TRANSACTION_ITEM_AMOUNT)
                )
            }
        }
    }
}

package com.lop.budget.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lop.budget.R
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.domain.usecase.insight.RemainingToSpend
import com.lop.budget.domain.usecase.insight.UpcomingMovement
import com.lop.budget.ui.common.TestTags
import com.lop.budget.ui.components.FloatingCard
import com.lop.budget.ui.components.LopBottomSheet
import com.lop.budget.ui.components.SUMMARY_PLACEHOLDER
import com.lop.budget.ui.components.clickableNoRipple
import com.lop.budget.ui.theme.LopTheme
import com.lop.budget.util.Format

/**
 * Carte « Reste à dépenser » de l'accueil (LOP-239), sous « Ce mois ». Touchable seulement quand
 * le calcul est prêt : elle ouvre alors la liste des mouvements à venir (CA-06).
 */
@Composable
fun RemainingToSpendCard(
    state: RemainingCardState,
    onOpen: () -> Unit,
    onRetry: () -> Unit,
    onAddIncome: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ready = state is RemainingCardState.Ready
    FloatingCard(
        modifier = modifier
            .fillMaxWidth()
            .then(if (ready) Modifier.clickableNoRipple(onOpen) else Modifier)
            .testTag(TestTags.HOME_REMAINING_CARD),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Savings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.home_remaining_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (state is RemainingCardState.Ready) {
                    // Pastille de la paie, comme une étiquette : la date borne tout ce que dit la carte.
                    Text(
                        stringResource(R.string.home_remaining_until, Format.dayMonth(state.data.payday)),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .testTag(TestTags.HOME_REMAINING_UNTIL)
                            .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            when (state) {
                RemainingCardState.Loading -> Text(
                    SUMMARY_PLACEHOLDER,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(TestTags.HOME_REMAINING_AMOUNT),
                )
                RemainingCardState.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.home_remaining_error),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f).testTag(TestTags.HOME_REMAINING_ERROR),
                    )
                    TextButton(onClick = onRetry, modifier = Modifier.testTag(TestTags.HOME_REMAINING_RETRY)) {
                        Text(stringResource(R.string.home_remaining_retry))
                    }
                }
                RemainingCardState.NoPayday -> {
                    Text(
                        stringResource(R.string.home_remaining_no_payday),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = onAddIncome, modifier = Modifier.testTag(TestTags.HOME_REMAINING_ADD_INCOME)) {
                        Text(stringResource(R.string.home_remaining_add_income))
                    }
                }
                is RemainingCardState.Ready -> ReadyFigures(state.data, state.currency)
            }
        }
    }
}

@Composable
private fun ReadyFigures(data: RemainingToSpend, currency: String) {
    val ext = LopTheme.extended
    if (data.perDay == null) {
        // CA-04 : dépassement, sans montant par jour. Le libellé porte le sens, pas la couleur seule.
        Text(
            stringResource(R.string.home_remaining_overrun, balance(data.remaining, currency)),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = ext.expense,
            modifier = Modifier.testTag(TestTags.HOME_REMAINING_AMOUNT),
        )
    } else {
        // Montant et rythme sur une même ligne de base : le « par jour » se lit comme la suite du reste.
        Row {
            Text(
                Format.money(data.remaining, currency),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                modifier = Modifier.alignByBaseline().testTag(TestTags.HOME_REMAINING_AMOUNT),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(R.string.home_remaining_per_day, Format.money(data.perDay, currency)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.alignByBaseline().testTag(TestTags.HOME_REMAINING_PER_DAY),
            )
        }
    }
    data.lowPoint?.let { low ->
        // Tuile teintée de la couleur des dépenses (DESIGN.md § 5.1) ; texte en `onSurface` pour le contraste.
        FloatingCard(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 16.dp,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            tint = ext.expense,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.AutoMirrored.Filled.TrendingDown,
                    contentDescription = null,
                    tint = ext.expense,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.home_remaining_low_point, balance(low.amount, currency), Format.dayMonth(low.date)),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag(TestTags.HOME_REMAINING_LOW_POINT),
                )
            }
        }
    }
}

/**
 * Mouvements à venir de l'horizon, par date croissante (CA-06) : la liste part du solde total et
 * chaque ligne porte le solde projeté après elle, la dernière tombe sur le reste à dépenser.
 */
@Composable
fun UpcomingMovementsSheet(data: RemainingToSpend, currency: String, onDismiss: () -> Unit) {
    LopBottomSheet(
        onDismiss = onDismiss,
        modifier = Modifier.testTag(TestTags.HOME_UPCOMING_SHEET),
        maxHeightFraction = 0.85f,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
            Text(stringResource(R.string.home_upcoming_title), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.home_remaining_until, Format.dayMonth(data.payday)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // ponytail: Column et non LazyColumn — l'horizon tient en un mois, toutes les lignes sont
            // composées. Passer en liste paresseuse si une série quotidienne longue rend la feuille lente.
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(top = 12.dp),
            ) {
                MovementLine(
                    title = stringResource(R.string.home_upcoming_start),
                    date = null,
                    amount = null,
                    amountColor = Color.Unspecified,
                    balanceAfter = balance(data.totalBalance, currency),
                    tag = TestTags.HOME_UPCOMING_START,
                )
                data.movements.forEach { movement -> UpcomingRow(movement, currency) }
                if (data.movements.isEmpty()) {
                    Text(
                        stringResource(R.string.home_upcoming_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun UpcomingRow(movement: UpcomingMovement, currency: String) {
    val tx = movement.transaction.transaction
    val income = tx.type == TransactionType.INCOME
    MovementLine(
        title = tx.title,
        date = Format.dayMonth(tx.date),
        amount = Format.signedMoney(if (income) tx.amount else -tx.amount, currency),
        amountColor = if (income) LopTheme.extended.income else LopTheme.extended.expense,
        balanceAfter = balance(movement.balanceAfter, currency),
        tag = TestTags.HOME_UPCOMING_ROW_PREFIX + tx.id,
    )
}

/** Une ligne : date et libellé à gauche, montant signé et solde après à droite. */
@Composable
private fun MovementLine(
    title: String,
    date: String?,
    amount: String?,
    amountColor: Color,
    balanceAfter: String,
    tag: String,
) {
    Column(Modifier.testTag(tag).semantics(mergeDescendants = true) {}) {
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (date != null) {
                    Text(
                        date,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                if (amount != null) {
                    Text(amount, style = MaterialTheme.typography.titleSmall, color = amountColor, textAlign = TextAlign.End)
                }
                Text(
                    balanceAfter,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** Solde : « 1 226,40 € », ou « −220,00 € » avec le même signe moins que les lignes. */
private fun balance(cents: Long, currency: String): String =
    if (cents < 0) Format.signedMoney(cents, currency) else Format.money(cents, currency)

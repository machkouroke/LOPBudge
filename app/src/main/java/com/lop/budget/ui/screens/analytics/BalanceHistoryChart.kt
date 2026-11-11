package com.lop.budget.ui.screens.analytics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lop.budget.R
import com.lop.budget.domain.usecase.insight.BalanceHistory
import com.lop.budget.ui.common.TestTags
import com.lop.budget.ui.components.FloatingCard
import com.lop.budget.ui.theme.LopTheme
import com.lop.budget.util.Format
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs

/**
 * Carte de LOP-242 : « Moyenne 574,66 €/mois · 21 % », puis une barre par mois couvert de la
 * fenêtre (CA-01) ; « — » à la place du taux sans revenu, « Aucune transaction sur ces 12 mois »
 * sans mois couvert (CA-05) ; un échec remplace le contenu (CA-07). Toucher un mois couvert le
 * passe à [onPick] (CA-06).
 */
@Composable
internal fun BalanceHistoryCard(
    history: BalanceHistory?,
    failed: Boolean,
    currency: String,
    onPick: (YearMonth) -> Unit,
    onRetry: () -> Unit,
) {
    FloatingCard(Modifier.fillMaxWidth().testTag(TestTags.ANALYTICS_BALANCES)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.analytics_balances_title), style = MaterialTheme.typography.titleMedium)
            val average = history?.average
            when {
                failed || history == null -> CardError(
                    stringResource(R.string.analytics_balances_error), TestTags.ANALYTICS_BALANCES_ERROR,
                    TestTags.ANALYTICS_BALANCES_RETRY, onRetry,
                )
                average == null -> HabitsMessage(stringResource(R.string.analytics_balances_none), TestTags.ANALYTICS_BALANCES_NONE)
                else -> {
                    val rate = history.rate
                    Text(
                        stringResource(
                            R.string.analytics_balances_average,
                            // CA-01 : sans « + » ; sous zéro, le « − » des soldes de l'app.
                            if (average < 0) Format.signedMoney(average, currency) else Format.money(average, currency),
                            if (rate == null) stringResource(R.string.analytics_balances_no_rate)
                            // Même « − » que les montants, pas le tiret de `%d`.
                            else stringResource(R.string.analytics_balances_rate, if (rate < 0) "−${-rate}" else "$rate"),
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.testTag(TestTags.ANALYTICS_BALANCES_AVERAGE),
                    )
                    BalanceBars(history, average, currency, onPick)
                }
            }
        }
    }
}

/**
 * CA-04 : verte au-dessus du zéro, rouge en dessous, un trait sur le zéro pour un solde nul ; la
 * moyenne en pointillé, sur une échelle qui contient le zéro. Un emplacement vide n'a ni barre, ni
 * trait, ni clic, ni annonce (CA-02, CA-06).
 */
@Composable
private fun BalanceBars(history: BalanceHistory, average: Long, currency: String, onPick: (YearMonth) -> Unit) {
    val income = LopTheme.extended.income
    val expense = LopTheme.extended.expense
    val axis = MaterialTheme.colorScheme.outlineVariant
    val mark = MaterialTheme.colorScheme.onSurfaceVariant
    val values = history.covered.values.map { it.balance } + average
    val top = maxOf(0L, values.max())
    val span = (top - minOf(0L, values.min())).coerceAtLeast(1L)

    Box(Modifier.fillMaxWidth().height(140.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val slot = size.width / history.months.size
            val barWidth = slot * 0.6f
            fun y(v: Long) = (top - v).toFloat() / span * size.height
            val zero = y(0L)
            drawLine(axis, Offset(0f, zero), Offset(size.width, zero), 1.dp.toPx())
            history.months.forEachIndexed { i, ym ->
                val balance = history.covered[ym]?.balance ?: return@forEachIndexed
                val left = i * slot + (slot - barWidth) / 2
                if (balance == 0L) {
                    drawLine(mark, Offset(left, zero), Offset(left + barWidth, zero), 2.dp.toPx(), cap = StrokeCap.Round)
                } else {
                    // 2 dp au moins : un solde de quelques centimes ne passe pas pour un emplacement vide.
                    val h = maxOf(abs(y(balance) - zero), 2.dp.toPx())
                    drawRoundRect(
                        color = if (balance > 0) income else expense,
                        topLeft = Offset(left, if (balance > 0) zero - h else zero),
                        size = Size(barWidth, h),
                        cornerRadius = CornerRadius(3.dp.toPx()),
                    )
                }
            }
            val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
            drawLine(mark, Offset(0f, y(average)), Offset(size.width, y(average)), 1.5.dp.toPx(), pathEffect = dash)
        }
        // Un seul toucher pour la rangée, résolu sur l'emplacement exact : un `clickable` par barre
        // étendrait sa cible à 48 dp, sur l'emplacement vide voisin (CA-06).
        val pick by rememberUpdatedState(onPick)
        Row(
            Modifier
                .matchParentSize()
                .pointerInput(history) {
                    detectTapGestures { at ->
                        val ym = history.months[(at.x / size.width * history.months.size).toInt().coerceIn(history.months.indices)]
                        if (ym in history.covered) pick(ym)
                    }
                },
        ) {
            history.months.forEach { ym ->
                val month = history.covered[ym]
                val description = month?.let {
                    stringResource(
                        R.string.analytics_balances_bar,
                        Format.monthYear(ym), Format.signedMoney(it.balance, currency), Format.money(it.income, currency),
                    )
                }
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .testTag("${TestTags.ANALYTICS_BALANCES_BAR}.$ym")
                        .then(
                            if (description == null) Modifier
                            else Modifier.semantics {
                                contentDescription = description
                                role = Role.Button
                                onClick { pick(ym); true }
                            },
                        ),
                )
            }
        }
    }
    // Initiale de chaque mois ; le lecteur d'écran lit déjà le mois de chaque barre.
    Row(Modifier.fillMaxWidth().clearAndSetSemantics {}) {
        history.months.forEach { ym ->
            Text(
                ym.month.getDisplayName(TextStyle.NARROW, Locale.FRANCE),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

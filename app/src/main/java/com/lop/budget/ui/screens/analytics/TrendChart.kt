package com.lop.budget.ui.screens.analytics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lop.budget.R
import com.lop.budget.domain.usecase.insight.AnalyticsTrend
import com.lop.budget.domain.usecase.insight.TrendPoint
import com.lop.budget.ui.common.TestTags
import com.lop.budget.ui.components.FloatingCard
import com.lop.budget.ui.theme.LopTheme
import com.lop.budget.util.Format
import java.time.YearMonth
import kotlin.math.roundToInt

/**
 * Bloc « Tendance » (LOP-235) : entrées et sorties de la période, un point par jour en mode Mois,
 * par mois en mode Année, chacun limité à ses transactions (P-4).
 */
@Composable
internal fun TrendSection(
    trend: AnalyticsTrend,
    failed: Boolean,
    mode: AnalysisMode,
    currency: String,
    onRetry: () -> Unit,
) {
    val chart = !failed && !trend.empty
    FloatingCard(modifier = Modifier.fillMaxWidth().testTag(TestTags.ANALYTICS_TREND)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // P-8 : légende à droite du titre, sur la même ligne.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.analytics_trend_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (chart) {
                    LegendItem(LopTheme.extended.income, stringResource(R.string.analytics_trend_income), TestTags.ANALYTICS_TREND_LEGEND_INCOME)
                    LegendItem(LopTheme.extended.expense, stringResource(R.string.analytics_trend_expense), TestTags.ANALYTICS_TREND_LEGEND_EXPENSE)
                }
            }
            when {
                // CA-04 : ni courbe ni donnée fictive à la place d'une lecture échouée ou vide.
                failed -> TrendError(onRetry)
                trend.empty -> Text(
                    stringResource(R.string.analytics_trend_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(TestTags.ANALYTICS_TREND_EMPTY),
                )
                else -> TrendChart(trend.points, mode, currency)
            }
        }
    }
}

@Composable
private fun TrendError(onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag(TestTags.ANALYTICS_TREND_ERROR),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
        Text(
            stringResource(R.string.analytics_trend_error),
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onRetry, modifier = Modifier.testTag(TestTags.ANALYTICS_TREND_RETRY)) {
            Text(stringResource(R.string.analysis_retry))
        }
    }
}

/**
 * CA-03 : deux courbes sur une échelle commune graduée en pas ronds, base à zéro (P-8), sinon
 * 50 € de sorties pourraient dominer 80 € d'entrées. Toucher ou glisser marque un point ; ses
 * montants iront aux tuiles de LOP-233. Le lecteur d'écran lit chaque point, date et montants.
 */
@Composable
private fun TrendChart(points: List<TrendPoint>, mode: AnalysisMode, currency: String) {
    val income = LopTheme.extended.income
    val expense = LopTheme.extended.expense
    val grid = MaterialTheme.colorScheme.outlineVariant
    val marker = MaterialTheme.colorScheme.onSurfaceVariant
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    // Liée à la série : une autre période s'ouvre sans point choisi.
    var selected by remember(points) { mutableStateOf<Int?>(null) }

    val step = scaleStep(points.maxOf { maxOf(it.income, it.expense) })
    val top = ((points.maxOf { maxOf(it.income, it.expense) } + step - 1) / step).coerceAtLeast(1) * step
    val ticks = (0..(top / step).toInt()).map { it * step }
    val measurer = rememberTextMeasurer()
    val tickLabels = remember(ticks, currency, labelStyle) {
        ticks.map { measurer.measure(Format.compactMoney(it, currency), labelStyle) }
    }
    val density = LocalDensity.current
    val gutterPx = tickLabels.maxOf { it.size.width } + with(density) { 8.dp.toPx() }
    val gutter = with(density) { gutterPx.toDp() }
    val descriptions = points.map {
        val label = if (mode == AnalysisMode.MONTH) Format.dayMonthYear(it.start) else Format.monthYear(YearMonth.from(it.start))
        stringResource(R.string.analytics_trend_point, label, Format.money(it.income, currency), Format.money(it.expense, currency))
    }

    Box(Modifier.fillMaxWidth().height(160.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val half = tickLabels.first().size.height / 2f
            val h = size.height - 2 * half
            val stepX = (size.width - gutterPx) / (points.size - 1).coerceAtLeast(1)
            fun x(i: Int) = gutterPx + i * stepX
            fun y(v: Long) = half + h - v.toFloat() / top * h

            // Échelle des montants : une ligne par graduation, pleine à zéro, pointillée au-dessus.
            val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
            ticks.forEachIndexed { i, t ->
                drawLine(grid, Offset(gutterPx, y(t)), Offset(size.width, y(t)), 1.dp.toPx(), pathEffect = if (t == 0L) null else dash)
                val label = tickLabels[i]
                drawText(label, topLeft = Offset(gutterPx - 8.dp.toPx() - label.size.width, y(t) - label.size.height / 2f))
            }
            listOf(income to TrendPoint::income, expense to TrendPoint::expense).forEach { (color, value) ->
                val path = Path()
                points.forEachIndexed { i, p ->
                    if (i == 0) path.moveTo(x(i), y(value(p))) else path.lineTo(x(i), y(value(p)))
                }
                drawPath(path, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            selected?.let { i ->
                drawLine(marker, Offset(x(i), 0f), Offset(x(i), size.height), 1.dp.toPx())
                drawCircle(income, 4.dp.toPx(), Offset(x(i), y(points[i].income)))
                drawCircle(expense, 4.dp.toPx(), Offset(x(i), y(points[i].expense)))
            }
        }
        // Zone des courbes, à droite de l'échelle : sélection au toucher, et une case par point
        // que le lecteur d'écran parcourt une à une (CA-03).
        Row(
            Modifier
                .matchParentSize()
                .padding(start = gutter)
                .pointerInput(points.size) {
                    detectTapGestures { selected = indexAt(it.x, size.width, points.size) }
                }
                .pointerInput(points.size) {
                    detectHorizontalDragGestures(
                        onDragStart = { selected = indexAt(it.x, size.width, points.size) },
                    ) { change, _ ->
                        change.consume()
                        selected = indexAt(change.position.x, size.width, points.size)
                    }
                },
        ) {
            points.forEachIndexed { i, p ->
                val key = if (mode == AnalysisMode.MONTH) p.start.toString() else YearMonth.from(p.start).toString()
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .testTag("${TestTags.ANALYTICS_TREND_POINT}.$key")
                        .semantics { contentDescription = descriptions[i] },
                )
            }
        }
    }
    // CA-03 : l'axe couvre la première et la dernière case, aux deux bords, sans chevauchement.
    Row(Modifier.fillMaxWidth().padding(start = gutter)) {
        AxisLabel(points.first(), mode, TestTags.ANALYTICS_TREND_AXIS_START)
        Spacer(Modifier.weight(1f))
        AxisLabel(points.last(), mode, TestTags.ANALYTICS_TREND_AXIS_END)
    }
}

/** Pas rond (1, 2 ou 5 × 10ⁿ €, 1 € au moins) : au plus quatre intervalles jusqu'à [max] centimes. */
private fun scaleStep(max: Long): Long {
    val raw = max / 4.0
    var unit = 100L
    while (unit * 10 <= raw) unit *= 10
    return longArrayOf(unit, 2 * unit, 5 * unit, 10 * unit).first { it >= raw }
}

/** Case la plus proche de [x] ; les points vont du bord gauche au bord droit de la zone. */
private fun indexAt(x: Float, width: Int, count: Int): Int =
    if (count < 2 || width == 0) 0 else (x / width * (count - 1)).roundToInt().coerceIn(0, count - 1)

@Composable
private fun LegendItem(color: Color, label: String, tag: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag(tag)) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun AxisLabel(point: TrendPoint, mode: AnalysisMode, tag: String) {
    Text(
        if (mode == AnalysisMode.MONTH) Format.dayMonth(point.start) else Format.shortMonth(point.start),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag(tag),
    )
}

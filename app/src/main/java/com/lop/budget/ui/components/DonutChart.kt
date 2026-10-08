package com.lop.budget.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

data class DonutSlice(val value: Double, val color: Color, val label: String)

/**
 * Anneau (donut) sobre limité à quelques tranches pour rester lisible —
 * correction directe du point faible de Budge (donut surchargé).
 *
 * Les valeurs par défaut tracent l'anneau d'origine ; l'analyse Dépenses/Revenus passe un anneau
 * épais à bouts droits sur une piste discrète (LOP-40, P-9).
 *
 * @param gapDegrees espace entre deux portions. Avec des bouts ronds, le trait déborde dans cet
 *   espace : seuls des bouts droits ([StrokeCap.Butt]) le laissent net.
 * @param trackColor anneau de fond, visible dans les espaces ; transparent par défaut.
 * @param onSliceClick toucher d'une portion, par son index dans [slices] (LOP-40, CA-06).
 *   `null` : l'anneau ne réagit à aucun toucher.
 */
@Composable
fun DonutChart(
    slices: List<DonutSlice>,
    modifier: Modifier = Modifier,
    strokeWidth: Float = 46f,
    diameter: Dp = 200.dp,
    cap: StrokeCap = StrokeCap.Round,
    gapDegrees: Float = 4f,
    trackColor: Color = Color.Transparent,
    onSliceClick: ((Int) -> Unit)? = null,
    center: @Composable () -> Unit = {},
) {
    val total = slices.sumOf { it.value }.takeIf { it > 0 } ?: 1.0
    // Le geste vit aussi longtemps que les portions : il doit appeler le dernier rappel reçu.
    val onClick by rememberUpdatedState(onSliceClick)
    val tap = if (onSliceClick == null) Modifier else Modifier.pointerInput(slices, strokeWidth) {
        detectTapGestures { offset ->
            val dx = offset.x - size.width / 2f
            val dy = offset.y - size.height / 2f
            val radius = (min(size.width, size.height) - strokeWidth) / 2f
            // Le trait est fin pour un doigt : une marge de 12 dp de part et d'autre de l'anneau.
            if (abs(hypot(dx, dy) - radius) > strokeWidth / 2f + 12.dp.toPx()) return@detectTapGestures
            // Même origine que le tracé : midi, puis sens horaire.
            val angle = (Math.toDegrees(atan2(dy, dx).toDouble()) + 450.0) % 360.0
            var end = 0.0
            val index = slices.indexOfFirst { slice ->
                end += slice.value / total * 360.0
                angle < end
            }
            onClick?.invoke(if (index >= 0) index else slices.lastIndex)
        }
    }
    Box(modifier = modifier.size(diameter).then(tap), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(diameter)) {
            var startAngle = -90f
            val ring = size.minDimension - strokeWidth
            val topLeft = Offset((size.width - ring) / 2f, (size.height - ring) / 2f)
            val arcSize = Size(ring, ring)
            if (trackColor != Color.Transparent) {
                drawArc(trackColor, 0f, 360f, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(strokeWidth))
            }
            slices.forEach { slice ->
                val sweep = (slice.value / total * 360f).toFloat() - gapDegrees
                drawArc(
                    color = slice.color,
                    startAngle = startAngle,
                    sweepAngle = sweep.coerceAtLeast(0f),
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = cap),
                )
                startAngle += sweep + gapDegrees
            }
        }
        center()
    }
}

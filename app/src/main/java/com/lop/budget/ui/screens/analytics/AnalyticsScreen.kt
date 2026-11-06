package com.lop.budget.ui.screens.analytics

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lop.budget.R
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.ui.common.TestTags
import com.lop.budget.ui.components.DonutSlice
import com.lop.budget.ui.components.LopScreenScaffold
import com.lop.budget.ui.components.MonthPickerBottomSheet
import com.lop.budget.ui.components.YearPickerBottomSheet
import com.lop.budget.ui.components.rememberShowSkeleton
import com.lop.budget.ui.components.skeleton
import com.lop.budget.ui.components.surfaceRelief
import com.lop.budget.util.Format

@Composable
fun AnalyticsScreen(
    vm: AnalyticsViewModel = hiltViewModel(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val period by vm.period.collectAsStateWithLifecycle()
    val isLoaded by vm.isLoaded.collectAsStateWithLifecycle()
    // Données lues pour la période affichée seulement : celles d'avant un geste ne se présentent
    // jamais sous la nouvelle pastille (LOP-234, CA-04).
    val current = isLoaded && state.mode == period.mode && state.month == period.month
    val showSkeleton = rememberShowSkeleton(loading = !current)
    var showPicker by rememberSaveable { mutableStateOf(false) }

    // Limite à 6 tranches + "Autres" pour la lisibilité.
    val top = state.breakdown.take(6)
    val othersTotal = state.breakdown.drop(6).sumOf { it.total }
    // Le donut ne trace que des proportions : l'unité importe peu, le Double suffit.
    val slices = buildList {
        top.forEach { add(DonutSlice(it.total.toDouble(), Color(it.colorArgb), it.name)) }
        if (othersTotal > 0) add(DonutSlice(othersTotal.toDouble(), Color(0xFF9E9E9E), stringResource(R.string.others)))
    }

    LopScreenScaffold(
        title = stringResource(R.string.analytics_title),
        onBack = null,
        modifier = Modifier.testTag(TestTags.SCREEN_ANALYTICS),
        // Fixé sous le titre, hors de la liste : visible et utilisable pendant le défilement (CA-05).
        header = {
            PeriodHeader(
                period = period,
                onPrevious = vm::previousPeriod,
                onNext = vm::nextPeriod,
                onPick = { showPicker = true },
                onMode = vm::setMode,
            )
        },
    ) {
        if (showSkeleton) return@LopScreenScaffold skeleton()
        if (!current) return@LopScreenScaffold // attente courte : rien (P-12)
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                com.lop.budget.ui.components.DonutChart(
                    slices = slices,
                    modifier = Modifier.size(240.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = stringResource(if (state.type == TransactionType.EXPENSE) R.string.expense else R.string.income),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = Format.money(state.total, state.currency),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                state.breakdown.forEach { item ->
                    val color = Color(item.colorArgb)
                    com.lop.budget.ui.components.FloatingCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("analytics.breakdown.${item.name}"),
                        contentPadding = PaddingValues(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(color.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "${(item.share * 100).toInt()}%",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = color,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = item.name,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = Format.money(item.total, state.currency),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }

        // P-6 : seuls des montants lus pour la période restent ; les blocs factices (courbe simulée,
        // « vs période précédente », Budget, Prévision, « Aujourd'hui ») sont retirés.
        item {
            Column(Modifier.fillMaxWidth().padding(top = 32.dp)) {
                Text(stringResource(R.string.analytics_spent_period), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Text(Format.money(state.breakdown.sumOf { it.total }, state.currency), style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold)
            }
        }
    }

    // CA-03 : un appui sur un mois ou une année valide ; fermer la feuille annule.
    if (showPicker) {
        when (period.mode) {
            AnalysisMode.MONTH -> MonthPickerBottomSheet(
                selected = period.month,
                currentMonth = vm.currentMonth(),
                onSelect = vm::pickMonth,
                onDismiss = { showPicker = false },
            )
            AnalysisMode.YEAR -> YearPickerBottomSheet(
                selected = period.month.year,
                onSelect = vm::pickYear,
                onDismiss = { showPicker = false },
                modifier = Modifier.testTag(TestTags.ANALYTICS_YEAR_PICKER),
            )
        }
    }
}

/** Pastille de période entre ses flèches, puis le choix Mois / Année (CA-01). Aucun mode Semaine. */
@Composable
private fun PeriodHeader(
    period: AnalyticsPeriod,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onPick: () -> Unit,
    onMode: (AnalysisMode) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // CA-03, P-5 : commandes visibles, sans geste de balayage.
            RoundButton(Icons.Outlined.ChevronLeft, stringResource(R.string.analytics_previous_period), TestTags.ANALYTICS_PERIOD_PREVIOUS, onPrevious)
            PeriodPill(period, onPrevious, onNext, onPick, Modifier.weight(1f))
            RoundButton(Icons.Outlined.ChevronRight, stringResource(R.string.analytics_next_period), TestTags.ANALYTICS_PERIOD_NEXT, onNext)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                .padding(4.dp),
        ) {
            Seg(stringResource(R.string.analytics_mode_month), period.mode == AnalysisMode.MONTH, Modifier.weight(1f).testTag(TestTags.ANALYTICS_MODE_MONTH)) { onMode(AnalysisMode.MONTH) }
            Seg(stringResource(R.string.analytics_mode_year), period.mode == AnalysisMode.YEAR, Modifier.weight(1f).testTag(TestTags.ANALYTICS_MODE_YEAR)) { onMode(AnalysisMode.YEAR) }
        }
    }
}

/** « Mai 2026 » en mode Mois (P-7), « 2026 » en mode Année. */
private fun AnalyticsPeriod.label(): String =
    if (mode == AnalysisMode.MONTH) Format.monthYear(month) else month.year.toString()

/**
 * CA-02, P-4 : doigt vers la gauche → période suivante, vers la droite → précédente, au-delà de
 * 48 dp. Un appui sans glissement ouvre le choix direct (CA-03).
 */
@Composable
private fun PeriodPill(
    period: AnalyticsPeriod,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onPick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val previous by rememberUpdatedState(onPrevious)
    val next by rememberUpdatedState(onNext)
    val shape = RoundedCornerShape(50)
    Row(
        modifier = modifier
            .heightIn(min = 48.dp)
            .surfaceRelief(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, shape)
            .clip(shape)
            .pointerInput(Unit) {
                val threshold = 48.dp.toPx()
                var dx = 0f
                detectHorizontalDragGestures(
                    onDragStart = { dx = 0f },
                    onDragEnd = {
                        if (dx <= -threshold) next() else if (dx >= threshold) previous()
                    },
                ) { change, amount ->
                    change.consume()
                    dx += amount
                }
            }
            .clickable(onClickLabel = stringResource(R.string.analytics_pick_period), role = Role.Button, onClick = onPick)
            .testTag(TestTags.ANALYTICS_PERIOD_PILL)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.CalendarMonth, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.width(8.dp))
        Text(period.label(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** Bouton rond d'en-tête (`DESIGN.md` § 6). */
@Composable
private fun RoundButton(icon: ImageVector, description: String, tag: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .surfaceRelief(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface)
    }
}

/** Segment du choix Mois / Année, sur le modèle des onglets de Suivi. */
@Composable
private fun Seg(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(12.dp))
            // Le mode choisi s'annonce sélectionné ; la graisse le distingue sans la couleur.
            .selectable(selected = selected, role = Role.Tab, onClick = onClick),
        color = if (selected) MaterialTheme.colorScheme.surface else Color.Transparent,
        tonalElevation = if (selected) 2.dp else 0.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

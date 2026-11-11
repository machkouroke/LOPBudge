package com.lop.budget.ui.screens.analytics

import androidx.annotation.StringRes
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lop.budget.R
import com.lop.budget.domain.model.ItemStatus
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.domain.usecase.insight.AnalyticsTrend
import com.lop.budget.domain.usecase.insight.GoalPace
import com.lop.budget.domain.usecase.insight.GoalSituation
import com.lop.budget.domain.usecase.insight.CategoryAboveHabits
import com.lop.budget.domain.usecase.insight.HabitsComparison
import com.lop.budget.domain.usecase.insight.RecurringCharge
import com.lop.budget.domain.usecase.insight.CalendarStart
import com.lop.budget.domain.usecase.insight.RecurringCharges
import com.lop.budget.ui.common.TestTags
import com.lop.budget.ui.components.CircleIcon
import com.lop.budget.ui.components.FloatingCard
import com.lop.budget.ui.components.LopBottomSheet
import com.lop.budget.ui.components.LopScreenScaffold
import com.lop.budget.ui.components.MonthPickerBottomSheet
import com.lop.budget.ui.components.YearPickerBottomSheet
import com.lop.budget.ui.components.rememberShowSkeleton
import com.lop.budget.ui.components.skeleton
import com.lop.budget.ui.components.glass
import com.lop.budget.ui.components.surfaceRelief
import dev.chrisbanes.haze.HazeState
import com.lop.budget.ui.theme.LopTheme
import com.lop.budget.util.Format
import com.lop.budget.util.IconMapper
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * [onOpenDetail] ouvre l'analyse détaillée d'un type (LOP-233, CA-02) ; [onOpenTransactions], la
 * vue unifiée des transactions (CA-07) ; [onOpenCategory], le détail Dépenses d'une catégorie
 * (LOP-240, CA-07) ; [onOpenSeriesCalendar], le calendrier d'une charge depuis son occurrence de
 * départ, ouvert sur sa prochaine échéance (LOP-241, CA-05, P-7, P-10) ; [onOpenGoal], le détail
 * d'un objectif, et [onCreateGoal], sa création (LOP-243, CA-06). Premier et dernier jours de la
 * période inclus.
 */
@Composable
fun AnalyticsScreen(
    onOpenDetail: (TransactionType, LocalDate, LocalDate) -> Unit,
    onOpenTransactions: (LocalDate, LocalDate) -> Unit,
    onOpenCategory: (categoryId: Long, LocalDate, LocalDate) -> Unit,
    onOpenSeriesCalendar: (CalendarStart) -> Unit,
    onOpenGoal: (goalId: Long) -> Unit,
    onCreateGoal: () -> Unit,
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
    val charges by vm.charges.collectAsStateWithLifecycle()
    val goals by vm.goals.collectAsStateWithLifecycle()
    var showCharges by rememberSaveable { mutableStateOf(false) }
    // P-8 de LOP-241 : la feuille est une fenêtre ; ouverte, elle resterait au-dessus du calendrier
    // pendant la transition. Fermée au départ, elle est rouverte au retour.
    var reopenCharges by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (reopenCharges) {
            reopenCharges = false
            showCharges = true
        }
    }
    val scope = rememberCoroutineScope()
    // P-7 : l'occurrence de départ est résolue au toucher ; une série sans occurrence n'ouvre rien.
    val openCharge: (Long) -> Unit = { seriesId ->
        scope.launch {
            val start = vm.calendarStart(seriesId) ?: return@launch
            if (showCharges) {
                showCharges = false
                reopenCharges = true
            }
            onOpenSeriesCalendar(start)
        }
    }

    LopScreenScaffold(
        title = stringResource(R.string.analytics_title),
        onBack = null,
        modifier = Modifier.testTag(TestTags.SCREEN_ANALYTICS),
        // Fixé sous le titre, hors de la liste : visible et utilisable pendant le défilement (CA-05).
        // En verre : la liste se voit défiler derrière ses contrôles.
        header = { haze ->
            PeriodHeader(
                period = period,
                onPrevious = vm::previousPeriod,
                onNext = vm::nextPeriod,
                onPick = { showPicker = true },
                onMode = vm::setMode,
                haze = haze,
            )
        },
    ) {
        if (showSkeleton) return@LopScreenScaffold skeleton()
        if (!current) return@LopScreenScaffold // attente courte : rien (P-12)
        // LOP-235 : en tête du contenu, les tuiles de LOP-233 dessous.
        item {
            TrendSection(state.trend, state.trendFailed, state.mode, state.currency, vm::retryTrend)
        }
        item {
            AnalyticsTiles(
                trend = state.trend,
                failed = state.trendFailed,
                currency = state.currency,
                // `current` : la période affichée est celle des montants des tuiles.
                onOpen = { type -> onOpenDetail(type, period.first, period.last) },
                onOpenAll = { onOpenTransactions(period.first, period.last) },
                onRetry = vm::retryTrend,
            )
        }
        // LOP-240 : sous les tuiles, en mode Mois, pour un mois passé ou courant (CA-06).
        if (state.habits != null || state.habitsFailed) item {
            HabitsCard(
                state.habits, state.habitsFailed, state.currency,
                // Du 1er au jour de comparaison (CA-07).
                onOpen = { id, until -> onOpenCategory(id, until.withDayOfMonth(1), until) },
                onRetry = vm::retryTrend,
            )
        }
        // LOP-241 : en dernier (P-9), hors période (CA-06) ; attente courte du chargement : rien.
        if (charges !is ChargesState.Loading) item {
            ChargesCard(charges, state.currency, onOpen = openCharge, onSeeAll = { showCharges = true }, onRetry = vm::retryCharges)
        }
        // LOP-242 : après les charges récurrentes ; un mois touché devient la période de tout
        // l'onglet, en mode Mois (CA-06, P-7). Réessayer relit la période, comme LOP-240.
        if (state.balances != null || state.balancesFailed) item {
            BalanceHistoryCard(state.balances, state.balancesFailed, state.currency, onPick = vm::pickMonth, onRetry = vm::retryTrend)
        }
        // LOP-243 : juste après le solde sur 12 mois (P-1), hors période (CA-06) ; attente courte du
        // chargement : rien, donc ni date ni situation (CA-07).
        if (goals !is GoalsState.Loading) item {
            GoalsPaceCard(goals, state.currency, onOpen = onOpenGoal, onCreate = onCreateGoal, onRetry = vm::retryGoals)
        }
        // Rien d'autre (CA-06 de LOP-233) : « Dépensé sur la période » (P-4), l'anneau et la
        // répartition par catégorie (P-7) venaient de l'ancienne page d'analyse ; la répartition
        // reste dans le détail ouvert par les tuiles.
    }

    (charges as? ChargesState.Ready)?.takeIf { showCharges }?.let { ready ->
        ChargesSheet(ready.charges.charges, state.currency, onOpen = openCharge, onDismiss = { showCharges = false })
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

/**
 * LOP-233 : Revenus et Dépenses de la période, payées et planifiées, tous comptes et sans compte
 * (P-3) — les totaux de la lecture de la tendance (P-5). Un appui ouvre le détail du type (CA-02) ;
 * vide, une tuile montre 0 € et reste ouvrable ; un échec remplace les montants (CA-05). Dessous,
 * le Solde ouvre toutes les transactions de la période (P-6, CA-07).
 */
@Composable
private fun AnalyticsTiles(
    trend: AnalyticsTrend,
    failed: Boolean,
    currency: String,
    onOpen: (TransactionType) -> Unit,
    onOpenAll: () -> Unit,
    onRetry: () -> Unit,
) {
    if (failed) {
        FloatingCard(Modifier.fillMaxWidth().testTag(TestTags.ANALYTICS_TILES_ERROR)) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.analytics_tiles_error), style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
                Button(onClick = onRetry, modifier = Modifier.testTag(TestTags.ANALYTICS_TILES_RETRY)) {
                    Text(stringResource(R.string.analysis_retry))
                }
            }
        }
        return
    }
    val ext = LopTheme.extended
    // Signe et couleur du « Solde du mois » de l'accueil : vert au-dessus de zéro, rouge en dessous.
    val balanceColor = when {
        trend.balance > 0 -> ext.income
        trend.balance < 0 -> ext.expense
        else -> MaterialTheme.colorScheme.onSurface
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Revenus puis Dépenses : l'ordre de la capture de l'US et de la légende de la tendance.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AnalyticsTile(
                R.string.income, Icons.Outlined.ArrowDownward, Format.money(trend.income, currency), ext.income,
                TestTags.ANALYTICS_TILE_INCOME, Modifier.weight(1f),
            ) { onOpen(TransactionType.INCOME) }
            AnalyticsTile(
                R.string.expense, Icons.Outlined.ArrowUpward, Format.money(trend.expense, currency), ext.expense,
                TestTags.ANALYTICS_TILE_EXPENSE, Modifier.weight(1f),
            ) { onOpen(TransactionType.EXPENSE) }
        }
        AnalyticsTile(
            R.string.analytics_balance, Icons.Outlined.AccountBalanceWallet, Format.signedMoney(trend.balance, currency),
            balanceColor, TestTags.ANALYTICS_TILE_BALANCE, Modifier.fillMaxWidth(), onOpenAll,
        )
    }
}

/**
 * Revenus et Dépenses en positif dans la couleur de leur type (`DESIGN.md` § 3.3), le détail porte
 * le signe (CA-03) ; le Solde est signé.
 */
@Composable
private fun AnalyticsTile(
    @StringRes label: Int,
    icon: ImageVector,
    amount: String,
    color: Color,
    tag: String,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    FloatingCard(
        modifier = modifier
            .clickable(interactionSource = null, indication = null, role = Role.Button, onClick = onClick)
            .testTag(tag),
        contentPadding = PaddingValues(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircleIcon(icon, tint = color, background = color.copy(alpha = 0.15f), size = 28.dp)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(label), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                amount,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Pastille de période entre ses flèches, puis le choix Mois / Année (CA-01). Aucun mode Semaine. */
/**
 * LOP-240 : au plus trois catégories au-dessus de leurs habitudes (CA-01, CA-04), ou le message
 * d'historique insuffisant, ou « Aucune catégorie… » (CA-05) ; un échec remplace le contenu (CA-06).
 */
@Composable
private fun HabitsCard(
    habits: HabitsComparison?,
    failed: Boolean,
    currency: String,
    onOpen: (categoryId: Long, until: LocalDate) -> Unit,
    onRetry: () -> Unit,
) {
    FloatingCard(Modifier.fillMaxWidth().testTag(TestTags.ANALYTICS_HABITS)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.analytics_habits_title), style = MaterialTheme.typography.titleMedium)
            when {
                failed -> CardError(
                    stringResource(R.string.analytics_habits_error), TestTags.ANALYTICS_HABITS_ERROR,
                    TestTags.ANALYTICS_HABITS_RETRY, onRetry,
                )
                habits is HabitsComparison.NotEnoughHistory -> HabitsMessage(
                    stringResource(R.string.analytics_habits_history, habits.missing), TestTags.ANALYTICS_HABITS_HISTORY,
                )
                habits is HabitsComparison.Ready && habits.lines.isEmpty() -> HabitsMessage(
                    stringResource(R.string.analytics_habits_none), TestTags.ANALYTICS_HABITS_NONE,
                )
                habits is HabitsComparison.Ready -> habits.lines.forEach { line ->
                    HabitsRow(line, currency) { onOpen(line.categoryId, habits.until) }
                }
            }
        }
    }
}

/** Échec de lecture d'une carte : message et Réessayer, à la place de son contenu. */
@Composable
internal fun CardError(text: String, tag: String, retryTag: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
        Text(text, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
        Button(onClick = onRetry, modifier = Modifier.testTag(retryTag)) {
            Text(stringResource(R.string.analysis_retry))
        }
    }
}

@Composable
internal fun HabitsMessage(text: String, tag: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag(tag),
    )
}

/**
 * Tuile teintée de la catégorie (`DESIGN.md` § 5.1), son icône dans une pastille comme les lignes
 * de transaction (P-9) ; l'écart, une dépense en trop, en rouge dépense.
 */
@Composable
private fun HabitsRow(line: CategoryAboveHabits, currency: String, onClick: () -> Unit) {
    val color = Color(line.colorArgb)
    FloatingCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(interactionSource = null, indication = null, role = Role.Button, onClick = onClick)
            .testTag(TestTags.ANALYTICS_HABITS_ROW),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        tint = color,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircleIcon(IconMapper.get(line.icon ?: "category"), tint = color, background = color.copy(alpha = 0.18f))
            Column(Modifier.weight(1f)) {
                Text(line.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    stringResource(R.string.analytics_habits_usual, Format.money(line.usual, currency)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(Format.money(line.spent, currency), style = MaterialTheme.typography.titleSmall)
                Text(
                    Format.signedMoney(line.gap, currency),
                    style = MaterialTheme.typography.labelMedium,
                    color = LopTheme.extended.expense,
                )
            }
        }
    }
}

/**
 * LOP-241 : total mensuel et part engagée (CA-03), les trois charges au plus fort coût annuel puis
 * le lien vers la liste complète (CA-04) ; « Aucune charge récurrente » ou l'échec (CA-06). Les
 * montants ne dépendent pas de la période affichée.
 */
@Composable
private fun ChargesCard(
    state: ChargesState,
    currency: String,
    onOpen: (seriesId: Long) -> Unit,
    onSeeAll: () -> Unit,
    onRetry: () -> Unit,
) {
    val ready = (state as? ChargesState.Ready)?.charges
    FloatingCard(Modifier.fillMaxWidth().testTag(TestTags.ANALYTICS_CHARGES)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.analytics_charges_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                // CA-02 : le nombre exact de charges.
                if (ready != null && ready.charges.isNotEmpty()) Text(
                    pluralStringResource(R.plurals.analytics_charges_count, ready.charges.size, ready.charges.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(TestTags.ANALYTICS_CHARGES_COUNT),
                )
            }
            when {
                ready == null -> CardError(
                    stringResource(R.string.analytics_charges_error), TestTags.ANALYTICS_CHARGES_ERROR,
                    TestTags.ANALYTICS_CHARGES_RETRY, onRetry,
                )
                ready.charges.isEmpty() -> HabitsMessage(stringResource(R.string.analytics_charges_none), TestTags.ANALYTICS_CHARGES_NONE)
                else -> {
                    ChargesSummary(ready, currency)
                    ready.charges.take(CHARGES_ON_CARD).forEach { ChargeRow(it, currency) { onOpen(it.seriesId) } }
                    if (ready.charges.size > CHARGES_ON_CARD) {
                        TextButton(onClick = onSeeAll, modifier = Modifier.testTag(TestTags.ANALYTICS_CHARGES_SEE_ALL)) {
                            Text(stringResource(R.string.analytics_charges_see_all, ready.charges.size))
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    }
}

private const val CHARGES_ON_CARD = 3

/**
 * CA-03 : « 1 650,00 €/mois », puis « 61 % de vos revenus récurrents » et sa barre. Au-delà de
 * 100 %, la part telle quelle, en rouge, barre pleine ; sans revenu récurrent, l'invitation.
 */
@Composable
private fun ChargesSummary(charges: RecurringCharges, currency: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.analytics_charges_per_month, Format.money(charges.monthlyTotal, currency)),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.testTag(TestTags.ANALYTICS_CHARGES_TOTAL),
        )
        val share = charges.share
        if (share == null) {
            HabitsMessage(stringResource(R.string.analytics_charges_no_income), TestTags.ANALYTICS_CHARGES_NO_INCOME)
            return@Column
        }
        val over = share > 100
        Text(
            stringResource(R.string.analytics_charges_share, share.toInt()),
            style = MaterialTheme.typography.bodyMedium,
            color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(TestTags.ANALYTICS_CHARGES_SHARE),
        )
        LinearProgressIndicator(
            progress = { share.coerceAtMost(100L) / 100f },
            modifier = Modifier.fillMaxWidth().height(8.dp).testTag(TestTags.ANALYTICS_CHARGES_SHARE_BAR),
            color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            strokeCap = StrokeCap.Round,
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
    }
}

/** Tuile teintée de la catégorie de la série, comme `HabitsRow` ; coûts mensuel puis annuel (CA-04). */
@Composable
private fun ChargeRow(charge: RecurringCharge, currency: String, onClick: () -> Unit) {
    val color = Color(charge.colorArgb)
    FloatingCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(interactionSource = null, indication = null, role = Role.Button, onClick = onClick)
            .testTag(TestTags.ANALYTICS_CHARGES_ROW),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        tint = color,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircleIcon(IconMapper.get(charge.icon ?: "category"), tint = color, background = color.copy(alpha = 0.18f))
            Text(charge.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text(stringResource(R.string.analytics_charges_per_month, Format.money(charge.monthly, currency)), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.analytics_charges_per_year, Format.money(charge.annual, currency)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * P-8 : la liste complète, dans le même ordre que la carte (CA-04). Posées dans une carte, les
 * lignes y sont des tuiles comme sur la carte ; liste bornée (`DESIGN.md` § 7).
 */
@Composable
private fun ChargesSheet(
    charges: List<RecurringCharge>,
    currency: String,
    onOpen: (seriesId: Long) -> Unit,
    onDismiss: () -> Unit,
) {
    LopBottomSheet(onDismiss = onDismiss, modifier = Modifier.testTag(TestTags.ANALYTICS_CHARGES_SHEET), maxHeightFraction = 0.9f) {
        Text(
            stringResource(R.string.analytics_charges_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 12.dp),
        )
        FloatingCard(
            modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp).fillMaxWidth().weight(1f, fill = false),
            contentPadding = PaddingValues(12.dp),
        ) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(charges, key = { it.seriesId }) { charge ->
                    ChargeRow(charge, currency) { onOpen(charge.seriesId) }
                }
            }
        }
    }
}

/**
 * LOP-243 : chaque objectif non archivé, dans l'ordre du use case (CA-05) ; sans objectif, « Aucun
 * objectif en cours » et « Créer un objectif » (CA-06) ; un échec remplace le contenu (CA-07).
 */
@Composable
private fun GoalsPaceCard(
    state: GoalsState,
    currency: String,
    onOpen: (goalId: Long) -> Unit,
    onCreate: () -> Unit,
    onRetry: () -> Unit,
) {
    val goals = (state as? GoalsState.Ready)?.goals
    FloatingCard(Modifier.fillMaxWidth().testTag(TestTags.ANALYTICS_GOALS)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.analytics_goals_title), style = MaterialTheme.typography.titleMedium)
            when {
                goals == null -> CardError(
                    stringResource(R.string.analytics_goals_error), TestTags.ANALYTICS_GOALS_ERROR,
                    TestTags.ANALYTICS_GOALS_RETRY, onRetry,
                )
                goals.isEmpty() -> {
                    HabitsMessage(stringResource(R.string.analytics_goals_none), TestTags.ANALYTICS_GOALS_NONE)
                    Button(onClick = onCreate, modifier = Modifier.testTag(TestTags.ANALYTICS_GOALS_CREATE)) {
                        Text(stringResource(R.string.analytics_goals_create))
                    }
                }
                else -> goals.forEach { goal -> GoalPaceRow(goal, currency) { onOpen(goal.id) } }
            }
        }
    }
}

/**
 * Tuile teintée de l'objectif, comme `HabitsRow`. Montants, barre et statut de LOP-79, tels quels
 * (I-2) ; dessous, le rythme et la date d'atteinte (CA-01, CA-02), puis la situation et l'effort
 * (CA-03, CA-04). Un Terminé se lit « Atteint », sans rythme ni date (CA-05).
 */
@Composable
private fun GoalPaceRow(goal: GoalPace, currency: String, onClick: () -> Unit) {
    val color = Color(goal.colorArgb)
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    FloatingCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(interactionSource = null, indication = null, role = Role.Button, onClick = onClick)
            .testTag(TestTags.ANALYTICS_GOALS_ROW),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        tint = color,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircleIcon(IconMapper.get(goal.icon), tint = color, background = color.copy(alpha = 0.18f))
                Text(goal.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(
                    Format.reachedOfTarget(goal.progress.reachedCents, goal.targetCents, currency),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.testTag(TestTags.ANALYTICS_GOALS_AMOUNTS),
                )
            }
            // Cible nulle ou négative : pourcentage non calculable, barre vide (LOP-79, CA-07, CA-09).
            LinearProgressIndicator(
                progress = { (goal.progress.percent ?: 0) / 100f },
                modifier = Modifier.fillMaxWidth().height(8.dp).testTag(TestTags.ANALYTICS_GOALS_BAR),
                color = color,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                strokeCap = StrokeCap.Round,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val pace = goal.paceCents ?: 0L
                Text(
                    when {
                        goal.status == ItemStatus.COMPLETED -> stringResource(R.string.financial_status_reached)
                        pace <= 0 -> stringResource(R.string.analytics_goals_no_pace)
                        else -> stringResource(R.string.analytics_goals_pace, Format.money(pace, currency))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = secondary,
                    modifier = Modifier.weight(1f).testTag(TestTags.ANALYTICS_GOALS_PACE),
                )
                goal.reachMonth?.let {
                    Text(
                        stringResource(R.string.analytics_goals_reach, Format.monthYearInline(it)),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.testTag(TestTags.ANALYTICS_GOALS_REACH),
                    )
                }
            }
            goal.situation?.let { situation ->
                Text(
                    stringResource(
                        when (situation) {
                            GoalSituation.LATE -> R.string.analytics_goals_late
                            GoalSituation.ON_TIME -> R.string.analytics_goals_on_time
                            GoalSituation.OVERDUE -> R.string.analytics_goals_overdue
                        },
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (situation == GoalSituation.ON_TIME) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag(TestTags.ANALYTICS_GOALS_SITUATION),
                )
            }
            goal.effortCents?.let {
                Text(
                    stringResource(R.string.analytics_goals_effort, Format.money(it, currency)),
                    style = MaterialTheme.typography.bodySmall,
                    color = secondary,
                    modifier = Modifier.testTag(TestTags.ANALYTICS_GOALS_EFFORT),
                )
            }
        }
    }
}

@Composable
private fun PeriodHeader(
    period: AnalyticsPeriod,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onPick: () -> Unit,
    onMode: (AnalysisMode) -> Unit,
    haze: HazeState,
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
            RoundButton(Icons.Outlined.ChevronLeft, stringResource(R.string.analytics_previous_period), TestTags.ANALYTICS_PERIOD_PREVIOUS, haze, onPrevious)
            PeriodPill(period, onPrevious, onNext, onPick, haze, Modifier.weight(1f))
            RoundButton(Icons.Outlined.ChevronRight, stringResource(R.string.analytics_next_period), TestTags.ANALYTICS_PERIOD_NEXT, haze, onNext)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .glass(haze, RoundedCornerShape(16.dp))
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
    haze: HazeState,
    modifier: Modifier = Modifier,
) {
    val previous by rememberUpdatedState(onPrevious)
    val next by rememberUpdatedState(onNext)
    val shape = RoundedCornerShape(50)
    Row(
        modifier = modifier
            .heightIn(min = 48.dp)
            .glass(haze, shape)
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
private fun RoundButton(icon: ImageVector, description: String, tag: String, haze: HazeState, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .glass(haze, CircleShape)
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

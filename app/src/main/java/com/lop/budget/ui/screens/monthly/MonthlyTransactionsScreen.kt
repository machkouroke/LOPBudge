package com.lop.budget.ui.screens.monthly

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Wallet
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lop.budget.R
import com.lop.budget.domain.CategoryBreakdown
import com.lop.budget.domain.model.NO_ACCOUNT_ID
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.ui.common.TestTags
import com.lop.budget.ui.components.AccountBottomSheet
import com.lop.budget.ui.components.CategoryBottomSheet
import com.lop.budget.ui.components.DonutChart
import com.lop.budget.ui.components.DonutSlice
import com.lop.budget.ui.components.FloatingCard
import com.lop.budget.ui.components.HapticIntent
import com.lop.budget.ui.components.LopBottomSheet
import com.lop.budget.ui.components.LopDatePicker
import com.lop.budget.ui.components.LopScreenScaffold
import com.lop.budget.ui.components.LopSearchBar
import com.lop.budget.ui.components.PickerBottomSheet
import com.lop.budget.ui.components.pressScaleClickable
import com.lop.budget.ui.components.transactionDayGroups
import com.lop.budget.ui.motion.MotionSpec
import com.lop.budget.ui.theme.LopTheme
import com.lop.budget.util.Format
import dev.chrisbanes.haze.HazeState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.delay

/** Panneau de catégories ouvert par l'analyse (LOP-40, CA-09). */
private enum class CategorySheet { ALL, OTHERS }

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MonthlyTransactionsScreen(
    onBack: () -> Unit,
    onOpenTransaction: (Long) -> Unit,
    onNavigateToSearch: (String) -> Unit, // Callback to navigate to global search
    snackbarHostState: SnackbarHostState,
    hazeState: HazeState? = null,
    vm: MonthlyTransactionsViewModel = hiltViewModel(),
    actionVm: com.lop.budget.ui.common.TransactionActionViewModel = hiltViewModel(LocalContext.current as androidx.activity.ComponentActivity)
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val txVersions by actionVm.txVersions.collectAsStateWithLifecycle()

    var showTypePicker by remember { mutableStateOf(false) }
    var showStatusPicker by remember { mutableStateOf(false) }
    var showAccountPicker by remember { mutableStateOf(false) }
    var showCategoryPicker by remember { mutableStateOf(false) }
    var showPeriodSheet by remember { mutableStateOf(false) }
    var categorySheet by remember { mutableStateOf<CategorySheet?>(null) }

    val ext = LopTheme.extended
    val accent = if (state.type == TransactionType.INCOME) ext.income else ext.expense

    // LOP-40, CA-01 : l'analyse porte le nom du type analysé, pas un titre générique.
    val pageTitle = when {
        !state.isAnalyticsMode -> stringResource(R.string.monthly_transactions_title)
        state.type == TransactionType.INCOME -> stringResource(R.string.income)
        state.type == TransactionType.EXPENSE -> stringResource(R.string.expense)
        else -> "Analyses"
    }

    LopScreenScaffold(
        title = pageTitle,
        onBack = onBack,
        navigationIcon = Icons.AutoMirrored.Filled.ArrowBack,
        modifier = Modifier.testTag(TestTags.SCREEN_MONTHLY),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        header = {
            if (state.isAnalyticsMode) {
                AnalysisFilters(
                    state = state,
                    accent = accent,
                    onOpenPeriod = { showPeriodSheet = true },
                    onStatus = vm::setFilter,
                )
            }
        },
    ) {
        if (state.isAnalyticsMode) {
            analysisContent(
                state = state,
                accent = accent,
                txVersions = txVersions,
                onOpenTransaction = onOpenTransaction,
                onToggleCategory = vm::toggleCategory,
                onClearCategory = { vm.onCategoryFilterChange(null) },
                onEditPeriod = { showPeriodSheet = true },
                onRetry = vm::retry,
                onOpenCategories = { categorySheet = CategorySheet.ALL },
                onOpenOthers = { categorySheet = CategorySheet.OTHERS },
            )
        } else {
            item {
                val monthStr = state.month.month.getDisplayName(TextStyle.FULL, Locale.FRANCE).replaceFirstChar { it.uppercase() }
                Text(
                    "$monthStr ${state.month.year}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Use the new modern LopSearchBar
                    LopSearchBar(
                        value = state.searchQuery,
                        onValueChange = vm::onQueryChange,
                        placeholder = "Rechercher ce mois..."
                    )

                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 8.dp)
                    ) {
                        item {
                            FilterChip(
                                selected = state.type != null,
                                onClick = { showTypePicker = true },
                                modifier = Modifier.testTag("monthly.filter.type"),
                                label = {
                                    Text(when(state.type) {
                                        null -> "Tous les types"
                                        TransactionType.EXPENSE -> "Dépenses"
                                        TransactionType.INCOME -> "Revenus"
                                    })
                                },
                                leadingIcon = {
                                    Icon(
                                        when(state.type) {
                                            null -> Icons.Default.SwapHoriz
                                            TransactionType.EXPENSE -> Icons.Default.ArrowDownward
                                            TransactionType.INCOME -> Icons.Default.ArrowUpward
                                        },
                                        null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            )
                        }
                        item {
                            FilterChip(
                                selected = state.filter != PaidFilter.ALL,
                                onClick = { showStatusPicker = true },
                                modifier = Modifier.testTag("monthly.filter.status"),
                                label = {
                                    Text(when(state.filter) {
                                        PaidFilter.ALL -> "Tous les statuts"
                                        PaidFilter.PAID -> "Payé"
                                        PaidFilter.PLANNED -> "Planifié"
                                    })
                                }
                            )
                        }
                        item {
                            FilterChip(
                                selected = state.selectedAccountId != null,
                                onClick = { showAccountPicker = true },
                                modifier = Modifier.testTag("monthly.filter.account"),
                                label = {
                                    val acc = state.availableAccounts.find { it.id == state.selectedAccountId }
                                    Text(
                                        when {
                                            acc != null -> acc.name
                                            // Filtre actif sur les transactions sans rattachement :
                                            // aucun compte ne correspond, et pourtant le filtre existe.
                                            state.selectedAccountId == NO_ACCOUNT_ID ->
                                                stringResource(R.string.tx_no_account)
                                            else -> "Compte"
                                        }
                                    )
                                },
                                leadingIcon = { Icon(Icons.Default.Wallet, null, modifier = Modifier.size(18.dp)) },
                                trailingIcon = if (state.selectedAccountId != null) {
                                    { IconButton(onClick = { vm.onAccountFilterChange(null) }, modifier = Modifier.size(18.dp)) { Icon(Icons.Default.Close, null) } }
                                } else null
                            )
                        }
                        item {
                            FilterChip(
                                selected = state.selectedCategoryId != null,
                                onClick = { showCategoryPicker = true },
                                modifier = Modifier.testTag("monthly.filter.category"),
                                label = {
                                    val cat = state.availableCategories.find { it.id == state.selectedCategoryId }
                                    Text(cat?.name ?: "Catégorie")
                                },
                                leadingIcon = { Icon(Icons.Default.Category, null, modifier = Modifier.size(18.dp)) },
                                trailingIcon = if (state.selectedCategoryId != null) {
                                    { IconButton(onClick = { vm.onCategoryFilterChange(null) }, modifier = Modifier.size(18.dp)) { Icon(Icons.Default.Close, null) } }
                                } else null
                            )
                        }
                    }
                }
            }

            // Cross-month suggestion banner
            item {
                AnimatedVisibility(
                    visible = state.hasResultsInOtherMonths,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.padding(vertical = 8.dp).fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Aucun résultat ce mois-ci", style = MaterialTheme.typography.titleSmall)
                                Text("Des transactions correspondantes existent dans d'autres mois.", style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(
                                onClick = { onNavigateToSearch(state.searchQuery) },
                                modifier = Modifier.testTag("monthly.suggestion.see_all")
                            ) {
                                Text("Voir tout")
                            }
                        }
                    }
                }
            }

            item {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.monthly_transactions_title), style = MaterialTheme.typography.titleLarge)
            }

            // Liste centralisée
            transactionDayGroups(
                dayGroups = state.dayGroups,
                currency = state.currency,
                txVersions = txVersions,
                onOpenTransaction = onOpenTransaction
            )

            if (state.dayGroups.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.monthly_no_transactions),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (showTypePicker) {
        PickerBottomSheet(
            title = "Filtrer par type",
            items = listOf(TransactionType.EXPENSE, TransactionType.INCOME),
            isSelected = { it == state.type },
            onSelect = {
                vm.setType(it)
                showTypePicker = false
            },
            onDismiss = { showTypePicker = false },
            itemLabel = { if (it == TransactionType.EXPENSE) "Dépenses" else "Revenus" },
            allowNone = true,
            noneLabel = "Tous les types",
        )
    }

    if (showStatusPicker) {
        PickerBottomSheet(
            title = "Filtrer par statut",
            items = listOf(PaidFilter.PAID, PaidFilter.PLANNED),
            isSelected = { it == state.filter },
            onSelect = {
                vm.setFilter(it ?: PaidFilter.ALL)
                showStatusPicker = false
            },
            onDismiss = { showStatusPicker = false },
            itemLabel = { if (it == PaidFilter.PAID) "Payé" else "Planifié" },
            allowNone = true,
            noneLabel = "Tous les statuts",
        )
    }

    if (showAccountPicker) {
        // Pas d'option « Tous les comptes » : ne rien filtrer est l'état par défaut, obtenu en
        // retirant le filtre par la croix du chip. En revanche « Sans compte » est bien un filtre
        // à part entière, qui ne ramène que les transactions sans rattachement.
        AccountBottomSheet(
            title = "Filtrer par compte",
            accounts = state.availableAccounts,
            selectedId = state.selectedAccountId,
            onSelect = { id ->
                vm.onAccountFilterChange(id)
                showAccountPicker = false
            },
            onDismiss = { showAccountPicker = false },
        )
    }

    if (showCategoryPicker) {
        CategoryBottomSheet(
            title = "Filtrer par catégorie",
            categories = state.availableCategories,
            selectedId = state.selectedCategoryId,
            onSelect = {
                vm.onCategoryFilterChange(it)
                showCategoryPicker = false
            },
            onDismiss = { showCategoryPicker = false }
        )
    }

    if (showPeriodSheet) {
        PeriodSheet(
            period = state.period,
            onApply = { start, end ->
                vm.setPeriod(start, end)
                showPeriodSheet = false
            },
            onDismiss = { showPeriodSheet = false },
        )
    }

    categorySheet?.let { sheet ->
        val icons = state.availableCategories.associate { it.id to it.icon }
        PickerBottomSheet(
            title = stringResource(
                if (sheet == CategorySheet.ALL) R.string.analysis_categories else R.string.analysis_other_categories
            ),
            // « Autres catégories » ne réunit que les catégories regroupées dans l'anneau.
            items = if (sheet == CategorySheet.ALL) state.categoryChoices else state.breakdown.drop(DONUT_SLICES),
            isSelected = { it.categoryId == state.selectedCategoryId },
            onSelect = { choice ->
                if (choice == null) vm.onCategoryFilterChange(null) else choice.categoryId?.let(vm::toggleCategory)
                categorySheet = null
            },
            onDismiss = { categorySheet = null },
            // Nom complet, sans troncature (CA-08).
            itemLabel = { it.name },
            modifier = Modifier.testTag(
                if (sheet == CategorySheet.ALL) TestTags.ANALYSIS_CATEGORIES_SHEET else TestTags.ANALYSIS_OTHER_CATEGORIES_SHEET
            ),
            allowNone = sheet == CategorySheet.ALL,
            noneLabel = stringResource(R.string.analysis_all_categories),
            isNoneSelected = { state.selectedCategoryId == null },
            itemIcon = { icons[it.categoryId] },
            itemTint = { Color(it.colorArgb) },
            itemSupportingText = { choice -> figuresOf(state, choice)?.let { (amount, share) -> "$amount · $share" } },
        )
    }
}

/** Portions individuelles de l'anneau ; au-delà, un regroupement « Autres catégories » (CA-09). */
private const val DONUT_SLICES = 6

/** Délai avant « Actualisation… » : une réponse plus rapide n'affiche aucun indicateur (CA-10). */
private const val REFRESHING_INDICATOR_DELAY_MS = 200L

private enum class CardMode { REFRESHING, FAILED, EMPTY, READY }

private fun cardMode(state: MonthlyTransactionsUiState) = when {
    state.isRefreshing -> CardMode.REFRESHING
    state.loadFailed -> CardMode.FAILED
    state.transactions.isEmpty() -> CardMode.EMPTY
    else -> CardMode.READY
}

/**
 * Montant signé et part d'une catégorie dans période + type + statut, quelle que soit la catégorie
 * active (P-9) : choisir ne change que la coche, l'anneau et le total. `null` tant que les choix
 * ne sont pas ceux des critères affichés, ou en cas d'échec (I-3, CA-10).
 *
 * Les dépenses sont négatives, la part est arrondie à une décimale (CA-03).
 */
private fun figuresOf(state: MonthlyTransactionsUiState, choice: CategoryBreakdown): Pair<String, String>? {
    if (state.isRefreshing || state.loadFailed) return null
    val signed = if (state.type == TransactionType.EXPENSE) -choice.total else choice.total
    val percent = Format.percent(choice.total, state.categoryChoices.sumOf { it.total })
    return Format.money(signed, state.currency) to percent
}

/**
 * Période et statut, fixés sous le titre : ils restent visibles pendant la lecture de la liste
 * (P-6). Pas de rappel de la catégorie active ici : son apparition décalait toute l'analyse au
 * premier choix (P-9). Elle reste marquée et retirable dans la légende (CA-06, CA-07).
 */
@Composable
private fun AnalysisFilters(
    state: MonthlyTransactionsUiState,
    accent: Color,
    onOpenPeriod: () -> Unit,
    onStatus: (PaidFilter) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PeriodButton(state.period, onOpenPeriod)
        StatusSegment(state.filter, accent, onStatus)
    }
}

/** CA-01, CA-05 : les deux dates restent lisibles, sur deux lignes si nécessaire. */
@Composable
private fun PeriodButton(period: AnalysisPeriod, onClick: () -> Unit) {
    val start = Format.dayMonthYear(period.start)
    val end = Format.dayMonthYear(period.end)
    val description = stringResource(R.string.analysis_period_choose, start, end)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(MaterialTheme.shapes.small)
            .testTag(TestTags.ANALYSIS_PERIOD)
            .semantics { contentDescription = description }
            .pressScaleClickable(intent = HapticIntent.Tap, pressedScale = 0.98f, onClick = onClick),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.DateRange, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Text(
                "$start – $end",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Tous / Payé / Non payé : le fond glisse vers le statut choisi (P-6, 180 ms). */
@Composable
private fun StatusSegment(selected: PaidFilter, accent: Color, onSelect: (PaidFilter) -> Unit) {
    val options = listOf(
        Triple(PaidFilter.ALL, stringResource(R.string.monthly_filter_all), "monthly.insight.toggle.all"),
        Triple(PaidFilter.PAID, stringResource(R.string.monthly_filter_paid), "monthly.insight.toggle.paid"),
        Triple(PaidFilter.PLANNED, stringResource(R.string.analysis_filter_planned), "monthly.insight.toggle.planned"),
    )
    val shape = MaterialTheme.shapes.small
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
    ) {
        val segment = maxWidth / options.size
        val index = options.indexOfFirst { it.first == selected }.coerceAtLeast(0)
        val offset by animateDpAsState(
            targetValue = segment * index,
            animationSpec = tween(MotionSpec.MEDIUM_MS, easing = MotionSpec.easeOut),
            label = "statusSegment",
        )
        Box(
            Modifier
                .offset(x = offset)
                .width(segment)
                .fillMaxHeight()
                .padding(3.dp)
                .clip(shape)
                .background(accent.copy(alpha = 0.14f))
                .border(1.dp, accent.copy(alpha = 0.35f), shape),
        )
        Row(Modifier.fillMaxSize()) {
            options.forEach { (filter, label, tag) ->
                val isSelected = filter == selected
                val color by animateColorAsState(
                    targetValue = if (isSelected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                    animationSpec = tween(MotionSpec.MEDIUM_MS),
                    label = "statusLabel",
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .testTag(tag)
                        .semantics {
                            this.selected = isSelected
                            role = Role.Tab
                        }
                        .pressScaleClickable(intent = HapticIntent.Selection, pressedScale = 0.98f) { onSelect(filter) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        color = color,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    )
                }
            }
        }
    }
}

/**
 * Carte d'analyse, légende et liste du mode Analyses (LOP-40).
 *
 * Total, anneau, chiffres de la légende et liste ne viennent que d'un résultat prêt pour les
 * critères affichés (I-3, CA-10) : un ancien résultat n'est jamais montré sous de nouveaux filtres.
 */
private fun LazyListScope.analysisContent(
    state: MonthlyTransactionsUiState,
    accent: Color,
    txVersions: Map<Long, Int>,
    onOpenTransaction: (Long) -> Unit,
    onToggleCategory: (Long) -> Unit,
    onClearCategory: () -> Unit,
    onEditPeriod: () -> Unit,
    onRetry: () -> Unit,
    onOpenCategories: () -> Unit,
    onOpenOthers: () -> Unit,
) {
    val mode = cardMode(state)

    item(key = "analysis_card") {
        FloatingCard(Modifier.fillMaxWidth()) {
            // Le résultat précédent disparaît d'un coup, seul le nouveau apparaît en fondu : une
            // transition ne mélange pas deux résultats (I-3), et les anciennes parts ne se
            // transforment pas en nouvelles (P-6).
            val trigger: Any = if (mode == CardMode.READY) state.breakdown else mode
            AppearOnChange(trigger = trigger, durationMs = if (mode == CardMode.EMPTY) 150 else 220, initialScale = 0.96f) {
                when (mode) {
                    CardMode.REFRESHING -> RefreshingContent()
                    CardMode.FAILED -> ErrorContent(onRetry)
                    CardMode.EMPTY -> EmptyContent(
                        state = state,
                        accent = accent,
                        onClearCategory = onClearCategory,
                        onEditPeriod = onEditPeriod,
                    )
                    CardMode.READY -> ReadyContent(state, accent, onToggleCategory, onOpenOthers)
                }
            }
        }
    }

    if (state.categoryChoices.isNotEmpty()) {
        item(key = "analysis_legend") {
            Legend(state, onToggleCategory, onClearCategory, onOpenCategories)
        }
    }

    if (mode == CardMode.READY) {
        transactionDayGroups(
            dayGroups = state.dayGroups,
            currency = state.currency,
            txVersions = txVersions,
            onOpenTransaction = onOpenTransaction,
        )
    }
}

/**
 * Le contenu apparaît en fondu (et légère mise à l'échelle) à chaque nouveau [trigger] ; l'ancien
 * est retiré sur-le-champ. Animations système coupées : l'état final est rendu directement.
 */
@Composable
private fun AppearOnChange(
    trigger: Any,
    durationMs: Int,
    initialScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    key(trigger) {
        val progress = remember { Animatable(0f) }
        LaunchedEffect(Unit) { progress.animateTo(1f, tween(durationMs, easing = MotionSpec.easeOut)) }
        Box(
            Modifier.graphicsLayer {
                alpha = progress.value
                val scale = initialScale + (1f - initialScale) * progress.value
                scaleX = scale
                scaleY = scale
            },
        ) { content() }
    }
}

@Composable
private fun RefreshingContent() {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(REFRESHING_INDICATOR_DELAY_MS)
        visible = true
    }
    Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
        if (visible) {
            Text(
                stringResource(R.string.analysis_refreshing),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(TestTags.ANALYSIS_REFRESHING),
            )
        }
    }
}

/** CA-10 : une erreur de lecture n'est jamais un faux total à zéro. */
@Composable
private fun ErrorContent(onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp).testTag(TestTags.ANALYSIS_ERROR),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
        Text(
            stringResource(R.string.analysis_error),
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onRetry, modifier = Modifier.testTag(TestTags.ANALYSIS_RETRY)) {
            Text(stringResource(R.string.analysis_retry))
        }
    }
}

/** CA-07 : total à zéro, ni anneau ni pourcentage, et les filtres restent modifiables. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmptyContent(
    state: MonthlyTransactionsUiState,
    accent: Color,
    onClearCategory: () -> Unit,
    onEditPeriod: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TotalLabel()
        AmountText(Format.money(0L, state.currency), accent)
        Text(
            stringResource(R.string.analysis_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag(TestTags.ANALYSIS_EMPTY),
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.selectedCategoryId != null) {
                OutlinedButton(
                    onClick = onClearCategory,
                    modifier = Modifier.testTag(TestTags.ANALYSIS_EMPTY_REMOVE_CATEGORY),
                ) { Text(stringResource(R.string.analysis_remove_category)) }
            }
            OutlinedButton(
                onClick = onEditPeriod,
                modifier = Modifier.testTag(TestTags.ANALYSIS_EMPTY_EDIT_PERIOD),
            ) { Text(stringResource(R.string.analysis_edit_period)) }
        }
    }
}

@Composable
private fun ReadyContent(
    state: MonthlyTransactionsUiState,
    accent: Color,
    onToggleCategory: (Long) -> Unit,
    onOpenOthers: () -> Unit,
) {
    val top = state.breakdown.take(DONUT_SLICES)
    val othersTotal = state.breakdown.drop(DONUT_SLICES).sumOf { it.total }
    val othersText = stringResource(R.string.analysis_other_categories)
    val slices = remember(top, othersTotal, othersText) {
        buildList {
            // Le donut ne trace que des proportions : l'unité importe peu, le Double suffit.
            top.forEach { add(DonutSlice(it.total.toDouble(), Color(it.colorArgb), it.name)) }
            if (othersTotal > 0) add(DonutSlice(othersTotal.toDouble(), Color(0xFF9E9E9E), othersText))
        }
    }

    // Un seul montant principal (P-6). S'il ne tient pas au centre de l'anneau, il passe en
    // pleine largeur au-dessus, sans troncature.
    val amount = Format.money(state.total, state.currency)
    val amountStyle = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val fitsInRing = remember(amount, amountStyle, density) {
        measurer.measure(amount, amountStyle).size.width <= with(density) { 150.dp.toPx() }
    }
    val selected = state.breakdown.singleOrNull()?.takeIf { state.selectedCategoryId != null }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // CA-09 : avec une catégorie active, l'anneau à 100,0 % porte son nom.
        if (selected != null) {
            Text(
                selected.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag(TestTags.ANALYSIS_SELECTED_NAME),
            )
        }
        if (!fitsInRing) {
            TotalLabel()
            AmountText(amount, accent)
        }
        // P-9 : anneau épais à bouts droits, espaces nets sur une piste discrète. Une seule
        // portion remplit l'anneau sans encoche.
        DonutChart(
            slices = slices,
            modifier = Modifier.testTag(TestTags.ANALYSIS_DONUT),
            strokeWidth = with(density) { 22.dp.toPx() },
            diameter = 220.dp,
            cap = StrokeCap.Butt,
            gapDegrees = if (slices.size > 1) 2f else 0f,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            onSliceClick = { index ->
                // CA-06 : une portion individuelle sélectionne sa catégorie. « Autres
                // catégories » n'en est pas une : elle ouvre les catégories regroupées (CA-09).
                val group = top.getOrNull(index)
                if (group == null) onOpenOthers() else group.categoryId?.let(onToggleCategory)
            },
        ) {
            if (fitsInRing) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    TotalLabel(Modifier.widthIn(max = 160.dp))
                    AmountText(amount, accent)
                }
            }
        }
    }
}

@Composable
private fun TotalLabel(modifier: Modifier = Modifier) {
    Text(
        stringResource(R.string.analysis_total_selection),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = modifier,
    )
}

@Composable
private fun AmountText(amount: String, accent: Color) {
    Text(
        amount,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        color = accent,
        textAlign = TextAlign.Center,
        modifier = Modifier.testTag(TestTags.ANALYSIS_TOTAL),
    )
}

/**
 * Légende et sélecteur à la fois (CA-06, CA-09) : six capsules, trois par ligne sur un écran
 * standard (P-9) ; deux, puis une seule, si la largeur ou la taille du texte l'exige. Les
 * emplacements ne bougent pas quand on choisit.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Legend(
    state: MonthlyTransactionsUiState,
    onToggleCategory: (Long) -> Unit,
    onClearCategory: () -> Unit,
    onOpenCategories: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val fontScale = LocalDensity.current.fontScale
            val columns = when {
                maxWidth >= 330.dp && fontScale <= 1.3f -> 3
                maxWidth >= 240.dp -> 2
                else -> 1
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                legendSlots(state.categoryChoices, state.selectedCategoryId).chunked(columns).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        row.forEach { choice ->
                            CategoryCapsule(
                                choice = choice,
                                active = choice.categoryId == state.selectedCategoryId,
                                figures = figuresOf(state, choice),
                                onClick = { choice.categoryId?.let(onToggleCategory) },
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                            )
                        }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.categoryChoices.size > LEGEND_SLOTS) {
                TextButton(onClick = onOpenCategories, modifier = Modifier.testTag(TestTags.ANALYSIS_CATEGORIES_SEE_ALL)) {
                    Text(stringResource(R.string.analysis_see_all))
                }
            }
            if (state.selectedCategoryId != null) {
                TextButton(onClick = onClearCategory, modifier = Modifier.testTag(TestTags.ANALYSIS_CATEGORIES_CLEAR)) {
                    Text(stringResource(R.string.analysis_all_categories))
                }
            }
        }
    }
}

/**
 * Capsule de catégorie, compacte pour tenir à trois par ligne (P-9). Active : fond teinté,
 * bordure et coche à l'accent du thème, état annoncé au lecteur d'écran (CA-06) ; la couleur de
 * la catégorie ne sert qu'à la reconnaître (P-6).
 */
@Composable
private fun CategoryCapsule(
    choice: CategoryBreakdown,
    active: Boolean,
    figures: Pair<String, String>?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    val background by animateColorAsState(
        targetValue = if (active) primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        animationSpec = tween(MotionSpec.MEDIUM_MS),
        label = "capsuleBackground",
    )
    val border by animateColorAsState(
        targetValue = if (active) primary else Color.Transparent,
        animationSpec = tween(MotionSpec.MEDIUM_MS),
        label = "capsuleBorder",
    )
    Surface(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(MaterialTheme.shapes.small)
            .testTag(TestTags.ANALYSIS_CATEGORY_PREFIX + choice.categoryId)
            .semantics { selected = active }
            .pressScaleClickable(intent = HapticIntent.Selection, pressedScale = 0.98f, onClick = onClick),
        shape = MaterialTheme.shapes.small,
        color = background,
        border = BorderStroke(1.dp, border),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    Modifier
                        .padding(top = 4.dp)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Color(choice.colorArgb)),
                )
                Spacer(Modifier.width(6.dp))
                // Nom complet, sur plusieurs lignes au besoin (CA-08).
                Text(
                    choice.name,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                AnimatedVisibility(
                    visible = active,
                    enter = fadeIn(tween(MotionSpec.MEDIUM_MS)) + scaleIn(tween(MotionSpec.MEDIUM_MS)),
                    exit = fadeOut(tween(MotionSpec.MEDIUM_MS)) + scaleOut(tween(MotionSpec.MEDIUM_MS)),
                ) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint = primary,
                        modifier = Modifier.padding(start = 2.dp).size(14.dp),
                    )
                }
            }
            // Montant et part sur deux lignes, réservées pendant une actualisation : la capsule
            // garde sa hauteur, la grille ne saute pas (P-6).
            Text(
                figures?.first ?: " ",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 14.dp, top = 2.dp),
            )
            Text(
                figures?.second ?: " ",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 14.dp),
            )
        }
    }
}

private enum class PeriodField { START, END }

/**
 * « Choisir une période » (CA-05, P-6). Parcourir les calendriers ne change pas l'analyse :
 * seul « Appliquer » transmet l'intervalle, et une fin antérieure au début le désactive.
 */
@Composable
private fun PeriodSheet(
    period: AnalysisPeriod,
    onApply: (LocalDate, LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    var start by remember { mutableStateOf(period.start) }
    var end by remember { mutableStateOf(period.end) }
    var editing by remember { mutableStateOf<PeriodField?>(null) }
    val valid = !end.isBefore(start)
    val haptic = LocalHapticFeedback.current

    LopBottomSheet(onDismiss = onDismiss, modifier = Modifier.testTag(TestTags.ANALYSIS_PERIOD_SHEET)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.analysis_period_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            PeriodDateField(stringResource(R.string.analysis_period_start), start, TestTags.ANALYSIS_PERIOD_START) {
                editing = PeriodField.START
            }
            PeriodDateField(stringResource(R.string.analysis_period_end), end, TestTags.ANALYSIS_PERIOD_END) {
                editing = PeriodField.END
            }
            if (!valid) {
                Text(
                    stringResource(R.string.analysis_period_invalid),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag(TestTags.ANALYSIS_PERIOD_INVALID),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = onDismiss, modifier = Modifier.testTag(TestTags.ANALYSIS_PERIOD_CANCEL)) {
                    Text(stringResource(R.string.cancel))
                }
                Button(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onApply(start, end)
                    },
                    enabled = valid,
                    modifier = Modifier.testTag(TestTags.ANALYSIS_PERIOD_APPLY),
                ) { Text(stringResource(R.string.analysis_period_apply)) }
            }
        }
    }

    editing?.let { field ->
        val zone = ZoneId.systemDefault()
        LopDatePicker(
            initialDateMillis = (if (field == PeriodField.START) start else end)
                .atStartOfDay(zone).toInstant().toEpochMilli(),
            onDateSelected = { millis ->
                val date = millis?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
                if (date != null) {
                    if (field == PeriodField.START) start = date else end = date
                }
            },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun PeriodDateField(label: String, date: LocalDate, tag: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(MaterialTheme.shapes.small)
            .testTag(tag)
            .pressScaleClickable(intent = HapticIntent.Tap, pressedScale = 0.98f, onClick = onClick),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(Format.dayMonthYear(date), style = MaterialTheme.typography.titleMedium)
            }
            Icon(Icons.Default.DateRange, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

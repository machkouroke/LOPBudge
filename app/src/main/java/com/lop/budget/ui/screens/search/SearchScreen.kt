package com.lop.budget.ui.screens.search

import androidx.activity.ComponentActivity
import androidx.compose.animation.EnterExitState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lop.budget.R
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.CategoryEntity
import com.lop.budget.data.local.entity.TagEntity
import com.lop.budget.domain.model.NO_ACCOUNT_ID
import com.lop.budget.domain.model.NO_CATEGORY_LABEL
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.ui.common.TestTags
import com.lop.budget.ui.common.TransactionActionViewModel
import com.lop.budget.ui.components.rememberShowSkeleton
import com.lop.budget.ui.components.skeleton
import com.lop.budget.ui.components.CategoryBottomSheet
import com.lop.budget.ui.components.FloatingCard
import com.lop.budget.ui.components.LopBottomSheet
import com.lop.budget.ui.components.LopDateRangePicker
import com.lop.budget.ui.components.LopFieldShape
import com.lop.budget.ui.components.LopScreenScaffold
import com.lop.budget.ui.components.LopSearchBar
import com.lop.budget.ui.components.SearchBarTransition
import com.lop.budget.ui.components.searchBarBounds
import com.lop.budget.ui.components.StackedTransactionGroup
import com.lop.budget.ui.components.SUMMARY_PLACEHOLDER
import com.lop.budget.ui.components.SummaryFigure
import com.lop.budget.ui.components.TransactionRow
import com.lop.budget.ui.components.surfaceRelief
import com.lop.budget.ui.screens.home.IconButton as RoundIconButton
import com.lop.budget.ui.screens.transaction.TagsBottomSheet
import com.lop.budget.ui.theme.LopTheme
import com.lop.budget.util.Format
import com.lop.budget.util.IconMapper
import dev.chrisbanes.haze.HazeState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Vue unifiée recherche + historique des transactions (LOP-228).
 *
 * Présentation inspirée de Silo (P-9) : bouton Filtres à pastille, une puce par critère appliqué,
 * carte Dépenses / Revenus / Solde, feuille de filtres validée par « Appliquer ». Recherche, puces
 * et synthèse restent fixes en tête ; seule la liste défile (P-11).
 */
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onOpenTransaction: (Long) -> Unit,
    snackbarHostState: SnackbarHostState,
    hazeState: HazeState? = null,
    /** Ouverte par la barre de l'accueil : la pilule arrive de l'accueil, le clavier s'ouvre ensuite. */
    searchBar: SearchBarTransition? = null,
    vm: SearchViewModel = hiltViewModel(),
    actionVm: TransactionActionViewModel = hiltViewModel(LocalContext.current as ComponentActivity)
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val txVersions by actionVm.txVersions.collectAsStateWithLifecycle()
    var showFilters by remember { mutableStateOf(false) }

    // Le champ prend le focus une fois la pilule posée, une seule fois : un retour depuis un détail
    // ne rouvre pas le clavier.
    val focusRequester = remember { FocusRequester() }
    var focusPending by rememberSaveable { mutableStateOf(searchBar != null) }
    val arrived = searchBar?.screen?.transition?.currentState?.let { it == EnterExitState.Visible } ?: true
    LaunchedEffect(focusPending, arrived) {
        if (focusPending && arrived) {
            focusRequester.requestFocus()
            focusPending = false
        }
    }

    // Regroupement visuel des occurrences d'une même série : la synthèse, elle, compte chaque
    // ligne (CA-04), elle ne lit pas ces piles.
    val allTxs = remember(state.dayGroups) { state.dayGroups.flatMap { it.transactions } }
    val multiOccurrencesSeries = remember(allTxs) {
        allTxs.groupBy { it.transaction.seriesId }.filter { it.key != null && it.value.size > 1 }
    }
    val dayGroupItems = remember(state.dayGroups, multiOccurrencesSeries) {
        val displayedSeries = mutableSetOf<Long>()
        state.dayGroups.map { group ->
            val items = group.transactions.filter { twr ->
                val sid = twr.transaction.seriesId
                if (sid != null && multiOccurrencesSeries.containsKey(sid)) displayedSeries.add(sid) else true
            }
            group.date to items
        }.filter { it.second.isNotEmpty() }
    }

    // Tant que les lignes ne sont pas celles des critères affichés, un squelette les remplace
    // (LOP-226, CA-06, P-10) : jamais un ancien résultat présenté comme le nouveau (CA-05).
    val showSkeleton = rememberShowSkeleton(loading = !state.isPrompt && !state.loadFailed && state.isRefreshing)
    // Avant que le squelette prenne le relais, les lignes d'une recherche précédente sont
    // estompées, jamais présentées comme le nouveau résultat (CA-05 de LOP-228).
    val listAlpha = if (state.isRefreshing) 0.4f else 1f

    LopScreenScaffold(
        title = stringResource(R.string.monthly_transactions_title),
        onBack = onBack,
        navigationIcon = Icons.AutoMirrored.Filled.ArrowBack,
        modifier = Modifier.testTag(TestTags.SCREEN_SEARCH),
        snackbarHost = { SnackbarHost(snackbarHostState, modifier = Modifier.testTag("snackbar_host")) },
        header = {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // Relief des contrôles flottants (DESIGN.md § 6) sur un fond opaque : le
                    // champ ne se fond plus dans le ciel de l'en-tête.
                    LopSearchBar(
                        value = state.query,
                        onValueChange = vm::onQueryChange,
                        modifier = Modifier
                            .weight(1f)
                            .searchBarBounds(searchBar)
                            .surfaceRelief(LopFieldShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh, LopFieldShape)
                            .focusRequester(focusRequester),
                        placeholder = stringResource(R.string.search_placeholder),
                    )
                    FiltersButton(state.filters.count) { showFilters = true }
                }
                ActiveFilters(state, onApply = vm::applyFilters)
                if (!state.isPrompt && !state.loadFailed) SummaryCard(state)
            }
        },
    ) {
        when {
            state.isPrompt -> item(key = "search_prompt") { Message(R.string.search_prompt, TestTags.SEARCH_PROMPT) }
            state.loadFailed -> item(key = "search_error") { ErrorContent(onRetry = vm::retry) }
            showSkeleton -> skeleton(TestTags.SEARCH_REFRESHING)
            !state.isRefreshing && state.count == 0 ->
                item(key = "search_empty") { Message(R.string.analysis_empty, TestTags.SEARCH_EMPTY) }
            else -> dayGroupItems.forEach { (date, itemsToDisplay) ->
                // Une pile en tête du jour porte ses propres dates : pas d'en-tête au-dessus.
                val firstIsStack = itemsToDisplay.first().transaction.seriesId
                    ?.let(multiOccurrencesSeries::containsKey) == true
                if (!firstIsStack) {
                    item(key = "day_$date") {
                        Text(
                            text = Format.fullDate(date),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.alpha(listAlpha).padding(vertical = 8.dp),
                        )
                    }
                }
                items(itemsToDisplay, key = { twr ->
                    val sid = twr.transaction.seriesId
                    if (sid != null && multiOccurrencesSeries.containsKey(sid)) "stack_$sid"
                    else "${twr.transaction.id}_${txVersions[twr.transaction.id] ?: 0}"
                }) { twr ->
                    Box(Modifier.alpha(listAlpha).testTag(TestTags.TRANSACTION_ITEM)) {
                        val stack = twr.transaction.seriesId?.let(multiOccurrencesSeries::get)
                        if (stack != null) {
                            StackedTransactionGroup(
                                transactions = stack,
                                currency = state.currency,
                                onOpenTransaction = onOpenTransaction,
                            )
                        } else {
                            TransactionRow(tx = twr, currency = state.currency, onOpenTransaction = onOpenTransaction)
                        }
                    }
                }
            }
        }
    }

    if (showFilters) {
        FiltersSheet(
            applied = state.filters,
            accounts = state.availableAccounts,
            categories = state.availableCategories,
            tags = state.availableTags,
            onApply = {
                vm.applyFilters(it)
                showFilters = false
            },
            onDismiss = { showFilters = false },
        )
    }
}


private val DAY_MONTH = DateTimeFormatter.ofPattern("d MMM", Locale.FRANCE)
private val DAY_MONTH_YEAR = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.FRANCE)

/** « 1 nov. – 30 nov. 2026 » : une période tient en une ligne (P-11). `null` sans période. */
@Composable
private fun periodLabel(start: LocalDate?, end: LocalDate?): String? = when {
    start != null && end != null && start == end -> start.format(DAY_MONTH_YEAR)
    start != null && end != null && start.year == end.year -> "${start.format(DAY_MONTH)} – ${end.format(DAY_MONTH_YEAR)}"
    start != null && end != null -> "${start.format(DAY_MONTH_YEAR)} – ${end.format(DAY_MONTH_YEAR)}"
    start != null -> stringResource(R.string.search_from, start.format(DAY_MONTH_YEAR))
    end != null -> stringResource(R.string.search_to, end.format(DAY_MONTH_YEAR))
    else -> null
}

@Composable
private fun FiltersButton(count: Int, onClick: () -> Unit) {
    val description = if (count == 0) stringResource(R.string.search_filters)
    else pluralStringResource(R.plurals.search_filters_applied, count, count)
    BadgedBox(
        badge = {
            if (count > 0) {
                Badge(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) { Text("$count") }
            }
        },
    ) {
        RoundIconButton(
            icon = Icons.Outlined.Tune,
            onClick = onClick,
            contentDescription = description,
            modifier = Modifier.testTag(TestTags.SEARCH_FILTER_OPEN),
        )
    }
}

/**
 * CA-03 : chaque critère appliqué reste visible, et un toucher le retire. Le texte n'y figure pas.
 * Une seule ligne qui défile de côté : l'en-tête fixe ne grandit pas avec le nombre de critères.
 */
@Composable
private fun ActiveFilters(state: SearchUiState, onApply: (SearchFilters) -> Unit) {
    val f = state.filters
    if (f.count == 0) return
    val accounts = state.availableAccounts.associateBy { it.id }
    val categories = state.availableCategories.associateBy { it.id }
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        periodLabel(f.start, f.end)?.let { ActiveChip(it, "period") { onApply(f.copy(start = null, end = null)) } }
        f.type?.let { ActiveChip(typeLabel(it), "type") { onApply(f.copy(type = null)) } }
        f.status?.let { ActiveChip(statusLabel(it), "status") { onApply(f.copy(status = null)) } }
        f.categoryIds.forEach { id ->
            ActiveChip(categories[id]?.name ?: NO_CATEGORY_LABEL, "category.$id") {
                onApply(f.copy(categoryIds = f.categoryIds - id))
            }
        }
        f.accountIds.forEach { id ->
            ActiveChip(accounts[id]?.name ?: stringResource(R.string.tx_no_account), "account.$id") {
                onApply(f.copy(accountIds = f.accountIds - id))
            }
        }
        f.tagNames.forEach { name ->
            ActiveChip("#$name", "tag.$name") { onApply(f.copy(tagNames = f.tagNames - name)) }
        }
        // Retire tous les critères de la feuille, pas le texte (CA-03).
        AssistChip(
            onClick = { onApply(SearchFilters()) },
            label = { Text(stringResource(R.string.search_filters_clear)) },
            colors = AssistChipDefaults.assistChipColors(labelColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.testTag(TestTags.SEARCH_ACTIVE_CLEAR),
        )
    }
}

@Composable
private fun ActiveChip(label: String, key: String, onRemove: () -> Unit) {
    InputChip(
        selected = true,
        onClick = onRemove,
        label = { Text(label, maxLines = 1) },
        trailingIcon = {
            Icon(Icons.Default.Close, stringResource(R.string.search_remove_filter), Modifier.size(18.dp))
        },
        modifier = Modifier.testTag(TestTags.SEARCH_ACTIVE_PREFIX + key),
    )
}

@Composable
private fun typeLabel(type: TransactionType) =
    stringResource(if (type == TransactionType.EXPENSE) R.string.expense else R.string.income)

@Composable
private fun statusLabel(status: TransactionStatus) =
    stringResource(if (status == TransactionStatus.PAID) R.string.search_status_paid else R.string.search_status_planned)

/**
 * CA-04 : Dépenses, Revenus et Solde des lignes listées. CA-05 : tant que ces lignes ne sont pas
 * celles des critères affichés, aucun montant n'est montré.
 */
@Composable
private fun SummaryCard(state: SearchUiState) {
    val ext = LopTheme.extended
    val ready = !state.isRefreshing
    val balanceColor = when {
        state.balance > 0 -> ext.income
        state.balance < 0 -> ext.expense
        else -> MaterialTheme.colorScheme.onSurface
    }
    FloatingCard(
        modifier = Modifier.fillMaxWidth().testTag(TestTags.SEARCH_SUMMARY),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp, vertical = 12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    if (ready) pluralStringResource(R.plurals.search_count, state.count, state.count) else SUMMARY_PLACEHOLDER,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(TestTags.SEARCH_SUMMARY_COUNT),
                )
            }
            Row(Modifier.fillMaxWidth()) {
                SummaryFigure(
                    R.string.expense, Format.money(state.expense, state.currency).takeIf { ready },
                    ext.expense, TestTags.SEARCH_SUMMARY_EXPENSE, Modifier.weight(1f),
                )
                SummaryFigure(
                    R.string.income, Format.money(state.income, state.currency).takeIf { ready },
                    ext.income, TestTags.SEARCH_SUMMARY_INCOME, Modifier.weight(1f),
                )
                SummaryFigure(
                    R.string.search_balance, Format.signedMoney(state.balance, state.currency).takeIf { ready },
                    balanceColor, TestTags.SEARCH_SUMMARY_BALANCE, Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun Message(text: Int, tag: String) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp).testTag(tag),
    )
}

/** CA-05 : une erreur n'affiche ni total ni liste, et se distingue d'un résultat vide. */
@Composable
private fun ErrorContent(onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp).testTag(TestTags.SEARCH_ERROR),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.search_error),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onRetry, modifier = Modifier.testTag(TestTags.SEARCH_RETRY)) {
            Text(stringResource(R.string.analysis_retry))
        }
    }
}

/** Sélecteur ouvert par-dessus la feuille Filtres. */
private enum class SubPicker { CATEGORIES, TAGS, PERIOD }

/**
 * Feuille Filtres (CA-02) : le brouillon vit ici, rien n'est transmis avant « Appliquer ». Glisser,
 * toucher le voile ou revenir referment sans rien appliquer (P-6).
 *
 * Catégories et étiquettes s'ouvrent dans leurs vrais sélecteurs, avec recherche et sections, et
 * n'y modifient que le brouillon (P-10). La période tient dans un seul bloc (P-11).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FiltersSheet(
    applied: SearchFilters,
    accounts: List<AccountEntity>,
    categories: List<CategoryEntity>,
    tags: List<TagEntity>,
    onApply: (SearchFilters) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf(applied) }
    var picker by remember { mutableStateOf<SubPicker?>(null) }
    val allLabel = stringResource(R.string.search_all_categories)

    LopBottomSheet(
        onDismiss = onDismiss,
        modifier = Modifier.testTag(TestTags.SEARCH_FILTER_SHEET),
        maxHeightFraction = 0.9f,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(stringResource(R.string.search_filters), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { draft = SearchFilters() }, modifier = Modifier.testTag(TestTags.SEARCH_FILTER_RESET)) {
                    Text(stringResource(R.string.search_filters_reset))
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                PickerRow(
                    title = stringResource(R.string.search_filter_period),
                    value = periodLabel(draft.start, draft.end) ?: stringResource(R.string.search_all_dates),
                    icon = Icons.Default.DateRange,
                    tag = TestTags.SEARCH_FILTER_PERIOD,
                    onClick = { picker = SubPicker.PERIOD },
                    onClear = if (draft.start == null && draft.end == null) null else {
                        { draft = draft.copy(start = null, end = null) }
                    },
                    clearTag = TestTags.SEARCH_FILTER_PERIOD_CLEAR,
                )
                Section(R.string.search_filter_type) {
                    listOf(TransactionType.EXPENSE, TransactionType.INCOME).forEach { t ->
                        ToggleChip(typeLabel(t), draft.type == t, TestTags.SEARCH_FILTER_TYPE_PREFIX + t.name) {
                            draft = draft.copy(type = if (draft.type == t) null else t)
                        }
                    }
                }
                Section(R.string.search_filter_status) {
                    listOf(TransactionStatus.PAID, TransactionStatus.PLANNED).forEach { s ->
                        ToggleChip(statusLabel(s), draft.status == s, TestTags.SEARCH_FILTER_STATUS_PREFIX + s.name) {
                            draft = draft.copy(status = if (draft.status == s) null else s)
                        }
                    }
                }
                PickerRow(
                    title = stringResource(R.string.search_filter_categories),
                    value = summary(categories.filter { it.id in draft.categoryIds }.map { it.name }) ?: allLabel,
                    tag = TestTags.SEARCH_FILTER_CATEGORIES,
                    onClick = { picker = SubPicker.CATEGORIES },
                )
                Section(R.string.search_filter_accounts) {
                    accounts.forEach { a ->
                        ToggleChip(
                            a.name, a.id in draft.accountIds, TestTags.SEARCH_FILTER_ACCOUNT_PREFIX + a.id,
                            icon = IconMapper.get(a.icon) as? ImageVector, tint = Color(a.colorArgb),
                        ) { draft = draft.copy(accountIds = draft.accountIds.toggle(a.id)) }
                    }
                    ToggleChip(
                        stringResource(R.string.tx_no_account), NO_ACCOUNT_ID in draft.accountIds,
                        TestTags.SEARCH_FILTER_ACCOUNT_PREFIX + NO_ACCOUNT_ID,
                    ) { draft = draft.copy(accountIds = draft.accountIds.toggle(NO_ACCOUNT_ID)) }
                }
                PickerRow(
                    title = stringResource(R.string.search_filter_tags),
                    value = summary(draft.tagNames.map { "#$it" }) ?: stringResource(R.string.search_all_tags),
                    tag = TestTags.SEARCH_FILTER_TAGS,
                    onClick = { picker = SubPicker.TAGS },
                )
            }
            Button(
                onClick = { onApply(draft) },
                enabled = draft.isValid,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag(TestTags.SEARCH_FILTER_APPLY),
            ) { Text(stringResource(R.string.analysis_period_apply)) }
        }
    }

    when (picker) {
        SubPicker.CATEGORIES -> CategoryBottomSheet(
            title = stringResource(R.string.search_filter_categories),
            categories = categories,
            selectedId = null,
            selectedIds = draft.categoryIds,
            onSelect = { id -> draft = draft.copy(categoryIds = draft.categoryIds.toggle(id)) },
            onSelectNone = { draft = draft.copy(categoryIds = emptySet()) },
            noneLabel = allLabel,
            onDismiss = { picker = null },
        )
        SubPicker.TAGS -> TagsBottomSheet(
            tags = tags,
            selectedTagIds = tags.filter { tag -> draft.tagNames.any { it.equals(tag.name, ignoreCase = true) } }
                .map { it.id }.toSet(),
            onToggleTag = { id ->
                tags.find { it.id == id }?.let { tag ->
                    val selected = draft.tagNames.filter { it.equals(tag.name, ignoreCase = true) }
                    draft = draft.copy(
                        tagNames = if (selected.isEmpty()) draft.tagNames + tag.name else draft.tagNames - selected.toSet()
                    )
                }
            },
            onCreateTag = null,
            onDeleteTag = null,
            subtitle = null,
            onDismiss = { picker = null },
        )
        SubPicker.PERIOD -> {
            val zone = ZoneId.systemDefault()
            LopDateRangePicker(
                initialStartMillis = draft.start?.atStartOfDay(zone)?.toInstant()?.toEpochMilli(),
                initialEndMillis = draft.end?.atStartOfDay(zone)?.toInstant()?.toEpochMilli(),
                // Un seul jour touché vaut une période d'un jour ; le sélecteur d'intervalle ne
                // permet pas de fin antérieure au début (CA-02).
                onRangeSelected = { startMillis, endMillis ->
                    val start = startMillis?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
                    val end = endMillis?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() } ?: start
                    if (start != null) draft = draft.copy(start = start, end = end)
                },
                onDismiss = { picker = null },
            )
        }
        null -> Unit
    }
}

/** « Courses, Loisirs +2 » : la sélection d'un sélecteur résumée sur une ligne. `null` si vide. */
@Composable
private fun summary(names: List<String>): String? = when {
    names.isEmpty() -> null
    names.size <= 2 -> names.joinToString(", ")
    else -> names.take(2).joinToString(", ") + " " + stringResource(R.string.search_more, names.size - 2)
}

private fun Set<Long>.toggle(id: Long) = if (id in this) this - id else this + id

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Section(title: Int, chips: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(title),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) { chips() }
    }
}

/** Sélection lisible sans la couleur : coche et fond plein (DESIGN.md § 9). */
@Composable
private fun ToggleChip(
    label: String,
    selected: Boolean,
    tag: String,
    icon: ImageVector? = null,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = when {
            selected -> { { Icon(Icons.Default.Check, null, Modifier.size(18.dp)) } }
            icon != null -> { { Icon(icon, null, Modifier.size(18.dp), tint = tint) } }
            else -> null
        },
        modifier = Modifier.testTag(tag),
    )
}

/**
 * Ligne qui ouvre un sélecteur : intitulé, valeur courante sur une ligne, chevron, et une croix
 * pour effacer quand [onClear] est fourni.
 */
@Composable
private fun PickerRow(
    title: String,
    value: String,
    tag: String,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    onClear: (() -> Unit)? = null,
    clearTag: String? = null,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(MaterialTheme.shapes.small)
            .testTag(tag)
            .clickable(onClickLabel = title, onClick = onClick),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Column(Modifier.weight(1f).padding(start = if (icon != null) 12.dp else 0.dp)) {
                Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (onClear != null) {
                IconButton(onClick = onClear, modifier = clearTag?.let { Modifier.testTag(it) } ?: Modifier) {
                    Icon(Icons.Default.Close, stringResource(R.string.search_date_clear, title), Modifier.size(18.dp))
                }
            } else {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

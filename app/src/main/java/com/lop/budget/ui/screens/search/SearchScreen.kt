package com.lop.budget.ui.screens.search

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Wallet
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lop.budget.ui.common.TestTags
import com.lop.budget.ui.components.CategoryBottomSheet
import com.lop.budget.ui.components.LopDateRangePicker
import com.lop.budget.ui.components.LopScreenScaffold
import com.lop.budget.ui.components.LopSearchBar
import com.lop.budget.ui.components.transactionDayGroups
import com.lop.budget.util.Format
import dev.chrisbanes.haze.HazeState

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onOpenTransaction: (Long) -> Unit,
    snackbarHostState: SnackbarHostState,
    hazeState: HazeState? = null,
    vm: SearchViewModel = hiltViewModel(),
    actionVm: com.lop.budget.ui.common.TransactionActionViewModel = hiltViewModel(LocalContext.current as ComponentActivity)
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val txVersions by actionVm.txVersions.collectAsStateWithLifecycle()

    // Logique de regroupement global Masterclass (en dehors de la LazyColumn)
    val allTxs = remember(state.dayGroups) { state.dayGroups.flatMap { it.transactions } }
    val seriesGroups = remember(allTxs) { allTxs.groupBy { it.transaction.seriesId } }
    val multiOccurrencesSeries = remember(seriesGroups) { 
        seriesGroups.filter { it.key != null && it.value.size > 1 } 
    }
    
    val dayGroupItems = remember(state.dayGroups, multiOccurrencesSeries) {
        val displayedSeries = mutableSetOf<Long>()
        state.dayGroups.map { group ->
            val items = group.transactions.filter { twr ->
                val sid = twr.transaction.seriesId
                if (sid != null && multiOccurrencesSeries.containsKey(sid)) {
                    if (displayedSeries.contains(sid)) false
                    else { displayedSeries.add(sid); true }
                } else true
            }
            group.date to items
        }.filter { it.second.isNotEmpty() }
    }

    var showAccountPicker by remember { mutableStateOf(false) }
    var showCategoryPicker by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }

    LopScreenScaffold(
        title = "Rechercher",
        onBack = onBack,
        navigationIcon = Icons.AutoMirrored.Filled.ArrowBack,
        modifier = Modifier.testTag(TestTags.SCREEN_SEARCH),
        snackbarHost = {
            SnackbarHost(
                snackbarHostState,
                modifier = Modifier.testTag("snackbar_host")
            )
        }
    ) {
        item {
            Column {
                LopSearchBar(
                    value = state.query,
                    onValueChange = vm::onQueryChange,
                    modifier = Modifier.padding(vertical = 8.dp).testTag(TestTags.SEARCH_BAR),
                    placeholder = "Titre, notes..."
                )

                // Filter chips
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    item {
                        FilterChip(
                            selected = state.selectedAccountId != null,
                            onClick = { showAccountPicker = true },
                            modifier = Modifier.testTag(TestTags.SEARCH_CHIP_ACCOUNT),
                            label = {
                                val acc =
                                    state.availableAccounts.find { it.id == state.selectedAccountId }
                                Text(acc?.name ?: "Compte")
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Wallet,
                                    null,
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            trailingIcon = if (state.selectedAccountId != null) {
                                {
                                    IconButton(
                                        onClick = { vm.onAccountFilterChange(null) },
                                        modifier = Modifier.size(18.dp)
                                    ) { Icon(Icons.Default.Close, null) }
                                }
                            } else null
                        )
                    }
                    item {
                        FilterChip(
                            selected = state.selectedCategoryId != null,
                            onClick = { showCategoryPicker = true },
                            modifier = Modifier.testTag(TestTags.SEARCH_CHIP_CATEGORY),
                            label = {
                                val cat =
                                    state.availableCategories.find { it.id == state.selectedCategoryId }
                                Text(cat?.name ?: "Catégorie")
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Category,
                                    null,
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            trailingIcon = if (state.selectedCategoryId != null) {
                                {
                                    IconButton(
                                        onClick = { vm.onCategoryFilterChange(null) },
                                        modifier = Modifier.size(18.dp)
                                    ) { Icon(Icons.Default.Close, null) }
                                }
                            } else null
                        )
                    }
                    item {
                        FilterChip(
                            selected = state.startDate != null,
                            onClick = { showDatePicker = true },
                            modifier = Modifier.testTag(TestTags.SEARCH_CHIP_DATE),
                            label = {
                                if (state.startDate != null) {
                                    Text("${Format.shortDate(state.startDate!!)} - ...")
                                } else Text("Période")
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.CalendarMonth,
                                    null,
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            trailingIcon = if (state.startDate != null) {
                                {
                                    IconButton(
                                        onClick = { vm.onDateRangeChange(null, null) },
                                        modifier = Modifier.size(18.dp)
                                    ) { Icon(Icons.Default.Close, null) }
                                }
                            } else null
                        )
                    }
                }
            }
        }

        if (state.query.isBlank() && state.selectedAccountId == null && state.selectedCategoryId == null && state.startDate == null) {
            item {
                Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Entrez un mot-clé ou utilisez les filtres",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else if (state.dayGroups.isEmpty()) {
            item {
                Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Aucun résultat",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            dayGroupItems.forEach { (date, itemsToDisplay) ->
                // On n'affiche l'en-tête de date que s'il n'y a pas de pile en premier élément
                // Si le premier élément est une pile, elle gère ses propres dates internes.
                val firstIsStack = itemsToDisplay.firstOrNull()?.let { twr ->
                    val sid = twr.transaction.seriesId
                    sid != null && multiOccurrencesSeries.containsKey(sid)
                } ?: false

                if (!firstIsStack) {
                    item {
                        Text(
                            text = Format.fullDate(date.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                }

                items(itemsToDisplay, key = { twr ->
                    val sid = twr.transaction.seriesId
                    if (sid != null && multiOccurrencesSeries.containsKey(sid)) "stack_$sid"
                    else "${twr.transaction.id}_${txVersions[twr.transaction.id] ?: 0}"
                }) { twr ->
                    val sid = twr.transaction.seriesId
                    if (sid != null && multiOccurrencesSeries.containsKey(sid)) {
                        com.lop.budget.ui.components.StackedTransactionGroup(
                            transactions = multiOccurrencesSeries[sid]!!,
                            currency = state.currency,
                            onOpenTransaction = onOpenTransaction,
                            onMaterializeAndOpen = { seriesId, d ->
                                vm.materializeAndOpen(seriesId, d, onOpenTransaction)
                            }
                        )
                    } else {
                        com.lop.budget.ui.components.TransactionRow(
                            tx = twr,
                            currency = state.currency,
                            onOpenTransaction = onOpenTransaction,
                            onMaterializeAndOpen = { seriesId, d ->
                                vm.materializeAndOpen(seriesId, d, onOpenTransaction)
                            },
                            hazeState = hazeState
                        )
                    }
                }
            }
        }
    }

    if (showAccountPicker) {
        ModalBottomSheet(onDismissRequest = { showAccountPicker = false }) {
            AccountList(
                accounts = state.availableAccounts,
                selectedId = state.selectedAccountId,
                onSelect = { id ->
                    vm.onAccountFilterChange(id)
                    showAccountPicker = false
                }
            )
        }
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

    if (showDatePicker) {
        LopDateRangePicker(
            initialStartMillis = state.startDate,
            initialEndMillis = state.endDate,
            onRangeSelected = { start, end ->
                vm.onDateRangeChange(start, end)
            },
            onDismiss = { showDatePicker = false }
        )
    }
}

@Composable
fun AccountList(
    accounts: List<com.lop.budget.data.local.entity.AccountEntity>,
    selectedId: Long?,
    onSelect: (Long) -> Unit
) {
    LazyColumn(Modifier
        .fillMaxWidth()
        .padding(bottom = 32.dp)) {
        item {
            Text(
                "Sélectionner un compte",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.titleLarge
            )
        }
        items(accounts) { acc ->
            ListItem(
                headlineContent = { Text(acc.name) },
                leadingContent = {
                    com.lop.budget.ui.components.CircleIcon(
                        icon = com.lop.budget.util.IconMapper.get(acc.icon),
                        tint = Color(acc.colorArgb),
                        background = Color(acc.colorArgb).copy(alpha = 0.1f),
                        size = 32.dp
                    )
                },
                modifier = Modifier.clickable { onSelect(acc.id) },
                trailingContent = { if (acc.id == selectedId) Icon(Icons.Default.Check, null) }
            )
        }
    }
}

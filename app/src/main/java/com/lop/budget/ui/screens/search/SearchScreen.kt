package com.lop.budget.ui.screens.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Wallet
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lop.budget.R
import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.ui.components.CategoryBottomSheet
import com.lop.budget.ui.components.LopScreenScaffold
import com.lop.budget.ui.components.LopSearchBar
import com.lop.budget.ui.components.transactionDayGroups
import com.lop.budget.util.Format
import dev.chrisbanes.haze.HazeState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onOpenTransaction: (Long) -> Unit,
    onPreviewTransaction: (TransactionWithRelations, String) -> Unit,
    hazeState: HazeState? = null,
    vm: SearchViewModel = hiltViewModel()
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    
    var showAccountPicker by remember { mutableStateOf(false) }
    var showCategoryPicker by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }

    val txDeletedMsg = stringResource(R.string.tx_deleted_snackbar)
    val undoMsg = stringResource(R.string.undo)

    LopScreenScaffold(
        title = "Rechercher",
        onBack = onBack,
        navigationIcon = Icons.AutoMirrored.Filled.ArrowBack,
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) {
        item {
            Column {
                LopSearchBar(
                    value = state.query,
                    onValueChange = vm::onQueryChange,
                    modifier = Modifier.padding(vertical = 8.dp),
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
                            label = { 
                                val acc = state.availableAccounts.find { it.id == state.selectedAccountId }
                                Text(acc?.name ?: "Compte") 
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
                    item {
                        FilterChip(
                            selected = state.startDate != null,
                            onClick = { showDatePicker = true },
                            label = { 
                                if (state.startDate != null) {
                                    Text("${Format.shortDate(state.startDate!!)} - ...")
                                } else Text("Période")
                            },
                            leadingIcon = { Icon(Icons.Default.CalendarMonth, null, modifier = Modifier.size(18.dp)) },
                            trailingIcon = if (state.startDate != null) {
                                { IconButton(onClick = { vm.onDateRangeChange(null, null) }, modifier = Modifier.size(18.dp)) { Icon(Icons.Default.Close, null) } }
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
            transactionDayGroups(
                dayGroups = state.dayGroups,
                currency = state.currency,
                txVersions = state.txVersions,
                onOpenTransaction = onOpenTransaction,
                onMaterializeAndOpen = { sid, date -> vm.materializeAndOpen(sid, date, onOpenTransaction) },
                onTogglePaid = vm::togglePaid,
                onDeleteRequest = { /* Handle recurring delete if needed */ },
                onPreviewTransaction = { tx, cur -> onPreviewTransaction(tx, cur) },
                onDeleteSimple = { id -> vm.deleteWithUndo(id, snackbarHostState, txDeletedMsg, undoMsg) },
                hazeState = hazeState
            )
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
        val datePickerState = rememberDateRangePickerState()
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    vm.onDateRangeChange(datePickerState.selectedStartDateMillis, datePickerState.selectedEndDateMillis)
                    showDatePicker = false
                }) { Text("OK") }
            }
        ) {
            DateRangePicker(state = datePickerState, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
fun AccountList(
    accounts: List<com.lop.budget.data.local.entity.AccountEntity>,
    selectedId: Long?,
    onSelect: (Long) -> Unit
) {
    LazyColumn(Modifier.fillMaxWidth().padding(bottom = 32.dp)) {
        item { Text("Sélectionner un compte", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.titleLarge) }
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


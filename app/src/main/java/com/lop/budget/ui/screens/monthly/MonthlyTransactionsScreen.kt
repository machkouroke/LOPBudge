package com.lop.budget.ui.screens.monthly

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Wallet
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lop.budget.R
import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.domain.model.SeriesDeletionMode
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.ui.components.CategoryBottomSheet
import com.lop.budget.ui.components.LopScreenScaffold
import com.lop.budget.ui.components.LopSearchBar
import com.lop.budget.ui.components.RecurringDeleteChoice
import com.lop.budget.ui.components.RecurringDeleteSheet
import com.lop.budget.ui.components.transactionDayGroups
import com.lop.budget.ui.theme.LopTheme
import com.lop.budget.util.Format
import dev.chrisbanes.haze.HazeState
import java.time.format.TextStyle
import java.util.Locale

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
    val deleteRequest by actionVm.deleteRequest.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showAccountPicker by remember { mutableStateOf(false) }
    var showCategoryPicker by remember { mutableStateOf(false) }

    val title = stringResource(R.string.monthly_transactions_title)
    
    val txDeletedMsg = stringResource(R.string.tx_deleted_snackbar)
    val undoMsg = stringResource(R.string.undo)


    val ext = LopTheme.extended
    val accent = if (state.type == TransactionType.INCOME) ext.income else ext.expense

    val pageTitle = if (state.isAnalyticsMode) "Analyses" else stringResource(R.string.monthly_transactions_title)

    val top = state.breakdown.take(6)
    val othersTotal = state.breakdown.drop(6).sumOf { it.total }
    val othersText = stringResource(R.string.others)
    val slices = remember(top, othersTotal, othersText) {
        buildList {
            top.forEach { add(com.lop.budget.ui.components.DonutSlice(it.total, Color(it.colorArgb), it.name)) }
            if (othersTotal > 0) add(com.lop.budget.ui.components.DonutSlice(othersTotal, Color(0xFF9E9E9E), othersText))
        }
    }

    LopScreenScaffold(
        title = pageTitle,
        onBack = onBack,
        navigationIcon = Icons.AutoMirrored.Filled.ArrowBack,
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) {
        item {
            val monthStr = state.month.month.getDisplayName(TextStyle.FULL, Locale.FRANCE).replaceFirstChar { it.uppercase() }
            val dateRange = if (state.isAnalyticsMode) {
                val end = state.month.atEndOfMonth()
                "1 ${monthStr.lowercase()} ${state.month.year} – ${end.dayOfMonth} ${monthStr.lowercase()} ${state.month.year}"
            } else {
                "$monthStr ${state.month.year}"
            }
            Text(
                dateRange,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        if (state.isAnalyticsMode) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    InsightToggle("Tous", state.filter == PaidFilter.ALL, accent, modifier = Modifier.weight(1f)) { vm.setFilter(PaidFilter.ALL) }
                    InsightToggle("Payé", state.filter == PaidFilter.PAID, accent, modifier = Modifier.weight(1f)) { vm.setFilter(PaidFilter.PAID) }
                    InsightToggle("Non payé", state.filter == PaidFilter.PLANNED, accent, modifier = Modifier.weight(1f)) { vm.setFilter(PaidFilter.PLANNED) }
                }
            }

            item {
                com.lop.budget.ui.components.FloatingCard(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        if (slices.isEmpty()) {
                            Text(stringResource(R.string.monthly_no_data), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(40.dp))
                        } else {
                            com.lop.budget.ui.components.DonutChart(slices = slices) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("Total", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(
                                        Format.money(state.total, state.currency),
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = accent,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            items(state.breakdown, key = { it.name }) { b ->
                BreakdownChip(
                    name = b.name,
                    amount = b.total,
                    percentage = (b.share * 100).toInt(),
                    color = Color(b.colorArgb),
                    currency = state.currency,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            item { Spacer(Modifier.height(16.dp)) }
        }

        if (!state.isAnalyticsMode) {
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
                                onClick = { 
                                    val next = when(state.type) {
                                        null -> TransactionType.EXPENSE
                                        TransactionType.EXPENSE -> TransactionType.INCOME
                                        TransactionType.INCOME -> null
                                    }
                                    vm.setType(next)
                                },
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
                                onClick = { 
                                    val next = when(state.filter) {
                                        PaidFilter.ALL -> PaidFilter.PAID
                                        PaidFilter.PAID -> PaidFilter.PLANNED
                                        PaidFilter.PLANNED -> PaidFilter.ALL
                                    }
                                    vm.setFilter(next)
                                },
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
                            TextButton(onClick = { onNavigateToSearch(state.searchQuery) }) {
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
        }

        // Liste centralisée
        transactionDayGroups(
            dayGroups = state.dayGroups,
            currency = state.currency,
            txVersions = txVersions,
            onOpenTransaction = onOpenTransaction,
            onMaterializeAndOpen = { sid, date -> vm.materializeAndOpen(sid, date, onOpenTransaction) },
            hazeState = hazeState
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

    if (deleteRequest != null) {
        val toDelete = deleteRequest!!
        if (toDelete.transaction.seriesId != null) {
            RecurringDeleteSheet(
                onDismiss = { actionVm.dismissDeleteRequest() },
                showFutureOnly = true,
                onChoose = { choice ->
                    actionVm.dismissDeleteRequest()
                    when (choice) {
                        RecurringDeleteChoice.THIS_OCCURRENCE -> {
                            actionVm.deleteWithUndo(toDelete, snackbarHostState, txDeletedMsg, undoMsg)
                        }
                        RecurringDeleteChoice.FUTURE_ONLY -> {
                            toDelete.transaction.seriesId?.let { 
                                actionVm.deleteSeriesWithUndo(it, SeriesDeletionMode.FUTURE, toDelete.transaction.date, snackbarHostState, txDeletedMsg, undoMsg) 
                            }
                        }
                        RecurringDeleteChoice.ALL_SERIES -> {
                            toDelete.transaction.seriesId?.let { 
                                actionVm.deleteSeriesWithUndo(it, SeriesDeletionMode.ALL, null, snackbarHostState, txDeletedMsg, undoMsg) 
                            }
                        }
                    }
                }
            )
        } else {
            androidx.compose.runtime.SideEffect {
                actionVm.deleteWithUndo(toDelete, snackbarHostState, txDeletedMsg, undoMsg)
                actionVm.dismissDeleteRequest()
            }
        }
    }
}

@Composable
fun BreakdownChip(
    name: String,
    amount: Double,
    percentage: Int,
    color: Color,
    currency: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.height(54.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                // Petit cercle de pourcentage
                Text(
                    text = "$percentage%",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                    color = color,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(verticalArrangement = Arrangement.Center) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = Format.money(amount, currency),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun InsightToggle(
    label: String,
    selected: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier.clickable { onClick() },
        shape = MaterialTheme.shapes.small,
        color = if (selected) accent.copy(alpha = 0.1f) else Color.Transparent,
        border = if (selected) androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = 0.3f)) else null
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            textAlign = TextAlign.Center
        )
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

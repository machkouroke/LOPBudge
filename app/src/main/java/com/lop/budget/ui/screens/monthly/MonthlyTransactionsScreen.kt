package com.lop.budget.ui.screens.monthly

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
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
import com.lop.budget.ui.components.*
import com.lop.budget.ui.navigation.Routes
import com.lop.budget.ui.screens.search.AccountList
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
    onPreviewTransaction: (TransactionWithRelations, String) -> Unit,
    onNavigateToSearch: (String) -> Unit, // Callback to navigate to global search
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    hazeState: HazeState? = null,
    vm: MonthlyTransactionsViewModel = hiltViewModel(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val ext = LopTheme.extended
    val context = LocalContext.current

    var showAccountPicker by remember { mutableStateOf(false) }
    var showCategoryPicker by remember { mutableStateOf(false) }

    val title = stringResource(R.string.monthly_transactions_title)
    val accent = if (state.type == TransactionType.EXPENSE) ext.expense else if (state.type == TransactionType.INCOME) ext.income else MaterialTheme.colorScheme.primary

    var showDeleteConfirmForTx by remember { mutableStateOf<TransactionWithRelations?>(null) }
    val txDeletedMsg = stringResource(R.string.tx_deleted_snackbar)
    val undoMsg = stringResource(R.string.undo)

    LopScreenScaffold(
        title = title,
        onBack = onBack,
        navigationIcon = Icons.AutoMirrored.Filled.ArrowBack,
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) {
        item {
            Text(
                "${state.month.month.getDisplayName(TextStyle.FULL, Locale.FRANCE).replaceFirstChar { it.uppercase() }} ${state.month.year}",
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

        // Liste centralisée
        transactionDayGroups(
            dayGroups = state.dayGroups,
            currency = state.currency,
            txVersions = state.txVersions,
            onOpenTransaction = onOpenTransaction,
            onMaterializeAndOpen = { sid, date -> vm.materializeAndOpen(sid, date, onOpenTransaction) },
            onTogglePaid = vm::togglePaid,
            onDeleteRequest = { showDeleteConfirmForTx = it },
            onPreviewTransaction = onPreviewTransaction,
            onDeleteSimple = { id -> vm.deleteWithUndo(id, snackbarHostState, txDeletedMsg, undoMsg) },
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

    if (showDeleteConfirmForTx != null) {
        val toDelete = showDeleteConfirmForTx!!
        RecurringDeleteSheet(
            onDismiss = { showDeleteConfirmForTx = null },
            showFutureOnly = true,
            onChoose = { choice ->
                showDeleteConfirmForTx = null
                when (choice) {
                    RecurringDeleteChoice.THIS_OCCURRENCE -> {
                        vm.deleteWithUndo(toDelete.transaction.id, snackbarHostState, context.getString(R.string.tx_deleted_snackbar), context.getString(R.string.undo))
                    }
                    RecurringDeleteChoice.FUTURE_ONLY -> {
                        toDelete.transaction.seriesId?.let { 
                            vm.deleteSeriesWithUndo(it, SeriesDeletionMode.FUTURE, toDelete.transaction.date, snackbarHostState, context.getString(R.string.tx_deleted_snackbar), context.getString(R.string.undo)) 
                        }
                    }
                    RecurringDeleteChoice.ALL_SERIES -> {
                        toDelete.transaction.seriesId?.let { 
                            vm.deleteSeriesWithUndo(it, SeriesDeletionMode.ALL, null, snackbarHostState, context.getString(R.string.tx_deleted_snackbar), context.getString(R.string.undo)) 
                        }
                    }
                }
            }
        )
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
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.clickableNoRipple(onClick),
        shape = MaterialTheme.shapes.small,
        color = if (selected) accent.copy(alpha = 0.1f) else Color.Transparent,
        border = if (selected) androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = 0.3f)) else null
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

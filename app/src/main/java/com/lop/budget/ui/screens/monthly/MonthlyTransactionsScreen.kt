package com.lop.budget.ui.screens.monthly

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    hazeState: HazeState? = null,
    vm: MonthlyTransactionsViewModel = hiltViewModel(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val ext = LopTheme.extended
    val context = LocalContext.current

    val title = if (state.type == TransactionType.EXPENSE) stringResource(R.string.expense) else stringResource(R.string.income)
    val accent = if (state.type == TransactionType.EXPENSE) ext.expense else ext.income

    val top = state.breakdown
    val slices = buildList {
        top.take(8).forEach { add(DonutSlice(it.total, Color(it.colorArgb), it.name)) }
    }

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
            )
        }

        // Filtre payé
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilterChip(stringResource(R.string.monthly_filter_all), state.filter == PaidFilter.ALL, Modifier.weight(1f), accent) { vm.setFilter(PaidFilter.ALL) }
                FilterChip(stringResource(R.string.monthly_filter_paid), state.filter == PaidFilter.PAID, Modifier.weight(1f), accent) { vm.setFilter(PaidFilter.PAID) }
                FilterChip(stringResource(R.string.monthly_filter_planned), state.filter == PaidFilter.PLANNED, Modifier.weight(1f), accent) { vm.setFilter(PaidFilter.PLANNED) }
            }
        }

        // Insights + Chart
        item {
            FloatingCard(Modifier.fillMaxWidth()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        InsightToggle("Catégories", state.insightMode == InsightMode.CATEGORY, accent) { vm.setInsightMode(InsightMode.CATEGORY) }
                        Spacer(Modifier.width(8.dp))
                        InsightToggle("Étiquettes", state.insightMode == InsightMode.TAG, accent) { vm.setInsightMode(InsightMode.TAG) }
                    }

                    if (state.transactions.isEmpty()) {
                        Text(stringResource(R.string.monthly_no_data), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(40.dp))
                    } else {
                        DonutChart(slices = slices) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(stringResource(R.string.total), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

        // Breakdown en Grille (3 colonnes)
        item {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                maxItemsInEachRow = 3
            ) {
                state.breakdown.forEach { item ->
                    BreakdownChip(
                        name = item.name,
                        amount = item.total,
                        percentage = (item.share * 100).toInt(),
                        color = Color(item.colorArgb),
                        currency = state.currency,
                        modifier = Modifier.weight(1f)
                    )
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
            onPreviewTransaction = { onPreviewTransaction(it, state.currency) },
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
private fun FilterChip(
    label: String,
    selected: Boolean,
    modifier: Modifier,
    accent: Color,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier.clickableNoRipple(onClick),
        shape = CircleShape,
        color = if (selected) accent.copy(alpha = 0.22f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
    ) {
        Text(
            label,
            color = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(vertical = 12.dp),
        )
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

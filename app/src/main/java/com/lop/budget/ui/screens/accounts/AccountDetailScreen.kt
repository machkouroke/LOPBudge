package com.lop.budget.ui.screens.accounts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lop.budget.R
import com.lop.budget.ui.components.CircleIcon
import com.lop.budget.ui.components.FloatingCard
import com.lop.budget.ui.components.SimpleLineChart
import com.lop.budget.ui.components.LopScreenScaffold
import com.lop.budget.ui.components.TransactionRow
import com.lop.budget.ui.navigation.Routes
import com.lop.budget.util.Format
import com.lop.budget.util.IconMapper
import dev.chrisbanes.haze.HazeState

@Composable
fun AccountDetailScreen(
    onBack: () -> Unit,
    onEdit: (Long) -> Unit,
    onOpenTransaction: (Long) -> Unit,
    onPreviewTransaction: (com.lop.budget.data.local.entity.TransactionWithRelations, String) -> Unit,
    hazeState: HazeState? = null,
    vm: AccountDetailViewModel = hiltViewModel()
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val account = state.account
    val snackbarHostState = remember { SnackbarHostState() }
    
    val txDeletedMsg = stringResource(R.string.tx_deleted_snackbar)
    val undoMsg = stringResource(R.string.undo)

    LopScreenScaffold(
        title = "Compte",
        onBack = onBack,
        navigationIcon = Icons.AutoMirrored.Filled.ArrowBack,
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) {
        if (account == null) {
            item { Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
        } else {
            val color = Color(account.colorArgb)
            
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircleIcon(
                        icon = IconMapper.get(account.icon),
                        tint = color,
                        background = color.copy(alpha = 0.15f),
                        size = 64.dp
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(account.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Compte bancaire • ${account.bankName ?: "Standard"}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Text(Format.money(state.balance, state.currency), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text("Mis à jour aujourd'hui", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            item {
                FloatingCard(Modifier.fillMaxWidth()) {
                    Column {
                        Text("Chronologie de l'équilibre", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(24.dp))
                        if (state.history.isNotEmpty()) {
                            SimpleLineChart(
                                points = state.history,
                                modifier = Modifier.fillMaxWidth().height(150.dp),
                                lineColor = color
                            )
                        } else {
                            Box(Modifier.fillMaxWidth().height(150.dp).background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.medium), contentAlignment = Alignment.Center) {
                                Text("Pas assez de données", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }

            if (state.upcomingTransactions.isNotEmpty()) {
                item { SectionHeader("Transactions à venir") }
                items(state.upcomingTransactions, key = { it.transaction.id }) { twr ->
                    TransactionRow(
                        tx = twr,
                        currency = state.currency,
                        onOpenTransaction = onOpenTransaction,
                        onMaterializeAndOpen = { sid, date -> vm.materializeAndOpen(sid, date, onOpenTransaction) },
                        onTogglePaid = vm::togglePaid,
                        onDeleteRequest = { vm.deleteWithUndo(it.transaction.id, snackbarHostState, txDeletedMsg, undoMsg) },
                        onPreviewTransaction = { tx, cur -> onPreviewTransaction(tx, cur) },
                        onDeleteSimple = { vm.deleteWithUndo(it, snackbarHostState, txDeletedMsg, undoMsg) },
                        hazeState = hazeState
                    )
                }
            }

            if (state.recentTransactions.isNotEmpty()) {
                item { SectionHeader("Transactions récentes") }
                items(state.recentTransactions, key = { it.transaction.id }) { twr ->
                    TransactionRow(
                        tx = twr,
                        currency = state.currency,
                        onOpenTransaction = onOpenTransaction,
                        onMaterializeAndOpen = { sid, date -> vm.materializeAndOpen(sid, date, onOpenTransaction) },
                        onTogglePaid = vm::togglePaid,
                        onDeleteRequest = { vm.deleteWithUndo(it.transaction.id, snackbarHostState, txDeletedMsg, undoMsg) },
                        onPreviewTransaction = { tx, cur -> onPreviewTransaction(tx, cur) },
                        onDeleteSimple = { vm.deleteWithUndo(it, snackbarHostState, txDeletedMsg, undoMsg) },
                        hazeState = hazeState
                    )
                }
            }
            
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(IconMapper.get("account_balance") as androidx.compose.ui.graphics.vector.ImageVector, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("Inclure dans la valeur nette", style = MaterialTheme.typography.bodyMedium)
                    }
                    Switch(checked = account.includeInTotal, onCheckedChange = { /* Update */ })
                }
            }

            item {
                Button(
                    onClick = { onEdit(account.id) },
                    modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Icon(Icons.Default.Edit, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Modifier le compte")
                }
            }
        }
    }
}

@Composable
fun SectionHeader(title: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        TextButton(onClick = { /* Show all */ }) { Text("Tout montrer >") }
    }
}

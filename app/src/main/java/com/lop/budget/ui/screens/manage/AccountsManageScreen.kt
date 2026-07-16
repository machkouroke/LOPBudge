package com.lop.budget.ui.screens.manage

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lop.budget.R
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.ui.components.CircleIcon
import com.lop.budget.ui.components.FloatingCard
import com.lop.budget.ui.components.LopScreenScaffold
import com.lop.budget.ui.components.clickableNoRipple
import com.lop.budget.util.Format
import com.lop.budget.util.IconMapper

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsManageScreen(
    onBack: () -> Unit,
    onAddAccount: () -> Unit,
    onEditAccount: (Long) -> Unit,
    vm: AccountsManageViewModel = hiltViewModel()
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    var accountToDelete by remember { mutableStateOf<AccountEntity?>(null) }

    if (accountToDelete != null) {
        AlertDialog(
            onDismissRequest = { accountToDelete = null },
            title = { Text("Supprimer le compte ?") },
            text = { Text("Cette action est irréversible. Toutes les transactions liées seront orphelines.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.deleteAccount(accountToDelete!!.id)
                        accountToDelete = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Supprimer")
                }
            },
            dismissButton = {
                TextButton(onClick = { accountToDelete = null }) {
                    Text("Annuler")
                }
            }
        )
    }

    LopScreenScaffold(
        title = "Gérer les comptes",
        onBack = onBack,
        navigationIcon = Icons.AutoMirrored.Filled.ArrowBack,
        bottomBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                FloatingActionButton(
                    onClick = onAddAccount,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Ajouter un compte")
                }
            }
        }
    ) {
        if (state.activeAccounts.isNotEmpty()) {
            item {
                Text(
                    "Comptes actifs",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
            items(state.activeAccounts, key = { it.id }) { account ->
                AccountManageRow(
                    account = account,
                    currency = state.currency,
                    onEdit = { onEditAccount(account.id) },
                    onArchive = { vm.toggleArchive(account) },
                    onDelete = { accountToDelete = account }
                )
            }
        }

        if (state.archivedAccounts.isNotEmpty()) {
            item {
                Spacer(Modifier.height(16.dp))
                Text(
                    "Comptes archivés",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            items(state.archivedAccounts, key = { it.id }) { account ->
                AccountManageRow(
                    account = account,
                    currency = state.currency,
                    onEdit = { onEditAccount(account.id) },
                    onArchive = { vm.toggleArchive(account) },
                    onDelete = { accountToDelete = account },
                    isArchived = true
                )
            }
        }

        if (state.activeAccounts.isEmpty() && state.archivedAccounts.isEmpty()) {
            item {
                Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Aucun compte configuré",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun AccountManageRow(
    account: AccountEntity,
    currency: String,
    onEdit: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    isArchived: Boolean = false
) {
    val color = Color(account.colorArgb)
    FloatingCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickableNoRipple(onEdit),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (isArchived) 0.2f else 0.4f)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircleIcon(
                icon = IconMapper.get(account.icon),
                tint = color,
                background = color.copy(alpha = 0.15f)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    account.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isArchived) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                )
                if (account.bankName != null) {
                    Text(
                        account.bankName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            IconButton(onClick = onArchive) {
                Icon(
                    imageVector = if (isArchived) Icons.Default.Unarchive else Icons.Default.Archive,
                    contentDescription = if (isArchived) "Désarchiver" else "Archiver",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }

            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Supprimer",
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

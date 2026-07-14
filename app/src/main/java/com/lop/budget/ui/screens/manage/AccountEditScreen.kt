package com.lop.budget.ui.screens.manage

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lop.budget.domain.model.AccountType
import com.lop.budget.ui.components.CircleIcon
import com.lop.budget.ui.components.FloatingCard
import com.lop.budget.ui.components.LopScreenScaffold
import com.lop.budget.ui.components.clickableNoRipple
import com.lop.budget.util.IconMapper

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountEditScreen(
    onBack: () -> Unit,
    vm: AccountFormViewModel = hiltViewModel()
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    var showTypeSheet by remember { mutableStateOf(false) }
    var showBankSheet by remember { mutableStateOf(false) }
    var showIconSheet by remember { mutableStateOf(false) }

    if (showTypeSheet) {
        AccountTypeBottomSheet(
            selected = state.type,
            onSelect = {
                vm.onTypeChange(it)
                showTypeSheet = false
            },
            onDismiss = { showTypeSheet = false }
        )
    }

    if (showBankSheet) {
        BankSelectorBottomSheet(
            banks = state.knownBanks,
            onSelect = {
                vm.onBankSelected(it)
                showBankSheet = false
            },
            onDismiss = { showBankSheet = false }
        )
    }

    if (showIconSheet) {
        IconSelectorBottomSheet(
            query = state.searchQuery,
            results = state.iconResults,
            currentIcon = state.iconName,
            onQueryChange = vm::onSearchQueryChange,
            onSelect = {
                vm.onIconChange(it)
                showIconSheet = false
            },
            onReset = { vm.onIconChange("account_balance") },
            onDismiss = { showIconSheet = false }
        )
    }

    LopScreenScaffold(
        title = if (state.isEdit) "Modifier le compte" else "Nouveau compte",
        onBack = onBack,
        navigationIcon = Icons.AutoMirrored.Filled.ArrowBack,
        bottomBar = {
            Box(Modifier.fillMaxWidth().padding(20.dp)) {
                Button(
                    onClick = { vm.save(onBack) },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = MaterialTheme.shapes.medium,
                    enabled = state.name.isNotBlank() && !state.isSaving
                ) {
                    if (state.isSaving) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                    } else {
                        Text("Enregistrer")
                    }
                }
            }
        }
    ) {
        if (!state.isLoaded) {
            item { Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
        } else {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    // Section Identité
                    FloatingCard(Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            OutlinedTextField(
                                value = state.name,
                                onValueChange = vm::onNameChange,
                                label = { Text("Nom du compte") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )

                            // Type de compte
                            SelectorField(
                                label = "Type de compte",
                                value = when(state.type) {
                                    AccountType.CHECKING -> "Bancaire / Courant"
                                    AccountType.CASH -> "Espèces / Cash"
                                    AccountType.SAVINGS -> "Épargne"
                                    AccountType.CARD -> "Carte prépayée"
                                    AccountType.CRYPTO -> "Crypto-monnaies"
                                    AccountType.INVESTMENT -> "Investissement"
                                    AccountType.OTHER -> "Autre"
                                },
                                onClick = { showTypeSheet = true }
                            )

                            if (state.type == AccountType.CHECKING) {
                                SelectorField(
                                    label = "Établissement bancaire",
                                    value = state.bankName.ifBlank { "Choisir une banque..." },
                                    onClick = { showBankSheet = true }
                                )
                            }
                        }
                    }

                    // Section Solde & Options
                    FloatingCard(Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            OutlinedTextField(
                                value = state.initialBalance,
                                onValueChange = vm::onInitialBalanceChange,
                                label = { Text("Solde initial") },
                                modifier = Modifier.fillMaxWidth(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                singleLine = true
                            )

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Inclure dans le solde total", style = MaterialTheme.typography.bodyLarge)
                                    Text("Impacte le solde global de l'application", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(checked = state.includeInTotal, onCheckedChange = vm::onIncludeInTotalChange)
                            }
                        }
                    }

                    // Section Look (Icon & Color)
                    FloatingCard(Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text("Apparence", style = MaterialTheme.typography.titleMedium)
                            
                            // Couleur
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                val colors = listOf(0xFF9C27B0, 0xFF2196F3, 0xFF4CAF50, 0xFFFFC107, 0xFFF44336, 0xFF607D8B)
                                colors.forEach { c ->
                                    val color = Color(c.toInt())
                                    Box(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .clip(CircleShape)
                                            .background(color)
                                            .border(2.dp, if (state.colorArgb == c.toInt()) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape)
                                            .clickableNoRipple { vm.onColorChange(c.toInt()) },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (state.colorArgb == c.toInt()) Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(20.dp))
                                    }
                                }
                            }

                            // Icône (Clickable preview)
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickableNoRipple { showIconSheet = true }) {
                                CircleIcon(
                                    icon = IconMapper.get(state.iconName),
                                    tint = Color(state.colorArgb),
                                    background = Color(state.colorArgb).copy(alpha = 0.15f),
                                    size = 56.dp
                                )
                                Spacer(Modifier.width(16.dp))
                                Column {
                                    Text("Icône du compte", style = MaterialTheme.typography.bodyLarge)
                                    Text("Cliquer pour modifier", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Spacer(Modifier.weight(1f))
                                Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }

                    if (state.isEdit) {
                        OutlinedTextField(
                            value = state.comment,
                            onValueChange = vm::onCommentChange,
                            label = { Text("Commentaire (optionnel)") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SelectorField(label: String, value: String, onClick: () -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Surface(
            modifier = Modifier.fillMaxWidth().clickableNoRipple(onClick),
            shape = MaterialTheme.shapes.small,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
            color = Color.Transparent
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(value, style = MaterialTheme.typography.bodyLarge)
                Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountTypeBottomSheet(
    selected: AccountType,
    onSelect: (AccountType) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp)
        ) {
            item {
                Text(
                    "Type de compte",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.titleLarge
                )
            }
            items(AccountType.entries) { type ->
                val label = when(type) {
                    AccountType.CHECKING -> "Bancaire / Courant"
                    AccountType.CASH -> "Espèces / Cash"
                    AccountType.SAVINGS -> "Épargne"
                    AccountType.CARD -> "Carte prépayée"
                    AccountType.CRYPTO -> "Crypto-monnaies"
                    AccountType.INVESTMENT -> "Investissement"
                    AccountType.OTHER -> "Autre"
                }
                ListItem(
                    headlineContent = { Text(label) },
                    modifier = Modifier.clickable { onSelect(type) },
                    trailingContent = {
                        if (type == selected) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BankSelectorBottomSheet(
    banks: List<com.lop.budget.data.repository.IconSearchRepository.BankInfo>,
    onSelect: (com.lop.budget.data.repository.IconSearchRepository.BankInfo?) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp)) {
            item {
                Text("Choisir une banque", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.titleLarge)
            }
            items(banks) { bank ->
                ListItem(
                    headlineContent = { Text(bank.name) },
                    leadingContent = {
                        CircleIcon(
                            icon = "https://logo.clearbit.com/${bank.domain}",
                            tint = MaterialTheme.colorScheme.primary,
                            background = MaterialTheme.colorScheme.surfaceVariant,
                            size = 32.dp
                        )
                    },
                    modifier = Modifier.clickable { onSelect(bank) }
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("Autre établissement") },
                    modifier = Modifier.clickable { onSelect(null) }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IconSelectorBottomSheet(
    query: String,
    results: List<com.lop.budget.data.repository.IconResult>,
    currentIcon: String,
    onQueryChange: (String) -> Unit,
    onSelect: (String) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            Text("Choisir une icône", style = MaterialTheme.typography.titleLarge)
            Text(
                "Trouvez le logo de votre banque ou d'une entreprise et l'utiliser comme icône.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp)
            )

            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Rechercher (ex: Revolut, Amazon...)") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Default.Close, null)
                        }
                    }
                },
                singleLine = true,
                shape = MaterialTheme.shapes.medium
            )

            Spacer(Modifier.height(24.dp))

            LazyColumn(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(results) { res ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .clickable { onSelect(res.iconName) }
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircleIcon(
                            icon = IconMapper.get(res.iconName),
                            tint = MaterialTheme.colorScheme.primary,
                            background = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            size = 48.dp
                        )
                        Spacer(Modifier.width(16.dp))
                        Text(
                            text = res.label,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f)
                        )
                        if (currentIcon == res.iconName) {
                            Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TextButton(
                    onClick = {
                        onReset()
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Reset", color = MaterialTheme.colorScheme.error)
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Abandonner")
                }
                Button(
                    onClick = onDismiss, // Just close, search is real-time
                    modifier = Modifier.weight(1.2f),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Text("Chercher")
                }
            }
        }
    }
}

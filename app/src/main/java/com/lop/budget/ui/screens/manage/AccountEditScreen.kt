package com.lop.budget.ui.screens.manage

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
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
                            Column {
                                Text("Type de compte", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(8.dp))
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(AccountType.entries) { type ->
                                        FilterChip(
                                            selected = state.type == type,
                                            onClick = { vm.onTypeChange(type) },
                                            label = { Text(type.name) } // TODO: Strings localisées
                                        )
                                    }
                                }
                            }

                            if (state.type == AccountType.CHECKING) {
                                OutlinedTextField(
                                    value = state.bankName,
                                    onValueChange = vm::onBankNameChange,
                                    label = { Text("Nom de la banque") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
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

                            // Icône
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                CircleIcon(
                                    icon = IconMapper.get(state.iconName),
                                    tint = Color(state.colorArgb),
                                    background = Color(state.colorArgb).copy(alpha = 0.15f),
                                    size = 56.dp
                                )
                                
                                OutlinedTextField(
                                    value = "", // On n'affiche pas le texte brut
                                    onValueChange = vm::onSearchQueryChange,
                                    placeholder = { Text("Rechercher une icône...") },
                                    modifier = Modifier.weight(1f),
                                    leadingIcon = { Icon(Icons.Default.Search, null) },
                                    singleLine = true
                                )
                            }

                            // Résultats recherche d'icônes
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                items(state.iconResults) { res ->
                                    Box(
                                        modifier = Modifier
                                            .size(48.dp)
                                            .clip(MaterialTheme.shapes.small)
                                            .background(if (state.iconName == res.iconName) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                            .clickableNoRipple { vm.onIconChange(res.iconName) },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            IconMapper.get(res.iconName),
                                            null,
                                            tint = if (state.iconName == res.iconName) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
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

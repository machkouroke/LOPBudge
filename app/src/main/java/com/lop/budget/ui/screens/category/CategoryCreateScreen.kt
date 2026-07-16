package com.lop.budget.ui.screens.category

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lop.budget.R
import com.lop.budget.data.local.entity.CategoryEntity
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.ui.components.CircleIcon
import com.lop.budget.ui.components.FloatingCard
import com.lop.budget.ui.components.LopScreenScaffold
import com.lop.budget.ui.components.clickableNoRipple
import com.lop.budget.util.IconMapper

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryCreateScreen(
    onBack: () -> Unit,
    vm: CategoryFormViewModel = hiltViewModel()
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    var showParentSheet by remember { mutableStateOf(false) }

    if (showParentSheet) {
        CategoryParentBottomSheet(
            categories = state.availableParents,
            selectedId = state.parentCategoryId,
            onSelect = {
                vm.onParentChange(it)
                showParentSheet = false
            },
            onDismiss = { showParentSheet = false }
        )
    }

    LopScreenScaffold(
        title = if (state.isEdit) "Modifier la catégorie" else "Nouvelle catégorie",
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
                    if (state.isSaving) CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(24.dp))
                    else Text("Enregistrer")
                }
            }
        }
    ) {
        if (!state.isLoaded) {
            item { Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
        } else {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    FloatingCard(Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            OutlinedTextField(
                                value = state.name,
                                onValueChange = vm::onNameChange,
                                label = { Text("Nom") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )

                            Column {
                                Text("Type", style = MaterialTheme.typography.labelMedium)
                                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    FilterChip(
                                        selected = state.type == TransactionType.EXPENSE,
                                        onClick = { vm.onTypeChange(TransactionType.EXPENSE) },
                                        label = { Text("Dépense") }
                                    )
                                    FilterChip(
                                        selected = state.type == TransactionType.INCOME,
                                        onClick = { vm.onTypeChange(TransactionType.INCOME) },
                                        label = { Text("Revenu") }
                                    )
                                }
                            }

                            // Catégorie parente
                            Column {
                                Text("Catégorie parente (Optionnel)", style = MaterialTheme.typography.labelMedium)
                                Spacer(Modifier.height(8.dp))
                                val parentName = state.availableParents.find { it.id == state.parentCategoryId }?.name ?: "Aucune"
                                Surface(
                                    modifier = Modifier.fillMaxWidth().clickableNoRipple { showParentSheet = true },
                                    shape = MaterialTheme.shapes.small,
                                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
                                    color = Color.Transparent
                                ) {
                                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Text(parentName)
                                        Icon(Icons.Default.ChevronRight, null)
                                    }
                                }
                            }
                        }
                    }

                    // Apparence
                    FloatingCard(Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text("Apparence", style = MaterialTheme.typography.titleMedium)
                            
                            // Couleurs (similaire au CRUD compte)
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                val colors = listOf(0xFF9C27B0, 0xFF2196F3, 0xFF4CAF50, 0xFFFFC107, 0xFFF44336, 0xFF607D8B)
                                colors.forEach { c ->
                                    Box(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .clip(CircleShape)
                                            .background(Color(c.toInt()))
                                            .border(2.dp, if (state.colorArgb == c.toInt()) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape)
                                            .clickable { vm.onColorChange(c.toInt()) },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (state.colorArgb == c.toInt()) Icon(Icons.Default.Check, null, tint = Color.White)
                                    }
                                }
                            }

                            // Icônes locales (simplifié pour US)
                            val icons = listOf("category", "restaurant", "directions_bus", "home", "work", "sports_esports", "shopping_cart", "bolt")
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                items(icons) { iconName ->
                                    Box(
                                        modifier = Modifier
                                            .size(48.dp)
                                            .clip(MaterialTheme.shapes.small)
                                            .background(if (state.icon == iconName) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                            .clickable { vm.onIconChange(iconName) },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            IconMapper.get(iconName) as androidx.compose.ui.graphics.vector.ImageVector,
                                            null,
                                            tint = if (state.icon == iconName) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (state.isEdit) {
                        Button(
                            onClick = { vm.delete(onBack) },
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer),
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Icon(Icons.Default.Delete, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Supprimer la catégorie")
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryParentBottomSheet(
    categories: List<CategoryEntity>,
    selectedId: Long?,
    onSelect: (Long?) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(Modifier.fillMaxWidth().padding(bottom = 32.dp)) {
            item { Text("Catégorie parente", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.titleLarge) }
            item {
                ListItem(
                    headlineContent = { Text("Aucune (Catégorie principale)") },
                    modifier = Modifier.clickable { onSelect(null) },
                    trailingContent = { if (selectedId == null) Icon(Icons.Default.Check, null) }
                )
            }
            items(categories) { cat ->
                ListItem(
                    headlineContent = { Text(cat.name) },
                    leadingContent = { CircleIcon(IconMapper.get(cat.icon), Color(cat.colorArgb), Color(cat.colorArgb).copy(alpha = 0.1f), size = 32.dp) },
                    modifier = Modifier.clickable { onSelect(cat.id) },
                    trailingContent = { if (selectedId == cat.id) Icon(Icons.Default.Check, null) }
                )
            }
        }
    }
}

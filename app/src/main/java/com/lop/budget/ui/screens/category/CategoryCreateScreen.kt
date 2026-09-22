package com.lop.budget.ui.screens.category

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.ui.common.TestTags
import com.lop.budget.ui.components.CategoryBottomSheet
import com.lop.budget.ui.components.CategoryDeleteConfirmSheet
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
    var showDeleteConfirm by remember { mutableStateOf(false) }

    if (showParentSheet) {
        // CA-11 : le même modal que le sélecteur de catégorie des autres écrans, avec en plus la
        // ligne « aucune » qui fait de la catégorie une principale.
        CategoryBottomSheet(
            title = "Catégorie parente",
            categories = state.availableParents,
            selectedId = state.parentCategoryId,
            onSelect = {
                vm.onParentChange(it)
                showParentSheet = false
            },
            onSelectNone = {
                vm.onParentChange(null)
                showParentSheet = false
            },
            noneLabel = "Aucune (catégorie principale)",
            onDismiss = { showParentSheet = false }
        )
    }

    if (showDeleteConfirm) {
        CategoryDeleteConfirmSheet(
            categoryName = state.name,
            isUsed = state.isUsed,
            hasChildren = state.hasChildren,
            onDismiss = { showDeleteConfirm = false },
            onConfirm = {
                showDeleteConfirm = false
                vm.delete(onBack)
            },
        )
    }

    LopScreenScaffold(
        title = if (state.isEdit) "Modifier la catégorie" else "Nouvelle catégorie",
        onBack = onBack,
        navigationIcon = Icons.AutoMirrored.Filled.ArrowBack,
        modifier = Modifier.testTag(TestTags.SCREEN_EDIT),
        bottomBar = {
            Box(Modifier.fillMaxWidth().padding(20.dp)) {
                Button(
                    onClick = { vm.save(onBack) },
                    modifier = Modifier.fillMaxWidth().height(56.dp).testTag(TestTags.BTN_SAVE),
                    shape = MaterialTheme.shapes.medium,
                    // CA-03 / CA-05 : sans nom, le formulaire refuse l'enregistrement.
                    enabled = state.canSave
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
                                modifier = Modifier.fillMaxWidth().testTag("category.edit.name"),
                                singleLine = true
                            )

                            // CA-12 / I-6 : le choix du type disparaît dès qu'un rattachement
                            // existe — une catégorie utilisée ou parente ne change pas de type.
                            if (state.canChangeType) {
                                Column {
                                    Text("Type", style = MaterialTheme.typography.labelMedium)
                                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        FilterChip(
                                            selected = state.type == TransactionType.EXPENSE,
                                            onClick = { vm.onTypeChange(TransactionType.EXPENSE) },
                                            label = { Text("Dépense") },
                                            modifier = Modifier.testTag("category.edit.type.expense")
                                        )
                                        FilterChip(
                                            selected = state.type == TransactionType.INCOME,
                                            onClick = { vm.onTypeChange(TransactionType.INCOME) },
                                            label = { Text("Revenu") },
                                            modifier = Modifier.testTag("category.edit.type.income")
                                        )
                                    }
                                }
                            }

                            // CA-09 / I-4 : une catégorie qui a déjà des sous-catégories ne peut
                            // pas devenir elle-même une sous-catégorie.
                            if (!state.hasChildren) {
                                Column {
                                    Text("Catégorie parente (Optionnel)", style = MaterialTheme.typography.labelMedium)
                                    Spacer(Modifier.height(8.dp))
                                    val parentName = state.availableParents.find { it.id == state.parentCategoryId }?.name ?: "Aucune"
                                    Surface(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickableNoRipple { showParentSheet = true }
                                            .testTag("category.edit.parent.selector"),
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
                                            .clickable { vm.onColorChange(c.toInt()) }
                                            .testTag("category.edit.color.${c}"),
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
                                            .clickable { vm.onIconChange(iconName) }
                                            .testTag("category.edit.icon.${iconName}"),
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
                            // E-2 : la suppression passe par une confirmation, comme CA-06 l'exige.
                            onClick = { showDeleteConfirm = true },
                            modifier = Modifier.fillMaxWidth().height(56.dp).testTag(TestTags.CAT_BTN_DELETE),
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

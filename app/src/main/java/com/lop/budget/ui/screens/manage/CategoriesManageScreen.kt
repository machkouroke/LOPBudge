package com.lop.budget.ui.screens.manage

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
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
import com.lop.budget.data.local.entity.CategoryEntity
import com.lop.budget.ui.components.CircleIcon
import com.lop.budget.ui.components.FloatingCard
import com.lop.budget.ui.components.LopScreenScaffold
import com.lop.budget.ui.components.clickableNoRipple
import com.lop.budget.util.IconMapper

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoriesManageScreen(
    onBack: () -> Unit,
    onAddCategory: () -> Unit,
    onEditCategory: (Long) -> Unit,
    vm: CategoriesManageViewModel = hiltViewModel()
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    var selectedType by remember { mutableIntStateOf(0) } // 0 for Expense, 1 for Income

    LopScreenScaffold(
        title = "Gérer les catégories",
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
                    onClick = onAddCategory,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Ajouter une catégorie")
                }
            }
        }
    ) {
        item {
            TabRow(
                selectedTabIndex = selectedType,
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.primary,
                divider = {}
            ) {
                Tab(
                    selected = selectedType == 0,
                    onClick = { selectedType = 0 },
                    text = { Text("Dépenses") }
                )
                Tab(
                    selected = selectedType == 1,
                    onClick = { selectedType = 1 },
                    text = { Text("Revenus") }
                )
            }
        }

        val categories = if (selectedType == 0) state.expenseCategories else state.incomeCategories

        if (categories.isEmpty()) {
            item {
                Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Aucune catégorie configurée", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            items(categories, key = { it.category.id }) { catWithSubs ->
                CategoryExpandableRow(
                    catWithSubs = catWithSubs,
                    onEdit = { onEditCategory(it) }
                )
            }
        }
    }
}

@Composable
fun CategoryExpandableRow(
    catWithSubs: CategoryWithSubs,
    onEdit: (Long) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val cat = catWithSubs.category
    val color = Color(cat.colorArgb)

    Column {
        FloatingCard(
            modifier = Modifier
                .fillMaxWidth()
                .clickableNoRipple { 
                    if (catWithSubs.subCategories.isNotEmpty()) expanded = !expanded
                    else onEdit(cat.id)
                },
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircleIcon(
                    icon = IconMapper.get(cat.icon),
                    tint = color,
                    background = color.copy(alpha = 0.15f)
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(cat.name, style = MaterialTheme.typography.titleMedium)
                    if (catWithSubs.subCategories.isNotEmpty()) {
                        Text(
                            "${catWithSubs.subCategories.size} sous-catégories",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (catWithSubs.subCategories.isNotEmpty()) {
                    IconButton(onClick = { expanded = !expanded }) {
                        Icon(
                            if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.ChevronRight,
                            null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                IconButton(onClick = { onEdit(cat.id) }) {
                    Icon(
                        Icons.Default.ChevronRight,
                        null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (expanded) {
            catWithSubs.subCategories.forEach { sub ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 32.dp, top = 8.dp)
                        .clickable { onEdit(sub.id) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val subColor = Color(sub.colorArgb)
                    CircleIcon(
                        icon = IconMapper.get(sub.icon),
                        tint = subColor,
                        background = subColor.copy(alpha = 0.15f),
                        size = 32.dp
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(sub.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

package com.lop.budget.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lop.budget.R
import com.lop.budget.data.local.entity.CategoryEntity
import com.lop.budget.util.IconMapper

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryBottomSheet(
    title: String,
    categories: List<CategoryEntity>,
    selectedId: Long?,
    onSelect: (Long) -> Unit,
    onCreate: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }
    var currentParent by remember { mutableStateOf<CategoryEntity?>(null) }
    
    val filteredCategories = remember(categories, searchQuery, currentParent) {
        if (searchQuery.isNotBlank()) {
            categories.filter { it.name.contains(searchQuery, ignoreCase = true) }
        } else {
            categories.filter { it.parentCategoryId == currentParent?.id }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        containerColor = MaterialTheme.colorScheme.surface,
        scrimColor = Color.Black.copy(alpha = 0.55f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (currentParent != null && searchQuery.isBlank()) {
                        IconButton(onClick = { currentParent = null }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                        }
                    }
                    Text(
                        text = if (searchQuery.isNotBlank()) "Résultats" else currentParent?.name ?: title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                
                if (onCreate != null) {
                    IconButton(onClick = onCreate) {
                        Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Chercher une catégorie...") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Close, null)
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                )
            )

            Spacer(Modifier.height(24.dp))

            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.weight(1f, fill = false),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (searchQuery.isBlank() && currentParent == null && categories.isNotEmpty()) {
                    item(span = { GridItemSpan(3) }) {
                        Text("Récente", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                    items(categories.take(3)) { cat ->
                        CategoryGridItem(cat, selectedId == cat.id) {
                            val hasChildren = categories.any { it.parentCategoryId == cat.id }
                            if (hasChildren) currentParent = cat
                            else onSelect(cat.id)
                        }
                    }
                    item(span = { GridItemSpan(3) }) {
                        Spacer(Modifier.height(8.dp))
                        Text("Toute", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                }

                if (filteredCategories.isEmpty()) {
                    item(span = { GridItemSpan(3) }) {
                        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            Text("Aucune catégorie trouvée", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                items(filteredCategories) { cat ->
                    CategoryGridItem(cat, selectedId == cat.id) {
                        val hasChildren = categories.any { it.parentCategoryId == cat.id }
                        if (hasChildren && searchQuery.isBlank()) {
                            currentParent = cat
                        } else {
                            onSelect(cat.id)
                        }
                    }
                }
            }
            
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun CategoryGridItem(
    cat: CategoryEntity,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val color = Color(cat.colorArgb)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircleIcon(
            icon = IconMapper.get(cat.icon),
            tint = color,
            background = color.copy(alpha = 0.15f),
            size = 56.dp
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = cat.name,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        )
    }
}

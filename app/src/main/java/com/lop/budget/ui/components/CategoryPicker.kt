package com.lop.budget.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lop.budget.data.local.entity.CategoryEntity
import com.lop.budget.util.IconMapper

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

    val parentIds = remember(categories) {
        categories.mapNotNullTo(hashSetOf()) { it.parentCategoryId }
    }
    val isSearching = searchQuery.isNotBlank()
    val visible = remember(categories, searchQuery, currentParent) {
        if (isSearching) {
            categories.filter { it.name.contains(searchQuery, ignoreCase = true) }
        } else {
            categories.filter { it.parentCategoryId == currentParent?.id }
        }
    }
    val rootCategories = remember(categories) {
        categories.filter { it.parentCategoryId == null }
    }
    val recents = remember(rootCategories) { rootCategories.take(3) }
    LopBottomSheet(onDismiss = onDismiss) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (currentParent != null && !isSearching) {
                IconButton(onClick = { currentParent = null }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                }
            }
            Text(
                text = when {
                    isSearching -> "Résultats"
                    else -> currentParent?.name ?: title
                },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        LopSearchBar(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            placeholder = "Chercher une catégorie...",
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!isSearching && currentParent != null) {
                SelectParentRow(parent = currentParent!!) {
                    onSelect(currentParent!!.id)
                }
                SectionLabel("Sous-catégories")
            }
            if (!isSearching && currentParent == null && recents.isNotEmpty()) {
                SectionLabel("Récente")
                CategoryTileRow(
                    tiles = recents.map { CategoryTile.Item(it) },
                    selectedId = selectedId,
                    parentIds = parentIds,
                    onOpenParent = { currentParent = it },
                    onSelect = onSelect,
                )
                SectionLabel("Toutes")
            }

// puis la grille `visible` + tuile Ajouter, comme maintenant
            if (visible.isEmpty() && onCreate == null) {
                Text(
                    "Aucune catégorie trouvée",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    textAlign = TextAlign.Center,
                )
            } else {
                val tiles: List<CategoryTile> = buildList {
                    visible.forEach { add(CategoryTile.Item(it)) }
                    if (onCreate != null && currentParent == null) {
                        add(CategoryTile.Add)
                    }
                }
                tiles.chunked(4).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        row.forEach { tile ->
                            when (tile) {
                                is CategoryTile.Item -> CategoryGridItem(
                                    cat = tile.cat,
                                    isSelected = selectedId == tile.cat.id,
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        if (tile.cat.id in parentIds && !isSearching) {
                                            currentParent = tile.cat
                                        } else {
                                            onSelect(tile.cat.id)
                                        }
                                    },
                                )
                                CategoryTile.Add -> AddCategoryTile(
                                    modifier = Modifier.weight(1f),
                                    onClick = onCreate!!,
                                )
                            }
                        }
                        repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

private sealed interface CategoryTile {
    data class Item(val cat: CategoryEntity) : CategoryTile
    data object Add : CategoryTile
}

@Composable
private fun SelectParentRow(parent: CategoryEntity, onSelect: () -> Unit) {
    val color = Color(parent.colorArgb)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .pressScaleClickable(intent = HapticIntent.Selection, onClick = onSelect),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircleIcon(
                icon = IconMapper.get(parent.icon),
                tint = color,
                background = color.copy(alpha = 0.15f),
                size = 40.dp,
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    "Sélectionner la catégorie principale",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    parent.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun CategoryGridItem(
    cat: CategoryEntity,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val color = Color(cat.colorArgb)
    Surface(
        modifier = modifier.pressScaleClickable(
            intent = HapticIntent.Selection,
            pressedScale = 0.96f,
            onClick = onClick,
        ),
        shape = RoundedCornerShape(16.dp),
        color = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        } else {
            Color.Transparent
        },
        border = if (isSelected) {
            BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.55f))
        } else {
            null
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircleIcon(
                icon = IconMapper.get(cat.icon),
                tint = color,
                background = color.copy(alpha = 0.15f),
                size = 52.dp,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = cat.name,
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }
    }
}

@Composable
private fun AddCategoryTile(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val color = MaterialTheme.colorScheme.primary
    Surface(
        modifier = modifier.pressScaleClickable(
            intent = HapticIntent.Tap,
            pressedScale = 0.96f,
            onClick = onClick,
        ),
        shape = RoundedCornerShape(16.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, color.copy(alpha = 0.35f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = "Ajouter",
                tint = color,
                modifier = Modifier.size(52.dp).padding(8.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Ajouter",
                style = MaterialTheme.typography.labelMedium,
                color = color,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
    )
}

@Composable
private fun CategoryTileRow(
    tiles: List<CategoryTile>,
    selectedId: Long?,
    parentIds: Set<Long>,
    onOpenParent: (CategoryEntity) -> Unit,
    onSelect: (Long) -> Unit,
    isSearching: Boolean = false,
    onCreate: (() -> Unit)? = null,
) {
    tiles.chunked(4).forEach { row ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            row.forEach { tile ->
                when (tile) {
                    is CategoryTile.Item -> CategoryGridItem(
                        cat = tile.cat,
                        isSelected = selectedId == tile.cat.id,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            if (tile.cat.id in parentIds && !isSearching) onOpenParent(tile.cat)
                            else onSelect(tile.cat.id)
                        },
                    )
                    CategoryTile.Add -> AddCategoryTile(
                        modifier = Modifier.weight(1f),
                        onClick = onCreate!!,
                    )
                }
            }
            repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}
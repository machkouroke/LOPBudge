package com.lop.budget.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
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

    val parentIds = remember(categories) {
        categories.mapNotNullTo(mutableSetOf()) { it.parentCategoryId }
    }
    val isSearching = searchQuery.isNotBlank()
    val visibleCategories = remember(categories, searchQuery, currentParent) {
        if (isSearching) {
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
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
        ) {
            CategorySheetHeader(
                title = when {
                    isSearching -> "Résultats"
                    else -> currentParent?.name ?: title
                },
                showBack = currentParent != null && !isSearching,
                onBack = { currentParent = null },
                onCreate = onCreate,
            )
            Spacer(Modifier.height(16.dp))
            CategorySearchField(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
            )
            Spacer(Modifier.height(24.dp))
            CategoryPickerGrid(
                categories = categories,
                visibleCategories = visibleCategories,
                selectedId = selectedId,
                currentParent = currentParent,
                isSearching = isSearching,
                hasChildren = { id -> id in parentIds },
                onOpenParent = { currentParent = it },
                onSelect = onSelect,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun CategoryPickerGrid(
    categories: List<CategoryEntity>,
    visibleCategories: List<CategoryEntity>,
    selectedId: Long?,
    currentParent: CategoryEntity?,
    isSearching: Boolean,
    hasChildren: (Long) -> Boolean,
    onOpenParent: (CategoryEntity) -> Unit,
    onSelect: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val consumeSheetScroll = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset = Offset(x = 0f, y = available.y)

            override suspend fun onPostFling(
                consumed: Velocity,
                available: Velocity,
            ): Velocity = Velocity(x = 0f, y = available.y)
        }
    }

    CompositionLocalProvider(LocalOverscrollFactory   provides null) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = modifier.nestedScroll(consumeSheetScroll),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val showRootSections = !isSearching && currentParent == null && categories.isNotEmpty()
            if (showRootSections) {
                item(span = { GridItemSpan(3) }, key = "header-recent") {
                    SectionLabel("Récente")
                }
                items(
                    items = categories.take(3),
                    key = { "recent-${it.id}" },
                ) { cat ->
                    CategoryGridItem(cat, selectedId == cat.id) {
                        if (hasChildren(cat.id)) onOpenParent(cat) else onSelect(cat.id)
                    }
                }
                item(span = { GridItemSpan(3) }, key = "header-all") {
                    Column {
                        Spacer(Modifier.height(8.dp))
                        SectionLabel("Toutes")
                    }
                }
            }

            if (!isSearching && currentParent != null) {
                item(span = { GridItemSpan(3) }, key = "select-parent") {
                    SelectParentRow(parent = currentParent, onSelect = { onSelect(currentParent.id) })
                }
                item(span = { GridItemSpan(3) }, key = "header-children") {
                    SectionLabel("Sous-catégories", modifier = Modifier.padding(top = 8.dp))
                }
            }

            if (visibleCategories.isEmpty() && (isSearching || currentParent == null)) {
                item(span = { GridItemSpan(3) }, key = "empty") {
                    Box(
                        Modifier.fillMaxWidth().padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Aucune catégorie trouvée", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            items(visibleCategories, key = { it.id }) { cat ->
                CategoryGridItem(cat, selectedId == cat.id) {
                    if (hasChildren(cat.id) && !isSearching) onOpenParent(cat)
                    else onSelect(cat.id)
                }
            }
        }
    }
}

@Composable
private fun CategorySheetHeader(
    title: String,
    showBack: Boolean,
    onBack: () -> Unit,
    onCreate: (() -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (showBack) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                }
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
        if (onCreate != null) {
            IconButton(onClick = onCreate) {
                Icon(Icons.Default.Add, contentDescription = "Créer", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun CategorySearchField(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("Chercher une catégorie...") },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Close, contentDescription = "Effacer")
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        ),
    )
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier,
    )
}

@Composable
private fun SelectParentRow(
    parent: CategoryEntity,
    onSelect: () -> Unit,
) {
    val color = Color(parent.colorArgb)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onSelect),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
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
                    text = "Sélectionner la catégorie principale",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = parent.name,
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
    onClick: () -> Unit,
) {
    val color = Color(cat.colorArgb)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircleIcon(
            icon = IconMapper.get(cat.icon),
            tint = color,
            background = color.copy(alpha = 0.15f),
            size = 56.dp,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = cat.name,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            color = if (isSelected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
}
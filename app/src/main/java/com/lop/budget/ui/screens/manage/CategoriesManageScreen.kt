package com.lop.budget.ui.screens.manage

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lop.budget.data.local.entity.CategoryEntity
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.domain.usecase.category.CategoryWithSubs
import com.lop.budget.ui.common.TestTags
import com.lop.budget.ui.components.CategoryDeleteConfirmSheet
import com.lop.budget.ui.components.CircleIcon
import com.lop.budget.ui.components.FloatingCard
import com.lop.budget.ui.components.LopScreenScaffold
import com.lop.budget.ui.components.clickableNoRipple
import com.lop.budget.ui.motion.MotionSpec
import com.lop.budget.util.IconMapper

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoriesManageScreen(
    onBack: () -> Unit,
    onAddCategory: (TransactionType) -> Unit,
    onEditCategory: (Long) -> Unit,
    vm: CategoriesManageViewModel = hiltViewModel()
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val pendingDelete by vm.pendingDelete.collectAsStateWithLifecycle()
    var selectedType by remember { mutableStateOf(TransactionType.EXPENSE) }

    pendingDelete?.let { pending ->
        CategoryDeleteConfirmSheet(
            categoryName = pending.category.name,
            isUsed = pending.usage.isUsed,
            hasChildren = pending.usage.hasChildren,
            onDismiss = vm::cancelDelete,
            onConfirm = vm::confirmDelete,
        )
    }

    LopScreenScaffold(
        title = "Gérer les catégories",
        onBack = onBack,
        navigationIcon = Icons.AutoMirrored.Filled.ArrowBack,
        modifier = Modifier.testTag(TestTags.SCREEN_CATEGORIES),
        bottomBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                FloatingActionButton(
                    // CA-14 : la création hérite du type de la section ouverte.
                    onClick = { onAddCategory(selectedType) },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.testTag(TestTags.CAT_BTN_ADD)
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Ajouter une catégorie")
                }
            }
        }
    ) {
        item {
            TabRow(
                selectedTabIndex = if (selectedType == TransactionType.EXPENSE) 0 else 1,
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.primary,
                divider = {}
            ) {
                Tab(
                    selected = selectedType == TransactionType.EXPENSE,
                    onClick = { selectedType = TransactionType.EXPENSE },
                    text = { Text("Dépenses") }
                )
                Tab(
                    selected = selectedType == TransactionType.INCOME,
                    onClick = { selectedType = TransactionType.INCOME },
                    text = { Text("Revenus") }
                )
            }
        }

        val categories =
            if (selectedType == TransactionType.EXPENSE) state.expense else state.income

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
                    onEdit = { onEditCategory(it) },
                    onDelete = { vm.requestDelete(it) }
                )
            }
        }
    }
}

@Composable
fun CategoryExpandableRow(
    catWithSubs: CategoryWithSubs,
    onEdit: (Long) -> Unit,
    onDelete: (CategoryEntity) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val cat = catWithSubs.category
    val hasSubs = catWithSubs.subCategories.isNotEmpty()
    // Deux gestes, deux signes : la flèche vers le bas qui pivote déplie la liste, le crayon ouvre
    // le formulaire. La même flèche pour les deux laissait deviner lequel faisait quoi.
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = MotionSpec.mediumTween(),
        label = "categoryExpandChevron",
    )

    Column {
        CategoryBlock(
            category = cat,
            onClick = { if (hasSubs) expanded = !expanded else onEdit(cat.id) },
            subtitle = if (hasSubs) "${catWithSubs.subCategories.size} sous-catégories" else null,
        ) {
            if (hasSubs) {
                IconButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.testTag("${TestTags.CAT_EXPAND}_${cat.id}")
                ) {
                    Icon(
                        Icons.Default.ExpandMore,
                        contentDescription = if (expanded) {
                            "Masquer les sous-catégories de ${cat.name}"
                        } else {
                            "Afficher les sous-catégories de ${cat.name}"
                        },
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.rotate(chevronRotation),
                    )
                }
            }

            IconButton(
                onClick = { onDelete(cat) },
                modifier = Modifier.testTag("category.delete.${cat.id}")
            ) {
                Icon(
                    Icons.Default.DeleteOutline,
                    contentDescription = "Supprimer ${cat.name}",
                    tint = MaterialTheme.colorScheme.error
                )
            }

            EditCategoryButton(cat, onEdit)
        }

        // La liste se déroule depuis la carte au lieu d'apparaître d'un bloc : même ressort que les
        // feuilles de l'application, sans rebond à la fermeture.
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(MotionSpec.sheetEnterSpring(), expandFrom = Alignment.Top) +
                fadeIn(MotionSpec.mediumTween()),
            exit = shrinkVertically(MotionSpec.sheetExitSpring(), shrinkTowards = Alignment.Top) +
                fadeOut(MotionSpec.fastTween()),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp).padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                catWithSubs.subCategories.forEach { sub ->
                    CategoryBlock(
                        category = sub,
                        onClick = { onEdit(sub.id) },
                        // FloatingCard fixe lui-même l'opacité de la bordure : on adoucit donc la teinte, pas l'alpha.
                        borderColor = lerp(Color(cat.colorArgb), MaterialTheme.colorScheme.surface, 0.5f),
                        compact = true,
                    ) {
                        EditCategoryButton(sub, onEdit)
                    }
                }
            }
        }
    }
}

/**
 * Le bloc d'une catégorie, commun aux principales et à leurs sous-catégories : même carte, même
 * pastille, mêmes actions à droite. Une sous-catégorie s'en distingue par un bloc plus compact,
 * plus étroit et centré sous sa principale, bordé d'une teinte adoucie de la couleur de celle-ci.
 */
@Composable
private fun CategoryBlock(
    category: CategoryEntity,
    onClick: () -> Unit,
    borderColor: Color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
    compact: Boolean = false,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit,
) {
    val color = Color(category.colorArgb)
    FloatingCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickableNoRipple(onClick)
            .testTag("${TestTags.CAT_ROW}_${category.id}"),
        color = borderColor,
        cornerRadius = if (compact) 22.dp else 28.dp,
        contentPadding = if (compact) PaddingValues(horizontal = 18.dp, vertical = 8.dp) else PaddingValues(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircleIcon(
                icon = IconMapper.get(category.icon),
                tint = color,
                background = color.copy(alpha = 0.15f),
                size = if (compact) 36.dp else 44.dp,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    category.name,
                    style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            actions()
        }
    }
}

@Composable
private fun EditCategoryButton(category: CategoryEntity, onEdit: (Long) -> Unit) {
    IconButton(
        onClick = { onEdit(category.id) },
        modifier = Modifier.testTag("category.edit.${category.id}")
    ) {
        Icon(
            Icons.Outlined.Edit,
            contentDescription = "Modifier ${category.name}",
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

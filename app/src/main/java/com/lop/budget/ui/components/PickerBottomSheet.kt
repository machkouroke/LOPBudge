package com.lop.budget.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lop.budget.BuildConfig

/**
 * Feuille de sélection générique : une liste d'options dont une seule est retenue.
 *
 * Étendue plutôt que dupliquée pour la sélection de devise (LOP-58, P-6) : recherche facultative,
 * liste paresseuse et pastille emoji sont des paramètres, si bien que les sélecteurs déjà en place
 * ne changent pas de comportement et qu'un second sélecteur ne peut pas diverger de celui-ci.
 *
 * @param items options à afficher, **déjà filtrées** par l'appelant. Le filtrage vit dans un use
 *   case, où il se teste sans appareil.
 * @param searchQuery saisie du champ de recherche ; `null` masque le champ.
 * @param itemEmoji pastille de gauche rendue telle quelle, pour les visuels que [itemIcon] ne sait
 *   pas produire (drapeaux). Purement décorative : elle est retirée de l'arbre d'accessibilité,
 *   [itemLabel] doit donc suffire à identifier la ligne.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> PickerBottomSheet(
    title: String,
    items: List<T>,
    isSelected: (T) -> Boolean,
    onSelect: (T?) -> Unit,      // null = option « Aucun »
    onDismiss: () -> Unit,
    itemLabel: @Composable (T) -> String,
    modifier: Modifier = Modifier,
    allowNone: Boolean = false,
    noneLabel: String? = null,
    emptyText: String? = null,
    itemIcon: ((T) -> Any?)? = null,
    itemTint: ((T) -> Color?)? = null,
    itemEmoji: ((T) -> String?)? = null,
    searchQuery: String? = null,
    onSearchQueryChange: (String) -> Unit = {},
    searchPlaceholder: String? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // La ligne sélectionnée doit être visible sans défilement manuel : sur un catalogue de
    // ~150 entrées elle est le plus souvent hors écran à l'ouverture. L'index n'est lu qu'à la
    // première composition, une recherche qui réordonne la liste ne ramène donc pas la vue en
    // arrière. Décalé de 1 quand « Aucun » occupe la première ligne.
    val selectedIndex = items.indexOfFirst(isSelected).takeIf { it >= 0 } ?: 0
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = if (allowNone) selectedIndex + 1 else selectedIndex
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        scrimColor = Color.Black.copy(alpha = 0.55f),
        dragHandle = { BottomSheetDefaults.DragHandle() },
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )

            if (searchQuery != null) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    placeholder = searchPlaceholder?.let { { Text(it) } },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (items.isEmpty() && emptyText != null) {
                Text(
                    text = emptyText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Liste paresseuse : un `Column` composait les ~150 lignes du catalogue de devises
            // d'un coup, à chaque ouverture. La borne de hauteur laisse les petits sélecteurs
            // s'ajuster à leur contenu comme avant.
            LazyColumn(
                state = listState,
                modifier = Modifier.heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (allowNone) {
                    item {
                        val noneSelected = items.none { isSelected(it) }
                        val label = noneLabel ?: "Aucun"
                        val displayLabel = if (noneSelected && BuildConfig.DEBUG) "$label ✅" else label

                        ItemRow(
                            label = displayLabel,
                            isSelected = noneSelected,
                            onClick = { onSelect(null) }
                        )
                    }
                }

                items(items.size) { index ->
                    val item = items[index]
                    val selected = isSelected(item)
                    val label = itemLabel(item)
                    val displayLabel = if (selected && BuildConfig.DEBUG) "$label ✅" else label

                    ItemRow(
                        label = displayLabel,
                        isSelected = selected,
                        onClick = { onSelect(item) },
                        icon = itemIcon?.invoke(item),
                        tint = itemTint?.invoke(item),
                        emoji = itemEmoji?.invoke(item)
                    )
                }
            }
        }
    }
}

@Composable
private fun ItemRow(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    icon: Any? = null,
    tint: Color? = null,
    emoji: String? = null,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .pressScaleClickable(
                intent = HapticIntent.Selection,
                pressedScale = 0.98f
            ) { onClick() }
            .semantics { selected = isSelected },
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.10f))
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (emoji != null) {
                // Décoratif : le lecteur d'écran l'ignore et n'annonce que le libellé, qui porte
                // déjà tout ce qui distingue la ligne.
                Text(
                    text = emoji,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.clearAndSetSemantics { }
                )
                Spacer(Modifier.width(12.dp))
            } else if (icon != null) {
                val iconTint = tint ?: MaterialTheme.colorScheme.primary
                CircleIcon(
                    icon = icon,
                    tint = iconTint,
                    background = iconTint.copy(alpha = 0.14f),
                    size = 38.dp
                )
                Spacer(Modifier.width(12.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (isSelected) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

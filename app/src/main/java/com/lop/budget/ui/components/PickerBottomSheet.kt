package com.lop.budget.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lop.budget.BuildConfig

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
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

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

            if (allowNone) {
                val noneSelected = items.none { isSelected(it) }
                val label = noneLabel ?: "Aucun"
                val displayLabel = if (noneSelected && BuildConfig.DEBUG) "$label ✅" else label
                
                ItemRow(
                    label = displayLabel,
                    isSelected = noneSelected,
                    onClick = { onSelect(null) }
                )
            }

            if (items.isEmpty() && emptyText != null) {
                Text(
                    text = emptyText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            items.forEach { item ->
                val selected = isSelected(item)
                val label = itemLabel(item)
                val displayLabel = if (selected && BuildConfig.DEBUG) "$label ✅" else label
                
                ItemRow(
                    label = displayLabel,
                    isSelected = selected,
                    onClick = { onSelect(item) },
                    icon = itemIcon?.invoke(item),
                    tint = itemTint?.invoke(item)
                )
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
            if (icon != null) {
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

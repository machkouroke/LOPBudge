package com.lop.budget.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NextPlan
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EventRepeat
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lop.budget.domain.model.EditScope

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecurringEditSheet(
    onDismiss: () -> Unit,
    onChoose: (EditScope) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val container = MaterialTheme.colorScheme.surface

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = container,
        scrimColor = Color.Black.copy(alpha = 0.55f),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
                    modifier = Modifier.size(44.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Filled.Edit,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
                Spacer(Modifier.size(12.dp))
                Column {
                    Text(
                        text = "Modifier la transaction ?",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Cette transaction fait partie d’une série récurrente.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Text(
                text = "Quelle est la portée de tes changements ?",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Actions
            ActionRow(
                icon = Icons.Filled.DateRange,
                title = "Cette occurrence uniquement",
                subtitle = "Modifie seulement cette date.",
                tone = EditActionTone.Primary,
                onClick = { onChoose(EditScope.SINGLE) },
            )

            ActionRow(
                icon = Icons.AutoMirrored.Filled.NextPlan,
                title = "Cette occurrence et les suivantes",
                subtitle = "Applique les changements à partir de cette date.",
                tone = EditActionTone.Primary,
                onClick = { onChoose(EditScope.FUTURE) },
            )

            ActionRow(
                icon = Icons.Filled.EventRepeat,
                title = "Toute la série",
                subtitle = "Applique les changements à toutes les occurrences.",
                tone = EditActionTone.Primary,
                onClick = { onChoose(EditScope.ALL) },
            )

            Spacer(Modifier.height(4.dp))

            // Cancel
            ActionRow(
                icon = null,
                title = "Annuler",
                subtitle = null,
                tone = EditActionTone.Neutral,
                onClick = onDismiss,
            )

            Spacer(Modifier.height(18.dp))
        }
    }
}

enum class EditActionTone { Neutral, Primary }

@Composable
private fun ActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    title: String,
    subtitle: String?,
    tone: EditActionTone,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(22.dp)
    val colorScheme = MaterialTheme.colorScheme
    
    val border = when (tone) {
        EditActionTone.Neutral -> colorScheme.onSurface.copy(alpha = 0.10f)
        EditActionTone.Primary -> colorScheme.primary.copy(alpha = 0.20f)
    }

    val top = when (tone) {
        EditActionTone.Neutral -> colorScheme.surfaceVariant.copy(alpha = 0.55f)
        EditActionTone.Primary -> colorScheme.primary.copy(alpha = 0.10f)
    }

    val bottom = when (tone) {
        EditActionTone.Neutral -> colorScheme.surface.copy(alpha = 0.45f)
        EditActionTone.Primary -> colorScheme.primary.copy(alpha = 0.06f)
    }

    Surface(
        shape = shape,
        border = BorderStroke(1.dp, border),
        shadowElevation = 4.dp,
        tonalElevation = 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.verticalGradient(listOf(top, bottom)))
            .pressScaleClickable(
                intent = HapticIntent.Selection,
                pressedScale = 0.98f,
                onClick = onClick
            ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (icon != null) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (tone == EditActionTone.Primary) colorScheme.primary else colorScheme.onSurface,
                    modifier = Modifier.size(20.dp),
                )
            } else {
                Spacer(Modifier.size(20.dp))
            }

            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (tone == EditActionTone.Primary) colorScheme.primary else colorScheme.onSurface,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

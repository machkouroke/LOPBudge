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
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.WarningAmber
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfirmDeleteSheet(
    title: String,
    message: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.14f),
                    border = BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.error.copy(alpha = 0.25f)
                    ),
                    modifier = Modifier.size(44.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Filled.WarningAmber,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
                Spacer(Modifier.size(12.dp))
                Column {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            ActionRow(
                icon = Icons.Filled.DeleteForever,
                title = confirmLabel,
                subtitle = null,
                tone = ActionTone.Danger,
                onClick = onConfirm,
            )

            Spacer(Modifier.height(4.dp))

            ActionRow(
                icon = null,
                title = "Annuler",
                subtitle = null,
                tone = ActionTone.Neutral,
                onClick = onDismiss,
            )

            Spacer(Modifier.height(18.dp))
        }
    }
}


@Composable
private fun ActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    title: String,
    subtitle: String?,
    tone: ActionTone,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(22.dp)
    val border = when (tone) {
        ActionTone.Neutral -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
        ActionTone.Danger -> MaterialTheme.colorScheme.error.copy(alpha = 0.20f)
    }

    val top = when (tone) {
        ActionTone.Neutral -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        ActionTone.Danger -> MaterialTheme.colorScheme.error.copy(alpha = 0.10f)
    }

    val bottom = when (tone) {
        ActionTone.Neutral -> MaterialTheme.colorScheme.surface.copy(alpha = 0.45f)
        ActionTone.Danger -> MaterialTheme.colorScheme.error.copy(alpha = 0.06f)
    }

    Surface(
        shape = shape,

        border = BorderStroke(1.dp, border),

        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                color = if (tone == ActionTone.Danger) Color.Red else MaterialTheme.colorScheme.surface
            )
            .pressScaleClickable(
                intent = if (tone == ActionTone.Danger) HapticIntent.Confirm else HapticIntent.Tap,
                pressedScale = 0.98f,
                onClick = onClick,
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
                    tint = if (tone == ActionTone.Danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
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
                    color = if (tone == ActionTone.Danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

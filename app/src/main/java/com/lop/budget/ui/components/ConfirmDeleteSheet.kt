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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lop.budget.ui.common.TestTags

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfirmDeleteSheet(
    title: String,
    message: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    dismissLabel: String = "Annuler",
    confirmIcon: androidx.compose.ui.graphics.vector.ImageVector? = Icons.Filled.DeleteForever,
    confirmTag: String = TestTags.DELETE_CONFIRM_SUBMIT,
    dismissTag: String = TestTags.DELETE_CONFIRM_CANCEL,
    /**
     * Écriture en cours : les deux actions sont inactives et ni le voile, ni le glissement, ni le
     * retour ne ferment la feuille (P-4 de LOP-204).
     */
    busy: Boolean = false,
    /** Échec de l'écriture, affiché et annoncé sous le message ; la confirmation relance (CA-07 de LOP-204). */
    error: String? = null,
    errorTag: String = "",
) {
    val busyNow by rememberUpdatedState(busy)
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        // Même instance à chaque composition : une nouvelle clé recréerait l'état de la feuille.
        confirmValueChange = remember { { value: SheetValue -> value != SheetValue.Hidden || !busyNow } },
    )
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

            if (error != null) {
                Text(
                    error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .semantics { liveRegion = LiveRegionMode.Polite }
                        .testTag(errorTag),
                )
            }

            ActionRow(
                icon = confirmIcon,
                title = confirmLabel,
                tone = ActionTone.Danger,
                onClick = onConfirm,
                modifier = Modifier.testTag(confirmTag),
                enabled = !busy,
                busy = busy,
            )

            Spacer(Modifier.height(4.dp))

            ActionRow(
                icon = null,
                title = dismissLabel,
                tone = ActionTone.Neutral,
                onClick = onDismiss,
                modifier = Modifier.testTag(dismissTag),
                enabled = !busy,
            )

            Spacer(Modifier.height(18.dp))
        }
    }
}


/**
 * Confirmation de suppression d'une catégorie (LOP-19, CA-06, CA-07, CA-15).
 *
 * L'écran de gestion et le formulaire ouvrent **ce** popup, pas deux popups qui se ressemblent :
 * le message énonce exactement ce que la confirmation va écrire, et rien ne l'écrit deux fois.
 */
@Composable
fun CategoryDeleteConfirmSheet(
    categoryName: String,
    isUsed: Boolean,
    hasChildren: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    ConfirmDeleteSheet(
        title = "Supprimer la catégorie ?",
        message = buildString {
            append("La catégorie \"$categoryName\" sera définitivement supprimée")
            if (hasChildren) append(", ainsi que ses sous-catégories")
            append(".")
            if (isUsed) {
                append(" Les transactions et les séries récurrentes concernées afficheront ")
                append("\"Sans catégorie\" ; aucune n'est supprimée.")
            }
        },
        confirmLabel = "Supprimer",
        onDismiss = onDismiss,
        onConfirm = onConfirm,
        modifier = Modifier.testTag(TestTags.DELETE_CONFIRM_DIALOG),
    )
}

@Composable
private fun ActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    title: String,
    tone: ActionTone,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** Écriture en cours : un indicateur remplace l'icône. */
    busy: Boolean = false,
) {
    val shape = RoundedCornerShape(22.dp)
    val border = when (tone) {
        ActionTone.Neutral -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
        ActionTone.Danger -> MaterialTheme.colorScheme.error.copy(alpha = 0.20f)
    }

    Surface(
        shape = shape,

        border = BorderStroke(1.dp, border),

        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                color = if (tone == ActionTone.Danger) Color.Red else MaterialTheme.colorScheme.surface
            )
            .then(
                if (enabled) {
                    Modifier.pressScaleClickable(
                        intent = if (tone == ActionTone.Danger) HapticIntent.Confirm else HapticIntent.Tap,
                        pressedScale = 0.98f,
                        onClick = onClick,
                    )
                } else {
                    Modifier.alpha(0.5f).semantics { disabled() }
                }
            ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (busy) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
            } else if (icon != null) {
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

            }
        }
    }
}

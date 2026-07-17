package com.lop.budget.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lop.budget.R
import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.ui.theme.LopTheme
import com.lop.budget.util.Format
import com.lop.budget.util.IconMapper

@Composable
fun TransactionPreviewPopup(
    tx: TransactionWithRelations,
    currency: String,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onTogglePaid: () -> Unit,
) {
    val transaction = tx.transaction
    val isIncome = transaction.type == TransactionType.INCOME
    val status = transaction.status
    val color = tx.category?.colorArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.primary

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickableNoRipple(onDismiss),
            contentAlignment = Alignment.Center
        ) {
            FloatingCard(
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .clickableNoRipple { /* stop propagation */ },
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircleIcon(
                        icon = IconMapper.get(tx.category?.icon ?: "category"),
                        tint = color,
                        background = color.copy(alpha = 0.12f),
                        size = 64.dp
                    )
                    
                    Spacer(Modifier.height(16.dp))
                    
                    Text(
                        text = transaction.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    
                    Text(
                        text = tx.account?.name ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(Modifier.height(12.dp))

                    Text(
                        text = (if (isIncome) "+" else "−") + Format.money(transaction.amount, currency),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (isIncome) LopTheme.extended.income else LopTheme.extended.expense
                    )

                    if (!transaction.note.isNullOrBlank()) {
                        Spacer(Modifier.height(16.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = transaction.note,
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    Spacer(Modifier.height(24.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        PreviewActionButton(
                            icon = if (status == TransactionStatus.PAID) Icons.AutoMirrored.Filled.Undo else Icons.Default.Check,
                            label = if (status == TransactionStatus.PAID) "Annuler" else "Payer",
                            onClick = { onTogglePaid(); onDismiss() }
                        )
                        PreviewActionButton(
                            icon = Icons.Default.Edit,
                            label = "Éditer",
                            onClick = { onEdit(); onDismiss() }
                        )
                        PreviewActionButton(
                            icon = Icons.Default.Delete,
                            label = "Supprimer",
                            color = MaterialTheme.colorScheme.error,
                            onClick = { onDelete(); onDismiss() }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewActionButton(
    icon: ImageVector,
    label: String,
    color: Color = MaterialTheme.colorScheme.primary,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickableNoRipple(onClick)
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = color.copy(alpha = 0.1f),
            modifier = Modifier.size(48.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = label, tint = color)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

package com.lop.budget.ui.components

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.ui.theme.LopTheme
import com.lop.budget.util.Format
import com.lop.budget.util.IconMapper
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint

@Composable
fun TransactionPreviewPopup(
    tx: TransactionWithRelations,
    currency: String,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onTogglePaid: () -> Unit,
    hazeState: HazeState? = null,
) {
    val transaction = tx.transaction
    val isIncome = transaction.type == TransactionType.INCOME
    val status = transaction.status
    val color = tx.category?.colorArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.primary

    // Animation state
    val transitionState = remember {
        MutableTransitionState(false).apply { targetState = true }
    }
    val transition = updateTransition(transitionState, label = "previewPop")
    val scale by transition.animateFloat(
        transitionSpec = {
            spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
        },
        label = "scale"
    ) { if (it) 1f else 0.8f }
    val alpha by transition.animateFloat(
        transitionSpec = { spring(stiffness = Spring.StiffnessLow) },
        label = "alpha"
    ) { if (it) 1f else 0f }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent) // Remove the black veil as requested
            .clickableNoRipple(onDismiss),
        contentAlignment = Alignment.Center
    ) {
        val popupShape = RoundedCornerShape(28.dp)
        
        Box(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .scale(scale)
                .clip(popupShape) // Clip to rounded corners FIRST
                .hazeEffect(state = hazeState) {
                    style = HazeStyle(
                        blurRadius = 30.dp,
                        backgroundColor = Color(0xFF1E1E1E).copy(alpha = 0.65f),
                        tints = listOf(HazeTint(Color.Black.copy(alpha = 0.2f)))
                    )
                }
                .clickableNoRipple { /* stop propagation */ }
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Icon with subtle glow
                Box(contentAlignment = Alignment.Center) {
                    Surface(
                        modifier = Modifier.size(72.dp),
                        shape = CircleShape,
                        color = color.copy(alpha = 0.15f)
                    ) {}
                    CircleIcon(
                        icon = IconMapper.get(tx.category?.icon ?: "category"),
                        tint = color,
                        background = Color.Transparent,
                        size = 64.dp
                    )
                }
                
                Spacer(Modifier.height(16.dp))
                
                Text(
                    text = transaction.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                
                Text(
                    text = tx.account?.name ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.6f)
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
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = transaction.note,
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.8f),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }

                Spacer(Modifier.height(28.dp))

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
                        iconColor = LopTheme.extended.expense,
                        onClick = { onDelete(); onDismiss() }
                    )
                }
            }
        }
    }
}


@Composable
private fun PreviewActionButton(
    icon: ImageVector,
    label: String,
    iconColor: Color = Color.Black,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickableNoRipple(onClick)
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color.White,
            modifier = Modifier.size(56.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon, 
                    contentDescription = label, 
                    tint = iconColor,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = label, 
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp), 
            color = Color.White.copy(alpha = 0.9f)
        )
    }
}

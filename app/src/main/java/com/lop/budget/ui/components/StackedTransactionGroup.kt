package com.lop.budget.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.ui.motion.MotionSpec

/**
 * Composant Masterclass UX : Affiche un groupe de transactions (ex: occurrences d'une série)
 * sous forme de pile de cartes qui se déploie avec une animation fluide.
 */
@Composable
fun StackedTransactionGroup(
    transactions: List<TransactionWithRelations>,
    currency: String,
    onOpenTransaction: (Long) -> Unit,
    onMaterializeAndOpen: (Long, Long) -> Unit,
    modifier: Modifier = Modifier
) {
    if (transactions.isEmpty()) return
    
    var isExpanded by remember { mutableStateOf(false) }
    val seriesId = transactions.first().transaction.seriesId ?: "unknown"
    
    // Tri par ordre croissant pour l'affichage déplié (Masterclass UX)
    val sortedTransactions = remember(transactions) {
        transactions.sortedBy { it.transaction.date }
    }

    AnimatedContent(
        targetState = isExpanded,
        transitionSpec = {
            fadeIn(animationSpec = tween(300, easing = LinearOutSlowInEasing))
                .togetherWith(fadeOut(animationSpec = tween(250, easing = FastOutLinearInEasing)))
                .using(SizeTransform(clip = false))
        },
        label = "stack_expansion",
        modifier = modifier.fillMaxWidth()
    ) { expanded ->
        if (!expanded) {
            // --- ÉTAT EMPIILÉ (STACK) ---
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp) // Un peu plus haut pour l'effet de profondeur
                    .clickableNoRipple { isExpanded = true }
                    .testTag("transaction_stack_$seriesId"),
                contentAlignment = Alignment.TopCenter
            ) {
                val stackSize = transactions.size.coerceAtMost(3)
                
                for (i in (stackSize - 1) downTo 0) {
                    val tx = transactions[i]
                    val scale = 1f - (i * 0.04f)
                    val offsetY = (i * 10).dp
                    
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .zIndex(-i.toFloat())
                            .offset(y = offsetY)
                            .scale(scale)
                    ) {
                        TransactionCardStatic(
                            tx = tx,
                            currency = currency,
                            isTop = i == 0 // La carte du dessus est opaque
                        )
                    }
                }

                // Badge indicateur premium
                if (transactions.size > 1) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 12.dp, bottom = 8.dp),
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(10.dp),
                        tonalElevation = 6.dp
                    ) {
                        Text(
                            text = "+${transactions.size - 1}",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }
        } else {
            // --- ÉTAT DÉPLOYÉ (EXPANDED) ---
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(24.dp)
                    )
                    .testTag("transaction_stack_expanded_$seriesId"),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f),
                shape = RoundedCornerShape(24.dp)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Header épuré
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                            .clickableNoRipple { isExpanded = false },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${sortedTransactions.size} prochaines occurrences",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Réduire",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    sortedTransactions.forEachIndexed { index, twr ->
                        TransactionRow(
                            tx = twr,
                            currency = currency,
                            onOpenTransaction = onOpenTransaction,
                            onMaterializeAndOpen = onMaterializeAndOpen,
                            showDate = true,
                            modifier = Modifier.testTag("stack_item_${seriesId}_$index")
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TransactionCardStatic(
    tx: TransactionWithRelations,
    currency: String,
    isTop: Boolean
) {
    // Si c'est la carte du dessus, on utilise un fond 100% opaque pour cacher les cartes en dessous
    val containerColor = if (isTop) MaterialTheme.colorScheme.surface 
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = containerColor,
        shape = RoundedCornerShape(28.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp, 
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (isTop) 0.5f else 0.2f)
        ),
        tonalElevation = if (isTop) 2.dp else 0.dp
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val catColor = tx.category?.colorArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.primary
            
            CircleIcon(
                icon = tx.category?.icon ?: "category",
                tint = Color.White,
                background = catColor,
                size = 36.dp
            )
            
            Spacer(Modifier.width(12.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = tx.transaction.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
                Text(
                    text = if (tx.transaction.isException) "Exception" else "Récurrent",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            Text(
                text = com.lop.budget.util.Format.money(tx.transaction.amount, currency),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = if (tx.transaction.type == com.lop.budget.domain.model.TransactionType.INCOME) 
                    com.lop.budget.ui.theme.LopTheme.extended.income 
                else com.lop.budget.ui.theme.LopTheme.extended.expense
            )
        }
    }
}

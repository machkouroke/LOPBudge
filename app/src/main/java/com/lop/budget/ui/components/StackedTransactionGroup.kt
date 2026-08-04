package com.lop.budget.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.ui.motion.MotionSpec
import kotlin.math.roundToInt

/**
 * Composant Masterclass UX : Affiche un groupe de transactions (ex: occurrences d'une série)
 * sous forme de pile de cartes qui se déploie au clic.
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
    
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("transaction_stack_$seriesId")
    ) {
        if (!isExpanded) {
            // --- ÉTAT EMPIILÉ (STACK) ---
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(90.dp) // Hauteur fixe pour la pile fermée
                    .clickableNoRipple { isExpanded = true },
                contentAlignment = Alignment.TopCenter
            ) {
                // On affiche jusqu'à 3 cartes pour l'effet de profondeur
                val stackSize = transactions.size.coerceAtMost(3)
                
                for (i in (stackSize - 1) downTo 0) {
                    val tx = transactions[i]
                    val scale = 1f - (i * 0.05f)
                    val offsetY = (i * 8).dp
                    
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .zIndex(-i.toFloat())
                            .offset(y = offsetY)
                            .scale(scale)
                    ) {
                        // On utilise une version simplifiée de la carte pour la pile
                        TransactionCardStatic(
                            tx = tx,
                            currency = currency
                        )
                    }
                }

                // Badge indicateur du nombre d'occurrences
                if (transactions.size > 1) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 8.dp, bottom = 4.dp),
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(12.dp),
                        tonalElevation = 4.dp
                    ) {
                        Text(
                            text = "+${transactions.size - 1}",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        } else {
            // --- ÉTAT DÉPLOYÉ (EXPANDED) ---
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(24.dp)
                    )
                    .testTag("transaction_stack_expanded_$seriesId"),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.1f),
                shape = RoundedCornerShape(24.dp)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Header du groupe pour pouvoir refermer
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .clickableNoRipple { isExpanded = false },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${transactions.size} occurrences prévues",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Réduire",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // On affiche toutes les transactions du groupe
                    transactions.forEachIndexed { index, twr ->
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

/**
 * Une version "morte" de la carte pour l'affichage en pile, 
 * évite de charger toute la logique interactive (swipe, etc.) n fois.
 */
@Composable
private fun TransactionCardStatic(
    tx: TransactionWithRelations,
    currency: String
) {
    FloatingCard(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
        contentPadding = PaddingValues(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
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

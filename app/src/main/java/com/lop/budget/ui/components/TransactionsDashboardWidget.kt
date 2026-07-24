package com.lop.budget.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lop.budget.R
import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.domain.model.TransactionStatus
import dev.chrisbanes.haze.HazeState

@Composable
fun TransactionsDashboardWidget(
    transactions: List<TransactionWithRelations>,
    currency: String,
    onSeeAll: () -> Unit,
    onOpenTransaction: (Long) -> Unit,
    onMaterializeAndOpen: (Long, Long) -> Unit,
    onTogglePaid: (Long, TransactionStatus) -> Unit,
    onDeleteRequest: (TransactionWithRelations) -> Unit,
    onPreviewTransaction: (TransactionWithRelations, String) -> Unit,
    onDeleteSimple: (Long) -> Unit,
    hazeState: HazeState? = null,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                stringResource(R.string.home_recent_transactions),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            TextButton(onClick = onSeeAll) {
                Text(stringResource(R.string.see_all))
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, modifier = Modifier.size(16.dp))
            }
        }

        if (transactions.isEmpty()) {
            FloatingCard(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)) {
                Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.home_no_transactions_month),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                transactions.forEach { tx ->
                    TransactionRow(
                        tx = tx,
                        currency = currency,
                        onOpenTransaction = onOpenTransaction,
                        onMaterializeAndOpen = onMaterializeAndOpen,
                        onTogglePaid = onTogglePaid,
                        onDeleteRequest = onDeleteRequest,
                        onPreviewTransaction = onPreviewTransaction,
                        onDeleteSimple = onDeleteSimple,
                        hazeState = hazeState
                    )
                }
            }
        }
    }
}

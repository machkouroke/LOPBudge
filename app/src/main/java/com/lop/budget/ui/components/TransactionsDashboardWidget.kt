package com.lop.budget.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
    FloatingCard(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        contentPadding = PaddingValues(0.dp) // Header handle padding internally
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, end = 8.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    stringResource(R.string.home_recent_transactions),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                TextButton(onClick = onSeeAll) {
                    Text(stringResource(R.string.see_all))
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, modifier = Modifier.size(16.dp))
                }
            }

            if (transactions.isEmpty()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 24.dp, top = 8.dp), 
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        stringResource(R.string.home_no_transactions_month),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
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
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

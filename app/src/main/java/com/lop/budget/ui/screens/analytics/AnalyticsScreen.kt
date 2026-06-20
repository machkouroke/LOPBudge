package com.lop.budget.ui.screens.analytics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.background
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.ui.components.DonutChart
import com.lop.budget.ui.components.DonutSlice
import com.lop.budget.ui.components.FloatingCard
import com.lop.budget.ui.components.ScreenPadding
import com.lop.budget.ui.components.SegmentedButton
import com.lop.budget.ui.theme.LopTheme
import com.lop.budget.util.Format

@Composable
fun AnalyticsScreen(vm: AnalyticsViewModel = hiltViewModel()) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val ext = LopTheme.extended

    // Limite à 6 tranches + "Autres" pour la lisibilité.
    val top = state.breakdown.take(6)
    val othersTotal = state.breakdown.drop(6).sumOf { it.total }
    val slices = buildList {
        top.forEach { add(DonutSlice(it.total, Color(it.colorArgb), it.name)) }
        if (othersTotal > 0) add(DonutSlice(othersTotal, Color(0xFF9E9E9E), "Autres"))
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = ScreenPadding,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { Text("Analyses", style = MaterialTheme.typography.headlineMedium) }

        // Sélecteur Dépenses / Revenus
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SegmentedButton("Dépenses", state.type == TransactionType.EXPENSE, ext.expense, Modifier.weight(1f)) { vm.setType(TransactionType.EXPENSE) }
                SegmentedButton("Revenus", state.type == TransactionType.INCOME, ext.income, Modifier.weight(1f)) { vm.setType(TransactionType.INCOME) }
            }
        }

        item {
            Text(
                Format.monthYear(state.month),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            FloatingCard(Modifier.fillMaxWidth()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    if (slices.isEmpty()) {
                        Text("Aucune donnée ce mois-ci", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(40.dp))
                    } else {
                        DonutChart(slices = slices) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Total", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(Format.money(state.total, state.currency), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        items(state.breakdown, key = { it.name }) { b ->
            FloatingCard(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(14.dp).clip(CircleShape).background(Color(b.colorArgb)))
                    Spacer(Modifier.width(12.dp))
                    Text(b.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text("${(b.share * 100).toInt()} %", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(12.dp))
                    Text(Format.money(b.total, state.currency), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}



package com.lop.budget.ui.screens.analytics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoGraph
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lop.budget.R
import com.lop.budget.ui.components.DonutSlice
import com.lop.budget.ui.components.FloatingCard
import com.lop.budget.ui.components.LopScreenScaffold
import com.lop.budget.ui.components.clickableNoRipple
import com.lop.budget.ui.theme.LopTheme
import com.lop.budget.util.Format

@Composable
fun AnalyticsScreen(
    vm: AnalyticsViewModel = hiltViewModel(),
    onBack: () -> Unit = {}
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val ext = LopTheme.extended

    // Limite à 6 tranches + "Autres" pour la lisibilité.
    val top = state.breakdown.take(6)
    val othersTotal = state.breakdown.drop(6).sumOf { it.total }
    val slices = buildList {
        top.forEach { add(DonutSlice(it.total, Color(it.colorArgb), it.name)) }
        if (othersTotal > 0) add(DonutSlice(othersTotal, Color(0xFF9E9E9E), stringResource(R.string.others)))
    }

    LopScreenScaffold(
        title = stringResource(R.string.nav_analytics),
        onBack = onBack,
        navigationIcon = Icons.AutoMirrored.Filled.ArrowBack
    ) {
        // Section Spent in budget period
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column {
                    val totalSpent = state.breakdown.sumOf { it.total }
                    Text(stringResource(R.string.analytics_spent_period), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Text(Format.money(totalSpent, state.currency), style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Icon(Icons.Filled.ArrowUpward, null, tint = com.lop.budget.ui.theme.ExpenseCoral, modifier = Modifier.size(14.dp))
                        Text(stringResource(R.string.analytics_vs_last_period, Format.money(109.89, state.currency)), style = MaterialTheme.typography.bodyMedium, color = com.lop.budget.ui.theme.ExpenseCoral)
                    }
                }
                com.lop.budget.ui.components.CircleIcon(
                    icon = Icons.Filled.Restaurant, // Placeholder
                    tint = Color.Black,
                    background = com.lop.budget.ui.theme.CategoryOrange,
                    size = 64.dp
                )
            }
        }

        // Graphique simulé (Ligne)
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .padding(vertical = 12.dp)
            ) {
                // Ligne de limite budget
                androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                    drawLine(
                        color = Color.DarkGray,
                        start = androidx.compose.ui.geometry.Offset(0f, size.height * 0.2f),
                        end = androidx.compose.ui.geometry.Offset(size.width, size.height * 0.2f),
                        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
                    )
                }
                Text("$1 500", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.TopEnd))
                
                // Ligne Actual
                androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                    drawLine(
                        color = com.lop.budget.ui.theme.CategoryOrange,
                        start = androidx.compose.ui.geometry.Offset(0f, size.height * 0.8f),
                        end = androidx.compose.ui.geometry.Offset(size.width * 0.4f, size.height * 0.8f),
                        strokeWidth = 6f
                    )
                    drawCircle(color = com.lop.budget.ui.theme.CategoryOrange, radius = 12f, center = androidx.compose.ui.geometry.Offset(0f, size.height * 0.8f))
                }
                
                // Ligne Forecast
                androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                    drawLine(
                        color = Color.Gray,
                        start = androidx.compose.ui.geometry.Offset(0f, size.height * 0.8f),
                        end = androidx.compose.ui.geometry.Offset(size.width, size.height * 0.3f),
                        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f),
                        strokeWidth = 4f
                    )
                }
                
                // Légende X-axis
                Row(
                    modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("1", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("6", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("11", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("16", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("20", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("25", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("30", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        
        // Légende Graphique
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(com.lop.budget.ui.theme.CategoryOrange))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.analytics_actual), style = MaterialTheme.typography.bodyMedium)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(Color.Gray))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.analytics_forecast), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        // Cartes Budget & Forecast
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 8.dp)) {
                // Carte Budget
                FloatingCard(
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.Icon(Icons.Filled.AccountBalanceWallet, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.analytics_budget), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.height(16.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Mini Donut Chart
                            Box(modifier = Modifier.size(60.dp), contentAlignment = Alignment.Center) {
                                androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                                    drawArc(color = Color.DarkGray, startAngle = 0f, sweepAngle = 360f, useCenter = false, style = androidx.compose.ui.graphics.drawscope.Stroke(12f))
                                    drawArc(color = com.lop.budget.ui.theme.CategoryOrange, startAngle = -90f, sweepAngle = 32f, useCenter = false, style = androidx.compose.ui.graphics.drawscope.Stroke(12f))
                                }
                                Text("9%", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.width(16.dp))
                            Column {
                                Text(Format.money(1372.0, state.currency), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                Text(stringResource(R.string.analytics_left), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                
                // Carte Forecast
                FloatingCard(
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.Icon(Icons.Filled.AutoGraph, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.analytics_forecast), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.height(20.dp))
                        Text(Format.money(430.0, state.currency), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text(stringResource(R.string.analytics_spend_forecast, Format.money(45.73, state.currency)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        
        // Today Transactions
        item {
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.analytics_today), style = MaterialTheme.typography.titleMedium)
                Text(Format.money(128.0, state.currency), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        
        item {
            FloatingCard(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    com.lop.budget.ui.components.CircleIcon(
                        icon = Icons.Filled.Restaurant, // Placeholder
                        tint = Color.Black,
                        background = com.lop.budget.ui.theme.CategoryOrange,
                        size = 48.dp
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.other), style = MaterialTheme.typography.titleMedium)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("R", style = MaterialTheme.typography.labelSmall, modifier = Modifier.background(Color.DarkGray, androidx.compose.foundation.shape.RoundedCornerShape(2.dp)).padding(horizontal = 4.dp, vertical = 1.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.other), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Text(Format.money(128.0, state.currency), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun Seg(label: String, selected: Boolean, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier.clickableNoRipple(onClick),
        shape = CircleShape,
        color = if (selected) color.copy(alpha = 0.22f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
    ) {
        Text(label, color = if (selected) color else MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(vertical = 12.dp))
    }
}

package com.lop.budget.ui.screens.goals

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.lop.budget.ui.components.CircleIcon
import com.lop.budget.ui.components.FloatingCard
import com.lop.budget.ui.components.ScreenPadding
import com.lop.budget.ui.theme.LopTheme
import com.lop.budget.util.Format
import com.lop.budget.util.IconMapper

@Composable
fun GoalsScreen(vm: GoalsViewModel = hiltViewModel()) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val ext = LopTheme.extended

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = ScreenPadding,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { Text("Objectifs", style = MaterialTheme.typography.headlineMedium) }

        items(state.goals, key = { "g${it.id}" }) { goal ->
            ProgressCard(
                name = goal.name,
                icon = goal.icon,
                colorArgb = goal.colorArgb,
                subtitle = "",
                currentAmount = goal.savedAmount,
                targetAmount = goal.targetAmount,
                currency = state.currency,
                accentColor = ext.income,
            )
        }

        item { Text("Dettes", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp)) }

        items(state.debts, key = { "d${it.id}" }) { debt ->
            ProgressCard(
                name = debt.name,
                icon = debt.icon,
                colorArgb = debt.colorArgb,
                subtitle = "Remboursé ",
                currentAmount = debt.repaidAmount,
                targetAmount = debt.totalAmount,
                currency = state.currency,
                accentColor = ext.expense,
            )
        }
    }
}

@Composable
private fun ProgressCard(
    name: String,
    icon: String,
    colorArgb: Int,
    subtitle: String,
    currentAmount: Double,
    targetAmount: Double,
    currency: String,
    accentColor: Color,
) {
    val progress = (currentAmount / targetAmount).coerceIn(0.0, 1.0)
    FloatingCard(Modifier.fillMaxWidth()) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircleIcon(IconMapper.get(icon), Color(colorArgb), Color(colorArgb).copy(alpha = 0.18f))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.titleMedium)
                    Text("$subtitle${Format.money(currentAmount, currency)} / ${Format.money(targetAmount, currency)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("${(progress * 100).toInt()} %", style = MaterialTheme.typography.titleMedium, color = accentColor, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(12.dp))
            ProgressBar(progress.toFloat(), accentColor)
        }
    }
}

@Composable
private fun ProgressBar(progress: Float, color: Color) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            Modifier
                .fillMaxWidth(progress)
                .height(10.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(color),
        )
    }
}

package com.lop.budget.ui.screens.goals

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lop.budget.R
import com.lop.budget.data.local.entity.DebtEntity
import com.lop.budget.data.local.entity.GoalEntity
import com.lop.budget.ui.components.CircleIcon
import com.lop.budget.ui.components.FloatingCard
import com.lop.budget.ui.components.LopScreenScaffold
import com.lop.budget.ui.theme.LopTheme
import com.lop.budget.util.Format
import com.lop.budget.util.IconMapper

@Composable
fun GoalsScreen(
    vm: GoalsViewModel = hiltViewModel(),
    onBack: () -> Unit = {}
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val ext = LopTheme.extended

    LopScreenScaffold(
        title = stringResource(R.string.goals_title),
        onBack = onBack,
        navigationIcon = Icons.AutoMirrored.Filled.ArrowBack
    ) {
        items(state.goals, key = { "g${it.id}" }) { goal ->
            GoalCard(goal, state.currency, ext.income)
        }

        item { Text(stringResource(R.string.debts_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp)) }

        items(state.debts, key = { "d${it.id}" }) { debt ->
            DebtCard(debt, state.currency, ext.expense)
        }
    }
}

@Composable
private fun GoalCard(goal: GoalEntity, currency: String, color: Color) {
    val progress = (goal.savedAmount / goal.targetAmount).coerceIn(0.0, 1.0)
    FloatingCard(Modifier.fillMaxWidth()) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircleIcon(IconMapper.get(goal.icon), Color(goal.colorArgb), Color(goal.colorArgb).copy(alpha = 0.18f))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(goal.name, style = MaterialTheme.typography.titleMedium)
                    Text("${Format.money(goal.savedAmount, currency)} / ${Format.money(goal.targetAmount, currency)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("${(progress * 100).toInt()} %", style = MaterialTheme.typography.titleMedium, color = color, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(12.dp))
            ProgressBar(progress.toFloat(), color)
        }
    }
}

@Composable
private fun DebtCard(debt: DebtEntity, currency: String, color: Color) {
    val progress = (debt.repaidAmount / debt.totalAmount).coerceIn(0.0, 1.0)
    FloatingCard(Modifier.fillMaxWidth()) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircleIcon(IconMapper.get(debt.icon), Color(debt.colorArgb), Color(debt.colorArgb).copy(alpha = 0.18f))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(debt.name, style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.debt_repaid_amount, Format.money(debt.repaidAmount, currency), Format.money(debt.totalAmount, currency)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("${(progress * 100).toInt()} %", style = MaterialTheme.typography.titleMedium, color = color, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(12.dp))
            ProgressBar(progress.toFloat(), color)
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

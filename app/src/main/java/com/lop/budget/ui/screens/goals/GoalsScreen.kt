package com.lop.budget.ui.screens.goals

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    onBack: () -> Unit = {},
    onAddGoal: () -> Unit = {},
    onEditGoal: (Long) -> Unit = {},
    onAddDebt: () -> Unit = {},
    onEditDebt: (Long) -> Unit = {}
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val ext = LopTheme.extended
    var selectedTab by remember { mutableIntStateOf(0) } // 0: Goals, 1: Debts

    LopScreenScaffold(
        title = stringResource(R.string.nav_goals),
        onBack = onBack,
        navigationIcon = Icons.AutoMirrored.Filled.ArrowBack,
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TabItem(
                    text = stringResource(R.string.goals_title),
                    selected = selectedTab == 0,
                    modifier = Modifier.weight(1f)
                ) { selectedTab = 0 }
                
                TabItem(
                    text = stringResource(R.string.debts_title),
                    selected = selectedTab == 1,
                    modifier = Modifier.weight(1f)
                ) { selectedTab = 1 }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (selectedTab == 0) stringResource(R.string.goals_title) else stringResource(R.string.debts_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = { if (selectedTab == 0) onAddGoal() else onAddDebt() }) {
                    Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.primary)
                }
            }
        }

        if (selectedTab == 0) {
            if (state.goals.isEmpty()) {
                item {
                    EmptyState(stringResource(R.string.tx_no_categories)) // TODO: Better string
                }
            } else {
                items(state.goals, key = { "g${it.id}" }) { goal ->
                    GoalCard(goal, state.currency, ext.income, onClick = { onEditGoal(goal.id) })
                }
            }
        } else {
            if (state.debts.isEmpty()) {
                item {
                    EmptyState(stringResource(R.string.no_accounts_to_show)) // TODO: Better string
                }
            } else {
                items(state.debts, key = { "d${it.id}" }) { debt ->
                    DebtCard(debt, state.currency, ext.expense, onClick = { onEditDebt(debt.id) })
                }
            }
        }
    }
}

@Composable
private fun TabItem(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        color = if (selected) MaterialTheme.colorScheme.surface else Color.Transparent,
        tonalElevation = if (selected) 2.dp else 0.dp
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun EmptyState(text: String) {
    Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun GoalCard(goal: GoalEntity, currency: String, color: Color, onClick: () -> Unit) {
    val progress = (goal.savedAmount / goal.targetAmount).coerceIn(0.0, 1.0)
    FloatingCard(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).clickable { onClick() }) {
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
private fun DebtCard(debt: DebtEntity, currency: String, color: Color, onClick: () -> Unit) {
    val progress = (debt.repaidAmount / debt.totalAmount).coerceIn(0.0, 1.0)
    FloatingCard(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).clickable { onClick() }) {
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

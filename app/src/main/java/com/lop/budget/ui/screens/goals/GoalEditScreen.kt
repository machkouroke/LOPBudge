package com.lop.budget.ui.screens.goals

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lop.budget.R
import com.lop.budget.ui.components.FloatingCard
import com.lop.budget.ui.components.LopScreenScaffold
import com.lop.budget.ui.components.LopTextField

@Composable
fun GoalEditScreen(
    onBack: () -> Unit,
    vm: GoalEditViewModel = hiltViewModel(),
) {
    val form by vm.form.collectAsStateWithLifecycle()

    LopScreenScaffold(
        title = if (form.name.isEmpty()) stringResource(R.string.goal_new_title) else stringResource(R.string.goal_edit_title),
        onBack = onBack,
        navigationIcon = Icons.AutoMirrored.Filled.ArrowBack,
        bottomBar = {
            Box(Modifier.fillMaxWidth().padding(20.dp)) {
                Button(
                    onClick = { vm.save(onBack) },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = MaterialTheme.shapes.medium,
                    enabled = form.name.isNotBlank() && form.targetAmount > 0
                ) {
                    Text(stringResource(R.string.save))
                }
            }
        }
    ) {
        item {
            FloatingCard {
                LopTextField(
                    label = stringResource(R.string.goal_name_label),
                    value = form.name,
                    onValueChange = vm::updateName,
                    placeholder = "Ex: Vacances au Japon"
                )
            }
        }

        item {
            FloatingCard {
                LopTextField(
                    label = stringResource(R.string.goal_target_amount_label),
                    value = if (form.targetAmount == 0.0) "" else form.targetAmount.toString(),
                    onValueChange = { it.toDoubleOrNull()?.let { vm.updateTargetAmount(it) } ?: vm.updateTargetAmount(0.0) },
                    keyboardType = KeyboardType.Decimal,
                    placeholder = "0.00"
                )
            }
        }
        
        // TODO: Icon & Color Picker
    }
}

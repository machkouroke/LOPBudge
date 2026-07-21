package com.lop.budget.ui.screens.goals

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lop.budget.R
import com.lop.budget.domain.model.DebtType
import com.lop.budget.ui.components.FloatingCard
import com.lop.budget.ui.components.LopScreenScaffold
import com.lop.budget.ui.components.LopTextField
import com.lop.budget.ui.screens.transaction.SelectorRow
import com.lop.budget.util.Format

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebtEditScreen(
    onBack: () -> Unit,
    vm: DebtEditViewModel = hiltViewModel(),
) {
    val form by vm.form.collectAsStateWithLifecycle()

    LopScreenScaffold(
        title = if (form.name.isEmpty()) stringResource(R.string.debt_new_title) else stringResource(R.string.debt_edit_title),
        onBack = onBack,
        navigationIcon = Icons.AutoMirrored.Filled.ArrowBack,
        bottomBar = {
            Box(Modifier.fillMaxWidth().padding(20.dp)) {
                Button(
                    onClick = { vm.save(onBack) },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = MaterialTheme.shapes.medium,
                    enabled = form.name.isNotBlank() && form.totalAmount > 0
                ) {
                    Text(stringResource(R.string.save))
                }
            }
        }
    ) {
        item {
            FloatingCard {
                Column {
                    var expanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = { expanded = it },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        LopTextField(
                            label = stringResource(R.string.debt_type_label),
                            value = stringResource(form.debtType.labelRes),
                            onValueChange = {},
                            readOnly = true,
                            trailing = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                            modifier = Modifier.menuAnchor()
                        )
                        ExposedDropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            DebtType.entries.forEach { type ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(type.labelRes)) },
                                    onClick = {
                                        vm.updateDebtType(type)
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }

                    LopTextField(
                        label = stringResource(R.string.debt_name_label),
                        value = form.name,
                        onValueChange = vm::updateName,
                        placeholder = "Ex: Prêt voiture",
                        modifier = Modifier.padding(top = 16.dp)
                    )

                    LopTextField(
                        label = stringResource(R.string.creditor_name_label),
                        value = form.creditorName,
                        onValueChange = vm::updateCreditor,
                        placeholder = "Ex: Boursorama",
                        modifier = Modifier.padding(top = 16.dp)
                    )
                }
            }
        }

        item {
            FloatingCard {
                Column {
                    LopTextField(
                        label = stringResource(R.string.debt_total_amount_label),
                        value = if (form.totalAmount == 0.0) "" else form.totalAmount.toString(),
                        onValueChange = { it.toDoubleOrNull()?.let { vm.updateTotalAmount(it) } ?: vm.updateTotalAmount(0.0) },
                        keyboardType = KeyboardType.Decimal,
                        placeholder = "0.00"
                    )

                    LopTextField(
                        label = stringResource(R.string.starting_balance_label),
                        value = if (form.startingBalance == 0.0) "" else form.startingBalance.toString(),
                        onValueChange = { it.toDoubleOrNull()?.let { vm.updateStartingBalance(it) } ?: vm.updateStartingBalance(0.0) },
                        keyboardType = KeyboardType.Decimal,
                        placeholder = "0.00",
                        modifier = Modifier.padding(top = 16.dp)
                    )

                    LopTextField(
                        label = stringResource(R.string.debt_interest_rate_label),
                        value = if (form.interestRate == 0.0) "" else form.interestRate.toString(),
                        onValueChange = { it.toDoubleOrNull()?.let { vm.updateInterestRate(it) } ?: vm.updateInterestRate(0.0) },
                        keyboardType = KeyboardType.Decimal,
                        placeholder = "0.0",
                        modifier = Modifier.padding(top = 16.dp)
                    )
                }
            }
        }

        item {
            var showDatePicker by remember { mutableStateOf(false) }
            val dateState = rememberDatePickerState(initialSelectedDateMillis = form.dueDate)

            if (showDatePicker) {
                DatePickerDialog(
                    onDismissRequest = { showDatePicker = false },
                    confirmButton = {
                        TextButton(onClick = {
                            vm.updateDueDate(dateState.selectedDateMillis)
                            showDatePicker = false
                        }) { Text(stringResource(R.string.ok)) }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            vm.updateDueDate(null)
                            showDatePicker = false
                        }) { Text(stringResource(R.string.none)) }
                    }
                ) { DatePicker(state = dateState) }
            }

            SelectorRow(
                label = stringResource(R.string.due_date_label),
                value = form.dueDate?.let { Format.fullDate(it) } ?: stringResource(R.string.none),
                icon = Icons.Default.DateRange,
                onClick = { showDatePicker = true }
            )
        }
    }
}

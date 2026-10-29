package com.lop.budget.ui.screens.goals

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lop.budget.R
import com.lop.budget.domain.model.DebtType
import com.lop.budget.domain.model.FinancialItemKind
import com.lop.budget.domain.usecase.financial.FinancialItemRefusal
import com.lop.budget.ui.common.TestTags
import com.lop.budget.ui.components.FloatingCard
import com.lop.budget.ui.components.LopDatePicker
import com.lop.budget.ui.components.LopScreenScaffold
import com.lop.budget.ui.components.LopTextField
import com.lop.budget.ui.components.SelectorRow
import com.lop.budget.util.Format

/** Formulaire d'un prêt : dette ou créance selon le sens reçu à l'ouverture (CA-01, CA-02). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebtEditScreen(
    onBack: () -> Unit,
    /** Appelé une fois l'enregistrement réussi, avec le type enregistré (CA-05). */
    onSaved: (FinancialItemKind) -> Unit = { onBack() },
    vm: DebtEditViewModel = hiltViewModel(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val form = state.form
    val refusals = state.refusals
    val isDebt = state.kind == FinancialItemKind.DEBT

    LaunchedEffect(state.savedKind) { state.savedKind?.let(onSaved) }
    val requestExit = rememberDraftExit(state.hasUnsavedChanges, onBack)

    LopScreenScaffold(
        // CA-01 : le titre suit le mode et le sens, jamais le contenu du nom.
        title = stringResource(
            when {
                state.isEdit -> R.string.debt_edit_title
                isDebt -> R.string.debt_new_title
                else -> R.string.receivable_new_title
            }
        ),
        onBack = requestExit,
        navigationIcon = Icons.AutoMirrored.Filled.ArrowBack,
        modifier = Modifier.testTag(TestTags.SCREEN_EDIT)
    ) {
        item {
            SaveButton(
                isSaving = state.isSaving,
                saveFailed = state.saveFailed,
                // Modification (LOP-205) : règle d'activation d'avant LOP-202, reconduite.
                enabled = !state.isEdit ||
                    (form.name.isNotBlank() && (Format.centsOrNull(form.totalInput) ?: 0L) > 0),
                onClick = vm::save,
                tagPrefix = "debt.edit",
            )
        }
        item {
            FloatingCard {
                Column {
                    // CA-02 : une créance ne propose ni type de dette ni taux.
                    if (isDebt) {
                        var expanded by remember { mutableStateOf(false) }
                        ExposedDropdownMenuBox(
                            expanded = expanded,
                            onExpandedChange = { expanded = it },
                            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                        ) {
                            LopTextField(
                                label = stringResource(R.string.debt_type_label),
                                value = stringResource(form.debtType.labelRes),
                                onValueChange = {},
                                readOnly = true,
                                trailing = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                                modifier = Modifier.menuAnchor().testTag("debt.edit.type")
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
                                        },
                                        modifier = Modifier.testTag("debt.edit.type.${type.name}")
                                    )
                                }
                            }
                        }
                    }

                    LopTextField(
                        label = stringResource(if (isDebt) R.string.debt_name_label else R.string.receivable_name_label),
                        value = form.name,
                        onValueChange = vm::updateName,
                        placeholder = if (isDebt) "Ex: Prêt voiture" else "Ex: Avance à Karim",
                        errorMessage = refusalMessage(refusals, FinancialItemRefusal.BlankName),
                        modifier = Modifier.testTag("debt.edit.name")
                    )

                    // CA-01 : la contrepartie se lit dans le bon sens — à qui je dois, qui me doit.
                    LopTextField(
                        label = stringResource(if (isDebt) R.string.creditor_name_label else R.string.debtor_name_label),
                        value = form.creditorName,
                        onValueChange = vm::updateCreditor,
                        placeholder = if (isDebt) "Ex: Boursorama" else "Ex: Karim",
                        modifier = Modifier.padding(top = 16.dp).testTag("debt.edit.creditor")
                    )
                }
            }
        }

        item {
            FloatingCard {
                Column {
                    LopTextField(
                        label = stringResource(R.string.debt_total_amount_label),
                        value = form.totalInput,
                        onValueChange = vm::updateTotalAmount,
                        keyboardType = KeyboardType.Decimal,
                        placeholder = "0.00",
                        errorMessage = refusalMessage(refusals, FinancialItemRefusal.InvalidTarget),
                        modifier = Modifier.testTag("debt.edit.total")
                    )

                    LopTextField(
                        label = stringResource(R.string.starting_balance_label),
                        value = form.startingInput,
                        onValueChange = vm::updateStartingBalance,
                        keyboardType = KeyboardType.Decimal,
                        placeholder = "0.00",
                        errorMessage = refusalMessage(
                            refusals,
                            FinancialItemRefusal.InvalidStarting,
                            FinancialItemRefusal.StartingAboveTarget,
                        ),
                        modifier = Modifier.padding(top = 16.dp).testTag("debt.edit.starting")
                    )

                    if (isDebt) {
                        LopTextField(
                            label = stringResource(R.string.debt_interest_rate_label),
                            value = form.rateInput,
                            onValueChange = vm::updateInterestRate,
                            keyboardType = KeyboardType.Decimal,
                            placeholder = "0.0",
                            errorMessage = refusalMessage(refusals, FinancialItemRefusal.InvalidRate),
                            modifier = Modifier.padding(top = 16.dp).testTag("debt.edit.rate")
                        )
                    }
                }
            }
        }

        item {
            var showDatePicker by remember { mutableStateOf(false) }

            if (showDatePicker) {
                LopDatePicker(
                    initialDateMillis = form.dueDate,
                    onDateSelected = { vm.updateDueDate(it) },
                    onDismiss = { showDatePicker = false }
                )
            }

            SelectorRow(
                label = stringResource(R.string.due_date_label),
                value = form.dueDate?.let { Format.fullDate(it) } ?: stringResource(R.string.none),
                icon = Icons.Default.DateRange,
                onClick = { showDatePicker = true },
                modifier = Modifier.testTag("debt.edit.date")
            )
        }

        // Modification (LOP-205) : le formulaire reste celui d'avant LOP-202.
        if (!state.isEdit) {
            creationExtraFields(
                tagPrefix = "debt.edit",
                accounts = state.accounts,
                accountId = form.accountId,
                refusals = refusals,
                onAccount = vm::updateAccount,
                icon = form.icon,
                onIcon = vm::updateIcon,
                colorArgb = form.colorArgb,
                onColor = vm::updateColor,
                comment = form.comment,
                onComment = vm::updateComment,
            )
        }
    }
}

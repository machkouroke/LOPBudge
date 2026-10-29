package com.lop.budget.ui.screens.goals

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DateRange
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
import com.lop.budget.domain.model.FinancialItemKind
import com.lop.budget.domain.usecase.financial.FinancialItemRefusal
import com.lop.budget.ui.common.TestTags
import com.lop.budget.ui.components.FloatingCard
import com.lop.budget.ui.components.LopDatePicker
import com.lop.budget.ui.components.LopScreenScaffold
import com.lop.budget.ui.components.LopTextField
import com.lop.budget.ui.components.SelectorRow
import com.lop.budget.util.Format

@Composable
fun GoalEditScreen(
    onBack: () -> Unit,
    /** Appelé une fois l'enregistrement réussi, avec le type enregistré (CA-05). */
    onSaved: (FinancialItemKind) -> Unit = { onBack() },
    vm: GoalEditViewModel = hiltViewModel(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val form = state.form
    val refusals = state.refusals

    LaunchedEffect(state.savedKind) { state.savedKind?.let(onSaved) }
    val requestExit = rememberDraftExit(state.hasUnsavedChanges, onBack)

    LopScreenScaffold(
        // CA-01 : le titre suit le mode, jamais le contenu du nom.
        title = stringResource(if (state.isEdit) R.string.goal_edit_title else R.string.goal_new_title),
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
                    (form.name.isNotBlank() && (Format.centsOrNull(form.targetInput) ?: 0L) > 0),
                onClick = vm::save,
                tagPrefix = "goal.edit",
            )
        }
        item {
            FloatingCard {
                Column {
                    LopTextField(
                        label = stringResource(R.string.goal_name_label),
                        value = form.name,
                        onValueChange = vm::updateName,
                        placeholder = "Ex: Vacances au Japon",
                        errorMessage = refusalMessage(refusals, FinancialItemRefusal.BlankName),
                        modifier = Modifier.testTag("goal.edit.name")
                    )
                }
            }
        }

        item {
            FloatingCard {
                Column {
                    LopTextField(
                        label = stringResource(R.string.goal_target_amount_label),
                        value = form.targetInput,
                        onValueChange = vm::updateTargetAmount,
                        keyboardType = KeyboardType.Decimal,
                        placeholder = "0.00",
                        errorMessage = refusalMessage(refusals, FinancialItemRefusal.InvalidTarget),
                        modifier = Modifier.testTag("goal.edit.target")
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
                        modifier = Modifier.padding(top = 16.dp).testTag("goal.edit.starting")
                    )
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
                modifier = Modifier.testTag("goal.edit.date")
            )
        }

        // Modification (LOP-205) : le formulaire reste celui d'avant LOP-202.
        if (!state.isEdit) {
            creationExtraFields(
                tagPrefix = "goal.edit",
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

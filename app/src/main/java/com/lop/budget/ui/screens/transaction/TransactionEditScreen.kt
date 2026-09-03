package com.lop.budget.ui.screens.transaction

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.lop.budget.R
import com.lop.budget.domain.model.RecurrenceFrequency
import com.lop.budget.ui.common.TestTags
import com.lop.budget.ui.components.CategoryBottomSheet
import com.lop.budget.ui.components.LopDatePicker
import com.lop.budget.ui.components.LopScreenScaffold
import com.lop.budget.ui.components.PickerBottomSheet
import com.lop.budget.util.IconMapper


enum class EditSheet { Category, Account, Tags, Goal, Debt, Frequency, Date, EndDate }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TransactionEditScreen(
    onDone: (Long) -> Unit,
    onNavigateToCreateCategory: () -> Unit,
    vm: TransactionEditViewModel = hiltViewModel(),
) {
    val form by vm.form.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val tags by vm.tags.collectAsStateWithLifecycle()
    val goals by vm.goals.collectAsStateWithLifecycle()
    val debts by vm.debts.collectAsStateWithLifecycle()
    val showAlert by vm.showBalanceImpactAlert.collectAsStateWithLifecycle()
    val isSaving by vm.isSaving.collectAsStateWithLifecycle()


    var activeSheet by rememberSaveable { mutableStateOf<EditSheet?>(null) }
    var autoOpenedCategory by rememberSaveable { mutableStateOf(false) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle

    LaunchedEffect(vm.isEditing) {
        if (vm.isEditing || autoOpenedCategory) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (!autoOpenedCategory) {
                activeSheet = EditSheet.Category
                autoOpenedCategory = true
            }
        }
    }
    if (showAlert) {
        BalanceImpactDialog(
            onConfirm = { accountNow -> vm.confirmSave(accountNow, onDone) },
            onDismiss = vm::dismissAlert
        )
    }

    LopScreenScaffold(
        title = if (vm.isEditing) stringResource(R.string.tx_edit_title) else stringResource(R.string.tx_new_title),
        onBack = { onDone(vm.editingTransactionId ?: 0L) },
        navigationIcon = Icons.AutoMirrored.Filled.ArrowBack,
        modifier = Modifier.testTag(TestTags.SCREEN_EDIT),
        bottomBar = {
            Box(Modifier.fillMaxWidth().padding(20.dp)) {
                Button(
                    onClick = { vm.save(onDone) },
                    modifier = Modifier.fillMaxWidth().height(56.dp).testTag(TestTags.BTN_SAVE),
                    shape = MaterialTheme.shapes.medium,
                    enabled = form.isValid && !isSaving
                ) {
                    Text(stringResource(R.string.save))
                }
            }
        }
    ) {
        item {
            MainSection(
                form = form,
                isPaidToggleVisible = vm.isPaidToggleVisible,
                onSetType = vm::setType,
                onSetAmount = vm::setAmountRaw,
                onSetTitle = vm::setTitle,
                onSetStatus = vm::setStatus
            )
        }

        item {
            ClassificationSection(
                form = form,
                categories = categories,
                accounts = accounts,
                goals = goals,
                debts = debts,
                tags = tags,
                onOpenCategory = { activeSheet = EditSheet.Category },
                onOpenAccount = { activeSheet = EditSheet.Account },
                onOpenGoal = { activeSheet = EditSheet.Goal },
                onOpenDebt = { activeSheet = EditSheet.Debt },
                onOpenTags = { activeSheet = EditSheet.Tags }
            )
        }

        item {
            OptionalSection(
                form = form,
                onSetNote = vm::setNote,
                onOpenDate = { activeSheet = EditSheet.Date }
            )
        }

        if (vm.isRecurrenceSectionVisible) {
            item {
                RecurrenceSection(
                    form = form,
                    onSetInterval = vm::setInterval,
                    onToggleDow = vm::toggleDayOfWeek,
                    onSetEndDate = vm::setEndDate,
                    onSetMaxOccurrences = vm::setMaxOccurrences,
                    onOpenFrequency = { activeSheet = EditSheet.Frequency },
                    onOpenEndDate = { activeSheet = EditSheet.EndDate }
                )
            }
        }
    }

    when (activeSheet) {
        EditSheet.Category -> {
            CategoryBottomSheet(
                title = stringResource(R.string.tx_category_sheet_title),
                categories = categories.filter { it.type == form.type },
                selectedId = form.categoryId,
                onSelect = { id ->
                    vm.setCategory(id)
                    activeSheet = null
                },
                onCreate = {
                    activeSheet = null
                    onNavigateToCreateCategory()
                },
                onDismiss = { activeSheet = null },
            )
        }
        EditSheet.Account -> {
            PickerBottomSheet(
                title = stringResource(R.string.tx_account_sheet_title),
                items = accounts,
                isSelected = { it.id == form.accountId },
                onSelect = { account ->
                    account?.let { vm.setAccount(it.id) }
                    activeSheet = null
                },
                onDismiss = { activeSheet = null },
                itemLabel = { it.name },
                emptyText = stringResource(R.string.tx_no_accounts),
                itemIcon = { IconMapper.get(it.icon) },
                itemTint = { Color(it.colorArgb) }
            )
        }
        EditSheet.Tags -> {
            TagsBottomSheet(
                tags = tags,
                selectedTagIds = form.tagIds,
                onToggleTag = vm::toggleTag,
                onCreateTag = { name, color -> vm.createTag(name, color) },
                onDismiss = { activeSheet = null },
            )
        }
        EditSheet.Goal -> {
            PickerBottomSheet(
                title = stringResource(R.string.tx_linked_goal_label),
                items = goals,
                isSelected = { it.id == form.linkedGoalId },
                onSelect = { goal ->
                    vm.setGoal(goal?.id)
                    activeSheet = null
                },
                onDismiss = { activeSheet = null },
                itemLabel = { it.name },
                allowNone = true,
                noneLabel = stringResource(R.string.none),
                itemIcon = { IconMapper.get(it.icon) },
                itemTint = { Color(it.colorArgb) }
            )
        }
        EditSheet.Debt -> {
            PickerBottomSheet(
                title = stringResource(R.string.tx_linked_debt_label),
                items = debts,
                isSelected = { it.id == form.linkedDebtId },
                onSelect = { debt ->
                    vm.setDebt(debt?.id)
                    activeSheet = null
                },
                onDismiss = { activeSheet = null },
                itemLabel = { it.name },
                allowNone = true,
                noneLabel = stringResource(R.string.none),
                itemIcon = { IconMapper.get(it.icon) },
                itemTint = { Color(it.colorArgb) }
            )
        }
        EditSheet.Frequency -> {
            PickerBottomSheet(
                title = stringResource(R.string.tx_repeat_label),
                items = RecurrenceFrequency.entries.toList(),
                isSelected = { it == form.frequency },
                onSelect = { freq ->
                    freq?.let { vm.setFrequency(it) }
                    activeSheet = null
                },
                onDismiss = { activeSheet = null },
                itemLabel = { freq ->
                    stringResource(when (freq) {
                        RecurrenceFrequency.NONE -> R.string.tx_repeat_none
                        RecurrenceFrequency.DAILY -> R.string.tx_repeat_daily
                        RecurrenceFrequency.WEEKLY -> R.string.tx_repeat_weekly
                        RecurrenceFrequency.MONTHLY -> R.string.tx_repeat_monthly
                        RecurrenceFrequency.YEARLY -> R.string.tx_repeat_yearly
                    })
                }
            )
        }
        EditSheet.Date -> {
            LopDatePicker(
                initialDateMillis = form.date,
                onDateSelected = { it?.let { vm.setDate(it) }; activeSheet = null },
                onDismiss = { activeSheet = null }
            )
        }
        EditSheet.EndDate -> {
            LopDatePicker(
                initialDateMillis = form.endDate ?: form.date,
                onDateSelected = { it?.let { vm.setEndDate(it) }; activeSheet = null },
                onDismiss = { activeSheet = null }
            )
        }
        null -> {}
    }
}

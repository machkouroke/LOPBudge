package com.lop.budget.ui.screens.transaction

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lop.budget.R
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.TagEntity
import com.lop.budget.domain.model.RecurrenceFrequency
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.ui.components.CategoryBottomSheet
import com.lop.budget.ui.components.CircleIcon
import com.lop.budget.ui.components.FloatingCard
import com.lop.budget.ui.components.HapticIntent
import com.lop.budget.ui.components.LopScreenScaffold
import com.lop.budget.ui.components.PillTag
import com.lop.budget.ui.components.PressScale
import com.lop.budget.ui.components.pressScaleClickable
import com.lop.budget.ui.theme.LopTheme
import com.lop.budget.util.IconMapper

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TransactionEditScreen(
    onBack: () -> Unit,
    onNavigateToCreateCategory: () -> Unit,
    vm: TransactionEditViewModel = hiltViewModel(),
) {
    val form by vm.form.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val tags by vm.tags.collectAsStateWithLifecycle()
    val goals by vm.goals.collectAsStateWithLifecycle()
    val debts by vm.debts.collectAsStateWithLifecycle()

    var showCategorySheet by remember { mutableStateOf(!vm.isEditing) }
    var showAccountSheet by remember { mutableStateOf(false) }
    var showTagsSheet by remember { mutableStateOf(false) }
    var showGoalSheet by remember { mutableStateOf(false) }
    var showDebtSheet by remember { mutableStateOf(false) }

    val typeCategories = categories.filter { it.type == form.type }

    LopScreenScaffold(
        title = if (vm.isEditing) stringResource(R.string.tx_edit_title) else stringResource(R.string.tx_new_title),
        onBack = onBack,
        navigationIcon = Icons.AutoMirrored.Filled.ArrowBack,
        bottomBar = {
            Box(Modifier.fillMaxWidth().padding(20.dp)) {
                Button(
                    onClick = { vm.save(onBack) },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = MaterialTheme.shapes.medium,
                    enabled = form.amount > 0.0 && form.categoryId != null && form.accountId != null
                ) {
                    Text(stringResource(R.string.save))
                }
            }
        }
    ) {
        item { SectionTitle(stringResource(R.string.tx_section_main)) }

        item {
            FloatingCard {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    // Type Toggle
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surface),
                    ) {
                        TypeSegment(
                            label = stringResource(R.string.tx_type_expense),
                            selected = form.type == TransactionType.EXPENSE,
                            color = LopTheme.extended.expense,
                            modifier = Modifier.weight(1f)
                        ) { vm.setType(TransactionType.EXPENSE) }

                        TypeSegment(
                            label = stringResource(R.string.tx_type_income),
                            selected = form.type == TransactionType.INCOME,
                            color = LopTheme.extended.income,
                            modifier = Modifier.weight(1f)
                        ) { vm.setType(TransactionType.INCOME) }
                    }

                    // Amount
                    FilledField(
                        label = stringResource(R.string.tx_amount_label),
                        value = form.amountInput,
                        onValueChange = vm::setAmountRaw,
                        keyboardType = KeyboardType.Decimal,
                        textStyle = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
                        leading = { Text(if (form.type == TransactionType.EXPENSE) "−" else "+", style = MaterialTheme.typography.displaySmall) },
                        trailing = { Text("€", style = MaterialTheme.typography.titleLarge) }
                    )

                    // Title
                    FilledField(
                        label = stringResource(R.string.tx_name_label),
                        value = form.title,
                        onValueChange = vm::setTitle,
                        keyboardType = KeyboardType.Text,
                        textStyle = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }

        item { SectionTitle(stringResource(R.string.tx_section_classification)) }

        item {
            FloatingCard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val selectedCat = categories.find { it.id == form.categoryId }
                    SelectorRow(
                        label = stringResource(R.string.tx_category_label),
                        value = selectedCat?.name ?: stringResource(R.string.tx_select_category),
                        icon = selectedCat?.let { IconMapper.get(it.icon) } ?: Icons.Filled.Category,
                        iconTint = selectedCat?.let { Color(it.colorArgb) },
                        onClick = { showCategorySheet = true },
                    )

                    if (selectedCat != null) {
                        val subCats = categories.filter { it.parentCategoryId == selectedCat.id }
                        if (subCats.isNotEmpty()) {
                            val selectedSub = categories.find { it.id == form.subCategoryId }
                            SelectorRow(
                                label = "Sous-catégorie",
                                value = selectedSub?.name ?: "Choisir une sous-catégorie...",
                                icon = selectedSub?.let { IconMapper.get(it.icon) } ?: Icons.Filled.KeyboardArrowDown,
                                iconTint = selectedSub?.let { Color(it.colorArgb) },
                                onClick = { showCategorySheet = true }
                            )
                        }
                    }

                    val selectedAcc = accounts.find { it.id == form.accountId }
                    SelectorRow(
                        label = stringResource(R.string.tx_account_label),
                        value = selectedAcc?.name ?: stringResource(R.string.tx_select_account),
                        onClick = { showAccountSheet = true },
                        trailingChevron = true,
                    )

                    if (form.type == TransactionType.EXPENSE) {
                        val selectedGoal = goals.find { it.id == form.linkedGoalId }
                        SelectorRow(
                            label = stringResource(R.string.tx_linked_goal_label),
                            value = selectedGoal?.name ?: stringResource(R.string.tx_no_goal_linked),
                            icon = selectedGoal?.let { IconMapper.get(it.icon) } ?: Icons.Default.Add,
                            onClick = { showGoalSheet = true }
                        )

                        val selectedDebt = debts.find { it.id == form.linkedDebtId }
                        SelectorRow(
                            label = stringResource(R.string.tx_linked_debt_label),
                            value = selectedDebt?.name ?: stringResource(R.string.tx_no_debt_linked),
                            icon = selectedDebt?.let { IconMapper.get(it.icon) } ?: Icons.Default.Add,
                            onClick = { showDebtSheet = true }
                        )
                    }

                    val selectedTags = tags.filter { it.id in form.tagIds }
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { showTagsSheet = true },
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                stringResource(R.string.tx_tags_label),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.height(8.dp))
                            if (selectedTags.isEmpty()) {
                                Text(
                                    stringResource(R.string.tx_no_tags),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(selectedTags) { tag ->
                                        PillTag(text = tag.name, color = Color(tag.colorArgb))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        item { SectionTitle(stringResource(R.string.tx_section_optional)) }

        item {
            FloatingCard {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    FilledField(
                        label = stringResource(R.string.tx_notes_label),
                        value = form.note,
                        onValueChange = vm::setNote,
                        keyboardType = KeyboardType.Text,
                        textStyle = MaterialTheme.typography.bodyLarge,
                        minLines = 2
                    )

                    val dateState = rememberDatePickerState(initialSelectedDateMillis = form.date)
                    var showDatePickerInternal by remember { mutableStateOf(false) }

                    if (showDatePickerInternal) {
                        DatePickerDialog(
                            onDismissRequest = { showDatePickerInternal = false },
                            confirmButton = {
                                TextButton(onClick = {
                                    dateState.selectedDateMillis?.let { vm.setDate(it) }
                                    showDatePickerInternal = false
                                }) { Text(stringResource(R.string.ok)) }
                            }
                        ) { DatePicker(state = dateState) }
                    }

                    SelectorRow(
                        label = stringResource(R.string.tx_date_label),
                        value = com.lop.budget.util.Format.fullDate(form.date),
                        icon = Icons.Default.DateRange,
                        onClick = { showDatePickerInternal = true }
                    )
                }
            }
        }

        item { SectionTitle(stringResource(R.string.tx_repeat_label)) }

        item {
            FloatingCard {
                RecurrenceBlock(
                    form = form,
                    onSetFrequency = vm::setFrequency,
                    onSetInterval = vm::setInterval,
                    onToggleDow = vm::toggleDayOfWeek,
                    onSetEndDate = vm::setEndDate,
                    onSetMaxOccurrences = vm::setMaxOccurrences,
                )
            }
        }
    }

    if (showCategorySheet) {
        CategoryBottomSheet(
            title = stringResource(R.string.tx_category_sheet_title),
            categories = typeCategories,
            selectedId = form.categoryId,
            onSelect = { id ->
                val selected = categories.find { it.id == id }
                if (selected?.parentCategoryId != null) {
                    vm.setCategory(selected.parentCategoryId)
                    vm.setSubCategory(id)
                } else {
                    vm.setCategory(id)
                }
                showCategorySheet = false
            },
            onCreate = {
                showCategorySheet = false
                onNavigateToCreateCategory()
            },
            onDismiss = { showCategorySheet = false },
        )
    }

    if (showAccountSheet) {
        AccountBottomSheet(
            title = stringResource(R.string.tx_account_sheet_title),
            accounts = accounts,
            selectedId = form.accountId,
            onSelect = { id ->
                vm.setAccount(id)
                showAccountSheet = false
            },
            onDismiss = { showAccountSheet = false }
        )
    }

    if (showTagsSheet) {
        TagsBottomSheet(
            tags = tags,
            selectedTagIds = form.tagIds,
            onToggleTag = { id ->
                if (form.tagIds.contains(id) || form.tagIds.size < 3) {
                    vm.toggleTag(id)
                }
            },
            onCreateTag = { name, color -> vm.createTag(name, color) },
            onDeleteTag = { id -> vm.deleteTag(id) },
            onDismiss = { showTagsSheet = false },
        )
    }

    if (showGoalSheet) {
        GoalBottomSheet(
            goals = goals,
            selectedId = form.linkedGoalId,
            onSelect = { id ->
                vm.setGoal(id)
                showGoalSheet = false
            },
            onDismiss = { showGoalSheet = false }
        )
    }

    if (showDebtSheet) {
        DebtBottomSheet(
            debts = debts,
            selectedId = form.linkedDebtId,
            onSelect = { id ->
                vm.setDebt(id)
                showDebtSheet = false
            },
            onDismiss = { showDebtSheet = false }
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, top = 8.dp, bottom = 4.dp)
    )
}

@Composable
private fun TypeSegment(
    label: String,
    selected: Boolean,
    color: Color,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier
            .fillMaxHeight()
            .clickable(onClick = onClick),
        color = if (selected) color.copy(alpha = 0.15f) else Color.Transparent,
        shape = RoundedCornerShape(10.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) color else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
            )
        }
    }
}

@Composable
private fun FilledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType,
    textStyle: androidx.compose.ui.text.TextStyle,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    minLines: Int = 1,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp)
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            textStyle = textStyle,
            leadingIcon = leading,
            trailingIcon = trailing,
            minLines = minLines,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f),
                focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
            )
        )
    }
}

@Composable
private fun SelectorRow(
    label: String,
    value: String,
    icon: Any? = null,
    iconTint: Color? = null,
    trailingChevron: Boolean = true,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                CircleIcon(
                    icon = icon,
                    tint = iconTint ?: MaterialTheme.colorScheme.primary,
                    background = (iconTint ?: MaterialTheme.colorScheme.primary).copy(alpha = 0.12f),
                    size = 36.dp
                )
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold
                )
            }

            if (trailingChevron) {
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
        }
    }
}

@Composable
private fun RecurrenceBlock(
    form: TransactionForm,
    onSetFrequency: (RecurrenceFrequency) -> Unit,
    onSetInterval: (Int) -> Unit,
    onToggleDow: (Int) -> Unit,
    onSetEndDate: (Long?) -> Unit,
    onSetMaxOccurrences: (Int?) -> Unit,
) {
    var showAdvanced by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                stringResource(R.string.tx_repeat_label),
                style = MaterialTheme.typography.titleMedium
            )
            TextButton(onClick = { showAdvanced = !showAdvanced }) {
                Text(if (showAdvanced) "Moins" else stringResource(R.string.tx_repeat_advanced))
                Icon(
                    if (showAdvanced) Icons.Default.KeyboardArrowDown else Icons.Default.ChevronRight,
                    null
                )
            }
        }

        // Quick frequencies
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val frequencies = listOf(
                RecurrenceFrequency.NONE to R.string.tx_repeat_none,
                RecurrenceFrequency.DAILY to R.string.tx_repeat_daily,
                RecurrenceFrequency.WEEKLY to R.string.tx_repeat_weekly,
                RecurrenceFrequency.MONTHLY to R.string.tx_repeat_monthly,
                RecurrenceFrequency.YEARLY to R.string.tx_repeat_yearly
            )
            items(frequencies) { (freq, labelRes) ->
                FilterChip(
                    selected = form.frequency == freq,
                    onClick = { onSetFrequency(freq) },
                    label = { Text(stringResource(labelRes)) }
                )
            }
        }

        AnimatedVisibility(visible = showAdvanced && form.frequency != RecurrenceFrequency.NONE) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // Interval
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.tx_repeat_every))
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = form.interval.toString(),
                        onValueChange = { it.toIntOrNull()?.let { v -> onSetInterval(v) } },
                        modifier = Modifier
                            .width(80.dp)
                            .height(50.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        textStyle = androidx.compose.ui.text.TextStyle(textAlign = TextAlign.Center)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.tx_repeat_intervals))
                }

                // Days of week for weekly
                if (form.frequency == RecurrenceFrequency.WEEKLY) {
                    Text(
                        stringResource(R.string.tx_repeat_days_of_week),
                        style = MaterialTheme.typography.labelLarge
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        (1..7).forEach { day ->
                            val selected = form.daysOfWeek.contains(day)
                            Surface(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .clickable { onToggleDow(day) },
                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = when (day) {
                                            1 -> "L"; 2 -> "M"; 3 -> "M"; 4 -> "J"; 5 -> "V"; 6 -> "S"; else -> "D"
                                        },
                                        color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                // End condition
                HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))
                Text(
                    stringResource(R.string.tx_repeat_ends),
                    style = MaterialTheme.typography.labelLarge
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = form.endDate == null && form.maxOccurrences == null,
                        onClick = {
                            onSetEndDate(null)
                            onSetMaxOccurrences(null)
                        }
                    )
                    Text(stringResource(R.string.tx_repeat_ends_never))
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = form.maxOccurrences != null,
                        onClick = { onSetMaxOccurrences(12) }
                    )
                    Text(stringResource(R.string.tx_repeat_ends_after))
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = form.maxOccurrences?.toString() ?: "",
                        onValueChange = { it.toIntOrNull()?.let { v -> onSetMaxOccurrences(v) } },
                        modifier = Modifier
                            .width(80.dp)
                            .height(50.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        textStyle = androidx.compose.ui.text.TextStyle(textAlign = TextAlign.Center)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.tx_repeat_occurrences_label))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountBottomSheet(
    title: String,
    accounts: List<AccountEntity>,
    selectedId: Long?,
    onSelect: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        scrimColor = Color.Black.copy(alpha = 0.55f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )

            if (accounts.isEmpty()) {
                Text(
                    stringResource(R.string.tx_no_accounts),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            accounts.forEach { account ->
                val selected = account.id == selectedId
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .pressScaleClickable(
                            intent = HapticIntent.Selection,
                            pressedScale = 0.98f
                        ) { onSelect(account.id) },
                    color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.10f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val c = Color(account.colorArgb)
                        CircleIcon(
                            icon = IconMapper.get(account.icon),
                            tint = c,
                            background = c.copy(alpha = 0.14f),
                            size = 38.dp,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            account.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        if (selected) {
                            Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TagsBottomSheet(
    tags: List<TagEntity>,
    selectedTagIds: Set<Long>,
    onToggleTag: (Long) -> Unit,
    onCreateTag: (String, Int) -> Unit,
    onDeleteTag: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                stringResource(R.string.tx_tags_sheet_title),
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                stringResource(R.string.tx_tags_sheet_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(16.dp))

            // Liste des tags existants
            PressScale(
                modifier = Modifier.fillMaxWidth(),
                onClick = {}
            ) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    tags.forEach { tag ->
                        val selected = selectedTagIds.contains(tag.id)
                        FilterChip(
                            selected = selected,
                            onClick = { onToggleTag(tag.id) },
                            label = { Text(tag.name) },
                            leadingIcon = if (selected) {
                                { Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp)) }
                            } else null
                        )
                    }
                }
            }

            Spacer(Modifier.height(32.dp))

            // Création d'un tag
            Text(
                stringResource(R.string.tx_tags_create_title),
                style = MaterialTheme.typography.titleMedium
            )
            var newTagName by remember { mutableStateOf("") }
            Row(
                modifier = Modifier.padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = newTagName,
                    onValueChange = { newTagName = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.tx_tags_name_label)) },
                    singleLine = true
                )
                Spacer(Modifier.width(12.dp))
                IconButton(
                    onClick = {
                        if (newTagName.isNotBlank()) {
                            onCreateTag(newTagName, 0xFF9C27B0.toInt())
                            newTagName = ""
                        }
                    },
                    enabled = newTagName.isNotBlank()
                ) {
                    Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GoalBottomSheet(
    goals: List<com.lop.budget.data.local.entity.GoalEntity>,
    selectedId: Long?,
    onSelect: (Long?) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(20.dp).padding(bottom = 32.dp)) {
            Text(stringResource(R.string.tx_linked_goal_label), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))

            Surface(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { onSelect(null) },
                color = if (selectedId == null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
            ) {
                Text(stringResource(R.string.none), Modifier.padding(16.dp))
            }

            Spacer(Modifier.height(8.dp))

            goals.forEach { goal ->
                val selected = goal.id == selectedId
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(16.dp)).clickable { onSelect(goal.id) },
                    color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircleIcon(IconMapper.get(goal.icon), Color(goal.colorArgb), Color(goal.colorArgb).copy(alpha = 0.15f), size = 32.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(goal.name, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DebtBottomSheet(
    debts: List<com.lop.budget.data.local.entity.DebtEntity>,
    selectedId: Long?,
    onSelect: (Long?) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(20.dp).padding(bottom = 32.dp)) {
            Text(stringResource(R.string.tx_linked_debt_label), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))

            Surface(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { onSelect(null) },
                color = if (selectedId == null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
            ) {
                Text(stringResource(R.string.none), Modifier.padding(16.dp))
            }

            Spacer(Modifier.height(8.dp))

            debts.forEach { debt ->
                val selected = debt.id == selectedId
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(16.dp)).clickable { onSelect(debt.id) },
                    color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircleIcon(IconMapper.get(debt.icon), Color(debt.colorArgb), Color(debt.colorArgb).copy(alpha = 0.15f), size = 32.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(debt.name, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}

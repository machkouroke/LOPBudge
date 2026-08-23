package com.lop.budget.ui.screens.transaction

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.EventRepeat
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lop.budget.BuildConfig
import com.lop.budget.R
import com.lop.budget.data.local.entity.AccountEntity
import com.lop.budget.data.local.entity.CategoryEntity
import com.lop.budget.data.local.entity.DebtEntity
import com.lop.budget.data.local.entity.GoalEntity
import com.lop.budget.data.local.entity.TagEntity
import com.lop.budget.domain.model.RecurrenceFrequency
import com.lop.budget.domain.model.TransactionStatus
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.ui.common.TestTags
import com.lop.budget.ui.components.CircleIcon
import com.lop.budget.ui.components.FilledField
import com.lop.budget.ui.components.FloatingCard
import com.lop.budget.ui.components.LopSwitch
import com.lop.budget.ui.components.PillTag
import com.lop.budget.ui.components.SectionTitle
import com.lop.budget.ui.components.SelectorRow
import com.lop.budget.ui.theme.LopTheme
import com.lop.budget.util.Format
import com.lop.budget.util.IconMapper

@Composable
fun MainSection(
    form: TransactionForm,
    onSetType: (TransactionType) -> Unit,
    onSetAmount: (String) -> Unit,
    onSetTitle: (String) -> Unit,
    onSetStatus: (TransactionStatus) -> Unit,
) {
    SectionTitle(stringResource(R.string.tx_section_main))
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
                    tag = TestTags.TX_EDIT_TYPE_EXPENSE,
                    color = LopTheme.extended.expense,
                    modifier = Modifier.weight(1f)
                ) { onSetType(TransactionType.EXPENSE) }

                TypeSegment(
                    label = stringResource(R.string.tx_type_income),
                    selected = form.type == TransactionType.INCOME,
                    tag = TestTags.TX_EDIT_TYPE_INCOME,
                    color = LopTheme.extended.income,
                    modifier = Modifier.weight(1f)
                ) { onSetType(TransactionType.INCOME) }
            }

            // Amount
            FilledField(
                label = stringResource(R.string.tx_amount_label),
                value = form.amountInput,
                onValueChange = onSetAmount,
                keyboardType = KeyboardType.Decimal,
                modifier = Modifier.testTag(TestTags.TX_EDIT_FIELD_AMOUNT),
                textStyle = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
                leading = { Text(if (form.type == TransactionType.EXPENSE) "−" else "+", style = MaterialTheme.typography.displaySmall) },
                trailing = { Text("€", style = MaterialTheme.typography.titleLarge) }
            )

            // Title
            FilledField(
                label = stringResource(R.string.tx_name_label),
                value = form.title,
                onValueChange = onSetTitle,
                keyboardType = KeyboardType.Text,
                modifier = Modifier.testTag(TestTags.TX_EDIT_FIELD_TITLE),
                textStyle = MaterialTheme.typography.bodyLarge,
            )

            Spacer(Modifier.height(8.dp))

            // Status Toggle
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.tx_detail_mark_as_paid),
                    style = MaterialTheme.typography.bodyLarge
                )
                LopSwitch(
                    checked = form.status == TransactionStatus.PAID,
                    onCheckedChange = { isChecked -> 
                        onSetStatus(if (isChecked) TransactionStatus.PAID else TransactionStatus.PLANNED)
                    },
                    modifier = Modifier.testTag(TestTags.TRANSACTION_DETAIL_TOGGLE_PAID)
                )
            }
        }
    }
}

@Composable
fun ClassificationSection(
    form: TransactionForm,
    categories: List<CategoryEntity>,
    accounts: List<AccountEntity>,
    goals: List<GoalEntity>,
    debts: List<DebtEntity>,
    tags: List<TagEntity>,
    onOpenCategory: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenGoal: () -> Unit,
    onOpenDebt: () -> Unit,
    onOpenTags: () -> Unit,
) {
    SectionTitle(stringResource(R.string.tx_section_classification))
    FloatingCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val selectedCat = categories.find { it.id == form.categoryId }
            SelectorRow(
                label = stringResource(R.string.tx_category_label),
                value = selectedCat?.name ?: stringResource(R.string.tx_select_category),
                icon = selectedCat?.let { IconMapper.get(it.icon) } ?: Icons.Filled.Category,
                iconTint = selectedCat?.let { Color(it.colorArgb) },
                onClick = onOpenCategory,
                modifier = Modifier.testTag(TestTags.TX_EDIT_FIELD_CATEGORY)
            )

            val selectedAcc = accounts.find { it.id == form.accountId }
            SelectorRow(
                label = stringResource(R.string.tx_account_label),
                value = selectedAcc?.name ?: stringResource(R.string.tx_select_account),
                onClick = onOpenAccount,
                trailingChevron = true,
                modifier = Modifier.testTag(TestTags.TX_EDIT_FIELD_ACCOUNT)
            )

            if (form.type == TransactionType.EXPENSE) {
                val selectedGoal = goals.find { it.id == form.linkedGoalId }
                SelectorRow(
                    label = stringResource(R.string.tx_linked_goal_label),
                    value = selectedGoal?.name ?: stringResource(R.string.tx_no_goal_linked),
                    icon = selectedGoal?.let { IconMapper.get(it.icon) } ?: Icons.Default.Add,
                    onClick = onOpenGoal,
                    modifier = Modifier.testTag(TestTags.TX_EDIT_FIELD_GOAL)
                )

                val selectedDebt = debts.find { it.id == form.linkedDebtId }
                SelectorRow(
                    label = stringResource(R.string.tx_linked_debt_label),
                    value = selectedDebt?.name ?: stringResource(R.string.tx_no_debt_linked),
                    icon = selectedDebt?.let { IconMapper.get(it.icon) } ?: Icons.Default.Add,
                    onClick = onOpenDebt,
                    modifier = Modifier.testTag(TestTags.TX_EDIT_FIELD_DEBT)
                )
            }

            val selectedTags = tags.filter { it.id in form.tagIds }
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(onClick = onOpenTags)
                    .testTag(TestTags.TX_EDIT_FIELD_TAGS),
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

@Composable
fun OptionalSection(
    form: TransactionForm,
    onSetNote: (String) -> Unit,
    onOpenDate: () -> Unit,
) {
    SectionTitle(stringResource(R.string.tx_section_optional))
    FloatingCard {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            FilledField(
                label = stringResource(R.string.tx_notes_label),
                value = form.note,
                onValueChange = onSetNote,
                keyboardType = KeyboardType.Text,
                textStyle = MaterialTheme.typography.bodyLarge,
                minLines = 2
            )

            SelectorRow(
                label = stringResource(R.string.tx_date_label),
                value = Format.fullDate(form.date),
                icon = Icons.Default.DateRange,
                onClick = onOpenDate,
                modifier = Modifier.testTag(TestTags.TX_EDIT_FIELD_DATE)
            )
        }
    }
}

@Composable
fun RecurrenceSection(
    form: TransactionForm,
    onSetInterval: (Int) -> Unit,
    onToggleDow: (Int) -> Unit,
    onSetEndDate: (Long?) -> Unit,
    onSetMaxOccurrences: (Int?) -> Unit,
    onOpenFrequency: () -> Unit,
    onOpenEndDate: () -> Unit,
) {
    SectionTitle(stringResource(R.string.tx_repeat_label))
    FloatingCard(modifier = Modifier.testTag(TestTags.TX_EDIT_BLOCK_RECURRENCE)) {
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
                TextButton(
                    onClick = { showAdvanced = !showAdvanced },
                    modifier = Modifier.testTag(TestTags.TX_EDIT_BTN_ADVANCED)
                ) {
                    Text(if (showAdvanced) "Moins" else stringResource(R.string.tx_repeat_advanced))
                    Icon(
                        if (showAdvanced) Icons.Default.KeyboardArrowDown else Icons.Default.ChevronRight,
                        null
                    )
                }
            }

            // Frequency Selector
            val frequencyLabel = when (form.frequency) {
                RecurrenceFrequency.NONE -> stringResource(R.string.tx_repeat_none)
                RecurrenceFrequency.DAILY -> stringResource(R.string.tx_repeat_daily)
                RecurrenceFrequency.WEEKLY -> stringResource(R.string.tx_repeat_weekly)
                RecurrenceFrequency.MONTHLY -> stringResource(R.string.tx_repeat_monthly)
                RecurrenceFrequency.YEARLY -> stringResource(R.string.tx_repeat_yearly)
            }

            SelectorRow(
                label = stringResource(R.string.tx_repeat_label),
                value = frequencyLabel,
                icon = Icons.Default.EventRepeat,
                onClick = onOpenFrequency,
                modifier = Modifier.testTag(TestTags.TX_EDIT_FIELD_FREQUENCY)
            )

            AnimatedVisibility(visible = showAdvanced && form.frequency != RecurrenceFrequency.NONE) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    // Interval
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.tx_repeat_every))
                        Spacer(Modifier.width(8.dp))
                        NumberField(
                            value = form.interval,
                            onValue = { it?.takeIf { v -> v > 0 }?.let(onSetInterval) },
                            modifier = Modifier.testTag(TestTags.TX_EDIT_FIELD_INTERVAL)
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
                            val days = stringArrayResource(R.array.tx_days_short)
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
                                            text = days.getOrElse(day - 1) { "" },
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

                    RadioRow(
                        selected = form.endDate == null && form.maxOccurrences == null,
                        onClick = {
                            onSetEndDate(null)
                            onSetMaxOccurrences(null)
                        }
                    ) {
                        Text(stringResource(R.string.tx_repeat_ends_never))
                    }

                    RadioRow(
                        selected = form.endDate != null,
                        onClick = onOpenEndDate
                    ) {
                        Text(stringResource(R.string.tx_repeat_ends_on))
                        Spacer(Modifier.width(8.dp))
                        TextButton(
                            onClick = onOpenEndDate,
                            modifier = Modifier.testTag("tx_edit_recurrence_end_date_btn")
                    ) {
                        Text(Format.fullDate(form.endDate ?: form.date))
                    }
                    }

                    RadioRow(
                        selected = form.maxOccurrences != null,
                        onClick = { onSetMaxOccurrences(12) }
                    ) {
                        Text(stringResource(R.string.tx_repeat_ends_after))
                        Spacer(Modifier.width(8.dp))
                        NumberField(
                            value = form.maxOccurrences,
                            onValue = onSetMaxOccurrences
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.tx_repeat_occurrences_label))
                    }
                }
            }
        }
    }
}

@Composable
private fun TypeSegment(
    label: String,
    selected: Boolean,
    tag: String,
    color: Color,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val text = if (selected && BuildConfig.DEBUG) "$label ✅" else label
    Surface(
        modifier = modifier
            .fillMaxHeight()
            .clickable(onClick = onClick)
            .testTag(tag),
        color = if (selected) color.copy(alpha = 0.15f) else Color.Transparent,
        shape = RoundedCornerShape(10.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) color else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
            )
        }
    }
}

@Composable
private fun NumberField(
    value: Int?,
    onValue: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember(value) { mutableStateOf(value?.toString() ?: "") }
    OutlinedTextField(
        value = text,
        onValueChange = { 
            text = it
            onValue(it.toIntOrNull())
        },
        modifier = modifier
            .width(80.dp)
            .height(50.dp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        textStyle = MaterialTheme.typography.bodyLarge.copy(textAlign = TextAlign.Center),
        shape = RoundedCornerShape(8.dp)
    )
}

@Composable
private fun RadioRow(
    selected: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        content()
    }
}

package com.lop.budget.ui.screens.transaction

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lop.budget.R
import com.lop.budget.domain.model.RecurrenceFrequency
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.ui.components.CircleIcon
import com.lop.budget.ui.components.HapticIntent
import com.lop.budget.ui.components.clickableNoRipple
import com.lop.budget.ui.components.pressScaleClickable
import com.lop.budget.ui.theme.LopTheme
import com.lop.budget.util.IconMapper

/**
 * Material expressive, lisible, ergonomique.
 * - proportions inspirées de Home (padding 20.dp + header léger)
 * - bottom gradient similaire à Home
 * - progressive disclosure pour la récurrence
 */
@OptIn(ExperimentalMaterial3Api::class)
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

    // creation flow: open category picker first
    var showCategorySheet by remember { mutableStateOf(false) }
    var showAccountSheet by remember { mutableStateOf(false) }
    var showTagsSheet by remember { mutableStateOf(false) }

    val ext = LopTheme.extended
    val accent = if (form.type == TransactionType.INCOME) ext.income else ext.expense
    val typeCategories = categories.filter { it.type == form.type }

    val canSave = form.amount > 0.0 && form.categoryId != null && form.accountId != null

    LaunchedEffect(vm.isEditing, categories.size) {
        if (!vm.isEditing && categories.isNotEmpty() && form.categoryId == null) {
            showCategorySheet = true
        }
    }

    val listState = rememberLazyListState()
    val showTopBarDivider by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            // OneUI-ish: gradient header instead of an opaque block.
            // Opaque near the bottom (behind the appbar content), fading upwards.
            Box(
                modifier = Modifier
                    .fillMaxWidth()

            ) {
                CenterAlignedTopAppBar(
                    title = {
                        Text(
                            if (vm.isEditing) stringResource(R.string.tx_edit_title) else stringResource(R.string.tx_new_title),
                            fontWeight = FontWeight.SemiBold,
                        )
                    },
                    navigationIcon = {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.close),
                            modifier = Modifier
                                .padding(start = 12.dp)
                                .size(26.dp)
                                .clickableNoRipple(onBack),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = MaterialTheme.colorScheme.onBackground,
                    ),
                    // FIX: avoid double inset in edge-to-edge.
                    windowInsets = WindowInsets(0, 0, 0, 0),
                )

                // When scrolling, just show a subtle outline (no heavy blocks)
                if (showTopBarDivider) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
                    )
                }
            }
        },
        bottomBar = {
            // Keep the background container transparent; only the button is solid.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                MaterialTheme.colorScheme.background.copy(alpha = 0.55f),
                                MaterialTheme.colorScheme.background,
                            )
                        )
                    )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 14.dp)
                        .background(Color.Transparent)
                    ,
                ) {
                    Button(
                        onClick = { vm.save(onDone = onBack) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        enabled = canSave,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = accent,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    ) {
                        Text(
                            if (vm.isEditing) stringResource(R.string.save) else stringResource(R.string.create),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }

                    if (!canSave) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.tx_required_fields_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    TypeSegment(
                        label = stringResource(R.string.tx_type_expense),
                        selected = form.type == TransactionType.EXPENSE,
                        color = ext.expense,
                        modifier = Modifier.weight(1f),
                    ) { vm.setType(TransactionType.EXPENSE) }

                    TypeSegment(
                        label = stringResource(R.string.tx_type_income),
                        selected = form.type == TransactionType.INCOME,
                        color = ext.income,
                        modifier = Modifier.weight(1f),
                    ) { vm.setType(TransactionType.INCOME) }
                }
            }

            item { SectionTitle(stringResource(R.string.tx_section_main)) }

            item {
                ExpressiveCard {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        FilledField(
                            label = stringResource(R.string.tx_amount_label),
                            value = form.amountInput.takeIf { it != "0" } ?: "",
                            onValueChange = { newValue ->
                                val filtered =
                                    newValue.filter { it.isDigit() || it == ',' || it == '.' }
                                val normalized = filtered.replace(',', '.')
                                if (normalized.count { it == '.' } <= 1 && normalized.length <= 12) {
                                    vm.setAmountRaw(normalized)
                                }
                            },
                            leading = {
                                Text(
                                    text = if (form.type == TransactionType.INCOME) "+" else "−",
                                    color = accent,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                )
                            },
                            trailing = {
                                Text(
                                    text = "€",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            keyboardType = KeyboardType.Decimal,
                            textStyle = MaterialTheme.typography.headlineMedium.copy(
                                color = accent,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.End,
                            ),
                        )

                        var showDatePicker by remember { mutableStateOf(false) }
                        val dateFormatter = java.time.format.DateTimeFormatter.ofPattern(
                            "dd MMMM yyyy",
                            java.util.Locale.getDefault(),
                        )
                        val formattedDate = java.time.Instant.ofEpochMilli(form.date)
                            .atZone(java.time.ZoneId.systemDefault())
                            .toLocalDate()
                            .format(dateFormatter)

                        if (showDatePicker) {
                            val state =
                                rememberDatePickerState(initialSelectedDateMillis = form.date)
                            androidx.compose.material3.DatePickerDialog(
                                onDismissRequest = { showDatePicker = false },
                                confirmButton = {
                                    androidx.compose.material3.TextButton(onClick = {
                                        state.selectedDateMillis?.let { vm.setDate(it) }
                                        showDatePicker = false
                                    }) { Text(stringResource(R.string.ok)) }
                                },
                                dismissButton = {
                                    androidx.compose.material3.TextButton(onClick = {
                                        showDatePicker = false
                                    }) {
                                        Text(stringResource(R.string.cancel))
                                    }
                                },
                            ) {
                                androidx.compose.material3.DatePicker(state = state)
                            }
                        }

                        SelectorRow(
                            label = stringResource(R.string.tx_date_label),
                            value = formattedDate,
                            icon = Icons.Filled.DateRange,
                            onClick = { showDatePicker = true },
                        )

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
                ExpressiveCard {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        val selectedCat = typeCategories.find { it.id == form.categoryId }
                        SelectorRow(
                            label = stringResource(R.string.tx_category_label),
                            value = selectedCat?.name
                                ?: stringResource(R.string.tx_select_category),
                            icon = selectedCat?.let { IconMapper.get(it.icon) }
                                ?: Icons.Filled.KeyboardArrowDown,
                            iconTint = selectedCat?.let { Color(it.colorArgb) },
                            onClick = { showCategorySheet = true },
                        )

                        val selectedAcc = accounts.find { it.id == form.accountId }
                        SelectorRow(
                            label = stringResource(R.string.tx_account_label),
                            value = selectedAcc?.name ?: stringResource(R.string.tx_select_account),
                            onClick = { showAccountSheet = true },
                            trailingChevron = true,
                        )

                        val selectedTags = tags.filter { it.id in form.tagIds }
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .clickable { showTagsSheet = true },
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            border = BorderStroke(
                                1.dp,
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)
                            ),
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
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                } else {
                                    androidx.compose.foundation.layout.FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        selectedTags.forEach { tag ->
                                            com.lop.budget.ui.components.PillTag(
                                                text = tag.name,
                                                color = Color(tag.colorArgb),
                                            )
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
                ExpressiveCard {
                    FilledField(
                        label = stringResource(R.string.tx_notes_label),
                        value = form.note,
                        onValueChange = vm::setNote,
                        keyboardType = KeyboardType.Text,
                        textStyle = MaterialTheme.typography.bodyLarge,
                        minLines = 3,
                    )
                }
            }

            item { SectionTitle(stringResource(R.string.tx_repeat_label)) }

            item {
                ExpressiveCard {
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
    }

    if (showCategorySheet) {
        CategoryBottomSheet(
            title = stringResource(R.string.tx_category_sheet_title),
            categories = typeCategories,
            selectedId = form.categoryId,
            onSelect = { id ->
                vm.setCategory(id)
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
            onDismiss = { showAccountSheet = false },
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
            onDismiss = { showTagsSheet = false },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun ExpressiveCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 2.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.10f)),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            content()
        }
    }
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
        modifier = modifier.pressScaleClickable(intent = HapticIntent.Selection, onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = if (selected) color.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(
            1.dp,
            if (selected) color.copy(alpha = 0.35f) else MaterialTheme.colorScheme.outline.copy(
                alpha = 0.14f
            )
        ),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) color else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 12.dp),
        )
    }
}

@Composable
private fun FilledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType,
    textStyle: androidx.compose.ui.text.TextStyle,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    minLines: Int = 1,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.10f)),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (leading != null) {
                    Box(Modifier.padding(end = 10.dp)) { leading() }
                }

                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.weight(1f),
                    textStyle = textStyle,
                    keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                    singleLine = minLines == 1,
                    minLines = minLines,
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                    ),
                )

                if (trailing != null) {
                    Box(Modifier.padding(start = 10.dp)) { trailing() }
                }
            }
        }
    }
}

@Composable
private fun SelectorRow(
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    iconTint: Color? = null,
    trailingChevron: Boolean = true,
    onClick: () -> Unit,
    dropdown: (@Composable () -> Unit)? = null,
) {
    Box {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .clickable(onClick = onClick)
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.10f),
                    RoundedCornerShape(16.dp)
                ),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (icon != null) {
                    CircleIcon(
                        icon = icon,
                        tint = iconTint ?: MaterialTheme.colorScheme.onSurfaceVariant,
                        background = (iconTint ?: MaterialTheme.colorScheme.onSurfaceVariant).copy(
                            alpha = 0.12f
                        ),
                        size = 34.dp,
                    )
                    Spacer(Modifier.width(12.dp))
                }

                Column(Modifier.weight(1f)) {
                    Text(
                        label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        value,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                if (trailingChevron) {
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        dropdown?.invoke()
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
    var expandedAdvanced by remember { mutableStateOf(false) }

    var expandedFreq by remember { mutableStateOf(false) }
    val freqs = listOf(
        RecurrenceFrequency.NONE to stringResource(R.string.tx_repeat_none),
        RecurrenceFrequency.DAILY to stringResource(R.string.tx_repeat_daily),
        RecurrenceFrequency.WEEKLY to stringResource(R.string.tx_repeat_weekly),
        RecurrenceFrequency.MONTHLY to stringResource(R.string.tx_repeat_monthly),
        RecurrenceFrequency.YEARLY to stringResource(R.string.tx_repeat_yearly),
    )
    val selectedFreqLabel = freqs.find { it.first == form.frequency }?.second
        ?: stringResource(R.string.tx_repeat_none)

    SelectorRow(
        label = stringResource(R.string.tx_repeat_label),
        value = selectedFreqLabel,
        icon = Icons.Filled.Repeat,
        onClick = { expandedFreq = true },
        dropdown = {
            DropdownMenu(
                expanded = expandedFreq,
                onDismissRequest = { expandedFreq = false },
                modifier = Modifier.fillMaxWidth(0.92f)
            ) {
                freqs.forEach { (freq, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            onSetFrequency(freq)
                            expandedFreq = false
                        }
                    )
                }
            }
        },
    )

    if (form.frequency == RecurrenceFrequency.NONE) return

    Spacer(Modifier.height(8.dp))

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.tx_repeat_every),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.width(10.dp))
        OutlinedTextField(
            value = form.interval.toString(),
            onValueChange = { onSetInterval(it.filter { c -> c.isDigit() }.toIntOrNull() ?: 1) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(90.dp),
            shape = RoundedCornerShape(12.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            stringResource(R.string.tx_repeat_intervals),
            style = MaterialTheme.typography.bodyMedium
        )
    }

    Spacer(Modifier.height(8.dp))
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .pressScaleClickable(intent = HapticIntent.Tap, pressedScale = 0.99f) {
                expandedAdvanced = !expandedAdvanced
            },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.tx_repeat_advanced),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.rotate(if (expandedAdvanced) 180f else 0f),
            )
        }
    }

    AnimatedVisibility(visible = expandedAdvanced) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(top = 10.dp)
        ) {
            if (form.frequency == RecurrenceFrequency.WEEKLY) {
                Text(
                    stringResource(R.string.tx_repeat_days_of_week),
                    style = MaterialTheme.typography.labelLarge
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val days =
                        listOf("L" to 1, "M" to 2, "M" to 3, "J" to 4, "V" to 5, "S" to 6, "D" to 7)
                    days.forEach { (lbl, num) ->
                        val sel = num in form.daysOfWeek
                        Surface(
                            shape = CircleShape,
                            color = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier
                                .size(40.dp)
                                .pressScaleClickable(intent = HapticIntent.Selection) {
                                    onToggleDow(
                                        num
                                    )
                                },
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    lbl,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (sel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            Text(
                stringResource(R.string.tx_repeat_ends),
                style = MaterialTheme.typography.labelLarge
            )

            val endPolicy = when {
                form.endDate != null -> "date"
                form.maxOccurrences != null -> "occ"
                else -> "never"
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = endPolicy == "never",
                    onClick = { onSetEndDate(null); onSetMaxOccurrences(null) })
                Text(
                    stringResource(R.string.tx_repeat_ends_never),
                    modifier = Modifier.clickable { onSetEndDate(null); onSetMaxOccurrences(null) })
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = endPolicy == "date",
                    onClick = { onSetEndDate(form.date + 86400000L * 30); })
                Text(stringResource(R.string.tx_repeat_ends_on), modifier = Modifier.clickable {
                    if (endPolicy != "date") onSetEndDate(form.date + 86400000L * 30)
                })
                Spacer(Modifier.width(8.dp))
                if (endPolicy == "date") {
                    var showEndDatePicker by remember { mutableStateOf(false) }
                    if (showEndDatePicker) {
                        val endState = rememberDatePickerState(
                            initialSelectedDateMillis = form.endDate ?: System.currentTimeMillis()
                        )
                        androidx.compose.material3.DatePickerDialog(
                            onDismissRequest = { showEndDatePicker = false },
                            confirmButton = {
                                androidx.compose.material3.TextButton(onClick = {
                                    endState.selectedDateMillis?.let { onSetEndDate(it) }
                                    showEndDatePicker = false
                                }) { Text(stringResource(R.string.ok)) }
                            },
                            dismissButton = {
                                androidx.compose.material3.TextButton(onClick = {
                                    showEndDatePicker = false
                                }) { Text(stringResource(R.string.cancel)) }
                            },
                        ) {
                            androidx.compose.material3.DatePicker(state = endState)
                        }
                    }

                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.clickable { showEndDatePicker = true }
                    ) {
                        val endFormatted = java.time.Instant.ofEpochMilli(
                            form.endDate ?: System.currentTimeMillis()
                        )
                            .atZone(java.time.ZoneId.systemDefault())
                            .toLocalDate()
                            .format(
                                java.time.format.DateTimeFormatter.ofPattern(
                                    "dd MMM yyyy",
                                    java.util.Locale.getDefault()
                                )
                            )
                        Text(
                            endFormatted,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = endPolicy == "occ", onClick = { onSetMaxOccurrences(10) })
                Text(stringResource(R.string.tx_repeat_ends_after), modifier = Modifier.clickable {
                    if (endPolicy != "occ") onSetMaxOccurrences(10)
                })
                Spacer(Modifier.width(8.dp))
                if (endPolicy == "occ") {
                    OutlinedTextField(
                        value = form.maxOccurrences?.toString() ?: "",
                        onValueChange = {
                            val n = it.filter { c -> c.isDigit() }.toIntOrNull()
                            if (n != null && n > 0) onSetMaxOccurrences(n)
                        },
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
private fun CategoryBottomSheet(
    title: String,
    categories: List<com.lop.budget.data.local.entity.CategoryEntity>,
    selectedId: Long?,
    onSelect: (Long) -> Unit,
    onCreate: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState =
        androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)

    androidx.compose.material3.ModalBottomSheet(
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
            if (categories.isEmpty()) {
                Text(
                    stringResource(R.string.tx_no_categories),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            categories.forEach { cat ->
                val selected = cat.id == selectedId
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .pressScaleClickable(
                            intent = HapticIntent.Selection,
                            pressedScale = 0.98f
                        ) { onSelect(cat.id) },
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
                        val c = Color(cat.colorArgb)
                        CircleIcon(
                            icon = IconMapper.get(cat.icon),
                            tint = c,
                            background = c.copy(alpha = 0.14f),
                            size = 38.dp,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                cat.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                if (cat.type == TransactionType.INCOME) stringResource(R.string.tx_type_income) else stringResource(
                                    R.string.tx_type_expense
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (selected) {
                            Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }

            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onCreate() }
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        stringResource(R.string.tx_create_category),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(Modifier.height(18.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountBottomSheet(
    title: String,
    accounts: List<com.lop.budget.data.local.entity.AccountEntity>,
    selectedId: Long?,
    onSelect: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)

    androidx.compose.material3.ModalBottomSheet(
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
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)

            if (accounts.isEmpty()) {
                Text(stringResource(R.string.tx_no_accounts), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            accounts.forEach { acc ->
                val selected = acc.id == selectedId
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .pressScaleClickable(intent = HapticIntent.Selection, pressedScale = 0.98f) { onSelect(acc.id) },
                    color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.10f))
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Simple avatar (first letter) to keep it clean
                        val letter = acc.name.firstOrNull()?.uppercase() ?: "A"
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)),
                            modifier = Modifier.size(38.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(letter, fontWeight = FontWeight.Bold)
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(acc.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        if (selected) {
                            Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
        }
    }
}

// Tags sheet kept as-is (existing UI).
@OptIn(
    ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)
@Composable
private fun TagsBottomSheet(
    tags: List<com.lop.budget.data.local.entity.TagEntity>,
    selectedTagIds: Set<Long>,
    onToggleTag: (Long) -> Unit,
    onCreateTag: (String, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState =
        androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var newTagName by remember { mutableStateOf("") }

    val colors = listOf(
        Color(0xFFE53935), Color(0xFFD81B60), Color(0xFF8E24AA), Color(0xFF5E35B1),
        Color(0xFF3949AB), Color(0xFF1E88E5), Color(0xFF039BE5), Color(0xFF00ACC1),
        Color(0xFF00897B), Color(0xFF00838F), Color(0xFF43A047), Color(0xFF2E7D32),
        Color(0xFF7CB342), Color(0xFFC0CA33), Color(0xFFFDD835), Color(0xFFFFB300),
        Color(0xFFFB8C00), Color(0xFFF4511E), Color(0xFF6D4C41), Color(0xFF546E7A)
    )
    var selectedColor by remember { mutableStateOf(colors[0]) }

    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        scrimColor = Color.Black.copy(alpha = 0.55f),
    ) {
        Column(
            Modifier
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .fillMaxWidth()
        ) {
            Text(
                stringResource(R.string.tx_tags_sheet_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                stringResource(R.string.tx_tags_sheet_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))

            if (tags.isNotEmpty()) {
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    tags.forEach { tag ->
                        val isSelected = selectedTagIds.contains(tag.id)
                        val color = Color(tag.colorArgb)
                        Surface(
                            modifier = Modifier.clickable { onToggleTag(tag.id) },
                            shape = CircleShape,
                            color = if (isSelected) color.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant.copy(
                                alpha = 0.5f
                            ),
                            border = if (isSelected) BorderStroke(
                                1.dp,
                                color
                            ) else BorderStroke(1.dp, Color.Transparent)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(color)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    tag.name,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (isSelected) color else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }

            Text(
                stringResource(R.string.tx_tags_create_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = newTagName,
                onValueChange = { newTagName = it },
                label = { Text(stringResource(R.string.tx_tags_name_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )
            Spacer(Modifier.height(16.dp))

            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(colors) { color ->
                    val isSelected = selectedColor == color
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(color)
                            .clickable { selectedColor = color }
                            .border(
                                if (isSelected) 3.dp else 0.dp,
                                MaterialTheme.colorScheme.onSurface,
                                CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isSelected) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))

            Button(
                onClick = {
                    onCreateTag(newTagName, selectedColor.toArgb())
                    newTagName = ""
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                enabled = newTagName.isNotBlank() && (selectedTagIds.size < 3),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(stringResource(R.string.add), fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}
